// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.demo.sdk

import android.content.Context
import android.content.SharedPreferences
import com.qingzhu.ble.sdk.core.QSdk
import com.qingzhu.ble.sdk.core.api.QCommand
import com.qingzhu.ble.sdk.core.api.QTask
import com.qingzhu.ble.sdk.core.device.QDevice
import com.qingzhu.ble.sdk.core.protocol.ProtocolParam
import com.qingzhu.ble.sdk.core.settings.BleSettings
import com.qingzhu.sdk.QingzhuSdk
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 全局 SDK 管理单例：整个 demo 只有这里直接碰 [QSdk]，所有界面只调用本单例、订阅本单例的数据。
 *
 * 职责：
 * - 初始化 SDK + 注册协议
 * - 启动扫描（一次，持续跑，不随界面切换停止）
 * - 维护「设备列表」「任务列表」两份数据，界面通过监听器订阅
 * - 提交任务、设置两个旋钮
 *
 * 用法：
 * ```
 * SdkManager.init(this)                         // App 启动时调一次
 * SdkManager.addDeviceListener { devices -> ... } // 扫描页订阅设备
 * SdkManager.addTaskListener { tasks -> ... }     // 任务页订阅任务
 * SdkManager.submit(device, command)              // 加任务
 * ```
 */
object SdkManager {

    private const val PREFS_NAME = "sdk_manager_settings"
    private const val KEY_MAX_CONNECTIONS = "settings.maxConnections"
    private const val KEY_MAX_TRANSFERS = "settings.maxTransfers"
    private const val KEY_CONFIG_PREFIX = "bleConfig."
    private const val DEFAULT_MAX_CONNECTIONS = 0 // 自动学习
    private const val DEFAULT_MAX_TRANSFERS = 1   // 串行

    private val deviceListeners = CopyOnWriteArrayList<(List<QDevice>) -> Unit>()
    private val taskListeners = CopyOnWriteArrayList<(List<QTask>) -> Unit>()
    private val scanStateListeners = CopyOnWriteArrayList<(Boolean) -> Unit>()

    private var devices: List<QDevice> = emptyList()
    private var tasks: List<QTask> = emptyList()
    private var scanning = false

    private var initialized = false
    private lateinit var prefs: SharedPreferences

    /** App 启动时调用一次：初始化 SDK、注册协议、恢复缓存设置、启动扫描（持续跑）。幂等。 */
    fun init(context: Context) {
        if (initialized) {
            return
        }
        initialized = true
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // 门面内部已注册开源 Generic 协议，无需重复注册
        QingzhuSdk.init(context.applicationContext)

        // 恢复上次缓存的设置（重启 App 后依然生效）
        QingzhuSdk.setMaxConnections(getMaxConnections())
        QingzhuSdk.setMaxTransfers(getMaxTransfers())

        // 恢复缓存的 SDK 可配项（BleSettings）：每次打开 App 加载并应用
        BleSettings.get().listAll().forEach { item ->
            val v = loadConfig(item)
            if (v != null) {
                try {
                    BleSettings.get().set(item.key, v)
                } catch (e: Exception) {
                    // 类型不匹配等异常忽略，用默认值
                }
            }
        }

        // 扫描：只开一次，一直跑（不主动停止）。扫描结果喂给订阅者。
        QingzhuSdk.startScan(object : QSdk.ScanListener {
            override fun onDevices(devices: List<QDevice>) {
                this@SdkManager.devices = devices
                deviceListeners.forEach { it(devices) }
            }
        })

        // 任务池订阅：状态一变推全量快照。
        QingzhuSdk.subscribe { tasks ->
            this@SdkManager.tasks = tasks
            taskListeners.forEach { it(tasks) }
        }

        // 扫描状态订阅：扫描中/已停止（定时超时），UI 据此显示滚动图标 + 文字
        QingzhuSdk.setScanStateListener { s ->
            this@SdkManager.scanning = s
            scanStateListeners.forEach { it(s) }
        }
    }

    /**
     * 权限授予后调用：首次 init 时可能还没权限、扫描启动失败，授权后重新开扫。
     * 也用于「扫描已停止」后点击状态图标重新开扫。幂等，可重复调用。
     */
    fun restartScan() {
        if (!initialized) {
            return
        }
        QingzhuSdk.restartScan()
    }

    /** 当前是否正在扫描（定时超时自动停后为 false） */
    fun isScanning(): Boolean = scanning

    /** 订阅扫描状态变化（扫描中/已停止），订阅后立即推送一次当前状态 */
    fun addScanStateListener(l: (Boolean) -> Unit) {
        scanStateListeners.add(l)
        l(scanning)
    }

    fun removeScanStateListener(l: (Boolean) -> Unit) {
        scanStateListeners.remove(l)
    }

    // ===== 数据快照 =====

    /** 当前扫描到的设备列表 */
    fun devices(): List<QDevice> = devices

    /** 当前任务池列表 */
    fun tasks(): List<QTask> = tasks

    // ===== 订阅 / 退订 =====

    fun addDeviceListener(l: (List<QDevice>) -> Unit) {
        deviceListeners.add(l)
    }

    fun removeDeviceListener(l: (List<QDevice>) -> Unit) {
        deviceListeners.remove(l)
    }

    fun addTaskListener(l: (List<QTask>) -> Unit) {
        taskListeners.add(l)
    }

    fun removeTaskListener(l: (List<QTask>) -> Unit) {
        taskListeners.remove(l)
    }

    // ===== 操作 =====

    /** 扫描到的设备加任务 */
    fun submit(device: QDevice, command: QCommand?) {
        QingzhuSdk.submit(device, command)
    }

    /** 只给 mac（占位任务：系统自己找） */
    fun submit(mac: String, command: QCommand?) {
        QingzhuSdk.submit(mac, command)
    }

    /** 缓存里的同时连接数设置：0 = 自动学习，1-7 = 手动指定 */
    fun getMaxConnections(): Int = prefs.getInt(KEY_MAX_CONNECTIONS, DEFAULT_MAX_CONNECTIONS)

    /** 缓存里的同时传输数设置：1 = 串行 */
    fun getMaxTransfers(): Int = prefs.getInt(KEY_MAX_TRANSFERS, DEFAULT_MAX_TRANSFERS)

    /** 当前连接并发容量（自动学习时 = SDK 自适应建议值；手动时 = 用户指定值） */
    fun getConcurrency(): Int = QingzhuSdk.getConcurrency()

    /** 同时连接数：0 = 自动学习。写入缓存 + 立即生效。 */
    fun setMaxConnections(n: Int) {
        prefs.edit().putInt(KEY_MAX_CONNECTIONS, n).apply()
        QingzhuSdk.setMaxConnections(n)
    }

    /** 同时传输数：1 = 串行。写入缓存 + 立即生效。 */
    fun setMaxTransfers(n: Int) {
        prefs.edit().putInt(KEY_MAX_TRANSFERS, n).apply()
        QingzhuSdk.setMaxTransfers(n)
    }

    // ===== SDK 可配项（BleSettings）缓存 + 设置 =====

    /** 全部 SDK 可配项（key / 类型 / 默认值 / 当前值 / 说明），配置页直接渲染 */
    fun configItems(): List<BleSettings.SettingItem> = BleSettings.get().listAll()

    /** 设置一个 SDK 配置项：立即生效 + 写入缓存（下次打开 App 自动加载） */
    fun setConfig(key: String, value: Any) {
        BleSettings.get().set(key, value)
        when (value) {
            is Boolean -> prefs.edit().putBoolean(KEY_CONFIG_PREFIX + key, value).apply()
            is Int -> prefs.edit().putInt(KEY_CONFIG_PREFIX + key, value).apply()
            is Long -> prefs.edit().putLong(KEY_CONFIG_PREFIX + key, value).apply()
            is Double -> prefs.edit().putString(KEY_CONFIG_PREFIX + key, value.toString()).apply()
            is String -> prefs.edit().putString(KEY_CONFIG_PREFIX + key, value).apply()
            else -> {}
        }
    }

    /** 恢复某一项为默认值（同时清缓存） */
    fun resetConfig(key: String) {
        BleSettings.get().reset(key)
        prefs.edit().remove(KEY_CONFIG_PREFIX + key).apply()
    }

    /** 全部恢复默认值（同时清所有配置缓存） */
    fun resetAllConfig() {
        BleSettings.get().resetAll()
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(KEY_CONFIG_PREFIX) }.forEach { editor.remove(it) }
        editor.apply()
    }

    /** 从缓存读一个配置项（按声明类型解析；无缓存返回 null = 用默认值） */
    private fun loadConfig(item: BleSettings.SettingItem): Any? {
        val k = KEY_CONFIG_PREFIX + item.key
        return when (item.type) {
            ProtocolParam.Type.BOOLEAN -> if (prefs.contains(k)) prefs.getBoolean(k, false) else null
            ProtocolParam.Type.INT -> if (prefs.contains(k)) prefs.getInt(k, 0) else null
            ProtocolParam.Type.LONG -> if (prefs.contains(k)) prefs.getLong(k, 0L) else null
            ProtocolParam.Type.DOUBLE -> prefs.getString(k, null)?.toDoubleOrNull()
            ProtocolParam.Type.STRING -> prefs.getString(k, null)
        }
    }
}
