package com.fullstack.ble.sdk.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SerialOperationQueueTest {
    @Test
    fun `only one operation is in flight`() {
        val queue = SerialOperationQueue<String>()
        queue.enqueue("write-1")
        queue.enqueue("write-2")

        assertEquals("write-1", queue.startNext())
        assertNull(queue.startNext())
        assertEquals("write-1", queue.current)
        assertEquals(1, queue.pendingCount)
    }

    @Test
    fun `stale completion cannot finish a different operation`() {
        val queue = SerialOperationQueue<String>()
        queue.enqueue("write-1")
        queue.enqueue("write-2")
        queue.startNext()

        assertEquals(false, queue.complete("write-2"))
        assertEquals("write-1", queue.current)
        assertEquals(true, queue.complete("write-1"))
        assertEquals("write-2", queue.startNext())
    }

    @Test
    fun `drain returns current and pending operations exactly once`() {
        val queue = SerialOperationQueue<String>()
        queue.enqueue("notify")
        queue.enqueue("write")
        queue.enqueue("mtu")
        queue.startNext()

        assertEquals(listOf("notify", "write", "mtu"), queue.drain())
        assertEquals(emptyList<String>(), queue.drain())
        assertNull(queue.current)
    }
}
