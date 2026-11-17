// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.protocol.generic.action;

import com.qingzhu.ble.sdk.core.common.QLog;
import com.qingzhu.ble.sdk.core.connect.GattClient;
import com.qingzhu.ble.sdk.core.session.Session;
import com.qingzhu.protocol.generic.GenericProtocolPlugin;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 连接就绪后的能力协商：发 get_info，从端返回设备能力 JSON，解析进 Session 供传输层动态调整。
 *
 * <p>这是「连接流程」的一部分（由 {@code GenericProtocolPlugin.onGattReady} 触发），
 * 与收发指令 / OTA / 心跳无关，故独立成类。
 *
 * <p>协商失败（超时 / 发送失败）静默用默认能力，不阻塞连接流程。
 */
public final class CapabilityNegotiator {

    private static final String TAG = "CapabilityNegotiator";

    /** 能力协商指令：主端发送该文本，从端返回设备能力 JSON */
    public static final String CMD_GET_INFO = "get_info";
    /** 能力 key：最大 MTU（int），决定分片大小 */
    public static final String CAP_MAX_MTU = "cap.maxMtu";
    /** 能力 key：是否支持 2M PHY（boolean），支持则请求 2M PHY 翻倍吞吐 */
    public static final String CAP_PHY2M = "cap.phy2M";
    /** 能力 key：接收缓冲大小（int），决定滑动窗口 */
    public static final String CAP_RX_BUFFER = "cap.rxBufferBytes";
    /** 能力 key：是否支持无响应写（boolean） */
    public static final String CAP_WRITE_NO_RESPONSE = "cap.writeNoResponse";
    /** 能力 key：是否支持心跳探活（boolean）——设备级能力，主端据此决定是否发心跳 */
    public static final String CAP_HEARTBEAT = "cap.heartbeat";
    /** 能力 key：设备最大并发连接数（int，0=未知/不限）——主端据此判断是否超限 */
    public static final String CAP_MAX_CONNECTIONS = "cap.maxConnections";

    /** 能力协商应答超时（毫秒） */
    private static final long NEGOTIATE_TIMEOUT_MS = 3000;

    private static final ScheduledExecutorService TIMER =
            new ScheduledThreadPoolExecutor(1, r -> {
                Thread t = new Thread(r, "generic-cap-negotiate");
                t.setDaemon(true);
                return t;
            });

    private CapabilityNegotiator() {
    }

    /**
     * 发 get_info 协商能力，拿到结果后回调 onDone（无论成功失败都回调，不阻塞连接流程）。
     */
    public static void negotiateCapabilities(Session session, GattClient client, Runnable onDone) {
        AtomicBoolean done = new AtomicBoolean(false);
        client.setNotificationListener((uuid, value) -> {
            if (!GenericProtocolPlugin.CHAR_NOTIFY_UUID.equalsIgnoreCase(uuid) || done.get()) {
                return;
            }
            if (done.compareAndSet(false, true)) {
                client.setNotificationListener(null);
                parseAndStoreCapabilities(session, value);
                // 2M PHY 请求已上移到「连接就绪后」统一执行（AndroidGattClient.requestHighPriorityDelayed），
                // 那里时机更早（发现服务/MTU 之后立刻），此处不再重复请求。
                onDone.run();
            }
        });
        TIMER.schedule(() -> {
            if (done.compareAndSet(false, true)) {
                client.setNotificationListener(null);
                QLog.w(TAG, "能力协商超时，使用默认能力");
                onDone.run();
            }
        }, NEGOTIATE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        client.write(GenericProtocolPlugin.SERVICE_UUID, GenericProtocolPlugin.CHAR_WRITE_UUID,
                CMD_GET_INFO.getBytes(StandardCharsets.UTF_8), (ok, err, msg) -> {
                    if (!ok && done.compareAndSet(false, true)) {
                        client.setNotificationListener(null);
                        QLog.w(TAG, "能力协商请求发送失败: " + msg);
                        onDone.run();
                    }
                });
    }

    /** 解析能力 JSON 写入 Session（解析失败静默忽略，用默认能力兜底） */
    private static void parseAndStoreCapabilities(Session session, byte[] value) {
        try {
            String json = new String(value, StandardCharsets.UTF_8);
            org.json.JSONObject info = new org.json.JSONObject(json);
            session.put(CAP_MAX_MTU, info.optInt("maxMtu", 247));
            session.put(CAP_PHY2M, info.optBoolean("phy2M", false));
            session.put(CAP_RX_BUFFER, info.optInt("rxBufferBytes", 4096));
            session.put(CAP_WRITE_NO_RESPONSE, info.optBoolean("writeNoResponse", true));
            session.put(CAP_HEARTBEAT, info.optBoolean("heartbeat", false));
            session.put(CAP_MAX_CONNECTIONS, info.optInt("maxConnections", 0));
            QLog.d(TAG, "[" + session.getMac() + "] 能力协商成功: " + json);
        } catch (Exception e) {
            QLog.w(TAG, "[" + session.getMac() + "] 解析能力信息失败: " + e.getMessage());
        }
    }
}
