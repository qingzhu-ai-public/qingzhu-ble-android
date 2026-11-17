// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.demo.sdk.sheet

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.qingzhu.demo.sdk.R

/**
 * 青竹底部弹层的统一外观：顶部 24dp 圆角白面板 + 居中拖动条。
 *
 * BottomSheetDialog 自带方角白底，直接给 contentView 设圆角会被它盖住，
 * 所以在 onShow 时替换 design_bottom_sheet 的 background。
 */
object SheetStyle {

    fun prepare(dialog: BottomSheetDialog, ctx: Context, root: LinearLayout) {
        dialog.setOnShowListener {
            dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.background =
                ContextCompat.getDrawable(ctx, R.drawable.bg_sheet)
        }
        val grab = View(ctx)
        grab.background = ContextCompat.getDrawable(ctx, R.drawable.bg_grab)
        val lp = LinearLayout.LayoutParams(dp(36), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(14)
        }
        root.addView(grab, 0, lp)
    }

    fun dp(v: Int): Int = (v * 3f).toInt()
}
