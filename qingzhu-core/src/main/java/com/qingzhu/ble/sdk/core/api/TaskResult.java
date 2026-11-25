// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.sdk.core.api;

/** 单个命令的最终结果，每个命令恰好回调一次。 */
public final class TaskResult {

    private final boolean success;
    private final String payload;
    private final String error;
    private final TaskStage stage;
    private final int code;

    private TaskResult(boolean success, String payload, String error, TaskStage stage, int code) {
        this.success = success;
        this.payload = payload;
        this.error = error;
        this.stage = stage;
        this.code = code;
    }

    public static TaskResult success(String payload) {
        return new TaskResult(true, payload, null, null, 0);
    }

    public static TaskResult failure(TaskStage stage, String error) {
        return failure(stage, 0, error);
    }

    public static TaskResult failure(TaskStage stage, int code, String error) {
        return new TaskResult(false, null, error, stage, code);
    }

    public boolean isSuccess() { return success; }

    /** 成功时从机回显的数据；失败时为 null */
    public String payload() { return payload; }

    /** 失败原因；成功时为 null */
    public String error() { return error; }

    /** 失败所处阶段；成功时为 null */
    public TaskStage stage() { return stage; }

    /** 内核错误码；0 = 未附错误码，命令超时为 {@code ErrorCode.TASK_TIMEOUT} */
    public int code() { return code; }
}
