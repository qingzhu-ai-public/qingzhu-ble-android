// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.sdk.core.common;

/**
 * core 层通用错误码。协议插件可以在这个范围之外自定义自己的错误码（建议用更大的数字段落分隔），
 * 但引擎内部产生的错误统一用这里的常量，方便上层 App 做统一处理。
 */
public final class ErrorCode {

    private ErrorCode() {
    }

    public static final int SUCCESS = 0;

    // ---- 连接相关 ----
    public static final int CONNECT_TIMEOUT = 1001;
    public static final int CONNECT_GATT_FAILED = 1002;
    public static final int DISCOVER_SERVICES_FAILED = 1003;
    public static final int CHARACTERISTIC_NOT_FOUND = 1004;
    public static final int MTU_REQUEST_FAILED = 1005;
    public static final int ENABLE_NOTIFICATION_FAILED = 1006;
    public static final int AUTH_FAILED = 1007;
    public static final int RESCAN_TIMEOUT = 1008;
    public static final int DEVICE_NOT_CONNECTED = 1009;
    /** 配方执行中途 GATT 链路断开（不可重试——所有步骤都建立在链路存在的前提上，必须整体重来） */
    public static final int CONNECTION_LOST = 1010;

    // ---- 连接池 / 调度相关 ----
    public static final int POOL_FULL_REJECTED = 1101;
    public static final int TASK_TIMEOUT = 1102;
    public static final int TASK_CANCELLED = 1103;

    // ---- 指令 / JSON 门面相关 ----
    public static final int PROTOCOL_NOT_FOUND = 1201;
    public static final int ACTION_NOT_SUPPORTED = 1202;
    public static final int INVALID_JSON_FORMAT = 1203;
    public static final int EXECUTION_FAILED = 1204;

    // ---- 扫描相关（配合 ScanListener.onScanFailed 使用） ----
    /** 蓝牙未开启 */
    public static final int SCAN_BT_DISABLED = 1301;
    /** 蓝牙运行时权限未授予（Android 6~11 要定位权限，12+ 要 BLUETOOTH_SCAN/CONNECT） */
    public static final int SCAN_PERMISSION_MISSING = 1302;
    /** 定位服务未开启（Android 6~11 系统强制要求，否则扫描无结果） */
    public static final int SCAN_LOCATION_DISABLED = 1303;
    /** 系统扫描失败（重试后仍失败，message 带系统原始错误码） */
    public static final int SCAN_FAILED_INTERNAL = 1304;
    /** 设备不支持所需扫描特性 */
    public static final int SCAN_FAILED_FEATURE_UNSUPPORTED = 1305;
    /** 应用启动/停止扫描过于频繁，被系统限流 */
    public static final int SCAN_FAILED_TOO_FREQUENTLY = 1306;

    public static final int INTERNAL_ERROR = 1900;

    /**
     * 错误码 → 中文可读描述，供 UI 展示"错误码 + 原因"（配合 onFailed 的 message 一起用）。
     * 未收录的码返回 "未知错误"。
     */
    public static String describe(int code) {
        switch (code) {
            case SUCCESS: return "成功";
            case CONNECT_TIMEOUT: return "连接超时";
            case CONNECT_GATT_FAILED: return "GATT 连接失败";
            case DISCOVER_SERVICES_FAILED: return "发现服务失败";
            case CHARACTERISTIC_NOT_FOUND: return "特征值不存在";
            case MTU_REQUEST_FAILED: return "MTU 协商失败";
            case ENABLE_NOTIFICATION_FAILED: return "启用通知失败";
            case AUTH_FAILED: return "认证失败";
            case RESCAN_TIMEOUT: return "等待设备广播超时";
            case DEVICE_NOT_CONNECTED: return "设备未连接";
            case CONNECTION_LOST: return "链路断开";
            case POOL_FULL_REJECTED: return "连接池已满";
            case TASK_TIMEOUT: return "任务超时";
            case TASK_CANCELLED: return "任务取消";
            case PROTOCOL_NOT_FOUND: return "协议未注册";
            case ACTION_NOT_SUPPORTED: return "指令不支持";
            case INVALID_JSON_FORMAT: return "JSON 格式错误";
            case EXECUTION_FAILED: return "执行失败";
            case SCAN_BT_DISABLED: return "蓝牙未开启";
            case SCAN_PERMISSION_MISSING: return "蓝牙权限缺失";
            case SCAN_LOCATION_DISABLED: return "定位服务未开启";
            case SCAN_FAILED_INTERNAL: return "系统扫描失败";
            case SCAN_FAILED_FEATURE_UNSUPPORTED: return "扫描特性不支持";
            case SCAN_FAILED_TOO_FREQUENTLY: return "扫描过于频繁";
            case INTERNAL_ERROR: return "内部错误";
            default: return "未知错误";
        }
    }
}
