// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.sdk.core.api;

/** 单条任务意图的最终结果回调，回调在主线程执行。 */
public interface TaskIntentCallback {
    /**
     * @param intentId 任务内唯一的意图 ID
     * @param status   成功、失败或取消
     * @param result   成功时设备回显的数据，可能为 null
     * @param error    失败或取消原因，可能为 null
     */
    void onFinished(String intentId, TaskIntentStatus status, String result, String error);
}
