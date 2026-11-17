// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.demo.sdk.ui

import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.qingzhu.demo.sdk.R
import com.qingzhu.demo.sdk.sheet.CommandSheet
import com.qingzhu.sdk.QingzhuSdk

/**
 * 批量操作：输一堆 deviceMac，点「添加到任务」弹命令框，确认后逐个塞占位任务。
 */
class BatchAddActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_batch_add)

        // 标题栏返回按钮（系统返回键本来就走 finish，这里补界面上的入口）
        findViewById<android.view.View>(R.id.btnBack).setOnClickListener { finish() }

        val macInput = findViewById<EditText>(R.id.macInput)
        findViewById<android.view.View>(R.id.btnAdd).setOnClickListener {
            val macs = parseMacs(macInput.text.toString())
            if (macs.isEmpty()) {
                Toast.makeText(this, "先输入 deviceMac", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            CommandSheet.show(this, "${macs.size} 台设备", { command ->
                macs.forEach { QingzhuSdk.submit(it, command) }
                Toast.makeText(
                    this,
                    "${macs.size} 个任务已加入，完成后自动校准并发",
                    Toast.LENGTH_SHORT
                ).show()
                // 返回主界面后自动切到任务页
                com.qingzhu.demo.sdk.MainActivity.pendingSwitchToTask = true
                finish()
            }, false)
        }
    }

    private fun parseMacs(raw: String): List<String> =
        raw.split(Regex("[,\\s，；;]+")).map { it.trim() }.filter { it.isNotEmpty() }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        CommandSheet.onActivityResult(requestCode, resultCode, data)
    }
}
