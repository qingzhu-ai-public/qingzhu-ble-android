// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.sdk.core.protocol;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 已注册协议插件的登记表。
 *
 * 现有系统靠 META-INF/services 手工维护 SPI 文件，容易出现"文件散落、单独引用某模块时
 * ServiceLoader 发现 0 个协议"的问题（《SDK架构解析.md》观察点3）。这里先提供最朴素的
 * "运行时注册"方式（App 启动时手动 register），assembly-plugin 编译期自动生成注册代码
 * 是后续打包工具要做的事，不影响这个类的接口形状。
 */
public final class ProtocolRegistry {

    private static final ConcurrentHashMap<String, ProtocolPlugin> PLUGINS = new ConcurrentHashMap<>();
    private static final CopyOnWriteArrayList<ProtocolPlugin> ORDERED = new CopyOnWriteArrayList<>();

    private ProtocolRegistry() {
    }

    public static void register(ProtocolPlugin plugin) {
        String id = plugin.protocolType().getId();
        if (PLUGINS.putIfAbsent(id, plugin) == null) {
            ORDERED.add(plugin);
            // 把协议声明的可配置项（key/类型/默认值/说明）纳入全局设置中心：此后用
            // settings().set(key, value) 即可定制，不设置就是声明里的默认值。协议只"声明"，
            // 赋值权在使用方——这条线现在只有一处，不再需要门面中转。
            com.qingzhu.ble.sdk.core.settings.BleSettings.get()
                    .registerDefaults(plugin.configurableParams());
            // 插件注册成功后触发装配回调（注册 actions/自定义步骤等），接入方无需逐个调用
            plugin.install();
        }
    }

    public static ProtocolPlugin get(ProtocolType type) {
        return PLUGINS.get(type.getId());
    }

    /** 扫描层按注册顺序遍历所有插件逐个尝试识别 */
    public static Collection<ProtocolPlugin> all() {
        return Collections.unmodifiableList(ORDERED);
    }

    /** 主要供测试使用，清空所有已注册协议 */
    public static void clear() {
        PLUGINS.clear();
        ORDERED.clear();
    }
}
