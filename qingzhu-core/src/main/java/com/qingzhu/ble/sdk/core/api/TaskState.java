// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.sdk.core.api;

/**
 * 任务状态。
 *
 * <p>顶层分两类（判据：有没有命令）：
 * <ul>
 *   <li><b>待机中</b>（无命令）：{@link #UNKNOWN} 未知设备、{@link #READY} 就绪</li>
 *   <li><b>执行中</b>（有命令）：{@link #SEARCHING} ~ {@link #SENDING}</li>
 * </ul>
 */
public enum TaskState {
    /** 待机中：只给了 mac，还没找到设备 */
    UNKNOWN,
    /** 待机中：找到了 / 有 QDevice，没连，等命令 */
    READY,

    /** 执行中：占位任务在扫描池里寻人 */
    SEARCHING,
    /** 执行中：取号了，等连接窗口 */
    QUEUED,
    /** 执行中：正在建连 */
    CONNECTING,
    /** 执行中：已连接（保持连接） */
    CONNECTED,
    /** 执行中：正在发数据 */
    SENDING;

    /** 是否待机中（无命令，不占窗口） */
    public boolean isIdle() {
        return this == UNKNOWN || this == READY;
    }

    /** 是否执行中（有命令） */
    public boolean isRunning() {
        return !isIdle();
    }
}
