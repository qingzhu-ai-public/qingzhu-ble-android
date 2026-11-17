// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.protocol.generic.scan;

import com.qingzhu.ble.sdk.core.device.QDevice;
import com.qingzhu.ble.sdk.core.scan.Recognizer;
import com.qingzhu.ble.sdk.core.scan.ScanRecord;

/**
 * Generic 协议识别器：只看 Service Data 的 16 位 UUID 0xFFE0 判定协议归属；
 * 稳定身份 deviceMac 从 Manufacturer Data 中提取（不限定具体 Company ID，
 * 任何 ≥16 字节里后 ≥6 字节即认为是 MAC）。
 *
 * <p>BLE SIG Company Identifier 需要付费申请，本 demo 不绑死 ID；从端可填任意
 * 4 位 hex 作为内部 ID（包括 SIG 测试段 0xFFFF），主端只关心"Company ID 后 ≥6 字节"。
 */
public class GenericRecognizer implements Recognizer {

    public static final int ADV_SERVICE_UUID_16 = 0xFFE0;

    /** 原始广播载荷（完整 AD 结构）的 hex 字符串 */
    public static final String META_RAW_ADV = "rawAdv";
    /** Service Data (UUID 0xFFE0) 的 hex 字符串 */
    public static final String META_SERVICE_DATA = "serviceData";
    /** 设备名（如广播里有） */
    public static final String META_DEVICE_NAME = "deviceName";
    /** 稳定身份 deviceMac（冒号分隔大写），从 Manufacturer Data 提取 */
    public static final String META_DEVICE_MAC = "deviceMac";

    @Override
    public boolean recognize(ScanRecord record, String deviceName, QDevice device) {
        byte[] serviceData = record.getServiceData(ADV_SERVICE_UUID_16);
        if (serviceData == null) {
            return false; // 不是 sim 从机，过滤
        }
        byte[] raw = record.getRawScanRecord();
        device.putMetadata(META_RAW_ADV, raw != null ? toHex(raw) : "");
        device.putMetadata(META_SERVICE_DATA, toHex(serviceData));
        if (deviceName != null && !deviceName.isEmpty()) {
            device.putMetadata(META_DEVICE_NAME, deviceName);
        }
        // 从 raw 字节遍历所有 Manufacturer Specific Data AD（type=0xFF），
        // 找 ≥6 字节的当作稳定身份。不限定 Company ID，参见类注释。
        byte[] rawAdv = record.getRawScanRecord();
        String mac = extractFirstMacFromManufacturerData(rawAdv);
        // 调试：把 raw 字节和 MAC 解析结果打印出来（用 System.out 一定能看到）
//        System.out.println("[D/GenericRecogn] raw=" + (rawAdv != null ? toHex(rawAdv) : "null")
//                + " mac=" + mac + " serviceData=" + toHex(serviceData));
        if (mac != null) {
            device.putMetadata(META_DEVICE_MAC, mac);
        }
        return true;
    }

    /**
     * 遍历 AD 结构找第一个 Manufacturer Specific Data（type=0xFF），
     * data 段（去掉 2 字节 Company ID）≥6 字节即取前 6 字节当 MAC 返回。
     * 未找到返回 null。
     */
    private static String extractFirstMacFromManufacturerData(byte[] scanRecord) {
        if (scanRecord == null || scanRecord.length < 5) return null;
        int index = 0;
        while (index < scanRecord.length) {
            int length = scanRecord[index] & 0xFF;
            if (length == 0) break;
            if (index + length >= scanRecord.length) break;
            int type = scanRecord[index + 1] & 0xFF;
            if (type == 0xFF) {
                // length 包含 type 字节本身：length = 1(type) + 2(companyId) + data
                int dataLen = length - 3;
                if (dataLen >= 6 && index + 4 + dataLen <= scanRecord.length) {
                    return toMac(scanRecord, index + 4, 6);
                }
            }
            index += length + 1;
        }
        return null;
    }

    private static String toMac(byte[] src, int offset, int len) {
        StringBuilder sb = new StringBuilder(len * 3);
        for (int i = 0; i < len; i++) {
            if (i > 0) sb.append(':');
            sb.append(String.format("%02X", src[offset + i]));
        }
        return sb.toString();
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }
}