// SPDX-License-Identifier: MIT
package dev.qingzhu.bthome.advertiser

import android.Manifest
import androidx.annotation.RequiresPermission
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import dev.qingzhu.bthome.protocol.BTHOME_SERVICE_UUID
import dev.qingzhu.bthome.protocol.Measurement
import dev.qingzhu.bthome.protocol.encode
import dev.qingzhu.bthome.protocol.legacyAdvBytes
import dev.qingzhu.bthome.protocol.legacyScanResponseBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow


/**
 * 让这台手机变成一台 BTHome 传感器。
 *
 * 主包只放 Service Data，设备名挪到扫描响应，两边各有 31 字节预算，互不挤占。
 * 读数变了用 setAdvertisingData 热更新，不停广播。
 * 勾了“允许连接”还会同时开一个 GATT 服务（[BthomeGattServer]）。
 *
 * 所有方法都应在主线程调用；系统回调也在主线程回来，所以 updating / dirty 不用加锁。
 */
class BthomeAdvertiser(private val context: Context) {

    companion object {
        private val BTHOME_UUID: ParcelUuid = ParcelUuid.fromString(BTHOME_SERVICE_UUID)
        /** onAdvertisingSetStarted 等回调的 status：0 表示成功（框架没给这个常量起名）。 */
        private const val ADVERTISE_SUCCESS = 0

        /** 扫描响应 31 字节，去掉长度和类型各 1 字节，名字最多 29 字节。 */
        const val MAX_NAME_BYTES = 29

        fun describe(status: Int): String = when (status) {
            ADVERTISE_SUCCESS -> "成功"
            AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "数据超过 31 字节预算"
            AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "系统的广播句柄用完了"
            AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "已经有一个广播在跑"
            AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "蓝牙栈内部错误"
            AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "本机不支持这种广播参数"
            else -> "错误码 $status"
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var advertiser: BluetoothLeAdvertiser? = null
    private var set: AdvertisingSet? = null
    private var gattJob: Job? = null
    private var gatt: BthomeGattServer? = null

    private var current: List<Measurement> = emptyList()
    private var connectable = false

    /** 我们改过本机蓝牙名称的话，记下原值，停广播时还回去。 */
    private var originalName: String? = null

    /** setAdvertisingData 是异步的：上一次还没回调就再设，新数据会被丢掉。 */
    private var updating = false
    private var dirty = false

    private val _status = MutableStateFlow("未广播")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _serviceData = MutableStateFlow(ByteArray(0))
    val serviceData: StateFlow<ByteArray> = _serviceData.asStateFlow()

    private val _effectiveName = MutableStateFlow("")
    val effectiveName: StateFlow<String> = _effectiveName.asStateFlow()

    private val _gattState = MutableStateFlow("未开启")
    val gattState: StateFlow<String> = _gattState.asStateFlow()

    @Volatile
    var isRunning: Boolean = false
        private set

    private val adapter
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    /**
     * 开始广播。返回 false 表示没起来（本机不支持），原因在 [status]。
     * [listener] 只在 connectable = true 时使用：主端写进来的指令通过它回调。
     */
    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT])
    fun start(connectable: Boolean, listener: BthomeGattServer.Listener?): Boolean {
        val adv = adapter?.bluetoothLeAdvertiser ?: run {
            _status.value = "蓝牙未开启，或本机不支持 BLE 广播"
            return false
        }
        stopInternal()

        advertiser = adv
        isRunning = true
        this.connectable = connectable

        if (connectable && listener != null) {
            val server = BthomeGattServer(context)
            gatt = server
            server.start(listener)
            gattJob = scope.launch { server.state.collect { _gattState.value = it } }
        } else {
            _gattState.value = "未开启"
        }

        if (_effectiveName.value.isEmpty()) applyName("")   // 还没设过名字：用本机蓝牙名称
        val data = buildData() ?: run {
            isRunning = false
            return false
        }
        val params = AdvertisingSetParameters.Builder()
            .setConnectable(connectable)                           // 纯广播就不可连；开了连接路线才可连
            .setScannable(true)                                    // 要发扫描响应，必须可被扫描
            .setLegacyMode(true)                                   // 兼容 4.x 接收端
            .setInterval(AdvertisingSetParameters.INTERVAL_LOW)    // ≈ 100 ms
            .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_MEDIUM)
            .build()
        _status.value = "启动中…"
        adv.startAdvertisingSet(params, data, buildScanResponse(), null, null, callback)
        return true
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    fun stop() {
        stopInternal()
        _status.value = "已停止"
    }

    /** 新读数到了：没在广播时只更新界面预览；在广播时热更新 + 推给已订阅的主端。 */
    fun submit(ms: List<Measurement>) {
        current = ms
        val bytes = encode(ms)
        _serviceData.value = bytes
        if (!isRunning) return
        gatt?.notify(bytes)
        requestUpdate()
    }


    /**
     * 设置广播出去的设备名，返回实际生效的名字。
     * 系统只认本机蓝牙名称，所以这里改的是 adapter name，停广播时还原。
     */
    fun setDeviceName(name: String): String {
        val effective = applyName(name)
        if (isRunning && set != null) {
            set?.setScanResponseData(buildScanResponse())
        }
        return effective
    }

    private fun requestUpdate() {
        val s = set ?: return
        if (!isRunning) return
        if (updating) {              // 上一次还没回调：记下“有新数据”，回调来了再补发最新的
            dirty = true
            return
        }
        val data = buildData() ?: return
        updating = true
        dirty = false
        s.setAdvertisingData(data)
    }

    /** 超过 31 字节就不发，并把原因写到状态里。 */
    private fun buildData(): AdvertiseData? {
        val bytes = encode(current)
        val used = legacyAdvBytes(bytes)
        if (used > 31) {
            _status.value = "ADVERTISE_FAILED_DATA_TOO_LARGE：主包 $used/31 字节，减少对象或缩短数值范围"
            return null
        }
        return AdvertiseData.Builder()
            .addServiceData(BTHOME_UUID, bytes)
            .build()
    }

    private fun buildScanResponse(): AdvertiseData = AdvertiseData.Builder()
        .setIncludeDeviceName(_effectiveName.value.isNotEmpty())
        .build()

    private fun applyName(raw: String): String {
        val adapter = adapter ?: return raw
        val currentName = runCatching { adapter.name }.getOrNull()
        if (originalName == null) originalName = currentName
        val wanted = raw.trim().ifEmpty { currentName ?: "" }
        val effective = truncateUtf8(wanted, MAX_NAME_BYTES)
        runCatching { adapter.setName(effective) }
        _effectiveName.value = effective
        return effective
    }

    private fun restoreName() {
        val original = originalName ?: return
        runCatching { adapter?.setName(original) }
        originalName = null
    }

    private fun stopInternal() {
        gattJob?.cancel()
        gattJob = null
        runCatching { gatt?.close() }
        gatt = null
        _gattState.value = "未开启"

        set?.let { runCatching { advertiser?.stopAdvertisingSet(callback) } }
        set = null
        isRunning = false
        updating = false
        dirty = false
        restoreName()
    }

    private val callback = object : AdvertisingSetCallback() {
        override fun onAdvertisingSetStarted(advSet: AdvertisingSet?, txPower: Int, status: Int) {
            if (status != ADVERTISE_SUCCESS || advSet == null) {
                isRunning = false
                set = null
                _status.value = "启动失败：${describe(status)}"
                return
            }
            set = advSet
            _status.value = runningText()
        }

        override fun onAdvertisingSetStopped(advSet: AdvertisingSet) {
            set = null
        }

        override fun onAdvertisingDataSet(advSet: AdvertisingSet, status: Int) {
            updating = false
            if (status != ADVERTISE_SUCCESS) {
                _status.value = "主包更新失败：${describe(status)}"
                return
            }
            _status.value = runningText()
            if (dirty) requestUpdate()
        }

        override fun onScanResponseDataSet(advSet: AdvertisingSet, status: Int) {
            if (status != ADVERTISE_SUCCESS) {
                _status.value = "扫描响应更新失败：${describe(status)}"
            }
        }
    }

    private fun runningText(): String {
        val nameBytes = legacyScanResponseBytes(_effectiveName.value)
        val extra = if (connectable) " · 可连接" else ""
        return "广播中$extra · 主包 ${legacyAdvBytes(_serviceData.value)}/31 · 名字 $nameBytes/31"
    }

    /** 按 UTF-8 字节数截断，不会把一个汉字切成半个。 */
    private fun truncateUtf8(s: String, maxBytes: Int): String {
        var used = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val n = byteCount(cp)
            if (used + n > maxBytes) break
            used += n
            i += Character.charCount(cp)
        }
        return s.substring(0, i)
    }

    private fun byteCount(cp: Int): Int = when {
        cp < 0x80 -> 1
        cp < 0x800 -> 2
        cp < 0x10000 -> 3
        else -> 4
    }
}
