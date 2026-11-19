// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.sdk.core.api;

/** 订阅句柄：可提前取消。传入 owner 的订阅在 owner 销毁时自动取消。 */
public interface Subscription {

    /** 取消订阅，之后不再回调 */
    void cancel();

    /** 订阅是否仍然有效 */
    boolean isActive();
}
