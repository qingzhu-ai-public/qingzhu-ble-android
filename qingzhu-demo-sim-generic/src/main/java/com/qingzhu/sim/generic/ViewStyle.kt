// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.sim.generic

import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat

/** 顶栏 + 「设备」Tab 共用的状态点 / 标签 样式工具。 */
object ViewStyle {
    fun applyRunningStatus(context: Context, dot: View, label: TextView, running: Boolean) {
        dot.background.setTint(
            ContextCompat.getColor(context,
                if (running) R.color.success else R.color.text_disabled)
        )
        label.text = if (running) "已启动" else "已停止"
        label.setTextColor(
            ContextCompat.getColor(context,
                if (running) R.color.success else R.color.text_tertiary)
        )
    }
}