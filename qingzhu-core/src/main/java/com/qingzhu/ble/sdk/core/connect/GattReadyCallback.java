// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.sdk.core.connect;

/**
 * 协议"GATT 就绪钩子"（{@code ProtocolPlugin#onGattReady}）的结果回调。
 * 发现服务完成后，协议在钩子里找特征、使能通知、做认证，最后二选一回报。
 */
public interface GattReadyCallback {

    /** 业务准备完成（找特征 / 使能通知 / 认证全部通过），可以开始收发数据 */
    void onReady();

    /** 业务准备失败 */
    void onFailed(int errorCode, String message);
}
