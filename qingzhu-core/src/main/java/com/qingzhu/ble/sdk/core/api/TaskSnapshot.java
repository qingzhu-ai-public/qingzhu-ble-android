// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.sdk.core.api;

/** 任务的不可变快照。 */
public final class TaskSnapshot {

    private final String id;
    private final String deviceMac;
    private final String currentMac;
    private final TaskState state;
    private final TaskOutcome outcome;
    private final int progress;
    private final int sentBytes;
    private final int totalBytes;
    private final String result;
    private final String error;
    private final int pendingCount;

    public TaskSnapshot(String id, String deviceMac, String currentMac, TaskState state,
                        TaskOutcome outcome, int progress, int sentBytes, int totalBytes,
                        String result, String error, int pendingCount) {
        this.id = id;
        this.deviceMac = deviceMac;
        this.currentMac = currentMac;
        this.state = state;
        this.outcome = outcome;
        this.progress = progress;
        this.sentBytes = sentBytes;
        this.totalBytes = totalBytes;
        this.result = result;
        this.error = error;
        this.pendingCount = pendingCount;
    }

    public String id() { return id; }
    public String deviceMac() { return deviceMac; }
    public String currentMac() { return currentMac; }
    public TaskState state() { return state; }
    public TaskOutcome outcome() { return outcome; }
    public int progress() { return progress; }
    /** 已发送字节数（非发送中为 0） */
    public int sentBytes() { return sentBytes; }
    /** 本次传输总字节数（非发送中为 0） */
    public int totalBytes() { return totalBytes; }
    public String result() { return result; }
    public String error() { return error; }
    public int pendingCount() { return pendingCount; }
}
