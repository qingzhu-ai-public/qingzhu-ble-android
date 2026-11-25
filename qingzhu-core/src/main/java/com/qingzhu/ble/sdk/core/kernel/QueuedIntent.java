// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.sdk.core.kernel;

import com.qingzhu.ble.sdk.core.api.QCommand;
import com.qingzhu.ble.sdk.core.api.TaskCallback;

import java.util.concurrent.atomic.AtomicBoolean;

/** 任务内的一条待执行命令及其结果回调。 */
final class QueuedIntent {
    final String id;
    final QCommand command;
    final TaskCallback callback;
    /** 实际生效的命令超时（0 = 不挂表） */
    long effectiveTimeoutMs;
    /** 固定引用：Handler.removeCallbacks 必须用同一个 Runnable */
    Runnable timeoutRunnable;
    /** 终态去重：所有成功/失败/取消/超时都经 finishIntent 收口 */
    final AtomicBoolean finished = new AtomicBoolean(false);

    QueuedIntent(String id, QCommand command, TaskCallback callback) {
        this.id = id;
        this.command = command;
        this.callback = callback;
    }
}
