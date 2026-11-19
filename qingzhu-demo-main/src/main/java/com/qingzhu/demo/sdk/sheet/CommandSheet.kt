// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.demo.sdk.sheet

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.qingzhu.ble.sdk.core.api.QCommand
import com.qingzhu.ble.sdk.core.api.QTask
import com.qingzhu.ble.sdk.core.api.TaskStage

/**
 * 命令选择弹框（扫描页「添加到任务」/ 任务页「执行」「发数据」/ 批量操作页共用）。
 *
 * [alreadyConnected]=true 时跳过「连接/发数据」选择，直接进发数据（已连上）。
 * 「发数据」的「文件」类型支持调起系统文件选择器，选中后回显文件名并读真实内容下发。
 */
object CommandSheet {

    private const val REQ_PICK_FILE = 0x2001
    /** 文件选择结果回调：只在 show 期间短暂挂起，Activity 转发 onActivityResult 后立刻消费 */
    private var pendingFilePick: ((Uri?) -> Unit)? = null

    // ===== 记住上次的选择（App 运行期间有效，下次打开弹框自动恢复，免重复选） =====
    private var lastCmd = "connect"
    private var lastSendType = "字符串"
    private var lastKeep = false
    private var lastString = "batch-hello"

    /** 对单个任务执行命令 */
    fun show(ctx: Context, task: QTask, alreadyConnected: Boolean) {
        show(ctx, task.deviceMac(), { command ->
            task.send(command) { result ->
                if (!result.isSuccess) {
                    val msg = when (result.stage()) {
                        TaskStage.BUSY -> "待办已满，请稍后再试"
                        TaskStage.CLOSED -> "任务已删除"
                        TaskStage.CANCEL, TaskStage.DELETE -> "已取消"
                        else -> "失败：${result.error()}"
                    }
                    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                }
            }
        }, alreadyConnected)
    }

    /** 通用弹框：确认后回调 command */
    fun show(ctx: Context, title: String, onCommand: (QCommand) -> Unit, alreadyConnected: Boolean) {
        val dialog = BottomSheetDialog(ctx)
        val root = LinearLayout(ctx)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(20), dp(10), dp(20), dp(24))

        val tvTitle = TextView(ctx)
        tvTitle.text = if (alreadyConnected) "发数据 · $title" else "执行 · $title"
        tvTitle.textSize = 16f
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD)
        tvTitle.setTextColor(Color.parseColor("#20342B"))
        tvTitle.setPadding(0, 0, 0, dp(12))
        root.addView(tvTitle)

        var cmd = if (alreadyConnected) "send" else lastCmd
        var sendType = lastSendType
        var keep = lastKeep
        var pickedUri: Uri? = null
        var pickedName: String? = null

        // ===== 命令选择（已连接时隐藏） =====
        val cmdOpts = LinearLayout(ctx)
        cmdOpts.orientation = LinearLayout.VERTICAL
        root.addView(cmdOpts)

        // ===== 发数据区（发什么 + 发完） =====
        val sendArea = LinearLayout(ctx)
        sendArea.orientation = LinearLayout.VERTICAL
        root.addView(sendArea)

        val typeLabel = TextView(ctx)
        typeLabel.text = "发什么"
        typeLabel.textSize = 11f
        typeLabel.setTextColor(Color.parseColor("#5B6F63"))
        typeLabel.setPadding(0, dp(4), 0, dp(8))
        sendArea.addView(typeLabel)

        val typeChips = LinearLayout(ctx)
        typeChips.orientation = LinearLayout.HORIZONTAL
        sendArea.addView(typeChips)

        val etString = EditText(ctx)
        etString.hint = "要发送的字符串，如 batch-hello"
        etString.setText(lastString)
        etString.textSize = 13f
        etString.setPadding(dp(12), dp(10), dp(12), dp(10))
        etString.background = ctx.getDrawable(com.qingzhu.demo.sdk.R.drawable.bg_input)
        etString.isSingleLine = true
        val etLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        etLp.topMargin = dp(10)
        sendArea.addView(etString, etLp)

        // 文件选择区（选「文件」类型时显示）：选择文件按钮 + 文件名回显
        val fileArea = LinearLayout(ctx)
        fileArea.orientation = LinearLayout.HORIZONTAL
        fileArea.gravity = Gravity.CENTER_VERTICAL
        fileArea.setPadding(0, dp(10), 0, 0)
        fileArea.visibility = View.GONE
        sendArea.addView(fileArea)

        val btnPickFile = TextView(ctx)
        btnPickFile.text = "选择文件"
        btnPickFile.textSize = 13f
        btnPickFile.gravity = Gravity.CENTER
        btnPickFile.setPadding(dp(14), dp(10), dp(14), dp(10))
        val pickBg = GradientDrawable()
        pickBg.cornerRadius = dp(10).toFloat()
        pickBg.setColor(Color.parseColor("#2F7D5F"))
        btnPickFile.setTextColor(Color.WHITE)
        btnPickFile.background = pickBg
        fileArea.addView(btnPickFile)

        val fileLabel = TextView(ctx)
        fileLabel.text = "未选择文件"
        fileLabel.textSize = 12f
        fileLabel.setTextColor(Color.parseColor("#5B6F63"))
        fileLabel.setPadding(dp(12), 0, 0, 0)
        fileArea.addView(fileLabel)

        val afterLabel = TextView(ctx)
        afterLabel.text = "发完"
        afterLabel.textSize = 11f
        afterLabel.setTextColor(Color.parseColor("#5B6F63"))
        afterLabel.setPadding(0, dp(14), 0, dp(8))
        sendArea.addView(afterLabel)

        val afterChips = LinearLayout(ctx)
        afterChips.orientation = LinearLayout.HORIZONTAL
        sendArea.addView(afterChips)

        // 填充类型 chips（按上次记住的类型选中）
        val types = arrayOf("字符串", "文件", "OTA")
        for (i in types.indices) {
            typeChips.addView(chip(ctx, types[i], types[i] == sendType))
        }
        for (i in 0 until typeChips.childCount) {
            val idx = i
            typeChips.getChildAt(i).setOnClickListener {
                sendType = types[idx]
                for (j in 0 until typeChips.childCount) {
                    setChipActive(typeChips.getChildAt(j) as TextView, j == idx)
                }
                etString.visibility = if (sendType == "字符串") View.VISIBLE else View.GONE
                fileArea.visibility = if (sendType == "文件") View.VISIBLE else View.GONE
            }
        }

        // 选择文件：调起系统文件选择器，选中后回显文件名
        btnPickFile.setOnClickListener {
            val activity = ctx as? Activity
            if (activity == null) {
                pickedUri = null
                pickedName = "demo_file.bin"
                fileLabel.text = pickedName
            } else {
                pendingFilePick = { uri ->
                    pickedUri = uri
                    pickedName = if (uri != null) queryDisplayName(ctx, uri) else null
                    fileLabel.text = pickedName ?: "未选择文件"
                }
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
                activity.startActivityForResult(intent, REQ_PICK_FILE)
            }
        }

        // 填充发完 chips（按上次记住的行为选中）
        afterChips.addView(chip(ctx, "自动断开", !keep))
        afterChips.addView(chip(ctx, "保持连接", keep))
        for (i in 0 until afterChips.childCount) {
            val idx = i
            afterChips.getChildAt(i).setOnClickListener {
                keep = idx == 1
                for (j in 0 until afterChips.childCount) {
                    setChipActive(afterChips.getChildAt(j) as TextView, j == idx)
                }
            }
        }

        // 填充命令选项（已连接时跳过）
        if (!alreadyConnected) {
            val optConnect = option(ctx, "连接", "自动寻找 → 连接，连上并保持")
            val optSend = option(ctx, "发数据", "自动寻找 → 连接 → 发送 → 回显")
            cmdOpts.addView(optConnect)
            cmdOpts.addView(optSend)

            val selectCmd = { which: String ->
                cmd = which
                setOptionActive(optConnect, which == "connect")
                setOptionActive(optSend, which == "send")
                sendArea.visibility = if (which == "send") View.VISIBLE else View.GONE
            }
            optConnect.setOnClickListener { selectCmd("connect") }
            optSend.setOnClickListener { selectCmd("send") }
            selectCmd(cmd) // 恢复上次的选择
        } else {
            cmdOpts.visibility = View.GONE
            sendArea.visibility = View.VISIBLE
        }

        // 按记住的发送类型初始化输入框 / 文件区可见性（与 chip 选中态一致）
        etString.visibility = if (sendType == "字符串") View.VISIBLE else View.GONE
        fileArea.visibility = if (sendType == "文件") View.VISIBLE else View.GONE

        // ===== 取消 / 确认 =====
        val actions = LinearLayout(ctx)
        actions.orientation = LinearLayout.HORIZONTAL
        actions.setPadding(0, dp(18), 0, 0)
        root.addView(actions)
        actions.addView(actionBtn(ctx, "取消", false) { dialog.dismiss() })
        actions.addView(actionBtn(ctx, "确认", true) {
            // 记住本次选择，下次打开弹框自动恢复（免重复选）
            lastCmd = cmd
            lastSendType = sendType
            lastKeep = keep
            lastString = etString.text.toString()
            val c = if (cmd == "connect") {
                QCommand.builder().connect().build()
            } else {
                val (json, data) = buildSendPayload(ctx, sendType, etString.text.toString(), pickedUri, pickedName)
                val b = QCommand.builder().send(json, data)
                if (keep) {
                    b.keepConnected()
                }
                b.build()
            }
            dialog.dismiss()
            onCommand(c)
        })

        SheetStyle.prepare(dialog, ctx, root)
        dialog.setContentView(root)
        dialog.show()
    }

    /**
     * Activity 的 onActivityResult 转发入口：MainActivity / BatchAddActivity 各自转发一次即可，
     * 用于把「系统文件选择器」的选中结果回填给正在展示的弹框。
     */
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQ_PICK_FILE) {
            return
        }
        val cb = pendingFilePick
        pendingFilePick = null
        val uri = data?.data
        cb?.invoke(if (resultCode == Activity.RESULT_OK && uri != null) uri else null)
    }

    // ============ 工具 ============

    /** 按发送类型构建 JSON 指令 + 附件数据；文件类型读真实文件内容 */
    private fun buildSendPayload(
        ctx: Context, type: String, text: String, uri: Uri?, fileName: String?
    ): Pair<String, ByteArray?> {
        return when (type) {
            "文件" -> {
                val name = fileName ?: "file.bin"
                val json = "{\"action\":\"file\",\"name\":\"$name\"}"
                val data = if (uri != null) readUriBytes(ctx, uri) else ByteArray(32 * 1024)
                json to data
            }

            "OTA" -> "{\"action\":\"ota\"}" to ByteArray(64 * 1024)
            else -> "{\"action\":\"echo\",\"text\":\"$text\"}" to null
        }
    }

    private fun queryDisplayName(ctx: Context, uri: Uri): String? {
        return try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            uri.lastPathSegment
        }
    }

    private fun readUriBytes(ctx: Context, uri: Uri): ByteArray? {
        return try {
            ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (e: Exception) {
            null
        }
    }

    private fun option(ctx: Context, title: String, sub: String): LinearLayout {
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(14), dp(13), dp(14), dp(13))
        row.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) }

        // 标题与副标题拆成两个 TextView：原来挤在同一个 13sp 里，主次不分且行距过紧
        val textCol = LinearLayout(ctx)
        textCol.orientation = LinearLayout.VERTICAL
        val titleView = TextView(ctx)
        titleView.text = title
        titleView.textSize = 13f
        titleView.setTypeface(Typeface.DEFAULT_BOLD)
        titleView.setTextColor(Color.parseColor("#20342B"))
        textCol.addView(titleView)
        val subView = TextView(ctx)
        subView.text = sub
        subView.textSize = 10.5f
        subView.setTextColor(Color.parseColor("#5B6F63"))
        subView.setPadding(0, dp(3), 0, 0)
        textCol.addView(subView)

        val tvLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        tvLp.rightMargin = dp(12)
        row.addView(textCol, tvLp)

        // 单选圆点（● 选中 / ○ 未选中）—— 保持 index 1，setOptionActive 依赖此位置
        val radio = TextView(ctx)
        radio.text = "○"
        radio.textSize = 18f
        radio.setTextColor(Color.parseColor("#E6E9DF"))
        radio.gravity = Gravity.CENTER
        row.addView(radio)

        return row
    }

    /** 选中态：实心圆点 + 淡蓝背景高亮，未选中空心圆点透明背景 */
    private fun setOptionActive(row: LinearLayout, active: Boolean) {
        val radio = row.getChildAt(1) as TextView
        radio.text = if (active) "●" else "○"
        radio.setTextColor(Color.parseColor(if (active) "#2F7D5F" else "#E6E9DF"))
        val bg = GradientDrawable()
        bg.cornerRadius = dp(12).toFloat()
        if (active) {
            bg.setColor(Color.parseColor("#E9F3EC"))
            bg.setStroke(dp(1), Color.parseColor("#2F7D5F"))
        } else {
            bg.setColor(Color.TRANSPARENT)
            bg.setStroke(dp(1), Color.parseColor("#E6E9DF"))
        }
        row.background = bg
    }

    private fun chip(ctx: Context, text: String, active: Boolean): TextView {
        val c = TextView(ctx)
        c.text = text
        c.textSize = 12f
        c.gravity = Gravity.CENTER
        c.setPadding(dp(14), dp(9), dp(14), dp(9))
        setChipActive(c, active)
        val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        lp.rightMargin = dp(8)
        c.layoutParams = lp
        return c
    }

    private fun setChipActive(c: TextView, active: Boolean) {
        val bg = GradientDrawable()
        bg.cornerRadius = dp(10).toFloat()
        if (active) {
            bg.setColor(Color.parseColor("#2F7D5F"))
            c.setTextColor(Color.WHITE)
        } else {
            bg.setColor(Color.parseColor("#F2F6F0"))
            c.setTextColor(Color.parseColor("#5B6F63"))
        }
        c.background = bg
    }

    private fun actionBtn(ctx: Context, text: String, primary: Boolean, l: () -> Unit): TextView {
        val btn = TextView(ctx)
        btn.text = text
        btn.textSize = 14f
        btn.gravity = Gravity.CENTER
        btn.setPadding(dp(14), dp(14), dp(14), dp(14))
        val bg = GradientDrawable()
        bg.cornerRadius = dp(12).toFloat()
        if (primary) {
            bg.setColor(Color.parseColor("#2F7D5F"))
            btn.setTextColor(Color.WHITE)
        } else {
            bg.setColor(Color.parseColor("#F2F6F0"))
            btn.setTextColor(Color.parseColor("#5B6F63"))
        }
        btn.background = bg
        btn.setOnClickListener { l() }
        val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        lp.rightMargin = dp(10)
        btn.layoutParams = lp
        return btn
    }

    private fun dp(v: Int): Int = (v * 3f).toInt()
}
