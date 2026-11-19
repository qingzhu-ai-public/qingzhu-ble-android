// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.sdk.core.api;

import com.qingzhu.ble.sdk.core.pool.ReconnectPolicy;

import java.util.HashMap;
import java.util.Map;

/**
 * 命令：连接或发送。
 *
 * <pre>
 * QCommand.builder().connect().build();                          // 连接并保持
 * QCommand.builder().send("{\"action\":\"ota\"}", bytes).build(); // 发数据
 * QCommand.builder().send(json).keepConnected().build();         // 发完保持
 * </pre>
 *
 * <p>连接参数不属于命令，由 {@link QTask#setConnectParams(Map)} 设置。
 */
public final class QCommand {

    public enum Kind { CONNECT, SEND }

    private final Kind kind;
    private final String json;
    private final byte[] data;
    private final Map<String, Object> attachments;
    private final boolean keepConnected;
    private final boolean disconnectAfter;
    private final boolean noAck;
    private final ReconnectPolicy reconnectPolicy;

    private QCommand(Kind kind, String json, byte[] data, Map<String, Object> attachments,
                     boolean keepConnected, boolean disconnectAfter, boolean noAck,
                     ReconnectPolicy reconnectPolicy) {
        this.kind = kind;
        this.json = json;
        this.data = data;
        this.attachments = attachments;
        this.keepConnected = keepConnected;
        this.disconnectAfter = disconnectAfter;
        this.noAck = noAck;
        this.reconnectPolicy = reconnectPolicy;
    }

    public Kind kind() { return kind; }
    public String json() { return json; }
    public byte[] data() { return data; }
    public Map<String, Object> attachments() { return attachments; }
    public boolean keepConnected() { return keepConnected; }
    /** 是否显式要求发完断开：优先于任务上「保持连接」的期望（设备发完必须断开才生效时使用） */
    public boolean disconnectAfter() { return disconnectAfter; }
    /** 是否不要求从机应答（协议是否支持由协议层决定） */
    public boolean noAck() { return noAck; }
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
        private boolean keepConnected;
        private boolean disconnectAfter;
        private boolean noAck;
        private ReconnectPolicy reconnectPolicy = ReconnectPolicy.OFF;

        /** 连接并保持 */
        public Builder connect() {
            this.kind = Kind.CONNECT;
            this.json = null;
            this.data = null;
            this.attachments = null;
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
            return this;
        }

        /** 发数据（JSON 指令 + 多附件，如 {@code {"dataA": bytesA, "dataB": bytesB}}） */
        public Builder send(String json, Map<String, Object> attachments) {
            this.kind = Kind.SEND;
            this.json = json;
            this.data = null;
            this.attachments = attachments;
            return this;
        }

        /** 发完保持连接 */
        public Builder keepConnected() {
            this.keepConnected = true;
            return this;
        }

        /** 发完断开连接（默认） */
        public Builder disconnectAfter() {
            this.disconnectAfter = true;
            return this;
        }

        /** 不要求从机应答（仅部分协议支持） */
        public Builder noAck() {
            this.noAck = true;
            return this;
        }

        /** 连接意外断开后的自动重连策略（默认 {@link ReconnectPolicy#OFF}） */
        public Builder reconnect(ReconnectPolicy reconnectPolicy) {
            this.reconnectPolicy = reconnectPolicy;
            return this;
        }

        public QCommand build() {
            if (kind == null) {
                throw new IllegalStateException("命令未指定：connect() 或 send(json)");
            }
            if (keepConnected && disconnectAfter) {
                throw new IllegalStateException("keepConnected() 与 disconnectAfter() 互斥，只能选其一");
            }
            return new QCommand(kind, json, data, attachments, keepConnected, disconnectAfter,
                    noAck, reconnectPolicy);
        }
    }
}
