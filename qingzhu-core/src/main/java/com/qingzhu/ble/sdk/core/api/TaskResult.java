// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.sdk.core.api;

/** 单个命令的最终结果，每个命令恰好回调一次。 */
public final class TaskResult {

    private final boolean success;
    private final String payload;
    private final String error;
    private final TaskStage stage;

    private TaskResult(boolean success, String payload, String error, TaskStage stage) {
        this.success = success;
        this.payload = payload;
        this.error = error;
        this.stage = stage;
    }

    public static TaskResult success(String payload) {
        return new TaskResult(true, payload, null, null);
    }

    public static TaskResult failure(TaskStage stage, String error) {
        return new TaskResult(false, null, error, stage);
    }

    public boolean isSuccess() { return success; }

    /** 成功时从机回显的数据；失败时为 null */
    public String payload() { return payload; }

    /** 失败原因；成功时为 null */
    public String error() { return error; }

    /** 失败所处阶段；成功时为 null */
    public TaskStage stage() { return stage; }
}
