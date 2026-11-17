// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.demo.sdk.adapter

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.qingzhu.ble.sdk.core.api.QTask
import com.qingzhu.ble.sdk.core.api.TaskState
import com.qingzhu.demo.sdk.R
import com.qingzhu.demo.sdk.sheet.CommandSheet
import com.qingzhu.demo.sdk.util.TransferStats
import com.qingzhu.protocol.generic.scan.GenericRecognizer
import java.util.Locale

/**
 * 任务列表适配器：分「执行中 / 待机中」两组，按钮随状态变。
 */
class TaskAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<Any>() // String(header) 或 QTask

    fun submit(tasks: List<QTask>) {
        items.clear()
        val running = tasks.filter { it.state.isRunning }
        val idle = tasks.filter { !it.state.isRunning }
        items.add("执行中（在池里 / 有命令）· ${running.size}")
        items.addAll(running)
        items.add("待机中（无命令 / 空闲）· ${idle.size}")
        items.addAll(idle)
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int =
        if (items[position] is QTask) TYPE_TASK else TYPE_HEADER

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        if (viewType == TYPE_HEADER) {
            val tv = TextView(parent.context)
            tv.textSize = 12f
            tv.setTextColor(Color.parseColor("#5B6F63"))
            tv.setPadding(4, 14, 4, 6)
            return object : RecyclerView.ViewHolder(tv) {}
        }
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_task, parent, false)
        return TaskHolder(v)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        if (item is String) {
            (holder.itemView as TextView).text = item
            return
        }
        (holder as TaskHolder).bind(item as QTask)
    }

    override fun getItemCount(): Int = items.size

    // ============ ViewHolder ============

    class TaskHolder(v: View) : RecyclerView.ViewHolder(v) {
        private val name: TextView = v.findViewById(R.id.taskName)
        private val pill: TextView = v.findViewById(R.id.taskPill)
        private val mac: TextView = v.findViewById(R.id.taskMac)
        private val bleMac: TextView = v.findViewById(R.id.taskBleMac)
        private val progressRow: LinearLayout = v.findViewById(R.id.taskProgressRow)
        private val progress: ProgressBar = v.findViewById(R.id.taskProgress)
        private val size: TextView = v.findViewById(R.id.taskSize)
        private val note: TextView = v.findViewById(R.id.taskNote)
        private val actions: LinearLayout = v.findViewById(R.id.taskActions)

        fun bind(t: QTask) {
            val s = t.state
            val deviceName = t.device?.name ?: "设备"
            name.text = "$deviceName  [${stateText(s)}]"
            // 第一行：稳定 deviceMac（身份）；第二行：bleMac（会轮换）。占位任务 device 为 null 时退 bleMac
            val dm = t.device?.getMetadata<String>(GenericRecognizer.META_DEVICE_MAC)
            mac.text = "deviceMac " + (dm ?: t.deviceMac())
            // bleMac 用「当前实际操作的地址」：连接时重寻址会更新，设备换址后任务行实时刷新
            bleMac.text = "bleMac " + t.currentMac()
            val pending = t.pendingIntentCount()
            pill.text = buildString {
                append(if (s.isRunning) "执行中" else "待机中")
                if (pending > 0) append(" · 待执行 $pending")
            }
            pill.background = pillBg(s)

            // 进度 + 实时速率
            if (s == TaskState.SENDING) {
                val stats = statsMap.getOrPut(t.id()) { TransferStats() }
                stats.onProgress(t.sentBytes, t.totalBytes)
                progressRow.visibility = View.VISIBLE
                progress.progress = t.progress
                size.text = formatSize(t.totalBytes.toLong()) // 进度条右侧显示总大小
                note.visibility = View.VISIBLE
                note.text = "发送中 ${t.progress}% · ${formatSpeed(stats.instantSpeed)}"
            } else {
                progressRow.visibility = View.GONE
                val stats = statsMap[t.id()]
                if (stats != null && stats.totalBytes > 0) {
                    // 传输完成：显示「大小 · 耗时 · 平均速度」
                    stats.markDone()
                    note.visibility = View.VISIBLE
                    note.text = "${formatSize(stats.totalBytes.toLong())} · 耗时 ${formatDuration(stats.doneMs())} · ${formatSpeed(stats.avgSpeed)}"
                } else {
                    val r = t.result
                    if (r != null) {
                        note.visibility = View.VISIBLE
                        note.text = "回显：$r"
                    } else if (s == TaskState.CONNECTED) {
                        note.visibility = View.VISIBLE
                        note.text = "保持连接中，占 1 个连接窗口"
                    } else {
                        note.visibility = View.GONE
                        note.text = ""
                    }
                }
            }

            // 按钮
            actions.removeAllViews()
            when (s) {
                TaskState.READY, TaskState.UNKNOWN ->
                    addBtn(actions, "执行", Color.parseColor("#2F7D5F"), true) {
                        showCommandSheet(t, false)
                    }

                TaskState.CONNECTED -> {
                    addBtn(actions, "断开", Color.parseColor("#5B6F63"), false) { t.cancel() }
                    addBtn(actions, "发数据", Color.parseColor("#2F7D5F"), true) {
                        showCommandSheet(t, true)
                    }
                }

                else -> // 执行中（SEARCHING/QUEUED/CONNECTING/SENDING）
                    addBtn(actions, "取消", Color.parseColor("#5B6F63"), false) { t.cancel() }
            }
            addBtn(actions, "删除", Color.parseColor("#C0553A"), true) { t.delete() }
        }

        private fun showCommandSheet(t: QTask, alreadyConnected: Boolean) {
            CommandSheet.show(itemView.context, t, alreadyConnected)
        }

        private fun stateText(s: TaskState): String = when (s) {
            TaskState.UNKNOWN -> "未知设备"
            TaskState.READY -> "就绪"
            TaskState.SEARCHING -> "寻找中"
            TaskState.QUEUED -> "排队中"
            TaskState.CONNECTING -> "连接中"
            TaskState.CONNECTED -> "已连接"
            TaskState.SENDING -> "发送中"
        }

        private fun pillBg(s: TaskState): GradientDrawable {
            val d = GradientDrawable()
            d.cornerRadius = 20f
            val color = if (s.isRunning) {
                if (s == TaskState.CONNECTED) Color.parseColor("#3F956C") else Color.parseColor("#2F7D5F")
            } else {
                Color.parseColor("#9AA99D")
            }
            d.setColor(withAlpha(color, 30))
            return d
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_TASK = 1
    }
}

private fun addBtn(parent: LinearLayout, text: String, color: Int, filled: Boolean, l: () -> Unit) {
    val btn = TextView(parent.context)
    btn.text = text
    btn.textSize = 12f
    btn.setPadding(dp(14), dp(7), dp(14), dp(7))
    btn.gravity = Gravity.CENTER
    val bg = GradientDrawable()
    bg.cornerRadius = dp(6).toFloat()
    if (filled) {
        bg.setColor(color)
        btn.setTextColor(Color.WHITE)
    } else {
        bg.setColor(withAlpha(color, 25))
        btn.setTextColor(color)
    }
    btn.background = bg
    btn.setOnClickListener { l() }
    val lp = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )
    lp.rightMargin = dp(8)
    parent.addView(btn, lp)
}

private fun withAlpha(color: Int, alpha: Int): Int =
    Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

private fun dp(v: Int): Int = (v * 3f).toInt()

// ============ 传输速率统计（按任务 id 记忆历史，算实时/平均速率） ============

private val statsMap = mutableMapOf<String, TransferStats>()

private fun formatSize(bytes: Long): String = when {
    bytes >= 100 * 1024 -> String.format(Locale.US, "%.2f MB", bytes / 1048576.0)
    bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}

private fun formatDuration(ms: Long): String {
    if (ms >= 60_000) {
        val m = ms / 60_000
        val s = (ms % 60_000) / 1000
        return "${m}分${s}秒"
    }
    return String.format(Locale.US, "%.1f秒", ms / 1000.0)
}

private fun formatSpeed(bytesPerSec: Long): String {
    if (bytesPerSec <= 0) {
        return "0 KB/s"
    }
    val kbPerSec = bytesPerSec / 1024.0
    return if (kbPerSec >= 1024) {
        String.format(Locale.US, "%.2f MB/s", kbPerSec / 1024.0)
    } else {
        String.format(Locale.US, "%.1f KB/s", kbPerSec)
    }
}
