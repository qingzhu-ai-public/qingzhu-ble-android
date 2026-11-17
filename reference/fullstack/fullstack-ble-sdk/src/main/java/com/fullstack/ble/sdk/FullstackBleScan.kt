package com.fullstack.ble.sdk

import java.util.UUID

data class FullstackBleDevice(
    val address: String,
    val name: String?,
    val rssi: Int,
)

data class FullstackBleScanConfig(
    val serviceUuid: UUID? = null,
    val deviceNamePrefix: String? = null,
    val scanMode: Int = 2,
    val timeoutMs: Long = 0L,
) {
    init {
        require(timeoutMs >= 0L) { "timeoutMs must be >= 0" }
    }
}

interface FullstackBleScanListener {
    fun onScanStarted() = Unit
    fun onDeviceFound(device: FullstackBleDevice) = Unit
    fun onScanStopped() = Unit
    fun onError(error: FullstackBleException) = Unit
}
