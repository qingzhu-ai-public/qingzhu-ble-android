// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.sdk.core.scan;

import com.qingzhu.ble.sdk.core.common.QLog;
import com.qingzhu.ble.sdk.core.device.QDevice;
import com.qingzhu.ble.sdk.core.protocol.ProtocolPlugin;
import com.qingzhu.ble.sdk.core.protocol.ProtocolRegistry;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 协议无关的扫描引擎：收集原始广播，逐个协议问"是不是你的"，第一个认领的协议完成解析。
 *
 * 在逐条识别的基础上提供（对标旧架构 ScanManager 的生产能力，新架构此前缺失）：
 * 1. 按 stableId 去重：同一台设备反复广播只覆盖 byStableId 里的同一条目；
 * 2. 每秒批量回调 onScanResults（当前快照，<b>含已"离开范围"的设备</b>，每台带 lastSeenAt），
 *    上层不用自己去做去重/限频；
 * 3. ScanConfig 过滤（协议白名单 + stableId 白名单）；
 * 4. 超时自动停止 + onScanStopped 最终回调（含手动停止）。
 *
 * "离开范围"不删除设备：只触发一次 onDeviceLeftRange 语义事件，设备留在快照里由 UI 置灰。
 * 设备真正被移除只在手动 clear() 或扫描停止。
 *
 * 契约（不可破坏）：ScanCache.update() 保持在每条广播实时执行——
 * ConnectionPool"断开→等设备重新广播→重连"的 300ms 轮询依赖它，批量回调只影响上层 UI。
 *
 * 静态共享线程池（daemon）：扫描引擎实例随每次 startScan 创建，线程池不能跟着实例走，否则泄漏。
 */
public final class ScanEngine {

    private static final String TAG = "ScanEngine";
    private static final long REPORT_INTERVAL_MS = 1_000;
    private static final ScheduledExecutorService TIMER =
            new ScheduledThreadPoolExecutor(1, r -> {
                Thread t = new Thread(r, "qingzhu-scan-engine");
                t.setDaemon(true);
                return t;
            });

    private final ConcurrentHashMap<String, QDevice> byStableId = new ConcurrentHashMap<>();
    /** 每台设备"当前是否在范围内"——只在状态迁移时触发 onDeviceLeftRange，绝不删除设备 */
    private final ConcurrentHashMap<String, Boolean> inRange = new ConcurrentHashMap<>();
    /** 本轮扫描累计收到的广播条数（进入引擎识别的）——周期摘要日志用，诊断"扫描是否还活着" */
    private final java.util.concurrent.atomic.AtomicLong advCounter =
            new java.util.concurrent.atomic.AtomicLong();
    private final ScanListener listener;
    private final ScanConfig config;
    /**
     * 引擎停止（超时/手动）后的联动动作：由 QingzhuBle 注册，用于停掉平台扫描器。
     * 回传 self——门面侧用 "当前引擎 == self" 判断后再清引用，防止旧引擎的异步停止
     * 回调误杀新一轮扫描（快速 startScan(A) → startScan(B) 时 A 的超时回调后到）。
     */
    private final java.util.function.Consumer<ScanEngine> onStoppedCallback;
    private volatile boolean running = false;
    private int reportTick = 0;
    private ScheduledFuture<?> reportTask;
    private ScheduledFuture<?> timeoutTask;
    private ScheduledFuture<?> rangeSweepTask;

    public ScanEngine(ScanListener listener, ScanConfig config,
                      java.util.function.Consumer<ScanEngine> onStoppedCallback) {
        this.listener = listener;
        this.config = config != null ? config : ScanConfig.builder().build();
        this.onStoppedCallback = onStoppedCallback;
    }

    public void start() {
        if (running) return;
        running = true;
        byStableId.clear();
        inRange.clear();
        advCounter.set(0);
        reportTask = TIMER.scheduleAtFixedRate(this::report,
                REPORT_INTERVAL_MS, REPORT_INTERVAL_MS, TimeUnit.MILLISECONDS);
        rangeSweepTask = TIMER.scheduleAtFixedRate(this::checkRangeTransitions,
                REPORT_INTERVAL_MS, REPORT_INTERVAL_MS, TimeUnit.MILLISECONDS);
        if (config.getTimeoutMs() > 0) {
            timeoutTask = TIMER.schedule(this::stop, config.getTimeoutMs(), TimeUnit.MILLISECONDS);
        }
        QLog.d(TAG, "扫描引擎已启动 timeout=" + config.getTimeoutMs() + "ms"
                + " filterProtocols=" + config.getFilterProtocols().size()
                + " filterStableIds=" + config.getFilterStableIds().size());
    }

    /** 停止扫描（手动或超时），触发 onScanStopped 最终回调与 onStopped 联动。幂等。 */
    public void stop() {
        if (!running) return;
        running = false;
        if (reportTask != null) reportTask.cancel(false);
        if (rangeSweepTask != null) rangeSweepTask.cancel(false);
        if (timeoutTask != null) timeoutTask.cancel(false);
        if (listener != null) {
            listener.onScanStopped(new ArrayList<>(byStableId.values()));
        }
        if (onStoppedCallback != null) {
            onStoppedCallback.accept(this);
        }
        QLog.d(TAG, "扫描引擎已停止，最终设备数=" + byStableId.size());
    }

    /**
     * 平台层上报扫描失败（前置检查不通过 / 系统扫描失败且重试耗尽）：
     * 失败原因透传给调用方（onScanFailed），并终止本轮扫描——平台扫描已放弃，
     * 引擎继续跑只会是"永远没有新数据"的空转。幂等：引擎已停时忽略。
     */
    public void onPlatformScanFailed(int errorCode, String message) {
        if (!running) return;
        QLog.e(TAG, "扫描失败 errorCode=" + errorCode + " message=" + message + "，终止本轮扫描");
        if (listener != null) {
            listener.onScanFailed(errorCode, message);
        }
        stop();
    }

    /** 每条广播到达时调用（核心逻辑：过滤 + 识别 + 去重 + 喂缓存） */
    public void onScanResult(ScanRecord record, String deviceName) {
        if (!running) return;
        advCounter.incrementAndGet();
        long now = System.currentTimeMillis();
        // 同一广播会话内设备的 bleMac 不变（TXPGGP 从端是 Android，断开重播才换 MAC）：
        // 即使本帧不是"身份帧"（内容帧无法识别、不携带 deviceMac），只要这个 bleMac
        // 之前被识别过，就刷新它的"最后见到"时间——否则轮播设备（身份帧稀疏、内容帧密集）
        // 会被误判"离开范围"（表现为 UI 反复置灰）。设备仍保留在快照里，不删除。
        QDevice bound = ScanCache.getInstance().findByMac(record.getMac());
        if (bound != null) {
            QDevice existing = byStableId.get(bound.getStableId());
            if (existing != null) {
                existing.setLastSeenAt(now);
            }
        }
        for (ProtocolPlugin plugin : ProtocolRegistry.all()) {
            if (config.shouldFilterProtocol(plugin.protocolType())) continue;
            QDevice device = new QDevice(record.getMac(), deviceName, record.getRssi(), plugin.protocolType());
            if (!plugin.recognizer().recognize(record, deviceName, device)) continue;
            // stableId 由 SDK 统一分配（身份证风格：16 位大写字母+数字）。
            // 身份源 = 协议声明的 identityKey 对应的 metadata（未声明 = bleMac）；
            // 哈希前拼协议 ID，保证跨协议身份值不撞 key
            String idKey = plugin.identityKey();
            String identityValue = idKey != null
                    ? device.getMetadata(idKey, record.getMac())
                    : record.getMac();
            // stableId 是确定性哈希，同一台设备反复广播时直接复用扫描缓存里的结果，
            // 避免每条广播都做 SHA-256 + base36（扫描路径最热的计算）
            device.setStableId(bound != null ? bound.getStableId() : generateStableId(plugin.protocolType().getId(), identityValue));
            if (config.shouldFilterDevice(device.getStableId())) continue;
            device.setLastSeenAt(now);
            QDevice previous = byStableId.get(device.getStableId());
            if (previous != null) {
                // 同 stableId 设备已存在：用本次广播的新对象替换旧对象，而不是把新字段合并进旧对象。
                // QDevice.mac 是 final——若复用旧对象，TXPGGP 断开后地址轮换，mac 会永远停在首次发现的旧地址，
                // ScanCache 随之写回旧地址，重寻址拿到旧地址去连，connectGatt 无响应。
                // 旧版即整体 put 覆盖，地址随广播更新。此处保留"多帧字段共存"：旧对象里新帧没解析出的字段补进来。
                carryOverMetadata(device, previous);
                if (device.getName() == null) {
                    device.setName(previous.getName());
                }
            }
            byStableId.put(device.getStableId(), device);
            if (previous == null) {
                inRange.put(device.getStableId(), true);
                if (listener != null) {
                    listener.onDeviceEnteredRange(device);
                }
            }
            // 契约：缓存必须逐条广播实时更新（ConnectionPool 的重连轮询依赖它），不能挪进批量任务
            ScanCache.getInstance().update(device);
            return;
        }
        // 【排查埋点】未被任何协议认领的广播，两类打原始帧（按 mac+payload 去重防刷屏）：
        // 1) 带任一已注册协议声明的厂商数据——"设备在广播但识别器不认领"（帧格式变化）；
        // 2) MAC 已被身份帧绑定过（同一广播会话内）——"同设备的非身份帧"，看里面有没有可归属字段。
        byte[] knownMfrData = findRegisteredMfrData(record);
        boolean hasKnownMfr = knownMfrData != null && knownMfrData.length > 0;
        QDevice boundMac = ScanCache.getInstance().findByMac(record.getMac());
        if (hasKnownMfr || boundMac != null) {
            String hex = toHex(record.getRawScanRecord());
            String key = record.getMac() + "|" + hex;
            String prev = lastUnrecognizedPayloadByMac.put(key, "");
            if (prev == null) {
                QLog.d(TAG, "未识别广播 mac=" + record.getMac() + " rssi=" + record.getRssi()
                        + " name=" + deviceName
                        + " mfr=" + (hasKnownMfr ? toHex(knownMfrData) : "无")
                        + " raw=" + hex);
            }
        }
    }

    /** 取广播里首个命中「已注册协议声明的厂商 ID」的数据；都没有则返回 null */
    private byte[] findRegisteredMfrData(ScanRecord record) {
        for (ProtocolPlugin p : ProtocolRegistry.all()) {
            int[] ids = p.scanManufacturerIds();
            if (ids == null) {
                continue;
            }
            for (int id : ids) {
                byte[] d = record.getManufacturerData(id);
                if (d != null && d.length > 0) {
                    return d;
                }
            }
        }
        return null;
    }

    /** mac -> 最近一次未识别广播的 payload hex（去重用） */
    private final ConcurrentHashMap<String, String> lastUnrecognizedPayloadByMac = new ConcurrentHashMap<>();

    /**
     * 把旧设备里、本次广播未解析出的 metadata 补进新设备（新帧有值则覆盖，无值保留旧值）。
     * 设备轮换广播多种帧，每种帧解析出不同字段，必须共存。
     */
    private void carryOverMetadata(QDevice fresh, QDevice previous) {
        for (Map.Entry<String, Object> e : previous.getAllMetadata().entrySet()) {
            if (fresh.getMetadata(e.getKey()) == null) {
                fresh.putMetadata(e.getKey(), e.getValue());
            }
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }

    /**
     * 周期任务：检测"在范围内 ↔ 离开范围"的状态迁移，只在迁移瞬间触发一次
     * {@code onDeviceLeftRange} 语义事件——<b>不删除设备</b>。
     *
     * <p>设备从快照里被移除，只发生在手动 clear() 或扫描停止。是否"离开"只做语义通知，
     * UI 据此置灰/恢复（对齐 nRF Connect 的"只置灰不删除"），而不是把设备当不存在。
     */
    private void checkRangeTransitions() {
        if (!running) return;
        long now = System.currentTimeMillis();
        long threshold = config.getLeaveRangeMs();
        for (QDevice device : byStableId.values()) {
            String id = device.getStableId();
            boolean currentlyInRange = now - device.getLastSeenAt() <= threshold;
            Boolean wasInRange = inRange.get(id);
            if (wasInRange == null) {
                inRange.put(id, currentlyInRange);
                continue;
            }
            if (wasInRange && !currentlyInRange) {
//                QLog.d(TAG, "设备离开范围（静默超 " + threshold + "ms）mac=" + device.getMac()
//                        + " stableId=" + id);
                if (listener != null) {
                    listener.onDeviceLeftRange(device);
                }
            }
            inRange.put(id, currentlyInRange);
        }
    }

    /** 周期摘要（每 30 秒一条）：诊断"扫描是否还活着"——收到广播数持续增长 = 系统回调正常 */
    private void report() {
        if (!running) return;
        reportTick++;
        if (reportTick % 30 == 0) {
            QLog.d(TAG, "扫描运行中：累计广播=" + advCounter.get()
                    + "，快照设备数=" + byStableId.size());
        }
        ScanListener l = listener;
        if (l != null && !byStableId.isEmpty()) {
            l.onScanResults(new ArrayList<>(byStableId.values()));
        }
    }

    /**
     * 当前扫描结果快照（已按 stableId 去重，含已"离开范围"的设备——每台带 lastSeenAt，
     * 调用方自行用 {@link QDevice#isInRange(long)} 判定置灰/删除）。
     */
    public java.util.List<QDevice> getResults() {
        return new ArrayList<>(byStableId.values());
    }

    /**
     * 生成 stableId（身份证风格）：对 "协议ID:身份值" 做 SHA-256 → base36，
     * 定长 16 位大写字母+数字（如 "3F9K2M7Q1A8C5D2E"）。
     *
     * <p>设计要点：
     * 1. <b>确定性</b>——同一台设备无论何时何地扫描，算出的 ID 恒定（不掺时间/随机数），
     *    去重、扫描缓存、断连重连、跨 App 安装重装全部依赖这一点；
     * 2. <b>全局唯一</b>——哈希输入带协议 ID，不同协议身份值相同也绝不撞 key；
     *    80bit 哈希空间，实际设备量级下碰撞概率可忽略；
     * 3. <b>不泄露身份源</b>——对外呈现不暴露设备 MAC 等原始值（原始值仍在 metadata 里）。
     *
     * <p>公开：供 {@code FindQuery.byDeviceMac} 等"后端只有稳定身份、没有扫描结果"的入口
     * 用同一套算法算出 stableId，保证与扫描侧一致。
     */
    public static String generateStableId(String protocolId, String identityValue) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(
                    (protocolId + ":" + identityValue).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            // 取前 10 字节（80bit）→ 无符号大整数 → base36（0-9a-z）→ 定长 16 位大写
            byte[] prefix = java.util.Arrays.copyOf(digest, 10);
            String base36 = new java.math.BigInteger(1, prefix).toString(36).toUpperCase();
            StringBuilder sb = new StringBuilder("0000000000000000");
            sb.replace(16 - base36.length(), 16, base36);
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            // SHA-256 在所有 Java 平台必然存在，理论不可达；兜底退回原始标识保证功能不中断
            return (protocolId + ":" + identityValue).toUpperCase();
        }
    }

    public void clear() {
        byStableId.clear();
        inRange.clear();
    }

    public boolean isRunning() {
        return running;
    }
}
