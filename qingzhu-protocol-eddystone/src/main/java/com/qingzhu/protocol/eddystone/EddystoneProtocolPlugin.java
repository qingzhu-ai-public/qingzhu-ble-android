// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.protocol.eddystone;

import com.qingzhu.ble.sdk.core.common.ErrorCode;
import com.qingzhu.ble.sdk.core.connect.GattClient;
import com.qingzhu.ble.sdk.core.connect.GattReadyCallback;
import com.qingzhu.ble.sdk.core.device.QDevice;
import com.qingzhu.ble.sdk.core.protocol.ProtocolPlugin;
import com.qingzhu.ble.sdk.core.protocol.ProtocolType;
import com.qingzhu.ble.sdk.core.scan.Recognizer;
import com.qingzhu.ble.sdk.core.session.Session;
import com.qingzhu.protocol.eddystone.action.EddystoneActions;
import com.qingzhu.protocol.eddystone.scan.EddystoneRecognizer;

/**
 * Eddystone 协议插件的组装入口（协议示例工程，规范见 github.com/google/eddystone）。
 *
 * 广播侧：扫描解析 Eddystone 的 UID / URL / TLM 三种帧（Service Data UUID 0xFEAA）。
 * 连接侧：标准 Eddystone Beacon 是"只广播不连接"的，本插件配套的模拟从机 App
 * （qingzhu-demo-sim-eddystone）参照 Eddystone 配置服务（GATT Configuration Service）的 UUID 命名空间
 * 暴露了"命令写 + 状态通知"两个特征值，用于验证 SDK 的"扫描→连接→发消息"完整链路。
 *
 * 包结构与 core 分层一一对应：
 * <ul>
 *   <li>根包        —— 组装入口（协议必备，core 唯一的接触点）</li>
 *   <li>scan/       —— 协议必备：广播识别与解析（Recognizer + 帧工具）</li>
 *   <li>action/     —— 指令收发实现（ActionHandler 接线 + JSON 拼包/完整性判断/超时）</li>
 * </ul>
 */
public final class EddystoneProtocolPlugin implements ProtocolPlugin {

    /** 协议唯一标识（action 路由的 key） */
    public static final String TYPE_ID = "eddystone";

    public static final ProtocolType TYPE = ProtocolType.of(TYPE_ID);

    /**
     * 模拟从机暴露的 GATT 服务（取 Eddystone GATT Configuration Service 的 UUID 命名空间）：
     * a3c87500-8ed3-4bdf-8a39-a01bebede295
     */
    public static final String SERVICE_UUID = "a3c87500-8ed3-4bdf-8a39-a01bebede295";

    /** 命令特征值（App 写入，模拟从机收到后处理）：a3c87502-... */
    public static final String CHARACTERISTIC_COMMAND_UUID = "a3c87502-8ed3-4bdf-8a39-a01bebede295";

    /** 状态特征值（模拟从机 notify 回给 App）：a3c87503-... */
    public static final String CHARACTERISTIC_STATUS_UUID = "a3c87503-8ed3-4bdf-8a39-a01bebede295";

    private final Recognizer recognizer = new EddystoneRecognizer();

    @Override
    public ProtocolType protocolType() {
        return TYPE;
    }

    @Override
    public Recognizer recognizer() {
        return recognizer;
    }

    /**
     * GATT 就绪钩子：发现服务后做"MTU 协商 → 使能状态通知"（Eddystone 无认证）。
     * 连接用 SDK 默认策略（标准 connectGatt，零配置）——"发现服务"也由 SDK 默认流程自动做，
     * 协议只写"连上后要收哪个特征的通知"这一件业务。
     */
    @Override
    public void onGattReady(Session session, GattClient client, GattReadyCallback callback) {
        client.requestMtu(185, (ok1, code1, msg1) -> {
            if (!ok1) {
                callback.onFailed(code1 == ErrorCode.CONNECTION_LOST ? code1 : ErrorCode.MTU_REQUEST_FAILED, msg1);
                return;
            }
            client.enableNotification(SERVICE_UUID, CHARACTERISTIC_STATUS_UUID, (ok2, code2, msg2) -> {
                if (ok2) {
                    callback.onReady();
                } else {
                    callback.onFailed(code2 == ErrorCode.CONNECTION_LOST ? code2 : ErrorCode.ENABLE_NOTIFICATION_FAILED, msg2);
                }
            });
        });
    }

    /**
     * 身份字段声明：UID 帧里的 Namespace+Instance（24 hex chars）是设备出厂写死的身份，
     * 跨广播地址轮换不变——Beacon 类设备典型场景就是地址会轮换。SDK 据此生成
     * 身份证风格的 stableId；TLM/URL 帧（无身份字段）扫到的设备解析不出该字段，
     * SDK 自动退回 bleMac。
     */
    @Override
    public String identityKey() {
        return EddystoneRecognizer.META_EDDYSTONE_ID;
    }

    /**
     * 注册时的装配回调：指令处理器注册进 ActionRouter。
     */
    @Override
    public void install() {
        EddystoneActions.registerAll();
    }

}
