// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.sdk.core.connect;

import com.qingzhu.ble.sdk.core.common.ErrorCode;
import com.qingzhu.ble.sdk.core.common.QLog;
import com.qingzhu.ble.sdk.core.session.Session;

import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 统一负责每一步的超时、重试。协议只在 ConnectStepSpec 里声明策略参数（RetryConfig），
 * 不需要、也不允许自己实现重试循环——这条规则保证"重试逻辑"这类骨架代码永远只有一份实现，
 * 不会被每个协议各自复制一遍（对应 不同协议 现有代码里各自维护一套重试逻辑的问题）。
 */
public final class TimeoutRetryWrapper {

    private static final String TAG = "TimeoutRetryWrapper";

    private static final ScheduledExecutorService TIMER =
            new ScheduledThreadPoolExecutor(1, r -> {
                Thread t = new Thread(r, "qingzhu-step-timer");
                t.setDaemon(true);
                return t;
            });

    private TimeoutRetryWrapper() {
    }

    public static void execute(ConnectStep step, Session session, Map<String, Object> args,
                                RetryConfig retryConfig, StepCallback finalCallback) {
        attempt(step, session, args, retryConfig, finalCallback, 0);
    }

    private static void attempt(ConnectStep step, Session session, Map<String, Object> args,
                                 RetryConfig retryConfig, StepCallback finalCallback, int attemptIndex) {
        AtomicBoolean done = new AtomicBoolean(false);
        QLog.d(TAG, step.name() + " 第 " + (attemptIndex + 1) + " 次尝试，超时=" + retryConfig.getTimeoutMs() + "ms");

        ScheduledFuture<?> timeoutFuture = TIMER.schedule(() -> {
            if (done.compareAndSet(false, true)) {
                QLog.e(TAG, step.name() + " 第 " + (attemptIndex + 1) + " 次尝试超时（"
                        + retryConfig.getTimeoutMs() + "ms 内未收到 onSuccess/onFailure 回调）");
                step.cancel(session);
                handleFailure(step, session, args, retryConfig, finalCallback, attemptIndex,
                        ErrorCode.CONNECT_TIMEOUT, "步骤 " + step.name() + " 执行超时");
            }
        }, retryConfig.getTimeoutMs(), TimeUnit.MILLISECONDS);

        step.execute(session, args, new StepCallback() {
            @Override
            public void onSuccess() {
                if (done.compareAndSet(false, true)) {
                    timeoutFuture.cancel(false);
                    finalCallback.onSuccess();
                }
            }

            @Override
            public void onFailure(int errorCode, String message) {
                if (done.compareAndSet(false, true)) {
                    timeoutFuture.cancel(false);
                    handleFailure(step, session, args, retryConfig, finalCallback, attemptIndex, errorCode, message);
                }
            }
        });
    }

    private static void handleFailure(ConnectStep step, Session session, Map<String, Object> args,
                                       RetryConfig retryConfig, StepCallback finalCallback,
                                       int attemptIndex, int errorCode, String message) {
        // 链路中途断开是不可重试错误：后续所有步骤都建立在链路存在的前提上，
        // 原地重试只会撞上已被 close 的 gatt（"gatt 未初始化"），必须让配方整体失败重来
        boolean connectionLost = errorCode == ErrorCode.CONNECTION_LOST;
        boolean canRetry = !connectionLost
                && retryConfig.isRetryOnFail() && attemptIndex < retryConfig.getMaxRetries();
        if (!canRetry) {
            QLog.e(TAG, step.name() + " 最终失败（不再重试，"
                    + (connectionLost ? "链路已断开" : "retryOnFail=" + retryConfig.isRetryOnFail()
                    + " attemptIndex=" + attemptIndex + " maxRetries=" + retryConfig.getMaxRetries())
                    + "） errorCode=" + errorCode + " message=" + message);
            finalCallback.onFailure(errorCode, message);
            return;
        }
        QLog.w(TAG, step.name() + " 第 " + (attemptIndex + 1) + " 次失败，"
                + retryConfig.getRetryIntervalMs() + "ms 后重试");
        TIMER.schedule(() -> attempt(step, session, args, retryConfig, finalCallback, attemptIndex + 1),
                retryConfig.getRetryIntervalMs(), TimeUnit.MILLISECONDS);
    }
}
