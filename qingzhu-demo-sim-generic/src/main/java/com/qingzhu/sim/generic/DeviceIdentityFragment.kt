// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.sim.generic

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment

/** 「设备」Tab —— 居中显示 deviceMAC 大字 + 状态点 + 身份说明 */
class DeviceIdentityFragment : Fragment() {

    private var statusDot: View? = null
    private var tvLabel: TextView? = null
    private var tvMac: TextView? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_device_identity, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        statusDot = view.findViewById(R.id.statusDot)
        tvLabel = view.findViewById(R.id.tvDeviceMacLabel)
        tvMac = view.findViewById(R.id.tvDeviceMac)
        refresh()
    }

    fun refresh() {
        val act = activity as? MainActivity ?: return
        val running = act.isRunning
        val mac = act.deviceMac
        val dot = statusDot ?: return
        val label = tvLabel ?: return
        ViewStyle.applyRunningStatus(requireContext(), dot, label, running)
        tvMac?.text = mac?.let { act.formatMacString(it) } ?: "—"
    }
}