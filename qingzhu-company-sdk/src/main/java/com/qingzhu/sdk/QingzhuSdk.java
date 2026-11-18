// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.sdk;

import android.content.Context;

import com.qingzhu.ble.sdk.core.QSdk;
import com.qingzhu.ble.sdk.core.api.QCommand;
import com.qingzhu.ble.sdk.core.api.QTask;
import com.qingzhu.ble.sdk.core.api.ScanOptions;
import com.qingzhu.ble.sdk.core.common.QLog;
import com.qingzhu.ble.sdk.core.device.QDevice;
import com.qingzhu.ble.sdk.core.protocol.ProtocolPlugin;
import com.qingzhu.ble.sdk.core.protocol.ProtocolRegistry;
import com.qingzhu.ble.sdk.core.settings.BleSettings;
import com.qingzhu.protocol.eddystone.EddystoneProtocolPlugin;
import com.qingzhu.protocol.generic.GenericProtocolPlugin;

import java.util.ArrayList;
import java.util.List;

/**
 * 青竹 BLE SDK 开源门面。
 *
 * <p>定位：基于内核 {@code qingzhu-core} 的<b>开源对外入口</b>，直接暴露内核类型
 * （{@link QDevice} / {@link QTask} / {@link QCommand}），不做白标包装。
 *
 * <p>心智模型：<b>万物皆任务</b>——任务 = 一台设备 + 一条可选命令，内核负责寻人、排队、
 * 连接、发送、失败重试。接入方只需 init、扫描、提交任务。
 *
 * <pre>
 *   QingzhuSdk.init(context);
 *   QingzhuSdk.startScan(devices -> showList(devices));
 *   QingzhuSdk.submit(mac, QCommand.builder().send("{\"action\":\"echo\"}").build());
 * </pre>
 *
 * <p>要接入自己的协议：实现 {@link ProtocolPlugin} 后调用 {@link #registerProtocol} 即可，
 * 不需要改内核或本门面。
 */
public final class QingzhuSdk {

    private static volatile boolean initialized = false;

    private QingzhuSdk() {
    }

    /**
     * 初始化：注入内核 + 注册两个开源示例协议。重复调用幂等。
     *
     * <p>Eddystone 认 Service Data 0xFEAA、Generic 认 0xFFE0，识别条件互斥；
     * 注册顺序不影响归属。要替换成自己的协议，注释掉这两行后自行
     * {@link #registerProtocol(ProtocolPlugin)} 即可。
     */
    public static synchronized void init(Context context) {
        if (initialized) {
            return;
        }
        initialized = true;
        QSdk.getInstance().init(context.getApplicationContext());
        registerProtocol(new EddystoneProtocolPlugin());
        registerProtocol(new GenericProtocolPlugin());
        QLog.d("QingzhuSdk", "开源协议装配完成：" + getProtocols());
    }

    /** 注册协议插件（开源协议或自定义协议都走这里）；重复注册同一协议是幂等的 */
    public static void registerProtocol(ProtocolPlugin plugin) {
        ProtocolRegistry.register(plugin);
    }

    /** 当前已注册协议的 id 列表 */
    public static List<String> getProtocols() {
        List<String> ids = new ArrayList<>();
        for (ProtocolPlugin p : ProtocolRegistry.all()) {
            ids.add(p.protocolType().getId());
        }
        return ids;
    }

    // ---- 扫描 ----

    /** 订阅扫描结果并确保扫描开启（设备快照按 stableId 去重、每秒批量回调） */
    public static void startScan(QSdk.ScanListener listener) {
        QSdk.getInstance().startScan(listener);
    }

    /** 按扫描时长、RSSI、名称前缀、协议或稳定 ID 过滤开始 UI 扫描 */
    public static void startScan(ScanOptions options, QSdk.ScanListener listener) {
        QSdk.getInstance().startScan(options, listener);
    }

    /** 停止扫描：退订 UI 回调并真正停止底层扫描；任务需要寻人时仍可按需重新拉起 */
    public static void stopScan() {
        QSdk.getInstance().stopScan();
    }

    /** 重启扫描（权限授予后 / 手动重扫） */
    public static void restartScan() {
        QSdk.getInstance().restartScan();
    }

    /** 扫描状态监听（扫描中/已停止） */
    public static void setScanStateListener(QSdk.ScanStateListener listener) {
        QSdk.getInstance().setScanStateListener(listener);
    }

    // ---- 任务 ----

    /** 只给 mac 的占位任务：内核自己寻人，找到后自动执行命令 */
    public static QTask submit(String deviceMac, QCommand command) {
        return QSdk.getInstance().submit(deviceMac, command);
    }

    /** 扫描到的设备直接提交任务 */
    public static QTask submit(QDevice device, QCommand command) {
        return QSdk.getInstance().submit(device, command);
    }

    /**
     * 开始一轮批量任务，提交完成后调用 {@link QSdk.Batch#finish()}。
     * 批量结束会自动汇总任务终态并回灌自动并发学习。
     */
    public static QSdk.Batch beginBatch() {
        return QSdk.getInstance().beginBatch();
    }

    /** 批量配置：所有任务终态后回调汇总结果，用于判断是否退出配置页 */
    public static QSdk.Batch beginBatch(QSdk.BatchListener listener) {
        return QSdk.getInstance().beginBatch(listener);
    }

    /** 任务池句柄（list / get / clear） */
    public static QSdk.Tasks getTasks() {
        return QSdk.getInstance().getTasks();
    }

    /** 订阅任务池：状态一变就回调全量快照 */
    public static void subscribe(QSdk.TaskPoolListener listener) {
        QSdk.getInstance().subscribe(listener);
    }

    // ---- 调度旋钮 ----

    /** 同时连接数：0 = 自动学习（默认），1-N = 手动（手动模式下批量结果仍会记入学习，但不覆盖手动值） */
    public static void setMaxConnections(int n) {
        QSdk.getInstance().setMaxConnections(n);
    }

    /** 同时传输数：1 = 串行，N = 最多 N 台同时传 */
    public static void setMaxTransfers(int n) {
        QSdk.getInstance().setMaxTransfers(n);
    }

    /** 任务失败自动重试次数（连接失败/发送失败/寻人超时各算一次），默认 3 */
    public static void setMaxRetries(int n) {
        QSdk.getInstance().setMaxRetries(n);
    }

    /** 每台设备最多排队多少条待发送意图，默认 8；0 表示忙碌时不排队 */
    public static void setMaxPendingIntents(int n) {
        QSdk.getInstance().setMaxPendingIntents(n);
    }

    /** 系统连接状态回调兜底超时，默认 20 秒，最小 5 秒 */
    public static void setConnectCallbackTimeoutMs(long timeoutMs) {
        QSdk.getInstance().setConnectCallbackTimeoutMs(timeoutMs);
    }

    /** 当前连接并发容量（自动学习时为内核建议值） */
    public static int getConcurrency() {
        return QSdk.getInstance().getConcurrency();
    }

    // ---- 配置 ----

    /**
     * 全局设置中心：内核内置通用开关 + 已注册协议声明的协议专属项。
     * 不配置的项直接用默认值；{@code listAll()} 可列出全部 key 与说明。
     */
    public static BleSettings settings() {
        return BleSettings.get();
    }
}
