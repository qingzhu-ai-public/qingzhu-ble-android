// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.sdk.core.protocol;

/**
 * 协议类型标识。
 *
 * 故意不用 enum 枚举具体协议（如 某类设备协议/无厂商数据协议/轮换地址协议），因为 core 是协议无关的内核，
 * 新增协议不允许要求修改 core 里的任何类——如果用 enum，新增协议就必须在这个文件里加一行，
 * 这本身就是"改核心一行代码"，违反了架构设计文档第7节的验收标准。
 *
 * 协议方在自己的插件里用 {@link #of(String)} 定义自己的协议标识，
 * 例如 {@code ProtocolType.of("my-protocol")}，core 只按字符串比较，不关心具体是哪个协议。
 */
public final class ProtocolType {

    /** 广播识别失败、尚未归属任何协议时使用 */
    public static final ProtocolType UNKNOWN = new ProtocolType("unknown");

    private final String id;

    private ProtocolType(String id) {
        this.id = id;
    }

    public static ProtocolType of(String id) {
        if (id == null || id.trim().isEmpty()) {
            throw new IllegalArgumentException("ProtocolType id 不能为空");
        }
        return new ProtocolType(id.trim().toLowerCase());
    }

    public String getId() {
        return id;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ProtocolType)) return false;
        return id.equals(((ProtocolType) o).id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "ProtocolType(" + id + ")";
    }
}
