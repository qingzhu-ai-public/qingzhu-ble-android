// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.adaptive;

/**
 * 硬件体检分档：决定"第一次"从几台开始并发（之后由 {@link ConcurrencyLearner} 用实测修正）。
 *
 * <p>分档只用了可公开查询、无需额外权限的信号，追求"简单可靠"而非绝对精确——
 * 起跑点偏一点没关系，学习会把它掰回来。
 */
public enum DeviceTier {

    /** 高配：支持蓝牙 5.0 特性（2M PHY / 扩展广播等新芯片能力） */
    HIGH("高配", AdaptiveConfig.START_HIGH),

    /** 中配：蓝牙 4.2 / 系统 Android 8.0 以上 */
    MID("中配", AdaptiveConfig.START_MID),

    /** 低配：老机器 */
    LOW("低配", AdaptiveConfig.START_LOW);

    private final String label;
    private final int startConcurrency;

    DeviceTier(String label, int startConcurrency) {
        this.label = label;
        this.startConcurrency = startConcurrency;
    }

    /** 中文标签，界面展示用 */
    public String getLabel() {
        return label;
    }

    /** 该档位的起始并发度 */
    public int getStartConcurrency() {
        return startConcurrency;
    }

    /** 按名称还原（持久化后读取用），无法匹配时返回兜底值 */
    static DeviceTier fromName(String name, DeviceTier fallback) {
        if (name != null) {
            for (DeviceTier tier : values()) {
                if (tier.name().equals(name)) {
                    return tier;
                }
            }
        }
        return fallback;
    }
}
