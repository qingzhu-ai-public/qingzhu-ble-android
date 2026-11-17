// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.demo.sdk.adapter

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.qingzhu.ble.sdk.core.device.QDevice
import com.qingzhu.demo.sdk.R
import com.qingzhu.protocol.generic.scan.GenericRecognizer

/**
 * 设备列表适配器：前置选择框，点击选中/取消，支持全选。
 *
 * 身份一律用 stableId（稳定身份），不用 bleMac（会轮换）——设备换址后行原位更新、选中态不丢。
 */
class DeviceAdapter(private val onSelectionChanged: () -> Unit) :
    RecyclerView.Adapter<DeviceAdapter.Holder>() {

    private val devices = mutableListOf<QDevice>()
    private val selected = mutableSetOf<String>() // 存 stableId，换 bleMac 后选中态跟随设备

    fun submit(list: List<QDevice>) {
        // 按 stableId 去重（同一台设备轮换 bleMac 后只保留最新一条）
        val newList = list.distinctBy { it.stableId }
        selected.retainAll(newList.map { it.stableId }.toSet())

        // DiffUtil 增量刷新：身份用 stableId（稳定），内容变化（bleMac/RSSI/名字）时原位刷新，
        // 设备换址不会误判成「删除+新增」，bleMac 实时更新且行不闪烁
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = devices.size
            override fun getNewListSize(): Int = newList.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                devices[oldItemPosition].stableId == newList[newItemPosition].stableId

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                val o = devices[oldItemPosition]
                val n = newList[newItemPosition]
                return o.mac == n.mac && o.rssi == n.rssi && o.name == n.name
            }
        })
        devices.clear()
        devices.addAll(newList)
        diff.dispatchUpdatesTo(this)
    }

    fun getSelected(): List<QDevice> = devices.filter { selected.contains(it.stableId) }

    fun selectedCount(): Int = selected.size

    fun selectAll() {
        val all = selected.size == devices.size && devices.isNotEmpty()
        selected.clear()
        if (!all) {
            devices.forEach { selected.add(it.stableId) }
        }
        notifyDataSetChanged()
        onSelectionChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_device, parent, false)
        return Holder(v)
    }

    override fun onBindViewHolder(h: Holder, position: Int) {
        val d = devices[position]
        val sel = selected.contains(d.stableId)
        val deviceMac = d.getMetadata<String>(GenericRecognizer.META_DEVICE_MAC)
        h.name.text = d.name ?: "设备"
        h.proto.text = d.protocolType?.id
            ?.replaceFirstChar { c -> c.uppercaseChar() } ?: "设备"
        // 第一行：稳定 deviceMac（身份）；第二行：bleMac（会轮换）+ 帧类型
        h.mac.text = "deviceMac " + (deviceMac ?: d.mac)
        val frame = d.getMetadata<String>("frameType")
        h.bleMac.text = "bleMac " + d.mac + (if (frame != null) " · 帧 $frame" else "")
        h.rssi.text = "${d.rssi} dBm"
        val level = when {
            d.rssi >= -60 -> 4
            d.rssi >= -75 -> 3
            d.rssi >= -85 -> 2
            else -> 1
        }
        h.signal.forEachIndexed { i, bar ->
            bar.setBackgroundColor(Color.parseColor(if (i < level) "#2F7D5F" else "#E6E9DF"))
        }
        h.check.text = if (sel) "✓" else ""
        val bg = GradientDrawable()
        bg.cornerRadius = dp(5).toFloat()
        bg.setStroke(dp(2), Color.parseColor(if (sel) "#2F7D5F" else "#E6E9DF"))
        if (sel) {
            bg.setColor(Color.parseColor("#2F7D5F"))
        }
        h.check.background = bg
        h.itemView.setOnClickListener {
            if (!selected.add(d.stableId)) {
                selected.remove(d.stableId)
            }
            notifyItemChanged(position)
            onSelectionChanged()
        }
    }

    override fun getItemCount(): Int = devices.size

    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val check: TextView = v.findViewById(R.id.deviceCheck)
        val name: TextView = v.findViewById(R.id.deviceName)
        val proto: TextView = v.findViewById(R.id.deviceProto)
        val mac: TextView = v.findViewById(R.id.deviceMac)
        val bleMac: TextView = v.findViewById(R.id.deviceBleMac)
        val rssi: TextView = v.findViewById(R.id.deviceRssi)
        val signal: List<View> = listOf(
            v.findViewById(R.id.sig1),
            v.findViewById(R.id.sig2),
            v.findViewById(R.id.sig3),
            v.findViewById(R.id.sig4)
        )
    }

    private fun dp(v: Int): Int = (v * 3f).toInt()
}
