// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.sdk.core.connect;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 连接参数载体：门面 builder 收集 → 连接前钩子（beforeConnect）/ 标准连接读取。
 *
 * <p>设计原则：core <b>只承载不翻译</b>——这里放的是"调用方声明的连接意图"（要不要自动连、
 * 偏好哪个 PHY、transport 用哪个），具体怎么用这些值（映射到哪个系统 API 参数）完全由连接方
 * 自己决定。系统加新参数时，调用方往 {@link #put} 塞自定义 key，连接方直接读，core 无感知。
 *
 * <p>所有字段可为 null（= 未设置，连接方自行决定默认值），因此不提供 getter 的默认值兜底，
 * 由连接方用 {@code getXxx(k, default)} 语义判断。
 */
public final class ConnectParams {

    private Boolean autoConnect;
    private Integer preferredPhy;
    private Integer transport;
    private final Map<String, Object> extras = new ConcurrentHashMap<>();

    public ConnectParams() {
    }

    public Boolean getAutoConnect() {
        return autoConnect;
    }

    public void setAutoConnect(Boolean autoConnect) {
        this.autoConnect = autoConnect;
    }

    public Integer getPreferredPhy() {
        return preferredPhy;
    }

    public void setPreferredPhy(Integer preferredPhy) {
        this.preferredPhy = preferredPhy;
    }

    public Integer getTransport() {
        return transport;
    }

    public void setTransport(Integer transport) {
        this.transport = transport;
    }

    /** 自定义参数（协议专属，core 不解释语义），如 "force1M"、"rescanTimeoutSec" 等 */
    public void put(String key, Object value) {
        extras.put(key, value);
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        return (T) extras.get(key);
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        Object v = extras.get(key);
        return v instanceof Boolean ? (Boolean) v : defaultValue;
    }

    public int getInt(String key, int defaultValue) {
        Object v = extras.get(key);
        return v instanceof Number ? ((Number) v).intValue() : defaultValue;
    }
}
