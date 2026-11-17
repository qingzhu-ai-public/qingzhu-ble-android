// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.sdk.core.connect;

import com.qingzhu.ble.sdk.core.session.Session;

/** 一整份配方跑完之后的最终结果回调 */
public interface ConnectListener {

    void onConnected(Session session);

    /** @param failedStep 具体是哪一步失败的，方便排查（对应现有系统里"日志 grep 到哪个 op"这类诉求） */
    void onFailed(Session session, String failedStep, int errorCode, String message);
}
