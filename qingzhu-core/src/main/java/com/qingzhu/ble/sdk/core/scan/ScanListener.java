// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.sdk.core.scan;

import com.qingzhu.ble.sdk.core.device.QDevice;

import java.util.List;

/**
 * 扫描监听器（对标旧架构 OnScanResultListener 的批量 + 停止回调设计，
 * 并吸收 Kotlin 版 BLE 库的"语义级事件"：进入/离开范围）。
 *
 * - onScanResults：每秒批量回调一次"当前扫描到的所有设备"（已按 stableId 去重，
 *   RSSI/地址都是最新值，且含已"离开范围"的设备——每台带 lastSeenAt）。上层直接用
 *   {@code device.isInRange(threshold)} 决定置灰/删除，不需要自己去重/限频。
 * - onScanStopped：扫描停止（超时或手动）时的最终回调，交付最终设备清单。
 * - onDeviceEnteredRange/onDeviceLeftRange：语义事件——某设备第一次被扫到 / 静默
 *   （ScanConfig.leaveRangeMs，默认 30 秒）超过阈值时触发。<b>onDeviceLeftRange 只通知、
 *   不删除设备</b>，设备仍留在快照里（对齐 nRF Connect 的"置灰不删除"）。
 *
 * 四个方法都有默认空实现，调用方只实现需要的那几个。
 */
public interface ScanListener {

    /** 每秒批量回调：当前扫描到的所有设备（已按 stableId 去重，含最新 RSSI/地址） */
    default void onScanResults(List<QDevice> devices) {
    }

    /** 扫描停止（超时或手动）时的最终回调 */
    default void onScanStopped(List<QDevice> devices) {
    }

    /** 语义事件：设备第一次进入扫描范围（同一台设备只触发一次，直到它离开范围后重新出现） */
    default void onDeviceEnteredRange(QDevice device) {
    }

    /** 语义事件：设备超过 leaveRangeMs 没再被扫到（只通知，设备仍在快照里，UI 据此置灰） */
    default void onDeviceLeftRange(QDevice device) {
    }

    /**
     * 扫描无法正常进行时的失败回调（错误码见 ErrorCode 1301~1306，message 为可读原因）：
     * 前置条件不满足（蓝牙未开/权限缺失/定位服务未开）或系统扫描失败且重试后仍失败。
     * 触发后本轮扫描已终止，需要修复原因后重新调用 startScan。
     */
    default void onScanFailed(int errorCode, String message) {
    }
}
