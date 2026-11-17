// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.sim.generic

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
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
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Arrays
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 可编程从机的 GATT Server：echo 回显。
 */
class GenericGattServer(
    context: Context,
    private val logListener: LogListener?,
    private val fileListener: FileListener?,
    private val disconnectListener: DisconnectListener? = null
) {

    companion object {
        private const val TAG = "GenericGattServer"

        const val SERVICE_UUID = "0000ff00-0000-1000-8000-00805f9b34fb"
        const val CHAR_COMMAND_UUID = "0000ff01-0000-1000-8000-00805f9b34fb"
        const val CHAR_STATUS_UUID = "0000ff02-0000-1000-8000-00805f9b34fb"
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // ---- OTA 分片协议魔数（与主端 GenericActions 的分片协议对齐）----
        /** OTA 分片魔数：value[0]==0xAA 表示这是 OTA 数据片而非普通 echo */
        private const val OTA_MAGIC: Byte = 0xAA.toByte()

        /** OTA 收齐回执魔数：回执 [0]=0xBB, [1..4]=totalBytes(BE), [5..8]=chunkCount(BE) */
        private const val OTA_ACK_MAGIC: Byte = 0xBB.toByte()

        /** 文件元数据片魔数：[0]=0xAC, [1..4]=totalBytes(BE), [5]=type, [6..]=fileName(UTF-8) */
        private const val FILE_META_MAGIC: Byte = 0xAC.toByte()

        /** 文件类型：图片 */
        const val FILE_TYPE_IMAGE = 0

        /** 文件类型：视频 */
        const val FILE_TYPE_VIDEO = 1

        /** 文件类型：其它 */
        const val FILE_TYPE_OTHER = 2

        /** 能力协商指令：主端发送该文本，从端返回设备能力 JSON（不再原样回显） */
        private const val CMD_GET_INFO = "get_info"

        /** 心跳 JSON 协议：action 字段值 + status 合法值（与主端 GenericActions 对齐） */
        private const val HEARTBEAT_ACTION = "heartbeat"
        private const val HEARTBEAT_STATUS_OK = "ok"

        /**
         * notify 发送看门狗超时（毫秒）：发起 notify 后超过该时间仍未收到 [BluetoothGattServerCallback.onNotificationSent]，
         * 就强制复位发送状态并继续出队。防止一次回调丢失导致 notify 通道（echo 回显 / OTA 收齐回执）永久卡死。
         */
        private const val NOTIFY_TIMEOUT_MS = 2500L

        /** notify 待发队列上限：超出时丢弃最旧一条，避免对端链路失效时队列无界增长 */
        private const val MAX_NOTIFY_QUEUE = 64
    }

    /** 日志监听器 */
    fun interface LogListener {
        fun log(line: String)
    }

    /** 文件接收监听器：从机收齐一个完整文件后回调（主线程），供 UI 按类型展示。costMs 为传输耗时（从收到元数据到收齐） */
    fun interface FileListener {
        fun onFileReceived(fileName: String, type: Int, data: ByteArray, costMs: Long)
    }

    /** 主设备断开监听器：主设备断开后回调（供 MainActivity 重启广播，模拟真实设备「断开后换址」的行为） */
    fun interface DisconnectListener {
        fun onDisconnected(address: String)
    }

    constructor(context: Context, logListener: LogListener?) : this(context, logListener, null, null)

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    private var gattServer: BluetoothGattServer? = null
    private var statusCharacteristic: BluetoothGattCharacteristic? = null

    private val notificationEnabledDevices = HashSet<String>()
    private val notifyQueue = ConcurrentLinkedQueue<NotifyData>()

    /** notify 状态锁：sendingNotify / inflightNotify / notifyToken 的读写全部在锁内完成 */
    private val notifyLock = Any()

    /** 是否有一条 notify 已发出、正在等 onNotificationSent 回调（volatile 供 enqueueNotify 无锁快查） */
    @Volatile
    private var sendingNotify = false

    /** 当前在等的 notify，超时复位时用于日志/丢弃说明 */
    private var inflightNotify: NotifyData? = null

    /**
     * notify 序列号：每次发送 / 收到回调 / 复位都自增。
     * 超时看门狗携带发起时的序列号，回调时序列号不匹配则说明已过期，直接忽略——
     * 避免「回调与看门狗同时到达」时把新一次发送的状态误复位。
     */
    private var notifyToken = 0L

    /** 是否支持心跳探活（设备级能力）：能力协商时据此返回，可运行时切换（模拟不同设备） */
    @Volatile
    private var heartbeatSupported = true

    private val otaLock = Any()
    private var otaDevice: String? = null
    private var otaTotal = 0
    private var otaExpectedIndex = 0

    /** 最近一次上报的进度百分比（按 5% 节流进度日志，避免逐片打日志把 UI 刷死） */
    private var lastOtaPercent = 0
    private val otaBuffer = ByteArrayOutputStream()

    /** 文件模式：收到元数据片后记录，数据片收满后据此回调 FileListener */
    private var fileFileName: String? = null
    private var fileType = FILE_TYPE_OTHER

    /** 收到文件元数据的时间戳（用于计算传输耗时/平均速度） */
    private var fileStartTime = 0L

    private class NotifyData(val device: BluetoothDevice, val data: ByteArray)

    /** 设置是否支持心跳探活（设备级能力，运行时切换；下一次能力协商生效） */
    fun setHeartbeatSupported(supported: Boolean) {
        this.heartbeatSupported = supported
    }

    private fun log(line: String) {
        Log.d(TAG, line)
        logListener?.let { listener ->
            main.post { listener.log(line) }
        }
    }

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?
        if (manager == null || manager.adapter == null) {
            log("蓝牙不可用")
            return false
        }
        val server = manager.openGattServer(appContext, callback) ?: run {
            log("openGattServer 失败")
            return false
        }
        gattServer = server

        val service = BluetoothGattService(
            UUID.fromString(SERVICE_UUID), BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        val command = BluetoothGattCharacteristic(
            UUID.fromString(CHAR_COMMAND_UUID),
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        val status = BluetoothGattCharacteristic(
            UUID.fromString(CHAR_STATUS_UUID),
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        status.addDescriptor(BluetoothGattDescriptor(
            CCCD_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        ))
        statusCharacteristic = status

        service.addCharacteristic(command)
        service.addCharacteristic(status)

        val ok = server.addService(service)
        log(if (ok) "GATT 服务添加成功: $SERVICE_UUID" else "GATT 服务添加失败")
        return ok
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        gattServer?.close()
        gattServer = null
        notificationEnabledDevices.clear()
        notifyQueue.clear()
        resetNotifyState()
    }

    /** 复位 notify 发送状态，并作废所有在途超时看门狗（序列号自增） */
    private fun resetNotifyState() {
        synchronized(notifyLock) {
            notifyToken++
            sendingNotify = false
            inflightNotify = null
        }
    }

    private val callback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                log("主设备已连接: " + device.address)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                log("主设备已断开: " + device.address)
                notificationEnabledDevices.remove(device.address)
                notifyQueue.clear()
                // 主动断开时链路可能已失效、onNotificationSent 不再回调，必须复位，否则 notify 通道会永久卡死
                resetNotifyState()
                // 通知外部（MainActivity 据此重启广播，模拟真实设备「断开后换址」）
                disconnectListener?.onDisconnected(device.address)
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            log("MTU 协商完成: $mtu")
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?
        ) {
            if (CCCD_UUID == descriptor.uuid) {
                val enabled = value != null && Arrays.equals(value, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                if (enabled) {
                    notificationEnabledDevices.add(device.address)
                } else {
                    notificationEnabledDevices.remove(device.address)
                }
                log("设备 " + device.address + if (enabled) " 订阅了通知" else " 取消订阅通知")
                descriptor.value = value
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value)
            } else {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?
        ) {
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value)
            }
            // OTA 数据片（0xAA）重组；文件元数据片（0xAC）记录文件名/类型；否则 echo 原样回显。
            if (value != null && value.size >= 9 && value[0] == OTA_MAGIC) {
                handleOtaChunk(device, value)
            } else if (value != null && value.size >= 6 && value[0] == FILE_META_MAGIC) {
                log("收到文件元数据 " + value.size + " 字节")
                handleFileMeta(device, value)
            } else if (value != null) {
                // 能力协商：收到 "get_info" 指令时返回设备能力，而不是原样回显
                if (isGetInfoCommand(value)) {
                    log("收到能力协商请求，返回设备信息")
                    enqueueNotify(device, buildDeviceInfo())
                } else if (isHeartbeatRequest(value)) {
                    // JSON 心跳：解析 seq 回填，返回 {"action":"heartbeat","seq":N,"status":"ok"}
                    enqueueNotify(device, buildHeartbeatAck(value))
                } else {
                    log("收到数据 " + value.size + " 字节: " + bytesToDisplay(value))
                    enqueueNotify(device, value)
                }
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            // 序列号自增同时作废可能仍在排队的超时看门狗
            resetNotifyState()
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("notify 发送回调失败：status=$status device=" + device.address)
            }
            processNotifyQueue()
        }
    }

    @SuppressLint("MissingPermission")
    private fun enqueueNotify(device: BluetoothDevice, data: ByteArray) {
        if (!notificationEnabledDevices.contains(device.address)) {
            log("设备未订阅通知，无法 echo")
            return
        }
        // 队列有界：对端链路失效且未上报断开时，队列会一直堆积，超出上限丢弃最旧一条
        while (notifyQueue.size >= MAX_NOTIFY_QUEUE) {
            val dropped = notifyQueue.poll() ?: break
            log("notify 队列已满（$MAX_NOTIFY_QUEUE），丢弃最旧一条（${dropped.data.size} 字节）")
        }
        notifyQueue.offer(NotifyData(device, data))
        if (!sendingNotify) {
            processNotifyQueue()
        }
    }

    /** 主动上报：向所有已订阅通知的主设备发一条 notify（模拟电量低/按键等主动事件，场景 6） */
    @SuppressLint("MissingPermission")
    fun notifyAll(data: ByteArray) {
        if (notificationEnabledDevices.isEmpty()) {
            log("无已订阅设备，无法主动上报")
            return
        }
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        for (addr in notificationEnabledDevices) {
            enqueueNotify(adapter.getRemoteDevice(addr), data)
        }
        log("已向 " + notificationEnabledDevices.size + " 个主设备主动上报")
    }

    @SuppressLint("MissingPermission")
    private fun processNotifyQueue() {
        val next: NotifyData
        val token: Long
        synchronized(notifyLock) {
            if (sendingNotify) {
                return
            }
            next = notifyQueue.poll() ?: return
            sendingNotify = true
            inflightNotify = next
            token = ++notifyToken
        }
        statusCharacteristic?.value = next.data
        val ok = gattServer?.notifyCharacteristicChanged(next.device, statusCharacteristic, false) ?: false
        if (ok) {
            // 看门狗：超时仍未收到 onNotificationSent 就强制复位，避免 notify 通道永久卡死
            main.postDelayed({ onNotifyTimeout(token) }, NOTIFY_TIMEOUT_MS)
        } else {
            synchronized(notifyLock) {
                sendingNotify = false
                inflightNotify = null
            }
            processNotifyQueue()
        }
    }

    /**
     * notify 发送看门狗。被调用时若 [token] 已不是当前序列号，说明这条 notify 的回调（或上位机断开复位）
     * 已经先到，此次超时直接忽略；否则强制复位发送状态、丢弃在途回执并继续出队。
     */
    private fun onNotifyTimeout(token: Long) {
        val timedOut: NotifyData?
        synchronized(notifyLock) {
            if (!sendingNotify || token != notifyToken) {
                return
            }
            timedOut = inflightNotify
            sendingNotify = false
            inflightNotify = null
            notifyToken++
        }
        val extra = timedOut?.let { "（丢弃 ${it.data.size} 字节回执 device=${it.device.address}）" } ?: ""
        log("notify 发送超时（${NOTIFY_TIMEOUT_MS}ms 未收到 onNotificationSent），强制复位并继续出队$extra")
        processNotifyQueue()
    }

    /**
     * 重组 OTA 分片。分片格式（与主端 GenericActions.buildOtaPacket 对齐）：
     * [0]=0xAA, [1..4]=total(BE), [5..8]=index(BE), [9..]=data。
     * 从 index=0 开始顺序累积，收满 total 片后 notify 回执 [0]=0xBB, [1..4]=totalBytes, [5..8]=chunkCount。
     */
    private fun handleOtaChunk(device: BluetoothDevice, value: ByteArray) {
        val total = readInt32BE(value, 1)
        val index = readInt32BE(value, 5)
        val data = Arrays.copyOfRange(value, 9, value.size)
        synchronized(otaLock) {
            if (index == 0) {
                // 新 OTA 会话
                otaDevice = device.address
                otaTotal = total
                otaExpectedIndex = 0
                lastOtaPercent = 0
                otaBuffer.reset()
                log("OTA 开始：total=$total 片")
            }
            if (device.address != otaDevice || total != otaTotal || index != otaExpectedIndex) {
                log("OTA 分片异常：期望 index=$otaExpectedIndex 实收 $index（total=$total/$otaTotal），丢弃")
                return
            }
            try {
                otaBuffer.write(data)
            } catch (e: IOException) {
                log("OTA 累积异常：" + e.message)
                return
            }
            otaExpectedIndex++
            // 进度日志按「每 5%」节流（逐片都打会把 UI 刷死）；首片与收齐（100%）必打
            val percent = if (otaTotal > 0) otaExpectedIndex * 100 / otaTotal else 100
            if (otaExpectedIndex == 1 || percent >= lastOtaPercent + 5) {
                lastOtaPercent = percent
                log("OTA 进度 $percent%（$otaExpectedIndex/$otaTotal，累计 ${otaBuffer.size()} 字节）")
            }
            if (otaExpectedIndex == otaTotal) {
                val receivedBytes = otaBuffer.size()
                log("OTA 收齐：总字节=$receivedBytes")
                val ack = ByteArray(9)
                ack[0] = OTA_ACK_MAGIC
                writeInt32BE(ack, 1, receivedBytes)
                writeInt32BE(ack, 5, otaTotal)
                enqueueNotify(device, ack)
                // 文件模式：把完整文件交给 UI 展示
                val name = fileFileName
                if (name != null) {
                    val fileData = otaBuffer.toByteArray()
                    val type = fileType
                    val costMs = android.os.SystemClock.elapsedRealtime() - fileStartTime
                    fileFileName = null
                    fileListener?.let { listener ->
                        main.post { listener.onFileReceived(name, type, fileData, costMs) }
                    }
                }
                // 复位，准备下一次传输
                otaBuffer.reset()
                otaDevice = null
                otaTotal = 0
                otaExpectedIndex = 0
            }
        }
    }

    /** 解析文件元数据片：[0]=0xAC, [1..4]=totalBytes(BE), [5]=type, [6..]=fileName(UTF-8) */
    private fun handleFileMeta(device: BluetoothDevice, value: ByteArray) {
        val totalBytes = readInt32BE(value, 1)
        val type = value[5].toInt() and 0xFF
        val fileName = String(value, 6, value.size - 6, StandardCharsets.UTF_8)
        synchronized(otaLock) {
            fileFileName = fileName
            fileType = type
            fileStartTime = android.os.SystemClock.elapsedRealtime()
            otaDevice = device.address
            otaTotal = 0
            otaExpectedIndex = 0
            otaBuffer.reset()
            log("收到文件元数据：name=$fileName type=$type size=$totalBytes")
        }
    }

    private fun isGetInfoCommand(value: ByteArray): Boolean {
        return CMD_GET_INFO == String(value, StandardCharsets.UTF_8)
    }

    /** 判断是否 JSON 心跳请求：{"action":"heartbeat",...}（与普通 echo 区分，不再原样回显） */
    private fun isHeartbeatRequest(value: ByteArray): Boolean {
        return try {
            val o = org.json.JSONObject(String(value, StandardCharsets.UTF_8))
            HEARTBEAT_ACTION == o.optString("action", "")
        } catch (e: Exception) {
            false
        }
    }

    /** 心跳应答：回显请求的 seq + status=ok，主端据此校验存活 */
    private fun buildHeartbeatAck(request: ByteArray): ByteArray {
        return try {
            val req = org.json.JSONObject(String(request, StandardCharsets.UTF_8))
            val seq = req.optLong("seq", -1L)
            val ack = org.json.JSONObject()
            ack.put("action", HEARTBEAT_ACTION)
            ack.put("seq", seq)
            ack.put("status", HEARTBEAT_STATUS_OK)
            log("收到心跳请求 seq=$seq，返回应答")
            ack.toString().toByteArray(StandardCharsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "解析心跳请求失败: " + e.message)
            "{}".toByteArray(StandardCharsets.UTF_8)
        }
    }

    /** 上报设备能力：设备类型 / BLE 版本 / 是否支持 2M PHY / 最大 MTU / 接收缓冲大小 */
    @SuppressLint("MissingPermission")
    private fun buildDeviceInfo(): ByteArray {
        return try {
            val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?
            val adapter = manager?.adapter
            val le2m = adapter != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    && adapter.isLe2MPhySupported
            val info = org.json.JSONObject()
            info.put("deviceType", "generic")
            info.put("bleVersion", if (le2m) "5.0" else "4.2")
            info.put("phy2M", le2m)
            info.put("maxMtu", 247)
            info.put("rxBufferBytes", 4096)
            info.put("writeNoResponse", true)
            // 是否支持心跳探活：设备级能力，主端据此决定要不要发心跳（而非协议写死）
            info.put("heartbeat", heartbeatSupported)
            // 最大并发连接数：设备级能力（模拟「只能被 1 个主端连」的设备）
            info.put("maxConnections", 1)
            info.toString().toByteArray(StandardCharsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "构建设备信息失败: " + e.message)
            "{}".toByteArray(StandardCharsets.UTF_8)
        }
    }

    private fun readInt32BE(src: ByteArray, offset: Int): Int {
        return ((src[offset].toInt() and 0xFF) shl 24) or
                ((src[offset + 1].toInt() and 0xFF) shl 16) or
                ((src[offset + 2].toInt() and 0xFF) shl 8) or
                (src[offset + 3].toInt() and 0xFF)
    }

    private fun writeInt32BE(dst: ByteArray, offset: Int, value: Int) {
        dst[offset] = (value shr 24).toByte()
        dst[offset + 1] = (value shr 16).toByte()
        dst[offset + 2] = (value shr 8).toByte()
        dst[offset + 3] = value.toByte()
    }

    private fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(String.format("%02X", b))
        }
        return sb.toString()
    }

    /**
     * 把收到/回显的字节按 UTF-8 解码成可读文本；解码失败或含控制字符时退回 hex。
     * 可读文本用双引号包裹，二进制用 "hex:" 前缀，方便肉眼区分「字符串 vs 二进制」。
     */
    private fun bytesToDisplay(bytes: ByteArray): String {
        if (bytes.isEmpty()) {
            return "（空）"
        }
        val text = String(bytes, StandardCharsets.UTF_8)
        // 含替换字符（非法 UTF-8）→ 二进制
        if (text.indexOf('\uFFFD') >= 0) {
            return "hex:" + toHex(bytes)
        }
        // 含控制字符（除换行/回车/制表）→ 二进制
        for (i in text.indices) {
            val c = text[i]
            if (Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t') {
                return "hex:" + toHex(bytes)
            }
        }
        return "\"" + text + "\""
    }
}
