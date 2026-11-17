// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.demo.sdk.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.qingzhu.ble.sdk.core.device.QDevice
import com.qingzhu.demo.sdk.R
import com.qingzhu.demo.sdk.SdkManager
import com.qingzhu.demo.sdk.adapter.DeviceAdapter
import com.qingzhu.demo.sdk.sheet.CommandSheet

/**
 * 扫描页（资源市场）：发现设备 + 勾选批量添加到任务，右上角「+」进批量操作。
 * 只读 [SdkManager] 的设备数据、通过 [SdkManager] 加任务，不直接碰 QSdk。
 */
class ScanFragment : Fragment() {

    private var adapter: DeviceAdapter? = null
    private var btnAdd: MaterialButton? = null
    private var spinner: View? = null
    private var statusText: TextView? = null

    private val deviceListener: (List<QDevice>) -> Unit = { devices -> adapter?.submit(devices) }
    private val scanStateListener: (Boolean) -> Unit = { scanning -> updateScanStatus(scanning) }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val root = inflater.inflate(R.layout.fragment_scan, container, false)

        val recycler = root.findViewById<RecyclerView>(R.id.deviceRecycler)
        recycler.layoutManager = LinearLayoutManager(context)
        adapter = DeviceAdapter { refreshAddBtn() }
        recycler.adapter = adapter

        btnAdd = root.findViewById(R.id.btnAddTasks)
        btnAdd?.setOnClickListener {
            val selected = adapter?.getSelected().orEmpty()
            if (selected.isEmpty()) {
                Toast.makeText(requireContext(), "请先勾选设备", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            CommandSheet.show(requireContext(), "${selected.size} 台设备", { command ->
                selected.forEach { SdkManager.submit(it, command) }
                // 确认后自动切到任务页，看任务执行
                (activity as? com.qingzhu.demo.sdk.MainActivity)?.switchToTaskTab()
            }, false)
        }

        root.findViewById<View>(R.id.btnSelectAll).setOnClickListener { adapter?.selectAll() }
        root.findViewById<View>(R.id.btnBatchAdd).setOnClickListener {
            startActivity(Intent(context, BatchAddActivity::class.java))
        }

        // 扫描状态指示：扫描中显示滚动图标 + 文字，停止后点击重新开扫
        spinner = root.findViewById(R.id.scanStatusSpinner)
        statusText = root.findViewById(R.id.scanStatusText)
        root.findViewById<View>(R.id.scanStatusBar).setOnClickListener {
            if (!SdkManager.isScanning()) {
                SdkManager.restartScan()
            }
        }

        return root
    }

    override fun onResume() {
        super.onResume()
        SdkManager.addDeviceListener(deviceListener)
        // 订阅后主动刷一次当前快照（扫描由 SdkManager 持续跑，这里只拿数据）
        adapter?.submit(SdkManager.devices())
        // 订阅扫描状态 + 立即刷新一次
        SdkManager.addScanStateListener(scanStateListener)
    }

    override fun onPause() {
        super.onPause()
        SdkManager.removeDeviceListener(deviceListener)
        SdkManager.removeScanStateListener(scanStateListener)
    }

    /** 扫描状态变化：扫描中 = 滚动图标 + 「扫描中...」；停止 = 隐藏图标 + 「扫描已停止」 */
    private fun updateScanStatus(scanning: Boolean) {
        spinner?.visibility = if (scanning) View.VISIBLE else View.GONE
        statusText?.text = if (scanning) "扫描中..." else "扫描已停止"
    }

    /** 按钮常驻：只更新计数，不再因未选中而隐藏 */
    private fun refreshAddBtn() {
        val n = adapter?.selectedCount() ?: 0
        btnAdd?.text = if (n > 0) "添加到任务（$n）" else "添加到任务"
    }
}
