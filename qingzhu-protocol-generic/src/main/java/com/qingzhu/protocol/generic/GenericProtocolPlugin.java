// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.protocol.generic;

import com.qingzhu.ble.sdk.core.common.ErrorCode;
import com.qingzhu.ble.sdk.core.common.QLog;
import com.qingzhu.ble.sdk.core.connect.ConnectCallback;
import com.qingzhu.ble.sdk.core.connect.ConnectParams;
import com.qingzhu.ble.sdk.core.connect.GattClient;
import com.qingzhu.ble.sdk.core.connect.GattReadyCallback;
import com.qingzhu.ble.sdk.core.connect.SessionKeys;
import com.qingzhu.ble.sdk.core.protocol.ProtocolParam;
import com.qingzhu.ble.sdk.core.protocol.ProtocolPlugin;
import com.qingzhu.ble.sdk.core.protocol.ProtocolType;
import com.qingzhu.ble.sdk.core.scan.Recognizer;
import com.qingzhu.ble.sdk.core.scan.ScanCache;
import com.qingzhu.ble.sdk.core.session.Session;
import com.qingzhu.ble.sdk.core.settings.BleSettings;
import com.qingzhu.protocol.generic.action.CapabilityNegotiator;
import com.qingzhu.protocol.generic.action.GenericActions;
import com.qingzhu.protocol.generic.action.Heartbeat;
import com.qingzhu.protocol.generic.scan.GenericRecognizer;

/**
 * 通用测试协议插件：不绑定任何具体设备协议。
 *
 * <ul>
 *   <li>识别：{@link GenericRecognizer} 认领所有广播（原始字节 dump 进 metadata）；</li>
 *   <li>身份：identityKey 指向广播里解析出的稳定 deviceMac，设备换址不掉线；</li>
 *   <li>连接：标准连接（零配置），onGattReady 里 MTU + 使能 notify；</li>
 *   <li>收发：{@link GenericActions} 透传——发任意文本/字节（附件 key="data"），
 *       收任意应答（原样回传 hex）。</li>
 * </ul>
 *
 * <p>配合 qingzhu-demo-sim-generic（可编程从机：广播任意 payload + echo GATT）和
 * qingzhu-demo-main（扫任意设备 + 发任意数据），组成一套不依赖真实设备的联调套件。
 */
public final class GenericProtocolPlugin implements ProtocolPlugin {

    public static final ProtocolType TYPE = ProtocolType.of("generic");

    /** 通用测试服务（FF00 命名空间，避开常见设备服务冲突） */
    public static final String SERVICE_UUID = "0000ff00-0000-1000-8000-00805f9b34fb";
    /** 命令特征：App 写数据进来（从机 echo 回 notify） */
    public static final String CHAR_WRITE_UUID = "0000ff01-0000-1000-8000-00805f9b34fb";
    /** 状态特征：从机 notify 回包 */
    public static final String CHAR_NOTIFY_UUID = "0000ff02-0000-1000-8000-00805f9b34fb";

    private static final String TAG = "GenericConnect";

    private final Recognizer recognizer = new GenericRecognizer();

    @Override
    public ProtocolType protocolType() {
        return TYPE;
    }

    @Override
    public Recognizer recognizer() {
        return recognizer;
    }

    /**
     * 身份字段声明：使用从 Manufacturer Data 解析出的稳定 deviceMac。
     *
     * 从机默认以 SIG 测试段 Company ID 0xFFFF 携带 6 字节 deviceMac，主端与从机
     * 显示同一个身份，广播地址轮换后也仍能对上同一台设备。
     * 若从机在界面上清空 Company ID，则不带该段，SDK 会回退到当前广播地址
     * （bleMac），连接前重寻址链路（见 {@link #beforeConnect}）依然能正确连上。
     */
    @Override
    public String identityKey() {
        return GenericRecognizer.META_DEVICE_MAC;
    }

    /**
     * 连接前钩子：重寻址——用 stableId 从扫描缓存找回从机当前最新的广播地址，写进 Session 后放行
     * （SDK 会用这个新地址做标准连接）。安卓从机断开/重启必换地址，拿旧地址连必失败。
     */
    @Override
    public void beforeConnect(Session session, ConnectParams params, ConnectCallback callback) {
        String stableId = session.get(SessionKeys.STABLE_ID, session.getMac());
        final int timeoutSec = BleSettings.getInt(session, "generic.rescanTimeoutSec");
        QLog.d(TAG, "开始重寻址 stableId=" + stableId + " timeoutSec=" + timeoutSec);

        ScanCache.getInstance().findLatestAddress(stableId, timeoutSec, new ScanCache.AddressCallback() {
            @Override
            public void onFound(String currentAddr) {
                QLog.d(TAG, "重寻址成功 currentGattAddr=" + currentAddr);
                session.put(SessionKeys.CURRENT_GATT_ADDR, currentAddr);
                callback.onReady();
            }

            @Override
            public void onTimeout() {
                QLog.e(TAG, "重寻址超时：" + timeoutSec + "s 内没找到 stableId=" + stableId + " 的新地址");
                callback.onFailed(ErrorCode.RESCAN_TIMEOUT, timeoutSec + "s 内未找回从机新地址");
            }
        });
    }

    /**
     * 只声明【本协议专属】的可配置项。
     *
     * <p>判断标准——这个参数的<b>消费者是谁</b>：
     * <ul>
     *   <li>SDK 内核读的通用机制参数（心跳 {@code heartbeat.*}、BLE 写类型 {@code ble.*} 等）
     *       → 已收敛到 core 的 {@code BleSettings.CORE_SPECS} 全局配置，协议<b>不重复声明</b>；</li>
     *   <li>本协议自己读的功能参数（重寻址超时、传输停滞检测）→ 声明在这里，key 用 {@code generic.} 前缀。</li>
     * </ul>
     */
    @Override
    public java.util.List<ProtocolParam> configurableParams() {
        return java.util.Arrays.asList(
                ProtocolParam.ofInt("generic.rescanTimeoutSec", 30,
                        "重寻址等待从机重新广播的超时（秒）。安卓从机断开/重启会换 BLE 广播地址，"
                                + "超过这个时间还没扫到新地址就判定连接失败"),
                ProtocolParam.ofLong("generic.transferStallTimeoutMs", 30_000L,
                        "大块传输停滞检测阈值（毫秒）：传输/等待回执过程中，若连续这么久没有任何进展"
                                + "（无新分片写入、无回执），判定链路卡死并反馈错误码。不按「总时长」判超时——"
                                + "大文件传多久取决于数据量，只要在推进就不算停滞"));
    }

    /**
     * 心跳指令：<b>设备级能力</b>——连接就绪时能力协商（get_info）已把从端「是否支持心跳」
     * 写进 session（{@code cap.heartbeat}），这里据此决定：支持则返回心跳指令标识
     * {@link GenericActions#COMMAND_HEARTBEAT}，由 ActionRouter 路由到 JSON 心跳处理
     * （发 {@code {"action":"heartbeat","seq":N}} → 收 {@code {"action":"heartbeat","seq":N,"status":"ok"}} 并校验），
     * 不支持返回 null（不做心跳）。这样同一协议下「有的设备支持心跳、有的不支持」也能各自正确。
     *
     * <p>时序保证：能力协商在 {@code onGattReady} 内完成、先于心跳 {@code start}（连接就绪后才启动）。
     */
    @Override
    public String heartbeatCommand(Session session) {
        boolean supported = session.get(CapabilityNegotiator.CAP_HEARTBEAT, false);
        return supported ? Heartbeat.COMMAND_HEARTBEAT : null;
    }

    @Override
    public void onGattReady(Session session, GattClient client, GattReadyCallback callback) {
        client.requestMtu(247, (ok1, code1, msg1) -> {
            if (!ok1) {
                callback.onFailed(code1 == ErrorCode.CONNECTION_LOST ? code1 : ErrorCode.MTU_REQUEST_FAILED, msg1);
                return;
            }
            client.enableNotification(SERVICE_UUID, CHAR_NOTIFY_UUID, (ok2, code2, msg2) -> {
                if (!ok2) {
                    callback.onFailed(code2 == ErrorCode.CONNECTION_LOST ? code2 : ErrorCode.ENABLE_NOTIFICATION_FAILED, msg2);
                    return;
                }
                // 连接就绪后做能力协商：拿到从端 maxMtu/phy2M/缓冲大小，供传输层动态调整
                CapabilityNegotiator.negotiateCapabilities(session, client, callback::onReady);
            });
        });
    }

    @Override
    public void install() {
        GenericActions.registerAll();
    }
}
