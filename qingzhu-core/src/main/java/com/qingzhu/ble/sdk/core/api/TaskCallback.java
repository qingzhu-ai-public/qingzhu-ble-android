// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.sdk.core.api;

/** 命令结果回调，回调在主线程，每个命令恰好回调一次。 */
public interface TaskCallback {
    void onResult(TaskResult result);
}
