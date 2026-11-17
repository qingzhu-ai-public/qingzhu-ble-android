// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.protocol.eddystone.action;

import com.qingzhu.ble.sdk.core.action.ActionHandler;
import com.qingzhu.ble.sdk.core.action.ActionRouter;
import com.qingzhu.ble.sdk.core.action.JsonResponse;
import com.qingzhu.ble.sdk.core.common.ErrorCode;
import com.qingzhu.ble.sdk.core.common.QLog;
import com.qingzhu.ble.sdk.core.connect.GattClient;
import com.qingzhu.ble.sdk.core.connect.SessionKeys;
import com.qingzhu.ble.sdk.core.session.Session;
import com.qingzhu.protocol.eddystone.EddystoneProtocolPlugin;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/**
 * Eddystone（模拟从机）指令收发实现。
 *
 * 下行：接入方的 JSON（如 {"action":"get_info"}）原样 UTF-8 写入命令特征值（报文小，单包直写）；
 * 上行：订阅状态特征值 notify -> 拼 JSON -> 收齐（能解析出 payload 字段）即回调结果。
 *
 * 与设备的约定：请求 {"action":"xxx"}，响应 {"action":"xxx","payload":{...}}。
 */
public final class EddystoneActions {

    private static final String TAG = "EddystoneActions";
    private static final int RESPONSE_TIMEOUT_MS = 5000;

    private static final ScheduledExecutorService TIMER =
            new ScheduledThreadPoolExecutor(1, r -> {
                Thread t = new Thread(r, "eddystone-action-timer");
                t.setDaemon(true);
                return t;
            });

    /** 每个设备一个接收缓冲区，多设备同时收包互不污染 */
    private static final ConcurrentHashMap<String, ByteArrayOutputStream> RESPONSE_BUFFERS = new ConcurrentHashMap<>();

    private EddystoneActions() {
    }

    public static void registerAll() {
        ActionRouter.register(EddystoneProtocolPlugin.TYPE_ID, (session, rawMessage, attachments, callback) -> {
            byte[] payload;
            try {
                // 只校验是合法 JSON 对象，内容不解析——协议层不关心里面有什么字段
                new org.json.JSONObject(rawMessage);
                payload = rawMessage.getBytes(StandardCharsets.UTF_8);
            } catch (Exception e) {
                callback.onResult(JsonResponse.error(ErrorCode.INVALID_JSON_FORMAT,
                        "消息必须是合法的 JSON 对象字符串: " + e.getMessage()));
                return;
            }
            sendCommand(session, payload, rawMessage, callback);
        });
    }

    private static void sendCommand(Session session, byte[] payload, String rawMessage,
                                    ActionHandler.ActionCallback callback) {
        GattClient client = session.get(SessionKeys.GATT_CLIENT);
        if (client == null) {
            callback.onResult(JsonResponse.error(ErrorCode.DEVICE_NOT_CONNECTED, "设备未连接"));
            return;
        }
        String mac = session.getMac();

        RESPONSE_BUFFERS.put(mac, new ByteArrayOutputStream());
        QLog.d(TAG, "[" + mac + "] 发送指令: " + rawMessage);

        // 先订阅 notify 再发送，避免应答快于订阅导致丢包
        java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean(false);
        client.setNotificationListener((notifyCharUuid, value) -> {
            if (!EddystoneProtocolPlugin.CHARACTERISTIC_STATUS_UUID.equalsIgnoreCase(notifyCharUuid) || done.get()) {
                return;
            }
            onPacketReceived(session, mac, value, client, callback, done);
        });

        // 整体响应超时兜底
        TIMER.schedule(() -> {
            if (done.compareAndSet(false, true)) {
                client.setNotificationListener(null);
                RESPONSE_BUFFERS.remove(mac);
                QLog.e(TAG, "[" + mac + "] 指令响应超时");
                callback.onResult(JsonResponse.error(ErrorCode.EXECUTION_FAILED, "指令响应超时"));
            }
        }, RESPONSE_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        client.write(EddystoneProtocolPlugin.SERVICE_UUID,
                EddystoneProtocolPlugin.CHARACTERISTIC_COMMAND_UUID, payload,
                (writeOk, writeErr, writeMsg) -> {
                    if (!writeOk && done.compareAndSet(false, true)) {
                        client.setNotificationListener(null);
                        RESPONSE_BUFFERS.remove(mac);
                        callback.onResult(JsonResponse.error(ErrorCode.EXECUTION_FAILED, "写入失败: " + writeMsg));
                    }
                });
    }

    private static void onPacketReceived(Session session, String mac, byte[] value, GattClient client,
                                         ActionHandler.ActionCallback callback,
                                         java.util.concurrent.atomic.AtomicBoolean done) {
        ByteArrayOutputStream buffer = RESPONSE_BUFFERS.get(mac);
        if (buffer == null) {
            return; // 已收完/超时被清理
        }
        buffer.write(value, 0, value.length);
        QLog.d(TAG, "[" + mac + "] 拼包中，当前缓冲区长度=" + buffer.size());

        String completeJson = tryParseComplete(buffer.toByteArray());
        if (completeJson == null) {
            return; // 还没收完
        }
        if (!done.compareAndSet(false, true)) {
            return; // 已被超时逻辑抢先
        }
        client.setNotificationListener(null);
        RESPONSE_BUFFERS.remove(mac);
        QLog.d(TAG, "[" + mac + "] 收到完整响应: " + completeJson);

        String payloadJson = extractPayload(completeJson);
        callback.onResult(JsonResponse.ok(payloadJson != null ? payloadJson : completeJson));
    }

    /** 能解析出带 payload 字段的 JSON 即视为收齐 */
    private static String tryParseComplete(byte[] data) {
        try {
            String s = new String(data, StandardCharsets.UTF_8).trim();
            JSONObject root = new JSONObject(s);
            return root.has("payload") ? s : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String extractPayload(String completeJson) {
        try {
            JSONObject root = new JSONObject(completeJson);
            return root.optJSONObject("payload") != null
                    ? root.getJSONObject("payload").toString() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
