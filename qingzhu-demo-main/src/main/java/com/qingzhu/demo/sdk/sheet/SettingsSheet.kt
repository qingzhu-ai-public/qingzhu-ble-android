// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.demo.sdk.sheet

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.qingzhu.demo.sdk.SdkManager

/**
 * 设置弹框：两个旋钮（同时连接数 = 自动学习 / 1-7，同时传输数 = 1-5）。
 * 通过 [SdkManager] 设置，不直接碰 QSdk。
 */
object SettingsSheet {

    fun show(ctx: Context) {
        val dialog = BottomSheetDialog(ctx)
        val root = LinearLayout(ctx)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(20), dp(10), dp(20), dp(24))

        val title = TextView(ctx)
        title.text = "设置"
        title.textSize = 16f
        title.setTypeface(Typeface.DEFAULT_BOLD)
        title.setTextColor(Color.parseColor("#20342B"))
        root.addView(title)

        // ===== 同时连接数 =====
        val savedConn = SdkManager.getMaxConnections()
        val curConcurrency = SdkManager.getConcurrency()
        addLabel(root, "同时连接数", "自动学习（SDK 自适应，当前建议 $curConcurrency），或手动指定 1-7")
        val connAuto = LinearLayout(ctx)
        connAuto.orientation = LinearLayout.HORIZONTAL
        root.addView(connAuto)
        connAuto.addView(chip(ctx, "自动学习（当前 $curConcurrency）", savedConn == 0))
        val connNums = LinearLayout(ctx)
        connNums.orientation = LinearLayout.HORIZONTAL
        connNums.setPadding(0, dp(6), 0, 0)
        root.addView(connNums)
        for (i in 1..7) {
            connNums.addView(chip(ctx, "$i", savedConn == i))
        }

        // 连接数选择逻辑（打开时按缓存回显，确认时写入缓存）
        var connAutoMode = savedConn == 0
        connAuto.getChildAt(0).setOnClickListener {
            connAutoMode = true
            setChipActive(connAuto.getChildAt(0) as TextView, true)
            for (j in 0 until connNums.childCount) {
                setChipActive(connNums.getChildAt(j) as TextView, false)
            }
        }
        for (i in 0 until connNums.childCount) {
            val idx = i
            connNums.getChildAt(i).setOnClickListener {
                connAutoMode = false
                setChipActive(connAuto.getChildAt(0) as TextView, false)
                for (j in 0 until connNums.childCount) {
                    setChipActive(connNums.getChildAt(j) as TextView, j == idx)
                }
            }
        }

        // ===== 同时传输数 =====
        val savedTrans = SdkManager.getMaxTransfers()
        addLabel(root, "同时传输数", "最多同时几台在发数据（大文件建议 1）")
        val transNums = LinearLayout(ctx)
        transNums.orientation = LinearLayout.HORIZONTAL
        root.addView(transNums)
        for (i in 1..7) {
            transNums.addView(chip(ctx, "$i", i == savedTrans))
        }
        for (i in 0 until transNums.childCount) {
            val idx = i
            transNums.getChildAt(i).setOnClickListener {
                for (j in 0 until transNums.childCount) {
                    setChipActive(transNums.getChildAt(j) as TextView, j == idx)
                }
            }
        }

        // ===== 取消 / 确认 =====
        val actions = LinearLayout(ctx)
        actions.orientation = LinearLayout.HORIZONTAL
        actions.setPadding(0, dp(20), 0, 0)
        root.addView(actions)
        actions.addView(actionBtn(ctx, "取消", false) { dialog.dismiss() })
        actions.addView(actionBtn(ctx, "确认", true) {
            // 读取连接数
            var conn = 0
            if (!connAutoMode) {
                for (j in 0 until connNums.childCount) {
                    val c = connNums.getChildAt(j) as TextView
                    if (isChipActive(c)) {
                        conn = c.text.toString().toInt()
                    }
                }
            }
            SdkManager.setMaxConnections(conn) // 0 = 自动学习

            var trans = 1
            for (j in 0 until transNums.childCount) {
                val c = transNums.getChildAt(j) as TextView
                if (isChipActive(c)) {
                    trans = c.text.toString().toInt()
                }
            }
            SdkManager.setMaxTransfers(trans)

            dialog.dismiss()
            Toast.makeText(
                ctx,
                "已设置：连接 " + (if (conn == 0) "自动" else conn) + " · 传输 $trans",
                Toast.LENGTH_SHORT
            ).show()
        })

        SheetStyle.prepare(dialog, ctx, root)
        dialog.setContentView(root)
        dialog.show()
    }

    private fun addLabel(root: LinearLayout, title: String, sub: String) {
        val t = TextView(root.context)
        t.text = title
        t.textSize = 14f
        t.setTypeface(Typeface.DEFAULT_BOLD)
        t.setTextColor(Color.parseColor("#20342B"))
        t.setPadding(0, dp(18), 0, 0)
        root.addView(t)
        val s = TextView(root.context)
        s.text = sub
        s.textSize = 11f
        s.setTextColor(Color.parseColor("#5B6F63"))
        s.setPadding(0, dp(3), 0, dp(8))
        root.addView(s)
    }

    private fun chip(ctx: Context, text: String, active: Boolean): TextView {
        val c = TextView(ctx)
        c.text = text
        c.textSize = 12f
        c.gravity = Gravity.CENTER
        c.setPadding(dp(12), dp(9), dp(12), dp(9))
        setChipActive(c, active)
        val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        lp.rightMargin = dp(6)
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

    private fun isChipActive(c: TextView): Boolean =
        c.textColors.defaultColor == Color.WHITE

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
