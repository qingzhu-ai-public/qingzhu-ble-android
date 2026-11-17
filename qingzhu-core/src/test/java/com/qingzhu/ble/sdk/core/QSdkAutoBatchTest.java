// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.sdk.core;

import android.os.Looper;
import android.os.SystemClock;

import com.qingzhu.ble.adaptive.AdaptiveConcurrency;
import com.qingzhu.ble.sdk.core.api.QCommand;
import com.qingzhu.ble.sdk.core.api.QTask;
import com.qingzhu.ble.sdk.core.api.TaskOutcome;
import com.qingzhu.ble.sdk.core.ble.BleAdapter;
import com.qingzhu.ble.sdk.core.connect.ConnectParams;
import com.qingzhu.ble.sdk.core.device.QDevice;
import com.qingzhu.ble.sdk.core.kernel.QTaskPool;
import com.qingzhu.ble.sdk.core.pool.ReconnectPolicy;
import com.qingzhu.ble.sdk.core.protocol.ProtocolType;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 自动批次收集的端到端测试：普通 submit → 全部终态 → 静默窗口 → recordBatch 回灌。
 * 通过反射给 QSdk 单例注入 FakeAdapter + 独立 QTaskPool，避免触碰真实蓝牙栈。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class QSdkAutoBatchTest {

    /** 与 QSdk 的收批静默窗口对齐 */
    private static final long QUIET_MS = 3_000;

    private FakeAdapter adapter;
    private QTaskPool pool;
    private QSdk sdk;
    /** 虚拟时钟基准（Robolectric 下用 setCurrentTimeMillis 推进 SystemClock） */
    private long now = 100_000L;

    @Before
    public void setUp() throws Exception {
        SystemClock.setCurrentTimeMillis(now);
        adapter = new FakeAdapter();
        pool = new QTaskPool(adapter, 0);
        pool.setMaxTransfers(7); // 测试里不等传输窗口
        sdk = QSdk.getInstance();
        setField("adapter", adapter);
        setField("taskPool", pool);
        sdk.setMaxConnections(0); // 自动并发模式
        adapter.concurrencyZeroCalls = 0; // 排掉 setUp 触发的这一次
    }

    @After
    public void tearDown() throws Exception {
        try {
            pool.clear();
            Shadows.shadowOf(Looper.getMainLooper()).runToEndOfTasks(); // 冲掉残留的轮询
            clearCollectionField("autoBatchTasks");
            clearCollectionField("autoBatchTaskIds");
            clearCollectionField("batchOwnedTaskIds");
            setField("autoBatchCheckScheduled", false);
            setField("autoBatchStartAt", 0L);
            setField("autoBatchLastSubmitAt", 0L);
        } finally {
            setField("adapter", null);
            setField("taskPool", null);
        }
    }

    @Test
    public void autoBatchReportsAfterAllTerminalAndQuietWindow() {
        int runsBefore = AdaptiveConcurrency.get().snapshot().getRuns();

        QTask t1 = sdk.submit(device("AA:BB:CC:DD:EE:01"), send());
        QTask t2 = sdk.submit(device("AA:BB:CC:DD:EE:02"), send());
        QTask t3 = sdk.submit(device("AA:BB:CC:DD:EE:03"), send());
        driveToSettled();

        assertEquals(TaskOutcome.SUCCEEDED, t1.getOutcome());
        assertEquals(TaskOutcome.SUCCEEDED, t2.getOutcome());
        assertEquals(TaskOutcome.SUCCEEDED, t3.getOutcome());

        // 还在静默窗口内：不收批
        advanceClock(QUIET_MS - 1_000);
        assertEquals(0, adapter.concurrencyZeroCalls);

        // 过静默窗口：收批并回灌（自动模式下应用建议值 = setConcurrency(0)）
        advanceClock(2_000);
        assertEquals(1, adapter.concurrencyZeroCalls);
        assertEquals(runsBefore + 1, AdaptiveConcurrency.get().snapshot().getRuns());
    }

    @Test
    public void slowSubmitLoopStaysInOneBatch() {
        int runsBefore = AdaptiveConcurrency.get().snapshot().getRuns();

        sdk.submit(device("AA:BB:CC:DD:EE:01"), send());
        driveToSettled();
        advanceClock(1_000); // 静默窗口内补提交，不拆批

        sdk.submit(device("AA:BB:CC:DD:EE:02"), send());
        driveToSettled();
        advanceClock(1_000);

        sdk.submit(device("AA:BB:CC:DD:EE:03"), send());
        driveToSettled();
        advanceClock(4_000);

        assertEquals(1, adapter.concurrencyZeroCalls); // 3 台合成一个批次，只回灌一次
        assertEquals(runsBefore + 1, AdaptiveConcurrency.get().snapshot().getRuns());
    }

    @Test
    public void explicitBatchAndAutoSubmitDoNotDoubleReport() {
        int runsBefore = AdaptiveConcurrency.get().snapshot().getRuns();

        QSdk.Batch batch = sdk.beginBatch();
        assertNotNull(batch.submit(device("AA:BB:CC:DD:EE:01"), send()));
        assertNotNull(batch.submit(device("AA:BB:CC:DD:EE:02"), send()));
        assertNotNull(batch.submit(device("AA:BB:CC:DD:EE:03"), send()));
        // 同两台设备又走普通 submit（幂等复用同一任务）：不得重复统计
        assertNotNull(sdk.submit(device("AA:BB:CC:DD:EE:02"), send()));
        assertNotNull(sdk.submit(device("AA:BB:CC:DD:EE:03"), send()));
        // 第 4 台普通 submit：不足以单独成批
        assertNotNull(sdk.submit(device("AA:BB:CC:DD:EE:04"), send()));
        batch.finish();

        driveToSettled();
        advanceClock(4_000);

        assertEquals(runsBefore + 1, AdaptiveConcurrency.get().snapshot().getRuns());
    }

    @Test
    public void manualConcurrencyRecordsButNeverOverrides() {
        int runsBefore = AdaptiveConcurrency.get().snapshot().getRuns();

        sdk.setMaxConnections(5);
        assertEquals(5, adapter.manualConcurrency);

        sdk.submit(device("AA:BB:CC:DD:EE:01"), send());
        sdk.submit(device("AA:BB:CC:DD:EE:02"), send());
        sdk.submit(device("AA:BB:CC:DD:EE:03"), send());
        driveToSettled();
        advanceClock(4_000);

        // 手动模式：批次照常记入学习经验，但不回写并发
        assertEquals(runsBefore + 1, AdaptiveConcurrency.get().snapshot().getRuns());
        assertEquals(0, adapter.concurrencyZeroCalls);
    }

    @Test
    public void explicitBatchCannotQueueCommandOnTaskOwnedByAnotherExplicitBatch() {
        QSdk.Batch first = sdk.beginBatch();
        QTask ownedTask = first.submit(device("AA:BB:CC:DD:EE:21"), send());
        assertNotNull(ownedTask);

        QSdk.Batch second = sdk.beginBatch();
        assertNull(second.submit(device("AA:BB:CC:DD:EE:21"), send()));

        // 如果命令在归属预检查前执行，这里会多出一条待办
        assertEquals(0, ownedTask.pendingIntentCount());

        first.cancel();
        second.cancel();
    }

    @Test
    public void placeholderTasksDoNotBlockAutoBatch() {
        int runsBefore = AdaptiveConcurrency.get().snapshot().getRuns();

        // 无命令的占位任务：不进入学习批次（否则永不终态，批次被卡死）
        assertNotNull(sdk.submit(device("AA:BB:CC:DD:EE:01"), null));
        assertNotNull(sdk.submit(device("AA:BB:CC:DD:EE:02"), null));
        assertNotNull(sdk.submit(device("AA:BB:CC:DD:EE:03"), null));

        sdk.submit(device("AA:BB:CC:DD:EE:11"), send());
        sdk.submit(device("AA:BB:CC:DD:EE:12"), send());
        sdk.submit(device("AA:BB:CC:DD:EE:13"), send());
        driveToSettled();
        advanceClock(4_000);

        assertEquals(runsBefore + 1, AdaptiveConcurrency.get().snapshot().getRuns());
    }

    // ============ 驱动与工具 ============

    /** 反复消费 FakeAdapter 的挂起回调，直到任务池里所有任务都不再执行 */
    private void driveToSettled() {
        long deadline = System.currentTimeMillis() + 5_000L;
        while (System.currentTimeMillis() < deadline && !allSettled()) {
            idle();
            drainFakeCallbacks();
            try {
                Thread.sleep(5L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        idle();
        assertTrue("任务未在时限内全部终态", allSettled());
    }

    private boolean allSettled() {
        for (QTask task : pool.list()) {
            if (task.isDeleted()) {
                continue;
            }
            if (task.getState().isRunning() || task.pendingIntentCount() > 0) {
                return false;
            }
        }
        return true;
    }

    private void drainFakeCallbacks() {
        Map<String, BleAdapter.ConnectSink> connects = new HashMap<>(adapter.connectSinks);
        adapter.connectSinks.clear();
        for (Map.Entry<String, BleAdapter.ConnectSink> e : connects.entrySet()) {
            e.getValue().onConnected(e.getKey());
        }
        idle(); // doSend 的 post 在这里落地，注册 sendSink
        Map<String, BleAdapter.SendSink> sends = new HashMap<>(adapter.sendSinks);
        adapter.sendSinks.clear();
        for (Map.Entry<String, BleAdapter.SendSink> e : sends.entrySet()) {
            e.getValue().onDone("ok");
        }
        idle();
    }

    private void advanceClock(long ms) {
        now += ms;
        SystemClock.setCurrentTimeMillis(now);
        idle();
    }

    private void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private void setField(String name, Object value) throws Exception {
        Field field = QSdk.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(sdk, value);
    }

    @SuppressWarnings("unchecked")
    private void clearCollectionField(String name) throws Exception {
        Field field = QSdk.class.getDeclaredField(name);
        field.setAccessible(true);
        Object value = field.get(sdk);
        if (value instanceof Collection) {
            ((Collection<Object>) value).clear();
        }
    }

    private static QDevice device(String mac) {
        return new QDevice(mac, "device-" + mac.substring(mac.length() - 2), -40,
                ProtocolType.of("test"));
    }

    private static QCommand send() {
        return QCommand.builder().send("{\"action\":\"echo\"}").build();
    }

    private static final class FakeAdapter implements BleAdapter {
        final Map<String, ConnectSink> connectSinks = new HashMap<>();
        final Map<String, SendSink> sendSinks = new HashMap<>();
        int concurrencyZeroCalls;
        int manualConcurrency;
        int currentConcurrency = 4;

        @Override
        public void startScan(ScanSink sink) {
        }

        @Override
        public void stopScan() {
        }

        @Override
        public void clearScanCache() {
        }

        @Override
        public void connect(
                QDevice device,
                ConnectParams params,
                ReconnectPolicy reconnectPolicy,
                ConnectSink sink
        ) {
            connectSinks.put(device.getMac(), sink);
        }

        @Override
        public void disconnect(String mac) {
        }

        @Override
        public void send(String mac, String json, Map<String, Object> attachments, SendSink sink) {
            sendSinks.put(mac, sink);
        }

        @Override
        public void abortSend(String mac) {
        }

        @Override
        public void prepareForLargeTransfer(String mac, Runnable onReady) {
            onReady.run();
        }

        @Override
        public void setConcurrency(int n) {
            if (n <= 0) {
                concurrencyZeroCalls++;
                currentConcurrency = 4;
            } else {
                manualConcurrency = n;
                currentConcurrency = n;
            }
        }

        @Override
        public int concurrency() {
            return currentConcurrency;
        }
    }
}
