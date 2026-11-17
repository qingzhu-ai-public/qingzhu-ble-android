// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.adaptive;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 学习结果的持久化（SharedPreferences）。
 *
 * <p>存的是"这台手机学到的经验"——App 重启、杀进程、升级版本都还在，
 * 所以批量配置做得越多，结果就越准。
 */
final class AdaptiveStore {

    private static final String PREF_NAME = "qingzhu_ble_adaptive";

    private static final String K_INITIALIZED = "initialized";
    private static final String K_CONCURRENCY = "recommended";
    private static final String K_CEILING = "ceiling";
    private static final String K_RUNS = "runs";
    private static final String K_TIER = "device_tier";
    private static final String K_LAST_TOTAL = "last_total";
    private static final String K_LAST_OK = "last_ok";
    private static final String K_LAST_CONN_FAIL = "last_conn_fail";
    private static final String K_LAST_REASON = "last_reason";

    private final SharedPreferences prefs;

    AdaptiveStore(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    boolean isInitialized() {
        return prefs.getBoolean(K_INITIALIZED, false);
    }

    int concurrency(int fallback) {
        return prefs.getInt(K_CONCURRENCY, fallback);
    }

    int ceiling() {
        return prefs.getInt(K_CEILING, 0);
    }

    int runs() {
        return prefs.getInt(K_RUNS, 0);
    }

    String tierName() {
        return prefs.getString(K_TIER, null);
    }

    int lastTotal() {
        return prefs.getInt(K_LAST_TOTAL, 0);
    }

    int lastSucceeded() {
        return prefs.getInt(K_LAST_OK, 0);
    }

    int lastConnectFailed() {
        return prefs.getInt(K_LAST_CONN_FAIL, 0);
    }

    String lastReason() {
        return prefs.getString(K_LAST_REASON, "");
    }

    void save(int recommended, int ceiling, int runs, String tierName,
              int lastTotal, int lastSucceeded, int lastConnectFailed, String lastReason) {
        prefs.edit()
                .putBoolean(K_INITIALIZED, true)
                .putInt(K_CONCURRENCY, recommended)
                .putInt(K_CEILING, ceiling)
                .putInt(K_RUNS, runs)
                .putString(K_TIER, tierName)
                .putInt(K_LAST_TOTAL, lastTotal)
                .putInt(K_LAST_OK, lastSucceeded)
                .putInt(K_LAST_CONN_FAIL, lastConnectFailed)
                .putString(K_LAST_REASON, lastReason)
                .apply();
    }

    void clear() {
        prefs.edit().clear().apply();
    }
}
