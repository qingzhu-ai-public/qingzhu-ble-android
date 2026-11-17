// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.sdk.core.kernel;

import com.qingzhu.ble.sdk.core.api.QCommand;
import com.qingzhu.ble.sdk.core.api.TaskIntentCallback;

/** 任务内的一条待发送意图及其完成回调。 */
final class QueuedIntent {
    final String id;
    final QCommand command;
    final TaskIntentCallback callback;

    QueuedIntent(String id, QCommand command, TaskIntentCallback callback) {
        this.id = id;
        this.command = command;
        this.callback = callback;
    }
}
