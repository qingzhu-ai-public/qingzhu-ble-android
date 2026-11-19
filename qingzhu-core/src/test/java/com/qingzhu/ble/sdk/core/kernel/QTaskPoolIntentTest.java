// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.sdk.core.kernel;

import android.os.Looper;

import com.qingzhu.ble.sdk.core.api.QCommand;
import com.qingzhu.ble.sdk.core.api.QTask;
import com.qingzhu.ble.sdk.core.api.Subscription;
import com.qingzhu.ble.sdk.core.api.TaskResult;
import com.qingzhu.ble.sdk.core.api.TaskSnapshot;
import com.qingzhu.ble.sdk.core.api.TaskStage;
import com.qingzhu.ble.sdk.core.api.TaskState;
import com.qingzhu.ble.sdk.core.ble.BleAdapter;
import com.qingzhu.ble.sdk.core.connect.ConnectParams;
import com.qingzhu.ble.sdk.core.device.QDevice;
import com.qingzhu.ble.sdk.core.pool.ReconnectPolicy;
import com.qingzhu.ble.sdk.core.protocol.ProtocolType;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class QTaskPoolIntentTest {

    private FakeBleAdapter adapter;
    private QTaskPool pool;
    private QDevice device;

    @Before
    public void setUp() {
        adapter = new FakeBleAdapter();
        pool = new QTaskPool(adapter, 0);
        pool.setMaxTransfers(1);
        pool.setMaxPendingIntents(8);
        device = new QDevice("AA:BB:CC:DD:EE:01", "device", -40, ProtocolType.of("test"));
    }

    /** 创建任务并连接成功，返回处于 CONNECTED 的任务 */
    private QTask connectedTask() {
        QTask task = pool.createTask(device);
        task.connect(null);
        idle();
        assertNotNull(adapter.connectSink);
        adapter.connectSink.onConnected(device.getMac());
        idle();
        assertEquals(TaskState.CONNECTED, task.getState());
        return task;
    }

    @Test
    public void createTaskReplacesExistingTask() {
        QTask first = pool.createTask(device);
        QTask second = pool.createTask(device);

        assertTrue(first.isDeleted());
        assertNotSame(first, second);
        assertSame(second, pool.getTask(device.getMac()));
        assertEquals(1, pool.snapshots().size());
    }

    @Test
    public void getTaskOnlyQueriesAndNeverCreates() {
        assertNull(pool.getTask("AA:BB:CC:DD:EE:99"));
        assertEquals(0, pool.snapshots().size());
    }

    @Test
    public void deleteTaskRemovesFromPool() {
        QTask task = pool.createTask(device);
        pool.deleteTask(device.getMac());

        assertTrue(task.isDeleted());
        assertNull(pool.getTask(device.getMac()));
        assertEquals(0, pool.snapshots().size());
    }

    @Test
    public void sendOnDeletedTaskCallsBackClosed() {
        QTask task = pool.createTask(device);
        task.delete();
        List<TaskResult> results = new ArrayList<>();

        task.send(QCommand.builder().send("{}").build(), results::add);
        idle();

        assertEquals(1, results.size());
        assertFalse(results.get(0).isSuccess());
        assertEquals(TaskStage.CLOSED, results.get(0).stage());
    }

    @Test
    public void connectAgainWhenConnectedSucceedsWithoutReconnecting() {
        QTask task = connectedTask();
        List<TaskResult> results = new ArrayList<>();

        task.connect(results::add);
        idle();

        assertEquals(1, adapter.connectCalls);
        assertEquals(1, results.size());
        assertTrue(results.get(0).isSuccess());
        assertEquals(TaskState.CONNECTED, task.getState());
    }

    @Test
    public void sendSuccessCallsBackPayloadOnce() {
        QTask task = connectedTask();
        List<TaskResult> results = new ArrayList<>();

        task.send(QCommand.builder().send("{\"a\":1}").keepConnected().build(), results::add);
        waitForSendCount(1);
        adapter.sendSink.onDone("echo");
        idle();

        assertEquals(1, results.size());
        assertTrue(results.get(0).isSuccess());
        assertEquals("echo", results.get(0).payload());
        assertEquals(TaskState.CONNECTED, task.getState());
    }

    @Test
    public void cancelWhileSendingKeepsConnectionIfConnectedBefore() {
        QTask task = connectedTask();
        List<TaskResult> results = new ArrayList<>();
        task.send(QCommand.builder().send("{}").keepConnected().build(), results::add);
        waitForSendCount(1);
        assertEquals(TaskState.SENDING, task.getState());

        task.cancel();
        idle();

        assertEquals(TaskState.CONNECTED, task.getState());
        assertEquals(0, adapter.disconnectCalls);
        assertEquals(1, adapter.abortCalls);
        assertEquals(1, results.size());
        assertEquals(TaskStage.CANCEL, results.get(0).stage());
        assertFalse(task.isDeleted());
    }

    @Test
    public void cancelWhileConnectingReturnsToDisconnected() {
        QTask task = pool.createTask(device);
        List<TaskResult> results = new ArrayList<>();
        task.connect(results::add);
        idle();
        assertEquals(TaskState.CONNECTING, task.getState());

        task.cancel();
        idle();

        assertEquals(TaskState.READY, task.getState());
        assertEquals(1, adapter.disconnectCalls);
        assertEquals(1, results.size());
        assertEquals(TaskStage.CANCEL, results.get(0).stage());
        assertFalse(task.isDeleted());
    }

    @Test
    public void deleteWhileSendingAbortsDisconnectsAndRemoves() {
        QTask task = connectedTask();
        List<TaskResult> results = new ArrayList<>();
        task.send(QCommand.builder().send("{}").keepConnected().build(), results::add);
        waitForSendCount(1);

        task.delete();
        idle();

        assertTrue(task.isDeleted());
        assertEquals(1, adapter.abortCalls);
        assertEquals(1, adapter.disconnectCalls);
        assertNull(pool.getTask(device.getMac()));
        assertEquals(1, results.size());
        assertEquals(TaskStage.DELETE, results.get(0).stage());
    }

    @Test
    public void disconnectKeepsTaskInPool() {
        QTask task = connectedTask();
        task.disconnect();
        idle();

        assertFalse(task.isDeleted());
        assertSame(task, pool.getTask(device.getMac()));
    }

    @Test
    public void staleConnectedCallbackOfReplacedTaskDoesNotDropSharedLink() {
        QTask first = pool.createTask(device);
        first.connect(null);
        idle();
        BleAdapter.ConnectSink firstSink = adapter.connectSink;
        assertNotNull(firstSink);

        // 快速重进：新任务替换旧任务（旧任务被删除并发起一次断开）
        QTask second = pool.createTask(device);
        second.connect(null);
        idle();
        int disconnectsBefore = adapter.disconnectCalls;

        // 底层共享的同一条链路连上了：旧任务的迟到回调不能把它断掉
        firstSink.onConnected(device.getMac());
        idle();

        assertEquals(disconnectsBefore, adapter.disconnectCalls);
        assertTrue(first.isDeleted());
        assertFalse(second.isDeleted());
    }

    @Test
    public void deletedTaskConnectedCallbackStillDisconnectsWhenNoReplacement() {
        QTask task = pool.createTask(device);
        task.connect(null);
        idle();
        BleAdapter.ConnectSink sink = adapter.connectSink;
        task.delete();
        idle();
        int disconnectsBefore = adapter.disconnectCalls;

        sink.onConnected(device.getMac());
        idle();

        assertEquals(disconnectsBefore + 1, adapter.disconnectCalls);
    }

    @Test
    public void disconnectAfterOverridesConnectedTaskAndDisconnects() {
        QTask task = connectedTask(); // connect() 让任务带「保持连接」期望
        List<TaskResult> results = new ArrayList<>();

        task.send(QCommand.builder().send("{}").disconnectAfter().build(), results::add);
        waitForSendCount(1);
        adapter.sendSink.onDone("ok");
        idle();

        assertEquals(1, results.size());
        assertTrue(results.get(0).isSuccess());
        assertEquals(1, adapter.disconnectCalls);
        assertEquals(TaskState.READY, task.getState());
    }

    @Test
    public void disconnectWhileSendingReallyDisconnects() {
        QTask task = connectedTask();
        task.send(QCommand.builder().send("{}").keepConnected().build(), null);
        waitForSendCount(1);
        assertEquals(TaskState.SENDING, task.getState());

        task.disconnect();
        idle();

        assertEquals(TaskState.READY, task.getState());
        assertEquals(1, adapter.abortCalls);
        assertEquals(1, adapter.disconnectCalls);
        assertFalse(task.isDeleted());
    }

    @Test
    public void observeAllStopsAfterCancel() {
        final List<List<TaskSnapshot>> received = new ArrayList<>();
        Subscription sub = pool.observeAll(this, received::add);
        idle();
        assertEquals(1, received.size()); // 注册后立即推送当前快照

        pool.createTask(device);
        idle();
        int afterCreate = received.size();
        assertTrue(afterCreate >= 2);

        sub.cancel();
        assertFalse(sub.isActive());
        pool.createTask("AA:BB:CC:DD:EE:02");
        idle();
        assertEquals(afterCreate, received.size());
    }

    @Test(expected = IllegalStateException.class)
    public void keepConnectedAndDisconnectAfterAreMutuallyExclusive() {
        QCommand.builder().send("{}").keepConnected().disconnectAfter().build();
    }

    private void waitForSendCount(int expected) {
        long deadline = System.currentTimeMillis() + 2_000L;
        while (adapter.sentCommands.size() < expected && System.currentTimeMillis() < deadline) {
            idle();
            try {
                Thread.sleep(5L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        idle();
        assertEquals(expected, adapter.sentCommands.size());
    }

    private void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static final class FakeBleAdapter implements BleAdapter {
        int connectCalls;
        int disconnectCalls;
        int abortCalls;
        ConnectSink connectSink;
        SendSink sendSink;
        final List<String> sentCommands = new ArrayList<>();

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
            connectCalls++;
            connectSink = sink;
        }

        @Override
        public void disconnect(String mac) {
            disconnectCalls++;
        }

        @Override
        public void send(String mac, String json, Map<String, Object> attachments, SendSink sink) {
            sentCommands.add(json);
            sendSink = sink;
        }

        @Override
        public void abortSend(String mac) {
            abortCalls++;
        }

        @Override
        public void prepareForLargeTransfer(String mac, Runnable onReady) {
            onReady.run();
        }

        @Override
        public void setConcurrency(int n) {
        }

        @Override
        public int concurrency() {
            return 1;
        }
    }
}
