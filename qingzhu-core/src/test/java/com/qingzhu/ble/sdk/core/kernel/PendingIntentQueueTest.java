// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.sdk.core.kernel;

import com.qingzhu.ble.sdk.core.api.QCommand;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class PendingIntentQueueTest {

    @Test
    public void offerKeepsFifoOrder() {
        PendingIntentQueue<QCommand> queue = new PendingIntentQueue<>();
        QCommand first = QCommand.builder().send("{\"action\":\"one\"}").build();
        QCommand second = QCommand.builder().send("{\"action\":\"two\"}").build();

        assertTrue(queue.offer(first, 2));
        assertTrue(queue.offer(second, 2));

        assertEquals(2, queue.size());
        assertSame(first, queue.poll());
        assertSame(second, queue.poll());
        assertNull(queue.poll());
    }

    @Test
    public void fullQueueRejectsInsteadOfDropping() {
        PendingIntentQueue<QCommand> queue = new PendingIntentQueue<>();
        QCommand first = QCommand.builder().send("{\"action\":\"one\"}").build();
        QCommand second = QCommand.builder().send("{\"action\":\"two\"}").build();

        assertTrue(queue.offer(first, 1));
        assertFalse(queue.offer(second, 1));
        assertEquals(1, queue.size());
        assertSame(first, queue.poll());
    }

    @Test
    public void clearRemovesAllPendingCommands() {
        PendingIntentQueue<QCommand> queue = new PendingIntentQueue<>();
        queue.offer(QCommand.builder().send("{}").build(), 2);
        queue.offer(QCommand.builder().send("{}").build(), 2);

        queue.clear();

        assertEquals(0, queue.size());
        assertNull(queue.poll());
    }
}
