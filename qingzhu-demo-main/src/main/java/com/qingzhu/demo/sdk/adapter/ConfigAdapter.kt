// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.demo.sdk.adapter

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.qingzhu.ble.sdk.core.settings.BleSettings
import com.qingzhu.demo.sdk.R

/**
 * SDK 可配项列表：按 key 前缀分组（连接与写入 / 心跳探活 / 其它），
 * 组内一行一项，整张列表由 fragment_config 的白卡背景包住。
 */
class ConfigAdapter(
    private val onItemClick: (BleSettings.SettingItem) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<Any>() // String(分组标题) 或 SettingItem

    fun submit(list: List<BleSettings.SettingItem>) {
        items.clear()
        // listAll() 按注册顺序返回，同一组的 key 可能交错；先按组排序，
        // 否则「组名变化即加标题」会让同名标题反复出现。
        val ordered = list.sortedBy { groupRank(it.key) }
        var lastGroup = ""
        for (item in ordered) {
            val group = groupName(item.key)
            if (group != lastGroup) {
                items.add(group)
                lastGroup = group
            }
            items.add(item)
        }
        notifyDataSetChanged()
    }

    private fun groupRank(key: String): Int = when {
        key.startsWith("ble.") -> 0
        key.startsWith("heartbeat.") -> 1
        else -> 2
    }

    private fun groupName(key: String): String = when (groupRank(key)) {
        0 -> "连接与写入"
        1 -> "心跳探活"
        else -> "其它参数"
    }

    override fun getItemViewType(position: Int): Int =
        if (items[position] is String) TYPE_HEADER else TYPE_ITEM

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        if (viewType == TYPE_HEADER) {
            val tv = TextView(parent.context)
            tv.textSize = 11f
            tv.setTypeface(null, Typeface.BOLD)
            tv.setTextColor(Color.parseColor("#2F7D5F"))
            tv.setPadding(dp(4), dp(10), dp(4), dp(6))
            return object : RecyclerView.ViewHolder(tv) {}
        }
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_config, parent, false)
        return Holder(v)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        if (item is String) {
            (holder.itemView as TextView).text = item
        } else {
            (holder as Holder).bind(item as BleSettings.SettingItem)
        }
    }

    override fun getItemCount(): Int = items.size

    inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
        private val key: TextView = v.findViewById(R.id.configKey)
        private val value: TextView = v.findViewById(R.id.configValue)
        private val desc: TextView = v.findViewById(R.id.configDesc)

        fun bind(item: BleSettings.SettingItem) {
            key.text = item.key
            value.text = "${item.currentValue}  [${item.type}]"
            desc.text = if (item.isModified) {
                "${item.description}\n（默认 ${item.defaultValue}，已修改）"
            } else {
                item.description
            }
            // 修改过的配置项，值用暖橙高亮
            val bg = GradientDrawable()
            bg.cornerRadius = dp(6).toFloat()
            bg.setColor(Color.parseColor(if (item.isModified) "#FDF1E2" else "#E9F3EC"))
            value.background = bg
            itemView.setOnClickListener { onItemClick(item) }
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ITEM = 1
    }

    private fun dp(v: Int): Int = (v * 3f).toInt()
}
