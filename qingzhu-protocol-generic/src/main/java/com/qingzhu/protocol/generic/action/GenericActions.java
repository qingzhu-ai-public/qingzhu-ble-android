// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.protocol.generic.action;

import com.qingzhu.ble.sdk.core.action.ActionHandler;
import com.qingzhu.ble.sdk.core.action.ActionRouter;
import com.qingzhu.ble.sdk.core.action.JsonResponse;
import com.qingzhu.ble.sdk.core.common.ErrorCode;
import com.qingzhu.ble.sdk.core.common.QLog;
import com.qingzhu.ble.sdk.core.connect.GattClient;
import com.qingzhu.ble.sdk.core.connect.SessionKeys;
import com.qingzhu.ble.sdk.core.session.Session;
import com.qingzhu.protocol.generic.GenericProtocolPlugin;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * generic 协议的指令路由门面：把「发什么」分派到具体实现。
 *
 * <ul>
 *   <li>附件带 byte[]（key="data"）→ {@link OtaTransfer}（OTA / 文件大块传输）；</li>
 *   <li>{@link Heartbeat#COMMAND_HEARTBEAT} → {@link Heartbeat}（JSON 心跳）；</li>
 *   <li>其余 → {@link #sendEcho}（普通文本透传回显）。</li>
 * </ul>
 *
 * <p>能力协商见 {@link CapabilityNegotiator}（连接流程），大块传输见 {@link OtaTransfer}，
 * 心跳见 {@link Heartbeat}——本类只负责路由 + 最轻量的 echo。
 */
public final class GenericActions {

    private static final String TAG = "GenericActions";

    /** 附件 key 约定：{@code attachments.get("data")} 取到要透传/分片下发的原始字节 */
    public static final String ATTACHMENT_KEY_DATA = "data";
    /** 附件 key 约定：{@code attachments.get("fileName")} 带文件名时走「文件传输」模式（从端按类型展示） */
    public static final String ATTACHMENT_KEY_FILE_NAME = "fileName";
    /** 普通 echo 响应超时（毫秒） */
    private static final long RESPONSE_TIMEOUT_MS = 8000;

    private static final ScheduledExecutorService TIMER =
            new ScheduledThreadPoolExecutor(1, r -> {
                Thread t = new Thread(r, "generic-action-timer");
                t.setDaemon(true);
                return t;
            });

    private GenericActions() {
    }

    /** 注册 generic 协议的指令处理器（ActionRouter 按 protocolId="generic" 路由到这里） */
    public static void registerAll() {
        ActionRouter.register("generic", (session, rawMessage, attachments, callback) -> {
            GattClient client = session.get(SessionKeys.GATT_CLIENT);
            if (client == null) {
                callback.onResult(JsonResponse.error(ErrorCode.DEVICE_NOT_CONNECTED, "设备未连接"));
                return;
            }
            // 附件 byte[] → OTA 分片下发（带进度）；带 fileName = 文件传输模式
            if (attachments != null && attachments.get(ATTACHMENT_KEY_DATA) instanceof byte[]) {
                String fileName = attachments.get(ATTACHMENT_KEY_FILE_NAME) instanceof String
                        ? (String) attachments.get(ATTACHMENT_KEY_FILE_NAME) : null;
                // 兜底：附件没带 fileName 时，从指令 JSON 的 name 字段解析（demo 用 json 传真实文件名）
                if (fileName == null) {
                    fileName = parseFileName(rawMessage);
                }
                OtaTransfer.sendOtaFirmware(session, client,
                        (byte[]) attachments.get(ATTACHMENT_KEY_DATA), fileName, callback);
                return;
            }
            // 心跳：JSON 请求 → JSON 应答 → 解析校验（区别于普通 echo 的「收到即算成功」）
            if (Heartbeat.COMMAND_HEARTBEAT.equals(rawMessage)) {
                Heartbeat.sendHeartbeat(session, client, callback);
                return;
            }
            // 普通 echo：文本字节
            byte[] data = rawMessage == null ? new byte[0] : rawMessage.getBytes(StandardCharsets.UTF_8);
            sendEcho(session, client, data, callback);
        });
    }

    // ---- 普通 echo ----

    /** 透传回显：把文本字节原样写到命令特征，收 notify 原样回 hex */
    private static void sendEcho(Session session, GattClient client, byte[] data,
                                 ActionHandler.ActionCallback callback) {
        QLog.d(TAG, "[" + session.getMac() + "] 透传发送 " + data.length + " 字节");
        AtomicBoolean done = new AtomicBoolean(false);
        client.setNotificationListener((uuid, value) -> {
            if (!GenericProtocolPlugin.CHAR_NOTIFY_UUID.equalsIgnoreCase(uuid) || done.get()) {
                return;
            }
            if (done.compareAndSet(false, true)) {
                client.setNotificationListener(null);
                QLog.d(TAG, "[" + session.getMac() + "] 收到应答 " + value.length + " 字节: " + bytesToDisplay(value));
                callback.onResult(JsonResponse.ok(bytesToDisplay(value)));
            }
        });
        TIMER.schedule(() -> {
            if (done.compareAndSet(false, true)) {
                client.setNotificationListener(null);
                callback.onResult(JsonResponse.error(ErrorCode.EXECUTION_FAILED,
                        "透传应答超时（" + RESPONSE_TIMEOUT_MS + "ms 未收到 notify）"));
            }
        }, RESPONSE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        client.write(GenericProtocolPlugin.SERVICE_UUID, GenericProtocolPlugin.CHAR_WRITE_UUID, data,
                (ok, err, msg) -> {
                    if (!ok && done.compareAndSet(false, true)) {
                        client.setNotificationListener(null);
                        callback.onResult(JsonResponse.error(ErrorCode.EXECUTION_FAILED, "写入失败: " + msg));
                    }
                });
    }

    /** 从指令 JSON 里解析文件名（{@code {"action":"file","name":"xxx"}}），解析失败返回 null */
    private static String parseFileName(String rawMessage) {
        if (rawMessage == null || rawMessage.isEmpty()) {
            return null;
        }
        try {
            org.json.JSONObject o = new org.json.JSONObject(rawMessage);
            String name = o.optString("name", null);
            return (name == null || name.isEmpty()) ? null : name;
        } catch (Exception e) {
            return null;
        }
    }

    // ---- 字节工具（供本类与 OtaTransfer 复用）----

    /** 字节转大写十六进制字符串 */
    public static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }

    /**
     * 把收到/回显的字节按 UTF-8 解码成可读文本；解码失败或含控制字符时退回 hex。
     * 可读文本用双引号包裹，二进制用 "hex:" 前缀，方便肉眼区分「字符串 vs 二进制」。
     */
    private static String bytesToDisplay(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "（空）";
        }
        String text = new String(bytes, StandardCharsets.UTF_8);
        // 含替换字符（非法 UTF-8）→ 二进制
        if (text.indexOf('\uFFFD') >= 0) {
            return "hex:" + toHex(bytes);
        }
        // 含控制字符（除换行/回车/制表）→ 二进制
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t') {
                return "hex:" + toHex(bytes);
            }
        }
        return "\"" + text + "\"";
    }
}
