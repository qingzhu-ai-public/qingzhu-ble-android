// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.sdk.core.protocol;

import com.qingzhu.ble.sdk.core.session.Session;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 协议参数值容器：按 {@link ProtocolParam} 声明做 key/类型校验的键值对。
 *
 * 用法（宿主 SDK 侧）：
 * <pre>
 *     ProtocolParams params = new ProtocolParams(new MyProtocolPlugin());
 *     params.set("scan.scanMode", ScanSettings.SCAN_MODE_LOW_LATENCY);  // 宿主定制
 *     // 构造时已填入协议声明的默认值，set 过的就是定制过的
 * </pre>
 *
 * 生效路径：
 * 1. connect 时传入 → 连接池复制进 Session（key 加 "param." 前缀），协议步骤/Filter 读取；
 * 2. 扫描相关参数由宿主 SDK 门面自己读出来拼进 ScanConfig（协议声明什么可调，门面就暴露什么）。
 *
 * 校验规则：set 不存在的 key 或类型不匹配直接抛 IllegalArgumentException——
 * 配置错误应该在开发期暴露，而不是静默失效让线上行为难以解释。
 */
public final class ProtocolParams {

    /** Session 里的 key 前缀：param.&lt;key&gt;，与协议 metadata 天然隔离 */
    public static final String SESSION_PREFIX = "param.";

    private final Map<String, ProtocolParam> specs = new LinkedHashMap<>();
    private final Map<String, Object> values = new ConcurrentHashMap<>();

    public ProtocolParams(List<ProtocolParam> paramSpecs) {
        for (ProtocolParam spec : paramSpecs) {
            specs.put(spec.getKey(), spec);
            values.put(spec.getKey(), spec.getDefaultValue()); // 初始值 = 协议声明的默认值
        }
    }

    public ProtocolParams(ProtocolPlugin plugin) {
        this(plugin.configurableParams());
    }

    /** 设置一个参数值（key 必须是协议声明过的，类型必须匹配） */
    public void set(String key, Object value) {
        ProtocolParam spec = specs.get(key);
        if (spec == null) {
            throw new IllegalArgumentException("未声明的协议参数: " + key
                    + "（可用参数: " + specs.keySet() + "）");
        }
        checkType(key, spec.getType(), value);
        values.put(key, value);
    }

    /** 恢复某个参数为协议默认值 */
    public void reset(String key) {
        ProtocolParam spec = specs.get(key);
        if (spec != null) {
            values.put(key, spec.getDefaultValue());
        }
    }

    public Integer getInt(String key) {
        return (Integer) get(key, ProtocolParam.Type.INT);
    }

    public Long getLong(String key) {
        return (Long) get(key, ProtocolParam.Type.LONG);
    }

    public Double getDouble(String key) {
        return (Double) get(key, ProtocolParam.Type.DOUBLE);
    }

    public Boolean getBool(String key) {
        return (Boolean) get(key, ProtocolParam.Type.BOOLEAN);
    }

    public String getString(String key) {
        return (String) get(key, ProtocolParam.Type.STRING);
    }

    public boolean has(String key) {
        return values.containsKey(key);
    }

    /** 当前生效的全部参数（含默认值），快照 */
    public Map<String, Object> asMap() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    /** 全部参数声明（key/类型/默认值/说明）——宿主 SDK 可以据此生成配置界面 */
    public List<ProtocolParam> getSpecs() {
        return Collections.unmodifiableList(
                specs.values().stream().collect(java.util.stream.Collectors.toList()));
    }

    /** 连接时把参数复制进 Session（key 加 "param." 前缀），协议步骤/Filter 据此读取 */
    public void copyInto(Session session) {
        for (Map.Entry<String, Object> e : values.entrySet()) {
            session.put(SESSION_PREFIX + e.getKey(), e.getValue());
        }
    }

    private Object get(String key, ProtocolParam.Type expected) {
        Object v = values.get(key);
        if (v == null) {
            return null;
        }
        checkType(key, expected, v);
        return v;
    }

    private void checkType(String key, ProtocolParam.Type expected, Object value) {
        if (value == null) {
            throw new IllegalArgumentException("协议参数 " + key + " 的值不能为 null");
        }
        boolean ok;
        switch (expected) {
            case INT:
                ok = value instanceof Integer;
                break;
            case LONG:
                ok = value instanceof Long;
                break;
            case DOUBLE:
                ok = value instanceof Double;
                break;
            case BOOLEAN:
                ok = value instanceof Boolean;
                break;
            case STRING:
                ok = value instanceof String;
                break;
            default:
                ok = false;
        }
        if (!ok) {
            throw new IllegalArgumentException("协议参数 " + key + " 类型应为 "
                    + expected + "，实际为 " + value.getClass().getSimpleName());
        }
    }
}