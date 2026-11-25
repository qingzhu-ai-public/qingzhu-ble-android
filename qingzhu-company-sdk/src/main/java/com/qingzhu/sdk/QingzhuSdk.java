// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.sdk;

import android.content.Context;

import com.qingzhu.ble.sdk.core.QSdk;
import com.qingzhu.ble.sdk.core.api.QCommand;
import com.qingzhu.ble.sdk.core.api.QTask;
import com.qingzhu.ble.sdk.core.api.ScanOptions;
import com.qingzhu.ble.sdk.core.api.Subscription;
import com.qingzhu.ble.sdk.core.api.TaskSnapshot;
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
 *   QTask task = QingzhuSdk.createTask(mac);
 *   task.send(QCommand.builder().send("{\"action\":\"echo\"}").build(), r -&gt; handle(r));
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

    /** 创建任务：该设备已有任务则先删除，再新建。需要复用已有任务时使用 {@link #getTask}。 */
    public static QTask createTask(String mac) {
        return QSdk.getInstance().createTask(mac);
    }

    /** 用扫描到的设备创建任务：该设备已有任务则先删除，再新建。 */
    public static QTask createTask(QDevice device) {
        return QSdk.getInstance().createTask(device);
    }

    /** 只查询，不创建；该设备没有任务时返回 null。 */
    public static QTask getTask(String mac) {
        return QSdk.getInstance().getTask(mac);
    }

    /** 删除该设备的任务（取消当前操作、断开连接、从任务池移除）；不存在则忽略。 */
    public static void deleteTask(String mac) {
        QSdk.getInstance().deleteTask(mac);
    }

    /** 观察所有任务：注册后立即推送一次当前快照，之后每次变化回调不可变快照列表。 */
    public static Subscription observeAll(Object owner, QSdk.TaskListListener listener) {
        return QSdk.getInstance().observeAll(owner, listener);
    }

    /** 当前所有任务的不可变快照 */
    public static List<TaskSnapshot> snapshots() {
        return QSdk.getInstance().snapshots();
    }

    // ---- 调度旋钮 ----

    /** 同时连接数：0 = 自动（默认），1-N = 手动 */
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

    /** 每台设备最多排队多少条待执行命令，默认 10_000；0 表示忙碌时直接失败 */
    public static void setMaxPendingIntents(int n) {
        QSdk.getInstance().setMaxPendingIntents(n);
    }

    /** 系统连接状态回调兜底超时，默认 20 秒，最小 5 秒 */
    public static void setConnectCallbackTimeoutMs(long timeoutMs) {
        QSdk.getInstance().setConnectCallbackTimeoutMs(timeoutMs);
    }

    /** 单条 SEND 命令应答超时，默认 8,000 ms；0 = 不启用；CONNECT / noAck 命令不启用 */
    public static void setCommandTimeoutMs(long timeoutMs) {
        QSdk.getInstance().setCommandTimeoutMs(timeoutMs);
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
