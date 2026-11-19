// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.sdk.core.kernel;

import com.qingzhu.ble.sdk.core.api.QCommand;
import com.qingzhu.ble.sdk.core.api.QTask;
import com.qingzhu.ble.sdk.core.api.Subscription;
import com.qingzhu.ble.sdk.core.api.TaskCallback;
import com.qingzhu.ble.sdk.core.api.TaskObserver;
import com.qingzhu.ble.sdk.core.api.TaskOutcome;
import com.qingzhu.ble.sdk.core.api.TaskSnapshot;
import com.qingzhu.ble.sdk.core.api.TaskState;
import com.qingzhu.ble.sdk.core.device.QDevice;

import java.util.HashMap;
import java.util.Map;

/**
 * {@link QTask} 的实现：一台设备在任务池里的状态载体。
 *
 * <p>状态推进、调度、清理都在 {@link QTaskPool} 里，这里只持有状态数据 + 把操作委托给池。
 */
final class TaskImpl implements QTask {

    private final String id;
    private final String deviceMac;
    /** 去重身份：同一台物理设备只允许一个任务。扫描设备 = stableId，占位任务 = 传入的 mac。 */
    private final String identityKey;
    private final QTaskPool pool;

    private volatile QDevice device;
    private volatile QCommand command;
    private volatile TaskState state;
    /** 已发送字节数（大块传输进度回调携带） */
    private volatile int sentBytes;
    /** 本次传输总字节数 */
    private volatile int totalBytes;
    private volatile int retryCount;
    /** 当前实际要拿去操作的 bleMac（连接时会被重寻址更新为最新广播地址，初始=提交时的 mac） */
    private volatile String currentMac;
    /** 最近一次发送完成后从机回显的数据（可读文本 / hex） */
    private volatile String result;
    /** 最近一次发送失败的原因（如「查询失败 reason=129」） */
    private volatile String error;
    /** 最近一次命令的终态分类 */
    private volatile TaskOutcome outcome = TaskOutcome.NONE;
    /** 发送代次：execute / cancel 时递增。用于丢弃「取消或重新执行」后旧发送的迟到回调（onProgress/onDone/onFailed），防止旧进度/状态污染新一轮 */
    private volatile long sendGeneration;
    /** 上次已通知的进度百分比：大块传输 onProgress 每片都回调，进度百分比没变就不通知，避免高频全量刷新任务列表 */
    private volatile int lastNotifiedProgress = -1;
    /** 用户显式要求保持连接：发送过程中再次 connect 时只改这个期望，不覆盖当前发送 */
    private volatile boolean connectionDesired;
    /** 是否已经有一个线程在等待传输窗口，避免重复发起等待线程 */
    private volatile boolean waitingForTransfer;
    /** 是否已经删除；删除后的迟到调用不能再进入任务池 */
    private volatile boolean deleted;
    /** 连接参数（由 setConnectParams 写入，连接时透传给协议） */
    private volatile Map<String, Object> connectParams;
    /** 当前操作开始前链路是否已连接：cancel 据此决定回到已连接还是未连接 */
    private volatile boolean connectedBeforeOp;
    /** 任务级状态观察者 */
    private final ListenerRegistry<TaskObserver> observers = new ListenerRegistry<>();
    /** 同一设备的待发送意图，有界 FIFO */
    private final PendingIntentQueue<QueuedIntent> pendingIntents = new PendingIntentQueue<>();
    /** 当前正在发送的意图（带命令级 ID / 回调） */
    private volatile QueuedIntent currentIntent;
    /** 正在等待连接结果的意图（例如连接中再次 connect） */
    private final java.util.List<QueuedIntent> connectionWaiters =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    TaskImpl(String id, String deviceMac, QDevice device, QTaskPool pool, String identityKey) {
        this.id = id;
        this.deviceMac = deviceMac;
        this.device = device;
        this.pool = pool;
        this.identityKey = identityKey;
        this.state = device != null ? TaskState.READY : TaskState.UNKNOWN;
        this.currentMac = deviceMac;
    }

    // ============ QTask 对外 ============

    @Override
    public String id() { return id; }

    @Override
    public String deviceMac() { return deviceMac; }

    @Override
    public String currentMac() { return currentMac; }

    @Override
    public QDevice getDevice() { return device; }

    @Override
    public TaskState getState() { return state; }

    @Override
    public int getProgress() {
        if (totalBytes <= 0) {
            return 0;
        }
        return (int) (sentBytes * 100L / totalBytes);
    }

    @Override
    public int getSentBytes() { return sentBytes; }

    @Override
    public int getTotalBytes() { return totalBytes; }

    @Override
    public String getResult() { return result; }

    @Override
    public String getError() { return error; }

    @Override
    public TaskOutcome getOutcome() { return outcome; }

    @Override
    public int pendingIntentCount() { return pendingIntents.size(); }

    @Override
    public boolean isDeleted() { return deleted; }

    @Override
    public void send(QCommand command, TaskCallback callback) {
        pool.send(this, command, callback);
    }

    @Override
    public void connect(TaskCallback callback) {
        pool.send(this, QCommand.builder().connect().build(), callback);
    }

    @Override
    public void disconnect() { pool.disconnect(this); }

    @Override
    public void setConnectParams(Map<String, Object> params) {
        this.connectParams = params == null ? null : new HashMap<>(params);
    }

    @Override
    public Subscription observe(Object owner, TaskObserver observer) {
        if (deleted || observer == null) {
            return new Subscription() {
                @Override public void cancel() { }
                @Override public boolean isActive() { return false; }
            };
        }
        return observers.add(owner, observer);
    }

    @Override
    public void cancel() { pool.cancel(this); }

    @Override
    public void delete() { pool.delete(this); }

    // ============ 池内部调用 ============

    void setState(TaskState s) { this.state = s; }
    void setProgress(int sentBytes, int totalBytes) {
        this.sentBytes = sentBytes;
        this.totalBytes = totalBytes;
    }
    void resetProgress() {
        this.sentBytes = 0;
        this.totalBytes = 0;
        this.lastNotifiedProgress = -1; // 新一轮发送从头通知
    }

    /** 进度百分比变化时才返回 true（供 onProgress 节流 notifyChanged），避免大块传输每片回调都全量刷新 */
    boolean markProgressNotified(int percent) {
        if (percent == lastNotifiedProgress) {
            return false;
        }
        lastNotifiedProgress = percent;
        return true;
    }
    void setDevice(QDevice d) { this.device = d; }
    void setCommand(QCommand c) { this.command = c; }
    void setResult(String r) { this.result = r; }
    void setError(String e) { this.error = e; }
    void setOutcome(TaskOutcome outcome) { this.outcome = outcome; }

    QCommand command() { return command; }
    void setCurrentMac(String mac) { this.currentMac = mac; }
    String identityKey() { return identityKey; }
    int retryCount() { return retryCount; }
    void resetRetry() { this.retryCount = 0; }
    void bumpRetry() { this.retryCount++; }
    /** 递增发送代次并返回新值：cancel / execute 时调用，让旧发送的迟到回调失效 */
    long bumpSendGeneration() { return ++sendGeneration; }
    long sendGeneration() { return sendGeneration; }

    boolean isConnectionDesired() { return connectionDesired; }
    void setConnectionDesired(boolean desired) { this.connectionDesired = desired; }
    boolean isWaitingForTransfer() { return waitingForTransfer; }
    void setWaitingForTransfer(boolean waiting) { this.waitingForTransfer = waiting; }
    void markDeleted() { this.deleted = true; }
    Map<String, Object> connectParams() { return connectParams; }
    boolean connectedBeforeOp() { return connectedBeforeOp; }
    void setConnectedBeforeOp(boolean connected) { this.connectedBeforeOp = connected; }
    ListenerRegistry<TaskObserver> observers() { return observers; }

    /** 生成当前不可变快照 */
    TaskSnapshot snapshot() {
        return new TaskSnapshot(id, deviceMac, currentMac, state, outcome, getProgress(),
                sentBytes, totalBytes, result, error, pendingIntents.size());
    }
    boolean offerPendingIntent(QueuedIntent intent, int maxSize) {
        return pendingIntents.offer(intent, maxSize);
    }
    QueuedIntent pollPendingIntent() { return pendingIntents.poll(); }
    void clearPendingIntents() { pendingIntents.clear(); }
    java.util.List<QueuedIntent> drainPendingIntents() {
        java.util.List<QueuedIntent> drained = new java.util.ArrayList<>();
        QueuedIntent next;
        while ((next = pendingIntents.poll()) != null) {
            drained.add(next);
        }
        return drained;
    }

    QueuedIntent currentIntent() { return currentIntent; }
    void setCurrentIntent(QueuedIntent intent) { currentIntent = intent; }
    QueuedIntent clearCurrentIntent() {
        QueuedIntent intent = currentIntent;
        currentIntent = null;
        return intent;
    }

    void addConnectionWaiter(QueuedIntent intent) { connectionWaiters.add(intent); }
    java.util.List<QueuedIntent> drainConnectionWaiters() {
        java.util.List<QueuedIntent> waiters = new java.util.ArrayList<>(connectionWaiters);
        connectionWaiters.clear();
        return waiters;
    }
    void clearConnectionWaiters() { connectionWaiters.clear(); }
}
