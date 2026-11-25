// SPDX-License-Identifier: MIT
package dev.qingzhu.bthome.ui.advertiser

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import dev.qingzhu.bthome.R
import dev.qingzhu.bthome.protocol.legacyAdvBytes
import dev.qingzhu.bthome.protocol.legacyScanResponseBytes
import dev.qingzhu.bthome.protocol.parse
import dev.qingzhu.bthome.protocol.serviceDataHex
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 从端页：选数据源 → 看字节 → 开始广播。
 *
 * 界面本身不存状态，全部读 [AdvertiserViewModel]；
 * 切到主端页再回来，广播不会断，滑杆位置也还在。
 */
class AdvertiserScreen : Fragment(R.layout.screen_advertiser) {

    private val vm: AdvertiserViewModel by viewModels()

    private val required: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            emptyArray()
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.all { it }) startIfReady() else toast("缺少蓝牙权限，无法广播")
        }

    private val enableBtLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) startIfReady() else toast("蓝牙没有开启")
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val btnAdv = view.findViewById<Button>(R.id.btn_adv)
        val tvState = view.findViewById<TextView>(R.id.tv_adv_state)
        val rgSource = view.findViewById<RadioGroup>(R.id.rg_source)
        val cardManual = view.findViewById<ViewGroup>(R.id.card_manual)
        val sbTemp = view.findViewById<SeekBar>(R.id.sb_temp)
        val tvTemp = view.findViewById<TextView>(R.id.tv_temp_label)
        val sbHum = view.findViewById<SeekBar>(R.id.sb_hum)
        val tvHum = view.findViewById<TextView>(R.id.tv_hum_label)
        val cbBattery = view.findViewById<CheckBox>(R.id.cb_battery)
        val sbBattery = view.findViewById<SeekBar>(R.id.sb_battery)
        val tvBattery = view.findViewById<TextView>(R.id.tv_battery_label)
        val etName = view.findViewById<EditText>(R.id.et_name)
        val tvNameHint = view.findViewById<TextView>(R.id.tv_name_hint)
        val cbConnectable = view.findViewById<CheckBox>(R.id.cb_connectable)
        val tvGattState = view.findViewById<TextView>(R.id.tv_gatt_state)
        val tvRemoteNote = view.findViewById<TextView>(R.id.tv_remote_note)
        val tvHex = view.findViewById<TextView>(R.id.tv_hex)
        val tvExplain = view.findViewById<TextView>(R.id.tv_explain)
        val tvBudget = view.findViewById<TextView>(R.id.tv_budget)

        // ---- 输入 → ViewModel
        btnAdv.setOnClickListener {
            if (vm.advertiser.isRunning) {
                vm.stop()
            } else if (!hasPermissions()) {
                permissionLauncher.launch(required)
            } else {
                startIfReady()
            }
        }
        rgSource.setOnCheckedChangeListener { _, checkedId ->
            vm.source.value = when (checkedId) {
                R.id.rb_manual -> SourceKind.MANUAL
                R.id.rb_script -> SourceKind.SCRIPT
                else -> SourceKind.WAVE
            }
        }
        sbTemp.setOnSeekBarChangeListener(progress { vm.manual.setTemperature(it / 10f - 40f) })
        sbHum.setOnSeekBarChangeListener(progress { vm.manual.setHumidity(it / 10f) })
        cbBattery.setOnCheckedChangeListener { _, on -> vm.battery.value = if (on) sbBattery.progress else null }
        sbBattery.setOnSeekBarChangeListener(progress {
            tvBattery.text = "$it%"
            if (cbBattery.isChecked) vm.battery.value = it
        })
        etName.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                vm.deviceName.value = s?.toString() ?: ""
            }
        })
        cbConnectable.setOnCheckedChangeListener { _, on ->
            vm.connectable.value = on
            runCatching { vm.setConnectable(on) }
        }

        // ---- ViewModel → 界面
        val owner = viewLifecycleOwner
        owner.lifecycleScope.launch {
            vm.advertiser.status.collect {
                tvState.text = it
                btnAdv.text = if (vm.advertiser.isRunning) "停止广播" else "开始广播"
                renderBudget(tvBudget)
            }
        }
        owner.lifecycleScope.launch {
            vm.advertiser.serviceData.collect { renderFrame(it, tvHex, tvExplain, tvBudget) }
        }
        owner.lifecycleScope.launch {
            vm.advertiser.effectiveName.collect {
                val bytes = legacyScanResponseBytes(it)
                tvNameHint.text = "实际广播的设备名：“$it”（扫描响应 $bytes/31 字节）。停止广播后恢复本机原名。"
                renderBudget(tvBudget)
            }
        }
        owner.lifecycleScope.launch {
            vm.advertiser.gattState.collect { tvGattState.text = "GATT：$it" }
        }
        owner.lifecycleScope.launch {
            vm.source.collect { kind ->
                val id = when (kind) {
                    SourceKind.WAVE -> R.id.rb_wave
                    SourceKind.MANUAL -> R.id.rb_manual
                    SourceKind.SCRIPT -> R.id.rb_script
                }
                if (rgSource.checkedRadioButtonId != id) rgSource.check(id)
                setEnabled(cardManual, kind == SourceKind.MANUAL)            }
        }
        owner.lifecycleScope.launch {
            vm.manual.current.collect { list ->
                val t = vm.manual.temperature
                val h = vm.manual.humidity
                tvTemp.text = String.format(Locale.US, "温度 %.1f °C", t)
                tvHum.text = String.format(Locale.US, "湿度 %.1f %%", h)
                val tp = ((t + 40f) * 10).toInt()
                val hp = (h * 10).toInt()
                if (sbTemp.progress != tp) sbTemp.progress = tp
                if (sbHum.progress != hp) sbHum.progress = hp
            }
        }
        owner.lifecycleScope.launch {
            vm.battery.collect { b ->
                if (b == null) {
                    if (cbBattery.isChecked) cbBattery.isChecked = false
                } else {
                    if (!cbBattery.isChecked) cbBattery.isChecked = true
                    if (sbBattery.progress != b) sbBattery.progress = b
                    tvBattery.text = "$b%"
                }
            }
        }
        owner.lifecycleScope.launch {
            vm.connectable.collect {
                if (cbConnectable.isChecked != it) cbConnectable.isChecked = it
            }
        }
        owner.lifecycleScope.launch {
            vm.remoteNote.collect { tvRemoteNote.text = it ?: "" }
        }
    }

    private fun startIfReady() {
        val adapter = AdvertiserViewModel.adapter(requireActivity().application)
        if (adapter == null) {
            toast("本机不支持蓝牙")
            return
        }
        if (!runCatching { adapter.isEnabled }.getOrDefault(false)) {
            enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        vm.start()
    }

    private fun hasPermissions(): Boolean = required.all {
        ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED
    }

    private fun toast(msg: String) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }

    /** 把一帧拆开给人看：每个对象一行，字节、含义、换算后的值对齐在一起。 */
    private fun renderFrame(bytes: ByteArray, tvHex: TextView, tvExplain: TextView, tvBudget: TextView) {
        if (bytes.isEmpty()) {
            tvHex.text = "（还没有读数）"
            tvExplain.text = ""
            tvBudget.text = ""
            return
        }
        tvHex.text = serviceDataHex(bytes)
        val sb = StringBuilder()
        sb.append("40          设备信息：v2 · 不加密 · 定时广播\n")
        var i = 1
        parse(bytes)?.measurements?.forEach { m ->
            val hex = bytes.sliceArray(i + 1 until i + 1 + m.obj.len).joinToString(" ") { "%02X".format(it) }
            sb.append(String.format(Locale.US, "%02X %-8s", m.obj.id, hex))
                .append(m.obj.label).append(' ')
                .append(String.format(Locale.US, "%.2f", m.value)).append(m.obj.unit)
            if (!m.inRange) sb.append("  ← 越界，主端会标红")
            sb.append('\n')
            i += 1 + m.obj.len
        }
        tvExplain.text = sb.toString().trimEnd()
        renderBudget(tvBudget)
    }

    private fun renderBudget(tvBudget: TextView) {
        val data = vm.advertiser.serviceData.value
        if (data.isEmpty()) return
        val adv = legacyAdvBytes(data)
        val name = legacyScanResponseBytes(vm.advertiser.effectiveName.value)
        val over = adv > 31 || name > 31
        tvBudget.text = "主包 $adv/31 字节（Flags 3 + 服务数据 ${4 + data.size}）  ·  " +
            "扫描响应 $name/31 字节（设备名）"
        tvBudget.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (over) R.color.warn else R.color.ink_muted,
            ),
        )
    }

    private fun setEnabled(group: ViewGroup, enabled: Boolean) {
        group.alpha = if (enabled) 1f else 0.45f
        for (i in 0 until group.childCount) {
            group.getChildAt(i).isEnabled = enabled
        }
    }

    private fun progress(onProgress: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) onProgress(progress)
        }
        override fun onStartTrackingTouch(sb: SeekBar?) = Unit
        override fun onStopTrackingTouch(sb: SeekBar?) = Unit
    }
}
