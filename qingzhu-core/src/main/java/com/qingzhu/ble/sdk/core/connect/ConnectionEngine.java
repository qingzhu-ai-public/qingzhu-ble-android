// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.sdk.core.connect;

import com.qingzhu.ble.sdk.core.common.ErrorCode;
import com.qingzhu.ble.sdk.core.common.QLog;
import com.qingzhu.ble.sdk.core.platform.AndroidGattClient;
import com.qingzhu.ble.sdk.core.protocol.ProtocolPlugin;
import com.qingzhu.ble.sdk.core.session.Session;

import java.util.List;

/**
 * 协议无关的连接编排器。协议数量从 5 个变成 500 个，这个类不需要改一行——
 * 它只认识 ConnectStepSpec 列表（代码式连接流程），不认识任何具体协议。
 *
 * 职责边界：引擎只负责"按顺序执行 + 每步超时/重试 + 失败上报 + 连接成功回调"；
 * "具体怎么连"（connectGatt 参数、发现服务、加密握手……）完全由协议写的 ConnectStep 决定，
 * 引擎不预设任何步骤、不干预任何系统 API 调用。
 *
 * 两条流程在"GATT 建立后"汇合到同一套统一收尾 {@link #afterConnected}：
 * removeBond 策略 → discoverServices → 协议 onGattReady（MTU/通知/认证）——
 * 自定义步骤流程（如 轮换地址协议 裸连）不再需要自己重写这段，也保证 removeBond / 认证行为一致。
 *
 * 这个类本身无状态：两次并发调用 connect()，只要传入不同的 Session，
 * 彼此互不干扰，这是多连接能落地的前提之一。
 */
public final class ConnectionEngine {

    private static final String TAG = "ConnectionEngine";
    /**
     * onGattReady 钩子兜底超时：协议钩子（如 CMAC 认证等待设备应答）卡住时，
     * 不能让整条连接流程永久挂起——否则连接池槽位被占死、上层永远收不到成功/失败回调
     *（表现为 UI 一直"连接中"）。
     */
    private static final long ON_GATT_READY_TIMEOUT_MS = 15_000;
    private static final java.util.concurrent.ScheduledExecutorService TIMER =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "qingzhu-gatt-ready-timeout");
                t.setDaemon(true);
                return t;
            });

    /**
     * 自定义连接流程（协议覆写 connectSteps() 时走这里）：按顺序执行协议的连接步骤
     * （如 轮换地址协议 的裸连：重寻址 → connectGatt → adopt → setPreferredPhy），
     * 全部步骤完成（GATT 已建立）后进入统一收尾。
     */
    public void connect(Session session, List<ConnectStepSpec> steps, ProtocolPlugin plugin, ConnectListener listener) {
        QLog.d(TAG, "[" + session.getMac() + "] 开始执行连接流程，共 " + steps.size() + " 步");
        runStep(session, steps, 0, plugin, listener);
    }

    /**
     * 默认连接流程（零配置路径）：协议未覆写 connectSteps() 时走这里。
     * 编排固定三段——{@code beforeConnect} 连接前钩子 → 标准连接 → 统一收尾
     * （removeBond 策略 → discoverServices → 协议 onGattReady）。
     * "连上"由 SDK 统一负责（connectGatt），协议只需在「连接前钩子」里做自己的预处理（如重寻址），
     * "连上后做什么"由 onGattReady 钩子决定。协议零配置即可连接。
     */
    public void connectDefault(Session session, ProtocolPlugin plugin, ConnectParams params, ConnectListener listener) {
        String mac = session.getMac();
        final java.util.concurrent.atomic.AtomicBoolean finished =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        QLog.d(TAG, "[" + mac + "] 默认连接流程：beforeConnect → 标准连接 → discoverServices → onGattReady");

        // ① 连接前钩子（默认空实现 = 直接 onReady）：协议可在此重寻址/握手，处理完放行
        plugin.beforeConnect(session, params, new ConnectCallback() {
            @Override
            public void onReady() {
                // ② 标准连接（connectGatt）：SDK 统一负责，协议不用自己连
                standardConnect(session, params, this);
            }

            @Override
            public void onConnected() {
                QLog.d(TAG, "[" + mac + "] 标准连接成功，进入统一收尾（发现服务 + onGattReady）");
                // ③ 统一收尾：与自定义步骤流程共用（内部自带 finished 防重）
                afterConnected(session, plugin, listener);
            }

            @Override
            public void onFailed(int errorCode, String message) {
                if (finished.compareAndSet(false, true)) {
                    listener.onFailed(session, "Connect", errorCode, message);
                }
            }
        });
    }

    /**
     * GATT 建立后的统一收尾（默认流程与自定义步骤流程共用）：
     * removeBond 策略 → discoverServices → 协议 onGattReady（MTU/通知/认证）→ 上报连接成功。
     *
     * 关键时序（对齐旧 SDK）：连接成功后「立即 discoverServices」，不在此时 requestHighPriority()——
     * 立即提优先级会触发 HCI LE Connection Update，更新窗口撞上 discoverServices/MTU/认证写帧
     * 会导致链路断/超时。提优先级挪到认证完成后（ConnectionPool.onConnected 里 requestHighPriorityDelayed）。
     */
    private void afterConnected(Session session, ProtocolPlugin plugin, ConnectListener listener) {
        String mac = session.getMac();
        // 整轮收尾只允许回调一次最终结果：重复回调（如断开事件）不能让连接池重复反应
        final java.util.concurrent.atomic.AtomicBoolean finished =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        GattClient client = session.get(SessionKeys.GATT_CLIENT);
        if (client == null) {
            listener.onFailed(session, "Connect", ErrorCode.INTERNAL_ERROR,
                    "连接成功但 Session 里没有 GATT_CLIENT（连接前钩子/裸连步骤未正确挂载）");
            return;
        }
        // 按协议开关决定断开时是否解除系统配对（removeBond）：轮换地址协议 用应用层认证、需解绑清残留，
        // 需要系统配对的设备则保留配对。默认 false，不误伤任何协议。
        if (client instanceof AndroidGattClient) {
            ((AndroidGattClient) client).setRemoveBondOnDisconnect(plugin.removeBondOnDisconnect());
        }
        client.discoverServices((ok, code, msg) -> {
            if (!ok) {
                if (finished.compareAndSet(false, true)) {
                    listener.onFailed(session, "DiscoverServices",
                            code == ErrorCode.CONNECTION_LOST ? code : ErrorCode.DISCOVER_SERVICES_FAILED, msg);
                }
                return;
            }
            QLog.d(TAG, "[" + mac + "] 发现服务完成，进入协议 onGattReady");
            // 兜底超时：协议钩子卡住（如认证等不到应答）时必须让连接流程有终态，
            // 否则连接池槽位被占死、上层永远收不到 onConnected/onFailed（UI 一直"连接中"）
            final java.util.concurrent.atomic.AtomicReference<java.util.concurrent.ScheduledFuture<?>> timeoutRef =
                    new java.util.concurrent.atomic.AtomicReference<>();
            timeoutRef.set(TIMER.schedule(() -> {
                if (finished.compareAndSet(false, true)) {
                    QLog.e(TAG, "[" + mac + "] onGattReady 超时（" + ON_GATT_READY_TIMEOUT_MS
                            + "ms 未完成），终止连接流程");
                    listener.onFailed(session, "onGattReady", ErrorCode.CONNECT_TIMEOUT,
                            "连接就绪超时：" + (ON_GATT_READY_TIMEOUT_MS / 1000)
                                    + "s 内未完成（协议钩子未响应，请检查设备状态）");
                }
            }, ON_GATT_READY_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS));
            plugin.onGattReady(session, client, new GattReadyCallback() {
                @Override
                public void onReady() {
                    cancelTimeout(timeoutRef);
                    if (finished.compareAndSet(false, true)) {
                        QLog.d(TAG, "[" + mac + "] onGattReady 完成，连接成功");
                        listener.onConnected(session);
                    }
                }

                @Override
                public void onFailed(int errorCode, String message) {
                    cancelTimeout(timeoutRef);
                    if (finished.compareAndSet(false, true)) {
                        listener.onFailed(session, "onGattReady", errorCode, message);
                    }
                }
            });
        });
    }

    /**
     * 标准连接（连接前钩子放行后由引擎统一执行）：用 Session 里当前地址（可能被钩子重寻址更新过）
     * 创建 GattClient 并发起 connectGatt（统一的标准连接逻辑，内联进引擎），协议不再需要自己「接管连接」。
     */
    private void standardConnect(Session session, ConnectParams params, ConnectCallback callback) {
        String addr = session.get(SessionKeys.CURRENT_GATT_ADDR, session.getMac());
        GattClient client;
        try {
            client = GattClientFactory.create(addr);
        } catch (RuntimeException e) {
            QLog.e(TAG, "GattClientFactory.create 抛异常，可能忘了 init(context)", e);
            callback.onFailed(ErrorCode.INTERNAL_ERROR, "GattClientFactory 未初始化: " + e.getMessage());
            return;
        }
        session.put(SessionKeys.GATT_CLIENT, client);
        QLog.d(TAG, "标准连接发起 addr=" + addr);
        client.connect((ok, code, msg) -> {
            if (ok) {
                QLog.d(TAG, "标准连接成功 addr=" + addr);
                callback.onConnected();
            } else {
                callback.onFailed(code == ErrorCode.CONNECTION_LOST ? code : ErrorCode.CONNECT_GATT_FAILED, msg);
            }
        }, params);
    }

    private static void cancelTimeout(
            java.util.concurrent.atomic.AtomicReference<java.util.concurrent.ScheduledFuture<?>> ref) {
        java.util.concurrent.ScheduledFuture<?> f = ref.getAndSet(null);
        if (f != null) {
            f.cancel(false);
        }
    }

    private void runStep(Session session, List<ConnectStepSpec> steps, int index,
                         ProtocolPlugin plugin, ConnectListener listener) {
        if (index >= steps.size()) {
            QLog.d(TAG, "[" + session.getMac() + "] 全部连接步骤执行完毕（GATT 已建立），进入统一收尾");
            afterConnected(session, plugin, listener);
            return;
        }
        ConnectStepSpec spec = steps.get(index);
        ConnectStep step = spec.getStep();
        String stepName = step.name();
        QLog.d(TAG, "[" + session.getMac() + "] 第 " + (index + 1) + "/" + steps.size()
                + " 步开始: " + stepName + " args=" + spec.getArgs());

        TimeoutRetryWrapper.execute(step, session, spec.getArgs(), spec.getRetryConfig(), new StepCallback() {
            @Override
            public void onSuccess() {
                QLog.d(TAG, "[" + session.getMac() + "] 步骤 " + stepName + " 成功");
                runStep(session, steps, index + 1, plugin, listener);
            }

            @Override
            public void onFailure(int errorCode, String message) {
                QLog.e(TAG, "[" + session.getMac() + "] 步骤 " + stepName
                        + " 失败 errorCode=" + errorCode + " message=" + message);
                listener.onFailed(session, stepName, errorCode, message);
            }
        });
    }
}
