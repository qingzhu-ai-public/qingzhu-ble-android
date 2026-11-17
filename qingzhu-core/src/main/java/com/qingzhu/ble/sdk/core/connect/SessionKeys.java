// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.sdk.core.connect;

/**
 * core 与协议插件之间共享的 Session key 常量，统一收敛在这里，
 * 避免各协议各自的 Step 类里写字符串常量导致拼写不一致。
 */
public final class SessionKeys {

    private SessionKeys() {
    }

    /** 本次连接绑定的 GattClient 实例（协议连接步骤创建后写入，后续步骤读取） */
    public static final String GATT_CLIENT = "core.gattClient";

    /** 智能扫描重寻址后，实际要拿去连接的 GATT 地址（可能与 session.getMac() 不同） */
    public static final String CURRENT_GATT_ADDR = "core.currentGattAddr";

    /** 设备的稳定标识（acquireSlot 时写入，断开释放时据此清理 ScanCache 里的地址数据） */
    public static final String STABLE_ID = "core.stableId";

    /** 认证成功后写入 Session 的密钥 key，Cipher 实现（如 SingleCipherFilter）按这个 key 读取 */
    public static final String CIPHER_KEY = "core.cipher.secretKey";

    /**
     * 认证成功后写入 Session 的流加密状态机（SingleCipherState）。真实协议的 nonce 用
     * 认证挑战随机数派生，且发送/接收必须共用同一个状态实例（counter 整条连接持续递增），
     * 所以只能在认证成功那一刻创建一次，不能每次收发时重建。
     */
    public static final String CIPHER_STATE = "core.cipher.state";
}
