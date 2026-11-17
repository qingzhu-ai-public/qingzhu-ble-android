// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.sdk.core.api;

/** 单条任务意图的最终结果。 */
public enum TaskIntentStatus {
    /** 已经执行成功 */
    SUCCEEDED,
    /** 重试耗尽或不可恢复失败 */
    FAILED,
    /** 被取消、删除，或因前序命令失败而终止 */
    CANCELLED
}
