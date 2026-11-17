// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.sdk.core.pool;

import com.qingzhu.ble.sdk.core.session.Session;

public interface PoolConnectListener {

    void onConnected(Session session);

    void onFailed(String mac, int errorCode, String message);

    /**
     * 连接意外断开（非用户主动断开：从机重启/信号丢失/GATT 栈内部错误等）时回调。
     * 若该连接配置了 {@link ReconnectPolicy}，SDK 会自动重连并在成功后再次回调
     * {@link #onConnected}；全部重试耗尽后才回调 {@link #onFailed}。
     *
     * @param mac       断开时使用的地址
     * @param attempt   即将进行的自动重连序号（1 起）
     * @param maxRetries 配置的最大自动重连次数
     */
    default void onUnexpectedDisconnected(String mac, int attempt, int maxRetries) {
    }
}
