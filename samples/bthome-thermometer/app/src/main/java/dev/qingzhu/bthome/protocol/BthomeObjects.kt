// SPDX-License-Identifier: MIT
package dev.qingzhu.bthome.protocol

/** BTHome v2 的 Service Data UUID：0xFCD2。在广播包里按小端写作 `D2 FC`。 */
const val BTHOME_SERVICE_UUID = "0000FCD2-0000-1000-8000-00805F9B34FB"

/** 设备信息字节。bit0 加密 / bit2 触发式 / bit5-7 版本；0x40 = v2、不加密、定时广播。 */
const val DEVICE_INFO_V2_PLAIN = 0x40

/**
 * 对象 ID 表（bthome.io/format）。
 *
 * 本课只登记用到的五项。要支持新对象就在这里加一行，解析器和编码器都会自动生效。
 * min / max 是该对象的合理范围：越界的帧照样解出来，由界面标红，而不是直接相信或悄悄丢弃。
 */
enum class Obj(
    val id: Int,
    val label: String,
    val len: Int,
    val signed: Boolean,
    val factor: Float,
    val unit: String,
    val min: Float,
    val max: Float,
) {
    PACKET_ID(0x00, "序号", 1, false, 1f, "", 0f, 255f),
    BATTERY(0x01, "电量", 1, false, 1f, "%", 0f, 100f),
    TEMPERATURE(0x02, "温度", 2, true, 0.01f, "°C", -40f, 85f),
    HUMIDITY(0x03, "湿度", 2, false, 0.01f, "%", 0f, 100f),
    VOLTAGE(0x0C, "电压", 2, false, 0.001f, "V", 0f, 65.535f);
}

val OBJ_BY_ID: Map<Int, Obj> = Obj.entries.associateBy { it.id }
