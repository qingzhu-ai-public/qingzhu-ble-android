// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.sdk.core.scan;

import com.qingzhu.ble.sdk.core.device.QDevice;

/**
 * 协议的识别 + 解析入口：判断一段广播"是不是我的设备"，是的话顺便把字段解析进 QDevice。
 *
 * 识别和解析合并为一个方法是有意的：
 * 1. 两者读的是同一份广播数据——任何真实的"签名检查"都是解析流程的前几行，
 *    拆成两个接口必然导致解析时把识别逻辑再走一遍，或两份代码互相耦合共享常量；
 * 2. 对插件开发者来说只有一个类、一个方法：命中即填、不命中即 false，没有胶水代码。
 *
 * 实现要求：
 * - 不认领时返回 false，且不得修改 device（引擎会丢弃它）；
 * - 认领时把解析出的字段写进 device（协议私有字段放 metadata），返回 true；
 * - 厂商数据一律用 record.getManufacturerData(companyId) 获取——core 已按广播实例
 *   缓存提取结果，多个协议、识别+解析反复调用也只遍历一次 AD 结构。
 */
public interface Recognizer {

    /**
     * @return true = 认领这台设备（device 已填充解析结果）；false = 不是本协议设备
     */
    boolean recognize(ScanRecord record, String deviceName, QDevice device);
}
