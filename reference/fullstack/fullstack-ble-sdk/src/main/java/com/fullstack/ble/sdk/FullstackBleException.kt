package com.fullstack.ble.sdk

enum class FullstackBleErrorCode {
    BLUETOOTH_UNAVAILABLE,
    BLUETOOTH_DISABLED,
    SECURITY_EXCEPTION,
    SCAN_ALREADY_ACTIVE,
    SCAN_FAILED,
    INVALID_ARGUMENT,
    ALREADY_CONNECTED,
    CONNECT_TIMEOUT,
    CONNECTION_FAILED,
    SERVICE_DISCOVERY_FAILED,
    SERVICE_NOT_FOUND,
    CHARACTERISTIC_NOT_FOUND,
    DESCRIPTOR_NOT_FOUND,
    NOT_READY,
    OPERATION_TIMEOUT,
    GATT_ERROR,
    CLOSED,
}

class FullstackBleException(
    val code: FullstackBleErrorCode,
    message: String,
    val nativeStatus: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause)
