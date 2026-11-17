// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.sdk.core.scan;

import com.qingzhu.ble.sdk.core.device.QDevice;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 扫描缓存：ScanEngine 每识别出一台设备就自动存进来（无需接入方做任何事），
 * 提供"按稳定标识查当前广播地址"的查询能力。
 *
 * 缓存按协议声明的稳定标识（{@link QDevice#getStableId()}）组织——地址轮换的设备
 * （如 轮换地址协议：广播内容一变 BLE 地址就变，但其实是同一台设备）在这里只占一个条目，
 * 始终能查到它当前最新的广播地址。
 *
 * 这是"智能扫描重寻址"这类场景的默认数据源。
 */
public final class ScanCache {

    public interface AddressCallback {
        void onFound(String address);

        void onTimeout();
    }

    private static final ScanCache INSTANCE = new ScanCache();
    private static final long POLL_INTERVAL_MS = 500;

    private final ConcurrentHashMap<String, QDevice> byStableId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> macToStableId = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "qingzhu-scan-cache");
        t.setDaemon(true);
        return t;
    });

    private ScanCache() {
    }

    public static ScanCache getInstance() {
        return INSTANCE;
    }

    /** ScanEngine 每识别出一台设备时调用，持续刷新缓存（同一台设备跨地址轮换只保留最新一条） */
    public void update(QDevice device) {
        byStableId.put(device.getStableId(), device);
        macToStableId.put(device.getMac(), device.getStableId());
    }

    public QDevice get(String stableId) {
        return byStableId.get(stableId);
    }

    /**
     * 按 stableId 查设备，仅当它在 {@code thresholdMs} 内被扫到过（地址新鲜）才返回，否则返回 null。
     *
     * <p>这是「新鲜地址闸门」的正确判据：光「缓存里有」不够——设备可能早就走远了但没断开
     *（缓存只在断开时删），拿过期地址硬连必失败。连接前用本方法过滤，地址不新鲜就等重新广播。
     */
    public QDevice getFresh(String stableId, long thresholdMs) {
        QDevice d = byStableId.get(stableId);
        return (d != null && d.isInRange(thresholdMs)) ? d : null;
    }

    /**
     * 按广播 mac 查最近一次识别的设备（含该设备已解析的全部 metadata）。
     * 典型用途：同一台设备交替广播"身份帧"和"数据帧"（如 Eddystone 的 UID+TLM）时，
     * 数据帧本身没有身份字段，可借此找回身份帧已建立的身份，避免同一设备出现两个身份。
     */
    public QDevice findByMac(String mac) {
        String stableId = macToStableId.get(mac);
        return stableId != null ? byStableId.get(stableId) : null;
    }

    public int size() {
        return byStableId.size();
    }

    public void clear() {
        byStableId.clear();
        macToStableId.clear();
    }

    /**
     * 移除一台设备的缓存记录（断开连接时调用：地址数据随即作废，重新连接必须等设备
     * 重新广播、被扫描引擎重新发现后才有效）。除"断开删除"外，连接前还会用
     * {@link #getFresh} 做时间维度的新鲜度过滤——「缓存里有没有」与「地址新不新鲜」双重把关。
     */
    public void remove(String stableId) {
        QDevice removed = byStableId.remove(stableId);
        if (removed != null) {
            macToStableId.entrySet().removeIf(e -> e.getValue().equals(stableId));
        }
    }

    /**
     * 按"稳定标识"查找设备当前的广播地址。
     * 缓存里已有则立即回调 onFound；没有则每 500ms 重查一次，直到 timeoutSec 超时回调 onTimeout。
     */
    public void findLatestAddress(String stableId, int timeoutSec, AddressCallback callback) {
        String hit = lookup(stableId);
        if (hit != null) {
            callback.onFound(hit);
            return;
        }
        long deadline = System.currentTimeMillis() + timeoutSec * 1000L;
        AtomicBoolean done = new AtomicBoolean(false);
        ScheduledFuture<?>[] future = new ScheduledFuture<?>[1];
        future[0] = timer.scheduleWithFixedDelay(() -> {
            String addr = lookup(stableId);
            if (addr != null && done.compareAndSet(false, true)) {
                future[0].cancel(false);
                callback.onFound(addr);
                return;
            }
            if (System.currentTimeMillis() >= deadline && done.compareAndSet(false, true)) {
                future[0].cancel(false);
                callback.onTimeout();
            }
        }, 0, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * 查找：只按缓存键（stableId）精确命中。
     * 旧版本有一个"遍历所有设备 metadata 值匹配"的兜底——已删除：键的写法已在全链路
     * 统一（stableId 由协议 stableId() 声明、ScanEngine 统一打标），兜底匹配存在
     * "不同设备的 metadata 值相同导致误命中"的风险，精确匹配足够且安全。
     */
    private String lookup(String stableId) {
        if (stableId == null) {
            return null;
        }
        QDevice direct = byStableId.get(stableId);
        return direct != null ? direct.getMac() : null;
    }
}
