// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.sdk.core.connect;

import com.qingzhu.ble.sdk.core.common.QLog;
import com.qingzhu.ble.sdk.core.protocol.ProtocolPlugin;
import com.qingzhu.ble.sdk.core.protocol.ProtocolRegistry;
import com.qingzhu.ble.sdk.core.session.Session;
import com.qingzhu.ble.sdk.core.settings.BleSettings;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 心跳引擎：长连接（保持场景）的「假死发现」机制。
 *
 * <p>为什么需要：设备<b>硬断电</b>（直接关）时，BLE 链路层监督超时往往几十秒、甚至不回调
 * {@code onConnectionStateChange(DISCONNECTED)}——系统、SDK、UI 全程蒙在鼓里，界面一直显示
 * 「已连接」，发消息只会干等超时。心跳就是主动问一句「你还活着吗」，设备不答就判离线。
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>空闲探活</b>：只在「距离上次成功收发超过 {@code heartbeat.idleIntervalMs}」时才发，
 *       业务交互本身就是「设备活着」的证明，心跳不打扰正常使用；</li>
 *   <li><b>队列串行</b>：心跳指令走与 {@code send} 同一条收发队列（按 mac 串行），
 *       绝不与业务指令并发——「心跳在等应答时又来业务指令」只会排队，不串包；</li>
 *   <li><b>协议决定</b>：协议未声明 {@code heartbeatCommand} 时完全静默（无任何开销）；</li>
 *   <li><b>判离线收尾</b>：连续 {@code heartbeat.maxMiss} 次无应答 → 回调
 *       {@link OfflineListener}，由连接池触发「主动断开 + 自动重连」。</li>
 * </ul>
 */
public final class HeartbeatManager {

    private static final String TAG = "HeartbeatManager";

    /** 周期检查间隔：只做「时间戳比较」，开销可忽略 */
    private static final long CHECK_INTERVAL_MS = 1_000;

    /** 发指令抽象：生产注入 {@code QingzhuBle.send}（走按 mac 串行队列），单测注入 mock */
    public interface Sender {
        void send(String mac, String message, Callback cb);
    }

    /**
     * 发送结果回调：{@code ok=true} 表示设备真实应答（存活）；{@code false} 表示写失败 /
     * 超时等「无响应」——不能算存活（否则设备关机后写指令报「无效句柄」也会被当成活着）。
     */
    public interface Callback {
        void onReply(boolean ok);
    }

    /** 判离线回调：由连接池注册，触发「主动断开 + 自动重连」 */
    public interface OfflineListener {
        void onOffline(String mac, String stableId);
    }

    private static final HeartbeatManager INSTANCE = new HeartbeatManager();

    public static HeartbeatManager get() {
        return INSTANCE;
    }

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "qingzhu-heartbeat");
        t.setDaemon(true);
        return t;
    });

    private final Map<String, State> states = new ConcurrentHashMap<>();

    private volatile Sender sender;
    private volatile OfflineListener offlineListener;

    private HeartbeatManager() {
    }

    /** 注入发指令实现（生产在 {@code QingzhuBle} 初始化时注入 {@code send}） */
    public void setSender(Sender sender) {
        this.sender = sender;
    }

    /** 注册判离线回调（生产由连接池注册） */
    public void setOfflineListener(OfflineListener listener) {
        this.offlineListener = listener;
    }

    /**
     * 连接成功后启动心跳。协议未声明心跳指令 / 全局开关关闭时静默返回（无任何开销）。
     */
    public void start(String mac, String stableId, Session session) {
        if (session == null || mac == null) {
            return;
        }
        ProtocolPlugin plugin = ProtocolRegistry.get(session.getProtocolType());
        if (plugin == null) {
            return;
        }
        String command;
        try {
            command = plugin.heartbeatCommand(session);
        } catch (Exception e) {
            QLog.w(TAG, "heartbeatCommand 异常（忽略，不做心跳）: " + e.getMessage());
            return;
        }
        if (command == null || command.isEmpty()) {
            return; // 协议没声明心跳指令 = 不做心跳
        }
        // 心跳参数：协议级覆盖全局默认（不同协议节奏不同——有的 3 秒、有的 15 秒）
        ProtocolPlugin.HeartbeatConfig cfg = null;
        try {
            cfg = plugin.heartbeatConfig(session);
        } catch (Exception e) {
            QLog.w(TAG, "heartbeatConfig 异常（忽略，用全局默认）: " + e.getMessage());
        }
        long idle = (cfg != null && cfg.idleIntervalMs != null)
                ? cfg.idleIntervalMs : BleSettings.get().getLong("heartbeat.idleIntervalMs");
        long timeout = (cfg != null && cfg.timeoutMs != null)
                ? cfg.timeoutMs : BleSettings.get().getLong("heartbeat.timeoutMs");
        int maxMiss = (cfg != null && cfg.maxMiss != null)
                ? cfg.maxMiss : BleSettings.get().getInt("heartbeat.maxMiss");
        boolean enabled = (cfg != null && cfg.enabled != null)
                ? cfg.enabled : BleSettings.get().getBoolean("heartbeat.enabled");
        if (!enabled) {
            return; // 心跳总开关（协议级或全局）
        }
        State state = new State(mac, stableId, command, idle, timeout, maxMiss);
        if (states.putIfAbsent(mac, state) != null) {
            return; // 已在心跳中（幂等）
        }
        QLog.d(TAG, "心跳启动 stableId=" + stableId + " mac=" + mac);
        state.checkFuture = scheduler.scheduleWithFixedDelay(
                () -> checkIdle(state), CHECK_INTERVAL_MS, CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /** 连接断开时停止心跳（幂等） */
    public void stop(String mac) {
        State state = states.remove(mac);
        if (state != null) {
            state.cancel();
            QLog.d(TAG, "心跳停止 mac=" + mac);
        }
    }

    /** 一次成功收发（业务 or 心跳）后调用：刷新活跃时间 + 重置 miss */
    public void notifyActive(String mac) {
        State state = states.get(mac);
        if (state != null) {
            state.lastActiveAt = System.currentTimeMillis();
            state.miss = 0;
        }
    }

    /** 周期检查：空闲超时才发心跳 */
    private void checkIdle(State state) {
        if (state.pending) {
            return; // 正在等心跳应答，交给超时定时器处理
        }
        if (System.currentTimeMillis() - state.lastActiveAt < state.idleIntervalMs) {
            return; // 还活跃，不需要探测
        }
        sendHeartbeat(state);
    }

    private void sendHeartbeat(State state) {
        Sender s = sender;
        if (s == null) {
            QLog.w(TAG, "心跳 Sender 未注入，跳过探测 mac=" + state.mac);
            return;
        }
        state.pending = true;
        QLog.d(TAG, "发心跳 stableId=" + state.stableId + " mac=" + state.mac);
        state.timeoutFuture = scheduler.schedule(
                () -> onHeartbeatTimeout(state), state.timeoutMs, TimeUnit.MILLISECONDS);
        try {
            s.send(state.mac, state.command, ok -> onHeartbeatReply(state, ok));
        } catch (Exception e) {
            QLog.e(TAG, "发心跳异常 mac=" + state.mac, e);
            state.pending = false;
            if (state.timeoutFuture != null) {
                state.timeoutFuture.cancel(false);
                state.timeoutFuture = null;
            }
        }
    }

    /** 心跳结果：ok = 设备真实应答（存活）；false = 写失败/超时等无响应，计一次 miss */
    private void onHeartbeatReply(State state, boolean ok) {
        if (!states.containsKey(state.mac)) {
            return; // 已停止
        }
        if (!state.pending) {
            return; // 心跳引擎已判超时（这是协议层迟到的超时回调），忽略
        }
        state.pending = false;
        if (state.timeoutFuture != null) {
            state.timeoutFuture.cancel(false);
            state.timeoutFuture = null;
        }
        if (ok) {
            state.lastActiveAt = System.currentTimeMillis();
            state.miss = 0;
            QLog.d(TAG, "心跳收到应答 stableId=" + state.stableId + " mac=" + state.mac);
        } else {
            // 写失败（如链路已死、无效句柄）＝设备无响应，不能算存活，与超时同待遇
            recordMiss(state);
        }
    }

    /** 心跳超时：计一次 miss，连续达阈值判离线 */
    private void onHeartbeatTimeout(State state) {
        if (!states.containsKey(state.mac)) {
            return; // 已停止
        }
        if (!state.pending) {
            return; // 超时前已收到应答
        }
        state.pending = false;
        recordMiss(state);
    }

    /** 计一次 miss：连续达阈值判离线（主动断开 + 触发重连） */
    private void recordMiss(State state) {
        state.miss++;
        QLog.w(TAG, "心跳无应答 miss=" + state.miss + "/" + state.maxMiss
                + " stableId=" + state.stableId + " mac=" + state.mac);
        if (state.miss >= state.maxMiss) {
            stop(state.mac);
            OfflineListener l = offlineListener;
            if (l != null) {
                QLog.e(TAG, "心跳判离线 stableId=" + state.stableId + " mac=" + state.mac
                        + "，通知连接池主动断开 + 重连");
                l.onOffline(state.mac, state.stableId);
            } else {
                QLog.w(TAG, "OfflineListener 未注册，心跳判离线后无法触发重连 mac=" + state.mac);
            }
        }
    }

    /** 每连接的心跳状态 */
    private static final class State {
        final String mac;
        final String stableId;
        final String command;
        final long idleIntervalMs;
        final long timeoutMs;
        final int maxMiss;
        volatile long lastActiveAt = System.currentTimeMillis();
        volatile int miss = 0;
        volatile boolean pending = false;
        volatile ScheduledFuture<?> checkFuture;
        volatile ScheduledFuture<?> timeoutFuture;

        State(String mac, String stableId, String command, long idleIntervalMs, long timeoutMs, int maxMiss) {
            this.mac = mac;
            this.stableId = stableId;
            this.command = command;
            this.idleIntervalMs = idleIntervalMs;
            this.timeoutMs = timeoutMs;
            this.maxMiss = maxMiss;
        }

        void cancel() {
            if (checkFuture != null) {
                checkFuture.cancel(false);
            }
            if (timeoutFuture != null) {
                timeoutFuture.cancel(false);
            }
        }
    }
}
