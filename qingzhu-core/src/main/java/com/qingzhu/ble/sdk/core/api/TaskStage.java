// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.sdk.core.api;

/** 任务失败所处的阶段。 */
public enum TaskStage {
    /** 寻找设备 */
    SEARCH,
    /** 建立连接 */
    CONNECT,
    /** 发送数据 */
    SEND,
    /** 命令等待应答超时 */
    TIMEOUT,
    /** 被取消 */
    CANCEL,
    /** 被删除 */
    DELETE,
    /** 待办队列已满 */
    BUSY,
    /** 任务已删除，不能再接收命令 */
    CLOSED
}
