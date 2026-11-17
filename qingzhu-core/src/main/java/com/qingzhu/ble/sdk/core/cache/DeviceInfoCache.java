// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.sdk.core.cache;

import android.content.Context;

import com.qingzhu.ble.sdk.core.common.QLog;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 设备「恒定字段」本地缓存（SDK 内部私有存储，<b>不依赖宿主的 SharedPreferences</b>，跨平台无感）。
 *
 * <p>背景：V3 设备的<b>信息帧</b>（Device Info）广播慢（约每分钟一次），但里面的字段恒定
 *（面数 / 屏幕ID / 固件 / 产品ID / 双面类型）；<b>状态帧</b>（Tag Status）广播频繁，但不带这些字段。
 * 客户端一打开 APP 就想操作设备时，往往只收到状态帧、要等信息帧才能显示完整设备。
 *
 * <p>本缓存把信息帧解析出的恒定字段按 bleMac 存到本地，收到状态帧时从缓存补全，设备立即完整显示；
 * 收到信息帧时同步更新缓存。这样冷启动扫描更快；缓存未命中（全新设备）自然回退「等信息帧」，不影响正确性。
 *
 * <p>持久化到 {@code context.getFilesDir()/v3_device_info_cache.json}，内存 500 条上限，超限按
 * 最后更新时间淘汰最旧；超过 {@link #EXPIRE_MS} 未更新的条目在启动加载时清理。
 */
public final class DeviceInfoCache {

    private static final String TAG = "DeviceInfoCache";
    private static final String FILE_NAME = "v3_device_info_cache.json";
    private static final int MAX_ENTRIES = 500;
    private static final long EXPIRE_MS = 30L * 24 * 3600 * 1000; // 30 天

    private static volatile DeviceInfoCache instance;

    /** 恒定字段条目 */
    public static final class Entry {
        public int sides;
        public int screenId;
        public String firmware = "";
        public String productId = "";
        public int doubleScreenType = -1; // -1 = 无（单面/五面）
        /** DFH 协议版本（"1"/"2"）：连接读特征判定后缓存，跨启动复用，避免每次冷启动都要读特征（部分设备读特征失败会断连重连） */
        public String dfhVersion = "";
        public long lastSeen;
    }

    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();
    private final File file;

    private DeviceInfoCache(Context context) {
        this.file = new File(context.getFilesDir(), FILE_NAME);
        load();
    }

    public static void init(Context context) {
        if (instance == null) {
            synchronized (DeviceInfoCache.class) {
                if (instance == null) {
                    instance = new DeviceInfoCache(context.getApplicationContext());
                }
            }
        }
    }

    public static DeviceInfoCache get() {
        return instance;
    }

    /** 读某台设备的恒定字段缓存；未命中返回 null */
    public synchronized Entry get(String mac) {
        if (mac == null) {
            return null;
        }
        return entries.get(normalizeMac(mac));
    }

    /** 写某台设备的恒定字段缓存（有则覆盖，并刷新 lastSeen） */
    public synchronized void put(String mac, int sides, int screenId, String firmware,
                                 String productId, int doubleScreenType) {
        if (mac == null || sides <= 0) {
            return;
        }
        String key = normalizeMac(mac);
        Entry e = entries.get(key);
        if (e == null) {
            e = new Entry();
            entries.put(key, e);
        }
        e.sides = sides;
        e.screenId = screenId;
        e.firmware = firmware != null ? firmware : "";
        e.productId = productId != null ? productId : "";
        e.doubleScreenType = doubleScreenType;
        e.lastSeen = System.currentTimeMillis();
        evictIfNeeded();
        persistAsync();
    }

    /** 读某台设备已判定的 DFH 协议版本；未命中返回 null */
    public synchronized String getDfhVersion(String mac) {
        if (mac == null) {
            return null;
        }
        Entry e = entries.get(normalizeMac(mac));
        return e == null ? null : (e.dfhVersion.isEmpty() ? null : e.dfhVersion);
    }

    /** 写某台设备的 DFH 协议版本（"1"/"2"）。与 {@link #put} 独立：即使该设备还没解析出信息帧
     *  （sides=0、无条目），也单独建条目记录版本——因为「连接读特征判定」发生在扫描信息帧之前。 */
    public synchronized void putDfhVersion(String mac, String version) {
        if (mac == null || version == null || version.isEmpty()) {
            return;
        }
        String key = normalizeMac(mac);
        Entry e = entries.get(key);
        if (e == null) {
            e = new Entry();
            entries.put(key, e);
        }
        e.dfhVersion = version;
        e.lastSeen = System.currentTimeMillis();
        evictIfNeeded();
        persistAsync();
    }

    /** 超过 500 条时，淘汰最后更新时间最旧的 */
    private void evictIfNeeded() {
        if (entries.size() <= MAX_ENTRIES) {
            return;
        }
        // 找出最旧的 key 并移除（线性扫描，500 条量级足够）
        String oldest = null;
        long oldestTs = Long.MAX_VALUE;
        for (Map.Entry<String, Entry> e : entries.entrySet()) {
            if (e.getValue().lastSeen < oldestTs) {
                oldestTs = e.getValue().lastSeen;
                oldest = e.getKey();
            }
        }
        if (oldest != null) {
            entries.remove(oldest);
        }
    }

    /** 启动加载：读文件 + 清理过期条目 */
    private void load() {
        if (file == null || !file.exists()) {
            return;
        }
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[(int) file.length()];
            int read = in.read(buf);
            if (read <= 0) {
                return;
            }
            JSONObject root = new JSONObject(new String(buf, 0, read, StandardCharsets.UTF_8));
            long now = System.currentTimeMillis();
            Iterator<String> keys = root.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                try {
                    JSONObject o = root.getJSONObject(key);
                    long lastSeen = o.optLong("lastSeen", 0);
                    if (now - lastSeen > EXPIRE_MS) {
                        continue; // 过期跳过
                    }
                    Entry e = new Entry();
                    e.sides = o.optInt("sides");
                    e.screenId = o.optInt("screenId");
                    e.firmware = o.optString("firmware", "");
                    e.productId = o.optString("productId", "");
                    e.doubleScreenType = o.optInt("doubleScreenType", -1);
                    e.dfhVersion = o.optString("dfhVersion", "");
                    e.lastSeen = lastSeen;
                    entries.put(key, e);
                } catch (Exception ignored) {
                }
            }
            QLog.i(TAG, "缓存加载完成：共 " + entries.size() + " 条");
        } catch (Exception e) {
            QLog.w(TAG, "缓存加载失败（忽略，回退无缓存）: " + e.getMessage());
        }
    }

    /** 异步写回文件（避免扫描线程 IO 抖动） */
    private void persistAsync() {
        final Map<String, Entry> snapshot = new LinkedHashMap<>(entries);
        new Thread(() -> {
            try {
                JSONObject root = new JSONObject();
                for (Map.Entry<String, Entry> e : snapshot.entrySet()) {
                    JSONObject o = new JSONObject();
                    o.put("sides", e.getValue().sides);
                    o.put("screenId", e.getValue().screenId);
                    o.put("firmware", e.getValue().firmware);
                    o.put("productId", e.getValue().productId);
                    o.put("doubleScreenType", e.getValue().doubleScreenType);
                    o.put("dfhVersion", e.getValue().dfhVersion);
                    o.put("lastSeen", e.getValue().lastSeen);
                    root.put(e.getKey(), o);
                }
                try (FileOutputStream out = new FileOutputStream(file)) {
                    out.write(root.toString().getBytes(StandardCharsets.UTF_8));
                }
            } catch (Exception e) {
                QLog.w(TAG, "缓存写回失败（忽略）: " + e.getMessage());
            }
        }, "DeviceInfoCache-persist").start();
    }

    private static String normalizeMac(String mac) {
        return mac.replace(":", "").toUpperCase(java.util.Locale.US);
    }
}
