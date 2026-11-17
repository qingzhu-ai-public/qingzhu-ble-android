// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.sdk.core.api;

import com.qingzhu.ble.sdk.core.pool.ReconnectPolicy;

import java.util.HashMap;
import java.util.Map;

/**
 * 命令：连接 / 发数据（JSON to JSON）。
 *
 * <pre>
 * QCommand.builder().connect().build();                          // 连接（保持，无参）
 * QCommand.builder().connect(paramsMap).build();                 // 连接（带连接参数，如 deviceKey）
 * QCommand.builder().send("{\"action\":\"ota\"}", bytes).build(); // 发数据
 * QCommand.builder().send(json).keepConnected().build();         // 发完保持
 * </pre>
 *
 * <p>发数据的第一参是 JSON 字符串，{@code action} 字段告诉协议要干什么（echo/ota/file），
 * 协议自己解析参数。加新指令类型不改 API，只改协议解析。
 *
 * <p>连接参数用 {@code Map<String, Object>}（比固定字符串更灵活）：调用方塞任意 key，
 * 协议在连接前钩子（beforeConnect）读取，core 只承载不翻译。例如设备的一机一密认证密钥
 * {@code {"deviceKey": "xxx"}}。
 */
public final class QCommand {

    public enum Kind { CONNECT, SEND }

    private final Kind kind;
    private final String json;          // send 时：JSON 指令
    private final byte[] data;          // send 时：可选数据（固件/单面图片等单字节）
    private final Map<String, Object> attachments; // send 时：可选多附件（如多面图片 dataA..dataE）
    private final boolean keepConnected; // send 时：发完是否保持连接（默认断开）
    private final Map<String, Object> connectParams; // connect 时：连接参数（可空）
    private final ReconnectPolicy reconnectPolicy;   // connect 时：意外断开后的自动重连策略（默认 OFF）

    private QCommand(Kind kind, String json, byte[] data, Map<String, Object> attachments,
                     boolean keepConnected, Map<String, Object> connectParams,
                     ReconnectPolicy reconnectPolicy) {
        this.kind = kind;
        this.json = json;
        this.data = data;
        this.attachments = attachments;
        this.keepConnected = keepConnected;
        this.connectParams = connectParams;
        this.reconnectPolicy = reconnectPolicy;
    }

    public Kind kind() { return kind; }
    public String json() { return json; }
    public byte[] data() { return data; }
    public Map<String, Object> attachments() { return attachments; }
    public boolean keepConnected() { return keepConnected; }
    public Map<String, Object> connectParams() { return connectParams; }
    public ReconnectPolicy reconnectPolicy() { return reconnectPolicy; }

    /** 所有附件字节总和（用于判断是否大数据、是否需要请求高连接优先级） */
    public int totalPayloadSize() {
        int size = data != null ? data.length : 0;
        if (attachments != null) {
            for (Object v : attachments.values()) {
                if (v instanceof byte[]) {
                    size += ((byte[]) v).length;
                }
            }
        }
        return size;
    }

    /** 合并成统一的附件表（{@code "data"} 放单字节，多附件按 key 放），给协议层透传 */
    public Map<String, Object> buildAttachments() {
        if (attachments == null && data == null) {
            return null;
        }
        Map<String, Object> map = new HashMap<>();
        if (attachments != null) {
            map.putAll(attachments);
        }
        if (data != null) {
            map.put("data", data);
        }
        return map;
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private Kind kind;
        private String json;
        private byte[] data;
        private Map<String, Object> attachments;
        private boolean keepConnected = false; // 默认发完断开
        private Map<String, Object> connectParams;
        private ReconnectPolicy reconnectPolicy = ReconnectPolicy.OFF; // 默认不自动重连

        /** 连接：连上并保持（无参数） */
        public Builder connect() {
            return connect(null);
        }

        /** 连接：连上并保持，带连接参数（如 deviceKey） */
        public Builder connect(Map<String, Object> params) {
            this.kind = Kind.CONNECT;
            this.json = null;
            this.data = null;
            this.attachments = null;
            this.connectParams = params;
            return this;
        }

        /** 发数据（纯 JSON 指令） */
        public Builder send(String json) {
            return send(json, (byte[]) null);
        }

        /** 发数据（JSON 指令 + 数据，如固件/文件字节） */
        public Builder send(String json, byte[] data) {
            this.kind = Kind.SEND;
            this.json = json;
            this.data = data;
            this.attachments = null;
            this.connectParams = null;
            return this;
        }

        /** 发数据（JSON 指令 + 多附件，如多面图片 {@code {"dataA": bytesA, "dataB": bytesB, ...}}） */
        public Builder send(String json, Map<String, Object> attachments) {
            this.kind = Kind.SEND;
            this.json = json;
            this.data = null;
            this.attachments = attachments;
            this.connectParams = null;
            return this;
        }

        /** 发数据时附加连接参数（如设备的一机一密认证密钥 {@code {"deviceKey": "xxx"}}）：短连接
         *  连上后认证需要，core 只承载不翻译，协议在 beforeConnect 读取。{@link #connect(Map)} 已内置，
         *  此方法主要用于 {@link #send(String)} 系列需要带连接参数的场景。 */
        public Builder connectParams(Map<String, Object> params) {
            this.connectParams = params;
            return this;
        }

        /** 发完保持连接 */
        public Builder keepConnected() {
            this.keepConnected = true;
            return this;
        }

        /** 发完断开连接（默认） */
        public Builder disconnectAfter() {
            this.keepConnected = false;
            return this;
        }

        /** 连接意外断开后的自动重连策略（默认 {@link ReconnectPolicy#OFF} 不重连；
         *  {@link ReconnectPolicy#forever} 无限重连，适合「设备可能还要开机/进范围」的场景） */
        public Builder reconnect(ReconnectPolicy reconnectPolicy) {
            this.reconnectPolicy = reconnectPolicy;
            return this;
        }

        public QCommand build() {
            if (kind == null) {
                throw new IllegalStateException("命令未指定：connect() 或 send(json)");
            }
            return new QCommand(kind, json, data, attachments, keepConnected, connectParams, reconnectPolicy);
        }
    }
}
