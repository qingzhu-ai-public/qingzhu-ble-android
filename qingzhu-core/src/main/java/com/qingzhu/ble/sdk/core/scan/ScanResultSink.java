// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.sdk.core.scan;

/**
 * 平台扫描器的结果回调（消除底层对门面的反向依赖）。
 *
 * <p>AndroidBleScanner 扫到广播 / 扫描失败时，不再绕道 {@code QingzhuBle.getInstance()}，
 * 而是通过这个接口直接回调给持有它的上层（ScanEngine / 任务内核），架构上自底向上单向。
 */
public interface ScanResultSink {
    /** 扫到一条广播（已包成 ScanRecord，含地址/rssi/原始字节） */
    void onScanResult(ScanRecord record, String deviceName);

    /** 扫描失败（系统错误重试耗尽 / 不支持等），本轮扫描终止 */
    void onScanFailed(int errorCode, String message);
}
