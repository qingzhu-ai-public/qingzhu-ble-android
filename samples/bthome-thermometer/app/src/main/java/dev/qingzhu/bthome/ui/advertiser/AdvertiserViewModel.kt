// SPDX-License-Identifier: MIT
package dev.qingzhu.bthome.ui.advertiser

import android.app.Application
import android.bluetooth.BluetoothAdapter
import androidx.annotation.RequiresPermission
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.qingzhu.bthome.advertiser.BthomeAdvertiser
import dev.qingzhu.bthome.advertiser.BthomeGattServer
import dev.qingzhu.bthome.advertiser.ManualSource
import dev.qingzhu.bthome.advertiser.ScriptSource
import dev.qingzhu.bthome.advertiser.WaveSource
import dev.qingzhu.bthome.protocol.Measurement
import dev.qingzhu.bthome.protocol.Obj
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import java.util.Locale

enum class SourceKind(val label: String, val detail: String) {
    WAVE("正弦波", "每秒变一次，看起来最像真房间"),
    MANUAL("手动滑杆", "拉到零下、拉到 0，验证符号扩展和零值"),
    SCRIPT("剧本", "-5.5 → 0 → 25.06 → 40 → -40，再发一次 105% 越界"),
}

/**
 * 从端页的状态。放在 ViewModel 里，是为了切到“主端”页时广播不停：
 * 一台手机也能同时广播和扫描，自发自收先跑通，再上两台手机验收。
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class AdvertiserViewModel(app: Application) : AndroidViewModel(app) {

    val advertiser = BthomeAdvertiser(app)
    val manual = ManualSource()

    val source = MutableStateFlow(SourceKind.WAVE)
    val battery = MutableStateFlow<Int?>(null)
    val connectable = MutableStateFlow(false)
    val deviceName = MutableStateFlow("")

    /** 主端最近一次经 GATT 写进来的指令，给界面显示一行提示。 */
    val remoteNote = MutableStateFlow<String?>(null)

    /** GATT 回调在蓝牙栈线程，这里统一切回主线程再动数据源。 */
    private val gattListener = object : BthomeGattServer.Listener {
        override fun onCommand(measurements: List<Measurement>) {
            viewModelScope.launch { applyRemoteCommand(measurements) }
        }
    }

    init {
        // 数据源 + 可选电量 → 合成一路读数交给广播器。换数据源不需要重启广播。
        viewModelScope.launch {
            source
                .flatMapLatest { kind ->
                    when (kind) {
                        SourceKind.WAVE -> WaveSource().readings
                        SourceKind.MANUAL -> manual.readings
                        SourceKind.SCRIPT -> ScriptSource().readings
                    }
                }
                .combine(battery) { ms, b ->
                    if (b == null) ms else ms + Measurement(Obj.BATTERY, b.toFloat())
                }
                .collect { advertiser.submit(it) }
        }
        // 名字边输入边应用，停顿 400ms 才真正改本机蓝牙名称，避免每个字符都打一次系统调用
        viewModelScope.launch {
            deviceName.debounce(400).collect { advertiser.setDeviceName(it) }
        }
    }

    @RequiresPermission(allOf = [
        android.Manifest.permission.BLUETOOTH_ADVERTISE,
        android.Manifest.permission.BLUETOOTH_CONNECT,
    ])
    fun start() {
        advertiser.setDeviceName(deviceName.value)
        advertiser.start(connectable.value, gattListener)
    }

    @RequiresPermission(android.Manifest.permission.BLUETOOTH_ADVERTISE)
    fun stop() = advertiser.stop()

    /** 广播期间切“允许连接”要重启广播：可连接性是广播参数，热更新改不了。 */
    @RequiresPermission(allOf = [
        android.Manifest.permission.BLUETOOTH_ADVERTISE,
        android.Manifest.permission.BLUETOOTH_CONNECT,
    ])
    fun setConnectable(value: Boolean) {
        connectable.value = value
        if (advertiser.isRunning) start()
    }

    private fun applyRemoteCommand(ms: List<Measurement>) {
        if (ms.isEmpty()) return
        for (m in ms) {
            when (m.obj) {
                Obj.TEMPERATURE -> manual.setTemperature(m.value)
                Obj.HUMIDITY -> manual.setHumidity(m.value)
                else -> Unit
            }
        }
        // 切到手动，界面上能直接看到被主端改成了多少
        source.value = SourceKind.MANUAL
        remoteNote.value = "收到主端指令：" + ms.joinToString("、") {
            String.format(Locale.US, "%s %.2f%s", it.obj.label, it.value, it.obj.unit)
        }
    }

    override fun onCleared() {
        // 权限可能已被收回，停不掉也不能让应用崩
        runCatching { advertiser.stop() }
    }

    companion object {
        /** 供界面判断“蓝牙是否可用”，不在这里申请权限。 */
        fun adapter(app: Application): BluetoothAdapter? =
            (app.getSystemService(android.content.Context.BLUETOOTH_SERVICE)
                as? android.bluetooth.BluetoothManager)?.adapter
    }
}
