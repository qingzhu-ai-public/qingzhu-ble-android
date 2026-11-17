// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.sdk.core.kernel;

import android.os.Looper;

import com.qingzhu.ble.sdk.core.api.QCommand;
import com.qingzhu.ble.sdk.core.api.QTask;
import com.qingzhu.ble.sdk.core.api.TaskIntentCallback;
import com.qingzhu.ble.sdk.core.api.TaskIntentResult;
import com.qingzhu.ble.sdk.core.api.TaskIntentStatus;
import com.qingzhu.ble.sdk.core.api.TaskOutcome;
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
import static org.junit.Assert.assertNotNull;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class QTaskPoolIntentTest {

    private FakeBleAdapter adapter;
    private QTaskPool pool;
    private QTask task;
    private QDevice device;

    @Before
    public void setUp() {
        adapter = new FakeBleAdapter();
        pool = new QTaskPool(adapter, 0);
        pool.setMaxTransfers(1);
        pool.setMaxPendingIntents(8);

        device = new QDevice("AA:BB:CC:DD:EE:01", "device", -40, ProtocolType.of("test"));
        task = pool.submit(device, QCommand.builder().connect().build());
        idle();
        assertNotNull(adapter.connectSink);
        adapter.connectSink.onConnected(device.getMac());
        idle();
        assertEquals(TaskState.CONNECTED, task.getState());
    }

    @Test
    public void connectAgainIsIdempotent() {
        TaskIntentResult result = task.executeIntent(QCommand.builder().connect().build());

        assertEquals(TaskIntentResult.ALREADY_SATISFIED, result);
        assertEquals(1, adapter.connectCalls);
        assertEquals(TaskState.CONNECTED, task.getState());
    }

    @Test
    public void connectDuringSendKeepsConnectionAndDoesNotOverwriteSend() {
        assertEquals(TaskIntentResult.STARTED,
                task.executeIntent(QCommand.builder().send("{\"action\":\"send-1\"}").build()));
        waitForSendCount(1);

        assertEquals(TaskIntentResult.ALREADY_SATISFIED,
                task.executeIntent(QCommand.builder().connect().build()));
        assertEquals(1, adapter.sentCommands.size());
        assertEquals(TaskState.SENDING, task.getState());

        adapter.sendSink.onDone("ok");
        idle();

        assertEquals(TaskState.CONNECTED, task.getState());
        assertEquals(0, adapter.disconnectCalls);
        assertEquals(TaskOutcome.SUCCEEDED, task.getOutcome());
    }

    @Test
    public void sendLoopQueuesUpToBoundAndRejectsExcess() {
        assertEquals(TaskIntentResult.STARTED,
                task.executeIntent(QCommand.builder().send("{\"action\":\"send-0\"}").build()));
        waitForSendCount(1);

        TaskIntentResult last = null;
        for (int i = 1; i <= 9; i++) {
            last = task.executeIntent(
                    QCommand.builder().send("{\"action\":\"send-" + i + "\"}").build());
        }

        assertEquals(TaskIntentResult.BUSY, last);
        assertEquals(8, task.pendingIntentCount());
        assertEquals(1, adapter.sentCommands.size());

        adapter.sendSink.onDone("ok");
        idle();
        waitForSendCount(2);
        assertEquals("{\"action\":\"send-1\"}", adapter.sentCommands.get(1));
        assertEquals(7, task.pendingIntentCount());
    }

    @Test
    public void sendWhileConnectingWaitsForConnectThenStarts() {
        QDevice secondDevice = new QDevice(
                "AA:BB:CC:DD:EE:02", "device-2", -42, ProtocolType.of("test"));
        QTask secondTask = pool.submit(
                secondDevice, QCommand.builder().connect().build());
        idle();

        assertEquals(TaskIntentResult.QUEUED,
                secondTask.executeIntent(
                        QCommand.builder().send("{\"action\":\"after-connect\"}").build()));
        assertEquals(TaskState.CONNECTING, secondTask.getState());

        adapter.connectSink.onConnected(secondDevice.getMac());
        idle();
        waitForSendCount(1);

        assertEquals(TaskState.SENDING, secondTask.getState());
        assertEquals(0, secondTask.pendingIntentCount());
    }

    @Test
    public void deletedTaskRejectsLaterIntents() {
        task.delete();
        idle();

        assertEquals(TaskIntentResult.CLOSED,
                task.executeIntent(QCommand.builder().send("{}").build()));
        assertEquals(true, task.isDeleted());
    }

    @Test
    public void queuedSendCallbacksFinishInOrder() {
        TestCallback first = new TestCallback();
        TestCallback second = new TestCallback();

        assertEquals(TaskIntentResult.STARTED, task.executeIntent(
                QCommand.builder().send("{\"action\":\"first\"}").build(), first));
        waitForSendCount(1);
        assertEquals(TaskIntentResult.QUEUED, task.executeIntent(
                QCommand.builder().send("{\"action\":\"second\"}").build(), second));

        adapter.sendSink.onDone("first-result");
        idle();
        waitForSendCount(2);

        assertEquals(1, first.records.size());
        assertEquals(TaskIntentStatus.SUCCEEDED, first.records.get(0).status);
        assertEquals("first-result", first.records.get(0).result);
        assertEquals(0, second.records.size());

        adapter.sendSink.onDone("second-result");
        idle();

        assertEquals(1, second.records.size());
        assertEquals(TaskIntentStatus.SUCCEEDED, second.records.get(0).status);
        assertEquals("second-result", second.records.get(0).result);
    }

    @Test
    public void connectDuringSendCallbackReportsSuccess() {
        TestCallback connectCallback = new TestCallback();
        assertEquals(TaskIntentResult.STARTED,
                task.executeIntent(QCommand.builder().send("{}").build()));
        waitForSendCount(1);

        assertEquals(TaskIntentResult.ALREADY_SATISFIED,
                task.executeIntent(QCommand.builder().connect().build(), connectCallback));
        idle();

        assertEquals(1, connectCallback.records.size());
        assertEquals(TaskIntentStatus.SUCCEEDED, connectCallback.records.get(0).status);
    }

    @Test
    public void terminalFailureFailsCurrentAndCancelsPending() {
        TestCallback current = new TestCallback();
        TestCallback pending = new TestCallback();

        assertEquals(TaskIntentResult.STARTED,
                task.executeIntent(QCommand.builder().send("{}").build(), current));
        waitForSendCount(1);
        assertEquals(TaskIntentResult.QUEUED,
                task.executeIntent(QCommand.builder().send("{}").build(), pending));

        adapter.sendSink.onFailed("boom");
        idle();

        assertEquals(1, current.records.size());
        assertEquals(TaskIntentStatus.FAILED, current.records.get(0).status);
        assertEquals(1, pending.records.size());
        assertEquals(TaskIntentStatus.CANCELLED, pending.records.get(0).status);
    }

    @Test
    public void cancelNotificationsAreDeliveredPerIntent() {
        TestCallback current = new TestCallback();
        TestCallback pending = new TestCallback();

        assertEquals(TaskIntentResult.STARTED,
                task.executeIntent(QCommand.builder().send("{}").build(), current));
        waitForSendCount(1);
        assertEquals(TaskIntentResult.QUEUED,
                task.executeIntent(QCommand.builder().send("{}").build(), pending));

        task.cancel();
        idle();

        assertEquals(TaskIntentStatus.CANCELLED, current.records.get(0).status);
        assertEquals(TaskIntentStatus.CANCELLED, pending.records.get(0).status);
    }

    @Test
    public void cancelWhileSendingClearsPendingAndDisconnects() {
        assertEquals(TaskIntentResult.STARTED,
                task.executeIntent(QCommand.builder().send("{\"action\":\"send-1\"}").build()));
        waitForSendCount(1);
        assertEquals(TaskIntentResult.QUEUED,
                task.executeIntent(QCommand.builder().send("{\"action\":\"send-2\"}").build()));
        assertEquals(1, task.pendingIntentCount());

        task.cancel();
        idle();

        assertEquals(TaskState.READY, task.getState());
        assertEquals(0, task.pendingIntentCount());
        assertEquals(1, adapter.disconnectCalls);
        assertEquals(TaskOutcome.CANCELLED, task.getOutcome());
    }

    @Test
    public void sendFailureSetsOperationFailedOutcome() {
        assertEquals(TaskIntentResult.STARTED,
                task.executeIntent(QCommand.builder().send("{}").build()));
        waitForSendCount(1);

        adapter.sendSink.onFailed("operation failed");
        idle();

        assertEquals(TaskOutcome.OPERATION_FAILED, task.getOutcome());
    }

    @Test
    public void connectFailureSetsConnectFailedOutcome() {
        QDevice secondDevice = new QDevice(
                "AA:BB:CC:DD:EE:03", "device-3", -45, ProtocolType.of("test"));
        QTask secondTask = pool.submit(
                secondDevice, QCommand.builder().connect().build());
        idle();

        adapter.connectSink.onFailed(secondDevice.getMac(), 133, "connect failed");
        idle();

        assertEquals(TaskOutcome.CONNECT_FAILED, secondTask.getOutcome());
    }

    @Test
    public void deletedTaskOutcomeIsCancelled() {
        QDevice secondDevice = new QDevice(
                "AA:BB:CC:DD:EE:04", "device-4", -45, ProtocolType.of("test"));
        QTask secondTask = pool.submit(
                secondDevice, QCommand.builder().connect().build());
        idle();

        secondTask.delete();
        idle();

        assertEquals(TaskOutcome.CANCELLED, secondTask.getOutcome());
        assertEquals(true, secondTask.isDeleted());
    }

    @Test
    public void nextPendingIntentResetsOutcomeUntilFinal() {
        assertEquals(TaskIntentResult.STARTED,
                task.executeIntent(QCommand.builder().send("{\"action\":\"first\"}").build()));
        waitForSendCount(1);
        assertEquals(TaskIntentResult.QUEUED,
                task.executeIntent(QCommand.builder().send("{\"action\":\"second\"}").build()));

        adapter.sendSink.onDone("first");
        idle();
        waitForSendCount(2);

        // 第二条在途：outcome 应回到 NONE，不能停留在上一条的终态（否则批次会提前收口）
        assertEquals(TaskOutcome.NONE, task.getOutcome());

        adapter.sendSink.onDone("second");
        idle();

        assertEquals(TaskOutcome.SUCCEEDED, task.getOutcome());
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

    private static final class TestCallback implements TaskIntentCallback {
        final List<Record> records = new ArrayList<>();

        @Override
        public void onFinished(
                String intentId,
                TaskIntentStatus status,
                String result,
                String error
        ) {
            records.add(new Record(status, result, error));
        }
    }

    private static final class Record {
        final TaskIntentStatus status;
        final String result;
        final String error;

        Record(TaskIntentStatus status, String result, String error) {
            this.status = status;
            this.result = result;
            this.error = error;
        }
    }

    private static final class FakeBleAdapter implements BleAdapter {
        int connectCalls;
        int disconnectCalls;
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
