// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.adaptive;

import android.app.ActivityManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.os.Build;

/**
 * 硬件体检：只读系统公开能力，不申请权限、不改任何状态，异常一律降级为"按系统版本判断"。
 *
 * <p>安卓没有"本机最多支持几路 BLE 连接"的公开 API，所以这里综合几个能查到的硬件信号，
 * 估算这台手机大概什么档次，给学习器一个合理的起跑点：
 * <ul>
 *   <li><b>蓝牙芯片代数</b>：是否支持蓝牙 5.0 特性（2M PHY / 扩展广播 / LE Coded PHY）</li>
 *   <li><b>系统版本</b>：Android 8.0 以上的蓝牙协议栈更成熟</li>
 *   <li><b>整机档次</b>：CPU 核数 / 内存</li>
 * </ul>
 *
 * <p>估得不准没关系——真正的值由 {@link ConcurrencyLearner} 用实测成功率修正。
 */
public final class DeviceInspector {

    private DeviceInspector() {
    }

    public static DeviceReport inspect(Context context) {
        int score = 0;
        boolean ble5 = false;

        BluetoothAdapter adapter = adapter(context);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && adapter != null) {
            try {
                if (adapter.isLe2MPhySupported()) {
                    ble5 = true;
                    score += 2;
                }
                if (adapter.isLeExtendedAdvertisingSupported()) {
                    score += 1;
                }
                if (adapter.isLeCodedPhySupported()) {
                    score += 1;
                }
            } catch (Exception ignored) {
                // 权限未授予等异常：跳过蓝牙特性检测，不影响主流程
            }
        }
        if (adapter != null) {
            try {
                if (adapter.isOffloadedScanBatchingSupported()) {
                    score += 1;
                }
            } catch (Exception ignored) {
            }
        }

        boolean modernOs = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O;
        if (modernOs) {
            score += 2;
        }

        int cores = Runtime.getRuntime().availableProcessors();
        if (cores >= 8) {
            score += 1;
        }
        long memoryBytes = totalMemory(context);
        if (memoryBytes >= 6L * 1024 * 1024 * 1024) {
            score += 1;
        }

        // 分档规则（简单可靠，先给个合理起点）：
        //   蓝牙 5.0 → 高配（7 台）；系统 8.0+ → 中配（4 台）；更老的机器 → 低配（2 台）
        DeviceTier tier = ble5 ? DeviceTier.HIGH : (modernOs ? DeviceTier.MID : DeviceTier.LOW);

        String detail = (ble5 ? "蓝牙5.0" : "蓝牙4.x")
                + " · Android" + androidRelease()
                + " · " + cores + "核"
                + " · 内存" + (memoryBytes / (1024L * 1024 * 1024)) + "G"
                + " · 评分" + score;
        return new DeviceReport(tier, tier.getStartConcurrency(), score, detail);
    }

    private static BluetoothAdapter adapter(Context context) {
        try {
            BluetoothManager bm = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
            return bm != null ? bm.getAdapter() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String androidRelease() {
        String release = Build.VERSION.RELEASE;
        return release != null ? release : String.valueOf(Build.VERSION.SDK_INT);
    }

    private static long totalMemory(Context context) {
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(info);
                return info.totalMem;
            }
        } catch (Exception ignored) {
        }
        return 0L;
    }
}
