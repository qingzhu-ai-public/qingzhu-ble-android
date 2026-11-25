// SPDX-License-Identifier: MIT
package dev.qingzhu.bthome.scanner

import android.Manifest
import androidx.annotation.RequiresPermission
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
import android.os.Looper
import dev.qingzhu.bthome.advertiser.TeachingGatt
import dev.qingzhu.bthome.protocol.BthomeFrame
import dev.qingzhu.bthome.protocol.parse
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 连接路线（教学扩展，不是 BTHome 标准的一部分）。
 *
 * 流程就是 B4、B5、B6 讲的那条线：connectGatt → discoverServices → 找特征 → 写 CCCD 开通知。
 * 帧格式和广播完全一样，所以 notify 回来的字节直接丢给 [parse]，一个解析器两处复用。
 */
class BthomeGattClient(private val context: Context) {

    companion object {

        /** GATT 连接回调兜底：系统不回调时不能让界面永远停在“连接中”。 */
        private const val CONNECT_TIMEOUT_MS = 20_000L

        /** 连接、服务发现、订阅全部完成后的状态文案；界面上“能不能发”以它为准。 */
        private const val READY = "已连接 · 订阅成功，可收发"
    }

    private val handler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow("未连接")
    val state: StateFlow<String> = _state.asStateFlow()

    private val _frames = MutableSharedFlow<BthomeFrame>(extraBufferCapacity = 16)
    val frames: SharedFlow<BthomeFrame> = _frames.asSharedFlow()

    private val _lastFrame = MutableStateFlow<BthomeFrame?>(null)
    val lastFrame: StateFlow<BthomeFrame?> = _lastFrame.asStateFlow()

    /** 正在连接 / 已连接的那台设备的地址；断开后回到 null。 */
    private val _target = MutableStateFlow<String?>(null)
    val target: StateFlow<String?> = _target.asStateFlow()

    private var gatt: BluetoothGatt? = null
    private var characteristic: BluetoothGattCharacteristic? = null
    private var timeout: Runnable? = null

    val isConnected: Boolean
        get() = _state.value == READY

    private val timeoutRunnable = Runnable {
        fail("连接超时（${CONNECT_TIMEOUT_MS / 1000}s 没有回调），已断开")
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun connect(device: BluetoothDevice) {
        close()
        _state.value = "连接中…"
        _target.value = device.address
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        timeout = timeoutRunnable.also { handler.postDelayed(it, CONNECT_TIMEOUT_MS) }
    }

    /** 发送一帧。返回 false 表示还没就绪或这次写入没被系统接受。 */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun write(frame: ByteArray): Boolean {
        val c = characteristic
        val g = gatt
        if (c == null || g == null || _state.value != READY) return false
        val accepted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(c, frame, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            c.value = frame
            @Suppress("DEPRECATION")
            g.writeCharacteristic(c)
        }
        if (accepted) _state.value = "已发送 ${frame.size} 字节，等待从端…"
        return accepted
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun disconnect() {
        cancelTimeout()
        val g = gatt ?: run { _state.value = "未连接"; return }
        _target.value = null
        try {
            if (_state.value == READY) g.disconnect()
        } finally {
            close()
            _state.value = "已断开"
        }
    }

    private fun fail(message: String) {
        cancelTimeout()
        close()
        _target.value = null
        _state.value = message
    }

    private fun close() {
        cancelTimeout()
        characteristic = null
        gatt?.close()
        gatt = null
    }

    private fun cancelTimeout() {
        timeout?.let { handler.removeCallbacks(it) }
        timeout = null
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    cancelTimeout()
                    _state.value = "已连接，发现服务…"
                    handler.postDelayed(timeoutRunnable, CONNECT_TIMEOUT_MS)
                    if (!g.discoverServices()) fail("发起服务发现失败")
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    val wasConnected = _state.value == READY
                    close()
                    _target.value = null
                    _state.value = if (wasConnected) "从端断开（status=$status）" else "连接失败（status=$status）"
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            cancelTimeout()
            if (status != BluetoothGatt.GATT_SUCCESS) return fail("发现服务失败（status=$status）")
            val c = g.getService(TeachingGatt.SERVICE_UUID)
                ?.getCharacteristic(TeachingGatt.CHARACTERISTIC_UUID)
                ?: return fail("这台设备没有开放连接路线（从端要勾选“允许主端连接”）")
            characteristic = c

            val cccd = c.getDescriptor(TeachingGatt.CCCD_UUID)
                ?: return fail("特征没有 CCCD，无法订阅通知")
            // 顺序不能反：先让本地栈收通知，再往设备的 CCCD 写 0x0001
            if (!g.setCharacteristicNotification(c, true)) return fail("开启本地通知失败")
            val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                    BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
            if (!ok) fail("写 CCCD 失败")
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid != TeachingGatt.CCCD_UUID) return
            if (status != BluetoothGatt.GATT_SUCCESS) return fail("写 CCCD 失败（status=$status）")
            _state.value = READY
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _state.value = "写入失败（status=$status），连接仍在"
                return
            }
            _state.value = "写入成功，等从端回通知"
        }

        // Android 13+
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) = deliver(value)

        // Android 13 以下
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            deliver(characteristic.value ?: return)
        }
    }

    private fun deliver(bytes: ByteArray) {
        val frame = parse(bytes) ?: return
        _lastFrame.value = frame
        _frames.tryEmit(frame)
        _state.value = READY
    }
}
