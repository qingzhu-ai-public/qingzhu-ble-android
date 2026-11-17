// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.adaptive;

import android.content.Context;
import android.util.Log;

/**
 * core 内置连接自适应的统一入口。
 *
 * <p><b>本包只产出"建议"，不持有"生效值"。</b> 生效的并发数归 core 的
 * {@code ConnectionPool} 管（{@code QingzhuBle.getConcurrencyCapacity()}）——
 * 模块算得再准，也要门面把它写进 core 才算数；若 core 被 SDK 策略强制锁定，建议就不生效。
 * 这条边界是刻意的：让算法模块保持零依赖、可单测、可替换，而"到底用几台"由引擎说了算。
 *
 * <p>典型用法（上层门面里）：
 * <pre>
 *   // 启动时调一次：硬件体检 + 恢复上次学到的经验
 *   AdaptiveConcurrency.get().init(context);
 *   QingzhuBle.getInstance().setConcurrencyCapacity(AdaptiveConcurrency.get().recommend());
 *
 *   // 每轮批量结束后：喂统计 → 学到新建议 → 再写回 core
 *   AdaptiveConcurrency.get().recordBatch(BatchOutcome.builder()
 *           .total(5).succeeded(4).connectFailed(1).build());
 *   QingzhuBle.getInstance().setConcurrencyCapacity(AdaptiveConcurrency.get().recommend());
 * </pre>
 *
 * <p>内部做三件事：硬件体检定起点 → 用实测成功率学习 → 结果持久化（下次直接用）。
 * 所有状态读写都加锁，可在任意线程调用。
 */
public final class AdaptiveConcurrency {

    private static final String TAG = "AdaptiveConcurrency";

    private static final AdaptiveConcurrency INSTANCE = new AdaptiveConcurrency();

    public static AdaptiveConcurrency get() {
        return INSTANCE;
    }

    private AdaptiveStore store;
    private DeviceReport deviceReport;
    private DeviceTier tier = DeviceTier.MID;
    private int concurrency = AdaptiveConfig.START_MID;
    private int ceiling = 0;
    private int runs = 0;
    private int lastTotal = 0;
    private int lastSucceeded = 0;
    private int lastConnectFailed = 0;
    private String lastReason = "";
    private boolean initialized = false;

    private AdaptiveConcurrency() {
    }

    /**
     * 幂等初始化：硬件体检 + 恢复上次学到的结果（首次使用则以体检结果起步）。
     * 必须在 {@link #recommend()} 之前调用；重复调用无副作用。
     */
    public synchronized void init(Context context) {
        if (initialized) {
            return;
        }
        if (context == null) {
            Log.w(TAG, "init 收到 null context，使用默认并发度 " + concurrency + " 台");
            initialized = true;
            return;
        }
        Context app = context.getApplicationContext();
        store = new AdaptiveStore(app);
        deviceReport = DeviceInspector.inspect(app);
        tier = deviceReport.getTier();

        if (store.isInitialized()) {
            // 有历史经验：直接读上次学到哪儿
            tier = DeviceTier.fromName(store.tierName(), deviceReport.getTier());
            concurrency = clampConcurrency(store.concurrency(deviceReport.getStartConcurrency()));
            ceiling = store.ceiling();
            runs = store.runs();
            lastTotal = store.lastTotal();
            lastSucceeded = store.lastSucceeded();
            lastConnectFailed = store.lastConnectFailed();
            lastReason = store.lastReason();
            Log.i(TAG, "恢复学习结果 → " + snapshot().summary());
        } else {
            // 首次使用：拿硬件体检结果当起跑点
            concurrency = deviceReport.getStartConcurrency();
            ceiling = 0;
            runs = 0;
            lastReason = "首次使用，按硬件体检结果起步";
            persist();
            Log.i(TAG, "首次体检 → " + deviceReport.summary());
        }
        initialized = true;
    }

    public synchronized boolean isInitialized() {
        return initialized;
    }

    /**
     * 本模块产出的<b>建议</b>并发度——注意只是"建议"，不是生效值。
     *
     * <p>真正生效的值归 core 的 {@code ConnectionPool} 管（读 {@code QingzhuBle.getConcurrencyCapacity()}）：
     * 门面拿到本建议后调 {@code setConcurrencyCapacity} 写进去；若被 SDK 强制锁定，建议就不生效。
     * 这样"算法随便建议、池子最终说了算"，两头不会各说各话。
     */
    public synchronized int recommend() {
        return clampConcurrency(concurrency);
    }

    /** 学习状态快照（界面展示用） */
    public synchronized AdaptiveState snapshot() {
        return new AdaptiveState(clampConcurrency(concurrency), ceiling, runs,
                lastTotal, lastSucceeded, lastConnectFailed, lastReason, tier);
    }

    /** 一句话摘要，界面可直接显示 */
    public synchronized String getSummary() {
        return snapshot().summary();
    }

    /** 硬件体检报告原文（排查用，可能为 null 表示未初始化） */
    public synchronized DeviceReport getDeviceReport() {
        return deviceReport;
    }

    /** 硬件体检摘要，界面可直接显示 */
    public synchronized String getDeviceSummary() {
        return deviceReport != null ? deviceReport.summary() : "（未体检）";
    }

    /**
     * 记录一轮批量的结果并学习（一轮结束后调用一次）。
     * 中途取消的轮次也要调用（{@code cancelled=true}），否则那一轮会被当成"全失败"。
     */
    public synchronized void recordBatch(BatchOutcome outcome) {
        recordBatch(outcome, concurrency);
    }

    /**
     * 记录一轮批量的结果并学习，显式指定本轮<b>实际生效</b>的并发度作为学习基准。
     * 手动指定并发时跑的批次走这里：成功/失败发生在什么并发度下，就从什么并发度推演，
     * 避免把"手动串行全成功"错误归因到建议值上。实际值高于已知上限时按上限截断，
     * 不让手动试验突破学习天花板。
     */
    public synchronized void recordBatch(BatchOutcome outcome, int effectiveConcurrency) {
        if (outcome == null) {
            return;
        }
        int base = clampConcurrency(effectiveConcurrency);
        if (ceiling > 0 && base > ceiling) {
            base = ceiling;
        }
        ConcurrencyLearner.Result result = ConcurrencyLearner.learn(base, ceiling, outcome);
        concurrency = result.concurrency;
        ceiling = result.ceiling;
        lastTotal = outcome.getTotal();
        lastSucceeded = outcome.getSucceeded();
        lastConnectFailed = outcome.getConnectFailed();
        lastReason = result.reason;
        if (!outcome.isCancelled()) {
            runs++;
        }
        persist();
        Log.i(TAG, "第 " + runs + " 轮学习：" + result.reason
                + "（并发度 " + concurrency + " 台，上限 " + ceiling + "）");
    }

    /**
     * 人工校准基准值：把它作为新的学习起点，并重新开放上限探索。
     * 用于"明确知道这台机器能连更多/更少"的场景。
     * 注意这只是改建议——门面还要把它写进 core 的并发容量才真正生效。
     */
    public synchronized void setBaseline(int value) {
        concurrency = clampConcurrency(value);
        ceiling = 0;
        lastReason = "人工校准为 " + concurrency + " 台";
        persist();
        Log.i(TAG, lastReason);
    }

    /** 重置学习：清空历史经验，按硬件体检结果重新起步（换环境/想重新探测时用） */
    public synchronized void reset() {
        concurrency = deviceReport != null
                ? deviceReport.getStartConcurrency() : AdaptiveConfig.START_MID;
        ceiling = 0;
        runs = 0;
        lastTotal = 0;
        lastSucceeded = 0;
        lastConnectFailed = 0;
        lastReason = "已重置，按硬件体检结果重新起步";
        if (store != null) {
            store.clear();
        }
        persist();
        Log.i(TAG, lastReason + "（" + concurrency + " 台）");
    }

    private void persist() {
        if (store != null) {
            store.save(concurrency, ceiling, runs, tier != null ? tier.name() : null,
                    lastTotal, lastSucceeded, lastConnectFailed, lastReason);
        }
    }

    private static int clampConcurrency(int value) {
        return Math.max(AdaptiveConfig.MIN_CONCURRENCY,
                Math.min(AdaptiveConfig.MAX_CONCURRENCY, value));
    }
}
