// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.sdk.core.settings;

import com.qingzhu.ble.sdk.core.protocol.ProtocolParam;
import com.qingzhu.ble.sdk.core.protocol.ProtocolParams;
import com.qingzhu.ble.sdk.core.session.Session;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 全局设置中心：SDK 所有可配置项的<b>唯一入口</b>。
 *
 * <h3>为什么要有它（替代原来"声明与赋值分家"的做法）</h3>
 * 之前配置一件事要跨好几处：协议 {@code configurableParams()} 声明 key → 门面
 * {@code applyHostDefaults()} 设值 → connect 传参 → {@code copyInto(session)} →
 * 协议再读 {@code session.get("param.xxx")}。开发者看协议代码根本不知道"我要配的话在哪配"，
 * 而且同一件事还有两个通道并存（有校验的 {@code ProtocolParams} 与无校验的
 * {@code ConnectParams.extras}）。
 *
 * <p>统一成一个入口后：
 * <pre>
 *   BleSettings.get()
 *           .writeType(1)                            // 无响应写，传大包更快（core 内置项用强类型 API）
 *           .highConnectionPriority(true)
 *           .set("protocol.option", 15);    // 协议专属项仍用 set(key, value)
 *   // 不配置 = 用声明里的默认值，一行都不用写
 * </pre>
 *
 * <h3>可配项从哪来</h3>
 * <ul>
 *   <li><b>core 内置</b>：连接 / 传输这类跨协议通用的开关，见 {@link #CORE_SPECS}；</li>
 *   <li><b>协议声明</b>：插件在 {@code configurableParams()} 里声明，协议注册时自动纳入
 *       （{@link #registerDefaults}），协议作者只声明、不赋值——定制权仍在公司 / 接入方手里。</li>
 * </ul>
 * 两类都带 key / 类型 / 默认值 / 中文说明，{@link #listAll()} 可直接生成开发文档或配置界面：
 * <b>开发者不需要读源码猜"能配什么、默认是什么"</b>。
 *
 * <h3>生效链路</h3>
 * <ul>
 *   <li>core 各处<b>主动读</b>本类（如 {@code AndroidGattClient} 读 {@code ble.writeType}），
 *       设置改完立即全局生效；</li>
 *   <li>协议用 {@link #getInt(Session, String)} 等静态方法读，会<b>自动优先取连接级覆盖</b>
 *       （连接时通过 {@code ProtocolParams} 复制进 Session 的值），没有覆盖就用全局设置。</li>
 * </ul>
 *
 * <h3>错误处理</h3>
 * {@link #set} 不存在的 key 或类型不匹配<b>直接抛异常并列出可用 key</b>——
 * 配置错误必须在开发期暴露，而不是静默失效让线上行为难以解释。
 */
public final class BleSettings {

    /**
     * core 内置的可配置项（跨协议通用）。数值用系统常量的字面量，避免 core 依赖 android.bluetooth。
     *
     * <p><b>⚠️ 必须声明在 {@link #INSTANCE} 之前</b>：静态字段按声明顺序初始化，而构造函数要读它。
     * 若放在 INSTANCE 之后，构造函数会读到 null，导致 core 内置设置<b>全部没注册</b>
     *（之后读 {@code ble.*} 会直接抛"未声明的配置项"）。
     */
    private static final List<ProtocolParam> CORE_SPECS = Collections.unmodifiableList(Arrays.asList(
            ProtocolParam.ofInt("ble.writeType", 1,
                    "BLE 写类型：1=NO_RESPONSE（无响应，连续写不等确认、传大包更快，推荐——真实项目默认，"
                            + "可靠性靠应用层分片校验/回执兜底）/ 2=DEFAULT（有响应，最稳）/ 4=SIGNED（签名写，需配对加密）"),
            ProtocolParam.ofInt("ble.bulkWriteWindow", 2,
                    "大块下发流水线并发窗口：同时最多几个无响应写在途。越大发送越激进、吞吐越高，"
                            + "但过大容易把 BLE 发送缓冲打满反而变慢（默认 2 是吞吐与稳定性的平衡点）"),
            ProtocolParam.ofLong("ble.bufferRetryDelayMs", 5L,
                    "发送缓冲瞬时打满时的重试延迟（毫秒）：缓冲满说明 BLE 栈来不及消化，稍等再发。"
                            + "设 0 = 立即重试（不做间隔，激进，可能忙等）；旧版「无间隔连续发」即此值设 0"),
            ProtocolParam.ofBool("ble.highConnectionPriority", true,
                    "连接成功后是否请求高连接优先级（CONNECTION_PRIORITY_HIGH）——提升刷图/传输吞吐"),
            ProtocolParam.ofLong("ble.wakeLockThresholdBytes", 1024L,
                    "大块传输保活阈值：一次发送达到该长度就持有 WakeLock 防 CPU 休眠丢包；"
                            + "设 Long.MAX_VALUE 表示从不保活（需 App 声明 WAKE_LOCK 权限）"),
            ProtocolParam.ofBool("heartbeat.enabled", true,
                    "长连接心跳探活总开关：关闭后即使协议声明了心跳指令也不做心跳（协议没声明则天然不做）"),
            ProtocolParam.ofLong("heartbeat.idleIntervalMs", 10000L,
                    "心跳空闲阈值：距离上次成功收发超过该时长就开始发心跳探测"),
            ProtocolParam.ofLong("heartbeat.timeoutMs", 3000L,
                    "单条心跳的应答超时（毫秒）：超时未回复计一次 miss"),
            ProtocolParam.ofInt("heartbeat.maxMiss", 2,
                    "连续多少次心跳无应答判离线（判离线后主动断开并触发自动重连）"),
            ProtocolParam.ofInt("ble.scanTimeoutSec", 0,
                    "扫描超时（秒）：>0 定时扫描，到时自动停止并回调「扫描已停止」；0 = 持续扫描不停（默认）"),
            ProtocolParam.ofBool("ble.debugLog", true,
                    "输出 debug/warning 日志：关闭后只输出错误日志（e），减少扫描/连接高频日志的 logcat 开销")));

    /** 全局唯一实例（声明在 CORE_SPECS 之后——构造函数依赖它已完成初始化） */
    private static final BleSettings INSTANCE = new BleSettings();

    public static BleSettings get() {
        return INSTANCE;
    }

    /** key -> 声明（core 内置 + 各协议注册时声明） */
    private final Map<String, ProtocolParam> specs = new ConcurrentHashMap<>();
    /** key -> 显式设置的值；没有条目 = 用声明里的默认值 */
    private final Map<String, Object> values = new ConcurrentHashMap<>();

    private BleSettings() {
        registerDefaults(CORE_SPECS);
    }

    // ==================== 配置（开发者用） ====================

    /**
     * 设置一个配置项，返回 this 支持链式调用。
     *
     * @throws IllegalArgumentException key 未声明（内置项或协议声明里都没有），或值类型与声明不符
     */
    public BleSettings set(String key, Object value) {
        ProtocolParam spec = specs.get(key);
        if (spec == null) {
            throw new IllegalArgumentException("未声明的配置项: " + key
                    + "（可用: " + specs.keySet() + "）");
        }
        checkType(key, spec.getType(), value);
        values.put(key, value);
        return this;
    }

    // ==================== 强类型便捷 API（core 内置项，开发者首选） ====================
    //
    // 上面 set(key, value) 是「协议专属项」的通用通道（key 由协议动态声明，无法预生成强类型方法）。
    // 而 core 内置项是固定的，这里为每一项提供强类型 setter/getter：
    // 有 IDE 代码提示、编译期类型检查，拼错 key 直接编译不过——比字符串写法对开发者友好得多。
    // 内部仍复用 set/getInt 的声明校验与默认值逻辑，行为与字符串写法完全一致。

    /** 写类型：1=NO_RESPONSE（无响应，推荐）/ 2=DEFAULT（有响应）/ 4=SIGNED（签名）。等价于 set("ble.writeType", v) */
    public BleSettings writeType(int v) { set("ble.writeType", v); return this; }
    public int writeType() { return getInt("ble.writeType"); }

    /** 连接成功后是否请求高连接优先级（缩短连接间隔、提升吞吐）。等价于 set("ble.highConnectionPriority", v) */
    public BleSettings highConnectionPriority(boolean v) { set("ble.highConnectionPriority", v); return this; }
    public boolean highConnectionPriority() { return getBoolean("ble.highConnectionPriority"); }

    /** 大块传输保活阈值（字节）：一次发送达到该长度就持有 WakeLock 防休眠丢包。等价于 set("ble.wakeLockThresholdBytes", v) */
    public BleSettings wakeLockThresholdBytes(long v) { set("ble.wakeLockThresholdBytes", v); return this; }
    public long wakeLockThresholdBytes() { return getLong("ble.wakeLockThresholdBytes"); }

    /** 长连接心跳探活总开关。等价于 set("heartbeat.enabled", v) */
    public BleSettings heartbeatEnabled(boolean v) { set("heartbeat.enabled", v); return this; }
    public boolean heartbeatEnabled() { return getBoolean("heartbeat.enabled"); }

    /** 心跳空闲阈值（毫秒）：距离上次成功收发超过该时长开始发心跳探测。等价于 set("heartbeat.idleIntervalMs", v) */
    public BleSettings heartbeatIdleIntervalMs(long v) { set("heartbeat.idleIntervalMs", v); return this; }
    public long heartbeatIdleIntervalMs() { return getLong("heartbeat.idleIntervalMs"); }

    /** 单条心跳应答超时（毫秒）。等价于 set("heartbeat.timeoutMs", v) */
    public BleSettings heartbeatTimeoutMs(long v) { set("heartbeat.timeoutMs", v); return this; }
    public long heartbeatTimeoutMs() { return getLong("heartbeat.timeoutMs"); }

    /** 连续多少次心跳无应答判离线。等价于 set("heartbeat.maxMiss", v) */
    public BleSettings heartbeatMaxMiss(int v) { set("heartbeat.maxMiss", v); return this; }
    public int heartbeatMaxMiss() { return getInt("heartbeat.maxMiss"); }

    /** 恢复某一项为声明里的默认值 */
    public BleSettings reset(String key) {
        values.remove(key);
        return this;
    }

    /** 全部恢复默认 */
    public BleSettings resetAll() {
        values.clear();
        return this;
    }

    /** 该项是否被显式设置过（false = 正在用默认值） */
    public boolean isSet(String key) {
        return values.containsKey(key);
    }

    /**
     * 全部可配项（声明 + 当前值）——<b>开发文档 / 配置界面直接用这个渲染</b>。
     */
    public List<SettingItem> listAll() {
        List<SettingItem> list = new ArrayList<>(specs.size());
        for (ProtocolParam spec : specs.values()) {
            list.add(new SettingItem(spec, effective(spec.getKey())));
        }
        return list;
    }

    /** 当前生效值快照（含未显式设置项的默认值），key -> value */
    public Map<String, Object> snapshot() {
        Map<String, Object> map = new LinkedHashMap<>();
        for (String key : specs.keySet()) {
            map.put(key, effective(key));
        }
        return Collections.unmodifiableMap(map);
    }

    /** 全部已声明的 key */
    public java.util.Set<String> keys() {
        return Collections.unmodifiableSet(specs.keySet());
    }

    // ==================== 声明纳入（协议注册时由 core 调用） ====================

    /**
     * 纳入一批声明（协议注册时调用）。<b>不覆盖</b>已声明的同名 key，也不动已设置的值——
     * 所以"先 settings().set(...) 再注册协议"的顺序同样正确。
     */
    public void registerDefaults(List<ProtocolParam> params) {
        if (params == null) {
            return;
        }
        for (ProtocolParam p : params) {
            specs.putIfAbsent(p.getKey(), p);
        }
    }

    // ==================== 读取 ====================

    /** 当前生效值（未被 set 过则为声明默认值） */
    public Object get(String key) {
        return effective(key);
    }

    public int getInt(String key) {
        return toInt(key, effective(key));
    }

    public long getLong(String key) {
        return toLong(key, effective(key));
    }

    public boolean getBoolean(String key) {
        Object v = effective(key);
        return v instanceof Boolean ? (Boolean) v : false;
    }

    public String getString(String key) {
        Object v = effective(key);
        return v == null ? null : String.valueOf(v);
    }

    // ==================== 协议读取（自动优先「连接级覆盖」） ====================

    /**
     * 协议读取配置：<b>连接级覆盖优先，其次全局设置</b>。
     * 连接级覆盖由 {@code ProtocolParams.copyInto(session)} 写入（key 带 {@code param.} 前缀）。
     */
    public static int getInt(Session session, String key) {
        return get().toInt(key, resolve(session, key));
    }

    public static long getLong(Session session, String key) {
        return get().toLong(key, resolve(session, key));
    }

    public static boolean getBoolean(Session session, String key) {
        Object v = resolve(session, key);
        return v instanceof Boolean ? (Boolean) v : false;
    }

    public static String getString(Session session, String key) {
        Object v = resolve(session, key);
        return v == null ? null : String.valueOf(v);
    }

    private static Object resolve(Session session, String key) {
        assertDeclared(key);
        if (session != null) {
            Object override = session.get(ProtocolParams.SESSION_PREFIX + key);
            if (override != null) {
                return override;
            }
        }
        return get().effective(key);
    }

    // ==================== 私有 ====================

    /** 取生效值；key 未声明直接抛（开发期暴露拼写错误） */
    private Object effective(String key) {
        assertDeclared(key);
        Object v = values.get(key);
        return v != null ? v : specs.get(key).getDefaultValue();
    }

    private static void assertDeclared(String key) {
        if (!get().specs.containsKey(key)) {
            throw new IllegalArgumentException("未声明的配置项: " + key
                    + "（可用: " + get().specs.keySet() + "）");
        }
    }

    private int toInt(String key, Object v) {
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        throw new IllegalArgumentException("配置项 " + key + " 不是数字类型，实际: "
                + (v == null ? "null" : v.getClass().getSimpleName()));
    }

    private long toLong(String key, Object v) {
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        throw new IllegalArgumentException("配置项 " + key + " 不是数字类型，实际: "
                + (v == null ? "null" : v.getClass().getSimpleName()));
    }

    private void checkType(String key, ProtocolParam.Type expected, Object value) {
        if (value == null) {
            throw new IllegalArgumentException("配置项 " + key + " 的值不能为 null");
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
            throw new IllegalArgumentException("配置项 " + key + " 类型应为 " + expected
                    + "，实际为 " + value.getClass().getSimpleName()
                    + "（提示：Long 项请写 3000L 而不是 3000）");
        }
    }

    /**
     * 一条可配项的完整信息（声明 + 当前值）。开发文档直接遍历打印即可。
     */
    public static final class SettingItem {
        private final ProtocolParam spec;
        private final Object currentValue;

        SettingItem(ProtocolParam spec, Object currentValue) {
            this.spec = spec;
            this.currentValue = currentValue;
        }

        public String getKey() {
            return spec.getKey();
        }

        public ProtocolParam.Type getType() {
            return spec.getType();
        }

        public Object getDefaultValue() {
            return spec.getDefaultValue();
        }

        public Object getCurrentValue() {
            return currentValue;
        }

        public String getDescription() {
            return spec.getDescription();
        }

        /** 是否被改过（当前值 ≠ 默认值） */
        public boolean isModified() {
            return currentValue != null && !currentValue.equals(spec.getDefaultValue());
        }

        @Override
        public String toString() {
            return getKey() + " (" + getType() + ") = " + getCurrentValue()
                    + (isModified() ? "  [默认 " + getDefaultValue() + "]" : "  [默认值]")
                    + "  " + getDescription();
        }
    }
}
