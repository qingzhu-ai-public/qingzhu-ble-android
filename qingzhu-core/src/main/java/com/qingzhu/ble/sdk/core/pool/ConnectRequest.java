// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.sdk.core.pool;

import com.qingzhu.ble.sdk.core.connect.ConnectParams;
import com.qingzhu.ble.sdk.core.device.QDevice;
import com.qingzhu.ble.sdk.core.protocol.ProtocolParams;

import java.util.concurrent.atomic.AtomicInteger;

/** 一次连接请求：等不到空闲槎位、或设备还在释放中时进队列排队（同一设备重复请求只保留最新一次） */
public final class ConnectRequest {

    /** 完整保留扫描阶段的 QDevice（含 metadata，如 轮换地址协议 的 deviceMac）——排队重连时 SmartRescan 依赖 */
    private final QDevice device;
    private final PoolConnectListener listener;
    /** 自动重连策略（意外断开时生效），默认 OFF */
    private final ReconnectPolicy reconnectPolicy;
    /** 协议参数（宿主 SDK 定制的值，可空）——连接时复制进 Session，协议步骤/Filter 读取 */
    private final ProtocolParams params;
    /** 连接参数（门面 builder 收集，可空）——默认连接流程时传给连接前钩子/标准连接 */
    private final ConnectParams connectParams;
    /** 已执行的自动重连次数（连接成功即重置） */
    private final AtomicInteger reconnectAttempts = new AtomicInteger();
    /** 连接流程整轮尝试的总次数（首次 + 重试），默认 2 = 失败后再重试 1 次 */
    private final int maxConnectAttempts;
    /** 已执行的连接尝试次数 */
    private final AtomicInteger connectAttempts = new AtomicInteger();
    /** 池满时立即失败（true）还是进队列排队（false）——单个连接用 true（达到上限报错），批量用 false（任务池排队） */
    private final boolean failFastWhenFull;

    public ConnectRequest(QDevice device, PoolConnectListener listener) {
        this(device, listener, ReconnectPolicy.OFF, null);
    }

    public ConnectRequest(QDevice device, PoolConnectListener listener, ReconnectPolicy reconnectPolicy) {
        this(device, listener, reconnectPolicy, null);
    }

    public ConnectRequest(QDevice device, PoolConnectListener listener,
                          ReconnectPolicy reconnectPolicy, ProtocolParams params) {
        this(device, listener, reconnectPolicy, params, 2);
    }

    public ConnectRequest(QDevice device, PoolConnectListener listener,
                          ReconnectPolicy reconnectPolicy, ProtocolParams params, int maxConnectAttempts) {
        this(device, listener, reconnectPolicy, params, null, maxConnectAttempts);
    }

    public ConnectRequest(QDevice device, PoolConnectListener listener,
                          ReconnectPolicy reconnectPolicy, ProtocolParams params,
                          ConnectParams connectParams, int maxConnectAttempts) {
        this(device, listener, reconnectPolicy, params, connectParams, maxConnectAttempts, false);
    }

    public ConnectRequest(QDevice device, PoolConnectListener listener,
                          ReconnectPolicy reconnectPolicy, ProtocolParams params,
                          ConnectParams connectParams, int maxConnectAttempts, boolean failFastWhenFull) {
        this.device = device;
        this.listener = listener;
        this.reconnectPolicy = reconnectPolicy != null ? reconnectPolicy : ReconnectPolicy.OFF;
        this.params = params;
        this.connectParams = connectParams;
        this.maxConnectAttempts = Math.max(1, maxConnectAttempts);
        this.failFastWhenFull = failFastWhenFull;
    }

    public String getMac() {
        return device.getMac();
    }

    public QDevice getDevice() {
        return device;
    }

    public PoolConnectListener getListener() {
        return listener;
    }

    public ReconnectPolicy getReconnectPolicy() {
        return reconnectPolicy;
    }

    public ProtocolParams getParams() {
        return params;
    }

    public ConnectParams getConnectParams() {
        return connectParams;
    }

    /** 自动重连次数 +1，返回新值（1 起） */
    public int nextReconnectAttempt() {
        return reconnectAttempts.incrementAndGet();
    }

    /** 连接成功后重置自动重连计数 */
    public void resetReconnectAttempts() {
        reconnectAttempts.set(0);
    }

    /** 连接流程整轮尝试的总次数（首次 + 重试） */
    public int getMaxConnectAttempts() {
        return maxConnectAttempts;
    }

    /** 池满时是否立即失败（true）而非排队（false） */
    public boolean isFailFastWhenFull() {
        return failFastWhenFull;
    }

    /** 连接尝试次数 +1，返回新值（1 起 = 第 1 次尝试） */
    public int nextConnectAttempt() {
        return connectAttempts.incrementAndGet();
    }
}
