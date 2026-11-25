// SPDX-License-Identifier: MIT
package dev.qingzhu.bthome.advertiser

import android.Manifest
import androidx.annotation.RequiresPermission
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import dev.qingzhu.bthome.protocol.Measurement
import dev.qingzhu.bthome.protocol.parse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 从端的 GATT 服务（教学扩展）。
 *
 * 只有一个特征，三种能力：
 * - NOTIFY：每来一帧新读数就推给主端，字节和广播里的一模一样；
 * - WRITE：主端写进来一帧 BTHome 数据，解析后回调给界面去改数据源；
 * - CCCD：主端写 0x0001 表示订阅，写完立刻把当前这一帧推过去。
 *
 * 回调跑在蓝牙栈的线程上，不是主线程；需要更新界面的由调用方切线程。
 */
class BthomeGattServer(private val context: Context) {

    interface Listener {
        /** 主端写入了一帧，已经按广播同一套规则解析好。 */
        fun onCommand(measurements: List<Measurement>)

        /** 主端完成了订阅。 */
        fun onSubscribed() {}
    }

    private val _state = MutableStateFlow("未开启")
    val state: StateFlow<String> = _state.asStateFlow()

    private var server: BluetoothGattServer? = null
    private var characteristic: BluetoothGattCharacteristic? = null

    @Volatile
    private var central: BluetoothDevice? = null

    @Volatile
    private var subscribed = false

    @Volatile
    private var listener: Listener? = null

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun start(onCommand: Listener) {
        listener = onCommand
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val s = manager?.openGattServer(context, callback)
        if (s == null) {
            _state.value = "GATT 服务打开失败"
            return
        }
        server = s

        val ch = BluetoothGattCharacteristic(
            TeachingGatt.CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        ch.addDescriptor(
            BluetoothGattDescriptor(
                TeachingGatt.CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_WRITE,
            )
        )
        characteristic = ch

        val service = BluetoothGattService(
            TeachingGatt.SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY,
        )
        service.addCharacteristic(ch)
        s.addService(service)
    }

    /** 给已订阅的主端推一帧。没开服务、没连上、没订阅，都是静默跳过。 */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun notify(frame: ByteArray) {
        val s = server ?: return
        val d = central ?: return
        val c = characteristic ?: return
        if (!subscribed) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            s.notifyCharacteristicChanged(d, c, false, frame)
        } else {
            @Suppress("DEPRECATION")
            c.value = frame
            @Suppress("DEPRECATION")
            s.notifyCharacteristicChanged(d, c, false)
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun close() {
        server?.close()
        server = null
        characteristic = null
        central = null
        subscribed = false
        _state.value = "已关闭"
    }

    private val callback = object : BluetoothGattServerCallback() {

        override fun onServiceAdded(status: Int, service: android.bluetooth.BluetoothGattService) {
            _state.value =
                if (status == BluetoothGatt.GATT_SUCCESS) "等待主端连接"
                else "服务注册失败（status=$status）"
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    central = device
                    _state.value = "主端已连接，等待订阅"
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (central == device) central = null
                    subscribed = false
                    // 可连接广播在被连上后会停，断开后系统会自动恢复广播
                    _state.value = "主端已断开，恢复广播"
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
            if (descriptor.uuid != TeachingGatt.CCCD_UUID) {
                if (responseNeeded) server?.sendResponse(
                    device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, byteArrayOf(),
                )
                return
            }
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, byteArrayOf())
            subscribed = !preparedWrite && value.contentEquals(
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE,
            )
            _state.value = if (subscribed) "主端已订阅，正在推送" else "主端已连接，未订阅"
            if (subscribed) listener?.onSubscribed()
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
            val ok = characteristic.uuid == TeachingGatt.CHARACTERISTIC_UUID && !preparedWrite && offset == 0
            if (responseNeeded) {
                server?.sendResponse(
                    device, requestId,
                    if (ok) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, byteArrayOf(),
                )
            }
            if (ok) {
                parse(value)?.let { listener?.onCommand(it.measurements) }
            }
        }

        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, byteArrayOf())
        }
    }
}
