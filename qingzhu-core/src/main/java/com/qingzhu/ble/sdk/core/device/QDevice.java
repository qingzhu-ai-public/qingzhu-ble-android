// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.sdk.core.device;

import com.qingzhu.ble.sdk.core.protocol.ProtocolType;
import com.qingzhu.ble.sdk.core.session.Session;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 纯数据模型：一次扫描识别到的设备。
 *
 * 只放"人人都需要"的公共字段（mac/name/rssi/protocolType），
 * 协议私有的字段（固件版本、电量、productId 等）统一走 metadata，
 * 由协议自己的 Parser 写入 —— core 不需要为了适配某个协议的私有字段而修改这个类。
 */
public final class QDevice {

    private final String mac;
    private volatile String name;
    private volatile int rssi;
    private final ProtocolType protocolType;
    /** 最近一次被扫到的时间戳（System.currentTimeMillis，扫描引擎填充）。用于"在范围内"判定 */
    private volatile long lastSeenAt;
    /**
     * 稳定标识：跨广播轮换唯一标识"同一台物理设备"，由 <b>SDK 统一分配</b>——
     * 身份证风格的 16 位大写字母+数字（身份源 = 协议 identityKey() 声明的 metadata 字段，
     * 未声明 = bleMac；哈希输入含协议 ID 保证跨协议不重复）。扫描引擎填充。
     * 为 null 时等价于 mac（兜底）。
     */
    private volatile String stableId;
    private final ConcurrentHashMap<String, Object> metadata = new ConcurrentHashMap<>();

    public QDevice(String mac, String name, int rssi, ProtocolType protocolType) {
        this.mac = mac;
        this.name = name;
        this.rssi = rssi;
        this.protocolType = protocolType;
    }

    public String getMac() {
        return mac;
    }

    /** 稳定标识：同一台物理设备跨地址轮换保持不变；未设置时退回 mac。UI 分组/去重请用这个。 */
    public String getStableId() {
        return stableId != null ? stableId : mac;
    }

    public void setStableId(String stableId) {
        this.stableId = stableId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getRssi() {
        return rssi;
    }

    public void setRssi(int rssi) {
        this.rssi = rssi;
    }

    /** 最近一次被扫描到的时间戳（墙钟毫秒）；从未被扫到过为 0 */
    public long getLastSeenAt() {
        return lastSeenAt;
    }

    public void setLastSeenAt(long lastSeenAt) {
        this.lastSeenAt = lastSeenAt;
    }

    /**
     * 是否"在范围内"：距最近一次被扫到不超过 {@code thresholdMs}。
     * 只做"新鲜度"判定、不删除设备（对齐 nRF Connect 的 isInRange / 置灰模型）——
     * 设备到底算不算离开、要不要从列表移除，由调用方据此自行决定。
     */
    public boolean isInRange(long thresholdMs) {
        return lastSeenAt > 0 && System.currentTimeMillis() - lastSeenAt <= thresholdMs;
    }

    public ProtocolType getProtocolType() {
        return protocolType;
    }

    public void putMetadata(String key, Object value) {
        metadata.put(key, value);
    }

    @SuppressWarnings("unchecked")
    public <T> T getMetadata(String key) {
        return (T) metadata.get(key);
    }

    @SuppressWarnings("unchecked")
    public <T> T getMetadata(String key, T defaultValue) {
        Object v = metadata.get(key);
        return v == null ? defaultValue : (T) v;
    }

    /**
     * 全部 metadata 的只读快照（协议 Recognizer 解析出的字段，如 deviceMac/protocolVersion/
     * frameType/battery 等）。供接入方 UI 通用展示或业务遍历——不需要提前知道协议定义了哪些字段。
     */
    public java.util.Map<String, Object> getAllMetadata() {
        return java.util.Collections.unmodifiableMap(metadata);
    }

    /**
     * 判断这台设备是否「等于」给定的身份串（协议无关）。
     *
     * <p>占位任务只知道一个后端给的 mac（可能是 deviceMac 稳定身份、也可能是 bleMac），
     * 扫描结果拿来对比时，core 不该假设身份是哪个字段，所以依次比对：
     * bleMac → stableId → 各 metadata 值。任一中即认为匹配。
     */
    public boolean matchesIdentity(String identity) {
        if (identity == null || identity.isEmpty()) {
            return false;
        }
        // 归一化比对：后端可能给「无冒号小写」(02f51995e8d8)，扫描解析的是「带冒号大写」(02:F5:19:95:E8:D8)，
        // 去冒号/连字符 + 转大写后再比，避免格式差异导致寻人匹配失败
        String target = normalizeIdentity(identity);
        if (target.isEmpty()) {
            return false;
        }
        if (target.equals(normalizeIdentity(mac))) {
            return true;
        }
        if (target.equals(normalizeIdentity(getStableId()))) {
            return true;
        }
        for (Object v : metadata.values()) {
            if (v != null && target.equals(normalizeIdentity(String.valueOf(v)))) {
                return true;
            }
        }
        return false;
    }

    /** 身份串归一化：去冒号/连字符、去首尾空格、转大写 */
    private static String normalizeIdentity(String s) {
        if (s == null) {
            return "";
        }
        return s.replace(":", "").replace("-", "").trim().toUpperCase(java.util.Locale.US);
    }

    /**
     * 把扫描阶段解析出的 metadata（如 轮换地址协议 的 deviceMac）逐条复制进 Session。
     *
     * 这是连接建立时的必要一步——否则连接阶段（如重寻址）读不到扫描阶段
     * 解析出的字段，只能退化用别的值兜底，容易导致查缓存查不到、误判失败。
     * key 直接沿用 metadata 的 key（协议自己在 Parser 里定义的常量），不做重命名。
     *
     * @return 实际复制的条目数，方便调用方在日志里确认这一步真的生效了
     */
    public int copyMetadataInto(Session session) {
        int count = 0;
        for (Map.Entry<String, Object> entry : metadata.entrySet()) {
            session.put(entry.getKey(), entry.getValue());
            count++;
        }
        return count;
    }

    @Override
    public String toString() {
        return "QDevice(mac=" + mac + ", name=" + name + ", rssi=" + rssi + ", protocol=" + protocolType + ")";
    }
}
