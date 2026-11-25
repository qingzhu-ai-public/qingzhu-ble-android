// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.sdk.core.session;

import com.qingzhu.ble.sdk.core.protocol.ProtocolType;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 每个连接对应一个 Session 实例，生命周期与连接一致（连接建立时创建，断开时清空/移除）。
 *
 * 硬性规则（对应《新SDK架构-愿景与初步设计.md》第6节原则2/3）：
 * 1. 所有"连接过程中产生、之后还要用到"的状态（密钥、Nonce、序号计数器等），只能读写这个对象，
 *    不允许用静态字段 / 静态 Map 存储 —— 那样在多连接场景下会互相踩踏（现有 SecuritySessionHolder 的问题）。
 * 2. 步骤（ConnectStep）之间、管道环节（Filter）之间，只能通过 Session 交换数据，不允许有其他隐式通道。
 *
 * key 建议按协议自己的命名空间加前缀，避免不同协议/不同环节之间撞名，
 * 例如 "nanolink_encrypt.sessionKey"、"txpggp.secretKey"。
 */
public final class Session {

    private final String mac;
    private final ProtocolType protocolType;
    private final ConcurrentHashMap<String, Object> attributes = new ConcurrentHashMap<>();

    public Session(String mac, ProtocolType protocolType) {
        this.mac = mac;
        this.protocolType = protocolType;
    }

    public String getMac() {
        return mac;
    }

    public ProtocolType getProtocolType() {
        return protocolType;
    }

    /** 写入一个状态。value 为 null 时等价于 remove。 */
    public void put(String key, Object value) {
        if (value == null) {
            attributes.remove(key);
        } else {
            attributes.put(key, value);
        }
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        return (T) attributes.get(key);
    }

    /** 带默认值的读取，避免调用方到处写 null 判断 */
    @SuppressWarnings("unchecked")
    public <T> T get(String key, T defaultValue) {
        Object v = attributes.get(key);
        return v == null ? defaultValue : (T) v;
    }

    public boolean has(String key) {
        return attributes.containsKey(key);
    }

    public void remove(String key) {
        attributes.remove(key);
    }

    /** 连接断开时调用，清空所有派生状态。下次重连必须重新走配方产生新状态，不存在"会话续期"。 */
    public void clear() {
        attributes.clear();
    }

    @Override
    public String toString() {
        return "Session(mac=" + mac + ", protocol=" + protocolType + ", attrs=" + attributes.size() + ")";
    }
}
