// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.adaptive;

/**
 * 并发度学习算法（纯逻辑、无 Android 依赖，便于单元测试）。
 *
 * <p>思路借用 TCP 拥塞控制的 AIMD（加性增 / 乘性减）：
 * <ul>
 *   <li><b>加性增</b>：一轮全成功 → 并发度 +1，试探更高；</li>
 *   <li><b>乘性减</b>：失败率偏高 → 并发度 -1。</li>
 * </ul>
 *
 * <p><b>天花板（ceiling）是本算法的关键。</b> 一旦某个并发度失败过，就记下"以后最多到这里"，
 * 避免反复去撞同一面墙——否则并发度会在 N 和 N+1 之间来回震荡，永远不稳定。
 * 举例（手机实际只能承受 3 台）：不记天花板时会 3→4→3→4… 无限弹；记了之后第一次
 * 4 台失败就把天花板压到 3，此后稳定停在 3。
 *
 * <p>三条"不学"的保护规则（避免越学越歪）：
 * <ol>
 *   <li>样本太少（本轮设备数 &lt; {@link AdaptiveConfig#MIN_SAMPLE}）→ 不学；</li>
 *   <li>中途被取消 → 结果不完整，不学；</li>
 *   <li>本轮一台都没成功 → 多半是设备/环境问题（没电、不在范围、密钥错），
 *       不是并发过高，降并发也救不了 → 不动。</li>
 * </ol>
 */
public final class ConcurrencyLearner {

    private ConcurrencyLearner() {
    }

    /** 学习结果：新并发度 + 新天花板 + 是否发生变化 + 原因说明（可直接显示在界面上） */
    public static final class Result {

        public final int concurrency;
        public final int ceiling;
        public final boolean changed;
        public final String reason;

        Result(int concurrency, int ceiling, boolean changed, String reason) {
            this.concurrency = concurrency;
            this.ceiling = ceiling;
            this.changed = changed;
            this.reason = reason;
        }
    }

    /**
     * 根据一轮批量结果推演下一次该用几台并发。
     *
     * @param concurrency 本轮使用的并发度
     * @param ceiling     已知上限（0 = 未知）
     * @param outcome     本轮统计
     */
    public static Result learn(int concurrency, int ceiling, BatchOutcome outcome) {
        final int min = AdaptiveConfig.MIN_CONCURRENCY;
        final int max = AdaptiveConfig.MAX_CONCURRENCY;
        final int total = outcome.getTotal();

        // ── 保护 1/2：样本不完整或太少，不学习 ──
        if (outcome.isCancelled()) {
            return keep(concurrency, ceiling, "本轮被取消，结果不完整，跳过学习");
        }
        if (total < AdaptiveConfig.MIN_SAMPLE) {
            return keep(concurrency, ceiling, "本轮仅 " + total + " 台，样本太少，跳过学习");
        }

        final int connectFailed = outcome.getConnectFailed();
        final int succeeded = outcome.getSucceeded();

        // ── 情况 A：连接阶段零失败 → 考虑提高 ──
        if (connectFailed == 0) {
            if (total < concurrency) {
                return keep(concurrency, ceiling, "本轮设备数少于并发度，没压满，暂不提高");
            }
            int upper = ceiling > 0 ? ceiling : max;
            if (concurrency >= upper) {
                return keep(concurrency, ceiling, "已达稳定上限 " + upper + " 台，保持");
            }
            int next = Math.min(concurrency + 1, upper);
            return new Result(next, ceiling, true, "全部成功，提高到 " + next + " 台");
        }

        // ── 保护 3：一台都没成功 → 系统性故障，不是并发问题 ──
        if (succeeded == 0) {
            return keep(concurrency, ceiling, "本轮全部失败，疑似设备或环境问题（非并发过高），不调整");
        }

        // ── 情况 B：部分成功、部分失败 → 疑似撞上并发瓶颈 ──
        boolean highFailRatio = connectFailed * 100 >= AdaptiveConfig.FAIL_RATIO_PERCENT * total;
        if (!highFailRatio) {
            return keep(concurrency, ceiling, "有零星失败但比例不高，保持 " + concurrency + " 台");
        }
        if (concurrency <= min) {
            return keep(concurrency, ceiling, "已是最低并发 1 台，无法再降");
        }
        int next = concurrency - 1;
        return new Result(next, next, true,
                "失败率偏高（" + connectFailed + "/" + total + "），降低到 " + next + " 台并记住上限");
    }

    private static Result keep(int concurrency, int ceiling, String reason) {
        return new Result(concurrency, ceiling, false, reason);
    }
}
