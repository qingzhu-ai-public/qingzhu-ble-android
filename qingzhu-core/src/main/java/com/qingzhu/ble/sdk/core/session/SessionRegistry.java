// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.sdk.core.session;

import com.qingzhu.ble.sdk.core.protocol.ProtocolType;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按 mac 管理 Session 生命周期的容器。
 *
 * 这是 core 里唯一允许存在的"全局表"，因为它只做生命周期管理（"哪个 mac 对应哪个 Session 实例"），
 * 不存业务状态——业务状态永远只在 Session.attributes 内部，不会因为这里是全局的而产生串号风险。
 */
public final class SessionRegistry {

    private static final ConcurrentHashMap<String, Session> SESSIONS = new ConcurrentHashMap<>();

    private SessionRegistry() {
    }

    public static Session getOrCreate(String mac, ProtocolType type) {
        return SESSIONS.computeIfAbsent(mac, k -> new Session(mac, type));
    }

    public static Session get(String mac) {
        return SESSIONS.get(mac);
    }

    /** 连接断开/释放槎位时调用：清空该 Session 的业务状态并从表里移除。 */
    public static void remove(String mac) {
        Session s = SESSIONS.remove(mac);
        if (s != null) {
            s.clear();
        }
    }

    public static int activeCount() {
        return SESSIONS.size();
    }

    public static Collection<Session> activeSessions() {
        return SESSIONS.values();
    }
}
