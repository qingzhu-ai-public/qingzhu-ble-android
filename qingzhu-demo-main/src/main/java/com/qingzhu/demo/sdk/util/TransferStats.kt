// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.demo.sdk.util

import android.os.SystemClock

/**
 * 传输速率统计：根据「字节数 + 时间戳」算实时速率和平均速率（迅雷式）。
 * 纯展示层数据解释，不碰 SDK——core 只提供字节进度，这里负责算速率。
 */
class TransferStats {

    private var startMs = 0L
    private var lastBytes = 0
    private var lastMs = 0L
    private var doneMs = 0L

    /** 总字节数（进度回调里更新） */
    var totalBytes = 0
        private set

    /** 瞬时速率（字节/秒）：最近两次进度回调之间的增量 */
    var instantSpeed = 0L
        private set

    /** 平均速率（字节/秒）：传输完成后冻结 */
    var avgSpeed = 0L
        private set

    /** 每来一次进度回调更新一次 */
    fun onProgress(sentBytes: Int, totalBytes: Int) {
        val now = SystemClock.elapsedRealtime()
        // 检测新一轮传输：sentBytes 回退（从 0 重新开始）说明上次传输结束后又开始新的，重置统计
        if (lastBytes > sentBytes) {
            reset()
        }
        if (startMs == 0L) {
            startMs = now
        }
        this.totalBytes = totalBytes
        if (lastMs > 0 && now > lastMs) {
            instantSpeed = (sentBytes - lastBytes) * 1000L / (now - lastMs)
            if (instantSpeed < 0) {
                instantSpeed = 0
            }
        }
        lastBytes = sentBytes
        lastMs = now
    }

    /** 清空所有累计，用于新一轮传输 */
    fun reset() {
        startMs = 0L
        lastBytes = 0
        lastMs = 0L
        doneMs = 0L
        totalBytes = 0
        instantSpeed = 0L
        avgSpeed = 0L
    }

    /** 传输完成时调用：冻结耗时并算出平均速率（此后不再变化） */
    fun markDone() {
        if (doneMs == 0L && startMs > 0) {
            doneMs = SystemClock.elapsedRealtime() - startMs
            avgSpeed = if (doneMs > 0) totalBytes * 1000L / doneMs else 0
        }
    }

    /** 传输总耗时（毫秒）；未完成时返回 0 */
    fun doneMs(): Long = doneMs
}
