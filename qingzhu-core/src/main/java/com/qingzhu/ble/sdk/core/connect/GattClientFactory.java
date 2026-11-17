// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.sdk.core.connect;

import com.qingzhu.ble.sdk.core.common.QLog;

/**
 * GattClient 的创建入口。core 本身不实现真正的 Android GATT 封装（那部分放在
 * core 的 android 适配子包 / 或由宿主 App 在启动时注入），这里用一个可替换的
 * Provider 承接，方便单元测试用假实现替换真实蓝牙调用。
 */
public final class GattClientFactory {

    public interface Provider {
        GattClient create(String address);
    }

    private static volatile Provider provider;

    private GattClientFactory() {
    }

    public static void setProvider(Provider p) {
        QLog.d("GattClientFactory", "setProvider 被调用，provider=" + (p == null ? "null" : p.getClass().getName()));
        provider = p;
    }

    /**
     * 设置"默认"实现（仅当还没有任何 provider 时生效）。
     * 由 QingzhuBle.init(Context) 调用，注入 core 内置的 Android 实现；
     * 接入方之后仍可用 {@link #setProvider} 显式覆盖（如单测注入假实现）。
     */
    public static void setDefaultProviderIfAbsent(Provider p) {
        if (provider == null) {
            provider = p;
        }
    }

    public static GattClient create(String address) {
        if (provider == null) {
            QLog.e("GattClientFactory", "provider 为 null！App 启动时没有调用 setProvider 注入实现");
            throw new IllegalStateException("GattClientFactory.Provider 未设置，"
                    + "请在 App 启动时调用 GattClientFactory.setProvider(...) 注入真实的 Android GATT 实现");
        }
        return provider.create(address);
    }
}
