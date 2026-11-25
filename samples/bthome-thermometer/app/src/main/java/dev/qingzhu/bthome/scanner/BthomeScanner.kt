// SPDX-License-Identifier: MIT
package dev.qingzhu.bthome.scanner

import android.Manifest
import androidx.annotation.RequiresPermission
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.os.SystemClock
import dev.qingzhu.bthome.protocol.BTHOME_SERVICE_UUID
import dev.qingzhu.bthome.protocol.Obj
import dev.qingzhu.bthome.protocol.parse
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** 某台设备最新的一帧读数。timestamp 用 elapsedRealtime，只用来算「多久没更新」。 */
data class SensorReading(
    val address: String,
    val rssi: Int,
    val name: String?,
    val measurements: Map<Obj, Float>,
    val timestamp: Long = SystemClock.elapsedRealtime(),
    val online: Boolean = true,
)

/**
 * 只收 BTHome v2 广播的扫描器。
 *
 * 过滤交给蓝牙控制器做：App 不会被无关广播唤醒；系统还会替我们把 Service Data 重组好，
 * [ScanResult.scanRecord] 的 getServiceData() 直接返回 UUID 之后的字节，不用自己剥 AD 结构。
 * 带过滤的扫描在 Android 8.1+ 熄屏时不会被系统暂停。
 */
class BthomeScanner(private val context: Context) {

    companion object {
        /** 写成 16 位 UUID 的完整形式，ScanFilter 只比对前 16 位。 */
        val BTHOME_UUID: ParcelUuid = ParcelUuid.fromString(BTHOME_SERVICE_UUID)

        /** onScanFailed 的错误码，和 ScanCallback 常量一一对应。 */
        const val FAILED_ALREADY_STARTED = 1
        const val FAILED_TOO_FREQUENTLY = 6

        fun describe(code: Int): String = when (code) {
            ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "已经有一个扫描在跑（$code）"
            ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "扫描注册失败，重试或重启蓝牙（$code）"
            ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "蓝牙栈内部错误（$code）"
            ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "本机不支持这种扫描参数（$code）"
            ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES -> "硬件扫描资源不足，先停掉其它扫描（$code）"
            ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY -> "扫描启停太频繁，被系统限流（$code）"
            else -> "扫描失败（$code）"
        }
    }

    private val adapter
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val scanner
        @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
        get() = adapter?.bluetoothLeScanner

    private val _readings = MutableSharedFlow<SensorReading>(extraBufferCapacity = 64)
    val readings: SharedFlow<SensorReading> = _readings.asSharedFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _errorCode = MutableStateFlow<Int?>(null)
    val errorCode: StateFlow<Int?> = _errorCode.asStateFlow()

    @Volatile
    private var running = false

    val isRunning: Boolean get() = running

    /**
     * 开始扫描。返回 false 表示没启动起来（蓝牙没开），原因写在 [error] 里。
     * 权限由调用方先申请好。
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun start(): Boolean {
        val s = scanner
        if (s == null) {
            running = false
            _errorCode.value = null
            _error.value = "蓝牙未开启或本机不支持 BLE"
            return false
        }
        if (running) return true

        running = true
        _error.value = null
        _errorCode.value = null
        val filter = android.bluetooth.le.ScanFilter.Builder()
            .setServiceUuid(BTHOME_UUID)
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        s.startScan(listOf(filter), settings, callback)
        return true
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun stop() {
        if (!running) return
        running = false
        try {
            scanner?.stopScan(callback)
        } catch (_: SecurityException) {
            // 权限被收回：底层扫描会随进程的蓝牙句柄一起失效，这里只需要保证界面状态正确
        }
    }

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = emit(result)

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { emit(it) }
        }

        override fun onScanFailed(errorCode: Int) {           // 必须处理，否则界面会一直转圈
            running = false
            _errorCode.value = errorCode
            _error.value = describe(errorCode)
        }
    }

    private fun emit(r: ScanResult) {
        val bytes = r.scanRecord?.getServiceData(BTHOME_UUID) ?: return   // 不是 BTHome v2
        val frame = parse(bytes) ?: return                               // 版本不对 / 没有可解对象 → 忽略
        if (frame.encrypted) return                                      // 本课不处理加密
        val name = r.scanRecord?.deviceName ?: runCatching { r.device.name }.getOrNull()
        _readings.tryEmit(
            SensorReading(
                address = r.device.address,
                rssi = r.rssi,
                name = name,
                measurements = frame.measurements.associate { it.obj to it.value },
            )
        )
    }
}
