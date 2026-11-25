// SPDX-License-Identifier: MIT
package dev.qingzhu.bthome.protocol

/** 一条读数：对象 + 已乘过倍率的真实值。 */
data class Measurement(val obj: Obj, val value: Float) {
    /** 值是否落在该对象的合理范围内。 */
    val inRange: Boolean get() = value in obj.min..obj.max
}

/** 一帧解好的 BTHome 数据。 */
data class BthomeFrame(
    val version: Int,
    val encrypted: Boolean,
    val trigger: Boolean,
    val measurements: List<Measurement>,
) {
    /** 取某个对象的值，这一帧里没有就返回 null。 */
    operator fun get(obj: Obj): Float? = measurements.firstOrNull { it.obj == obj }?.value
}

/**
 * 解析 Service Data 里 UUID 之后的字节（`40 02 C4 09 …`）。
 *
 * 返回 null 表示这不是我们能处理的帧：长度不对、版本不是 v2，或者一个对象都解不出来。
 * 纯函数，不依赖任何 Android 类，所以能用官方样例直接写单元测试。
 */
fun parse(serviceData: ByteArray): BthomeFrame? {
    if (serviceData.size < 2) return null

    val info = serviceData[0].toInt() and 0xFF
    val encrypted = info and 0x01 != 0
    val trigger = info and 0x04 != 0
    val version = (info shr 5) and 0x07
    if (version != 2) return null                                      // 只处理 v2
    if (encrypted) return BthomeFrame(version, true, trigger, emptyList()) // 密文要先解密，本课不处理

    val out = ArrayList<Measurement>()
    var i = 1
    var lastId = -1
    while (i < serviceData.size) {
        val id = serviceData[i].toInt() and 0xFF
        val o = OBJ_BY_ID[id] ?: break              // 规范：遇到不认识的对象 ID 就停
        if (id < lastId) break                      // 规范：对象 ID 必须从小到大
        if (i + 1 + o.len > serviceData.size) break // 被截断的包，不能越界读
        val raw = readLE(serviceData, i + 1, o.len, o.signed)
        out += Measurement(o, raw * o.factor)
        lastId = id
        i += 1 + o.len
    }
    if (out.isEmpty()) return null
    return BthomeFrame(version, false, trigger, out)
}

/** 按小端读 len 个字节；signed 时做符号扩展，零下温度才不会变成六万多度。 */
private fun readLE(b: ByteArray, off: Int, len: Int, signed: Boolean): Int {
    var v = 0
    for (k in 0 until len) v = v or ((b[off + k].toInt() and 0xFF) shl (8 * k))
    if (signed) when (len) {
        1 -> v = v.toByte().toInt()
        2 -> v = v.toShort().toInt()
        3 -> if (v and 0x800000 != 0) v = v or 0xFF000000.toInt()
    }
    return v
}
