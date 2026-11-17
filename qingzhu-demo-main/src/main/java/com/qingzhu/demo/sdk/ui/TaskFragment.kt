// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.demo.sdk.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.qingzhu.ble.sdk.core.api.QTask
import com.qingzhu.demo.sdk.R
import com.qingzhu.demo.sdk.SdkManager
import com.qingzhu.demo.sdk.adapter.TaskAdapter
import com.qingzhu.demo.sdk.sheet.SettingsSheet

/**
 * 任务页：顶部统计条 + 执行中/待机中分组 + 右上角设置两个旋钮。
 * 只读 [SdkManager] 的任务数据，不直接碰 QSdk。
 */
class TaskFragment : Fragment() {

    private var adapter: TaskAdapter? = null
    private var statRunning: TextView? = null
    private var statIdle: TextView? = null
    private var statConn: TextView? = null

    private val taskListener: (List<QTask>) -> Unit = { tasks ->
        adapter?.submit(tasks)
        updateStats(tasks)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val root = inflater.inflate(R.layout.fragment_task, container, false)

        statRunning = root.findViewById(R.id.statRunning)
        statIdle = root.findViewById(R.id.statIdle)
        statConn = root.findViewById(R.id.statConn)

        val recycler = root.findViewById<RecyclerView>(R.id.taskRecycler)
        recycler.layoutManager = LinearLayoutManager(context)
        adapter = TaskAdapter()
        recycler.adapter = adapter

        root.findViewById<View>(R.id.btnSettings).setOnClickListener {
            SettingsSheet.show(requireContext())
        }

        return root
    }

    override fun onResume() {
        super.onResume()
        SdkManager.addTaskListener(taskListener)
        // 订阅后主动刷一次当前快照
        val tasks = SdkManager.tasks()
        adapter?.submit(tasks)
        updateStats(tasks)
    }

    override fun onPause() {
        super.onPause()
        SdkManager.removeTaskListener(taskListener)
    }

    private fun updateStats(tasks: List<QTask>) {
        statRunning?.text = tasks.count { it.state.isRunning }.toString()
        statIdle?.text = tasks.count { !it.state.isRunning }.toString()
        val max = SdkManager.getMaxConnections()
        statConn?.text = if (max == 0) "自动" else max.toString()
    }
}
