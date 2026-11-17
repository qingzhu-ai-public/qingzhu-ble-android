// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.adaptive;

/** 硬件体检报告：等级 + 推荐起始并发度 + 明细（界面展示 / 日志排查用）。 */
public final class DeviceReport {

    private final DeviceTier tier;
    private final int startConcurrency;
    private final int score;
    private final String detail;

    public DeviceReport(DeviceTier tier, int startConcurrency, int score, String detail) {
        this.tier = tier;
        this.startConcurrency = startConcurrency;
        this.score = score;
        this.detail = detail;
    }

    public DeviceTier getTier() {
        return tier;
    }

    /** 首次使用时推荐的并发度（= 档位起始值） */
    public int getStartConcurrency() {
        return startConcurrency;
    }

    /** 体检评分（明细参考，不直接决定行为） */
    public int getScore() {
        return score;
    }

    /** 体检明细，如 "蓝牙5.0 · Android12 · 8核 · 内存8G · 评分7" */
    public String getDetail() {
        return detail;
    }

    /** 一句话摘要，界面可直接显示 */
    public String summary() {
        return tier.getLabel() + "（" + detail + "），起始并发 " + startConcurrency + " 台";
    }

    @Override
    public String toString() {
        return "DeviceReport{" + summary() + "}";
    }
}
