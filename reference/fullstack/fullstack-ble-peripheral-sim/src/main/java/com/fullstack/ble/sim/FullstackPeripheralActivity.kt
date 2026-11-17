package com.fullstack.ble.sim

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class FullstackPeripheralActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private lateinit var toggleButton: Button
    private lateinit var logText: TextView
    private var running = false
    private val peripheral by lazy {
        FullstackPeripheral(this, object : FullstackPeripheral.Listener {
            override fun onLog(message: String) = appendLog(message)
            override fun onRunningChanged(running: Boolean) {
                this@FullstackPeripheralActivity.running = running
                statusText.text = if (running) "广播与服务运行中" else "未运行"
                toggleButton.text = if (running) "停止" else "启动广播和服务"
            }
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fullstack_peripheral)
        statusText = findViewById(R.id.statusText)
        toggleButton = findViewById(R.id.toggleButton)
        logText = findViewById(R.id.logText)
        toggleButton.setOnClickListener {
            if (running) peripheral.stop() else ensurePermissionsAndStart()
        }
        appendLog("Service UUID: ${FullstackPeripheral.SERVICE_UUID}")
    }

    private fun ensurePermissionsAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val permissions = arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
            val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isNotEmpty()) {
                requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
                return
            }
        }
        peripheral.start()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS) return
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            peripheral.start()
        } else {
            appendLog("Bluetooth permissions were denied")
        }
    }

    private fun appendLog(message: String) {
        runOnUiThread {
            val updated = logText.text.toString() + message + "\n"
            logText.text = if (updated.length > 16_000) updated.takeLast(16_000) else updated
        }
    }

    override fun onDestroy() {
        peripheral.stop()
        super.onDestroy()
    }

    private companion object {
        const val REQUEST_PERMISSIONS = 1001
    }
}
