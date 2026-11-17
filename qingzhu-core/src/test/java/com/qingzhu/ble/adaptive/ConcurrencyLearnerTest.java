// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.adaptive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 学习算法单测——本模块刻意做成"零 Android 依赖"，就是为了这层能跑纯 JVM 测试。
 */
public class ConcurrencyLearnerTest {

    private static BatchOutcome outcome(int total, int ok, int connectFail) {
        return BatchOutcome.builder()
                .total(total)
                .succeeded(ok)
                .connectFailed(connectFail)
                .operationFailed(Math.max(0, total - ok - connectFail))
                .build();
    }

    @Test
    public void allSuccessAndSaturated_increaseByOne() {
        // 并发 3、跑了 5 台全成功，且压满了 → 提到 4
        ConcurrencyLearner.Result r = ConcurrencyLearner.learn(3, 0, outcome(5, 5, 0));
        assertTrue(r.changed);
        assertEquals(4, r.concurrency);
    }

    @Test
    public void allSuccessButNotSaturated_keep() {
        // 并发 5，但本轮只有 3 台设备 → 没压满，不提高
        ConcurrencyLearner.Result r = ConcurrencyLearner.learn(5, 0, outcome(3, 3, 0));
        assertFalse(r.changed);
        assertEquals(5, r.concurrency);
    }

    @Test
    public void partialFailureHighRatio_decreaseAndRememberCeiling() {
        // 3 台里 2 台连接失败（67% ≥ 25%）→ 降到 2 并记住上限 2
        ConcurrencyLearner.Result r = ConcurrencyLearner.learn(3, 0, outcome(3, 1, 2));
        assertTrue(r.changed);
        assertEquals(2, r.concurrency);
        assertEquals(2, r.ceiling);
    }

    @Test
    public void totalFailure_doNotAdjust() {
        // 一台都没成功：疑似设备/环境问题（没电、不在范围），不是并发过高
        ConcurrencyLearner.Result r = ConcurrencyLearner.learn(3, 0, outcome(3, 0, 3));
        assertFalse(r.changed);
        assertEquals(3, r.concurrency);
    }

    @Test
    public void tooFewSamples_skipLearning() {
        ConcurrencyLearner.Result r = ConcurrencyLearner.learn(3, 0, outcome(2, 1, 1));
        assertFalse(r.changed);
        assertEquals(3, r.concurrency);
    }

    @Test
    public void cancelled_skipLearning() {
        BatchOutcome o = BatchOutcome.builder()
                .total(5).succeeded(3).connectFailed(1).cancelled(true).build();
        ConcurrencyLearner.Result r = ConcurrencyLearner.learn(3, 0, o);
        assertFalse(r.changed);
    }

    @Test
    public void ceilingPreventsHittingTheSameWallAgain() {
        // 第一轮：4 台失败过 → 降到 3，天花板 3
        ConcurrencyLearner.Result first = ConcurrencyLearner.learn(4, 0, outcome(4, 2, 2));
        assertEquals(3, first.concurrency);
        assertEquals(3, first.ceiling);
        // 第二轮：3 台全成功，也不会再去撞 4
        ConcurrencyLearner.Result second = ConcurrencyLearner.learn(3, first.ceiling, outcome(5, 5, 0));
        assertFalse(second.changed);
        assertEquals(3, second.concurrency);
    }

    @Test
    public void occasionalFailureBelowThreshold_keep() {
        // 1/5 = 20% < 25% → 视为偶发，不降低
        ConcurrencyLearner.Result r = ConcurrencyLearner.learn(5, 0, outcome(5, 4, 1));
        assertFalse(r.changed);
        assertEquals(5, r.concurrency);
    }

    @Test
    public void alreadyAtMinimum_cannotGoLower() {
        ConcurrencyLearner.Result r = ConcurrencyLearner.learn(1, 0, outcome(3, 1, 2));
        assertFalse(r.changed);
        assertEquals(1, r.concurrency);
    }

    @Test
    public void reachesMaxConcurrency_stays() {
        ConcurrencyLearner.Result r = ConcurrencyLearner.learn(
                AdaptiveConfig.MAX_CONCURRENCY, 0,
                outcome(10, 10, 0));
        assertFalse(r.changed);
        assertEquals(AdaptiveConfig.MAX_CONCURRENCY, r.concurrency);
    }
}
