// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.sdk.core.connect;

import java.util.Collections;
import java.util.Map;

/**
 * 连接流程里的一步：一个 ConnectStep 实例 + 它的参数 + 重试策略。
 *
 * 这是"代码"，不是"声明"——协议直接 new 出步骤实例（内置步骤用单例，自定义步骤用自家类），
 * 按顺序组成连接流程，交给 ConnectionEngine 执行。
 * 相比早期的声明式 Recipe（按名字查表 + JSON 参数），代码式把"怎么连"的完整自由度还给协议：
 * 条件分支、中途变量、系统 API 直通都能直接写，引擎仍统一负责超时/重试/失败上报。
 */
public final class ConnectStepSpec {

    private final ConnectStep step;
    private final Map<String, Object> args;
    private final RetryConfig retryConfig;

    public ConnectStepSpec(ConnectStep step, Map<String, Object> args, RetryConfig retryConfig) {
        this.step = step;
        this.args = args == null ? Collections.emptyMap() : args;
        this.retryConfig = retryConfig == null ? RetryConfig.DEFAULT : retryConfig;
    }

    public static ConnectStepSpec of(ConnectStep step) {
        return new ConnectStepSpec(step, null, null);
    }

    public static ConnectStepSpec of(ConnectStep step, Map<String, Object> args) {
        return new ConnectStepSpec(step, args, null);
    }

    public static ConnectStepSpec of(ConnectStep step, Map<String, Object> args, RetryConfig retryConfig) {
        return new ConnectStepSpec(step, args, retryConfig);
    }

    public ConnectStep getStep() {
        return step;
    }

    public Map<String, Object> getArgs() {
        return args;
    }

    public RetryConfig getRetryConfig() {
        return retryConfig;
    }
}