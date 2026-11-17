// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.adaptive;

/**
 * 一轮批量配置的结果统计——学习算法的唯一输入。
 *
 * <p><b>关键点：必须区分"连接建立阶段失败"和"业务操作失败"。</b>
 * 只有前者能反映"并发太高"；后者（指令被设备拒绝、业务校验不过）是设备/业务问题，
 * 混进来会把学习结果带偏——明明是设备有问题，却把并发度降下来，越学越差。
 */
public final class BatchOutcome {

    private final int total;
    private final int succeeded;
    private final int connectFailed;
    private final int operationFailed;
    private final boolean cancelled;

    private BatchOutcome(Builder b) {
        this.total = b.total;
        this.succeeded = b.succeeded;
        this.connectFailed = b.connectFailed;
        this.operationFailed = b.operationFailed;
        this.cancelled = b.cancelled;
    }

    /** 本轮参与的设备总数 */
    public int getTotal() {
        return total;
    }

    /** 成功完成的设备数 */
    public int getSucceeded() {
        return succeeded;
    }

    /** 连接建立阶段失败数（含连接 / 发现服务 / MTU / 使能通知 / 认证） */
    public int getConnectFailed() {
        return connectFailed;
    }

    /** 连上之后业务操作失败数（不参与学习判断） */
    public int getOperationFailed() {
        return operationFailed;
    }

    /** 本轮是否被中途取消（样本不完整，不参与学习） */
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public String toString() {
        return "BatchOutcome{total=" + total + ", ok=" + succeeded
                + ", connectFail=" + connectFailed + ", opFail=" + operationFailed
                + ", cancelled=" + cancelled + "}";
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private int total;
        private int succeeded;
        private int connectFailed;
        private int operationFailed;
        private boolean cancelled;

        public Builder total(int v) {
            this.total = v;
            return this;
        }

        public Builder succeeded(int v) {
            this.succeeded = v;
            return this;
        }

        public Builder connectFailed(int v) {
            this.connectFailed = v;
            return this;
        }

        public Builder operationFailed(int v) {
            this.operationFailed = v;
            return this;
        }

        public Builder cancelled(boolean v) {
            this.cancelled = v;
            return this;
        }

        public BatchOutcome build() {
            return new BatchOutcome(this);
        }
    }
}
