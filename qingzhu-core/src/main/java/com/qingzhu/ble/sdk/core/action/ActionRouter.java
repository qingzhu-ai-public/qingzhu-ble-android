// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.sdk.core.action;

import com.qingzhu.ble.sdk.core.common.ErrorCode;
import com.qingzhu.ble.sdk.core.session.Session;
import com.qingzhu.ble.sdk.core.session.SessionRegistry;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 消息门面：一段原始字符串 -> 找到对应设备的 Session -> 按协议 id 找到唯一的
 * {@link ActionHandler} -> 交给它自己解析和处理 -> JsonResponse 回传。
 *
 * 每个协议只注册一个 Handler（key 就是 protocolId），ActionRouter 本身不含任何
 * 协议知识、不解析消息内容、不预设消息里该有哪些字段——这是"任何厂商都能自由接入"的
 * 关键：core 只做"路由到哪个协议"这一件事。
 */
public final class ActionRouter {

    private static final String TAG = "QPlugin";

    private static final ConcurrentHashMap<String, ActionHandler> HANDLERS = new ConcurrentHashMap<>();

    private ActionRouter() {
    }

    /** 每个协议注册自己唯一的消息处理入口，重复注册会覆盖旧的（方便调试时热替换） */
    public static void register(String protocolId, ActionHandler handler) {
        HANDLERS.put(protocolId, handler);
    }

    /** 协议下线时注销，避免回调指向已被移除的协议实现 */
    public static void unregister(String protocolId) {
        HANDLERS.remove(protocolId);
    }

    public static void execute(String mac, String protocolId, String rawMessage,
                                ActionHandler.ActionCallback callback) {
        execute(mac, protocolId, rawMessage, null, callback);
    }

    /** 带附件的执行：{@code attachments} 透传给协议的 {@link ActionHandler#execute(Session, String, Map, ActionHandler.ActionCallback)} */
    public static void execute(String mac, String protocolId, String rawMessage,
                                Map<String, Object> attachments, ActionHandler.ActionCallback callback) {
        Session session = SessionRegistry.get(mac);
        if (session == null) {
            callback.onResult(JsonResponse.error(ErrorCode.DEVICE_NOT_CONNECTED, "设备未连接: " + mac));
            return;
        }
        ActionHandler handler = HANDLERS.get(protocolId);
        if (handler == null) {
            com.qingzhu.ble.sdk.core.common.QLog.e(TAG, "【路由】协议=" + protocolId
                    + " 未注册消息处理器（该协议 AAR 是否未随包提供？）mac=" + mac);
            callback.onResult(JsonResponse.error(ErrorCode.PROTOCOL_NOT_FOUND,
                    "协议 " + protocolId + " 未注册消息处理器"));
            return;
        }
        handler.execute(session, rawMessage, attachments, callback);
    }
}
