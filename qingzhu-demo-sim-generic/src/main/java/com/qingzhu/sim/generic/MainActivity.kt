// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.sim.generic

import android.Manifest
import android.app.Dialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.chrisbanes.photoview.PhotoView
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 通用可编程从机主界面。
 *
 * 顶部状态栏常驻（deviceMAC + 状态点 + 状态文字）；底部 4 个 Tab 切换 Fragment：
 *  - 设备：居中展示 deviceMAC
 *  - 控制：主操作 + 广播参数 + 能力与测试
 *  - 文件：主端发来的图/视频/文件名
 *  - 日志：日志列表 + 清空 + 滚动到底
 *
 * UI 风格：青竹配色，米白底 + 白圆角卡片，竹青按钮 / tab 指示。
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQ_PERMISSIONS = 2001
        private const val PREFS_NAME = "generic_sim"
        private const val KEY_DEVICE_MAC = "device_mac"
        private const val LOG_FLUSH_INTERVAL_MS = 150L
        private const val MAX_LOG_LINES = 1000

        private const val TAB_DEVICE = 1
        private const val TAB_CONTROL = 0
        private const val TAB_FILE = 2
        private const val TAB_LOG = 3
    }

    // ============== 顶部状态栏 ==============
    private lateinit var statusDot: View
    private lateinit var tvDeviceMac: TextView
    private lateinit var tvDeviceMacLabel: TextView

    // ============== 底部 Tab ==============
    private lateinit var tabDevice: LinearLayout
    private lateinit var tabControl: LinearLayout
    private lateinit var tabFile: LinearLayout
    private lateinit var tabLog: LinearLayout
    private lateinit var tabDeviceIndicator: View
    private lateinit var tabControlIndicator: View
    private lateinit var tabFileIndicator: View
    private lateinit var tabLogIndicator: View
    private lateinit var tabDeviceText: TextView
    private lateinit var tabControlText: TextView
    private lateinit var tabFileText: TextView
    private lateinit var tabLogText: TextView
    private var currentTab: Int = TAB_CONTROL

    // ============== Fragment 缓存 ==============
    private var deviceIdentityFragment: DeviceIdentityFragment? = null
    private var controlFragment: ControlFragment? = null
    private var fileDisplayFragment: FileDisplayFragment? = null
    private var logFragment: LogFragment? = null

    // ============== 业务状态 ==============
    private val main = Handler(Looper.getMainLooper())
    private val flushTask = Runnable { flushLogs() }
    private val pendingLines = ArrayList<String>()
    private var flushScheduled = false
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    private var autoScroll = true
    private var beacon: GenericBeacon? = null
    internal var deviceMac: ByteArray? = null
        private set
    private var gattServer: GenericGattServer? = null
    private var running = false
    private var currentBitmap: Bitmap? = null

    /** 最近一次收到的文件内容：Fragment 的 View 被销毁重建后据此恢复显示 */
    private sealed class LastFile {
        class Image(val bitmap: Bitmap) : LastFile()
        class Video(val file: File) : LastFile()
        class Other(val fileName: String, val size: Int) : LastFile()
    }

    private var lastFile: LastFile? = null

    // 日志 RecyclerView 引用（attach 时由 LogFragment 提供，detach 时清空）
    private var logRecyclerView: RecyclerView? = null
    private var btnScrollBottom: TextView? = null
    private var btnClearLog: Button? = null
    private lateinit var logLayoutManager: LinearLayoutManager
    private lateinit var logAdapter: LogAdapter

    private val scrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            updateAutoScrollState()
        }
    }

    // ============== Fragment 公共访问 ==============
    internal val isRunning: Boolean get() = running
    fun formatMacString(mac: ByteArray): String = formatMac(mac)

    // ============== Lifecycle ==============
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        bindViews()
        setupTabs()
        setupLog()
        updateRunningStatus(false)

        // 支持 am start -e tab <device|control|file|log> 直接进入指定 Tab
        // 用法：adb shell am start -n com.qingzhu.sim.generic/.MainActivity --es tab log
        // 留作开发期调试 / 自动化截图入口；不影响正常启动（不传 extra 时走默认控制 Tab）
        val tabExtra = intent?.getStringExtra("tab")
        val initialTab = when (tabExtra) {
            "device" -> TAB_DEVICE
            "control" -> TAB_CONTROL
            "file" -> TAB_FILE
            "log" -> TAB_LOG
            else -> TAB_CONTROL
        }
        selectTab(initialTab)

        if (ensurePermissions()) {
            startAll()
        }
    }

    private fun bindViews() {
        statusDot = findViewById(R.id.statusDot)
        tvDeviceMac = findViewById(R.id.tvDeviceMac)
        tvDeviceMacLabel = findViewById(R.id.tvDeviceMacLabel)

        tabDevice = findViewById(R.id.tabDevice)
        tabControl = findViewById(R.id.tabControl)
        tabFile = findViewById(R.id.tabFile)
        tabLog = findViewById(R.id.tabLog)

        tabDeviceIndicator = findViewById(R.id.tabDeviceIndicator)
        tabControlIndicator = findViewById(R.id.tabControlIndicator)
        tabFileIndicator = findViewById(R.id.tabFileIndicator)
        tabLogIndicator = findViewById(R.id.tabLogIndicator)

        tabDeviceText = findViewById(R.id.tabDeviceText)
        tabControlText = findViewById(R.id.tabControlText)
        tabFileText = findViewById(R.id.tabFileText)
        tabLogText = findViewById(R.id.tabLogText)
    }

    private fun setupTabs() {
        tabDevice.setOnClickListener { selectTab(TAB_DEVICE) }
        tabControl.setOnClickListener { selectTab(TAB_CONTROL) }
        tabFile.setOnClickListener { selectTab(TAB_FILE) }
        tabLog.setOnClickListener { selectTab(TAB_LOG) }
    }

    private fun setupLog() {
        logLayoutManager = LinearLayoutManager(this)
        logAdapter = LogAdapter()
    }

    private fun selectTab(index: Int) {
        currentTab = index
        // indicator 显隐
        tabDeviceIndicator.visibility = if (index == TAB_DEVICE) View.VISIBLE else View.INVISIBLE
        tabControlIndicator.visibility = if (index == TAB_CONTROL) View.VISIBLE else View.INVISIBLE
        tabFileIndicator.visibility = if (index == TAB_FILE) View.VISIBLE else View.INVISIBLE
        tabLogIndicator.visibility = if (index == TAB_LOG) View.VISIBLE else View.INVISIBLE
        // 文字颜色
        tabDeviceText.setTextColor(colorOf(if (index == TAB_DEVICE) R.color.brand_blue else R.color.text_tertiary))
        tabControlText.setTextColor(colorOf(if (index == TAB_CONTROL) R.color.brand_blue else R.color.text_tertiary))
        tabFileText.setTextColor(colorOf(if (index == TAB_FILE) R.color.brand_blue else R.color.text_tertiary))
        tabLogText.setTextColor(colorOf(if (index == TAB_LOG) R.color.brand_blue else R.color.text_tertiary))

        // Fragment 切换（缓存避免状态丢失）
        val fragment: Fragment = when (index) {
            TAB_DEVICE -> deviceIdentityFragment ?: DeviceIdentityFragment().also { deviceIdentityFragment = it }
            TAB_CONTROL -> controlFragment ?: ControlFragment().also { controlFragment = it }
            TAB_FILE -> fileDisplayFragment ?: FileDisplayFragment().also { fileDisplayFragment = it }
            TAB_LOG -> logFragment ?: LogFragment().also { logFragment = it }
            else -> DeviceIdentityFragment()
        }

        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
    }

    private fun colorOf(resId: Int): Int = ContextCompat.getColor(this, resId)

    /** 状态切换：顶栏 + 当前 Fragment 同步刷新 */
    private fun updateRunningStatus(running: Boolean) {
        this.running = running
        ViewStyle.applyRunningStatus(this, statusDot, tvDeviceMacLabel, running)
        // 「设备」Tab 通过 onResume 自己拉取；「控制」Tab 需要刷新按钮文字
        controlFragment?.refreshToggleState()
    }

    // ============== 权限 ==============
    private fun ensurePermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val need = arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
            var allGranted = true
            for (p in need) {
                if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) allGranted = false
            }
            if (!allGranted) {
                requestPermissions(need, REQ_PERMISSIONS)
                return false
            }
        }
        return true
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMISSIONS) return
        for (r in grantResults) {
            if (r != PackageManager.PERMISSION_GRANTED) {
                log("权限被拒绝，无法运行")
                return
            }
        }
        startAll()
    }

    // ============== BLE / GATT ==============
    internal fun startAll() {
        val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager?
        val adapter = manager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            log("蓝牙未开启，请先打开系统蓝牙")
            return
        }
        val mfgId = parseCompanyId(currentMfgId())
        val payload = parseHex(currentPayload())
        deviceMac = getOrCreateDeviceMac()
        val b = GenericBeacon(adapter)
        beacon = b
        b.startAdvertising(payload, mfgId, deviceMac)
        tvDeviceMac.text = formatMac(deviceMac!!)
        updateRunningStatus(true)
        log("广播已启动，完整 AD = " + b.buildAdvPacketHex(payload, mfgId, deviceMac)
                + " deviceMac=" + formatMac(deviceMac!!))

        val server = GenericGattServer(
            this,
            { line -> log(line) },
            { name, type, data, costMs -> onFileReceived(name, type, data, costMs) },
            { address ->
                // 主设备断开后重启广播，模拟真实设备「断开后换址」：bleMac 轮换，主端重扫到新地址。
                // 延迟 500ms 再重启，避免从端还没走完断开流程就立刻重播（导致地址不轮换）
                main.postDelayed({
                    log("主设备断开（$address），重启广播模拟换址")
                    restartAdvertising()
                }, 500)
            }
        )
        gattServer = server
        val ok = server.start()
        log(if (ok) "GATT Server 已启动（echo 回显），等待主设备连接..." else "GATT Server 启动失败")
        running = ok
        controlFragment?.refreshToggleState()
    }

    internal fun stopAll() {
        beacon?.stopAdvertising()
        gattServer?.stop()
        updateRunningStatus(false)
        log("已停止广播和 GATT Server")
    }

    internal fun restartAdvertising() {
        val b = beacon ?: return
        val mfgId = parseCompanyId(currentMfgId())
        val payload = parseHex(currentPayload())
        b.startAdvertising(payload, mfgId, deviceMac)
        log("广播 payload 更新为 " + b.buildAdvPacketHex(payload, mfgId, deviceMac))
    }

    /** 当前 ControlFragment 输入框里的 hex payload；Fragment 未创建时回退到空。 */
    private fun currentPayload(): String =
        controlFragment?.payloadText() ?: ""

    private fun currentMfgId(): String =
        controlFragment?.mfgCompanyIdText() ?: "FFFF"

    fun setHeartbeatSupported(supported: Boolean) {
        gattServer?.setHeartbeatSupported(supported)
    }

    fun notifyLowBattery() {
        gattServer?.notifyAll("{\"event\":\"low_battery\",\"level\":15}".toByteArray(Charsets.UTF_8))
    }

    fun showFullscreenImage() {
        val bmp = currentBitmap ?: return
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val photoView = PhotoView(this)
        photoView.setImageBitmap(bmp)
        photoView.setOnPhotoTapListener { _, _, _ -> dialog.dismiss() }
        photoView.setOnViewTapListener { _, _, _ -> dialog.dismiss() }
        dialog.setContentView(photoView)
        dialog.show()
    }

    // ============== hex 解析 ==============
    private fun parseHex(hex: String): ByteArray {
        val clean = hex.replace(Regex("[^0-9a-fA-F]"), "")
        if (clean.isEmpty()) return ByteArray(0)
        if (clean.length % 2 != 0) return byteArrayOf(0x01, 0x02, 0x03)
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            out[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }

    private fun parseCompanyId(hex: String): Int? {
        val clean = hex.replace(Regex("[^0-9a-fA-F]"), "")
        if (clean.isEmpty()) return null
        if (clean.length > 4) return null
        val padded = clean.padStart(4, '0')
        val v = padded.toInt(16)
        if (v < 0 || v > 0xFFFF) return null
        return v
    }

    // ============== deviceMac 派生 ==============
    private fun getOrCreateDeviceMac(): ByteArray {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val saved = prefs.getString(KEY_DEVICE_MAC, null)
        if (saved != null && saved.length == 12) return parseHex(saved)
        val mac = deriveMacFromAndroidId()
        prefs.edit().putString(KEY_DEVICE_MAC, toHex(mac)).apply()
        return mac
    }

    private fun deriveMacFromAndroidId(): ByteArray {
        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: ""
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(("qingzhu-sim:" + androidId).toByteArray(Charsets.UTF_8))
        val mac = ByteArray(6)
        System.arraycopy(digest, 0, mac, 0, 6)
        mac[0] = (mac[0].toInt() or 0x02).toByte()
        return mac
    }

    // ============== 文件接收 ==============
    private fun onFileReceived(fileName: String, type: Int, data: ByteArray, costMs: Long) {
        log("收到文件：$fileName · ${formatSize(data.size.toLong())} · 耗时 ${formatDuration(costMs)} · 平均 ${formatSpeed(data.size.toLong(), costMs)}")
        main.post {
            // 先缓存内容，再渲染；Fragment 未创建 / View 已销毁也不丢数据
            lastFile = when (type) {
                GenericGattServer.FILE_TYPE_IMAGE -> {
                    val bmp = BitmapFactory.decodeByteArray(data, 0, data.size)
                    if (bmp != null) {
                        currentBitmap = bmp
                        LastFile.Image(bmp)
                    } else {
                        LastFile.Other(fileName, data.size)
                    }
                }
                GenericGattServer.FILE_TYPE_VIDEO -> {
                    val f = writeRecvVideo(data)
                    if (f != null) LastFile.Video(f) else LastFile.Other(fileName, data.size)
                }
                else -> LastFile.Other(fileName, data.size)
            }
            // 视图还在（正停留在「文件」Tab）才即时渲染；否则等 onViewCreated 时恢复
            fileDisplayFragment?.takeIf { it.isAdded && it.view != null }?.let { renderLastFile(it) }
        }
    }

    /** 把视频数据落到缓存文件，失败返回 null */
    private fun writeRecvVideo(data: ByteArray): File? {
        val dir = File(cacheDir, "recv")
        if (!dir.exists()) dir.mkdirs()
        val f = File(dir, "recv_" + System.currentTimeMillis() + ".mp4")
        return try {
            FileOutputStream(f).use { fos -> fos.write(data) }
            f
        } catch (e: IOException) {
            log("写视频临时文件失败：${e.message}")
            null
        }
    }

    /** 依据 [lastFile] 恢复「文件」Tab 的显示内容；Fragment 视图重建时（onViewCreated）也会调用 */
    internal fun renderLastFile(fragment: FileDisplayFragment) {
        when (val lf = lastFile) {
            is LastFile.Image -> fragment.showBitmap(lf.bitmap)
            is LastFile.Video -> fragment.playVideo(lf.file)
            is LastFile.Other -> fragment.showFileName(lf.fileName, lf.size)
            null -> fragment.showEmpty()
        }
    }

    // ============== 日志（LogFragment 接管 RecyclerView 后 attach/detach）==============
    fun attachLogViews(rv: RecyclerView, btnBottom: TextView, btnClear: Button) {
        logRecyclerView = rv
        btnScrollBottom = btnBottom
        btnClearLog = btnClear

        rv.layoutManager = logLayoutManager
        rv.adapter = logAdapter
        rv.addOnScrollListener(scrollListener)
        btnClear.setOnClickListener { clearLogs() }
        btnBottom.setOnClickListener { scrollToBottom() }

        // 用户在底部时立即续滚
        if (autoScroll && logAdapter.itemCount > 0) {
            logLayoutManager.scrollToPosition(logAdapter.itemCount - 1)
        }
    }

    fun detachLogViews() {
        logRecyclerView?.adapter = null
        logRecyclerView?.layoutManager = null
        logRecyclerView?.clearOnScrollListeners()
        btnClearLog?.setOnClickListener(null)
        btnScrollBottom?.setOnClickListener(null)
        logRecyclerView = null
        btnScrollBottom = null
        btnClearLog = null
    }

    fun log(line: String) {
        val entry = "[${timeFmt.format(Date())}] $line"
        synchronized(pendingLines) { pendingLines.add(entry) }
        if (!flushScheduled) {
            flushScheduled = true
            main.postDelayed(flushTask, LOG_FLUSH_INTERVAL_MS)
        }
    }

    private fun flushLogs() {
        flushScheduled = false
        val batch: List<String>
        synchronized(pendingLines) {
            if (pendingLines.isEmpty()) return
            batch = ArrayList(pendingLines)
            pendingLines.clear()
        }
        logAdapter.appendLines(batch)
        if (autoScroll && logRecyclerView != null) {
            logLayoutManager.scrollToPosition(logAdapter.itemCount - 1)
        }
    }

    private fun clearLogs() {
        main.removeCallbacks(flushTask)
        flushScheduled = false
        synchronized(pendingLines) { pendingLines.clear() }
        logAdapter.clear()
        autoScroll = true
        btnScrollBottom?.visibility = View.GONE
    }

    private fun scrollToBottom() {
        autoScroll = true
        btnScrollBottom?.visibility = View.GONE
        if (logRecyclerView != null && logAdapter.itemCount > 0) {
            logLayoutManager.scrollToPosition(logAdapter.itemCount - 1)
        }
    }

    private fun updateAutoScrollState() {
        val rv = logRecyclerView ?: return
        val last = logLayoutManager.findLastVisibleItemPosition()
        val atBottom = last >= logAdapter.itemCount - 2
        autoScroll = atBottom
        btnScrollBottom?.visibility = if (atBottom) View.GONE else View.VISIBLE
    }

    // ============== 工具 ==============
    private fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append(String.format(Locale.US, "%02X", b))
        return sb.toString()
    }

    private fun formatMac(mac: ByteArray): String {
        val sb = StringBuilder()
        for (i in mac.indices) {
            if (i > 0) sb.append(":")
            sb.append(String.format(Locale.US, "%02X", mac[i]))
        }
        return sb.toString()
    }

    fun formatSize(bytes: Long): String = when {
        bytes >= 100 * 1024 -> String.format(Locale.US, "%.2f MB", bytes / 1048576.0)
        bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun formatDuration(ms: Long): String =
        if (ms >= 60_000) "${ms / 60_000}分${(ms % 60_000) / 1000}秒"
        else String.format(Locale.US, "%.1f秒", ms / 1000.0)

    private fun formatSpeed(bytes: Long, ms0: Long): String {
        var ms = ms0
        if (ms <= 0) ms = 1
        val kbPerSec = bytes / 1024.0 / (ms / 1000.0)
        return if (kbPerSec >= 1024) String.format(Locale.US, "%.2f MB/s", kbPerSec / 1024.0)
        else String.format(Locale.US, "%.1f KB/s", kbPerSec)
    }

    /** 日志行适配器：每条日志一个 TextView，内存有界（最多 [MAX_LOG_LINES] 条） */
    private inner class LogAdapter : RecyclerView.Adapter<LogAdapter.VH>() {
        private val lines = ArrayList<String>()

        fun appendLines(batch: List<String>) {
            val start = lines.size
            lines.addAll(batch)
            if (lines.size > MAX_LOG_LINES) {
                lines.subList(0, lines.size - MAX_LOG_LINES).clear()
                notifyDataSetChanged()
            } else {
                notifyItemRangeInserted(start, batch.size)
            }
        }

        fun clear() {
            val size = lines.size
            lines.clear()
            if (size > 0) notifyItemRangeRemoved(0, size)
        }

        override fun getItemCount(): Int = lines.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val tv = layoutInflater.inflate(R.layout.item_log, parent, false) as TextView
            return VH(tv)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.text.text = lines[position]
        }

        inner class VH(val text: TextView) : RecyclerView.ViewHolder(text)
    }
}