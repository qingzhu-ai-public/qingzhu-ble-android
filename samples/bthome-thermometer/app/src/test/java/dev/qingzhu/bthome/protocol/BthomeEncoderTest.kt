// SPDX-License-Identifier: MIT
package dev.qingzhu.bthome.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** 编码器 + 31 字节预算的单元测试。 */
class BthomeEncoderTest {

    private fun hex(s: String): ByteArray =
        s.split(" ").filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()

    @Test fun encodesOfficialExample() {
        val bytes = encode(
            listOf(
                Measurement(Obj.TEMPERATURE, 25.00f),
                Measurement(Obj.HUMIDITY, 50.55f),
            )
        )
        assertEquals("40 02 C4 09 03 BF 13", bytes.toHex())
    }

    /** 调用方给什么顺序都行，编码器负责排成升序。 */
    @Test fun sortsObjectsById() {
        val bytes = encode(
            listOf(
                Measurement(Obj.HUMIDITY, 50.55f),
                Measurement(Obj.VOLTAGE, 3.074f),
                Measurement(Obj.TEMPERATURE, 25.00f),
                Measurement(Obj.BATTERY, 90f),
            )
        )
        assertEquals("40 01 5A 02 C4 09 03 BF 13 0C 02 0C", bytes.toHex())
    }

    @Test fun encodesNegativeTemperatureLittleEndian() {
        assertEquals("40 02 DA FD", encode(listOf(Measurement(Obj.TEMPERATURE, -5.50f))).toHex())
    }

    @Test fun encodesZero() {
        assertEquals("40 02 00 00 03 00 00", encode(listOf(Measurement(Obj.TEMPERATURE, 0f), Measurement(Obj.HUMIDITY, 0f))).toHex())
    }

    /** 编码 → 解析，绕一圈回到原值：任意随机值都成立。 */
    @Test fun roundTrip() {
        val rnd = Random(42)
        repeat(500) {
            val ms = listOf(
                Measurement(Obj.TEMPERATURE, rnd.nextInt(-4000, 8500 + 1) / 100f),
                Measurement(Obj.HUMIDITY, rnd.nextInt(0, 10000 + 1) / 100f),
                Measurement(Obj.BATTERY, rnd.nextInt(0, 100 + 1).toFloat()),
                Measurement(Obj.VOLTAGE, rnd.nextInt(0, 65535 + 1) / 1000f),
                Measurement(Obj.PACKET_ID, rnd.nextInt(0, 255 + 1).toFloat()),
            )
            val frame = parse(encode(ms))!!
            for (m in ms) {
                assertEquals(m.obj.name, m.value, frame[m.obj]!!, 0.0011f)
            }
        }
    }

    // ---------------------------------------------------------------- 预算

    /** 官方示例整包 26 字节：Flags 3 + 名字 12 + 服务数据 11。 */
    @Test fun officialPayloadBudget() {
        val data = hex("40 02 C4 09 03 BF 13")
        assertEquals(14, legacyAdvBytes(data))
        assertEquals(12, legacyScanResponseBytes("DIY-sensor"))
        assertEquals(26, legacyAdvBytes(data) + legacyScanResponseBytes("DIY-sensor"))
    }

    /** 主包只放温湿度 + 名字时放得下；再加电量也放得下。 */
    @Test fun mainPayloadFits() {
        val th = encode(listOf(Measurement(Obj.TEMPERATURE, 25.1f), Measurement(Obj.HUMIDITY, 45.2f)))
        assertEquals(23, legacyAdvBytes(th) + legacyScanResponseBytes("QZ-TH01"))
        val thb = encode(listOf(Measurement(Obj.TEMPERATURE, 25.1f), Measurement(Obj.HUMIDITY, 45.2f), Measurement(Obj.BATTERY, 90f)))
        assertEquals(25, legacyAdvBytes(thb) + legacyScanResponseBytes("QZ-TH01"))   // 数据 9 字节
    }

    /** 60 字节的长名字放主包一定爆；挪到扫描响应后主包只有 14 字节。 */
    @Test fun longNameOverflowsMainPayloadOnly() {
        val longName = "Qingzhu-BTHome-Temperature-And-Humidity-Sensor-For-Classroom"
        val data = hex("40 02 C4 09 03 BF 13")
        val inMain = legacyAdvBytes(data) + legacyScanResponseBytes(longName)
        assertTrue("expected > 31 but was $inMain", inMain > 31)
        assertEquals(76, inMain)
        assertTrue(legacyAdvBytes(data) <= 31)
        assertTrue(legacyScanResponseBytes(longName) > 31)
    }

    @Test fun emptyNameCostsNothing() {
        assertEquals(0, legacyScanResponseBytes(""))
    }

    /** 汉字按 UTF-8 每个占 3 字节，不能按字符数算预算。 */
    @Test fun chineseNameIsCountedInBytes() {
        assertEquals(2 + 3 * 5, legacyScanResponseBytes("教室温湿度"))
    }
}
