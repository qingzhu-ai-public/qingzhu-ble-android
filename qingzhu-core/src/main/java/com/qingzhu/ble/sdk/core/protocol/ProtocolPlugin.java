// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.sdk.core.protocol;

import com.qingzhu.ble.sdk.core.device.QDevice;
import com.qingzhu.ble.sdk.core.scan.Recognizer;

/**
 * 一个协议插件对外暴露的最小契约：识别解析、连接配方。
 *
 * 这是 core 唯一认识的"协议"形状。ProtocolRegistry 只会调用这几个 getter，
 * 不关心协议插件内部的连接流程是复用内置步骤实例还是自写 ConnectStep（原生直通）——
 * 最终都要汇合成这一个接口的实现。
 */
public interface ProtocolPlugin {

    ProtocolType protocolType();

    /**
     * 识别 + 解析（合一）：这段广播是不是我的设备？是的话把字段解析进 QDevice。
     * 引擎按注册顺序逐个问，第一个认领的协议拿到这台设备。
     */
    Recognizer recognizer();

    /**
     * 连接流程（代码式，逃生舱）：完全自定义的连接流程步骤列表。
     * 返回 null（默认）时，SDK 走"默认流程"——{@link #beforeConnect} 连接前钩子 → 标准连接
     * → 自动发现服务 → {@link #onGattReady} 钩子，协议零配置即可连接。只有当默认流程 + 钩子
     * 都覆盖不了（需要在连接中插入任意步骤/分支）时才覆写本方法，自己 new 步骤实例组成列表。
     */
    default java.util.List<com.qingzhu.ble.sdk.core.connect.ConnectStepSpec> connectSteps() {
        return null;
    }

    /**
     * <b>连接前钩子</b>：正式建立 GATT 连接（connectGatt）之前调用。
     *
     * <p>默认空实现 = 不做任何处理，SDK 直接用当前地址标准连接（零配置）。
     * 需要「连接前自定义处理」的协议覆写它——典型场景：
     * <ul>
     *   <li><b>地址会轮换的设备</b>：先重寻址，把最新广播地址写进 Session（{@code CURRENT_GATT_ADDR}）；</li>
     *   <li>连接前要先握手 / 取临时密钥等。</li>
     * </ul>
     * 处理完成调 {@code callback.onReady()}，SDK 就继续<b>标准连接</b>（connectGatt，协议不用自己连）；
     * 失败调 {@code callback.onFailed(...)}。连上之后 SDK 统一走「发现服务 + {@link #onGattReady}」。
     */
    default void beforeConnect(com.qingzhu.ble.sdk.core.session.Session session,
                               com.qingzhu.ble.sdk.core.connect.ConnectParams params,
                               com.qingzhu.ble.sdk.core.connect.ConnectCallback callback) {
        callback.onReady();
    }

    /**
     * GATT 就绪钩子：连接 + 发现服务完成后，协议在这里做"业务准备"——找特征、使能通知、
     * 认证（如 CMAC）。默认实现什么都不做（纯连接，不收数据）。
     * 这是 nRF Connect "连接通用、业务交互式"模型里的"业务"挂载点：连接通用由 SDK 兜底，
     * 业务由协议在这里写。完成后回调 callback.onReady() 或 onFailed()。
     */
    default void onGattReady(com.qingzhu.ble.sdk.core.session.Session session,
                             com.qingzhu.ble.sdk.core.connect.GattClient client,
                             com.qingzhu.ble.sdk.core.connect.GattReadyCallback callback) {
        callback.onReady();
    }

    /**
     * 心跳指令（长连接保活）：返回一条<b>轻量、无副作用</b>的查询指令，设备收到后应回复。
     * core 在「连接保持」场景下，空闲超时（{@code heartbeat.idleIntervalMs}）时周期发这条指令
     * 探测设备存活；连续 {@code heartbeat.maxMiss} 次无应答就判离线 → 主动断开 + 自动重连。
     *
     * <p>返回 {@code null}（默认）= <b>不做心跳</b>——协议自己决定要不要、怎么做；core 完全静默，
     * 没有任何额外开销。指令走与 {@code send} 同一条收发队列（按 mac 串行），不与业务指令并发。
     */
    default String heartbeatCommand(com.qingzhu.ble.sdk.core.session.Session session) {
        return null;
    }

    /**
     * 协议级心跳参数：不同协议心跳节奏不同（有的 3 秒、有的 15 秒、有的不做）时覆写。
     * 返回 {@code null}（默认）= 用全局默认 {@code heartbeat.idleIntervalMs/timeoutMs/maxMiss/enabled}。
     *
     * <p>与 {@link #heartbeatCommand} 配合：指令决定「做不做」心跳，参数决定「做的话节奏如何」。
     * 某字段为 {@code null} 表示「该项沿用全局默认」，方便只覆写节奏中的一两个而不必全写。
     */
    default HeartbeatConfig heartbeatConfig(com.qingzhu.ble.sdk.core.session.Session session) {
        return null;
    }

    /**
     * 心跳参数值对象（协议级覆盖全局默认）。
     * 字段为 {@code null} 表示「该项沿用全局默认」——字段名与全局 key 后缀一一对齐：
     * {@code idleIntervalMs↔heartbeat.idleIntervalMs}、{@code timeoutMs↔heartbeat.timeoutMs}、
     * {@code maxMiss↔heartbeat.maxMiss}、{@code enabled↔heartbeat.enabled}，一眼看出覆盖的是哪个全局项。
     */
    final class HeartbeatConfig {
        /** null = 沿用全局 {@code heartbeat.idleIntervalMs} */
        public final Long idleIntervalMs;
        /** null = 沿用全局 {@code heartbeat.timeoutMs} */
        public final Long timeoutMs;
        /** null = 沿用全局 {@code heartbeat.maxMiss} */
        public final Integer maxMiss;
        /** null = 沿用全局 {@code heartbeat.enabled} */
        public final Boolean enabled;

        public HeartbeatConfig(Long idleIntervalMs, Long timeoutMs, Integer maxMiss, Boolean enabled) {
            this.idleIntervalMs = idleIntervalMs;
            this.timeoutMs = timeoutMs;
            this.maxMiss = maxMiss;
            this.enabled = enabled;
        }

        /** 只覆写节奏（空闲/超时/判离线），开关置 null 沿用全局 */
        public static HeartbeatConfig of(Long idleIntervalMs, Long timeoutMs, Integer maxMiss) {
            return new HeartbeatConfig(idleIntervalMs, timeoutMs, maxMiss, null);
        }

        public static HeartbeatConfig of(Long idleIntervalMs, Long timeoutMs, Integer maxMiss, Boolean enabled) {
            return new HeartbeatConfig(idleIntervalMs, timeoutMs, maxMiss, enabled);
        }
    }

    /**
     * 身份字段声明：告诉 SDK"这个协议的设备身份放在哪个 metadata key 里"（如 "deviceMac"）。
     *
     * stableId 由 <b>SDK 统一分配</b>（插件不再自己生成）：引擎取该 key 对应的 metadata 值
     * 作为身份源，生成身份证风格的全局唯一 ID——16 位大写字母+数字（如 "3F9K2M7Q1A8C5D2E"）。
     * 哈希输入含协议 ID，不同协议的身份值即使相同也绝不互相撞 key。
     *
     * 地址会轮换的协议（广播内容一变 BLE 地址就变，但其实是同一台设备）必须覆写：
     * 在 Recognizer 里把稳定字段解析进 metadata，这里声明该字段名。
     * 地址固定的协议不用管（默认 null = 用 bleMac 做身份源，天然稳定）。
     */
    default String identityKey() {
        return null;
    }

    /**
     * 断开连接时是否解除系统配对（removeBond）。
     *
     * <p>默认 {@code false}（<b>保留配对</b>）：需要系统配对的设备（加密特征、HID 等）靠系统配对
     * 工作，断开后必须保留配对，否则下次连不上。
     *
     * <p>返回 {@code true} 的协议（如 轮换地址协议——用应用层 CMAC 认证、不需要系统配对）：配对过的
     * 从端重启后倾向用<b>定向广播</b>只发给已配对手机，普通 LE 扫描收不到，表现为「关机重启后
     * 列表不显示」。这类协议应在断开时主动解绑，清掉历史残留的配对（旧 SDK 的 safeRemoveBondDelayed
     * 同款做法）。
     */
    default boolean removeBondOnDisconnect() {
        return false;
    }

    /**
     * 可选：暴露本协议的<b>可配置项</b>（key/类型/默认值/说明）。
     *
     * 职责分离（分层设计）：协议只负责"暴露 + 默认值"，<b>定制权在宿主 SDK</b>——
     * 宿主 SDK 通过 {@link com.qingzhu.ble.sdk.core.protocol.ProtocolParams} 设置值：
     * 扫描相关参数由门面读出来拼进 ScanConfig，连接相关参数随 connect 复制进 Session
     * （key 加 "param." 前缀），协议自己的步骤/Filter 据此读取。
     *
     * 不需要可配置项时返回空列表（默认）。
     */
    default java.util.List<ProtocolParam> configurableParams() {
        return java.util.Collections.emptyList();
    }

    /**
     * 协议级扫描配置：声明本协议期望的<b>系统扫描参数</b>（扫描模式 / 上报延迟 / PHY / 扩展广播 /
     * 硬件匹配模式等）。返回 {@code null}（默认）= 不干预，走 core 内置默认。
     *
     * <p>core 会在扫描启动时把所有已注册协议声明的配置与调用方传入的 config 合并（见
     * {@code ScanConfig.merge}：调用方显式设置优先，其次协议声明，最后 core 内置默认），
     * 用于构建系统 ScanSettings。协议只声明「系统扫描参数」这几个可空字段，<b>不要声明
     * timeout / 协议过滤 / stableId 白名单</b>——那些是调用方关注点，不参与合并，以调用方为准。
     */
    default com.qingzhu.ble.sdk.core.scan.ScanConfig scanConfig() {
        return null;
    }

    /**
     * 硬件过滤：本协议设备的<b>厂商 ID 列表</b>（即 BLE SIG 分配的 Company Identifier）。
     * core 在平台层据此构建
     * 原生 ScanFilter（{@code setManufacturerData(id, new byte[]{})}）把过滤下沉到芯片 offload——
     * 只上报这些厂商的广播，屏蔽周围其它 BLE 设备，扫描又快又省电（对标旧架构 LollipopScanner
     * 在 Android 9+ 的厂商 ID 过滤，是「瞬间扫出目标设备」的关键）。
     *
     * <p>返回 {@code null} 或空数组 = 不做厂商 ID 过滤，回落 core 的 match-all 空 filter（全量广播
     * 进软件识别）。多个协议各自声明，core 会<b>合并</b>所有已注册协议的厂商 ID 到一个过滤列表。
     */
    default int[] scanManufacturerIds() {
        return null;
    }

    /**
     * 装配钩子（对标 Gradle 插件的 apply / VSCode 的 activate）：由
     * {@link ProtocolRegistry#register} 在插件真正注册成功后调用一次。
     *
     * 在这里把插件自己的零件"安装"到引擎上——注册 ActionHandler、注册自定义步骤等，
     * 接入方只需要 registerProtocol 一行，不需要逐个调用插件的各个 register 方法。
     * 重复注册同一协议是幂等的（不会二次触发）。
     */
    default void install() {
    }
}
