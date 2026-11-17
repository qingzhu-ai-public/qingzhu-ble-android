package com.fullstack.ble.demo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.fullstack.ble.sdk.FullstackBleConnection
import com.fullstack.ble.sdk.FullstackBleConnectionConfig
import com.fullstack.ble.sdk.FullstackBleConnectionListener
import com.fullstack.ble.sdk.FullstackBleConnectionState
import com.fullstack.ble.sdk.FullstackBleDevice
import com.fullstack.ble.sdk.FullstackBleException
import com.fullstack.ble.sdk.FullstackBleOperationCallback
import com.fullstack.ble.sdk.FullstackBleScanConfig
import com.fullstack.ble.sdk.FullstackBleScanListener
import com.fullstack.ble.sdk.FullstackBleSdk
import com.fullstack.ble.sdk.FullstackBleWriteCallback
import java.util.UUID

class FullstackDemoActivity : AppCompatActivity() {
    private lateinit var sdk: FullstackBleSdk
    private lateinit var statusText: TextView
    private lateinit var payloadInput: EditText
    private lateinit var sendButton: Button
    private lateinit var logText: TextView
    private lateinit var rowsAdapter: ArrayAdapter<String>
    private val devices = linkedMapOf<String, FullstackBleDevice>()
    private val rows = mutableListOf<String>()
    private var connection: FullstackBleConnection? = null
    private var pendingAddress: String? = null
    private var reconnectDevice: FullstackBleDevice? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fullstack_demo)
        sdk = FullstackBleSdk(this)
        statusText = findViewById(R.id.statusText)
        payloadInput = findViewById(R.id.payloadInput)
        sendButton = findViewById(R.id.sendButton)
        logText = findViewById(R.id.logText)
        val deviceList = findViewById<ListView>(R.id.deviceList)
        rowsAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, rows)
        deviceList.adapter = rowsAdapter
        deviceList.setOnItemClickListener { _, _, position, _ ->
            devices.values.elementAtOrNull(position)?.let(::connectTo)
        }
        findViewById<Button>(R.id.scanButton).setOnClickListener { ensurePermissionsAndScan() }
        findViewById<Button>(R.id.stopScanButton).setOnClickListener { sdk.stopScan() }
        sendButton.setOnClickListener { sendPayload() }
        appendLog("Service UUID: $SERVICE_UUID")
        appendLog("请先启动 Fullstack BLE Peripheral，再开始扫描")
    }

    private fun ensurePermissionsAndScan() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
            return
        }
        startScan()
    }

    private fun startScan() {
        sdk.stopScan()
        pendingAddress = null
        devices.clear()
        refreshDevices()
        statusText.text = "扫描中…"
        sdk.startScan(
            FullstackBleScanConfig(serviceUuid = SERVICE_UUID, timeoutMs = 20_000L),
            object : FullstackBleScanListener {
                override fun onScanStarted() {
                    statusText.text = "扫描中…"
                    appendLog("Scan started")
                }

                override fun onDeviceFound(device: FullstackBleDevice) {
                    devices[device.address] = device
                    refreshDevices()
                }

                override fun onScanStopped() {
                    if (pendingAddress == null && connection?.isReady != true) statusText.text = "扫描已停止"
                    appendLog("Scan stopped")
                }

                override fun onError(error: FullstackBleException) {
                    statusText.text = "扫描失败：${error.code}"
                    appendLog(formatError("Scan", error))
                }
            },
        )
    }

    private fun refreshDevices() {
        rows.clear()
        devices.values.forEach { device ->
            rows.add("${device.name ?: "Fullstack peripheral"}\n${device.address}  RSSI ${device.rssi}")
        }
        rowsAdapter.notifyDataSetChanged()
    }

    private fun connectTo(device: FullstackBleDevice) {
        sdk.stopScan()
        val existing = connection
        if (existing != null) {
            if (existing.address == device.address && existing.isReady) {
                statusText.text = "设备已连接"
                sendButton.isEnabled = true
                return
            }
            reconnectDevice = device
            pendingAddress = null
            connection = null
            sendButton.isEnabled = false
            statusText.text = "正在释放旧连接…"
            existing.disconnect()
            return
        }
        if (pendingAddress != null) {
            appendLog("Another connection attempt is still active")
            return
        }
        reconnectDevice = null
        sendButton.isEnabled = false
        pendingAddress = device.address
        statusText.text = "连接 ${device.address}…"
        appendLog("Connecting to ${device.address}")
        sdk.connect(
            device,
            FullstackBleConnectionConfig(
                serviceUuid = SERVICE_UUID,
                writeCharacteristicUuid = WRITE_UUID,
                notifyCharacteristicUuid = NOTIFY_UUID,
            ),
            object : FullstackBleConnectionListener {
                override fun onStateChanged(state: FullstackBleConnectionState) = appendLog("Connection state: $state")

                override fun onConnected(newConnection: FullstackBleConnection) {
                    if (pendingAddress != device.address) {
                        newConnection.disconnect()
                        return
                    }
                    connection = newConnection
                    statusText.text = "服务已就绪，正在初始化…"
                    appendLog("Connected and services discovered")
                    newConnection.requestMtu(247, FullstackBleOperationCallback { mtuError ->
                        appendLog(mtuError?.let { formatError("MTU", it) } ?: "MTU request completed")
                        newConnection.enableNotifications(FullstackBleOperationCallback { notifyError ->
                            if (notifyError == null && newConnection.isReady) {
                                statusText.text = "已连接，可以发送数据"
                                sendButton.isEnabled = true
                                appendLog("Notify enabled")
                            } else {
                                statusText.text = "Notify 开启失败"
                                notifyError?.let { appendLog(formatError("Notify", it)) }
                            }
                        })
                    })
                }

                override fun onDataReceived(data: ByteArray) {
                    appendLog("Received: ${String(data, Charsets.UTF_8)}")
                }

                override fun onDisconnected(error: FullstackBleException?) {
                    if (pendingAddress == device.address) {
                        pendingAddress = null
                        connection = null
                        sendButton.isEnabled = false
                        statusText.text = "连接已断开"
                    }
                    appendLog(error?.let { formatError("Disconnected", it) } ?: "Disconnected by request")
                    val retry = reconnectDevice
                    if (retry != null) {
                        reconnectDevice = null
                        statusText.post { connectTo(retry) }
                    }
                }
            },
        )
    }

    private fun sendPayload() {
        val current = connection ?: return
        val data = payloadInput.text.toString().toByteArray(Charsets.UTF_8)
        if (data.isEmpty()) {
            appendLog("Payload is empty")
            return
        }
        if (data.size > 20) {
            statusText.text = "单条演示命令最多 20 字节"
            appendLog("Payload exceeds the demo protocol limit of 20 bytes")
            return
        }
        sendButton.isEnabled = false
        appendLog("Sending ${data.size} bytes")
        current.write(data, object : FullstackBleWriteCallback {
            override fun onProgress(sentBytes: Int, totalBytes: Int) {
                if (totalBytes > 20) appendLog("Write progress: $sentBytes/$totalBytes")
            }

            override fun onComplete(error: FullstackBleException?) {
                sendButton.isEnabled = current.isReady
                if (error == null) {
                    statusText.text = "发送完成，等待 Notify"
                    appendLog("Write completed")
                } else {
                    statusText.text = "发送失败：${error.code}"
                    appendLog(formatError("Write", error))
                }
            }
        })
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS) return
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startScan()
        } else {
            statusText.text = "蓝牙权限被拒绝"
            appendLog("Bluetooth permissions were denied")
        }
    }

    private fun formatError(prefix: String, error: FullstackBleException): String =
        "$prefix error: ${error.code}, nativeStatus=${error.nativeStatus}, message=${error.message}"

    private fun appendLog(message: String) {
        runOnUiThread {
            val updated = logText.text.toString() + message + "\n"
            logText.text = if (updated.length > 20_000) updated.takeLast(20_000) else updated
        }
    }

    override fun onDestroy() {
        sdk.close()
        super.onDestroy()
    }

    private companion object {
        const val REQUEST_PERMISSIONS = 1002
        val SERVICE_UUID: UUID = UUID.fromString("f5a50001-6f7d-4c2f-9f6e-5a41e6f10000")
        val WRITE_UUID: UUID = UUID.fromString("f5a50002-6f7d-4c2f-9f6e-5a41e6f10000")
        val NOTIFY_UUID: UUID = UUID.fromString("f5a50003-6f7d-4c2f-9f6e-5a41e6f10000")
    }
}
