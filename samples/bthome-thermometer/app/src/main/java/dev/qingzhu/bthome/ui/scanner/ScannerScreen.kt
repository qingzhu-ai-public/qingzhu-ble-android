// SPDX-License-Identifier: MIT
package dev.qingzhu.bthome.ui.scanner

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import dev.qingzhu.bthome.R
import dev.qingzhu.bthome.protocol.Measurement
import dev.qingzhu.bthome.protocol.Obj
import dev.qingzhu.bthome.protocol.encode
import dev.qingzhu.bthome.protocol.toHex
import dev.qingzhu.bthome.scanner.BthomeGattClient
import dev.qingzhu.bthome.scanner.BthomeRepository
import dev.qingzhu.bthome.scanner.BthomeScanner
import dev.qingzhu.bthome.scanner.SensorReading
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 主端页：扫描 → 列表 → 点一台连接 → 订阅通知 → 写一条指令。
 *
 * 扫描的开始和停止由按钮控制，收集 readings 的协程跟着按钮走；
 * 连接面板的状态来自 [BthomeGattClient]，页面销毁时断开。
 */
class ScannerScreen : Fragment(R.layout.screen_scanner) {

    companion object {
        private const val MAX_RETRIES = 3

        /** 列表上按这个顺序显示对象。 */
        private val DISPLAY_ORDER = listOf(Obj.BATTERY, Obj.TEMPERATURE, Obj.HUMIDITY, Obj.VOLTAGE)
    }

    private val repo by lazy { BthomeRepository(requireContext().applicationContext) }
    private val client by lazy { BthomeGattClient(requireContext().applicationContext) }

    private val adapter = ReadingAdapter(::onDeviceClick)

    private var scanJob: Job? = null
    private var watchJob: Job? = null
    private var retryJob: Job? = null
    private var wantScan = false
    private var retries = 0

    private lateinit var btnScan: Button
    private lateinit var tvState: TextView
    private lateinit var btnWrite: Button
    private lateinit var tvGattState: TextView
    private lateinit var tvGattFrame: TextView
    private lateinit var tvGattValues: TextView

    private val required: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.all { it }) {
                startScanFlow()
            } else {
                toast("缺少蓝牙权限，无法扫描")
            }
        }

    private val enableBtLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) startScanFlow() else toast("蓝牙没有开启")
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        btnScan = view.findViewById(R.id.btn_scan)
        tvState = view.findViewById(R.id.tv_scan_state)
        btnWrite = view.findViewById(R.id.btn_write)
        tvGattState = view.findViewById(R.id.tv_gatt_state)
        tvGattFrame = view.findViewById(R.id.tv_gatt_frame)
        tvGattValues = view.findViewById(R.id.tv_gatt_values)

        view.findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@ScannerScreen.adapter
        }

        btnScan.setOnClickListener { if (wantScan) stopScan() else startScanFlow() }
        btnWrite.setOnClickListener {
            val ok = client.write(encode(listOf(Measurement(Obj.TEMPERATURE, 30f))))
            if (!ok) toast("还没连上，或这次写入没被接受")
        }
        view.findViewById<View>(R.id.btn_disconnect).setOnClickListener { client.disconnect() }

        val owner = viewLifecycleOwner
        owner.lifecycleScope.launch {
            client.state.collect { s ->
                tvGattState.text = s
                btnWrite.isEnabled = client.isConnected
                btnWrite.alpha = if (client.isConnected) 1f else 0.5f
                adapter.target = client.target.value
            }
        }
        owner.lifecycleScope.launch {
            client.target.collect { adapter.target = it }
        }
        owner.lifecycleScope.launch {
            client.frames.collect { frame ->
                val bytes = encode(frame.measurements)
                tvGattFrame.text = "收到通知 ${bytes.size} 字节：${bytes.toHex()}"
                tvGattValues.text = formatMeasurements(frame.measurements)
            }
        }
    }

    override fun onDestroyView() {
        client.disconnect()
        scanJob = null
        watchJob = null
        retryJob = null
        super.onDestroyView()
    }

    // ---------------------------------------------------------------- 扫描

    private fun toggleScanUi(scanning: Boolean) {
        btnScan.text = if (scanning) "停止扫描" else "开始扫描"
    }

    private fun startScanFlow() {
        if (!hasPermissions()) {
            permissionLauncher.launch(required)
            return
        }
        val adapter = bleAdapter()
        if (adapter == null) {
            toast("本机不支持蓝牙")
            return
        }
        if (!runCatching { adapter.isEnabled }.getOrDefault(false)) {
            enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        beginScan()
    }

    private fun beginScan() {
        wantScan = true
        retries = 0
        if (scanJob == null) {
            scanJob = repo.readings
                .onEach { adapter.submitList(it) }
                .launchIn(viewLifecycleOwner.lifecycleScope)
            // drop(1)：订阅时先跳过上一次遗留的错误码，只处理这次扫描期间发生的
            watchJob = viewLifecycleOwner.lifecycleScope.launch {
                repo.errorCode.drop(1).collect { code -> if (code != null) onScanFailed(code) }
            }
        }
        if (!repo.start()) {
            wantScan = false
            toggleScanUi(false)
            tvState.text = repo.error.value
            return
        }
        toggleScanUi(true)
        tvState.text = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) {
            "扫描中… Android 11 及以下还需要打开系统定位开关"
        } else {
            "扫描中…"
        }
    }

    private fun stopScan() {
        wantScan = false
        retryJob?.cancel()
        retryJob = null
        scanJob?.cancel()
        scanJob = null
        watchJob?.cancel()
        watchJob = null
        toggleScanUi(false)
        tvState.text = "已停止，列表保留最后一次结果"
    }

    /**
     * onScanFailed 必须处理。
     * 错误码 6（启停太频繁）：等 1s / 2s / 4s 再试，最多 3 次，绝不死循环重试。
     * 其它错误：停下来把原因显示给用户。
     */
    private fun onScanFailed(code: Int) {
        if (!wantScan) return
        if (code == BthomeScanner.FAILED_TOO_FREQUENTLY && retries < MAX_RETRIES) {
            val waitMs = 1000L shl retries
            retries++
            tvState.text = "扫描启停太频繁，被系统限流；${waitMs / 1000}s 后做第 $retries 次重试"
            retryJob?.cancel()
            retryJob = viewLifecycleOwner.lifecycleScope.launch {
                delay(waitMs)
                if (wantScan) repo.start()
            }
            return
        }
        if (code == BthomeScanner.FAILED_ALREADY_STARTED) return
        wantScan = false
        toggleScanUi(false)
        tvState.text = repo.error.value ?: "扫描失败"
    }

    private fun hasPermissions(): Boolean = required.all {
        ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED
    }

    private fun bleAdapter(): BluetoothAdapter? =
        (requireContext().getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    // ---------------------------------------------------------------- 连接路线

    private fun onDeviceClick(r: SensorReading) {
        if (client.target.value == r.address) {
            client.disconnect()
            return
        }
        if (!r.online) {
            toast("这台设备已经离线，连不上")
            return
        }
        if (!hasPermissions()) {
            permissionLauncher.launch(required)
            return
        }
        val device = runCatching { bleAdapter()?.getRemoteDevice(r.address) }.getOrNull()
        if (device == null) {
            toast("拿不到这台设备的句柄")
            return
        }
        client.connect(device)
    }

    private fun toast(msg: String) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }

    // ---------------------------------------------------------------- 列表

    private class ReadingAdapter(
        private val onClick: (SensorReading) -> Unit,
    ) : ListAdapter<SensorReading, ReadingAdapter.VH>(DIFF) {

        /** 正在连接 / 已连接的设备地址。 */
        var target: String? = null
            set(value) {
                field = value
                notifyDataSetChanged()
            }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_reading, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position), target, onClick)

        private class VH(view: View) : RecyclerView.ViewHolder(view) {
            private val dot: View = view.findViewById(R.id.dot)
            private val name: TextView = view.findViewById(R.id.tv_name)
            private val meta: TextView = view.findViewById(R.id.tv_meta)
            private val action: TextView = view.findViewById(R.id.tv_action)
            private val address: TextView = view.findViewById(R.id.tv_address)
            private val values: TextView = view.findViewById(R.id.tv_values)

            fun bind(r: SensorReading, target: String?, onClick: (SensorReading) -> Unit) {
                val ctx = itemView.context
                val age = (SystemClock.elapsedRealtime() - r.timestamp) / 1000
                dot.setBackgroundResource(if (r.online) R.drawable.dot_on else R.drawable.dot_off)
                name.text = r.name ?: "（广播里没有名字）"
                meta.text = if (r.online) "${r.rssi} dBm · ${age}s 前" else "离线 · ${age}s 没有广播"
                meta.setTextColor(ContextCompat.getColor(ctx, if (r.online) R.color.ink_muted else R.color.warn))
                action.text = if (target == r.address) "点按断开" else "连接 ›"
                address.text = r.address
                values.text = buildValues(ctx, r.measurements)
                itemView.setOnClickListener { onClick(r) }
            }

            /** 每个对象一段：范围内的用正文色，越界的整段标红并写明“越界”。 */
            private fun buildValues(ctx: Context, ms: Map<Obj, Float>): CharSequence {
                val text = SpannableStringBuilder()
                for (obj in DISPLAY_ORDER) {
                    val v = ms[obj] ?: continue
                    val start = text.length
                    val ok = v in obj.min..obj.max
                    text.append(obj.label).append(' ')
                        .append(String.format(Locale.US, "%.2f", v))
                        .append(obj.unit)
                    if (!ok) text.append(" 越界")
                    text.append("    ")
                    text.setSpan(
                        ForegroundColorSpan(ContextCompat.getColor(ctx, if (ok) R.color.ink else R.color.warn)),
                        start,
                        text.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
                if (text.isEmpty()) text.append("（没有可显示的对象）")
                return text
            }
        }

        private companion object {
            val DIFF = object : DiffUtil.ItemCallback<SensorReading>() {
                override fun areItemsTheSame(a: SensorReading, b: SensorReading) = a.address == b.address
                override fun areContentsTheSame(a: SensorReading, b: SensorReading) = a == b
            }
        }
    }

    private fun formatMeasurements(ms: List<Measurement>): String =
        ms.joinToString("    ") { m ->
            val v = String.format(Locale.US, "%.2f", m.value)
            if (m.inRange) "${m.obj.label} $v${m.obj.unit}" else "${m.obj.label} $v${m.obj.unit}（越界）"
        }
}
