// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.sdk.core.task;

/** TaskScheduler 调度的最小单位：一次命令执行。run 完成后必须调用 onDone。 */
public interface Task {

    void run(Runnable onDone);
}
