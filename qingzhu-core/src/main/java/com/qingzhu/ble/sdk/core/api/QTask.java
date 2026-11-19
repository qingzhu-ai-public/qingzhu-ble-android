// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.sdk.core.api;

import com.qingzhu.ble.sdk.core.device.QDevice;

import java.util.Map;

/**
 * 任务句柄：一台设备在任务池里的身份。
 *
 * <p>任务 = 设备 + 命令。命令提交后自动执行（寻找 → 连接 → 发送），
 * 任务一直留在任务池中，直到 {@link #delete()} 才离开。
 */
public interface QTask {

    /** 任务唯一 ID */
    String id();

    /** 目标设备的 mac */
    String deviceMac();

    /** 当前实际操作的 mac：连接时可能被重寻址更新为设备最新地址 */
    String currentMac();

    /** 找到的设备；未找到时为 null */
    QDevice getDevice();

    /** 当前任务状态（派生值，供调试与界面展示） */
    TaskState getState();

    /** 最近一次命令的终态分类 */
    TaskOutcome getOutcome();

    /** 最近一次成功命令的回显数据；未成功过时为 null */
    String getResult();

    /** 最近一次失败的原因；未失败过时为 null */
    String getError();

    /** 当前发送进度 0-100 */
    int getProgress();

    /** 当前已发送字节数（非发送中为 0） */
    int getSentBytes();

    /** 本次传输总字节数（非发送中为 0） */
    int getTotalBytes();

    /** 等待当前操作结束的命令数量 */
    int pendingIntentCount();

    /** 任务是否已删除 */
    boolean isDeleted();

    /**
     * 发送命令，并在结果确定后回调一次。
     * 任务已删除时立即回调 {@link TaskStage#CLOSED} 失败。
     */
    void send(QCommand command, TaskCallback callback);

    /** 连接设备并保持连接，等同于 {@code send(QCommand.builder().connect().build(), callback)} */
    void connect(TaskCallback callback);

    /** 断开连接，任务保留在任务池中 */
    void disconnect();

    /** 设置连接参数（如认证密钥），对该任务之后的连接生效 */
    void setConnectParams(Map<String, Object> params);

    /** 观察任务状态变化，传入 owner 时 owner 销毁后自动取消 */
    Subscription observe(Object owner, TaskObserver observer);

    /** 取消当前操作，回到操作开始前的链路状态（未连接或已连接），任务保留 */
    void cancel();

    /** 删除：取消当前操作、断开连接，并从任务池移除 */
    void delete();
}
