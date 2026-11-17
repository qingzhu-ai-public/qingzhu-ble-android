// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.sdk.core.connect;

/**
 * 一个连接步骤的执行结果回调。步骤内部无论要发几个包、等几次 notify，最终只允许调用一次
 * onSuccess 或 onFailure —— 这是保证 ConnectionEngine 调度逻辑正确的前提。
 */
public interface StepCallback {

    void onSuccess();

    void onFailure(int errorCode, String message);
}
