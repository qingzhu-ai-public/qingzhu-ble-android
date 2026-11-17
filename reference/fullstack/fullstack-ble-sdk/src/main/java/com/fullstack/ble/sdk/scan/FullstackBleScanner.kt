package com.fullstack.ble.sdk.scan

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.Handler
import android.os.ParcelUuid
import com.fullstack.ble.sdk.FullstackBleDevice
import com.fullstack.ble.sdk.FullstackBleErrorCode
import com.fullstack.ble.sdk.FullstackBleException
import com.fullstack.ble.sdk.FullstackBleScanConfig
import com.fullstack.ble.sdk.FullstackBleScanListener
import com.fullstack.ble.sdk.internal.CallbackDispatcher

@SuppressLint("MissingPermission")
internal class FullstackBleScanner(
    private val adapter: BluetoothAdapter?,
    private val dispatcher: CallbackDispatcher,
) {
    private val lock = Any()
    private var currentSession: ScanSession? = null
    private var closed = false

    fun start(config: FullstackBleScanConfig, listener: FullstackBleScanListener) {
        synchronized(lock) {
            if (closed) {
                dispatchError(listener, FullstackBleErrorCode.CLOSED, "Scanner is closed")
                return
            }
            if (currentSession?.active == true) {
                dispatchError(listener, FullstackBleErrorCode.SCAN_ALREADY_ACTIVE, "A scan is already active")
                return
            }
        }

        val localAdapter = adapter
        if (localAdapter == null) {
            dispatchError(listener, FullstackBleErrorCode.BLUETOOTH_UNAVAILABLE, "Bluetooth is unavailable")
            return
        }

        try {
            if (!localAdapter.isEnabled) {
                dispatchError(listener, FullstackBleErrorCode.BLUETOOTH_DISABLED, "Bluetooth is disabled")
                return
            }
            val scanner = localAdapter.bluetoothLeScanner
            if (scanner == null) {
                dispatchError(listener, FullstackBleErrorCode.BLUETOOTH_UNAVAILABLE, "BluetoothLeScanner is unavailable")
                return
            }

            val filters = config.serviceUuid?.let {
                listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(it)).build())
            }.orEmpty()
            val settings = ScanSettings.Builder().setScanMode(config.scanMode).build()
            lateinit var session: ScanSession
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    handleResult(session, config, result)
                }

                override fun onBatchScanResults(results: MutableList<ScanResult>) {
                    results.forEach { handleResult(session, config, it) }
                }

                override fun onScanFailed(errorCode: Int) {
                    finish(
                        session,
                        FullstackBleException(
                            FullstackBleErrorCode.SCAN_FAILED,
                            "Platform scan failed with code $errorCode",
                            errorCode,
                        ),
                    )
                }
            }
            session = ScanSession(scanner, callback, listener)
            synchronized(lock) {
                if (closed) {
                    dispatchError(listener, FullstackBleErrorCode.CLOSED, "Scanner is closed")
                    return
                }
                if (currentSession?.active == true) {
                    dispatchError(listener, FullstackBleErrorCode.SCAN_ALREADY_ACTIVE, "A scan is already active")
                    return
                }
                currentSession = session
            }

            scanner.startScan(filters, settings, callback)
            val remainsActive = synchronized(lock) { !closed && session.active && currentSession === session }
            if (!remainsActive) {
                try {
                    scanner.stopScan(callback)
                } catch (_: Throwable) {
                }
                return
            }
            dispatcher.dispatch { listener.onScanStarted() }
            if (config.timeoutMs > 0L) {
                val timeout = Runnable { finish(session, null) }
                session.timeout = timeout
                dispatcher.handler.postDelayed(timeout, config.timeoutMs)
            }
        } catch (securityException: SecurityException) {
            currentSession?.let { finish(it, null) }
            dispatchError(listener, FullstackBleErrorCode.SECURITY_EXCEPTION, "Bluetooth permission is missing", securityException)
        } catch (throwable: Throwable) {
            currentSession?.let { finish(it, null) }
            dispatchError(listener, FullstackBleErrorCode.SCAN_FAILED, "Unable to start scan", throwable)
        }
    }

    fun stop() {
        val session = synchronized(lock) { currentSession }
        if (session != null) finish(session, null)
    }

    fun close() {
        val session = synchronized(lock) {
            closed = true
            currentSession
        }
        if (session != null) finish(session, null)
    }

    private fun handleResult(session: ScanSession, config: FullstackBleScanConfig, result: ScanResult) {
        synchronized(lock) {
            if (!session.active || currentSession !== session) return
        }
        val name = result.scanRecord?.deviceName
        val prefix = config.deviceNamePrefix
        if (prefix != null && name?.startsWith(prefix) != true) return
        val device = FullstackBleDevice(result.device.address, name, result.rssi)
        dispatcher.dispatch { session.listener.onDeviceFound(device) }
    }

    private fun finish(session: ScanSession, error: FullstackBleException?) {
        val shouldFinish = synchronized(lock) {
            if (!session.active) return
            session.active = false
            if (currentSession === session) currentSession = null
            true
        }
        if (!shouldFinish) return
        session.timeout?.let { dispatcher.handler.removeCallbacks(it) }
        try {
            session.scanner.stopScan(session.callback)
        } catch (_: Throwable) {
        }
        error?.let { value -> dispatcher.dispatch { session.listener.onError(value) } }
        dispatcher.dispatch { session.listener.onScanStopped() }
    }

    private fun dispatchError(
        listener: FullstackBleScanListener,
        code: FullstackBleErrorCode,
        message: String,
        cause: Throwable? = null,
    ) {
        dispatcher.dispatch { listener.onError(FullstackBleException(code, message, cause = cause)) }
    }

    private class ScanSession(
        val scanner: BluetoothLeScanner,
        val callback: ScanCallback,
        val listener: FullstackBleScanListener,
    ) {
        var active: Boolean = true
        var timeout: Runnable? = null
    }
}
