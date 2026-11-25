// SPDX-License-Identifier: MIT
package dev.qingzhu.bthome.protocol

import java.io.ByteArrayOutputStream

/**
 * 把读数编码成 BTHome 数据（设备信息字节 + 对象列表）。
 *
 * 调用方拿到的是 UUID 之后的字节，广播时由系统在前面补上 `D2 FC`。
 * 对象 ID 会被自动排成升序；值不做范围裁剪，越界由接收端标红，方便复现问题。
 */
fun encode(ms: List<Measurement>): ByteArray {
    val sorted = ms.sortedBy { it.obj.id }          // 规范：对象 ID 必须从小到大
    val out = ByteArrayOutputStream()
    out.write(DEVICE_INFO_V2_PLAIN)                  // 0x40 = v2、不加密、定时广播
    for (m in sorted) {
        out.write(m.obj.id)
        val raw = Math.round(m.value / m.obj.factor)
        repeat(m.obj.len) { k -> out.write((raw shr (8 * k)) and 0xFF) }   // 小端
    }
    return out.toByteArray()
}

/** Legacy 广播（4.x 兼容）的主包 / 扫描响应各只有 31 字节。 */
const val LEGACY_ADV_MAX_BYTES = 31

/** 主包占用：Flags 3 字节 + Service Data（长度 1 + 类型 1 + UUID 2 + 数据）。 */
fun legacyAdvBytes(serviceData: ByteArray): Int = 3 + 4 + serviceData.size

/** 扫描响应占用：只放设备名（长度 1 + 类型 1 + 名字的 UTF-8 字节）。空名字占 0。 */
fun legacyScanResponseBytes(deviceName: String): Int {
    if (deviceName.isEmpty()) return 0
    return 2 + deviceName.toByteArray(Charsets.UTF_8).size
}

/** 字节转 "D2FC 40 02 CA 09" 这样的展示串。 */
fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it) }

/** 连 UUID 前缀一起展示，和 nRF Connect 里看到的服务数据一致。 */
fun serviceDataHex(serviceData: ByteArray): String =
    "D2FC " + serviceData.toHex()
