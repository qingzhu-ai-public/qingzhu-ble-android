// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.demo.sdk

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.qingzhu.demo.sdk.sheet.CommandSheet
import com.qingzhu.demo.sdk.ui.ConfigFragment
import com.qingzhu.demo.sdk.ui.ScanFragment
import com.qingzhu.demo.sdk.ui.TaskFragment

/**
 * demo 主界面：底部三个 Tab（扫描 / 任务 / 配置），扫描为首页，自实现高亮。
 */
class MainActivity : AppCompatActivity() {

    companion object {
        const val TAB_SCAN = 0
        const val TAB_TASK = 1
        const val TAB_CONFIG = 2

        /** 批量操作页确认后置位：MainActivity onResume 时据此自动切到任务 Tab */
        @JvmField
        var pendingSwitchToTask = false
    }

    private lateinit var tabTask: View
    private lateinit var tabScan: View
    private lateinit var tabConfig: View

    /** 权限就绪后是否已重启过扫描（只重启一次，避免 onResume 频繁触发） */
    private var scanEnsured = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 初始化 SDK + 注册协议 + 启动扫描（单例统一管理，只调一次）
        SdkManager.init(this)

        requestPermissions()

        tabTask = findViewById(R.id.nav_task)
        tabScan = findViewById(R.id.nav_scan)
        tabConfig = findViewById(R.id.nav_config)

        tabTask.setOnClickListener { selectTab(TAB_TASK) }
        tabScan.setOnClickListener { selectTab(TAB_SCAN) }
        tabConfig.setOnClickListener { selectTab(TAB_CONFIG) }

        // 默认落在「扫描」页：一打开就能看到设备列表。
        // 任务页在没加任务时执行中/待机中都显示 0，容易被误认为「没扫到设备 / App 没启动」。
        // 开发期可用 am start --es tab <scan|task|config> 直接进指定页，方便自动化截图。
        val initialTab = when (intent?.getStringExtra("tab")) {
            "task" -> TAB_TASK
            "config" -> TAB_CONFIG
            else -> TAB_SCAN
        }
        selectTab(initialTab)

        // 开发期自动化截图入口：从 App 内部拉起独立页（exported=false，shell 无法直接启动）
        // adb shell am start -n com.qingzhu.demo.sdk/.MainActivity --es page batch
        if (intent?.getStringExtra("page") == "batch") {
            startActivity(Intent(this, com.qingzhu.demo.sdk.ui.BatchAddActivity::class.java))
        }
        // 开发期自动化截图入口：am start ... --es sheet command
        if (intent?.getStringExtra("sheet") == "command") {
            CommandSheet.show(this, "3 台设备", { }, false)
        }
        if (intent?.getStringExtra("sheet") == "settings") {
            com.qingzhu.demo.sdk.sheet.SettingsSheet.show(this)
        }
    }

    override fun onResume() {
        super.onResume()
        // 从批量操作页返回后，若有「跳任务页」的请求，自动切过去
        if (pendingSwitchToTask) {
            pendingSwitchToTask = false
            switchToTaskTab()
        }
        // 权限对话框关闭后 Activity 会 onResume，此时权限可能已就绪——确保扫描启动
        ensureScanAfterPermission()
    }

    /** 切到「任务」Tab（扫描页/批量操作页确认后调用） */
    fun switchToTaskTab() {
        selectTab(TAB_TASK)
    }

    private fun selectTab(tab: Int) {
        styleTab(tabTask, tab == TAB_TASK)
        styleTab(tabScan, tab == TAB_SCAN)
        styleTab(tabConfig, tab == TAB_CONFIG)
        showFragment(
            when (tab) {
                TAB_SCAN -> ScanFragment()
                TAB_CONFIG -> ConfigFragment()
                else -> TaskFragment()
            }
        )
    }

    /** 选中态：图标容器填竹青、图标转白、文字变竹青加粗 */
    private fun styleTab(tab: View, selected: Boolean) {
        val box = tab.findViewWithTag<View>("iconBox") ?: return
        val icon = tab.findViewWithTag<ImageView>("icon") ?: return
        val label = tab.findViewWithTag<TextView>("label") ?: return
        box.background = ContextCompat.getDrawable(
            this,
            if (selected) R.drawable.bg_tab_icon_on else R.drawable.bg_tab_icon_off
        )
        icon.setColorFilter(
            ContextCompat.getColor(this, if (selected) R.color.bg_card else R.color.ink_3)
        )
        label.setTextColor(
            ContextCompat.getColor(this, if (selected) R.color.brand else R.color.ink_3)
        )
        label.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun showFragment(f: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, f).commit()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        CommandSheet.onActivityResult(requestCode, resultCode, data)
    }

    private fun requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(
                    arrayOf(
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ), 1
                )
            }
        } else {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 1) return
        // 直接用 grantResults 判断（不依赖 checkSelfPermission，避免 MIUI 权限状态更新延迟），
        // 关键扫描权限（蓝牙扫描/位置）授予了就重启扫描
        val scanGranted = permissions.indices.any { i ->
            grantResults[i] == PackageManager.PERMISSION_GRANTED
                    && (permissions[i] == Manifest.permission.BLUETOOTH_SCAN
                    || permissions[i] == Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (scanGranted) {
            scanEnsured = true
            SdkManager.restartScan()
        }
    }

    /** 关键 BLE 权限就绪后，确保扫描真正启动（首次 init 时无权限、扫描启动失败） */
    private fun ensureScanAfterPermission() {
        if (scanEnsured) {
            return
        }
        if (!hasBlePermissions()) {
            return
        }
        scanEnsured = true
        SdkManager.restartScan()
    }

    /** 关键权限是否齐全：Android 12+ 看蓝牙扫描/连接，以下看位置 */
    private fun hasBlePermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }
}
