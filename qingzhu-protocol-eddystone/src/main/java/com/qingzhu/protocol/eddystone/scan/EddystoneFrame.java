// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.protocol.eddystone.scan;

/**
 * Eddystone 广播帧的解析工具（纯字节读取，无状态）。
 *
 * Eddystone 是 Google 的开源 Beacon 协议规范（github.com/google/eddystone）：
 * 广播负载为 Service Data（AD Type 0x16），16 位服务 UUID = 0xFEAA，
 * 载荷首字节为帧类型：
 *
 *   Eddystone-UID (18B): [0]=0x00, [1]=TxPower(int8), [2..11]=Namespace(10B), [12..17]=Instance(6B)
 *   Eddystone-URL (3~B): [0]=0x10, [1]=TxPower(int8), [2]=Scheme, [3..]=编码后的 URL
 *   Eddystone-TLM (14B): [0]=0x20, [1]=Version(=0), [2..3]=Voltage mV(BE), [4]=温度整数部分(sint8),
 *                        [5]=温度小数部分(/256), [6..9]=广播计数(BE), [10..13]=开机秒数(BE)
 *
 * 帧解析的输入是"Service Data 载荷"（ScanRecord.getServiceData(0xFEAA) 的返回值，
 * 已去掉 2 字节 UUID），core 层做了按广播实例的提取缓存。
 * 仅被 scan 包内的 Recognizer 使用，对外不可见。
 */
final class EddystoneFrame {

    static final byte FRAME_UID = 0x00;
    static final byte FRAME_URL = 0x10;
    static final byte FRAME_TLM = 0x20;

    private static final String[] URL_SCHEMES = {
            "http://www.", "https://www.", "http://", "https://"
    };
    private static final String[] URL_ENDINGS = {
            ".com/", ".org/", ".edu/", ".net/", ".info/", ".biz/", ".gov/",
            ".com", ".org", ".edu", ".net", ".info", ".biz", ".gov"
    };

    private EddystoneFrame() {
    }

    // ---------------- UID ----------------

    static boolean isUid(byte[] f) {
        return f != null && f.length >= 18 && f[0] == FRAME_UID;
    }

    /** @return 20 字符的十六进制 Namespace ID（不含分隔符） */
    static String uidNamespace(byte[] f) {
        return bytesToHex(f, 2, 10);
    }

    /** @return 12 字符的十六进制 Instance ID（不含分隔符） */
    static String uidInstance(byte[] f) {
        return bytesToHex(f, 12, 6);
    }

    static int uidTxPower(byte[] f) {
        return f[1]; // int8，单位 dBm（在 0 米距离处的 RSSI 参考值）
    }

    // ---------------- URL ----------------

    static boolean isUrl(byte[] f) {
        return f != null && f.length >= 3 && f[0] == FRAME_URL;
    }

    /**
     * 按 Eddystone-URL 编码规范还原完整 URL：
     * [2] 为 Scheme 前缀（0~3），[3..] 逐字节查编码表（0x00~0x0D 为常用后缀），其余按 ASCII。
     * 无法解码的字节返回 null（调用方按"不认领"处理）。
     */
    static String url(byte[] f) {
        int scheme = f[2] & 0xFF;
        if (scheme >= URL_SCHEMES.length) {
            return null;
        }
        StringBuilder sb = new StringBuilder(URL_SCHEMES[scheme]);
        for (int i = 3; i < f.length; i++) {
            int c = f[i] & 0xFF;
            if (c < URL_ENDINGS.length) {
                sb.append(URL_ENDINGS[c]);
            } else if (c >= 0x20 && c < 0x7F) {
                sb.append((char) c);
            } else {
                return null; // 非法编码字节
            }
        }
        return sb.toString();
    }

    static int urlTxPower(byte[] f) {
        return f[1];
    }

    // ---------------- TLM ----------------

    static boolean isTlm(byte[] f) {
        return f != null && f.length >= 14 && f[0] == FRAME_TLM && f[1] == 0x00;
    }

    /** 电池电压，单位 mV */
    static int tlmVoltage(byte[] f) {
        return ((f[2] & 0xFF) << 8) | (f[3] & 0xFF);
    }

    /** 温度，单位 °C（8.8 定点：整数部分 sint8 + 小数部分 /256） */
    static double tlmTemperature(byte[] f) {
        return f[4] + (f[5] & 0xFF) / 256.0;
    }

    /** 自开机以来的广播计数 */
    static long tlmAdvCount(byte[] f) {
        return ((f[6] & 0xFFL) << 24) | ((f[7] & 0xFFL) << 16) | ((f[8] & 0xFFL) << 8) | (f[9] & 0xFFL);
    }

    /** 自开机以来的秒数 */
    static long tlmUptimeSeconds(byte[] f) {
        return ((f[10] & 0xFFL) << 24) | ((f[11] & 0xFFL) << 16) | ((f[12] & 0xFFL) << 8) | (f[13] & 0xFFL);
    }

    // ---------------- 公共工具 ----------------

    private static String bytesToHex(byte[] bytes, int offset, int count) {
        StringBuilder sb = new StringBuilder(count * 2);
        for (int i = offset; i < offset + count; i++) {
            sb.append(String.format("%02X", bytes[i]));
        }
        return sb.toString();
    }
}
