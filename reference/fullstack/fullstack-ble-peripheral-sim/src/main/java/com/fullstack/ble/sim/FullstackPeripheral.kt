package com.fullstack.ble.sim

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid

@SuppressLint("MissingPermission")
internal class FullstackPeripheral(
    context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onLog(message: String)
        fun onRunningChanged(running: Boolean)
    }

    private val applicationContext = context.applicationContext
    private val manager = applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = manager.adapter
    private var advertiser: BluetoothLeAdvertiser? = null
    private val main = Handler(Looper.getMainLooper())
    private val connectedDevices = mutableMapOf<String, BluetoothDevice>()
    private val subscribedAddresses = mutableSetOf<String>()
    private val notificationQueue = ArrayDeque<Notification>()
    private var currentNotification: Notification? = null
    private var server: BluetoothGattServer? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null
    private var started = false
    private var running = false
    private var lastResponse = ByteArray(0)

    fun start() {
        main.post { startInternal() }
    }

    fun stop() {
        main.post { stopInternal() }
    }

    private fun startInternal() {
        if (started) {
            listener.onLog("Peripheral is already started")
            return
        }
        val localAdapter = adapter
        if (localAdapter == null || !localAdapter.isEnabled) {
            listener.onLog("Bluetooth is unavailable or disabled")
            listener.onRunningChanged(false)
            return
        }
        advertiser = localAdapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            listener.onLog("BLE advertising is not supported")
            listener.onRunningChanged(false)
            return
        }
        try {
            started = true
            server = manager.openGattServer(applicationContext, serverCallback)
            if (server == null) {
                listener.onLog("openGattServer returned null")
                stopInternal()
                return
            }
            val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            val write = BluetoothGattCharacteristic(
                WRITE_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE,
            )
            val notify = BluetoothGattCharacteristic(
                NOTIFY_UUID,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ,
            )
            notify.addDescriptor(
                BluetoothGattDescriptor(
                    CCCD_UUID,
                    BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
                ),
            )
            service.addCharacteristic(write)
            service.addCharacteristic(notify)
            notifyCharacteristic = notify
            if (server?.addService(service) != true) {
                listener.onLog("addService returned false")
                stopInternal()
                return
            }
            listener.onLog("GATT service registration started")
        } catch (securityException: SecurityException) {
            listener.onLog("Bluetooth permission missing: ${securityException.message}")
            stopInternal()
        } catch (throwable: Throwable) {
            listener.onLog("Unable to start peripheral: ${throwable.message}")
            stopInternal()
        }
    }

    private fun startAdvertising() {
        try {
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(true)
                .build()
            val data = AdvertiseData.Builder()
                .addServiceUuid(ParcelUuid(SERVICE_UUID))
                .build()
            advertiser?.startAdvertising(settings, data, advertiseCallback)
        } catch (throwable: Throwable) {
            listener.onLog("Unable to start advertising: ${throwable.message}")
            stopInternal()
        }
    }

    private fun stopInternal() {
        val wasStarted = started || running
        started = false
        running = false
        try {
            advertiser?.stopAdvertising(advertiseCallback)
        } catch (_: Throwable) {
        }
        try {
            server?.close()
        } catch (_: Throwable) {
        }
        advertiser = null
        server = null
        notifyCharacteristic = null
        notificationQueue.clear()
        currentNotification = null
        connectedDevices.clear()
        subscribedAddresses.clear()
        if (wasStarted) listener.onLog("Peripheral stopped")
        listener.onRunningChanged(false)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            main.post {
                if (!started) return@post
                running = true
                listener.onRunningChanged(true)
                listener.onLog("Connectable advertising started")
            }
        }

        override fun onStartFailure(errorCode: Int) {
            main.post {
                listener.onLog("Advertising failed with code $errorCode")
                stopInternal()
            }
        }
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            main.post {
                if (!started) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    listener.onLog("Service registration failed with status $status")
                    stopInternal()
                    return@post
                }
                listener.onLog("GATT service ready: ${service.uuid}")
                startAdvertising()
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            main.post {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    connectedDevices[device.address] = device
                    listener.onLog("Central connected: ${device.address}")
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    connectedDevices.remove(device.address)
                    subscribedAddresses.remove(device.address)
                    notificationQueue.removeAll { it.device.address == device.address }
                    if (currentNotification?.device?.address == device.address) currentNotification = null
                    listener.onLog("Central disconnected: ${device.address}, status=$status")
                    pumpNotifications()
                }
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            main.post {
                connectedDevices[device.address] = device
                val valid = descriptor.uuid == CCCD_UUID &&
                    descriptor.characteristic.uuid == NOTIFY_UUID &&
                    !preparedWrite && offset == 0 && value.size == 2
                val subscribed = valid && value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                val unsubscribed = valid && value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
                val status = if (subscribed || unsubscribed) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE
                if (subscribed) subscribedAddresses.add(device.address)
                if (unsubscribed) subscribedAddresses.remove(device.address)
                if (responseNeeded) sendResponse(device, requestId, status, offset, value)
                listener.onLog("CCCD write from ${device.address}: subscribed=$subscribed")
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            main.post {
                if (characteristic.uuid != WRITE_UUID || preparedWrite || offset != 0 || value.size > MAX_COMMAND_BYTES) {
                    if (responseNeeded) sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, value)
                    listener.onLog("Rejected invalid command from ${device.address}: ${value.size} bytes")
                    return@post
                }
                if (responseNeeded) sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                val received = String(value, Charsets.UTF_8)
                lastResponse = "OK".toByteArray(Charsets.UTF_8)
                listener.onLog("Received command from ${device.address}: $received")
                notifyResponse(lastResponse)
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            main.post {
                if (characteristic.uuid == NOTIFY_UUID) {
                    sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, lastResponse)
                } else {
                    sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                }
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            main.post {
                val current = currentNotification
                if (current == null || current.device.address != device.address) return@post
                listener.onLog("Notify sent to ${device.address}, status=$status")
                currentNotification = null
                pumpNotifications()
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            main.post { listener.onLog("MTU changed for ${device.address}: $mtu") }
        }
    }

    private fun notifyResponse(value: ByteArray) {
        val targets = subscribedAddresses.mapNotNull(connectedDevices::get)
        if (targets.isEmpty()) {
            listener.onLog("No Notify subscriber; response is available through read")
            return
        }
        targets.forEach { device -> notificationQueue.addLast(Notification(device, value.copyOf())) }
        pumpNotifications()
    }

    private fun pumpNotifications() {
        if (currentNotification != null) return
        val next = notificationQueue.removeFirstOrNull() ?: return
        val localServer = server ?: return
        val characteristic = notifyCharacteristic ?: return
        if (!subscribedAddresses.contains(next.device.address)) {
            pumpNotifications()
            return
        }
        currentNotification = next
        val accepted = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                localServer.notifyCharacteristicChanged(next.device, characteristic, false, next.value) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = next.value
                @Suppress("DEPRECATION")
                localServer.notifyCharacteristicChanged(next.device, characteristic, false)
            }
        } catch (throwable: Throwable) {
            listener.onLog("Notify failed: ${throwable.message}")
            false
        }
        if (!accepted) {
            listener.onLog("Unable to queue Notify for ${next.device.address}")
            currentNotification = null
            main.post { pumpNotifications() }
        }
    }

    private fun sendResponse(
        device: BluetoothDevice,
        requestId: Int,
        status: Int,
        offset: Int,
        value: ByteArray?,
    ) {
        try {
            server?.sendResponse(device, requestId, status, offset, value)
        } catch (throwable: Throwable) {
            listener.onLog("sendResponse failed: ${throwable.message}")
        }
    }

    private data class Notification(val device: BluetoothDevice, val value: ByteArray)

    companion object {
        const val MAX_COMMAND_BYTES = 20
        val SERVICE_UUID = java.util.UUID.fromString("f5a50001-6f7d-4c2f-9f6e-5a41e6f10000")!!
        val WRITE_UUID = java.util.UUID.fromString("f5a50002-6f7d-4c2f-9f6e-5a41e6f10000")!!
        val NOTIFY_UUID = java.util.UUID.fromString("f5a50003-6f7d-4c2f-9f6e-5a41e6f10000")!!
        val CCCD_UUID = java.util.UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")!!
    }
}
