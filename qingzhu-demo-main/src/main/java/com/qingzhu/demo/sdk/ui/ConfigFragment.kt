// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.demo.sdk.ui

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.qingzhu.ble.sdk.core.protocol.ProtocolParam
import com.qingzhu.ble.sdk.core.settings.BleSettings
import com.qingzhu.demo.sdk.R
import com.qingzhu.demo.sdk.SdkManager
import com.qingzhu.demo.sdk.adapter.ConfigAdapter

/**
 * 配置页：展示 SDK 全部可配项（BleSettings.listAll），点击修改，App 缓存持久化。
 */
class ConfigFragment : Fragment() {

    private var adapter: ConfigAdapter? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val root = inflater.inflate(R.layout.fragment_config, container, false)

        val recycler = root.findViewById<RecyclerView>(R.id.configRecycler)
        recycler.layoutManager = LinearLayoutManager(context)
        adapter = ConfigAdapter { item -> onItemClick(item) }
        recycler.adapter = adapter

        root.findViewById<View>(R.id.btnResetAll).setOnClickListener {
            SdkManager.resetAllConfig()
            refresh()
            Toast.makeText(context, "已恢复全部默认值", Toast.LENGTH_SHORT).show()
        }

        return root
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        adapter?.submit(SdkManager.configItems())
    }

    private fun onItemClick(item: BleSettings.SettingItem) {
        when (item.type) {
            ProtocolParam.Type.BOOLEAN -> {
                // 布尔：直接切换
                val next = !(item.currentValue as? Boolean ?: false)
                SdkManager.setConfig(item.key, next)
                refresh()
                Toast.makeText(context, "${item.key} = $next", Toast.LENGTH_SHORT).show()
            }

            else -> {
                // 数字 / 字符串：弹框输入
                showInputDialog(item)
            }
        }
    }

    private fun showInputDialog(item: BleSettings.SettingItem) {
        val ctx = context ?: return
        val input = EditText(ctx)
        input.setText(item.currentValue.toString())
        input.inputType = when (item.type) {
            ProtocolParam.Type.INT -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            ProtocolParam.Type.LONG, ProtocolParam.Type.DOUBLE ->
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or InputType.TYPE_NUMBER_FLAG_DECIMAL
            else -> InputType.TYPE_CLASS_TEXT
        }
        input.hint = "默认 ${item.defaultValue}"
        val wrap = LinearLayout(ctx)
        wrap.setPadding(48, 16, 48, 0)
        wrap.addView(input, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        AlertDialog.Builder(ctx)
            .setTitle(item.key)
            .setMessage(item.description)
            .setView(wrap)
            .setNegativeButton("恢复默认") { _, _ ->
                SdkManager.resetConfig(item.key)
                refresh()
            }
            .setPositiveButton("确定") { _, _ ->
                val text = input.text.toString().trim()
                applyInput(item, text)
            }
            .show()
    }

    private fun applyInput(item: BleSettings.SettingItem, text: String) {
        try {
            val value: Any = when (item.type) {
                ProtocolParam.Type.INT -> text.toInt()
                ProtocolParam.Type.LONG -> text.toLong()
                ProtocolParam.Type.DOUBLE -> text.toDouble()
                else -> text
            }
            SdkManager.setConfig(item.key, value)
            refresh()
            Toast.makeText(context, "${item.key} = $value（已缓存，重启生效）", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "输入格式错误：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
