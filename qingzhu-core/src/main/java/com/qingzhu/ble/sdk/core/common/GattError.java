// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.sdk.core.common;

import java.util.HashMap;
import java.util.Map;

/**
 * GATT 状态码 → 可读描述（对照 RxAndroidBle 的 GattStatusParser 与 Nordic 的 GattError
 * 整理，覆盖 Android 回调里实际会出现的 GATT status / error 码）。
 *
 * 背景：Android 回调只给一个裸数字（如 133），没有文档说明，开发者只能上网搜。
 * 这个类把数字翻译成"是什么问题、大概该干什么"，所有失败消息都应带上它。
 */
public final class GattError {

    private GattError() {
    }

    private static final Map<Integer, String> STATUS = new HashMap<>();

    static {
        // ---- ATT 协议层（0x01~0x11）----
        STATUS.put(0x00, "成功");
        STATUS.put(0x01, "无效句柄");
        STATUS.put(0x02, "不允许读");
        STATUS.put(0x03, "不允许写");
        STATUS.put(0x04, "无效 PDU");
        STATUS.put(0x05, "认证不足");
        STATUS.put(0x06, "请求不支持");
        STATUS.put(0x07, "无效偏移");
        STATUS.put(0x08, "授权不足 / 连接超时");
        STATUS.put(0x09, "准备队列满");
        STATUS.put(0x0A, "未找到");
        STATUS.put(0x0B, "属性过长");
        STATUS.put(0x0C, "密钥长度不足");
        STATUS.put(0x0D, "属性长度无效");
        STATUS.put(0x0E, "罕见错误");
        STATUS.put(0x0F, "加密不足");
        STATUS.put(0x10, "不支持的组类型");
        STATUS.put(0x11, "资源不足");
        // ---- 链路层断开原因（0x13~0x3E）----
        STATUS.put(0x13, "远程设备主动断开（0x13/19，最常见：从机重启/休眠/复位）");
        STATUS.put(0x16, "主机主动断开（0x16/22）");
        STATUS.put(0x22, "链路监督超时（LMP timeout，信号丢失）");
        STATUS.put(0x3E, "连接建立失败（0x3E/62，设备不在范围内或未响应）");
        // ---- GATT 内部（0x80~0x8F）----
        STATUS.put(0x80, "硬件资源不足");
        STATUS.put(0x81, "内部错误");
        STATUS.put(0x82, "状态错误（操作时序不对）");
        STATUS.put(0x83, "数据库满");
        STATUS.put(0x84, "忙");
        STATUS.put(0x85, "一般 GATT 错误");
        STATUS.put(0x86, "命令已启动");
        STATUS.put(0x87, "非法参数");
        STATUS.put(0x88, "操作挂起");
        STATUS.put(0x89, "认证失败");
        STATUS.put(0x8A, "更多数据");
        STATUS.put(0x8B, "配置无效");
        STATUS.put(0x8C, "服务已启动");
        STATUS.put(0x8D, "已加密但无 MITM 保护");
        STATUS.put(0x8E, "未加密");
        STATUS.put(0x8F, "拥塞");
        STATUS.put(0xFD, "CCD 配置错误");
        STATUS.put(0xFE, "程序进行中");
        STATUS.put(0xFF, "超出范围");
        // ---- Android 特有 ----
        STATUS.put(0x100, "连接被取消");
        STATUS.put(8, "连接超时（Android code 8，设备可能已离开/断电）");
        STATUS.put(19, "远程设备主动断开（Android code 19，同 0x13：从机重启/休眠/复位）");
        STATUS.put(34, "本地主机断开（Android code 34，协议错误）");
        STATUS.put(62, "连接建立失败（Android code 62，同 0x3E）");
        STATUS.put(133, "通用 GATT 错误（Android code 133，最棘手：通常是设备不可达、"
                + "缓存脏、资源耗尽或栈内部错误——先确认设备在广播，再尝试开关蓝牙）");
    }

    /**
     * @return 形如 "133(通用 GATT 错误...)" 的可读描述；未收录的码返回原值
     */
    public static String describe(int status) {
        if (status == 0) {
            return "0(成功)";
        }
        String desc = STATUS.get(status);
        return desc != null ? status + "(" + desc + ")" : String.valueOf(status);
    }
}
