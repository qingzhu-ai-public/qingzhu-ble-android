// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.sim.generic

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView

/** 「日志」Tab —— 把 RecyclerView / 清空按钮 / 滚动到底按钮 全部托管给 MainActivity 内的 LogAdapter */
class LogFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_log, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val act = activity as? MainActivity ?: return
        val rv = view.findViewById<RecyclerView>(R.id.logRecyclerView)
        val btnBottom = view.findViewById<TextView>(R.id.btnScrollBottom)
        val btnClear = view.findViewById<Button>(R.id.btnClearLog)
        act.attachLogViews(rv, btnBottom, btnClear)
    }

    override fun onDestroyView() {
        (activity as? MainActivity)?.detachLogViews()
        super.onDestroyView()
    }
}