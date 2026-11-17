// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.sdk.core.connect;

/**
 * 单个步骤的超时/重试策略。每个 ConnectStepSpec 可以覆盖默认策略——
 * 这是解决"明文版密钥错误不重试、加密版握手失败误重试"这类协议间策略冲突的机制：
 * 两个协议各自声明各自的 retryOnFail，互不影响，
 * 不需要在引擎里写 if 分支区分协议。
 */
public final class RetryConfig {

    public static final RetryConfig DEFAULT = new RetryConfig(10_000, true, 3, 500);

    private final long timeoutMs;
    private final boolean retryOnFail;
    private final int maxRetries;
    private final long retryIntervalMs;

    public RetryConfig(long timeoutMs, boolean retryOnFail, int maxRetries, long retryIntervalMs) {
        this.timeoutMs = timeoutMs;
        this.retryOnFail = retryOnFail;
        this.maxRetries = maxRetries;
        this.retryIntervalMs = retryIntervalMs;
    }

    public static RetryConfig noRetry(long timeoutMs) {
        return new RetryConfig(timeoutMs, false, 0, 0);
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }

    public boolean isRetryOnFail() {
        return retryOnFail;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public long getRetryIntervalMs() {
        return retryIntervalMs;
    }
}
