// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.sdk.core.platform;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;

/**
 * 原生 GATT 对象直通接口：让协议自己的 ConnectStep 能拿到真正的 BluetoothGatt / BluetoothDevice，
 * 自由调用系统 API（connectGatt 的 transport/PHY、setPreferredPhy、非标读写等）——core 不干预
 * 系统 API 的调用，只负责生命周期（断链感知、超时、池、重连）。
 *
 * 用法（协议自定义连接步骤里）：
 * <pre>
 *     GattClient client = session.get(SessionKeys.GATT_CLIENT);
 *     if (client instanceof NativeGatt) {
 *         BluetoothGatt gatt = ((NativeGatt) client).getRawGatt();
 *         gatt.setPreferredPhy(...);
 *     }
 * </pre>
 *
 * 只有 core 内置的 Android 实现（AndroidGattClient）实现本接口；单测注入的假实现
 * 拿不到原生对象（返回 null），符合预期——系统 API 调用本来就不该出现在纯逻辑单测里。
 */
public interface NativeGatt {

    /** 真正的 BluetoothGatt（协议自己 connectGatt 后挂进来的，或 core connect 建立的）；未连接时为 null */
    BluetoothGatt getRawGatt();

    /** 目标 BluetoothDevice（协议可据此调 setPreferredPhy / 读广播等系统能力） */
    BluetoothDevice getRawDevice();
}