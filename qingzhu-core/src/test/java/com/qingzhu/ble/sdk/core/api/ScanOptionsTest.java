// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.sdk.core.api;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.qingzhu.ble.sdk.core.device.QDevice;
import com.qingzhu.ble.sdk.core.protocol.ProtocolType;

import org.junit.Test;

public class ScanOptionsTest {

    private QDevice device(String name, int rssi, String protocolId, String stableId) {
        QDevice d = new QDevice("AA:BB:CC:DD:EE:01", name, rssi, ProtocolType.of(protocolId));
        d.setStableId(stableId);
        return d;
    }

    @Test
    public void defaultsAcceptAll() {
        ScanOptions options = ScanOptions.defaults();
        assertFalse(options.hasFilters());
        assertTrue(options.accepts(device("anything", -90, "generic", "S1")));
    }

    @Test
    public void filtersByRssiNameProtocolAndStableId() {
        ScanOptions options = ScanOptions.builder()
                .setMinRssi(-80)
                .setNamePrefix("esl")
                .addProtocol("generic")
                .addStableId("S1")
                .build();
        assertTrue(options.hasFilters());
        assertTrue(options.accepts(device("ESL-001", -70, "generic", "S1")));
        assertFalse(options.accepts(device("ESL-001", -85, "generic", "S1")));
        assertFalse(options.accepts(device("OTHER", -70, "generic", "S1")));
        assertFalse(options.accepts(device("ESL-001", -70, "eddystone", "S1")));
        assertFalse(options.accepts(device("ESL-001", -70, "generic", "S2")));
    }

    @Test
    public void timeoutIsUnlimitedAtZeroAndHasMinimum() {
        assertTrue(ScanOptions.builder().build().getTimeoutMs() == ScanOptions.UNLIMITED);
        assertTrue(ScanOptions.builder().setTimeoutMs(500).build().getTimeoutMs() >= 1_000);
    }
}
