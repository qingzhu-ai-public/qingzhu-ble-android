// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.sdk.core.action;

import com.qingzhu.ble.sdk.core.session.Session;

import java.util.Map;

/**
 * 一个协议插件对"发给设备的原始消息"的处理入口。
 *
 * 故意不预设消息格式（不是"action名 + paramsJson"这种固定结构）——core 只负责把
 * 一段原始字符串（可以是任意 JSON、任意文本、任意编码约定）转交给对应协议的这一个
 * Handler，具体怎么解析、里面有没有 action 字段、data 字段叫什么，完全是协议私有的事。
 * 这样任何第三方厂商接入自己的协议时，不需要削足适履去适配一种"预设的门面协议"，
 * 只需要在自己的 Handler 里决定"收到这段字符串该怎么办"。
 *
 * 每个协议只注册一个 Handler（不是每个 action 一个），内部的分发逻辑（比如按 JSON 里的
 * "action" 字段路由到不同处理分支）由协议插件自己实现，core 不关心。
 */
public interface ActionHandler {

    /**
     * 带<b>附件</b>的处理入口：指令是 {@code rawMessage}（文本，协议解析"要做什么"），
     * 大数据（升级包、图片等字节）放 {@code attachments}——协议解析指令后，按自己约定的
     * key 从附件里取 {@code byte[]} / 其它值。
     *
     * <p>设计意图：命令与数据分离。指令保持文本模型（现有 JSON/文本约定不变），大块字节
     * 不进字符串（避免 UTF-8 破坏），由附件携带。key 的语义完全由协议自己定（core 不预设），
     * 比如 {@code attachments.get("firmware")} 拿到升级包字节。
     *
     * <p>{@code attachments} 为 {@code null} 表示无附件（普通指令）。
     */
    void execute(Session session, String rawMessage, Map<String, Object> attachments, ActionCallback callback);

    /** 便捷重载：无附件（等价于 {@code execute(session, rawMessage, null, callback)}） */
    default void execute(Session session, String rawMessage, ActionCallback callback) {
        execute(session, rawMessage, null, callback);
    }

    interface ActionCallback {
        void onResult(JsonResponse response);

        /**
         * 大块传输（OTA/刷图）分包进度：协议层在逐包写入时上报<b>字节数</b>（事实），
         * 百分比由上层按 {@code sentBytes*100/totalBytes} 推导。普通一问一答不触发。
         *
         * @param sentBytes  已写入的字节数（近似到片）
         * @param totalBytes 总字节数
         */
        default void onProgress(int sentBytes, int totalBytes) {
        }
    }
}
