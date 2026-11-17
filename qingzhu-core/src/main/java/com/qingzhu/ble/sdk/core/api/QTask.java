// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.sdk.core.api;

import com.qingzhu.ble.sdk.core.device.QDevice;

/**
 * 任务句柄：一台设备在任务池里的身份。
 *
 * <p>任务 = 设备 + 可选命令。有命令就自动执行（待机 → 寻找 → 排队 → 连接 → 发送），
 * 无命令就停在待机中。任务一直在队列里，直到 {@link #delete()} 才离开。
 */
public interface QTask {

    /** 任务唯一 ID */
    String id();

    /** 目标设备的 mac（占位任务的核心标识） */
    String deviceMac();

    /** 当前实际操作的 bleMac：连接时可能被重寻址更新为设备最新广播地址；未连接/未找到设备时 = 提交时的 mac */
    String currentMac();

    /** 找到的 QDevice（占位任务寻找成功后才有；扫描来的任务一开始就有） */
    QDevice getDevice();

    /** 当前状态 */
    TaskState getState();

    /** 发送进度 0-100（非发送中为 0），由 sentBytes/totalBytes 推导 */
    int getProgress();

    /** 当前已发送字节数（大块传输进度回调携带；非发送中为 0） */
    int getSentBytes();

    /** 本次传输总字节数（大块传输进度回调携带；非发送中为 0） */
    int getTotalBytes();

    /** 最近一次发送完成后，从机回显的数据（可读文本 / hex）；从未发过或失败时为 null */
    String getResult();

    /** 最近一次发送失败的原因（如「查询失败 reason=129」）；从未失败或刚成功时为 null */
    String getError();

    /** 最近一次命令的终态分类，供批量统计和自动并发学习使用 */
    TaskOutcome getOutcome();

    /** 等待当前操作结束后再执行的意图数量 */
    int pendingIntentCount();

    /** 任务是否已经删除；删除后的迟到调用应返回 {@link TaskIntentResult#CLOSED} */
    boolean isDeleted();

    /**
     * 提交一个意图，并返回“开始、排队、已满足、进行中、忙或已关闭”。
     * 这只代表意图是否被接收，最终结果仍看任务状态、result 和 error。
     */
    default TaskIntentResult executeIntent(QCommand command) {
        return executeIntent(command, null);
    }

    /**
     * 提交意图并接收该意图的最终结果回调。
     *
     * <p>回调只在意图被接受后注册；返回 BUSY 或 CLOSED 时不会回调。
     * 连接意图若立即已满足，会异步回调一次 SUCCEEDED；若正在连接，则在
     * 连接成功或最终失败时回调。发送意图在真正发送完成、最终失败或被取消时回调。
     */
    TaskIntentResult executeIntent(QCommand command, TaskIntentCallback callback);

    /** 兼容旧调用：忽略立即结果。需要判断排队/已满足/忙时请使用 {@link #executeIntent(QCommand)}。 */
    default void execute(QCommand command) {
        executeIntent(command);
    }

    /** 取消执行，回到待机中（任务还在队列） */
    void cancel();

    /** 删除：按当前状态清理（取消发送 / 断开 / 清缓存），任务彻底离开队列 */
    void delete();
}
