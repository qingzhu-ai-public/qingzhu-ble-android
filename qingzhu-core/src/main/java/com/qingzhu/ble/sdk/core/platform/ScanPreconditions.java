// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.sdk.core.platform;

import android.Manifest;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Build;

import com.qingzhu.ble.sdk.core.common.ErrorCode;

import java.util.ArrayList;
import java.util.List;

/**
 * 扫描前置条件检查（对标 Nordic 库 BluetoothEnabler/前置检查的思路）：
 * 扫描开始前把"缺什么"一次查清——权限、蓝牙开关、定位服务（Android 6~11 系统强制要求）。
 *
 * 旧实现的问题：缺条件时 startScan 内部只打个日志就返回，上层表现为"扫了但永远没结果"的
 * 静默失败，用户和接入方都无从排查。现在检查不通过直接回调 onScanFailed(明确错误码+原因)。
 *
 * 只读检查，不弹系统授权窗/引导页——那是接入方 UI 层的职责（demo 已有 ensurePermissions 的范式）。
 */
public final class ScanPreconditions {

    private ScanPreconditions() {
    }

    /**
     * 检查扫描前置条件。
     *
     * @return ErrorCode.SUCCESS 表示全部通过；否则返回扫描类错误码（1301~1303）
     */
    public static int check(Context context) {
        if (context == null) {
            return ErrorCode.INTERNAL_ERROR; // 未 init，由调用方给出专属提示
        }

        // 1. 蓝牙权限（Android 12 起是新的 BLUETOOTH_SCAN/CONNECT 运行时权限）
        List<String> missing = missingPermissions(context);
        if (!missing.isEmpty()) {
            return ErrorCode.SCAN_PERMISSION_MISSING;
        }

        // 2. 蓝牙已开启
        BluetoothManager bm = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm == null || bm.getAdapter() == null || !bm.getAdapter().isEnabled()) {
            return ErrorCode.SCAN_BT_DISABLED;
        }

        // 3. 定位服务开启（Android 6~11：系统不给未开定位的应用分发扫描结果）
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            boolean locationOn = lm != null && (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER));
            if (!locationOn) {
                return ErrorCode.SCAN_LOCATION_DISABLED;
            }
        }
        return ErrorCode.SUCCESS;
    }

    /** 把错误码翻译成面向接入方/用户的可读说明（含应做什么），随 onScanFailed 一起回调 */
    public static String describe(int errorCode) {
        switch (errorCode) {
            case ErrorCode.SCAN_PERMISSION_MISSING:
                return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                        ? "缺少 BLUETOOTH_SCAN/BLUETOOTH_CONNECT 运行时权限，请先申请（Android 12+）"
                        : "缺少 ACCESS_FINE_LOCATION 运行时权限，请先申请（Android 6~11 扫描必需）";
            case ErrorCode.SCAN_BT_DISABLED:
                return "蓝牙未开启，请引导用户开启蓝牙";
            case ErrorCode.SCAN_LOCATION_DISABLED:
                return "定位服务未开启（Android 6~11 系统强制要求，扫描不会返回任何结果）";
            case ErrorCode.INTERNAL_ERROR:
                return "未调用 QingzhuBle.init(context)，无法启动扫描";
            default:
                return "扫描失败 errorCode=" + errorCode;
        }
    }

    private static List<String> missingPermissions(Context context) {
        List<String> missing = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (notGranted(context, Manifest.permission.BLUETOOTH_SCAN)) {
                missing.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (notGranted(context, Manifest.permission.BLUETOOTH_CONNECT)) {
                missing.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        } else if (notGranted(context, Manifest.permission.ACCESS_FINE_LOCATION)) {
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        return missing;
    }

    private static boolean notGranted(Context context, String permission) {
        return context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED;
    }
}
