// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.adaptive;

/** 学习状态快照：给界面展示"它现在学成什么样了"（不可变，随便传）。 */
public final class AdaptiveState {

    private final int recommended;
    private final int ceiling;
    private final int runs;
    private final int lastTotal;
    private final int lastSucceeded;
    private final int lastConnectFailed;
    private final String lastReason;
    private final DeviceTier tier;

    public AdaptiveState(int recommended, int ceiling, int runs,
                         int lastTotal, int lastSucceeded, int lastConnectFailed,
                         String lastReason, DeviceTier tier) {
        this.recommended = recommended;
        this.ceiling = ceiling;
        this.runs = runs;
        this.lastTotal = lastTotal;
        this.lastSucceeded = lastSucceeded;
        this.lastConnectFailed = lastConnectFailed;
        this.lastReason = lastReason;
        this.tier = tier;
    }

    /** 本模块<b>建议</b>的并发度（非生效值——生效值归 core 的并发容量管） */
    public int getRecommended() {
        return recommended;
    }

    /** 已知上限（0 = 还没撞过墙，未知） */
    public int getCeiling() {
        return ceiling;
    }

    /** 累计学习轮数——"配得越多越准"的可视化依据 */
    public int getRuns() {
        return runs;
    }

    public int getLastTotal() {
        return lastTotal;
    }

    public int getLastSucceeded() {
        return lastSucceeded;
    }

    public int getLastConnectFailed() {
        return lastConnectFailed;
    }

    /** 最近一次调整的原因（人话，可直接显示） */
    public String getLastReason() {
        return lastReason;
    }

    public DeviceTier getTier() {
        return tier;
    }

    /** 一句话摘要，界面直接显示，如 "智能并发 3 台（上限 3） · 已学习 12 次 · 上次 3/3 成功 · 中配" */
    public String summary() {
        StringBuilder sb = new StringBuilder("建议并发 ").append(recommended).append(" 台");
        if (ceiling > 0 && ceiling != recommended) {
            sb.append("（上限 ").append(ceiling).append("）");
        }
        sb.append(" · 已学习 ").append(runs).append(" 次");
        if (lastTotal > 0) {
            sb.append(" · 上次 ").append(lastSucceeded).append("/").append(lastTotal).append(" 成功");
        }
        if (tier != null) {
            sb.append(" · ").append(tier.getLabel());
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return "AdaptiveState{" + summary() + "}";
    }
}
