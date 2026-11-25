// SPDX-License-Identifier: MIT
package dev.qingzhu.bthome.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 解析器单元测试。
 *
 * 样例来自 bthome.io/format 的官方示例和对象表，不需要手机、不需要蓝牙：
 * 协议层是纯 Kotlin，所以「不用两台手机也能验证解析器」。
 */
class BthomeParserTest {

    private fun hex(s: String): ByteArray =
        s.split(" ").filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()

    /** 官方示例：DIY-sensor，25.00 °C / 50.55 %。 */
    @Test fun officialExample() {
        val f = parse(hex("40 02 C4 09 03 BF 13"))!!
        assertEquals(2, f.version)
        assertFalse(f.encrypted)
        assertFalse(f.trigger)
        assertEquals(25.00f, f.measurements[0].value, 0.001f)
        assertEquals(50.55f, f.measurements[1].value, 0.001f)
        assertEquals(25.00f, f[Obj.TEMPERATURE]!!, 0.001f)
    }

    /** 0xFDDA = 65050，sint16 → -486，×0.01 = -5.50。 */
    @Test fun negativeTemperature() {
        val f = parse(hex("40 02 DA FD"))!!
        assertEquals(-5.50f, f.measurements[0].value, 0.001f)
    }

    @Test fun lowestTemperature() {
        val f = parse(hex("40 02 60 F0"))!!      // 0xF060 = 61536，sint16 → -4000
        assertEquals(-40.00f, f.measurements[0].value, 0.001f)
    }

    /** 电量 90%、电压 3.074 V（官方样例 0C020C）：对象表里的另外两项。 */
    @Test fun batteryAndVoltage() {
        val f = parse(hex("40 01 5A 0C 02 0C"))!!
        assertEquals(90f, f[Obj.BATTERY]!!, 0.0001f)
        assertEquals(3.074f, f[Obj.VOLTAGE]!!, 0.0001f)
    }

    @Test fun packetId() {
        val f = parse(hex("40 00 2A 02 C4 09"))!!
        assertEquals(42f, f[Obj.PACKET_ID]!!, 0.0001f)
    }

    /** 规范：遇到不认识的对象 ID 就停，后面的不再解析，也不抛异常。 */
    @Test fun unknownIdStopsParsing() {
        val f = parse(hex("40 02 C4 09 53 0C 48 65 6C 6C 6F"))!!
        assertEquals(1, f.measurements.size)
        assertEquals(Obj.TEMPERATURE, f.measurements[0].obj)

        assertNull(parse(hex("40 53 0C 48 69")))        // 第一个就不认识：整帧无效
    }

    /** 规范：对象 ID 必须升序，乱序的那一项开始全部丢弃。 */
    @Test fun outOfOrderStopsParsing() {
        val f = parse(hex("40 03 BF 13 02 C4 09"))!!
        assertEquals(1, f.measurements.size)
        assertEquals(Obj.HUMIDITY, f.measurements[0].obj)
    }

    /** 被截断的包不能越界读，也不能崩。 */
    @Test fun truncatedFrameIsIgnored() {
        assertNull(parse(hex("40 02 C4")))
        assertNull(parse(hex("40")))
        assertNull(parse(hex("")))
    }

    /** 只处理 v2：v1（0x20）和 v0 都不是我们的帧。 */
    @Test fun otherVersionsAreIgnored() {
        assertNull(parse(hex("20 02 C4 09")))
        assertNull(parse(hex("00 02 C4 09")))
        assertNull(parse(hex("60 02 C4 09")))       // v3
    }

    /** 加密帧：解不出对象，但 encrypted 标记要给出来，让上层决定忽略还是去解密。 */
    @Test fun encryptedFrameIsFlagged() {
        val f = parse(hex("41 01 02 03 04 05 06 07 08 09 0A 0B 0C 0D 0E 0F 10 11 12"))!!
        assertTrue(f.encrypted)
        assertEquals(2, f.version)
        assertTrue(f.measurements.isEmpty())
    }

    @Test fun triggerBasedFlag() {
        assertTrue(parse(hex("44 02 C4 09"))!!.trigger)
    }

    /** 越界的值照样解出来，由 inRange 标记，不能悄悄丢掉或直接相信。 */
    @Test fun outOfRangeValueIsKeptAndFlagged() {
        val f = parse(hex("40 03 A2 28"))!!          // 0x28A2 = 10402，× 0.01 = 104.02 %
        val h = f.measurements.single()
        assertEquals(104.02f, h.value, 0.001f)
        assertFalse(h.inRange)
        assertNotNull(f)
    }
}
