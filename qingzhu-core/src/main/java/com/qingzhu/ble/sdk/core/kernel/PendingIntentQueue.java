// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.sdk.core.kernel;

import java.util.ArrayDeque;

/**
 * 单设备任务内的有界待办队列：线程安全、FIFO、拒绝而不是静默丢弃。
 */
final class PendingIntentQueue<T> {
    private final ArrayDeque<T> queue = new ArrayDeque<>();

    synchronized boolean offer(T item, int maxSize) {
        if (item == null || maxSize <= 0 || queue.size() >= maxSize) {
            return false;
        }
        queue.addLast(item);
        return true;
    }

    synchronized T poll() {
        return queue.pollFirst();
    }

    synchronized int size() {
        return queue.size();
    }

    synchronized void clear() {
        queue.clear();
    }
}
