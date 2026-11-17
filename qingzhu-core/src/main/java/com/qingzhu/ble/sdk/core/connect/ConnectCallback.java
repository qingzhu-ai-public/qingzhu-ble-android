// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.sdk.core.connect;

/**
 * 连接回调：连接前钩子（{@code ProtocolPlugin#beforeConnect}）与标准连接的结果通知。
 * 三个信号分别对应「连接前钩子处理完 → 继续连接」「GATT 连接已建立」「连接失败」。
 */
public interface ConnectCallback {

    /** 连接前钩子处理完成，继续标准连接（connectGatt）。默认空实现，供只关心成败的调用方省略 */
    default void onReady() {
    }

    /** GATT 连接已建立（onConnectionStateChange 回调 STATE_CONNECTED） */
    void onConnected();

    /** 连接失败或链路已断开 */
    void onFailed(int errorCode, String message);
}
