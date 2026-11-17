// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.protocol.eddystone.scan;

import com.qingzhu.ble.sdk.core.common.QLog;
import com.qingzhu.ble.sdk.core.device.QDevice;
import com.qingzhu.ble.sdk.core.scan.Recognizer;
import com.qingzhu.ble.sdk.core.scan.ScanCache;
import com.qingzhu.ble.sdk.core.scan.ScanRecord;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Eddystone 广播识别 + 解析（合一）。
 *
 * Eddystone 的广播负载在 Service Data（AD Type 0x16，UUID 0xFEAA）里，不在厂商数据段。
 * 首字节帧类型分三种，全部认领并解析：
 * - UID：稳定身份（Namespace+Instance）——UID 帧里的 Namespace+Instance 跨设备生命周期
 *   不变，即使广播地址轮换也能对上同一台设备（stableId 的素材）；
 * - URL：还原完整 URL；
 * - TLM：电压/温度/广播计数/开机秒数。
 *
 * 性能说明：Service Data 载荷由 ScanRecord.getServiceData(0xFEAA) 提取，
 * core 层按广播实例缓存，AD 结构每条广播只遍历一次。
 */
public final class EddystoneRecognizer implements Recognizer {

    /** Eddystone 的 16 位服务 UUID（官方规范固定值） */
    public static final int EDDYSTONE_SERVICE_UUID = 0xFEAA;

    /** 同一台设备同一种帧只打一次识别日志，避免 beacon 每秒广播刷屏 */
    private static final ConcurrentHashMap<String, Boolean> LOGGED = new ConcurrentHashMap<>();

    private static void logOnce(String key, String message) {
        if (LOGGED.putIfAbsent(key, Boolean.TRUE) == null) {
            QLog.d("Eddystone", message);
        }
    }

    public static final String META_FRAME_TYPE = "frameType";       // UID / URL / TLM
    public static final String META_TX_POWER = "txPower";           // dBm
    public static final String META_NAMESPACE_ID = "namespaceId";   // UID：20 hex chars
    public static final String META_INSTANCE_ID = "instanceId";     // UID：12 hex chars
    public static final String META_EDDYSTONE_ID = "eddystoneId";   // UID：namespace+instance 拼接
    public static final String META_URL = "url";                    // URL：还原后的完整 URL
    public static final String META_VOLTAGE_MV = "voltageMv";       // TLM
    public static final String META_TEMPERATURE = "temperature";    // TLM，°C
    public static final String META_ADV_COUNT = "advCount";         // TLM
    public static final String META_UPTIME_SECONDS = "uptimeSeconds"; // TLM

    @Override
    public boolean recognize(ScanRecord record, String deviceName, QDevice device) {
        byte[] frame = record.getServiceData(EDDYSTONE_SERVICE_UUID);

        // UID 帧：身份帧，优先判断
        if (EddystoneFrame.isUid(frame)) {
            device.putMetadata(META_FRAME_TYPE, "UID");
            device.putMetadata(META_TX_POWER, EddystoneFrame.uidTxPower(frame));
            String namespace = EddystoneFrame.uidNamespace(frame);
            String instance = EddystoneFrame.uidInstance(frame);
            device.putMetadata(META_NAMESPACE_ID, namespace);
            device.putMetadata(META_INSTANCE_ID, instance);
            device.putMetadata(META_EDDYSTONE_ID, namespace + instance);
            logOnce("UID|" + device.getMac(), "【识别】UID mac=" + device.getMac()
                    + " ns=" + namespace + " inst=" + instance);
            return true;
        }

        // URL 帧
        if (EddystoneFrame.isUrl(frame)) {
            String url = EddystoneFrame.url(frame);
            if (url == null) {
                return false; // 编码非法，不认领
            }
            device.putMetadata(META_FRAME_TYPE, "URL");
            device.putMetadata(META_TX_POWER, EddystoneFrame.urlTxPower(frame));
            device.putMetadata(META_URL, url);
            adoptUidIdentity(record, device);
            logOnce("URL|" + device.getMac(), "【识别】URL mac=" + device.getMac() + " url=" + url);
            return true;
        }

        // TLM 帧：遥测数据
        if (EddystoneFrame.isTlm(frame)) {
            device.putMetadata(META_FRAME_TYPE, "TLM");
            device.putMetadata(META_VOLTAGE_MV, EddystoneFrame.tlmVoltage(frame));
            device.putMetadata(META_TEMPERATURE, EddystoneFrame.tlmTemperature(frame));
            device.putMetadata(META_ADV_COUNT, EddystoneFrame.tlmAdvCount(frame));
            device.putMetadata(META_UPTIME_SECONDS, EddystoneFrame.tlmUptimeSeconds(frame));
            adoptUidIdentity(record, device);
            logOnce("TLM|" + device.getMac(), "【识别】TLM mac=" + device.getMac()
                    + " voltage=" + device.getMetadata(META_VOLTAGE_MV, 0)
                    + " advCount=" + device.getMetadata(META_ADV_COUNT, 0L));
            return true;
        }

        return false; // 不是 Eddystone 帧（或未知帧类型），不认领
    }

    /**
     * TLM/URL 帧本身没有身份字段，但同一台 Beacon 通常交替广播 UID+TLM/URL 帧：
     * 如果之前扫到过这台 mac 的 UID 帧（身份已在扫描缓存里建立），就把身份继承过来——
     * 否则同一台设备会因为 UID 帧/TLM 帧的 stableId 不同（eddystoneId vs mac）出现两行。
     */
    private void adoptUidIdentity(ScanRecord record, QDevice device) {
        QDevice known = ScanCache.getInstance().findByMac(record.getMac());
        if (known != null) {
            String eddystoneId = known.getMetadata(META_EDDYSTONE_ID);
            if (eddystoneId != null) {
                device.putMetadata(META_EDDYSTONE_ID, eddystoneId);
            }
        }
    }
}
