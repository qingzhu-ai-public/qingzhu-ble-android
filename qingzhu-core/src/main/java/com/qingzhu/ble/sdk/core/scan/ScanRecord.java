// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.sdk.core.scan;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 对 Android 原始扫描结果的协议无关封装。
 *
 * 之所以不直接用 android.bluetooth.le.ScanResult，是为了：
 * 1. 让 core 的扫描层可以脱离 Android 环境做单元测试；
 * 2. 未来如果要接入非 Android 平台的扫描源（如某些网关设备的 SDK 桥接），
 *    只需要在这一层做适配，不需要改 Matcher/Parser 的签名。
 *
 * 关于性能：一条广播会先被 Matcher 检查、再被 Parser 解析，两者往往都需要厂商数据段。
 * {@link #getManufacturerData(int)} 按 (广播实例, companyId) 缓存提取结果——
 * AD 结构每条广播只遍历一次，Matcher/Parser 之间不会重复做这件事。
 */
public final class ScanRecord {

    private final String mac;
    private final int rssi;
    private final byte[] rawScanRecord;
    private final byte[] manufacturerData;
    private final ConcurrentHashMap<Integer, byte[]> vendorDataByCompanyId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, byte[]> serviceDataByUuid = new ConcurrentHashMap<>();

    public ScanRecord(String mac, int rssi, byte[] rawScanRecord, byte[] manufacturerData) {
        this.mac = mac;
        this.rssi = rssi;
        this.rawScanRecord = rawScanRecord;
        this.manufacturerData = manufacturerData;
    }

    public String getMac() {
        return mac;
    }

    public int getRssi() {
        return rssi;
    }

    /** 完整的原始广播字节（AD 结构拼接），供需要自己遍历 AD 结构的协议使用 */
    public byte[] getRawScanRecord() {
        return rawScanRecord;
    }

    /**
     * 按公司 ID 提取厂商数据段（AD Type 0xFF 的 value 中去掉 2 字节公司 ID 后的有效载荷），
     * 结果在本广播实例内缓存。大多数协议的识别和解析只需要这一段。
     */
    public byte[] getManufacturerData(int companyId) {
        return vendorDataByCompanyId.computeIfAbsent(companyId, id -> extractManufacturerData(rawScanRecord, id));
    }

    /**
     * 按 16 位服务 UUID 提取 Service Data（AD Type 0x16 的 value 中去掉 2 字节 UUID 后的有效载荷），
     * 结果在本广播实例内缓存。Eddystone（0xFEAA）、BTHome（0xFCD2）等知名开放协议都走这一段。
     */
    public byte[] getServiceData(int uuid16) {
        return serviceDataByUuid.computeIfAbsent(uuid16, id -> extractServiceData(rawScanRecord, id));
    }

    /**
     * 遍历 AD 结构提取指定公司 ID 的厂商数据载荷（不含 2 字节公司 ID 本身）。
     * 未找到时返回 null。
     */
    private static byte[] extractManufacturerData(byte[] scanRecord, int companyId) {
        if (scanRecord == null) {
            return null;
        }
        int index = 0;
        while (index < scanRecord.length) {
            int length = scanRecord[index] & 0xFF;
            if (length == 0) {
                break;
            }
            if (index + length >= scanRecord.length) {
                break;
            }
            int type = scanRecord[index + 1] & 0xFF;
            if (type == 0xFF && index + 3 < scanRecord.length) {
                int companyIdLow = scanRecord[index + 2] & 0xFF;
                int companyIdHigh = scanRecord[index + 3] & 0xFF;
                int foundCompanyId = (companyIdHigh << 8) | companyIdLow;
                if (foundCompanyId == companyId) {
                    int dataLength = length - 3;
                    if (dataLength > 0 && index + 4 + dataLength <= scanRecord.length) {
                        byte[] result = new byte[dataLength];
                        System.arraycopy(scanRecord, index + 4, result, 0, dataLength);
                        return result;
                    }
                }
            }
            index += length + 1;
        }
        return null;
    }

    /**
     * 遍历 AD 结构提取指定 16 位服务 UUID 的 Service Data 载荷（不含 2 字节 UUID 本身）。
     * 未找到时返回 null。
     */
    private static byte[] extractServiceData(byte[] scanRecord, int uuid16) {
        if (scanRecord == null) {
            return null;
        }
        int index = 0;
        while (index < scanRecord.length) {
            int length = scanRecord[index] & 0xFF;
            if (length == 0) {
                break;
            }
            if (index + length >= scanRecord.length) {
                break;
            }
            int type = scanRecord[index + 1] & 0xFF;
            if (type == 0x16 && index + 3 < scanRecord.length) {
                int uuidLow = scanRecord[index + 2] & 0xFF;
                int uuidHigh = scanRecord[index + 3] & 0xFF;
                int foundUuid = (uuidHigh << 8) | uuidLow;
                if (foundUuid == uuid16) {
                    int dataLength = length - 3;
                    // dataLength 可为 0（广播只带 UUID、不带 payload）：返回空数组而非 null，
                    // 否则识别器会把它当"无此 service data"而漏掉该设备
                    if (dataLength >= 0 && index + 4 + dataLength <= scanRecord.length) {
                        byte[] result = new byte[dataLength];
                        if (dataLength > 0) {
                            System.arraycopy(scanRecord, index + 4, result, 0, dataLength);
                        }
                        return result;
                    }
                }
            }
            index += length + 1;
        }
        return null;
    }
}
