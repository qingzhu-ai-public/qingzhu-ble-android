// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.sdk.core.pool;

/** 连接池队列也满了之后怎么处理，可插拔（对应线程池的 RejectedExecutionHandler）。 */
public interface RejectionPolicy {

    void onRejected(ConnectRequest request);

    /** 默认策略：直接回调失败，不做任何重试 */
    RejectionPolicy IMMEDIATE_FAIL = request ->
            request.getListener().onFailed(request.getMac(), com.qingzhu.ble.sdk.core.common.ErrorCode.POOL_FULL_REJECTED,
                    "连接池已满（核心+队列容量耗尽），请稍后重试");
}
