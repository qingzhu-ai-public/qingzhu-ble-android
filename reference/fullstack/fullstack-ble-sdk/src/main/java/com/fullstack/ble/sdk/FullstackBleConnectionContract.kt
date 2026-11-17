package com.fullstack.ble.sdk

import java.util.UUID

data class FullstackBleConnectionConfig(
    val serviceUuid: UUID,
    val writeCharacteristicUuid: UUID,
    val notifyCharacteristicUuid: UUID? = null,
    val connectTimeoutMs: Long = 15_000L,
    val operationTimeoutMs: Long = 10_000L,
) {
    init {
        require(connectTimeoutMs > 0L) { "connectTimeoutMs must be > 0" }
        require(operationTimeoutMs > 0L) { "operationTimeoutMs must be > 0" }
    }
}

enum class FullstackBleConnectionState {
    CONNECTING,
    DISCOVERING_SERVICES,
    READY,
    CLOSED,
}

interface FullstackBleConnectionListener {
    fun onStateChanged(state: FullstackBleConnectionState) = Unit
    fun onConnected(newConnection: FullstackBleConnection) = Unit
    fun onDataReceived(data: ByteArray) = Unit
    fun onDisconnected(error: FullstackBleException?) = Unit
}

fun interface FullstackBleOperationCallback {
    fun onComplete(error: FullstackBleException?)
}

interface FullstackBleWriteCallback {
    fun onProgress(sentBytes: Int, totalBytes: Int) = Unit
    fun onComplete(error: FullstackBleException?)
}
