// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.sdk.core.kernel;

import com.qingzhu.ble.sdk.core.api.QCommand;
import com.qingzhu.ble.sdk.core.api.TaskCallback;

/** 任务内的一条待执行命令及其结果回调。 */
final class QueuedIntent {
    final String id;
    final QCommand command;
    final TaskCallback callback;

    QueuedIntent(String id, QCommand command, TaskCallback callback) {
        this.id = id;
        this.command = command;
        this.callback = callback;
    }
}
