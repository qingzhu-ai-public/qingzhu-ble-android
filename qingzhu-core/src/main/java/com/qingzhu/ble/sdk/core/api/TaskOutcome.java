// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.sdk.core.api;

/**
 * 任务最近一次命令的终态分类，供批量统计和自动并发学习使用。
 */
public enum TaskOutcome {
    /** 没有命令、命令仍在执行，或当前结果尚未定论 */
    NONE,
    /** 命令执行成功 */
    SUCCEEDED,
    /** 寻人、连接、发现服务、认证等连接阶段失败 */
    CONNECT_FAILED,
    /** 已连接后发送或业务操作失败 */
    OPERATION_FAILED,
    /** 被取消、删除，或前序命令失败导致待办终止 */
    CANCELLED
}
