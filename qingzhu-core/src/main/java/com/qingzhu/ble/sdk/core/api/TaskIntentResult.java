// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.sdk.core.api;

/**
 * 意图被任务接收时的立即结果。
 *
 * <p>这只表示“意图有没有被接受、是否需要排队”，不代表最终物理操作已经成功。
 * 最终结果仍以任务状态、result 和 error 为准。
 */
public enum TaskIntentResult {
    /** 已开始执行 */
    STARTED,
    /** 已进入任务内待办队列 */
    QUEUED,
    /** 当前状态已经满足该意图，例如已连接时再次连接 */
    ALREADY_SATISFIED,
    /** 已有连接或操作正在进行，意图已合并或等待其完成 */
    IN_PROGRESS,
    /** 待办队列已满或当前策略拒绝 */
    BUSY,
    /** 任务已删除，不能再接收意图 */
    CLOSED
}
