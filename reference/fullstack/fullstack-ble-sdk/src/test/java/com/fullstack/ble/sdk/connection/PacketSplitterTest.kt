package com.fullstack.ble.sdk.connection

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PacketSplitterTest {
    @Test
    fun `splits using negotiated payload size`() {
        val input = ByteArray(45) { it.toByte() }
        val chunks = PacketSplitter.split(input, 20)

        assertEquals(listOf(20, 20, 5), chunks.map { it.size })
        assertArrayEquals(input, chunks.reduce { acc, bytes -> acc + bytes })
    }

    @Test
    fun `single packet remains unchanged`() {
        val input = byteArrayOf(1, 2, 3)
        val chunks = PacketSplitter.split(input, 20)

        assertEquals(1, chunks.size)
        assertArrayEquals(input, chunks[0])
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects invalid chunk size`() {
        PacketSplitter.split(byteArrayOf(1), 0)
    }
}
