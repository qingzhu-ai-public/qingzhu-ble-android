// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.sdk.core.protocol;

/**
 * 协议可配置项声明：协议插件通过 ProtocolPlugin.configurableParams() 暴露
 * "有哪些东西可以调"，值由上层（公司 SDK / 接入方）通过 ProtocolParams 设置。
 *
 * 职责分离：协议只负责"暴露 + 提供默认值"，不负责"定制"——定制权在公司 SDK 手里。
 * 每个 param 带 key/类型/默认值/说明，上层设置时会做 key 存在性与类型校验，
 * 拼错 key 或类型不匹配立刻暴露，而不是静默失效。
 */
public final class ProtocolParam {

    public enum Type { INT, LONG, DOUBLE, BOOLEAN, STRING }

    private final String key;
    private final Type type;
    private final Object defaultValue;
    private final String description;

    private ProtocolParam(String key, Type type, Object defaultValue, String description) {
        this.key = key;
        this.type = type;
        this.defaultValue = defaultValue;
        this.description = description;
    }

    public static ProtocolParam ofInt(String key, int defaultValue, String description) {
        return new ProtocolParam(key, Type.INT, defaultValue, description);
    }

    public static ProtocolParam ofLong(String key, long defaultValue, String description) {
        return new ProtocolParam(key, Type.LONG, defaultValue, description);
    }

    public static ProtocolParam ofDouble(String key, double defaultValue, String description) {
        return new ProtocolParam(key, Type.DOUBLE, defaultValue, description);
    }

    public static ProtocolParam ofBool(String key, boolean defaultValue, String description) {
        return new ProtocolParam(key, Type.BOOLEAN, defaultValue, description);
    }

    public static ProtocolParam ofString(String key, String defaultValue, String description) {
        return new ProtocolParam(key, Type.STRING, defaultValue, description);
    }

    public String getKey() {
        return key;
    }

    public Type getType() {
        return type;
    }

    public Object getDefaultValue() {
        return defaultValue;
    }

    public String getDescription() {
        return description;
    }
}