// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.sdk.core.connect;

import com.qingzhu.ble.sdk.core.session.Session;

import java.util.Collections;
import java.util.Map;

/**
 * 一个连接步骤。所有步骤输入输出都走 Session，不允许有其他隐式通道
 *（架构设计文档第6节原则2）。
 *
 * 引擎负责调度这些步骤（顺序执行、超时、重试、失败上报），步骤本身只管自己那一件事。
 *
 * 内置步骤库里的实现类是终态的、可直接实例化使用的"零件"，不允许被继承——
 * 协议方如果发现某个内置步骤的参数不够用，走的路径是自己实现一个新的 ConnectStep（逃生舱），
 * 不是继承它改一部分。这条规则直接堵死"继承 + 覆写导致代码量下不去"的老路
 *（详见技术设计文档第4.2节）。
 */
public interface ConnectStep {

    /** 步骤名，配方 JSON 里用这个名字引用，如 "ConnectGatt" "AuthCmac" */
    String name();

    /**
     * 执行这一步。
     *
     * @param session  当前连接的状态容器
     * @param args     配方里为这一步声明的参数
     * @param callback 执行结果回调，只能调用一次
     */
    void execute(Session session, Map<String, Object> args, StepCallback callback);

    /** 取消/超时时调用，用于释放本步骤持有的资源（如临时的 GATT 监听器）。默认空实现。 */
    default void cancel(Session session) {
    }

    static Map<String, Object> emptyArgs() {
        return Collections.emptyMap();
    }
}
