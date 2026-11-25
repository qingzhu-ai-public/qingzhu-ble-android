// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.sdk.core;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.qingzhu.ble.sdk.core.api.QTask;
import com.qingzhu.ble.sdk.core.api.ScanOptions;
import com.qingzhu.ble.sdk.core.api.Subscription;
import com.qingzhu.ble.sdk.core.api.TaskSnapshot;
import com.qingzhu.ble.sdk.core.ble.AndroidBleAdapter;
import com.qingzhu.ble.sdk.core.ble.BleAdapter;
import com.qingzhu.ble.sdk.core.device.QDevice;
import com.qingzhu.ble.sdk.core.kernel.QTaskPool;

import java.util.ArrayList;
import java.util.List;

/**
 * SDK 内核入口（单例）：扫描 / 任务 / 调度参数。
 *
 * <pre>
 * QSdk sdk = QSdk.getInstance();
 * sdk.init(context);
 * QTask task = sdk.createTask(mac);
 * task.send(QCommand.builder().send(json).build(), result -&gt; { ... });
 * </pre>
 */
public final class QSdk {

    private static final QSdk INSTANCE = new QSdk();

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile BleAdapter adapter;
    private volatile QTaskPool taskPool;
    /** UI 层的扫描监听（可为 null = 只做任务池的寻人/重寻址，不展示） */
    private volatile ScanListener uiScanListener;
    /** 当前 UI 扫描设置（只影响 UI 回调，不影响任务池寻人） */
    private volatile ScanOptions uiScanOptions = ScanOptions.defaults();
    /** UI 扫描时长到点关闭 */
    private final Runnable uiScanTimeoutRunnable = this::stopScan;
    /** 扫描状态监听（扫描中/已停止） */
    private volatile ScanStateListener scanStateListener;
    /** 扫描是否已开启 */
    private volatile boolean scanning;
    /** 扫描会话代次：旧引擎停止/失败的回调据此被忽略 */
    private final java.util.concurrent.atomic.AtomicInteger scanGen =
            new java.util.concurrent.atomic.AtomicInteger(0);

    /** 初始化时保存的 applicationContext：协议裸连步骤取用 */
    private static volatile Context appContext;

    private QSdk() {
    }

    public static QSdk getInstance() {
        return INSTANCE;
    }

    /** SDK 初始化时保存的 applicationContext；未 init 时返回 null。 */
    public static Context appContext() {
        return appContext;
    }

    /** 初始化（注入 Android 平台实现 + 建任务池）。重复调用幂等。 */
    public void init(Context context) {
        appContext = context != null ? context.getApplicationContext() : null;
        if (adapter == null) {
            adapter = new AndroidBleAdapter(context);
        }
        if (taskPool == null) {
            taskPool = new QTaskPool(adapter);
            // 任务需要扫描（寻人 / 连接重寻址）时，自动开扫描
            taskPool.setScanRequiredListener(() -> main.post(this::ensureScanning));
        }
    }

    // ============ 扫描 ============

    /** 订阅扫描结果，并确保扫描开启 */
    public void startScan(ScanListener listener) {
        startScan(ScanOptions.defaults(), listener);
    }

    /** 按指定条件开始 UI 扫描；扫描时长和过滤只作用于 UI 回调，任务池仍能看到完整广播。 */
    public void startScan(ScanOptions options, ScanListener listener) {
        ensureInit();
        uiScanOptions = options != null ? options : ScanOptions.defaults();
        uiScanListener = listener;
        main.removeCallbacks(uiScanTimeoutRunnable);
        long timeoutMs = uiScanOptions.getTimeoutMs();
        if (timeoutMs > 0) {
            main.postDelayed(uiScanTimeoutRunnable, timeoutMs);
        }
        ensureScanning();
    }

    /** 停止扫描：退订 UI 回调并停止底层扫描；任务需要寻人时仍会按需重新拉起。 */
    public void stopScan() {
        main.removeCallbacks(uiScanTimeoutRunnable);
        uiScanListener = null;
        uiScanOptions = ScanOptions.defaults();
        if (adapter == null) {
            scanning = false;
            notifyScanState(false);
            return;
        }
        scanning = false;
        scanGen.incrementAndGet();
        adapter.stopScan();
        notifyScanState(false);
    }

    /** 订阅扫描状态（扫描中/已停止） */
    public void setScanStateListener(ScanStateListener listener) {
        scanStateListener = listener;
        ScanStateListener l = scanStateListener;
        if (l != null) {
            main.post(() -> l.onScanStateChanged(scanning));
        }
    }

    /**
     * 重启扫描：扫描运行中只重新注册系统扫描器（不清空设备快照）；
     * 已停止时先彻底停掉底层扫描再重建。
     */
    public void restartScan() {
        ensureInit();
        if (scanning) {
            adapter.restartScanner();
        } else {
            scanGen.incrementAndGet();
            adapter.stopScan();
            ensureScanning();
        }
    }

    /** 确保扫描开启：任务池寻人/重寻址依赖扫描缓存 */
    private void ensureScanning() {
        if (scanning) {
            return;
        }
        scanning = true;
        notifyScanState(true);
        final int gen = scanGen.incrementAndGet();
        adapter.startScan(new BleAdapter.ScanSink() {
            @Override
            public void onDevices(List<QDevice> devices) {
                taskPool.onDevices(devices);
                ScanListener l = uiScanListener;
                if (l != null) {
                    List<QDevice> uiDevices = filterForUi(devices);
                    main.post(() -> l.onDevices(uiDevices));
                }
            }

            @Override
            public void onScanFailed(int errorCode, String message) {
                if (gen != scanGen.get()) {
                    return;
                }
                scanning = false;
                notifyScanState(false);
                ScanListener l = uiScanListener;
                if (l != null) {
                    main.post(() -> l.onScanFailed(errorCode, message));
                }
            }

            @Override
            public void onScanStopped(List<QDevice> devices) {
                if (gen != scanGen.get()) {
                    return;
                }
                scanning = false;
                notifyScanState(false);
                ScanListener l = uiScanListener;
                if (l != null) {
                    List<QDevice> uiDevices = filterForUi(devices);
                    main.post(() -> l.onScanStopped(uiDevices));
                }
            }
        });
    }

    private void notifyScanState(boolean s) {
        ScanStateListener l = scanStateListener;
        if (l != null) {
            main.post(() -> l.onScanStateChanged(s));
        }
    }

    private List<QDevice> filterForUi(List<QDevice> devices) {
        ScanOptions options = uiScanOptions;
        if (options == null || !options.hasFilters()) {
            return devices;
        }
        List<QDevice> filtered = new ArrayList<>();
        for (QDevice device : devices) {
            if (options.accepts(device)) {
                filtered.add(device);
            }
        }
        return filtered;
    }

    public void clearScanCache() {
        ensureInit();
        adapter.clearScanCache();
    }

    // ============ 任务 ============

    /** 创建任务：该设备已有任务则先删除，再新建。 */
    public QTask createTask(String deviceMac) {
        ensureInit();
        return taskPool.createTask(deviceMac);
    }

    /** 用扫描到的设备创建任务：该设备已有任务则先删除，再新建。 */
    public QTask createTask(QDevice device) {
        ensureInit();
        return taskPool.createTask(device);
    }

    /** 只查询，不创建；不存在返回 null。 */
    public QTask getTask(String mac) {
        ensureInit();
        return taskPool.getTask(mac);
    }

    /** 删除该设备的任务（不存在则忽略）。 */
    public void deleteTask(String mac) {
        ensureInit();
        taskPool.deleteTask(mac);
    }

    /** 当前所有任务的不可变快照 */
    public List<TaskSnapshot> snapshots() {
        ensureInit();
        return taskPool.snapshots();
    }

    /** 观察所有任务：注册后立即推送一次当前快照，之后每次变化回调。 */
    public Subscription observeAll(Object owner, TaskListListener listener) {
        ensureInit();
        return taskPool.observeAll(owner, listener::onChanged);
    }

    // ============ 调度参数 ============

    /** 同时连接数：0 = 自动，1-N = 手动 */
    public void setMaxConnections(int n) {
        ensureInit();
        adapter.setConcurrency(n);
    }

    /** 同时传输数：1 = 串行，N = 最多 N 台同时传 */
    public void setMaxTransfers(int n) {
        ensureInit();
        taskPool.setMaxTransfers(n);
    }

    /** 任务失败自动重试次数：连接失败 / 发送失败 / 寻人超时都算一次。0 = 不重试，默认 3。 */
    public void setMaxRetries(int n) {
        ensureInit();
        taskPool.setMaxRetries(n);
    }

    /** 每台设备最多排队多少条待执行命令；0 = 忙碌时直接失败。默认 10_000。 */
    public void setMaxPendingIntents(int n) {
        ensureInit();
        taskPool.setMaxPendingIntents(n);
    }

    /** 系统连接状态回调兜底超时，默认 20 秒，最小 5 秒。 */
    public void setConnectCallbackTimeoutMs(long timeoutMs) {
        com.qingzhu.ble.sdk.core.platform.AndroidGattClient
                .setConnectCallbackTimeoutMs(timeoutMs);
    }

    /** 单条 SEND 命令应答超时，默认 8,000 ms；0 = 不启用。CONNECT / noAck 命令不启用。 */
    public void setCommandTimeoutMs(long timeoutMs) {
        if (timeoutMs < 0) {
            throw new IllegalArgumentException("命令超时不能为负数（0 = 不启用）");
        }
        com.qingzhu.ble.sdk.core.settings.BleSettings.get().commandTimeoutMs(timeoutMs);
    }

    /** 当前连接并发容量 */
    public int getConcurrency() {
        ensureInit();
        return adapter.concurrency();
    }

    private void ensureInit() {
        if (adapter == null || taskPool == null) {
            throw new IllegalStateException("QSdk 未初始化，请先调用 QSdk.getInstance().init(context)");
        }
    }

    // ============ 回调接口 ============

    /** 扫描监听 */
    public interface ScanListener {
        void onDevices(List<QDevice> devices);

        default void onScanFailed(int errorCode, String message) {
        }

        /** 扫描停止（定时超时自动停）时的最终回调 */
        default void onScanStopped(List<QDevice> devices) {
        }
    }

    /** 扫描状态监听：true = 扫描中，false = 已停止 */
    public interface ScanStateListener {
        void onScanStateChanged(boolean scanning);
    }

    /** 任务列表变化监听，回调在主线程，参数为不可变快照 */
    public interface TaskListListener {
        void onChanged(List<TaskSnapshot> snapshots);
    }
}
