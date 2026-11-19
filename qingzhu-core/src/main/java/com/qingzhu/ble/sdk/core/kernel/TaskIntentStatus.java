// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.sdk.core.kernel;

/** 命令的内部终态（不对外暴露，对外转换为 TaskResult）。 */
enum TaskIntentStatus {
    SUCCEEDED,
    FAILED,
    CANCELLED
}
