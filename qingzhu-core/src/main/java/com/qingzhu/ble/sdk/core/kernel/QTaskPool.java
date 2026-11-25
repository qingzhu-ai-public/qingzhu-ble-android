// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.sdk.core.kernel;

import com.qingzhu.ble.sdk.core.api.QCommand;
import com.qingzhu.ble.sdk.core.api.QTask;
import com.qingzhu.ble.sdk.core.api.Subscription;
import com.qingzhu.ble.sdk.core.api.TaskCallback;
import com.qingzhu.ble.sdk.core.api.TaskObserver;
import com.qingzhu.ble.sdk.core.api.TaskOutcome;
import com.qingzhu.ble.sdk.core.api.TaskResult;
import com.qingzhu.ble.sdk.core.api.TaskSnapshot;
import com.qingzhu.ble.sdk.core.api.TaskStage;
import com.qingzhu.ble.sdk.core.api.TaskState;
import com.qingzhu.ble.sdk.core.ble.BleAdapter;
import com.qingzhu.ble.sdk.core.common.ErrorCode;
import com.qingzhu.ble.sdk.core.common.QLog;
import com.qingzhu.ble.sdk.core.connect.ConnectParams;
import com.qingzhu.ble.sdk.core.device.QDevice;
import com.qingzhu.ble.sdk.core.pool.ReconnectPolicy;
import com.qingzhu.ble.sdk.core.settings.BleSettings;
import com.qingzhu.ble.sdk.core.session.SessionRegistry;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 任务池：迅雷式任务内核。
 *
 * <p>核心规则：
 * <ul>
 *   <li>任务 = 设备 + 可选命令；有命令自动跑（寻找 → 排队 → 连接 → 发送），无命令待机。</li>
 *   <li>两个窗口：连接窗口（交给 ConnectionPool 的并发容量）、传输窗口（本池的 Semaphore）。</li>
 *   <li>循环无终态：办完 / 取消回待机，只有 {@link #delete} 才离场。</li>
 *   <li>占位任务（只给 mac）先寻人，找到后把 QDevice 填进任务。</li>
 * </ul>
 */
public final class QTaskPool {

    private static final String TAG = "QTaskPool";
    private static final String KEY_COMMAND_TIMEOUT_MS = "ble.commandTimeoutMs";

    private static final int DEFAULT_MAX_TRANSFERS = 1;
    private static final int DEFAULT_MAX_RETRIES = 3;
    /** 每台设备默认待发送队列上限；显式调大，避免批量/高频指令被 BUSY 拒绝 */
    private static final int DEFAULT_MAX_PENDING_INTENTS = 10_000;
    /** 寻人（searching）超时：只给 mac 的任务在这么长时间内没被扫描匹配到，算一次失败（触发重试） */
    private static final long SEARCH_TIMEOUT_MS = 30_000L;
    /** 失败后自动重试的间隔（给从机复位 / 链路喘息） */
    private static final long RETRY_INTERVAL_MS = 1_500L;

    private final BleAdapter adapter;
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private final List<TaskImpl> tasks = new CopyOnWriteArrayList<>();
    private final Map<String, TaskImpl> byId = new ConcurrentHashMap<>();
    /** 去重索引：identityKey（stableId / deviceMac）-> 任务。同一台物理设备只允许一个任务。 */
    private final Map<String, TaskImpl> byIdentity = new ConcurrentHashMap<>();
    /** 寻人中的占位任务：deviceMac -> task（等扫描池匹配） */
    private final Map<String, TaskImpl> searching = new ConcurrentHashMap<>();
    private volatile int maxRetries;
    private volatile int maxPendingIntents = DEFAULT_MAX_PENDING_INTENTS;
    private final AtomicLong idSeq = new AtomicLong();
    private final AtomicLong intentSeq = new AtomicLong();

    private volatile Semaphore transferGate = new Semaphore(DEFAULT_MAX_TRANSFERS);
    /** 按 mac（大写）索引：createTask / getTask / deleteTask 统一经此解析，避免同一设备产生两个任务 */
    private final Map<String, TaskImpl> byMac = new ConcurrentHashMap<>();
    /** 池级观察者：任务增删或状态变化时回调全量不可变快照 */
    private final ListenerRegistry<PoolObserver> poolObservers = new ListenerRegistry<>();
    /** 任务需要扫描（寻人 / 连接重寻址）时回调，由上层（QSdk）响应自动开扫描 */
    private volatile Runnable scanRequiredListener;

    public QTaskPool(BleAdapter adapter) {
        this(adapter, DEFAULT_MAX_RETRIES);
    }

    public QTaskPool(BleAdapter adapter, int maxRetries) {
        this.adapter = adapter;
        this.maxRetries = maxRetries;
    }

    /** 池级观察者回调（在主线程调用） */
    public interface PoolObserver {
        void onChanged(List<TaskSnapshot> snapshots);
    }

    /** 观察所有任务：注册后立即推送一次当前快照。owner 被回收或 Subscription.cancel() 后不再回调。 */
    public Subscription observeAll(Object owner, PoolObserver observer) {
        Subscription sub = poolObservers.add(owner, observer);
        final List<TaskSnapshot> current = snapshots();
        main.post(() -> {
            if (sub.isActive()) {
                observer.onChanged(current);
            }
        });
        return sub;
    }

    /** 当前所有任务的不可变快照 */
    public List<TaskSnapshot> snapshots() {
        List<TaskSnapshot> out = new java.util.ArrayList<>();
        for (TaskImpl t : tasks) {
            out.add(t.snapshot());
        }
        return out;
    }

    /** 任务池需要扫描（寻人/重寻址）时的回调：上层据此自动开启扫描，不依赖 UI 页面是否在扫描页 */
    public void setScanRequiredListener(Runnable listener) {
        this.scanRequiredListener = listener;
    }

    /** 设置传输并发度（1 = 串行，N = 最多 N 台同时传） */
    public void setMaxTransfers(int n) {
        this.transferGate = new Semaphore(Math.max(1, n));
    }

    /** 设置任务失败自动重试次数（连接失败 / 发送失败 / 寻人超时都算一次失败）。
     *  0 = 不重试，失败即终态；N = 失败后自动重试 N 次，全部耗尽才终态失败。 */
    public void setMaxRetries(int n) {
        this.maxRetries = Math.max(0, n);
    }

    /** 每台设备最多保留多少条待发送意图；0 表示不排队，忙碌时直接返回 BUSY */
    public void setMaxPendingIntents(int n) {
        this.maxPendingIntents = Math.max(0, n);
    }

    // ============ 提交任务 ============

    // ============ 创建 / 查询 / 删除任务 ============

    private static String macKey(String mac) {
        return mac == null ? "" : mac.toUpperCase(java.util.Locale.US);
    }

    /** 只给 mac：先删除该设备已有任务，再新建一个（占位任务，用到时才寻人）。 */
    public QTask createTask(String deviceMac) {
        return createTask(macKey(deviceMac), deviceMac, null);
    }

    /** 扫描到的设备：先删除该设备已有任务，再新建一个。 */
    public QTask createTask(QDevice device) {
        return createTask(device.getStableId(), device.getMac(), device);
    }

    /** 只查询，不创建；不存在返回 null。 */
    public QTask getTask(String mac) {
        return resolve(mac);
    }

    /** 删除该设备的任务（不存在则忽略）。 */
    public void deleteTask(String mac) {
        TaskImpl t = resolve(mac);
        if (t != null) {
            delete(t);
        }
    }

    /** 先按 mac 查（含设备换址后的最新地址），未命中再按 identityKey 查。 */
    private TaskImpl resolve(String mac) {
        String key = macKey(mac);
        TaskImpl t = byMac.get(key);
        if (t == null) {
            t = byIdentity.get(key);
        }
        return (t != null && !t.isDeleted()) ? t : null;
    }

    private QTask createTask(String identity, String mac, QDevice device) {
        TaskImpl old = resolve(mac);
        if (old == null) {
            old = byIdentity.get(identity);
        }
        if (old != null) {
            delete(old);
        }
        TaskImpl task = new TaskImpl(nextId(), mac, device, this, identity);
        add(task);
        byIdentity.put(identity, task);
        indexMac(task, mac);
        notifyChanged();
        return task;
    }

    private void indexMac(TaskImpl task, String mac) {
        if (mac != null && !mac.isEmpty()) {
            byMac.put(macKey(mac), task);
        }
    }

    // ============ 任务操作（TaskImpl 委托进来） ============

    /** 发送命令：结果经 callback 回调一次（主线程）。 */
    void send(TaskImpl task, QCommand command, TaskCallback callback) {
        if (command == null) {
            postResult(callback, TaskResult.failure(TaskStage.BUSY, "命令为空"));
            return;
        }
        TaskIntentResult accepted = execute(task, command, callback);
        if (accepted == TaskIntentResult.CLOSED) {
            postResult(callback, TaskResult.failure(TaskStage.CLOSED, "任务已删除"));
        } else if (accepted == TaskIntentResult.BUSY) {
            postResult(callback, TaskResult.failure(TaskStage.BUSY, "待办已满"));
        } else if (accepted == TaskIntentResult.ALREADY_SATISFIED) {
            // 已连接时再次 connect：回调已在 execute 内以 SUCCEEDED 处理
            return;
        }
    }

    private void postResult(TaskCallback callback, TaskResult result) {
        if (callback == null) {
            return;
        }
        main.post(() -> {
            try {
                callback.onResult(result);
            } catch (Throwable t) {
                QLog.e(TAG, "任务回调异常", t);
            }
        });
    }

    /** 断开连接：有当前操作先取消，再断开；任务保留在池中。 */
    void disconnect(TaskImpl task) {
        if (task.isDeleted() || !tasks.contains(task)) {
            return;
        }
        if (task.getState().isRunning()) {
            cancel(task);
        }
        // cancel 会回到「操作开始前」的链路状态：发送前已连接则仍是 CONNECTED。
        // disconnect 的语义是真正断开，所以这里补一次断开。
        if (task.getState() == TaskState.CONNECTED) {
            adapter.disconnect(task.currentMac());
            task.setState(task.getDevice() != null ? TaskState.READY : TaskState.UNKNOWN);
        }
        task.setConnectionDesired(false);
        notifyChanged();
    }

    TaskIntentResult execute(TaskImpl task, QCommand command, TaskCallback callback) {
        if (command == null) {
            return TaskIntentResult.BUSY;
        }
        if (task.isDeleted() || !tasks.contains(task)) {
            return TaskIntentResult.CLOSED;
        }

        QueuedIntent intent = newQueuedIntent(task, command, callback);
        TaskState state = task.getState();

        if (command.kind() == QCommand.Kind.CONNECT) {
            // 连接是“确保最终连接”的幂等意图，不是会覆盖当前发送的命令。
            task.setConnectionDesired(true);
            if (state == TaskState.CONNECTED || state == TaskState.SENDING) {
                if (state == TaskState.CONNECTED) {
                    task.setOutcome(TaskOutcome.SUCCEEDED);
                }
                finishIntent(intent, TaskIntentStatus.SUCCEEDED, null, null);
                return TaskIntentResult.ALREADY_SATISFIED;
            }
            if (state == TaskState.CONNECTING
                    || state == TaskState.QUEUED
                    || state == TaskState.SEARCHING) {
                if (callback != null) {
                    task.addConnectionWaiter(intent);
                }
                return TaskIntentResult.IN_PROGRESS;
            }
            if (task.command() == null) {
                if (callback != null) {
                    task.addConnectionWaiter(intent);
                }
                prepareNewCommand(task, command);
                schedule(task);
                notifyChanged();
                return TaskIntentResult.STARTED;
            }
            if (callback != null) {
                task.addConnectionWaiter(intent);
            }
            return TaskIntentResult.IN_PROGRESS;
        }

        // SEND：同一设备只允许一个正在发送，后续命令排队，绝不覆盖当前命令。
        if (state == TaskState.SENDING) {
            return enqueueOrBusy(task, intent);
        }
        if (state == TaskState.CONNECTED) {
            if (task.command() == null
                    && !task.isWaitingForTransfer()
                    && task.pendingIntentCount() == 0) {
                task.setCurrentIntent(intent);
                prepareNewCommand(task, command);
                startSending(task);
                notifyChanged();
                return TaskIntentResult.STARTED;
            }
            return enqueueOrBusy(task, intent);
        }
        if (state == TaskState.CONNECTING
                || state == TaskState.QUEUED
                || state == TaskState.SEARCHING) {
            return enqueueOrBusy(task, intent);
        }

        // READY / UNKNOWN
        if (task.command() == null && task.pendingIntentCount() == 0) {
            task.setCurrentIntent(intent);
            prepareNewCommand(task, command);
            schedule(task);
            notifyChanged();
            return TaskIntentResult.STARTED;
        }
        return enqueueOrBusy(task, intent);
    }

    private TaskIntentResult enqueueOrBusy(TaskImpl task, QueuedIntent intent) {
        if (task.offerPendingIntent(intent, maxPendingIntents)) {
            notifyChanged();
            return TaskIntentResult.QUEUED;
        }
        return TaskIntentResult.BUSY;
    }

    private QueuedIntent newQueuedIntent(
            TaskImpl task,
            QCommand command,
            TaskCallback callback
    ) {
        return new QueuedIntent(
                task.id() + "-i" + intentSeq.incrementAndGet(),
                command,
                callback);
    }

    private void prepareNewCommand(TaskImpl task, QCommand command) {
        // 记录操作开始前的链路状态：cancel 据此决定回到已连接还是未连接
        task.setConnectedBeforeOp(task.getState() == TaskState.CONNECTED);
        task.setCommand(command);
        task.resetRetry();
        task.resetProgress();
        task.setResult(null);
        task.setError(null);
        task.setOutcome(TaskOutcome.NONE);
        task.setWaitingForTransfer(false);
        // 新一轮操作，旧发送回调必须失效
        task.bumpSendGeneration();
    }

    private void finishIntent(
            QueuedIntent intent,
            TaskIntentStatus status,
            String result,
            String error
    ) {
        finishIntent(intent, status, result, error,
                status == TaskIntentStatus.CANCELLED ? TaskStage.CANCEL : TaskStage.SEND);
    }

    private void finishIntent(
            QueuedIntent intent,
            TaskIntentStatus status,
            String result,
            String error,
            TaskStage stage
    ) {
        finishIntent(intent, status, result, error, stage, 0);
    }

    private void finishIntent(
            QueuedIntent intent,
            TaskIntentStatus status,
            String result,
            String error,
            TaskStage stage,
            int code
    ) {
        if (intent == null) {
            return;
        }
        cancelTimeout(intent);
        // 所有终态都从这里收口：成功、失败、取消、删除、超时都只能回调一次。
        if (!intent.finished.compareAndSet(false, true)) {
            return;
        }
        if (intent.callback == null) {
            return;
        }
        final TaskResult taskResult = status == TaskIntentStatus.SUCCEEDED
                ? TaskResult.success(result)
                : TaskResult.failure(stage, code, error);
        postResult(intent.callback, taskResult);
    }

    private void cancelTimeout(QueuedIntent intent) {
        if (intent == null) {
            return;
        }
        Runnable r = intent.timeoutRunnable;
        if (r != null) {
            main.removeCallbacks(r);
            intent.timeoutRunnable = null;
        }
    }

    /** 只在真正开始写入时挂表：排队、寻人、连接阶段不消耗命令超时。 */
    private void scheduleCommandTimeout(
            TaskImpl task,
            QueuedIntent intent,
            QCommand command,
            long generation
    ) {
        if (intent == null || intent.finished.get()) {
            return;
        }
        cancelTimeout(intent);
        long timeoutMs = resolveCommandTimeoutMs(task, command);
        intent.effectiveTimeoutMs = timeoutMs;
        if (timeoutMs <= 0L) {
            return;
        }
        Runnable timeoutTask = () -> onCommandTimeout(task, intent, generation, timeoutMs);
        intent.timeoutRunnable = timeoutTask;
        main.postDelayed(timeoutTask, timeoutMs);
    }

    /**
     * 优先级：单条命令 timeout(ms) &gt; 连接级 param.ble.commandTimeoutMs &gt; 全局配置 &gt; core 默认 8,000 ms。
     */
    private long resolveCommandTimeoutMs(TaskImpl task, QCommand command) {
        if (command == null
                || command.kind() != QCommand.Kind.SEND
                || command.noAck()) {
            return 0L;
        }
        long explicit = command.timeoutMs();
        if (explicit >= 0L) {
            return explicit;
        }
        String mac = task.currentMac();
        if (mac == null || mac.isEmpty()) {
            mac = task.deviceMac();
        }
        return BleSettings.getLong(
                (mac == null || mac.isEmpty()) ? null : SessionRegistry.get(mac),
                KEY_COMMAND_TIMEOUT_MS);
    }

    private void onCommandTimeout(
            TaskImpl task,
            QueuedIntent intent,
            long generation,
            long timeoutMs
    ) {
        if (intent == null
                || intent.finished.get()
                || task.isDeleted()
                || !tasks.contains(task)
                || task.sendGeneration() != generation
                || task.getState() != TaskState.SENDING
                || task.currentIntent() != intent) {
            return;
        }
        QLog.w(TAG, "任务 " + task.id() + " 命令超时（" + timeoutMs + "ms）");
        // 先递增代次再中止发送：底层若同步抛出迟到失败，也会被 startSend 的代次校验丢弃。
        task.bumpSendGeneration();
        adapter.abortSend(task.currentMac());
        transferGate.release();
        task.setWaitingForTransfer(false);
        String error = "命令超时（" + timeoutMs + "ms）";
        task.setError(error);
        QueuedIntent completedIntent = task.clearCurrentIntent();
        QCommand finished = task.command();
        boolean keepConnection = (finished == null || !finished.disconnectAfter())
                && (task.isConnectionDesired()
                || (finished != null && finished.keepConnected()));
        task.setCommand(null);
        task.resetProgress();
        task.resetRetry();
        finishIntent(completedIntent, TaskIntentStatus.FAILED, null, error,
                TaskStage.TIMEOUT, ErrorCode.TASK_TIMEOUT);
        if (startNextPending(task)) {
            return;
        }
        task.setOutcome(TaskOutcome.OPERATION_FAILED);
        if (keepConnection) {
            task.setState(TaskState.CONNECTED);
        } else {
            adapter.disconnect(task.currentMac());
            task.setConnectionDesired(false);
            task.setState(TaskState.READY);
        }
        notifyChanged();
    }

    /** 连接成功/失败时结清所有等待连接结果的意图。 */
    private void finishConnectionWaiters(
            TaskImpl task,
            TaskIntentStatus status,
            String error
    ) {
        finishConnectionWaiters(task, status, error,
                status == TaskIntentStatus.CANCELLED ? TaskStage.CANCEL : TaskStage.CONNECT);
    }

    private void finishConnectionWaiters(
            TaskImpl task,
            TaskIntentStatus status,
            String error,
            TaskStage stage
    ) {
        for (QueuedIntent waiter : task.drainConnectionWaiters()) {
            finishIntent(waiter, status, null, error, stage);
        }
    }

    /** 任务取消/删除：当前、待办、连接等待者全部标记为取消。 */
    private void cancelAllIntents(TaskImpl task, String reason, TaskStage stage) {
        finishIntent(task.clearCurrentIntent(), TaskIntentStatus.CANCELLED, null, reason, stage);
        for (QueuedIntent pending : task.drainPendingIntents()) {
            finishIntent(pending, TaskIntentStatus.CANCELLED, null, reason, stage);
        }
        finishConnectionWaiters(task, TaskIntentStatus.CANCELLED, reason, stage);
    }

    /** 重试耗尽：当前意图和连接等待者失败，后续待办因为没有开始而取消。 */
    private void failAllIntents(TaskImpl task, String reason, TaskStage stage) {
        finishIntent(task.clearCurrentIntent(), TaskIntentStatus.FAILED, null, reason, stage);
        for (QueuedIntent pending : task.drainPendingIntents()) {
            finishIntent(pending, TaskIntentStatus.CANCELLED, null,
                    "前序命令失败：" + reason, TaskStage.CANCEL);
        }
        finishConnectionWaiters(task, TaskIntentStatus.FAILED, reason, stage);
    }

    void cancel(TaskImpl task) {
        if (task.isDeleted() || !task.getState().isRunning()) {
            return;
        }
        TaskState state = task.getState();
        if (task.getOutcome() == TaskOutcome.NONE) {
            task.setOutcome(TaskOutcome.CANCELLED);
        }
        cancelAllIntents(task, "任务已取消", TaskStage.CANCEL);
        if (state == TaskState.SEARCHING) {
            searching.remove(task.deviceMac());
        }
        // 回到操作开始前的链路状态：开始前已连接且正在发送 -> 只中止发送，保持连接；
        // 其余情形（寻人/排队/建连/重试等待，或开始前未连接）-> 断开回到未连接。
        boolean backToConnected = state == TaskState.SENDING && task.connectedBeforeOp();
        if (state == TaskState.SENDING) {
            adapter.abortSend(task.currentMac());
            transferGate.release();
        }
        if (!backToConnected
                && (state == TaskState.CONNECTING
                || state == TaskState.CONNECTED
                || state == TaskState.SENDING)) {
            adapter.disconnect(task.currentMac());
        }
        task.setConnectionDesired(backToConnected);
        task.setWaitingForTransfer(false);
        task.setCommand(null);
        task.resetProgress();
        task.resetRetry();
        task.setError(null);
        // 递增发送代次：丢弃取消时仍在执行、尚未返回的发送回调（onProgress/onDone/onFailed），
        // 否则它们迟到后会把进度/状态又改回旧值
        task.bumpSendGeneration();
        if (backToConnected) {
            task.setState(TaskState.CONNECTED);
        } else {
            task.setState(task.getDevice() != null ? TaskState.READY : TaskState.UNKNOWN);
        }
        notifyChanged();
    }

    void delete(TaskImpl task) {
        if (task.isDeleted()) {
            return;
        }
        TaskState state = task.getState();
        if (task.getOutcome() == TaskOutcome.NONE) {
            task.setOutcome(TaskOutcome.CANCELLED);
        }
        task.markDeleted();
        cancelAllIntents(task, "任务已删除", TaskStage.DELETE);
        task.setConnectionDesired(false);
        task.setWaitingForTransfer(false);
        if (state == TaskState.SEARCHING) {
            searching.remove(task.deviceMac());
        }
        if (state == TaskState.SENDING) {
            adapter.abortSend(task.currentMac());
            transferGate.release();
        }
        if (state == TaskState.CONNECTED || state == TaskState.SENDING || state == TaskState.CONNECTING) {
            adapter.disconnect(task.currentMac());
        }
        // 递增发送代次：让仍在执行的发送的迟到回调失效，避免 onDone 再 release 一次 transferGate（双重释放）
        task.bumpSendGeneration();
        task.setCommand(null);
        tasks.remove(task);
        byId.remove(task.id());
        byIdentity.remove(task.identityKey(), task);
        byMac.values().removeIf(t -> t == task);
        notifyChanged();
        // 删除后任务不再保留：通知完最后一次变化后清空任务级观察者
        main.post(() -> task.observers().clear());
    }

    /** 一键清空所有任务（按各自状态清理） */
    public void clear() {
        for (TaskImpl task : tasks) {
            delete(task);
        }
    }

    // ============ 查询 ============

    public List<QTask> list() {
        return new java.util.ArrayList<QTask>(tasks);
    }

    public QTask get(String taskId) {
        return byId.get(taskId);
    }

    /** 扫描结果喂进来：寻人匹配（协议无关：bleMac / stableId / deviceMac 任一中即匹配） */
    public void onDevices(List<QDevice> devices) {
        if (!searching.isEmpty()) {
            for (QDevice d : devices) {
                for (Map.Entry<String, TaskImpl> e : searching.entrySet()) {
                    if (d.matchesIdentity(e.getKey())) {
                        TaskImpl task = searching.remove(e.getKey());
                        if (task != null) {
                            task.setDevice(d);
                            indexMac(task, d.getMac());
                            task.resetRetry();
                            startQueued(task);
                        }
                        break;
                    }
                }
            }
        }
        // 待机任务的设备换址跟随：从端断开重播后 bleMac 轮换，同 stableId 的扫描结果有新地址时，
        // 实时更新任务缓存的 device，任务界面 bleMac 跟着刷新。执行中的任务不打断（连接/发送用
        // currentMac，重寻址会兜底），避免改了地址影响进行中的连接。
        for (TaskImpl task : tasks) {
            QDevice cur = task.getDevice();
            if (cur == null || task.getState().isRunning()) {
                continue;
            }
            for (QDevice d : devices) {
                if (d.getStableId().equals(cur.getStableId()) && !d.getMac().equalsIgnoreCase(cur.getMac())) {
                    task.setDevice(d);
                    task.setCurrentMac(d.getMac());
                    indexMac(task, d.getMac());
                    notifyChanged();
                    break;
                }
            }
        }
    }

    // ============ 调度（状态推进） ============

    private void schedule(TaskImpl task) {
        if (task.isDeleted() || task.command() == null) {
            return;
        }
        TaskState state = task.getState();
        if (state == TaskState.UNKNOWN || state == TaskState.READY) {
            // 任务要开跑（寻人 或 连接重寻址）都依赖扫描，先确保扫描开着
            requireScan();
            if (task.getDevice() == null) {
                startSearching(task);
            } else {
                startQueued(task);
            }
        } else if (state == TaskState.CONNECTED) {
            // 已连接任务再给「发数据」命令：复用连接直接发，不用重新连
            QCommand cmd = task.command();
            if (cmd != null && cmd.kind() == QCommand.Kind.SEND) {
                startSending(task);
            }
            // 连接命令：已连接即幂等。这里不覆盖命令，由 handleConnected 收尾
        }
        // 其他状态由各自的结果回调推进
    }

    private void requireScan() {
        Runnable l = scanRequiredListener;
        if (l != null) {
            l.run();
        }
    }

    private void startSearching(TaskImpl task) {
        if (task.isDeleted() || task.command() == null) {
            return;
        }
        task.setState(TaskState.SEARCHING);
        searching.put(task.deviceMac(), task);
        notifyChanged();
        // 寻人超时：只给 mac 的占位任务，若在 SEARCH_TIMEOUT_MS 内始终没被扫描匹配到，算一次失败
        //（触发 retryOrIdle 的重试）。匹配成功后 searching 里已移除，此超时自然失效。
        final String mac = task.deviceMac();
        main.postDelayed(() -> {
            if (searching.get(mac) == task) {
                searching.remove(mac);
                QLog.w(TAG, "任务 " + task.id() + " 寻人超时（" + (SEARCH_TIMEOUT_MS / 1000) + "s 未广播）");
                retryOrIdle(task, "寻人超时（设备未广播）", TaskOutcome.CONNECT_FAILED);
            }
        }, SEARCH_TIMEOUT_MS);
    }

    private void startQueued(TaskImpl task) {
        if (task.isDeleted() || task.command() == null) {
            return;
        }
        task.setState(TaskState.QUEUED);
        notifyChanged();
        task.setState(TaskState.CONNECTING);
        notifyChanged();
        QCommand cmd = task.command();
        ReconnectPolicy rp = (cmd != null && cmd.reconnectPolicy() != null)
                ? cmd.reconnectPolicy() : ReconnectPolicy.OFF;
        adapter.connect(task.getDevice(), toConnectParams(task), rp, new BleAdapter.ConnectSink() {
            @Override
            public void onConnected(String mac) {
                handleConnected(task, mac);
            }

            @Override
            public void onFailed(String mac, int errorCode, String message) {
                handleConnectFailed(task, message);
            }
        });
    }

    /** 把任务上的连接参数 Map 转成 ConnectParams（core 只承载不翻译，协议在 beforeConnect 读） */
    private ConnectParams toConnectParams(TaskImpl task) {
        ConnectParams cp = new ConnectParams();
        Map<String, Object> m = task != null ? task.connectParams() : null;
        if (m != null) {
            for (Map.Entry<String, Object> e : m.entrySet()) {
                cp.put(e.getKey(), e.getValue());
            }
        }
        return cp;
    }

    private void handleConnected(TaskImpl task, String mac) {
        if (task.isDeleted()
                || !tasks.contains(task)
                || task.getState() != TaskState.CONNECTING) {
            // 同一设备的连接可能被新任务合并共享（连接进行中再次请求会合并回调）。
            // 已删除的旧任务不能把这条共享链路断掉，否则新任务以为已连接、链路却已释放。
            TaskImpl other = byMac.get(macKey(mac));
            if (other == null || other == task || other.isDeleted()) {
                adapter.disconnect(mac);
            }
            return;
        }
        // 连接成功后，重寻址可能把 bleMac 换成了新地址，用连接实际成功的地址更新 currentMac，
        // 后续 disconnect / send 都认这个最新地址，不再用提交时过期的 bleMac
        if (mac != null && !mac.isEmpty()) {
            task.setCurrentMac(mac);
            indexMac(task, mac);
        }
        QCommand cmd = task.command();
        task.setState(TaskState.CONNECTED);
        finishConnectionWaiters(task, TaskIntentStatus.SUCCEEDED, null);
        if (cmd != null && cmd.kind() == QCommand.Kind.SEND) {
            // 发数据命令：先标记「已连接」（UI 不再卡在「连接中」），再排队等传输窗口
            notifyChanged();
            startSending(task);
        } else {
            // 纯连接命令：连接意图已经满足，当前命令清空；若有排队发送，继续执行
            task.setCommand(null);
            if (!startNextPending(task)) {
                task.setOutcome(TaskOutcome.SUCCEEDED);
                notifyChanged();
            }
        }
    }

    private void handleConnectFailed(TaskImpl task, String message) {
        if (task.isDeleted()
                || !tasks.contains(task)
                || task.getState() != TaskState.CONNECTING) {
            return;
        }
        retryOrIdle(task, message, TaskOutcome.CONNECT_FAILED);
    }

    private void startSending(TaskImpl task) {
        if (task.isDeleted() || task.isWaitingForTransfer()) {
            return;
        }
        if (task.getState() != TaskState.CONNECTED || task.command() == null) {
            return;
        }
        task.setWaitingForTransfer(true);
        // 异步排队等传输窗口：不能阻塞当前（连接回调）线程，否则多台并发时第二台会卡在 acquire 上，
        // 连带把连接回调也卡死（表现为「一直连接中」）。单独开个守护线程等待，拿到许可后回主线程发。
        Thread waiter = new Thread(() -> {
            while (true) {
                if (task.getState() != TaskState.CONNECTED
                        || task.command() == null
                        || task.isDeleted()) {
                    task.setWaitingForTransfer(false);
                    return; // 等待期间被 cancel/delete，退出（不占用传输窗口）
                }
                try {
                    if (transferGate.tryAcquire(500, TimeUnit.MILLISECONDS)) {
                        break;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    task.setWaitingForTransfer(false);
                    return;
                }
            }
            main.post(() -> doSend(task));
        }, "transfer-wait-" + task.id());
        waiter.setDaemon(true);
        waiter.start();
    }

    private void doSend(TaskImpl task) {
        task.setWaitingForTransfer(false);
        if (task.isDeleted()
                || task.getState() != TaskState.CONNECTED
                || task.command() == null) {
            transferGate.release(); // 排队期间被取消，释放刚拿到的传输许可
            return;
        }
        QCommand cmd = task.command();
        // 高连接优先级已在「连接成功后」由 ConnectionEngine 立即请求（对齐原 SDK 时序），
        // 认证期间参数更新已完成；这里直接发送，不再请求 HIGH + 等待（否则每个大数据任务多 500ms，
        // 且发送前才请求 HIGH 会让参数更新撞上发送、断链 status=59）
        startSend(task, cmd);
    }

    private void startSend(TaskImpl task, QCommand cmd) {
        if (task.getState() != TaskState.CONNECTED) {
            transferGate.release();
            return;
        }
        task.setState(TaskState.SENDING);
        notifyChanged();
        // 记录本次发送的代次：回调回来时校验，代次不一致说明是「取消/重新执行」前的旧发送残留，直接丢弃
        final long gen = task.sendGeneration();
        scheduleCommandTimeout(task, task.currentIntent(), cmd, gen);
        adapter.send(task.currentMac(), cmd.json(), cmd.buildAttachments(), new BleAdapter.SendSink() {
            @Override
            public void onProgress(int sentBytes, int totalBytes) {
                if (task.sendGeneration() != gen) {
                    return;
                }
                task.setProgress(sentBytes, totalBytes);
                // 节流：进度百分比变化才通知（大块传输 onProgress 每片都回调，全量刷新任务列表很浪费）
                if (task.markProgressNotified(task.getProgress())) {
                    notifyChanged();
                }
            }

            @Override
            public void onDone(String result) {
                if (task.sendGeneration() != gen) {
                    return;
                }
                onSendDone(task, result);
            }

            @Override
            public void onFailed(String message) {
                if (task.sendGeneration() != gen) {
                    return;
                }
                onSendFailed(task, message);
            }
        });
    }

    private void onSendDone(TaskImpl task, String result) {
        if (task.isDeleted() || !tasks.contains(task)) {
            return;
        }
        transferGate.release();
        task.setWaitingForTransfer(false);
        task.setResult(result); // 记录从机回显的数据，供 UI 展示
        QueuedIntent completedIntent = task.clearCurrentIntent();
        QCommand finished = task.command();
        // 命令显式要求「发完断开」优先于任务上「保持连接」的期望
        boolean keepConnection = (finished == null || !finished.disconnectAfter())
                && (task.isConnectionDesired()
                || (finished != null && finished.keepConnected()));
        task.setCommand(null);
        task.resetProgress();
        task.resetRetry();
        finishIntent(completedIntent, TaskIntentStatus.SUCCEEDED, result, null);

        if (startNextPending(task)) {
            return;
        }
        task.setOutcome(TaskOutcome.SUCCEEDED);
        if (keepConnection) {
            task.setState(TaskState.CONNECTED);
        } else {
            adapter.disconnect(task.currentMac());
            task.setConnectionDesired(false);
            task.setState(TaskState.READY);
        }
        notifyChanged();
    }

    /** 从待办队列取下一发送意图；返回 true 表示已经启动下一条。 */
    private boolean startNextPending(TaskImpl task) {
        QueuedIntent next = task.pollPendingIntent();
        if (next == null || task.isDeleted() || !tasks.contains(task)) {
            return false;
        }
        task.setCurrentIntent(next);
        task.setCommand(next.command);
        task.setOutcome(TaskOutcome.NONE);
        task.resetProgress();
        task.setError(null);
        task.setWaitingForTransfer(false);
        task.bumpSendGeneration();
        task.setState(TaskState.CONNECTED);
        notifyChanged();
        startSending(task);
        return true;
    }

    private void onSendFailed(TaskImpl task, String message) {
        if (task.isDeleted() || !tasks.contains(task)) {
            return;
        }
        transferGate.release();
        task.setWaitingForTransfer(false);
        task.setError(message); // 记录失败原因，供 UI 展示具体错误（如查询失败 reason）
        adapter.disconnect(task.currentMac());
        retryOrIdle(task, message, TaskOutcome.OPERATION_FAILED);
    }

    /** 暂时性失败（连接失败 / 发送失败 / 寻人超时）：重试次数 +1，未超 {@link #maxRetries} 则保留命令、
     *  延迟 {@link #RETRY_INTERVAL_MS} 自动重跑；超限则清命令、回待机（终态失败，等手动 execute 重新开始）。
     *  <p>重试等待期间置为 {@link TaskState#QUEUED}（而非 READY/UNKNOWN）：保持「执行中」，
     *  避免上层把重试误读成「已完成/空闲」（READY/UNKNOWN 会映射成 IDLE）。 */
    private void retryOrIdle(TaskImpl task, String message, TaskOutcome failureOutcome) {
        if (task.isDeleted() || !tasks.contains(task)) {
            return;
        }
        // 发送失败进入重试：撤掉本轮命令超时，等下一次真正 startSend 时重新挂表。
        cancelTimeout(task.currentIntent());
        task.bumpRetry();
        task.resetProgress();
        if (task.retryCount() <= maxRetries) {
            // 未超限：清掉本轮错误、保留命令，延迟后重新寻人/重连/重发
            task.setError(null);
            task.setState(TaskState.QUEUED);
            QLog.w(TAG, "任务 " + task.id() + " 失败，自动重试 " + task.retryCount() + "/" + maxRetries
                    + "：" + message);
            main.postDelayed(() -> {
                // 期间被 cancel（command 已清）或 delete（已离场）则不再重试
                if (tasks.contains(task) && task.command() != null) {
                    if (task.getDevice() == null) {
                        startSearching(task);
                    } else {
                        startQueued(task);
                    }
                }
            }, RETRY_INTERVAL_MS);
        } else {
            // 超限：当前意图失败，所有待办也一起终止，回到待机等待新的显式意图
            int cancelledPending = task.pendingIntentCount();
            task.setOutcome(failureOutcome);
            failAllIntents(task, message, failureOutcome == TaskOutcome.CONNECT_FAILED
                    ? TaskStage.CONNECT : TaskStage.SEND);
            task.setConnectionDesired(false);
            task.setWaitingForTransfer(false);
            task.setError(cancelledPending > 0
                    ? message + "（另有 " + cancelledPending + " 条待办已取消）"
                    : message);
            task.setCommand(null);
            task.resetRetry();
            task.setState(task.getDevice() != null ? TaskState.READY : TaskState.UNKNOWN);
            QLog.e(TAG, "任务 " + task.id() + " 重试 " + maxRetries + " 次仍失败，终止：" + message);
        }
        notifyChanged();
    }

    // ============ 内部 ============

    private void add(TaskImpl task) {
        tasks.add(task);
        byId.put(task.id(), task);
    }

    private String nextId() {
        return "t" + idSeq.incrementAndGet();
    }

    /** 状态变化：在主线程生成快照并派发给任务级与池级观察者 */
    private void notifyChanged() {
        main.post(() -> {
            for (TaskImpl t : tasks) {
                if (!t.observers().isEmpty()) {
                    final TaskSnapshot s = t.snapshot();
                    t.observers().forEach(o -> o.onChanged(s));
                }
            }
            if (!poolObservers.isEmpty()) {
                final List<TaskSnapshot> all = snapshots();
                poolObservers.forEach(o -> o.onChanged(all));
            }
        });
    }
}
