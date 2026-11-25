// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.sdk.core.scan;

import com.qingzhu.ble.sdk.core.protocol.ProtocolType;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 扫描配置（对标旧架构 ScanSetting）。
 *
 * 默认值：超时 60 秒、不过滤协议、无白名单。
 * 不传 config 的 startScan(listener) 走默认值。
 *
 * 过滤说明：
 * - Builder#addProtocol：只认这些协议的设备（为空 = 不过滤）；
 * - Builder#addStableId：只认这些稳定标识（对标旧架构的 MAC 白名单——
 *   对会换址的设备，按 mac 过滤不可用，必须按 stableId）。
 *
 * <h3>系统扫描参数（平台 API 配置化）</h3>
 * Android 的 ScanSettings 有很多系统级参数（扫描模式/批量上报/匹配模式/PHY...）。
 * 本类把它们全部声明为<b>可空字段</b>，生效优先级：
 * <pre>
 * ScanConfig 显式设置（开发者一键改）
 *   > 未设置 → 平台适配层默认（公司机型适配逻辑，当前和将来都在这层兜底）
 *     > core 兜底
 * </pre>
 * 即：不设置任何参数，行为与从前完全一致（LOW_LATENCY + 机型适配默认）；设置了的字段
 * 才覆盖下层。字段全部走 Builder 一键设置；特殊的场景用
 * {@link Builder#customizePlatformSettings} 直接操作平台原生 Builder（逃生舱）。
 */
public final class ScanConfig {

    public static final long DEFAULT_TIMEOUT_MS = 60_000;
    public static final long MIN_TIMEOUT_MS = 3_000;
    /**
     * 超过该时长没有再收到某设备的广播，判定为"离开范围"——只触发一次 onDeviceLeftRange
     * 语义事件 + 让 {@code QDevice.isInRange(leaveRangeMs)} 返回 false（供 UI 置灰），
     * <b>不删除设备</b>（对齐 nRF Connect 的"置灰不删除"模型）。
     *
     * <p>默认 30 秒：部分设备（TXPGGP 重启/省电态实测 14~20 秒）广播间隔远大于秒级，
     * 若阈值取 5 秒这类小值，设备会在"在范围内/离开"之间反复横跳，UI 闪烁。阈值必须
     * 显著大于目标设备的最长广播间隔；若确有设备 1 分钟才广播一次，调用方应调大该值。
     */
    public static final long DEFAULT_LEAVE_RANGE_MS = 30_000;

    private final long timeoutMs;
    private final Set<ProtocolType> filterProtocols;
    private final Set<String> filterStableIds;
    private final long leaveRangeMs;

    // ---- 系统扫描参数（可空 = 不干预，落到平台适配层默认）----
    private final Integer scanMode;
    private final Long reportDelayMs;
    private final Integer callbackType;
    private final Integer matchMode;
    private final Integer numOfMatches;
    private final Integer phy;
    private final Boolean legacy;
    /** 平台逃生舱：Android 上实参是 android.bluetooth.le.ScanSettings.Builder，可继续调任意系统 API */
    private final java.util.function.Consumer<Object> platformSettingsCustomizer;

    private ScanConfig(Builder b) {
        // timeoutMs <= 0 表示无限扫描（ScanEngine 只在 > 0 时才设超时任务）；
        // > 0 时最小 3 秒，防止误传极小值导致扫描瞬间停止
        this.timeoutMs = b.timeoutMs > 0 ? Math.max(MIN_TIMEOUT_MS, b.timeoutMs) : b.timeoutMs;
        this.filterProtocols = Collections.unmodifiableSet(new HashSet<>(b.filterProtocols));
        this.filterStableIds = Collections.unmodifiableSet(new HashSet<>(b.filterStableIds));
        this.leaveRangeMs = b.leaveRangeMs;
        this.scanMode = b.scanMode;
        this.reportDelayMs = b.reportDelayMs;
        this.callbackType = b.callbackType;
        this.matchMode = b.matchMode;
        this.numOfMatches = b.numOfMatches;
        this.phy = b.phy;
        this.legacy = b.legacy;
        this.platformSettingsCustomizer = b.platformSettingsCustomizer;
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }

    /** 为空表示不过滤协议 */
    public Set<ProtocolType> getFilterProtocols() {
        return filterProtocols;
    }

    /** 为空表示不过滤稳定标识 */
    public Set<String> getFilterStableIds() {
        return filterStableIds;
    }

    /** 判定"离开范围"的静默时长 */
    public long getLeaveRangeMs() {
        return leaveRangeMs;
    }

    // ---- 系统扫描参数 getter（可空 = 未设置）----

    public Integer getScanMode() {
        return scanMode;
    }

    public Long getReportDelayMs() {
        return reportDelayMs;
    }

    public Integer getCallbackType() {
        return callbackType;
    }

    public Integer getMatchMode() {
        return matchMode;
    }

    public Integer getNumOfMatches() {
        return numOfMatches;
    }

    public Integer getPhy() {
        return phy;
    }

    public Boolean getLegacy() {
        return legacy;
    }

    /** 平台逃生舱（Android 上实参为 android.bluetooth.le.ScanSettings.Builder），可为 null */
    public java.util.function.Consumer<Object> getPlatformSettingsCustomizer() {
        return platformSettingsCustomizer;
    }

    /**
     * 合并两份配置：override 里<b>显式设置</b>的字段（非 null）优先，未设置的字段落回 base。
     * 用于"协议插件声明默认扫描参数 + 接入方调用时覆盖"的分层：
     * effective = merge(协议声明, 调用方 config)。
     * 合并范围：8 个系统扫描参数 + 逃生舱（协议能声明的就这些）；
     * timeout/协议过滤/白名单是调用方关注点，不参与合并，以调用方 config 为准。
     */
    public static ScanConfig merge(ScanConfig base, ScanConfig override) {
        if (base == null) return override;
        if (override == null) return base;
        Builder b = new Builder();
        b.scanMode = override.scanMode != null ? override.scanMode : base.scanMode;
        b.reportDelayMs = override.reportDelayMs != null ? override.reportDelayMs : base.reportDelayMs;
        b.callbackType = override.callbackType != null ? override.callbackType : base.callbackType;
        b.matchMode = override.matchMode != null ? override.matchMode : base.matchMode;
        b.numOfMatches = override.numOfMatches != null ? override.numOfMatches : base.numOfMatches;
        b.phy = override.phy != null ? override.phy : base.phy;
        b.legacy = override.legacy != null ? override.legacy : base.legacy;
        b.platformSettingsCustomizer = override.platformSettingsCustomizer != null
                ? override.platformSettingsCustomizer : base.platformSettingsCustomizer;
        b.timeoutMs = override.timeoutMs;
        b.leaveRangeMs = override.leaveRangeMs;
        b.filterProtocols.addAll(override.filterProtocols);
        b.filterStableIds.addAll(override.filterStableIds);
        return b.build();
    }

    /** 该协议是否被过滤掉（不在白名单里） */
    public boolean shouldFilterProtocol(ProtocolType type) {
        return !filterProtocols.isEmpty() && !filterProtocols.contains(type);
    }

    /** 该设备是否被过滤掉（配置了白名单且设备不在里面） */
    public boolean shouldFilterDevice(String stableId) {
        return !filterStableIds.isEmpty() && (stableId == null || !filterStableIds.contains(stableId));
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private long timeoutMs = DEFAULT_TIMEOUT_MS;
        private final Set<ProtocolType> filterProtocols = new HashSet<>();
        private final Set<String> filterStableIds = new HashSet<>();
        private long leaveRangeMs = DEFAULT_LEAVE_RANGE_MS;
        private Integer scanMode;
        private Long reportDelayMs;
        private Integer callbackType;
        private Integer matchMode;
        private Integer numOfMatches;
        private Integer phy;
        private Boolean legacy;
        private java.util.function.Consumer<Object> platformSettingsCustomizer;

        /** 扫描超时（到时自动停止并回调 onScanStopped）：> 0 时最小 3 秒；<= 0 表示无限扫描（不自动停） */
        public Builder setTimeout(long ms) {
            this.timeoutMs = ms;
            return this;
        }

        /** 只认指定协议的设备（可多次调用累加；不调用 = 不过滤） */
        public Builder addProtocol(ProtocolType type) {
            this.filterProtocols.add(type);
            return this;
        }

        /** 只认指定稳定标识的设备（可多次调用累加；不调用 = 不过滤） */
        public Builder addStableId(String stableId) {
            this.filterStableIds.add(stableId);
            return this;
        }

        /** "离开范围"判定的静默时长（默认 30 秒，须大于目标设备的最长广播间隔） */
        public Builder setLeaveRangeMs(long ms) {
            this.leaveRangeMs = ms;
            return this;
        }

        // ---- 系统扫描参数（不设置 = 落到平台适配层默认/公司机型逻辑）----

        /** 扫描占空比：android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_POWER / BALANCED / LOW_LATENCY / OPPORTUNISTIC。
         *  不设置 = 平台适配层默认（当前为 LOW_LATENCY） */
        public Builder setScanMode(int scanMode) {
            this.scanMode = scanMode;
            return this;
        }

        /** 批量上报间隔毫秒（>0 时系统攒批回调 onBatchScanResults，高密度场景省 CPU）；不设置 = 逐条上报 */
        public Builder setReportDelayMs(long ms) {
            this.reportDelayMs = ms;
            return this;
        }

        /** 回调类型：android.bluetooth.le.ScanSettings.CALLBACK_TYPE_FIRST_MATCH / MATCH_LOST（需硬件 filter，API 23+） */
        public Builder setCallbackType(int callbackType) {
            this.callbackType = callbackType;
            return this;
        }

        /** 硬件匹配模式：android.bluetooth.le.ScanSettings.MATCH_MODE_AGGRESSIVE / STICKY（API 23+） */
        public Builder setMatchMode(int matchMode) {
            this.matchMode = matchMode;
            return this;
        }

        /** 硬件 filter 命中数上限：android.bluetooth.le.ScanSettings.MATCH_NUM_FEW_ADVERTISEMENTS 等（API 23+） */
        public Builder setNumOfMatches(int numOfMatches) {
            this.numOfMatches = numOfMatches;
            return this;
        }

        /** PHY：android.bluetooth.le.ScanSettings.PHY_LE_1M / PHY_LE_CODED（长距）/ PHY_LE_ALL_SUPPORTED（API 26+） */
        public Builder setPhy(int phy) {
            this.phy = phy;
            return this;
        }

        /** 是否传统广播模式（false = 扩展广播，数据量更大，API 26+ 且芯片支持） */
        public Builder setLegacy(boolean legacy) {
            this.legacy = legacy;
            return this;
        }

        /**
         * 平台逃生舱：直接操作平台原生设置 Builder，覆盖上面的常用参数之外的一切系统 API。
         * Android 上实参是 {@code android.bluetooth.le.ScanSettings.Builder}，例如：
         * <pre>config.customizePlatformSettings(b -&gt; ((ScanSettings.Builder) b).setScanResultType(...))</pre>
         * 执行时机在常用参数之后（逃生舱可覆盖它们）。
         */
        public Builder customizePlatformSettings(java.util.function.Consumer<Object> customizer) {
            this.platformSettingsCustomizer = customizer;
            return this;
        }

        public ScanConfig build() {
            return new ScanConfig(this);
        }
    }
}
