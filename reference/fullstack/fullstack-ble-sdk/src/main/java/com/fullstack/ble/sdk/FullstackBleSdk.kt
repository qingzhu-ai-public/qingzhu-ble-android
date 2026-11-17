package com.fullstack.ble.sdk

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import com.fullstack.ble.sdk.internal.CallbackDispatcher
import com.fullstack.ble.sdk.scan.FullstackBleScanner
import java.util.concurrent.ConcurrentHashMap

class FullstackBleSdk(context: Context) {
    private val applicationContext = context.applicationContext
    private val bluetoothManager = applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter = bluetoothManager.adapter
    private val dispatcher = CallbackDispatcher()
    private val scanner = FullstackBleScanner(adapter, dispatcher)
    private val connections = ConcurrentHashMap<String, FullstackBleConnection>()

    fun startScan(
        config: FullstackBleScanConfig = FullstackBleScanConfig(),
        listener: FullstackBleScanListener,
    ) {
        scanner.start(config, listener)
    }

    fun stopScan() = scanner.stop()

    @Synchronized
    @SuppressLint("MissingPermission")
    fun connect(
        device: FullstackBleDevice,
        config: FullstackBleConnectionConfig,
        listener: FullstackBleConnectionListener,
    ) {
        val localAdapter = adapter
        if (localAdapter == null) {
            dispatchConnectionError(device, listener, FullstackBleErrorCode.BLUETOOTH_UNAVAILABLE, "Bluetooth is unavailable")
            return
        }
        if (connections.containsKey(device.address)) {
            dispatchConnectionError(device, listener, FullstackBleErrorCode.ALREADY_CONNECTED, "A connection already exists for ${device.address}")
            return
        }
        try {
            if (!localAdapter.isEnabled) {
                dispatchConnectionError(device, listener, FullstackBleErrorCode.BLUETOOTH_DISABLED, "Bluetooth is disabled")
                return
            }
            val remoteDevice = localAdapter.getRemoteDevice(device.address)
            lateinit var connection: FullstackBleConnection
            connection = FullstackBleConnection(
                device.address,
                applicationContext,
                remoteDevice,
                config,
                listener,
                dispatcher,
            ) { terminated -> connections.remove(device.address, terminated) }
            connections[device.address] = connection
            connection.start()
        } catch (securityException: SecurityException) {
            dispatchConnectionError(device, listener, FullstackBleErrorCode.SECURITY_EXCEPTION, "Bluetooth permission is missing", securityException)
        } catch (throwable: Throwable) {
            dispatchConnectionError(device, listener, FullstackBleErrorCode.CONNECTION_FAILED, "Unable to create connection", throwable)
        }
    }

    fun disconnectAll() {
        connections.values.forEach { it.disconnect() }
    }

    fun close() {
        scanner.close()
        disconnectAll()
    }

    private fun dispatchConnectionError(
        device: FullstackBleDevice,
        listener: FullstackBleConnectionListener,
        code: FullstackBleErrorCode,
        message: String,
        cause: Throwable? = null,
    ) {
        dispatcher.dispatch {
            listener.onDisconnected(FullstackBleException(code, "[$code] ${device.address}: $message", cause = cause))
        }
    }
}
