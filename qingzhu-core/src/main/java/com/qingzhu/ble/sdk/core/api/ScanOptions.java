// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.sdk.core.api;

import com.qingzhu.ble.sdk.core.device.QDevice;
import com.qingzhu.ble.sdk.core.protocol.ProtocolType;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * UI 扫描设置：扫描时长和设备过滤条件。
 *
 * <p>过滤只作用于 UI 回调，不影响任务池的寻人/重寻址；任务池仍然能看到完整广播。
 * 不设置任何条件时，等价于原来的 {@code startScan(listener)}。
 */
public final class ScanOptions {

    public static final long UNLIMITED = 0L;

    private final long timeoutMs;
    private final Integer minRssi;
    private final String namePrefix;
    private final Set<ProtocolType> protocols;
    private final Set<String> stableIds;

    private ScanOptions(Builder builder) {
        long timeout = builder.timeoutMs;
        this.timeoutMs = timeout > 0 ? Math.max(1_000L, timeout) : UNLIMITED;
        this.minRssi = builder.minRssi;
        String prefix = builder.namePrefix;
        this.namePrefix = prefix == null || prefix.trim().isEmpty()
                ? null : prefix.trim();
        this.protocols = Collections.unmodifiableSet(new HashSet<>(builder.protocols));
        this.stableIds = Collections.unmodifiableSet(new HashSet<>(builder.stableIds));
    }

    public static ScanOptions defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 扫描时长，0 表示不自动停止 */
    public long getTimeoutMs() {
        return timeoutMs;
    }

    /** null 表示不按 RSSI 过滤 */
    public Integer getMinRssi() {
        return minRssi;
    }

    /** null 表示不按名称过滤 */
    public String getNamePrefix() {
        return namePrefix;
    }

    public Set<ProtocolType> getProtocols() {
        return protocols;
    }

    public Set<String> getStableIds() {
        return stableIds;
    }

    public boolean hasFilters() {
        return minRssi != null
                || namePrefix != null
                || !protocols.isEmpty()
                || !stableIds.isEmpty();
    }

    /** 设备是否通过 UI 过滤条件。 */
    public boolean accepts(QDevice device) {
        if (device == null) {
            return false;
        }
        if (minRssi != null && device.getRssi() < minRssi) {
            return false;
        }
        if (namePrefix != null) {
            String name = device.getName();
            if (name == null
                    || !name.toLowerCase(Locale.US)
                    .startsWith(namePrefix.toLowerCase(Locale.US))) {
                return false;
            }
        }
        if (!protocols.isEmpty() && !protocols.contains(device.getProtocolType())) {
            return false;
        }
        return stableIds.isEmpty() || stableIds.contains(device.getStableId());
    }

    public static final class Builder {
        private long timeoutMs = UNLIMITED;
        private Integer minRssi;
        private String namePrefix;
        private final Set<ProtocolType> protocols = new HashSet<>();
        private final Set<String> stableIds = new HashSet<>();

        /** 扫描时长；> 0 时到点自动停止，0 表示持续扫描 */
        public Builder setTimeoutMs(long timeoutMs) {
            this.timeoutMs = timeoutMs;
            return this;
        }

        /** 最低信号强度，例如 -80；不设置表示不过滤 */
        public Builder setMinRssi(int minRssi) {
            this.minRssi = minRssi;
            return this;
        }

        /** 设备名前缀，大小写不敏感；不设置表示不过滤 */
        public Builder setNamePrefix(String namePrefix) {
            this.namePrefix = namePrefix;
            return this;
        }

        /** 只保留指定协议；不设置表示不过滤 */
        public Builder addProtocol(ProtocolType protocolType) {
            if (protocolType != null) {
                protocols.add(protocolType);
            }
            return this;
        }

        /** 只保留指定协议 ID；不设置表示不过滤 */
        public Builder addProtocol(String protocolId) {
            if (protocolId != null && !protocolId.trim().isEmpty()) {
                protocols.add(ProtocolType.of(protocolId.trim()));
            }
            return this;
        }

        /** 只保留指定稳定标识；不是 BLE MAC，轮换地址设备请用 stableId */
        public Builder addStableId(String stableId) {
            if (stableId != null && !stableId.trim().isEmpty()) {
                stableIds.add(stableId.trim());
            }
            return this;
        }

        public ScanOptions build() {
            return new ScanOptions(this);
        }
    }
}
