// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.protocol.generic.action;

import com.qingzhu.ble.sdk.core.action.ActionHandler;
import com.qingzhu.ble.sdk.core.action.JsonResponse;
import com.qingzhu.ble.sdk.core.common.ErrorCode;
import com.qingzhu.ble.sdk.core.common.QLog;
import com.qingzhu.ble.sdk.core.connect.GattClient;
import com.qingzhu.ble.sdk.core.session.Session;
import com.qingzhu.protocol.generic.GenericProtocolPlugin;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * JSON 心跳（协议级）：
 * <pre>
 * 发（主端→从端）：{"action":"heartbeat","seq":N}   N = 自增序号
 * 收（从端→主端）：{"action":"heartbeat","seq":N,"status":"ok"}
 * </pre>
 * 校验规则：action=="heartbeat" 且 seq 回显一致 且 status=="ok" 才算存活。
 * 收到乱包 / 旧应答（seq 不匹配）/ 超时 / 写失败，一律判失败（心跳引擎记一次 miss）。
 */
public final class Heartbeat {

    private static final String TAG = "Heartbeat";

    /** 心跳指令标识：heartbeatCommand() 返回它，ActionRouter 据此路由到本类（区别于普通 echo） */
    public static final String COMMAND_HEARTBEAT = "heartbeat";
    /** 心跳请求/应答 JSON 字段名 */
    private static final String HB_FIELD_ACTION = "action";
    private static final String HB_FIELD_SEQ = "seq";
    private static final String HB_FIELD_STATUS = "status";
    /** 心跳应答 status 的合法值：ok = 存活 */
    private static final String HB_STATUS_OK = "ok";
    /** 心跳应答超时（毫秒） */
    private static final long RESPONSE_TIMEOUT_MS = 8000;
    /** 心跳序号：每次心跳自增，用于匹配请求/应答（丢弃迟到的旧应答、错乱包） */
    private static final AtomicLong HEARTBEAT_SEQ = new AtomicLong(0);

    private static final ScheduledExecutorService TIMER =
            new ScheduledThreadPoolExecutor(1, r -> {
                Thread t = new Thread(r, "generic-heartbeat");
                t.setDaemon(true);
                return t;
            });

    private Heartbeat() {
    }

    /** 发一次 JSON 心跳：构造请求 → 写 → 等 notify 应答 → 解析校验。结果经 callback 回报（isSuccess = 存活）。 */
    public static void sendHeartbeat(Session session, GattClient client,
                                     ActionHandler.ActionCallback callback) {
        final long seq = HEARTBEAT_SEQ.incrementAndGet();
        final byte[] request;
        try {
            org.json.JSONObject req = new org.json.JSONObject();
            req.put(HB_FIELD_ACTION, COMMAND_HEARTBEAT);
            req.put(HB_FIELD_SEQ, seq);
            request = req.toString().getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            callback.onResult(JsonResponse.error(ErrorCode.EXECUTION_FAILED,
                    "构造心跳请求失败: " + e.getMessage()));
            return;
        }
        QLog.d(TAG, "[" + session.getMac() + "] 发心跳 JSON: " + new String(request, StandardCharsets.UTF_8));
        AtomicBoolean done = new AtomicBoolean(false);
        client.setNotificationListener((uuid, value) -> {
            if (!done.compareAndSet(false, true)) {
                return;
            }
            client.setNotificationListener(null);
            String ack = value == null ? "" : new String(value, StandardCharsets.UTF_8);
            QLog.d(TAG, "[" + session.getMac() + "] 收到心跳应答: " + ack);
            callback.onResult(parseHeartbeatAck(ack, seq));
        });
        TIMER.schedule(() -> {
            if (done.compareAndSet(false, true)) {
                client.setNotificationListener(null);
                callback.onResult(JsonResponse.error(ErrorCode.EXECUTION_FAILED,
                        "心跳应答超时（" + RESPONSE_TIMEOUT_MS + "ms）"));
            }
        }, RESPONSE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        client.write(GenericProtocolPlugin.SERVICE_UUID, GenericProtocolPlugin.CHAR_WRITE_UUID, request,
                (ok, err, msg) -> {
                    if (!ok && done.compareAndSet(false, true)) {
                        client.setNotificationListener(null);
                        callback.onResult(JsonResponse.error(ErrorCode.EXECUTION_FAILED,
                                "心跳写入失败: " + msg));
                    }
                });
    }

    /** 解析心跳应答：action=="heartbeat" 且 seq 匹配 且 status=="ok" 才算存活，否则判失败 */
    private static JsonResponse parseHeartbeatAck(String ack, long expectSeq) {
        try {
            org.json.JSONObject o = new org.json.JSONObject(ack);
            String action = o.optString(HB_FIELD_ACTION, "");
            long seq = o.optLong(HB_FIELD_SEQ, -1L);
            String status = o.optString(HB_FIELD_STATUS, "");
            if (!COMMAND_HEARTBEAT.equals(action)) {
                return JsonResponse.error(ErrorCode.EXECUTION_FAILED, "心跳应答 action 不符: " + action);
            }
            if (seq != expectSeq) {
                return JsonResponse.error(ErrorCode.EXECUTION_FAILED,
                        "心跳应答 seq 不符：期望 " + expectSeq + " 实收 " + seq);
            }
            if (!HB_STATUS_OK.equals(status)) {
                return JsonResponse.error(ErrorCode.EXECUTION_FAILED, "心跳应答 status 非 ok: " + status);
            }
            return JsonResponse.ok("heartbeat ok seq=" + seq);
        } catch (Exception e) {
            return JsonResponse.error(ErrorCode.EXECUTION_FAILED, "心跳应答 JSON 解析失败: " + e.getMessage());
        }
    }
}
