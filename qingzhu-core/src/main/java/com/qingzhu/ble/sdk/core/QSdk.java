// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.sdk.core;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.qingzhu.ble.adaptive.AdaptiveConfig;
import com.qingzhu.ble.adaptive.AdaptiveConcurrency;
import com.qingzhu.ble.adaptive.BatchOutcome;
import com.qingzhu.ble.sdk.core.api.QCommand;
import com.qingzhu.ble.sdk.core.api.QTask;
import com.qingzhu.ble.sdk.core.api.TaskIntentResult;
import com.qingzhu.ble.sdk.core.api.TaskOutcome;
import com.qingzhu.ble.sdk.core.ble.AndroidBleAdapter;
import com.qingzhu.ble.sdk.core.ble.BleAdapter;
import com.qingzhu.ble.sdk.core.common.QLog;
import com.qingzhu.ble.sdk.core.device.QDevice;
import com.qingzhu.ble.sdk.core.kernel.QTaskPool;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * SDK 入口（单例）：扫描 / 提交任务 / 任务池 / 订阅 / 两个旋钮。
 *
 * <pre>
 * QSdk sdk = QSdk.getInstance();
 * sdk.init(context);
 * sdk.startScan(devices -&gt; showList(devices));
 * QTask t = sdk.submit(QTask.builder()...);   // 见 {@link QTask} 构造
 * sdk.getTasks().clear();
 * </pre>
 */
public final class QSdk {

    private static final QSdk INSTANCE = new QSdk();

    private static final String TAG = "QSdk";
    /** 自动批次轮询间隔 */
    private static final long AUTO_BATCH_CHECK_MS = 250;
    /** 收批静默窗口：全部终态后再等这么久，慢速提交循环仍能补进同一批次（避免批次被拆散） */
    private static final long AUTO_BATCH_QUIET_MS = 3_000;
    /** 自动批次兜底超时：超过仍未收口则强制结算（未终态任务按取消处理，本轮不参与学习） */
    private static final long AUTO_BATCH_TIMEOUT_MS = 10 * 60_000L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile BleAdapter adapter;
    private volatile QTaskPool taskPool;
    /** UI 层的扫描监听（可为 null = 只做任务池的寻人/重寻址，不展示） */
    private volatile ScanListener uiScanListener;
    /** 扫描状态监听（扫描中/已停止）：UI 据此显示滚动图标 + 文字 */
    private volatile ScanStateListener scanStateListener;
    /** 扫描是否已开启（一旦开启就持续跑，直到 SDK 释放，满足「任务随时需要扫描」的自动扫描语义） */
    private volatile boolean scanning;
    /** 扫描会话代次：每次开新扫描递增。旧引擎停止/失败的回调（携带旧代次）据此被忽略，
     *  避免 restartScan 时旧引擎的 onScanStopped 把 scanning 标志误改回 false（UI 卡在「扫描已停止」）。 */
    private final java.util.concurrent.atomic.AtomicInteger scanGen =
            new java.util.concurrent.atomic.AtomicInteger(0);
    /** 当前是否使用自动并发；手动指定时批次学习只记录经验，不强行覆盖设置 */
    private volatile boolean autoConcurrency = true;
    /** 自动学习批次：普通 submit 的任务先进入这里，全部终态并度过静默窗口后统一回灌 */
    private final List<QTask> autoBatchTasks = new ArrayList<>();
    private final Set<String> autoBatchTaskIds = new HashSet<>();
    /** 所有活动批次（自动 + 显式 Batch）已认领的任务 id：同一任务不允许被两个批次容器重复统计 */
    private final Set<String> batchOwnedTaskIds = new HashSet<>();
    private boolean autoBatchCheckScheduled;
    /** 批次计时（SystemClock.elapsedRealtime()）；autoBatchStartAt == 0 表示当前无活动批次 */
    private long autoBatchStartAt;
    private long autoBatchLastSubmitAt;

    /** 初始化时保存的 applicationContext：协议裸连步骤（connectSteps 里自己 connectGatt）取用 */
    private static volatile Context appContext;

    private QSdk() {
    }

    public static QSdk getInstance() {
        return INSTANCE;
    }

    /**
     * SDK 初始化时保存的 applicationContext。
     * 协议自定义连接步骤（裸连）调 {@code device.connectGatt(context, ...)} 时取用；
     * 未 init 时返回 null（裸连步骤应自行判空并上报失败）。
     */
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
            // 任务需要扫描（寻人 / 连接重寻址）时，自动开扫描——不依赖 UI 页面是否在扫描页
            taskPool.setScanRequiredListener(() -> main.post(this::ensureScanning));
        }
    }

    // ============ 扫描 ============

    /** 订阅扫描结果（发现资源），并确保扫描开启 */
    public void startScan(ScanListener listener) {
        ensureInit();
        uiScanListener = listener;
        ensureScanning();
    }

    /** 退订 UI 扫描回调（扫描本身可能继续，供任务池寻人/重寻址用） */
    public void stopScan() {
        uiScanListener = null;
    }

    /**
     * 真正停止底层扫描（与 {@link #stopScan()} 只退订 UI 回调不同，这里连底层扫描器也停掉）。
     * 用于「离开扫描页即停扫描省电」的场景。
     *
     * <p>注意：扫描为任务池服务（寻人 / 连接重寻址），若停扫后仍有任务在跑，任务池会通过
     * {@link #ensureScanning()} 自动重新拉起扫描，因此扫描不会长期停住——这是预期行为。
     */
    public void stopScanning() {
        ensureInit();
        scanning = false;
        adapter.stopScan();
        notifyScanState(false);
    }

    /** 订阅扫描状态（扫描中/已停止），UI 据此显示滚动图标 + 文字 */
    public void setScanStateListener(ScanStateListener listener) {
        scanStateListener = listener;
        ScanStateListener l = scanStateListener;
        if (l != null) {
            main.post(() -> l.onScanStateChanged(scanning));
        }
    }

    /**
     * 重启扫描。分两种情形：
     * <ul>
     *   <li><b>扫描运行中</b>（手动重扫 / 扫描僵尸自愈）：走 {@code restartScanner}——只重新注册
     *       底层系统扫描器，<b>不重建扫描引擎、不清空设备快照</b>，列表不闪空；</li>
     *   <li><b>扫描已停止</b>（如首次无权限时 scanEngine 残留、扫描启动失败）：必须真正停掉底层
     *       扫描再重建——首次无权限时 AndroidBleAdapter 已创建了 scanEngine（但底层 scanner.startScan
     *       抛 SecurityException 被静默 catch），若不清掉 scanEngine，再次 startScan 会因「已在扫描」
     *       直接 return，扫描永远起不来。</li>
     * </ul>
     */
    public void restartScan() {
        ensureInit();
        if (scanning) {
            adapter.restartScanner();
        } else {
            scanGen.incrementAndGet(); // 让旧扫描会话的回调失效（旧 onScanStopped/onScanFailed 不再改 scanning）
            adapter.stopScan();
            ensureScanning();
        }
    }

    /** 确保扫描开启：任务池寻人/重寻址都依赖 ScanCache 的新鲜地址，扫描必须持续跑 */
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
                taskPool.onDevices(devices); // 寻人匹配
                ScanListener l = uiScanListener;
                if (l != null) {
                    main.post(() -> l.onDevices(devices));
                }
            }

            @Override
            public void onScanFailed(int errorCode, String message) {
                if (gen != scanGen.get()) {
                    return; // 旧扫描会话的迟到回调，忽略（restartScan 已开新一轮）
                }
                // 扫描失败（如首次无权限 / 蓝牙关闭）：重置标志，允许后续 restartScan / 任务触发 requireScan 重新开扫
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
                    return; // 旧扫描会话的迟到回调，忽略（restartScan 已开新一轮）
                }
                // 定时扫描超时自动停止：重置标志，UI 显示「扫描已停止」，点击可 restartScan
                scanning = false;
                notifyScanState(false);
                ScanListener l = uiScanListener;
                if (l != null) {
                    main.post(() -> l.onScanStopped(devices));
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

    public void clearScanCache() {
        ensureInit();
        adapter.clearScanCache();
    }

    // ============ 提交任务 ============

    /**
     * 只给 mac（占位任务：系统自己找，找到后 device 自动填充）。
     * 命令被任务拒绝（忙碌且待办已满 / 已关闭）时命令会被丢弃并打错误日志，任务照常返回。
     */
    public QTask submit(String deviceMac, QCommand command) {
        return submitInternal(null, deviceMac, command, true);
    }

    /**
     * 扫描到的设备。
     * 命令被任务拒绝（忙碌且待办已满 / 已关闭）时命令会被丢弃并打错误日志，任务照常返回。
     */
    public QTask submit(QDevice device, QCommand command) {
        return submitInternal(device, null, command, true);
    }

    /**
     * 统一提交路径：先建/取任务，再以「意图」下命令，拿得到 BUSY/CLOSED 立即结果。
     * 无命令的占位任务不进学习批次（它永远不会到终态，会把批次卡死）。
     */
    private QTask submitInternal(QDevice device, String deviceMac, QCommand command, boolean track) {
        ensureInit();
        QTask task = device != null
                ? taskPool.submit(device, null)
                : taskPool.submit(deviceMac, null);
        if (task == null || command == null) {
            return task;
        }
        TaskIntentResult accepted = task.executeIntent(command);
        if (accepted == TaskIntentResult.BUSY || accepted == TaskIntentResult.CLOSED) {
            QLog.e(TAG, "任务 " + task.id() + " 拒绝命令（" + accepted + "），命令已丢弃：" + command.json());
            return task;
        }
        if (track) {
            trackAutoLearning(task);
        }
        return task;
    }

    /**
     * 开始一轮批量任务。批量结束后会自动统计成功、连接失败、操作失败和取消，
     * 回灌自动并发学习；自动并发模式下会立即应用新的建议值。
     * 被显式批次认领的任务不会再进入自动批次（反之亦然），不会重复统计。
     */
    public Batch beginBatch() {
        ensureInit();
        return new Batch(this);
    }

    /** 任务池（clear / list / get） */
    public Tasks getTasks() {
        ensureInit();
        return new Tasks(taskPool);
    }

    // ============ 订阅任务池 ============

    /** 订阅任务池：状态一变就回调全量快照 */
    public void subscribe(TaskPoolListener listener) {
        ensureInit();
        taskPool.setChangeListener(() -> main.post(() -> listener.onChanged(taskPool.list())));
    }

    // ============ 两个旋钮 ============

    /** 同时连接数：0 = 自动学习（默认），1-N = 手动。
     *  手动模式下批量结果仍会记入学习经验（切回自动时立即可用），但不会覆盖手动设置。 */
    public void setMaxConnections(int n) {
        ensureInit();
        autoConcurrency = n <= 0;
        adapter.setConcurrency(n);
    }

    /** 同时传输数：1 = 串行，N = 最多 N 台同时传 */
    public void setMaxTransfers(int n) {
        ensureInit();
        taskPool.setMaxTransfers(n);
    }

    /** 任务失败自动重试次数：连接失败 / 发送失败 / 寻人超时都算一次失败，失败后自动重试，
     *  全部耗尽才终态失败。0 = 不重试，默认 3。 */
    public void setMaxRetries(int n) {
        ensureInit();
        taskPool.setMaxRetries(n);
    }

    /** 每台设备最多排队多少条待发送意图；0 = 忙碌时直接返回 BUSY。默认 8。 */
    public void setMaxPendingIntents(int n) {
        ensureInit();
        taskPool.setMaxPendingIntents(n);
    }

    /**
     * 系统连接状态回调兜底超时。部分慢设备建立 GATT 需要 13～20 秒；
     * 默认 20 秒，最小 5 秒。过短会导致系统尚未回调就被判定失败。
     */
    public void setConnectCallbackTimeoutMs(long timeoutMs) {
        com.qingzhu.ble.sdk.core.platform.AndroidGattClient
                .setConnectCallbackTimeoutMs(timeoutMs);
    }

    /** 当前连接并发容量（自动学习时 = SDK 自适应建议值；手动时 = 用户指定值） */
    public int getConcurrency() {
        ensureInit();
        return adapter.concurrency();
    }

    // ============ 内部 ============

    private void ensureInit() {
        if (adapter == null || taskPool == null) {
            throw new IllegalStateException("QSdk 未初始化，请先调用 QSdk.getInstance().init(context)");
        }
    }

    // ============ 自动学习批次 ============

    /**
     * 普通 submit 的任务自动进入当前学习批次（无论自动/手动并发模式）：
     * 连续提交的任务（例如批量页循环添加）落在同一个活动批次里；
     * 批次全部终态并度过静默窗口后统一统计回灌。手动并发模式下只记录不回写并发。
     */
    private synchronized void trackAutoLearning(QTask task) {
        if (task == null) {
            return;
        }
        if (batchOwnedTaskIds.contains(task.id())) {
            return; // 已被活动批次认领（显式 Batch 或本批次），避免重复统计
        }
        if (!autoBatchTaskIds.add(task.id())) {
            return;
        }
        batchOwnedTaskIds.add(task.id());
        autoBatchTasks.add(task);
        long now = SystemClock.elapsedRealtime();
        if (autoBatchStartAt == 0) {
            autoBatchStartAt = now;
        }
        autoBatchLastSubmitAt = now;
        scheduleAutoBatchCheck();
    }

    private synchronized void checkAutoLearningBatch() {
        autoBatchCheckScheduled = false;
        if (autoBatchTasks.isEmpty()) {
            autoBatchStartAt = 0;
            return;
        }

        // 剔除僵尸任务：不在执行、没有待办、也没有终态（例如无命令的占位任务），
        // 既不阻塞批次收口，也不计入统计
        pruneZombieAutoBatchTasks();

        if (!autoBatchTasks.isEmpty()) {
            long now = SystemClock.elapsedRealtime();
            boolean timedOut = autoBatchStartAt > 0
                    && now - autoBatchStartAt >= AUTO_BATCH_TIMEOUT_MS;
            if (!timedOut) {
                for (QTask task : autoBatchTasks) {
                    if (!task.isDeleted()
                            && (task.getOutcome() == TaskOutcome.NONE
                                || task.pendingIntentCount() > 0)) {
                        scheduleAutoBatchCheck();
                        return;
                    }
                }
                long sinceLastSubmit = now - autoBatchLastSubmitAt;
                if (sinceLastSubmit < AUTO_BATCH_QUIET_MS) {
                    // 全部终态：再等一个静默窗口，慢速提交循环还能补进同一批次
                    autoBatchCheckScheduled = true;
                    main.postDelayed(this::checkAutoLearningBatch,
                            Math.max(AUTO_BATCH_CHECK_MS, AUTO_BATCH_QUIET_MS - sinceLastSubmit));
                    return;
                }
            } else {
                QLog.e(TAG, "自动批次 " + (AUTO_BATCH_TIMEOUT_MS / 1000)
                        + "s 未收口，强制结算（未终态任务按取消处理，本轮不参与学习）");
            }
        }
        if (autoBatchTasks.size() >= AdaptiveConfig.MIN_SAMPLE) {
            reportBatch(autoBatchTasks, false);
        }
        closeAutoBatch();
    }

    /** 剔除「不执行、无待办、无终态」的任务（如无命令占位），避免批次永不收口 */
    private synchronized void pruneZombieAutoBatchTasks() {
        Iterator<QTask> it = autoBatchTasks.iterator();
        while (it.hasNext()) {
            QTask task = it.next();
            if (!task.isDeleted()
                    && task.getOutcome() == TaskOutcome.NONE
                    && task.pendingIntentCount() == 0
                    && !task.getState().isRunning()) {
                it.remove();
                autoBatchTaskIds.remove(task.id());
                batchOwnedTaskIds.remove(task.id());
                QLog.e(TAG, "任务 " + task.id() + " 既不在执行也没有终态，移出自动批次（不参与统计）");
            }
        }
    }

    /** 清空自动批次并释放任务认领（被显式批次认领的任务不受影响） */
    private synchronized void closeAutoBatch() {
        for (QTask task : autoBatchTasks) {
            batchOwnedTaskIds.remove(task.id());
        }
        autoBatchTasks.clear();
        autoBatchTaskIds.clear();
        autoBatchStartAt = 0;
    }

    private void scheduleAutoBatchCheck() {
        if (!autoBatchCheckScheduled) {
            autoBatchCheckScheduled = true;
            main.postDelayed(this::checkAutoLearningBatch, AUTO_BATCH_CHECK_MS);
        }
    }

    /** 预检查：任务是否已被另一个显式批次认领（自动批次的归属允许移交给显式批次）。 */
    private synchronized boolean isTaskClaimedByOtherBatch(QTask task, Set<String> currentBatchTaskIds) {
        if (task == null) {
            return false;
        }
        String id = task.id();
        return batchOwnedTaskIds.contains(id)
                && !autoBatchTaskIds.contains(id)
                && !currentBatchTaskIds.contains(id);
    }

    /** 显式批次认领任务：若任务当前在自动批次里则移交（移出自动批次）；
     *  返回 false 表示已被其他显式批次认领。 */
    private synchronized boolean claimBatchTask(QTask task) {
        if (task == null) {
            return false;
        }
        String id = task.id();
        if (batchOwnedTaskIds.contains(id)) {
            if (autoBatchTaskIds.remove(id)) {
                autoBatchTasks.remove(task); // 从自动批次移交给本批次
                return true;
            }
            return false; // 已被另一个显式批次认领
        }
        batchOwnedTaskIds.add(id);
        return true;
    }

    /** 批次结束后释放任务认领；任务之后可被新的批次重新统计 */
    private synchronized void releaseBatchTasks(Set<String> ids) {
        if (ids != null) {
            batchOwnedTaskIds.removeAll(ids);
        }
    }

    /** 把一组成熟的任务统计成 BatchOutcome，并回灌学习结果。 */
    private void reportBatch(List<QTask> tasks, boolean cancelled) {
        if (tasks == null || tasks.isEmpty()) {
            return;
        }
        int succeeded = 0;
        int connectFailed = 0;
        int operationFailed = 0;
        boolean hasCancelled = cancelled;
        for (QTask task : tasks) {
            TaskOutcome outcome = task.getOutcome();
            if (outcome == null || outcome == TaskOutcome.NONE || outcome == TaskOutcome.CANCELLED) {
                hasCancelled = true;
                continue;
            }
            switch (outcome) {
                case SUCCEEDED:
                    succeeded++;
                    break;
                case CONNECT_FAILED:
                    connectFailed++;
                    break;
                case OPERATION_FAILED:
                    operationFailed++;
                    break;
                default:
                    break;
            }
        }
        BatchOutcome outcome = BatchOutcome.builder()
                .total(tasks.size())
                .succeeded(succeeded)
                .connectFailed(connectFailed)
                .operationFailed(operationFailed)
                .cancelled(hasCancelled)
                .build();
        // 学习以本轮实际生效的并发度为基准（手动模式下也照实记录，只是不回写并发）
        AdaptiveConcurrency.get().recordBatch(outcome, Math.max(1, adapter.concurrency()));
        if (autoConcurrency) {
            adapter.setConcurrency(0);
        }
    }

    // ============ 批次 ============

    /**
     * 一轮批量任务的会话。调用方提交完设备后调用 {@link #finish()}，
     * SDK 等到所有任务终态后自动统计并回灌自动并发学习。
     */
    public static final class Batch {
        /** 批次兜底超时：超过仍未收口则强制结算（未终态任务按取消处理，本轮不参与学习） */
        private static final long BATCH_TIMEOUT_MS = 10 * 60_000L;
        /** 终态轮询间隔 */
        private static final long CHECK_INTERVAL_MS = 200;

        private final QSdk sdk;
        private final List<QTask> tasks = new ArrayList<>();
        private final Set<String> taskIds = new HashSet<>();
        private final long createdAt = SystemClock.elapsedRealtime();
        private boolean finished;
        private boolean cancelled;
        private boolean reported;

        private Batch(QSdk sdk) {
            this.sdk = sdk;
        }

        /**
         * 提交一台设备。返回 null 表示命令被任务拒绝（忙碌且待办已满 / 已关闭），
         * 或该设备已被另一个活动批次认领——此时命令不会执行，也不会计入本批次。
         */
        public synchronized QTask submit(String deviceMac, QCommand command) {
            return submitInternal(null, deviceMac, command);
        }

        /** 提交一台设备；拒绝或被其他批次认领时返回 null（见 {@link #submit(String, QCommand)}）。 */
        public synchronized QTask submit(QDevice device, QCommand command) {
            return submitInternal(device, null, command);
        }

        private QTask submitInternal(QDevice device, String deviceMac, QCommand command) {
            if (command == null) {
                throw new IllegalArgumentException("批量任务必须带命令");
            }
            if (finished || cancelled) {
                throw new IllegalStateException("批次已经结束，不能再提交任务");
            }
            QTask task = sdk.submitInternal(device, deviceMac, null, false);
            if (task == null) {
                return null;
            }
            if (taskIds.contains(task.id())) {
                throw new IllegalStateException("同一批次不能重复提交同一设备：" + task.deviceMac());
            }
            if (sdk.isTaskClaimedByOtherBatch(task, taskIds)) {
                QLog.e(TAG, "设备 " + task.deviceMac() + " 已在另一个显式批次中，命令未执行");
                return null;
            }
            taskIds.add(task.id());
            TaskIntentResult accepted = task.executeIntent(command);
            if (accepted == TaskIntentResult.BUSY || accepted == TaskIntentResult.CLOSED) {
                taskIds.remove(task.id());
                QLog.e(TAG, "批次任务 " + task.id() + " 拒绝命令（" + accepted + "），命令已丢弃");
                return null;
            }
            if (!sdk.claimBatchTask(task)) {
                taskIds.remove(task.id());
                QLog.e(TAG, "设备 " + task.deviceMac() + " 已在另一个活动批次中，本次提交不计入本批次");
                return null;
            }
            tasks.add(task);
            return task;
        }

        /** 所有设备提交完成；SDK 会等待它们在任务池里走到终态。 */
        public synchronized void finish() {
            if (finished) {
                return;
            }
            finished = true;
            checkCompletion();
        }

        /** 中止本轮批次；尚未完成的设备会一起取消，并作为取消批次跳过学习。 */
        public synchronized void cancel() {
            if (cancelled) {
                return;
            }
            cancelled = true;
            for (QTask task : tasks) {
                if (!task.isDeleted()
                        && (task.getOutcome() == TaskOutcome.NONE
                            || task.pendingIntentCount() > 0)) {
                    task.cancel();
                }
            }
            checkCompletion();
        }

        private synchronized void checkCompletion() {
            if (reported || (!finished && !cancelled)) {
                return;
            }
            boolean timedOut = SystemClock.elapsedRealtime() - createdAt >= BATCH_TIMEOUT_MS;
            for (QTask task : tasks) {
                if (!task.isDeleted()
                        && (task.getOutcome() == TaskOutcome.NONE
                            || task.pendingIntentCount() > 0)) {
                    if (!timedOut) {
                        sdk.main.postDelayed(this::checkCompletion, CHECK_INTERVAL_MS);
                        return;
                    }
                    QLog.e(TAG, "批次等待超时，强制结算（未终态任务按取消处理，本轮不参与学习）");
                    break;
                }
            }
            report();
        }

        private void report() {
            if (reported) {
                return;
            }
            reported = true;
            sdk.releaseBatchTasks(taskIds);
            if (tasks.isEmpty()) {
                return;
            }

            boolean hasCancelled = cancelled;
            for (QTask task : tasks) {
                if (task.getOutcome() == null
                        || task.getOutcome() == TaskOutcome.NONE
                        || task.getOutcome() == TaskOutcome.CANCELLED) {
                    hasCancelled = true;
                    break;
                }
            }
            sdk.reportBatch(tasks, hasCancelled);
        }
    }

    // ============ 回调接口 ============

    /** 扫描监听 */
    public interface ScanListener {
        void onDevices(List<QDevice> devices);

        default void onScanFailed(int errorCode, String message) {
        }

        /** 扫描停止（定时超时自动停）时的最终回调，交付最终设备清单 */
        default void onScanStopped(List<QDevice> devices) {
        }
    }

    /** 扫描状态监听：true = 扫描中，false = 已停止（定时超时/失败） */
    public interface ScanStateListener {
        void onScanStateChanged(boolean scanning);
    }

    /** 任务池订阅（全量快照） */
    public interface TaskPoolListener {
        void onChanged(List<QTask> tasks);
    }

    /** 任务池句柄 */
    public static final class Tasks {
        private final QTaskPool pool;

        private Tasks(QTaskPool pool) {
            this.pool = pool;
        }

        /** 一键清空所有任务（按各自状态清理） */
        public void clear() {
            pool.clear();
        }

        /** 当前所有任务 */
        public List<QTask> list() {
            return pool.list();
        }

        /** 按 id 查任务 */
        public QTask get(String taskId) {
            return pool.get(taskId);
        }
    }
}
