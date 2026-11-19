// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.sdk.core.api;

/** 任务状态变化回调，回调在主线程。 */
public interface TaskObserver {
    void onChanged(TaskSnapshot snapshot);
}
