// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.sdk.core.common;

import com.qingzhu.ble.sdk.core.settings.BleSettings;

/**
 * core 层的轻量日志桥。
 *
 * qingzhu-ble-core 故意设计成纯 Java、零 Android 依赖（方便脱离 Android 环境做单元测试），
 * 所以不能直接 import android.util.Log。默认实现用 System.out/System.err 输出——
 * 在 Android 上 System.out/err 会被重定向进 logcat（tag 是 "System.out"/"System.err"），
 * 所以不需要额外配置也能在 adb logcat 里看到。
 *
 * 如果宿主 App 想要更规范的 tag/等级过滤，可以调用 {@link #setSink} 换成真正的
 * android.util.Log 实现（qingzhu-demo 里的 AndroidLogSink 就是这么做的）。
 */
public final class QLog {

    public interface Sink {
        void d(String tag, String msg);
        void i(String tag, String msg);
        void w(String tag, String msg);
        void e(String tag, String msg, Throwable t);
    }

    private static volatile Sink sink = new Sink() {
        @Override
        public void d(String tag, String msg) {
            System.out.println("[D/" + tag + "] " + msg);
        }

        @Override
        public void i(String tag, String msg) {
            System.out.println("[I/" + tag + "] " + msg);
        }

        @Override
        public void w(String tag, String msg) {
            System.out.println("[W/" + tag + "] " + msg);
        }

        @Override
        public void e(String tag, String msg, Throwable t) {
            System.err.println("[E/" + tag + "] " + msg + (t != null ? (" - " + t) : ""));
        }
    };

    private QLog() {
    }

    public static void setSink(Sink s) {
        sink = s;
    }

    public static void d(String tag, String msg) {
        d(tag, null, msg);
    }

    public static void i(String tag, String msg) {
        i(tag, null, msg);
    }

    public static void w(String tag, String msg) {
        w(tag, null, msg);
    }

    public static void e(String tag, String msg) {
        e(tag, null, msg);
    }

    public static void e(String tag, String msg, Throwable t) {
        e(tag, null, msg, t);
    }

    /** 带「阶段」标签的日志：{@code phase} 显式指定前缀（如 {@code 【断开】【下发】【解析】}），
     *  优先于按 tag 的自动归类；传 null 走自动归类。 */
    public static void d(String tag, String phase, String msg) {
        if (!debugEnabled()) {
            return;
        }
        sink.d(tag, prefixOf(tag, phase) + msg);
    }

    /** info：关键节点（连接成功/发送成功/断开成功等），始终输出，不受 debugLog 开关控制 */
    public static void i(String tag, String phase, String msg) {
        sink.i(tag, prefixOf(tag, phase) + msg);
    }

    public static void w(String tag, String phase, String msg) {
        if (!debugEnabled()) {
            return;
        }
        sink.w(tag, prefixOf(tag, phase) + msg);
    }

    public static void e(String tag, String phase, String msg) {
        sink.e(tag, prefixOf(tag, phase) + msg, null);
    }

    public static void e(String tag, String phase, String msg, Throwable t) {
        sink.e(tag, prefixOf(tag, phase) + msg, t);
    }

    /**
     * 日志前缀：{@code phase} 非空则直接用它（显式阶段）；否则按 tag 关键字自动归类。
     *
     * <p>自动归类（类名）：{@code 【扫描】}（Scan/Scanner）、{@code 【传输】}（Sender/Actions）、
     * {@code 【连接】}（Connect/Heartbeat/Gatt/Retry/Protocol）、其余 {@code 【其它】}。
     * 细分阶段请显式传 phase，例如断开用 {@code 【断开】}、下发用 {@code 【下发】}、解析应答用 {@code 【解析】}。
     */
    private static String prefixOf(String tag, String phase) {
        if (phase != null && !phase.isEmpty()) {
            return phase;
        }
        if (tag == null || tag.isEmpty()) {
            return "";
        }
        if (tag.contains("Scan") || tag.contains("Scanner")) {
            return "【扫描】";
        }
        if (tag.contains("Sender") || tag.contains("Actions")) {
            return "【传输】";
        }
        if (tag.contains("Connect") || tag.contains("Heartbeat") || tag.contains("Gatt")
                || tag.contains("Retry") || tag.contains("Protocol")) {
            return "【连接】";
        }
        return "【其它】";
    }

    /** 是否输出 debug/warning 日志（由 BleSettings 的 ble.debugLog 控制；错误日志始终输出） */
    private static boolean debugEnabled() {
        try {
            return BleSettings.get().getBoolean("ble.debugLog");
        } catch (Throwable ignored) {
            return true; // BleSettings 未初始化/未声明等异常，兜底默认输出
        }
    }
}
