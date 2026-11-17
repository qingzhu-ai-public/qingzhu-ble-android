// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.sdk.core.pool;

/**
 * 自动重连策略（意外断开时生效，用户主动断开不会触发重连）。
 *
 * 默认 {@link #OFF}：意外断开只通知调用方，不做任何自动动作。
 * 开启后：连接意外丢失时按"间隔 + 退避倍率"自动重连最多 maxRetries 次——
 * 复用连接池既有的新鲜地址闸门（设备重新广播被扫到才真正发起 GATT 连接），
 * 重连成功后重置计数并再次回调 onConnected；全部耗尽才回调 onFailed。
 *
 * 示例：每 3 秒重连，最多 3 次
 * <pre>ReconnectPolicy.of(3, 3000)</pre>
 * 指数退避：3s → 6s → 12s
 * <pre>ReconnectPolicy.withBackoff(3, 3000, 2f)</pre>
 */
public final class ReconnectPolicy {

    /** 不自动重连（默认） */
    public static final ReconnectPolicy OFF = new ReconnectPolicy(0, 0, 1f);
    /** 无限重连用这个标记（退避上限保护见 {@link #MAX_BACKOFF_MS}） */
    private static final int INFINITE = Integer.MAX_VALUE;
    /** 退避间隔上限：无限重连时 attempt 无限涨，不设上限会 long 溢出 + 间隔不可控 */
    private static final long MAX_BACKOFF_MS = 60_000;

    private final int maxRetries;
    private final long intervalMs;
    private final float backoffMultiplier;

    private ReconnectPolicy(int maxRetries, long intervalMs, float backoffMultiplier) {
        if (maxRetries < 0 || intervalMs < 0 || backoffMultiplier < 1f) {
            throw new IllegalArgumentException("maxRetries >= 0, intervalMs >= 0, backoffMultiplier >= 1");
        }
        this.maxRetries = maxRetries;
        this.intervalMs = intervalMs;
        this.backoffMultiplier = backoffMultiplier;
    }

    /** 固定间隔自动重连：maxRetries 次、每次间隔 intervalMs */
    public static ReconnectPolicy of(int maxRetries, long intervalMs) {
        return new ReconnectPolicy(maxRetries, intervalMs, 1f);
    }

    /** 递增间隔自动重连：第 n 次重连的等待时间 = intervalMs * multiplier^(n-1) */
    public static ReconnectPolicy withBackoff(int maxRetries, long intervalMs, float backoffMultiplier) {
        return new ReconnectPolicy(maxRetries, intervalMs, backoffMultiplier);
    }

    /**
     * 无限自动重连（固定间隔）：直到用户主动断开/cancel 才停止，永不因次数耗尽而终态失败。
     * 适合「设备可能还要开机/进范围」的 Forever 场景。单次连接仍有兜底超时（见连接池），
     * 只是"重试次数"无限；使用方必须通过返回的句柄 cancel 来停。
     */
    public static ReconnectPolicy forever(long intervalMs) {
        return new ReconnectPolicy(INFINITE, intervalMs, 1f);
    }

    /** 无限自动重连 + 指数退避（间隔封顶 {@link #MAX_BACKOFF_MS}） */
    public static ReconnectPolicy foreverWithBackoff(long intervalMs, float backoffMultiplier) {
        return new ReconnectPolicy(INFINITE, intervalMs, backoffMultiplier);
    }

    public boolean enabled() {
        return maxRetries > 0;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    /** 是否无限重连 */
    public boolean isForever() {
        return maxRetries == INFINITE;
    }

    /** 第 attempt 次重连（1 起）前应等待的毫秒数；退避封顶 {@link #MAX_BACKOFF_MS}，防止无限重连时溢出 */
    public long intervalForAttempt(int attempt) {
        long ms = (long) (intervalMs * Math.pow(backoffMultiplier, attempt - 1));
        return Math.min(ms, MAX_BACKOFF_MS);
    }

    @Override
    public String toString() {
        return "ReconnectPolicy{maxRetries=" + maxRetries + ", intervalMs=" + intervalMs
                + ", backoff=" + backoffMultiplier + "}";
    }
}
