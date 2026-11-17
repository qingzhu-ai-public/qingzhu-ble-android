// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.adaptive;

/**
 * core 内置连接自适应的全部可调参数——集中一处，方便长期迭代时统一调整。
 *
 * <p>本包与 BLE 协议栈无关，只做两件事：
 * <ol>
 *   <li><b>硬件体检</b>（{@link DeviceInspector}）：看手机"体格"给一个合理的起始并发度；</li>
 *   <li><b>经验学习</b>（{@link ConcurrencyLearner}）：用每轮批量的成功率，逐步逼近这台手机
 *       真正能承受的并发连接数，并把结果持久化，配得越多越准。</li>
 * </ol>
 */
public final class AdaptiveConfig {

    private AdaptiveConfig() {
    }

    // ---------------- 并发度边界 ----------------

    /** 并发度下限：1 = 完全串行，任何手机都能跑 */
    public static final int MIN_CONCURRENCY = 1;

    /**
     * 并发度上限：对齐 core 侧连接池的硬闸（{@code ConnectionPool.defaultPool()} 的 maxPoolSize = 7）
     * 与 Android 常见的 GATT 并发连接数上限。学到再高也没有意义——底层会挡住。
     */
    public static final int MAX_CONCURRENCY = 7;

    // ---------------- 学习参数 ----------------

    /** 一轮批量至少要有这么多台设备，结果才拿来学习（样本太少会学歪） */
    public static final int MIN_SAMPLE = 3;

    /** 连接阶段失败率达到该比例即判定"并发太高"（单位 %） */
    public static final int FAIL_RATIO_PERCENT = 25;

    // ---------------- 硬件体检分档的起始并发度 ----------------

    /** 高配：支持蓝牙 5.0 特性（新芯片），实测往往一上来就能并发 7 台 */
    public static final int START_HIGH = 7;

    /** 中配：蓝牙 4.2 / 系统 Android 8.0 以上 */
    public static final int START_MID = 4;

    /** 低配：老机器 */
    public static final int START_LOW = 2;
}
