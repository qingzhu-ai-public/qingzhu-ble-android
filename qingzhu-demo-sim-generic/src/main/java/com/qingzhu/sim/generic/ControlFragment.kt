// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.sim.generic

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import androidx.fragment.app.Fragment

/** 「控制」Tab —— 主操作（开始/停止）+ 广播参数 + 能力与测试 */
class ControlFragment : Fragment() {

    private var btnToggle: Button? = null
    private var btnApply: Button? = null
    private var btnNotify: Button? = null
    private var etPayload: EditText? = null
    private var etMfgCompanyId: EditText? = null
    private var cbHeartbeat: CheckBox? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_control, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        btnToggle = view.findViewById(R.id.btnToggle)
        btnApply = view.findViewById(R.id.btnApply)
        btnNotify = view.findViewById(R.id.btnNotify)
        etPayload = view.findViewById(R.id.etPayload)
        etMfgCompanyId = view.findViewById(R.id.etMfgCompanyId)
        cbHeartbeat = view.findViewById(R.id.cbHeartbeat)

        val act = activity as? MainActivity ?: return
        btnToggle?.setOnClickListener {
            if (act.isRunning) act.stopAll() else act.startAll()
        }
        btnApply?.setOnClickListener {
            if (act.isRunning) {
                act.restartAdvertising()
                act.log("广播已用新 payload 重启")
            }
        }
        cbHeartbeat?.setOnCheckedChangeListener { _, isChecked ->
            act.setHeartbeatSupported(isChecked)
            act.log("心跳能力已切换为：" + if (isChecked) "支持" else "不支持（主端将不做心跳）")
        }
        btnNotify?.setOnClickListener {
            act.notifyLowBattery()
            act.log("已发送主动上报（电量低 level=15）")
        }

        // 初始默认值（只在第一次创建时填，避免覆盖用户输入）
        if (savedInstanceState == null) {
            etPayload?.setText("")
            etMfgCompanyId?.setText("FFFF")
        }

        refreshToggleState()
    }

    fun refreshToggleState() {
        val act = activity as? MainActivity ?: return
        btnToggle?.text = if (act.isRunning) "停止" else "开始"
    }

    /** MainActivity 读取当前输入框的 hex payload（用于重启广播） */
    fun payloadText(): String = etPayload?.text?.toString() ?: ""

    /**
     * MainActivity 读取当前输入框的 Company ID。
     *
     * startAll() 在 onCreate 里就调用，此时 Fragment 实例已创建但 View 可能还没走完
     * onViewCreated（FragmentTransaction.commit 是异步的）。若这里返回空串，
     * 广播就会丢掉 Manufacturer Data，主端解析不到稳定 deviceMac 而回退 bleMac。
     * View 未就绪时回退默认值；用户主动清空输入框时仍返回空串（表示不带该段）。
     */
    fun mfgCompanyIdText(): String = etMfgCompanyId?.text?.toString() ?: "FFFF"
}