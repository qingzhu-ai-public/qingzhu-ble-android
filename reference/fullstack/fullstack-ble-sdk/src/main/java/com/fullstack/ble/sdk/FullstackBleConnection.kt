package com.fullstack.ble.sdk

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import com.fullstack.ble.sdk.connection.PacketSplitter
import com.fullstack.ble.sdk.connection.SerialOperationQueue
import com.fullstack.ble.sdk.internal.CallbackDispatcher
import java.util.UUID

@SuppressLint("MissingPermission")
class FullstackBleConnection internal constructor(
    val address: String,
    private val context: Context,
    private val device: BluetoothDevice,
    private val config: FullstackBleConnectionConfig,
    private val listener: FullstackBleConnectionListener,
    private val dispatcher: CallbackDispatcher,
    private val onTerminated: (FullstackBleConnection) -> Unit,
) {
    @Volatile
    var state: FullstackBleConnectionState = FullstackBleConnectionState.CONNECTING
        private set

    val isReady: Boolean
        get() = state == FullstackBleConnectionState.READY

    private val worker = HandlerThread("fullstack-ble-$address")
    private val handler: Handler
    private val operations = SerialOperationQueue<GattOperation>()
    private var gatt: BluetoothGatt? = null
    private var attMtu = 23
    private var terminated = false
    private var connectTimeout: Runnable? = null

    init {
        worker.start()
        handler = Handler(worker.looper)
    }

    internal fun start() {
        handler.post {
            changeState(FullstackBleConnectionState.CONNECTING)
            try {
                val connectedGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                if (connectedGatt == null) {
                    terminate(FullstackBleException(FullstackBleErrorCode.CONNECTION_FAILED, "connectGatt returned null"))
                    return@post
                }
                gatt = connectedGatt
                val timeout = Runnable {
                    terminate(FullstackBleException(FullstackBleErrorCode.CONNECT_TIMEOUT, "Connection timed out"))
                }
                connectTimeout = timeout
                handler.postDelayed(timeout, config.connectTimeoutMs)
            } catch (securityException: SecurityException) {
                terminate(securityError("Bluetooth connect permission is missing", securityException))
            } catch (throwable: Throwable) {
                terminate(FullstackBleException(FullstackBleErrorCode.CONNECTION_FAILED, "Unable to connect", cause = throwable))
            }
        }
    }

    fun enableNotifications(callback: FullstackBleOperationCallback) {
        handler.post { enqueueOperation(NotifyOperation(callback)) }
    }

    fun requestMtu(mtu: Int, callback: FullstackBleOperationCallback) {
        if (mtu !in 23..517) {
            dispatcher.dispatch {
                callback.onComplete(FullstackBleException(FullstackBleErrorCode.INVALID_ARGUMENT, "MTU must be in 23..517"))
            }
            return
        }
        handler.post { enqueueOperation(MtuOperation(mtu, callback)) }
    }

    fun write(data: ByteArray, callback: FullstackBleWriteCallback) {
        if (data.isEmpty()) {
            dispatcher.dispatch {
                callback.onComplete(FullstackBleException(FullstackBleErrorCode.INVALID_ARGUMENT, "data must not be empty"))
            }
            return
        }
        val safeCopy = data.copyOf()
        handler.post { enqueueOperation(WriteOperation(safeCopy, callback)) }
    }

    fun disconnect() {
        handler.post { terminate(null) }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(callbackGatt: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                if (terminated || gatt !== callbackGatt) return@post
                when {
                    newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS -> {
                        changeState(FullstackBleConnectionState.DISCOVERING_SERVICES)
                        if (!callbackGatt.discoverServices()) {
                            terminate(FullstackBleException(FullstackBleErrorCode.SERVICE_DISCOVERY_FAILED, "discoverServices returned false"))
                        }
                    }
                    newState == BluetoothProfile.STATE_DISCONNECTED -> {
                        terminate(FullstackBleException(FullstackBleErrorCode.CONNECTION_FAILED, "Disconnected with status $status", status))
                    }
                    status != BluetoothGatt.GATT_SUCCESS -> {
                        terminate(FullstackBleException(FullstackBleErrorCode.CONNECTION_FAILED, "Connection failed with status $status", status))
                    }
                }
            }
        }

        override fun onServicesDiscovered(callbackGatt: BluetoothGatt, status: Int) {
            handler.post {
                if (terminated || gatt !== callbackGatt || state != FullstackBleConnectionState.DISCOVERING_SERVICES) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    terminate(FullstackBleException(FullstackBleErrorCode.SERVICE_DISCOVERY_FAILED, "Service discovery failed", status))
                    return@post
                }
                validateCharacteristics(callbackGatt)?.let { error ->
                    terminate(error)
                    return@post
                }
                connectTimeout?.let { handler.removeCallbacks(it) }
                connectTimeout = null
                changeState(FullstackBleConnectionState.READY)
                dispatcher.dispatch { listener.onConnected(this@FullstackBleConnection) }
            }
        }

        override fun onDescriptorWrite(callbackGatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            handler.post {
                if (terminated || gatt !== callbackGatt) return@post
                val operation = operations.current as? NotifyOperation ?: return@post
                if (descriptor.characteristic.uuid != config.notifyCharacteristicUuid) return@post
                val error = if (status == BluetoothGatt.GATT_SUCCESS) null else gattError("CCCD write failed", status)
                completeOperation(operation, error)
            }
        }

        override fun onMtuChanged(callbackGatt: BluetoothGatt, mtu: Int, status: Int) {
            handler.post {
                if (terminated || gatt !== callbackGatt) return@post
                if (status == BluetoothGatt.GATT_SUCCESS) attMtu = mtu
                val operation = operations.current as? MtuOperation ?: return@post
                val error = if (status == BluetoothGatt.GATT_SUCCESS) null else gattError("MTU request failed", status)
                completeOperation(operation, error)
            }
        }

        override fun onCharacteristicWrite(
            callbackGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            handler.post { handleWriteCallback(callbackGatt, characteristic, status) }
        }

        override fun onCharacteristicChanged(callbackGatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: ByteArray(0)
            handler.post { dispatchNotification(callbackGatt, characteristic.uuid, value) }
        }

        override fun onCharacteristicChanged(
            callbackGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handler.post { dispatchNotification(callbackGatt, characteristic.uuid, value) }
        }
    }

    private fun validateCharacteristics(callbackGatt: BluetoothGatt): FullstackBleException? {
        val service = callbackGatt.getService(config.serviceUuid)
            ?: return FullstackBleException(FullstackBleErrorCode.SERVICE_NOT_FOUND, "Service ${config.serviceUuid} was not found")
        service.getCharacteristic(config.writeCharacteristicUuid)
            ?: return FullstackBleException(FullstackBleErrorCode.CHARACTERISTIC_NOT_FOUND, "Write characteristic was not found")
        config.notifyCharacteristicUuid?.let { notifyUuid ->
            val characteristic = service.getCharacteristic(notifyUuid)
                ?: return FullstackBleException(FullstackBleErrorCode.CHARACTERISTIC_NOT_FOUND, "Notify characteristic was not found")
            characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG)
                ?: return FullstackBleException(FullstackBleErrorCode.DESCRIPTOR_NOT_FOUND, "CCCD was not found")
        }
        return null
    }

    private fun enqueueOperation(operation: GattOperation) {
        if (terminated) {
            failOperationImmediately(operation, FullstackBleException(FullstackBleErrorCode.CLOSED, "Connection is closed"))
            return
        }
        if (!isReady || gatt == null) {
            failOperationImmediately(operation, FullstackBleException(FullstackBleErrorCode.NOT_READY, "Connection is not ready"))
            return
        }
        operations.enqueue(operation)
        pumpOperations()
    }

    private fun pumpOperations() {
        if (terminated) return
        val operation = operations.startNext() ?: return
        armOperationTimeout(operation)
        try {
            when (operation) {
                is NotifyOperation -> startNotify(operation)
                is MtuOperation -> {
                    if (gatt?.requestMtu(operation.mtu) != true) {
                        completeOperation(operation, FullstackBleException(FullstackBleErrorCode.GATT_ERROR, "requestMtu returned false"))
                    }
                }
                is WriteOperation -> startWrite(operation)
            }
        } catch (securityException: SecurityException) {
            completeOperation(operation, securityError("Bluetooth permission is missing", securityException))
        } catch (throwable: Throwable) {
            completeOperation(operation, FullstackBleException(FullstackBleErrorCode.GATT_ERROR, "Unable to start GATT operation", cause = throwable))
        }
    }

    private fun startNotify(operation: NotifyOperation) {
        val notifyUuid = config.notifyCharacteristicUuid
        if (notifyUuid == null) {
            completeOperation(operation, FullstackBleException(FullstackBleErrorCode.INVALID_ARGUMENT, "No notify characteristic was configured"))
            return
        }
        val callbackGatt = gatt ?: return
        val characteristic = callbackGatt.getService(config.serviceUuid)?.getCharacteristic(notifyUuid)
        if (characteristic == null) {
            completeOperation(operation, FullstackBleException(FullstackBleErrorCode.CHARACTERISTIC_NOT_FOUND, "Notify characteristic was not found"))
            return
        }
        val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG)
        if (descriptor == null) {
            completeOperation(operation, FullstackBleException(FullstackBleErrorCode.DESCRIPTOR_NOT_FOUND, "CCCD was not found"))
            return
        }
        if (!callbackGatt.setCharacteristicNotification(characteristic, true)) {
            completeOperation(operation, FullstackBleException(FullstackBleErrorCode.GATT_ERROR, "Unable to enable local notifications"))
            return
        }
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            callbackGatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            callbackGatt.writeDescriptor(descriptor)
        }
        if (!started) {
            completeOperation(operation, FullstackBleException(FullstackBleErrorCode.GATT_ERROR, "writeDescriptor returned failure"))
        }
    }

    private fun startWrite(operation: WriteOperation) {
        operation.chunks = PacketSplitter.split(operation.data, (attMtu - 3).coerceAtLeast(1))
        operation.index = 0
        startWriteChunk(operation)
    }

    private fun startWriteChunk(operation: WriteOperation) {
        val callbackGatt = gatt ?: return
        val characteristic = callbackGatt.getService(config.serviceUuid)?.getCharacteristic(config.writeCharacteristicUuid)
        if (characteristic == null) {
            completeOperation(operation, FullstackBleException(FullstackBleErrorCode.CHARACTERISTIC_NOT_FOUND, "Write characteristic was not found"))
            return
        }
        val chunk = operation.chunks[operation.index]
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            callbackGatt.writeCharacteristic(
                characteristic,
                chunk,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            characteristic.value = chunk
            @Suppress("DEPRECATION")
            callbackGatt.writeCharacteristic(characteristic)
        }
        if (!started) {
            completeOperation(operation, FullstackBleException(FullstackBleErrorCode.GATT_ERROR, "writeCharacteristic returned failure"))
        }
    }

    private fun handleWriteCallback(
        callbackGatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        status: Int,
    ) {
        if (terminated || gatt !== callbackGatt || characteristic.uuid != config.writeCharacteristicUuid) return
        val operation = operations.current as? WriteOperation ?: return
        if (status != BluetoothGatt.GATT_SUCCESS) {
            completeOperation(operation, gattError("Characteristic write failed", status))
            return
        }
        operation.index += 1
        operation.sentBytes += operation.chunks[operation.index - 1].size
        val totalBytes = operation.data.size
        dispatcher.dispatch { operation.callback.onProgress(operation.sentBytes, totalBytes) }
        if (operation.index >= operation.chunks.size) {
            completeOperation(operation, null)
            return
        }
        armOperationTimeout(operation)
        startWriteChunk(operation)
    }

    private fun dispatchNotification(callbackGatt: BluetoothGatt, uuid: UUID, value: ByteArray) {
        if (terminated || gatt !== callbackGatt || uuid != config.notifyCharacteristicUuid) return
        val safeCopy = value.copyOf()
        dispatcher.dispatch { listener.onDataReceived(safeCopy) }
    }

    private fun armOperationTimeout(operation: GattOperation) {
        operation.timeout?.let { handler.removeCallbacks(it) }
        val timeout = Runnable {
            if (operations.current === operation && !terminated) {
                terminate(FullstackBleException(FullstackBleErrorCode.OPERATION_TIMEOUT, "GATT operation timed out; connection was reset"))
            }
        }
        operation.timeout = timeout
        handler.postDelayed(timeout, config.operationTimeoutMs)
    }

    private fun completeOperation(operation: GattOperation, error: FullstackBleException?) {
        if (!operations.complete(operation)) return
        operation.timeout?.let { handler.removeCallbacks(it) }
        when (operation) {
            is NotifyOperation -> dispatcher.dispatch { operation.callback.onComplete(error) }
            is MtuOperation -> dispatcher.dispatch { operation.callback.onComplete(error) }
            is WriteOperation -> dispatcher.dispatch { operation.callback.onComplete(error) }
        }
        if (!terminated) pumpOperations()
    }

    private fun failOperationImmediately(operation: GattOperation, error: FullstackBleException) {
        when (operation) {
            is NotifyOperation -> dispatcher.dispatch { operation.callback.onComplete(error) }
            is MtuOperation -> dispatcher.dispatch { operation.callback.onComplete(error) }
            is WriteOperation -> dispatcher.dispatch { operation.callback.onComplete(error) }
        }
    }

    private fun terminate(reason: FullstackBleException?) {
        if (terminated) return
        terminated = true
        connectTimeout?.let { handler.removeCallbacks(it) }
        connectTimeout = null
        val operationError = reason ?: FullstackBleException(FullstackBleErrorCode.CLOSED, "Connection was closed")
        operations.drain().forEach { operation ->
            operation.timeout?.let { handler.removeCallbacks(it) }
            failOperationImmediately(operation, operationError)
        }
        try {
            gatt?.disconnect()
        } catch (_: Throwable) {
        }
        try {
            gatt?.close()
        } catch (_: Throwable) {
        }
        gatt = null
        changeState(FullstackBleConnectionState.CLOSED)
        dispatcher.dispatch {
            listener.onDisconnected(reason)
            onTerminated(this@FullstackBleConnection)
        }
        worker.quitSafely()
    }

    private fun changeState(newState: FullstackBleConnectionState) {
        if (state == newState) return
        state = newState
        dispatcher.dispatch { listener.onStateChanged(newState) }
    }

    private fun securityError(message: String, cause: SecurityException) =
        FullstackBleException(FullstackBleErrorCode.SECURITY_EXCEPTION, message, cause = cause)

    private fun gattError(message: String, status: Int) =
        FullstackBleException(FullstackBleErrorCode.GATT_ERROR, "$message (status $status)", status)

    private abstract inner class GattOperation {
        var timeout: Runnable? = null
    }

    private inner class NotifyOperation(
        val callback: FullstackBleOperationCallback,
    ) : GattOperation()

    private inner class MtuOperation(
        val mtu: Int,
        val callback: FullstackBleOperationCallback,
    ) : GattOperation()

    private inner class WriteOperation(
        val data: ByteArray,
        val callback: FullstackBleWriteCallback,
    ) : GattOperation() {
        var chunks: List<ByteArray> = emptyList()
        var index: Int = 0
        var sentBytes: Int = 0
    }

    private companion object {
        val CLIENT_CHARACTERISTIC_CONFIG: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
