// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.protocol.generic.action;

import com.qingzhu.ble.sdk.core.action.ActionHandler;
import com.qingzhu.ble.sdk.core.action.JsonResponse;
import com.qingzhu.ble.sdk.core.common.ErrorCode;
import com.qingzhu.ble.sdk.core.common.QLog;
import com.qingzhu.ble.sdk.core.connect.GattClient;
import com.qingzhu.ble.sdk.core.connect.HeartbeatManager;
import com.qingzhu.ble.sdk.core.session.Session;
import com.qingzhu.ble.sdk.core.settings.BleSettings;
import com.qingzhu.protocol.generic.GenericProtocolPlugin;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * OTA / 文件大块传输：按 MTU 切片 → 加 9 字节头 → 流水线批量下发 → 等从机收满回执。
 *
 * <p>分片格式（generic 测试约定，无加密）：[0]=0xAA, [1..4]=total(BE), [5..8]=index(BE), [9..]=data。
 * 文件模式先写元数据片（0xAC + 大小 + 类型 + 文件名）。收满回执 [0]=0xBB，与下发字节数/片数一致才算成功。
 */
public final class OtaTransfer {

    private static final String TAG = "OtaTransfer";

    /** OTA 分片魔数（区分普通 echo 数据 vs OTA 分片） */
    private static final byte OTA_MAGIC = (byte) 0xAA;
    /** OTA 收齐回执魔数（从机收满后 notify 回执 [0]=0xBB） */
    private static final byte OTA_ACK_MAGIC = (byte) 0xBB;
    /** 文件元数据片魔数（发文件内容前先发：文件名 + 类型 + 大小） */
    private static final byte FILE_META_MAGIC = (byte) 0xAC;
    /** 文件类型：图片 */
    private static final int FILE_TYPE_IMAGE = 0;
    /** 文件类型：视频 */
    private static final int FILE_TYPE_VIDEO = 1;
    /** 文件类型：其它文件 */
    private static final int FILE_TYPE_OTHER = 2;
    /** 停滞检测的周期检查间隔（毫秒）：watchdog 按这个频率检查「最后活跃时间」是否超阈值 */
    private static final long STALL_CHECK_INTERVAL_MS = 5000;

    private static final ScheduledExecutorService TIMER =
            new ScheduledThreadPoolExecutor(1, r -> {
                Thread t = new Thread(r, "generic-ota-transfer");
                t.setDaemon(true);
                return t;
            });

    private OtaTransfer() {
    }

    /** 入口：按 MTU 切片并下发。fileMode（fileName 非空）先写文件元数据片。 */
    public static void sendOtaFirmware(Session session, GattClient client, byte[] firmware,
                                       String fileName, ActionHandler.ActionCallback callback) {
        byte[][] chunks = split(firmware, resolveChunkSize(session));
        boolean fileMode = fileName != null && !fileName.isEmpty();
        QLog.d(TAG, "[" + session.getMac() + "] " + (fileMode ? "文件" : "OTA") + " 分片下发：大小="
                + firmware.length + " 分片数=" + chunks.length + (fileMode ? " 文件名=" + fileName : ""));
        AtomicBoolean done = new AtomicBoolean(false);
        // 停滞检测：进度/回执推进时刷新「最后活跃时间」，连续 stallTimeoutMs 无任何进展判链路卡死。
        // 不用「总时长」判超时——大文件传多久取决于数据量，只要在推进就不算停滞。
        final long stallTimeoutMs = BleSettings.getLong(session, "generic.transferStallTimeoutMs");
        final AtomicLong lastActiveAt = new AtomicLong(System.currentTimeMillis());
        final Runnable markActive = () -> {
            lastActiveAt.set(System.currentTimeMillis());
            // 传输有进展 = 设备活跃：同步刷新心跳活跃时间，避免大文件传输期间
            // 心跳误判「空闲」发探测、又因探测指令排在传输队列后发不出去而判离线断开。
            HeartbeatManager.get().notifyActive(session.getMac());
        };
        // 订阅 notify 收"收完回执"
        client.setNotificationListener((uuid, value) -> {
            if (!GenericProtocolPlugin.CHAR_NOTIFY_UUID.equalsIgnoreCase(uuid) || done.get()) {
                return;
            }
            if (done.compareAndSet(false, true)) {
                client.setNotificationListener(null);
                QLog.d(TAG, "[" + session.getMac() + "] 收到回执 " + value.length + " 字节");
                callback.onResult(parseOtaAck(value, firmware.length, chunks.length));
            }
        });
        // 停滞 watchdog：周期检查「自上次活跃以来」是否超过阈值（传输停滞 / 从机不回执都会触发）
        TIMER.scheduleAtFixedRate(() -> {
            if (done.get()) {
                return;
            }
            long idleMs = System.currentTimeMillis() - lastActiveAt.get();
            if (idleMs > stallTimeoutMs) {
                if (done.compareAndSet(false, true)) {
                    client.setNotificationListener(null);
                    QLog.w(TAG, "[" + session.getMac() + "] 传输停滞：连续 " + idleMs + "ms 无进展");
                    callback.onResult(JsonResponse.error(ErrorCode.EXECUTION_FAILED,
                            "传输停滞（连续 " + idleMs + "ms 无进展）"));
                }
            }
        }, STALL_CHECK_INTERVAL_MS, STALL_CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);

        if (fileMode) {
            writeFileMetaThenChunks(session, client, chunks, fileName, firmware.length, done, callback, markActive);
        } else {
            writeChunksBulk(session, client, chunks, done, callback, markActive);
        }
    }

    /** 发文件：先写文件元数据片（0xAC + 大小 + 类型 + 文件名），再走数据分片 */
    private static void writeFileMetaThenChunks(Session session, GattClient client, byte[][] chunks,
                                                String fileName, int totalBytes, AtomicBoolean done,
                                                ActionHandler.ActionCallback callback, Runnable markActive) {
        byte[] meta = buildFileMetaPacket(fileName, totalBytes);
        client.write(GenericProtocolPlugin.SERVICE_UUID, GenericProtocolPlugin.CHAR_WRITE_UUID, meta,
                (ok, err, msg) -> {
                    if (done.get()) {
                        return;
                    }
                    if (!ok) {
                        if (done.compareAndSet(false, true)) {
                            client.setNotificationListener(null);
                            callback.onResult(JsonResponse.error(ErrorCode.EXECUTION_FAILED,
                                    "文件元数据写入失败: " + msg));
                        }
                        return;
                    }
                    markActive.run(); // 元数据写入成功 = 有进展，刷新活跃时间
                    writeChunksBulk(session, client, chunks, done, callback, markActive);
                });
    }

    /** 文件元数据片：[0]=0xAC, [1..4]=totalBytes(BE), [5]=type, [6..]=fileName(UTF-8) */
    private static byte[] buildFileMetaPacket(String fileName, int totalBytes) {
        byte[] nameBytes = fileName.getBytes(StandardCharsets.UTF_8);
        byte[] p = new byte[6 + nameBytes.length];
        p[0] = FILE_META_MAGIC;
        writeInt32BE(p, 1, totalBytes);
        p[5] = (byte) inferFileType(fileName);
        System.arraycopy(nameBytes, 0, p, 6, nameBytes.length);
        return p;
    }

    /** 按扩展名推断文件类型：图片 0 / 视频 1 / 其它 2 */
    private static int inferFileType(String fileName) {
        String lower = fileName.toLowerCase(java.util.Locale.US);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                || lower.endsWith(".gif") || lower.endsWith(".bmp") || lower.endsWith(".webp")) {
            return FILE_TYPE_IMAGE;
        }
        if (lower.endsWith(".mp4") || lower.endsWith(".3gp") || lower.endsWith(".mkv")
                || lower.endsWith(".webm") || lower.endsWith(".avi") || lower.endsWith(".mov")
                || lower.endsWith(".m4v")) {
            return FILE_TYPE_VIDEO;
        }
        return FILE_TYPE_OTHER;
    }

    /**
     * 用 writeBulk 流水线一次性下发所有分片：滑动窗口 + 无响应写，不等逐片 ACK，
     * 充分利用每个连接事件发多个包（大块传输提速关键）。丢包由从端收满后的回执校验兜底。
     */
    private static void writeChunksBulk(Session session, GattClient client, byte[][] chunks,
                                        AtomicBoolean done, ActionHandler.ActionCallback callback,
                                        Runnable markActive) {
        java.util.List<byte[]> packets = new java.util.ArrayList<>(chunks.length);
        int sum = 0;
        for (int i = 0; i < chunks.length; i++) {
            sum += chunks[i].length;
            packets.add(buildOtaPacket(chunks[i], i, chunks.length));
        }
        final int totalBytes = sum;
        final int chunkSize = chunks.length > 0 ? chunks[0].length : 0;
        final int[] lastPercent = {-1};
        client.writeBulk(GenericProtocolPlugin.SERVICE_UUID, GenericProtocolPlugin.CHAR_WRITE_UUID, packets,
                (sent, totalPackets) -> {
                    // 逐片上报发送进度（0~99%，100 留给「从机回执确认」）；只在百分比变化时上报，避免刷屏
                    if (done.get()) {
                        return;
                    }
                    markActive.run(); // 每片写完 = 有进展，刷新「最后活跃时间」，供停滞检测用
                    int percent = (int) (sent * 99L / totalPackets);
                    if (percent != lastPercent[0]) {
                        lastPercent[0] = percent;
                        // 上报字节数（近似到片），速率由上层按时间戳推导
                        int sentBytes = Math.min(sent * chunkSize, totalBytes);
                        callback.onProgress(sentBytes, totalBytes);
                    }
                },
                (ok, code, msg) -> {
                    if (done.get()) {
                        return;
                    }
                    if (!ok) {
                        if (done.compareAndSet(false, true)) {
                            client.setNotificationListener(null);
                            callback.onResult(JsonResponse.error(ErrorCode.EXECUTION_FAILED,
                                    "分片写入失败: " + msg));
                        }
                        return;
                    }
                    markActive.run(); // 全部分片写完 = 有进展（随后进入等回执阶段）
                    callback.onProgress(totalBytes, totalBytes); // 全部写完（100%），等从机 notify 回执
                });
    }

    /** 分片头：[0]=0xAA, [1..4]=total(BE), [5..8]=index(BE), [9..]=data */
    private static byte[] buildOtaPacket(byte[] data, int index, int total) {
        byte[] p = new byte[9 + data.length];
        p[0] = OTA_MAGIC;
        writeInt32BE(p, 1, total);
        writeInt32BE(p, 5, index);
        System.arraycopy(data, 0, p, 9, data.length);
        return p;
    }

    private static void writeInt32BE(byte[] dst, int offset, int value) {
        dst[offset] = (byte) (value >> 24);
        dst[offset + 1] = (byte) (value >> 16);
        dst[offset + 2] = (byte) (value >> 8);
        dst[offset + 3] = (byte) value;
    }

    private static int readInt32BE(byte[] src, int offset) {
        return ((src[offset] & 0xFF) << 24)
                | ((src[offset + 1] & 0xFF) << 16)
                | ((src[offset + 2] & 0xFF) << 8)
                | (src[offset + 3] & 0xFF);
    }

    /**
     * 解析从机 OTA 回执：[0]=0xBB, [1..4]=totalBytes(BE), [5..8]=chunkCount(BE)。
     * 与下发字节数 / 片数一致才算成功，否则报「回执不符」。
     */
    private static JsonResponse parseOtaAck(byte[] value, int sentBytes, int sentChunks) {
        if (value != null && value.length >= 9 && value[0] == OTA_ACK_MAGIC) {
            int ackedBytes = readInt32BE(value, 1);
            int ackedChunks = readInt32BE(value, 5);
            if (ackedBytes == sentBytes && ackedChunks == sentChunks) {
                return JsonResponse.ok("OTA_OK bytes=" + ackedBytes + " chunks=" + ackedChunks);
            }
            return JsonResponse.error(ErrorCode.EXECUTION_FAILED,
                    "OTA 回执不符：期望 bytes=" + sentBytes + "/chunks=" + sentChunks
                            + " 实收 bytes=" + ackedBytes + "/chunks=" + ackedChunks);
        }
        return JsonResponse.ok(GenericActions.toHex(value));
    }

    /** 按 MTU 切分原始字节为分片数据（不含 9 字节分片头） */
    private static byte[][] split(byte[] data, int chunkSize) {
        int total = Math.max(1, (data.length + chunkSize - 1) / chunkSize);
        byte[][] chunks = new byte[total][];
        for (int i = 0; i < total; i++) {
            int start = i * chunkSize;
            int len = Math.min(chunkSize, data.length - start);
            chunks[i] = new byte[len];
            if (len > 0) {
                System.arraycopy(data, start, chunks[i], 0, len);
            }
        }
        return chunks;
    }

    /** 根据协商到的 maxMtu 计算分片数据大小（分片头 9 字节 + ATT 头 3 字节，留余量） */
    private static int resolveChunkSize(Session session) {
        int maxMtu = session.get(CapabilityNegotiator.CAP_MAX_MTU, 247);
        return Math.max(20, maxMtu - 3 - 9);
    }
}
