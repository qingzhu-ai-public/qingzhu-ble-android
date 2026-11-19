// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.sdk.core.kernel;

/** 命令被任务接收时的内部立即结果（不对外暴露）。 */
enum TaskIntentResult {
    STARTED,
    QUEUED,
    ALREADY_SATISFIED,
    IN_PROGRESS,
    BUSY,
    CLOSED
}
