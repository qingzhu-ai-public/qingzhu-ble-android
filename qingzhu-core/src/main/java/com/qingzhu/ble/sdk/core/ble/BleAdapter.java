// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.sdk.core.ble;

import com.qingzhu.ble.sdk.core.connect.ConnectParams;
import com.qingzhu.ble.sdk.core.device.QDevice;
import com.qingzhu.ble.sdk.core.pool.ReconnectPolicy;

import java.util.List;
import java.util.Map;

/**
 * BLE 能力接口（平台无关抽象）。
 *
 * <p>任务内核只通过它操作设备，不碰任何平台 API。Android 实现见
 * {@code AndroidBleAdapter}（把 {@code platform} 包里的 ScanEngine / ConnectionPool /
 * TaskScheduler 包起来）；iOS / 鸿蒙各实现一套。
 *
 * <p>三个能力：扫描（发现设备）、连接（单台，内部建连串行）、发数据（单台，内部串行）。
 */
public interface BleAdapter {

    // ============ 扫描 ============

    /** 开始扫描，设备变化通过 {@link ScanSink} 回调 */
    void startScan(ScanSink sink);

    /** 停止扫描 */
    void stopScan();

    /**
     * 重启系统扫描器（<b>不重建扫描引擎、不清空设备快照</b>）：用于「手动重扫 / 扫描僵尸自愈」——
     * 只重新注册底层系统 startScan，解决息屏后扫描「注册成功但不再投递」的问题，同时保留已扫到的设备。
     * 与 {@link #stopScan()} + {@link #startScan(ScanSink)} 的区别：后者会清空引擎快照（设备列表清空）。
     */
    default void restartScanner() {
    }

    /** 清空扫描缓存（增量缓存，SDK 不主动清，需要时手动清） */
    void clearScanCache();

    // ============ 连接 ============

    /** 连接设备（内部建连串行；重连策略由上层任务内核决定，这里只负责连这一次） */
    void connect(QDevice device, ConnectParams params, ReconnectPolicy reconnectPolicy, ConnectSink sink);

    /** 主动断开 */
    void disconnect(String mac);

    // ============ 发数据 ============

    /** 发数据（JSON 指令 + 可选附件，如 {@code {"data": 固件/单面图字节}} 或
     *  {@code {"dataA": bytesA, "dataB": bytesB, ...}} 多面图；单设备串行在内部） */
    void send(String mac, String json, Map<String, Object> attachments, SendSink sink);

    /** 中止该设备正在进行的发送（若有） */
    void abortSend(String mac);

    /**
     * 大数据发送前的准备：请求高连接优先级 + 2M PHY 提升吞吐，等约 500ms（让连接参数
     * 更新生效）后回调 {@code onReady}。小数据发送不要调用（连接参数更新反而干扰回执）。
     */
    void prepareForLargeTransfer(String mac, Runnable onReady);

    // ============ 能力 ============

    /** 设置连接并发容量（0 = 自动学习） */
    void setConcurrency(int n);

    /** 当前并发容量 */
    int concurrency();

    // ============ 回调 ============

    /** 扫描结果回调 */
    interface ScanSink {
        /** 当前扫描到的所有设备（已按稳定标识去重，含最新信号） */
        void onDevices(List<QDevice> devices);

        /** 扫描失败（蓝牙未开/权限缺失/系统扫描失败重试耗尽） */
        void onScanFailed(int errorCode, String message);

        /** 扫描停止（定时超时自动停 / 手动停）时的最终回调，交付最终设备清单 */
        default void onScanStopped(List<QDevice> devices) {
        }
    }

    /** 连接结果回调 */
    interface ConnectSink {
        void onConnected(String mac);

        void onFailed(String mac, int errorCode, String message);
    }

    /** 发送结果回调 */
    interface SendSink {
        /**
         * 发送进度（字节数，事实）：{@code sentBytes} 已写字节、{@code totalBytes} 总字节。
         * 百分比、速率等由调用方推导。
         */
        void onProgress(int sentBytes, int totalBytes);

        /** 发送完成（设备已回执）；{@code result} = 从机回显的数据（可读文本 / hex），可能为 null */
        void onDone(String result);

        /** 发送失败 */
        void onFailed(String message);
    }
}
