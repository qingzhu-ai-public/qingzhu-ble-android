// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.sdk.core.task;

import com.qingzhu.ble.sdk.core.session.Session;

import java.util.concurrent.BlockingDeque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;

/**
 * 按 Session（每连接）隔离的任务队列，取代现有系统里 {@code CommandExecutor} 的全局单例设计
 *（现有 {@code CommandExecutor.currentTask} 是唯一的 volatile 字段，不区分设备，
 * A 设备命令执行中，B 设备命令一来会被当成"新任务"抢占旧任务）。
 *
 * A 设备执行命令时，B 设备提交命令只进 B 自己的队列，物理上碰不到 A 的执行槎位——
 * 这是"多连接"从"不可能"变成"默认支持"的关键：不是加新功能，是把原来错误的全局锁粒度
 * 改成按连接的锁粒度（《新SDK架构-技术实现方案.md》第12.5节）。
 *
 * <p><b>并发安全</b>：执行位用 {@code putIfAbsent} 原子占位。此前的"先查 containsKey 再 put"
 * 存在 check-then-act 竞态——两个线程同时提交同一 mac 的任务时可能都看到执行位空闲，
 * 于是并发执行，破坏"按 mac 串行"的保证。而协议层的应答监听器与接收缓冲区都是每设备一份，
 * 串行是<b>正确性前提</b>，不只是性能优化。
 */
public final class TaskScheduler {

    private final ConcurrentHashMap<String, LinkedBlockingDeque<Task>> queues = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Task> executing = new ConcurrentHashMap<>();

    public void submit(Session session, Task task) {
        String mac = session.getMac();
        queues.computeIfAbsent(mac, k -> new LinkedBlockingDeque<>()).offerLast(task);
        tryExecuteNext(mac);
    }

    private void tryExecuteNext(String mac) {
        LinkedBlockingDeque<Task> queue = queues.get(mac);
        if (queue == null) {
            return;
        }
        Task next = queue.pollFirst();
        if (next == null) {
            return;
        }
        // 原子占位：抢不到执行位说明已有任务在跑，放回队首等它收尾时再来取（保持 FIFO 顺序）
        if (executing.putIfAbsent(mac, next) != null) {
            queue.offerFirst(next);
            return;
        }
        next.run(() -> {
            executing.remove(mac);
            tryExecuteNext(mac);
        });
    }

    /** 连接断开时调用，清空该设备残留的任务队列，避免内存泄漏和"断线后命令还在排队"的错觉 */
    public void clear(String mac) {
        queues.remove(mac);
        executing.remove(mac);
    }

    public boolean isExecuting(String mac) {
        return executing.containsKey(mac);
    }

    public int pendingCount(String mac) {
        BlockingDeque<Task> queue = queues.get(mac);
        return queue == null ? 0 : queue.size();
    }
}
