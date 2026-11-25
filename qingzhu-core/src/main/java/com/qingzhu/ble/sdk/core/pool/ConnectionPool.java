// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.sdk.core.pool;

import com.qingzhu.ble.sdk.core.common.QLog;
import com.qingzhu.ble.sdk.core.connect.ConnectListener;
import com.qingzhu.ble.sdk.core.connect.ConnectionEngine;
import com.qingzhu.ble.sdk.core.connect.GattClient;
import com.qingzhu.ble.sdk.core.connect.SessionKeys;
import com.qingzhu.ble.sdk.core.device.QDevice;
import com.qingzhu.ble.sdk.core.protocol.ProtocolPlugin;
import com.qingzhu.ble.sdk.core.protocol.ProtocolRegistry;
import com.qingzhu.ble.sdk.core.protocol.ProtocolType;
import com.qingzhu.ble.sdk.core.session.Session;
import com.qingzhu.ble.sdk.core.session.SessionRegistry;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 连接槎位调度器，语义对齐 ThreadPoolExecutor：core/max 决定同时能有几个连接活跃，
 * queue 决定超出时排多久，rejectionPolicy 决定队列也满了怎么处理。
 *
 * 默认池的队列是<b>无界</b>（{@link #QUEUE_UNBOUNDED}）：超出并发容量的连接请求不会失败，
 * 全部按 FIFO 排队，槽位释放后依次补位。适合「一次下发几百台设备」的场景，
 * 代价是排队期间没有超时——前面的请求等多久，后面的就跟着等多久。
 *
 * 单连接是这个模型的退化配置：new ConnectionPool(1, 1, 0, IMMEDIATE_FAIL)
 *（《新SDK架构-技术实现方案.md》第12.4节）。
 *
 * 同设备的连接请求管理——所有去重都按 stableId（设备身份）而不是 mac，
 * 因为同一台设备换址后的重复调用也必须被识别为"同一台"：
 * 1. 已连接 → 幂等返回成功（复用现有连接，绝不重复连接）；
 * 2. 连接进行中 → 合并回调，连接结果出来时所有调用方一起收到（只连一次，各回各的）；
 * 3. 释放中（GATT 正在异步断开）→ 排队，重复调用只保留最后一次；
 * 4. 无新鲜扫描数据 → 等设备重新广播被扫到再连；
 * 5. 池满 → 全局等待队列，槎位释放后按 FIFO 补位（同设备同样去重）。
 */
public final class ConnectionPool {

    private static final String TAG = "ConnectionPool";

    private final int corePoolSize;
    /**
     * 并发容量：同时允许多少个连接活跃。
     *
     * <p><b>本字段是"同时连接几台"的唯一权威。</b> 上层（自适应模块算出的建议值、门面 API）
     * 都只是往这里写，池子是最终执行者与裁判——这样"生效值"只有一份，不会出现
     * 上层以为设了 3、底层却按 7 跑的情况。
     */
    private volatile int maxPoolSize;
    /** 被强制的并发容量（0 = 未强制）：>0 时覆盖 maxPoolSize，并拒绝外部调整（SDK 策略） */
    private volatile int forcedCapacity = 0;
    private final BlockingQueue<ConnectRequest> waitingQueue;
    private final ConcurrentHashMap<String, Session> activeSessions = new ConcurrentHashMap<>();
    /**
     * 底层 GATT 正在异步断开的设备（按 stableId 记，断开完成前不接受该设备的新连接，请求排队）。
     * 用 stableId 而不是 mac：同一台设备换址后的重复调用也必须被去重。
     */
    private final java.util.Set<String> releasingStableIds = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** 释放期间排队的连接请求（stableId -> 最新一次请求，重复点击覆盖旧的） */
    private final ConcurrentHashMap<String, ConnectRequest> pendingByStableId = new ConcurrentHashMap<>();
    /** 等待设备重新被扫到的连接请求（stableId -> 最新一次请求） */
    private final ConcurrentHashMap<String, ConnectRequest> waitingScanByStableId = new ConcurrentHashMap<>();
    /** 连接进行中的设备：stableId -> 当前连接实际使用的 mac */
    private final ConcurrentHashMap<String, String> activeMacByStableId = new ConcurrentHashMap<>();
    /** 已连接完成的设备（stableId 集合）：再次调用 connect 时幂等返回成功，不重复连接 */
    private final java.util.Set<String> connectedStableIds = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** 已建立的连接对应的原始请求（stableId -> request）：意外断开时据此发起自动重连 */
    private final ConcurrentHashMap<String, ConnectRequest> activeRequestByStableId = new ConcurrentHashMap<>();
    /** 自动重连策略登记（stableId -> policy）：意外断开时读取；用户主动断开时移除（取消未发起的重连） */
    private final ConcurrentHashMap<String, ReconnectPolicy> policyByStableId = new ConcurrentHashMap<>();
    /** mac -> stableId 反查（意外断开后 session 已清，用户主动 release(mac) 时仍要能撤销重连登记） */
    private final ConcurrentHashMap<String, String> stableIdByMac = new ConcurrentHashMap<>();
    /**
     * 连接进行中合并进来的回调（stableId -> 等待同一连接结果的调用方列表）：
     * 连接成功/失败时，所有合并的调用方与首个调用方收到同一个结果——只连一次，各回各的。
     */
    private final ConcurrentHashMap<String, java.util.List<PoolConnectListener>> mergedListenersByStableId = new ConcurrentHashMap<>();
    /** 等设备重新广播的最长时间：超时则判定设备不在广播范围内 */
    private static final long WAIT_SCAN_TIMEOUT_MS = 15_000;
    /**
     * 地址新鲜度阈值：连接前要求设备在这么长时间内被扫到过，否则算"地址过期"，等重新广播。
     * 对齐 ScanConfig.DEFAULT_LEAVE_RANGE_MS（30s）——设备走远/息屏超过这个时间，缓存里的旧地址即失效。
     */
    private static final long ADDR_FRESHNESS_MS = 30_000;
    /** 连接流程整轮尝试的默认总次数（首次 + 重试）：失败后从头重试 1 次 */
    private static final int DEFAULT_MAX_CONNECT_ATTEMPTS = 2;
    private final RejectionPolicy rejectionPolicy;
    private final ConnectionEngine connectionEngine = new ConnectionEngine();

    /**
     * 释放完成后的"从机复位等待"：主机侧 GATT 关闭 ≠ 从机已复位。断开后立刻重连，
     * 从机协议状态机可能还停在上一轮会话（表现为 GATT 能连、认证帧无应答、8s 超时）。
     * 释放完成后再等这一小段时间才执行（排队的/新来的）连接，期间新请求照常排队。
     */
    private static final long RELEASE_SETTLE_MS = 1000;
    /** 连接失败后的退避：1 轮 2 秒、2 轮 4 秒、3 轮以上最多 6 秒，避免连续踩同一个不可连接窗口 */
    private static final long RETRY_BASE_DELAY_MS = 2_000L;
    private static final long RETRY_MAX_DELAY_MS = 6_000L;
    private final java.util.concurrent.ScheduledExecutorService settleScheduler =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "qingzhu-release-settle");
                t.setDaemon(true);
                return t;
            });

    /**
     * 建连闸门（单线程）：<b>无论多少台设备同时发起连接，建连阶段
     *（connectGatt → discoverServices → onGattReady/认证）都一个一个按 FIFO 顺序执行</b>。
     *
     * <p>并发建连会让 Android BLE 栈撞车（多台同时写 CCCD 返回 GATT_INVALID_HANDLE、
     * 认证应答丢失等）；这里是"同时几台在建连"的唯一闸门——连上之后的业务操作仍并发，
     * 并发容量（maxPoolSize）控制的仍是"同时保有几台连接"。
     *
     * <p>放在池里而不是门面/任务池：无论从哪个入口（单台 connect / 多台 connect / config）
     * 进来，都经过这里，全局串行。
     */
    private final java.util.concurrent.ExecutorService connectGate =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "qingzhu-connect-gate");
                t.setDaemon(true);
                return t;
            });
    /** 建连闸门等待单台建连终态的兜底超时（正常由 onConnected/onFailed 放行） */
    private static final long CONNECT_GATE_TIMEOUT_MS = 30_000;
    /**
     * 正在占用建连闸门的放行信号（mac -> latch）。
     * 建连途中被主动释放（页面退出 / 任务删除）时，连接流程不会再回调 onConnected/onFailed，
     * 如果不在这里放行，闸门会被这台已取消的设备占满 {@link #CONNECT_GATE_TIMEOUT_MS}，
     * 期间所有设备的新连接全部排队。
     */
    private final ConcurrentHashMap<String, java.util.concurrent.CountDownLatch> gateLatchByMac =
            new ConcurrentHashMap<>();

    /**
     * 队列容量传这个值表示无界：超出并发容量的请求不会被拒绝，全部 FIFO 排队。
     */
    public static final int QUEUE_UNBOUNDED = -1;

    public ConnectionPool(int corePoolSize, int maxPoolSize, int queueCapacity, RejectionPolicy rejectionPolicy) {
        if (corePoolSize < 1 || maxPoolSize < corePoolSize) {
            throw new IllegalArgumentException("corePoolSize 必须 >= 1 且 maxPoolSize >= corePoolSize");
        }
        this.corePoolSize = corePoolSize;
        this.maxPoolSize = maxPoolSize;
        // QUEUE_UNBOUNDED：无界队列，请求永不丢弃（顺序下发几百台设备时用）
        // 其余情况：LinkedBlockingQueue 不支持容量 0，queueCapacity<=0 时退化为最小容量 1
        //（单连接 "不排队" 语义由 maxPoolSize 限制保证：池满即拒绝/挂起，几乎不会用到队列位）
        this.waitingQueue = queueCapacity == QUEUE_UNBOUNDED
                ? new LinkedBlockingQueue<>()
                : new LinkedBlockingQueue<>(Math.max(queueCapacity, 1));
        this.rejectionPolicy = rejectionPolicy;
    }

    /**
     * 默认配置：初始并发容量 7（对齐 Android GATT 常见上限，之后可由
     * {@link #setConcurrencyCapacity(int)} 按本机实际能力动态收放）、<b>无界队列</b>。
     *
     * 队列无界意味着：一次提交几百台设备的连接请求也不会被拒绝，全部排队等槽位，
     * 按 FIFO 依次建连。{@link RejectionPolicy} 在这种情况下不会被触发。
     */
    public static ConnectionPool defaultPool() {
        return new ConnectionPool(1, 7, QUEUE_UNBOUNDED, RejectionPolicy.IMMEDIATE_FAIL);
    }

    /** 现有系统等价配置：只允许 1 个连接，不排队，新请求直接拒绝 */
    public static ConnectionPool singleConnectionOnly() {
        return new ConnectionPool(1, 1, 0, RejectionPolicy.IMMEDIATE_FAIL);
    }

    /**
     * @param device 扫描阶段识别到的完整 QDevice（含 metadata，如协议自定义的 deviceMac）。
     *               会把 metadata 逐条复制进新建的 Session，供连接阶段（如重寻址）读取。
     */
    public void requestConnect(QDevice device, PoolConnectListener listener) {
        requestConnect(device, listener, ReconnectPolicy.OFF, null, null, DEFAULT_MAX_CONNECT_ATTEMPTS, true);
    }

    /**
     * 发起连接（带自动重连策略）：连接建立后若发生<b>意外断开</b>（从机掉电/走出范围等，
     * 用户主动断开不算），按策略自动重连——复用新鲜地址闸门，设备重新广播被扫到才真正重连；
     * 重连成功再次回调 onConnected；全部耗尽才回调 onFailed(CONNECTION_LOST)。
     */
    public void requestConnect(QDevice device, PoolConnectListener listener, ReconnectPolicy reconnectPolicy) {
        requestConnect(device, listener, reconnectPolicy, null, null, DEFAULT_MAX_CONNECT_ATTEMPTS, true);
    }

    /** 发起连接（带重连策略与协议参数） */
    public void requestConnect(QDevice device, PoolConnectListener listener,
                               ReconnectPolicy reconnectPolicy, com.qingzhu.ble.sdk.core.protocol.ProtocolParams params) {
        requestConnect(device, listener, reconnectPolicy, params, null, DEFAULT_MAX_CONNECT_ATTEMPTS, true);
    }

    /**
     * 发起连接（带重连策略、协议参数、连接整轮尝试次数）。
     * maxConnectAttempts = 连接流程的总尝试次数（首次 + 重试）：某一步失败后不原地重试，
     * 而是清理后从头再来一遍，直到尝试次数耗尽才回调 onFailed。默认 2（失败重试 1 次）。
     */
    public void requestConnect(QDevice device, PoolConnectListener listener,
                               ReconnectPolicy reconnectPolicy, com.qingzhu.ble.sdk.core.protocol.ProtocolParams params,
                               int maxConnectAttempts) {
        requestConnect(device, listener, reconnectPolicy, params, null, maxConnectAttempts);
    }

    /** 发起连接（带重连策略、协议参数、连接参数、连接整轮尝试次数） */
    public void requestConnect(QDevice device, PoolConnectListener listener,
                               ReconnectPolicy reconnectPolicy, com.qingzhu.ble.sdk.core.protocol.ProtocolParams params,
                               com.qingzhu.ble.sdk.core.connect.ConnectParams connectParams, int maxConnectAttempts) {
        requestConnect(device, listener, reconnectPolicy, params, connectParams, maxConnectAttempts, false);
    }

    /**
     * 发起连接（failFastWhenFull：池满时立即失败返回 {@code POOL_FULL_REJECTED} 还是进队列排队）。
     * 单个连接用 true（用户要「达到上限报错」），批量任务用 false（任务池自己控制并发、排队等槽位）。
     */
    public void requestConnect(QDevice device, PoolConnectListener listener,
                               ReconnectPolicy reconnectPolicy, com.qingzhu.ble.sdk.core.protocol.ProtocolParams params,
                               com.qingzhu.ble.sdk.core.connect.ConnectParams connectParams, int maxConnectAttempts,
                               boolean failFastWhenFull) {
        String mac = device.getMac();
        String stableId = device.getStableId();
        ProtocolType type = device.getProtocolType();
        QLog.d(TAG, "requestConnect mac=" + mac + " stableId=" + stableId + " protocol=" + type
                + " reconnect=" + reconnectPolicy
                + " active=" + activeSessions.size() + "/" + maxPoolSize);

        // 状态 1：同一台设备（按 stableId 判定，换址后的重复调用也算同一台）已在连接中/已连接 → 幂等合并
        String activeMac = activeMacByStableId.get(stableId);
        if (activeMac != null) {
            Session session = activeSessions.get(activeMac);
            if (session != null) {
                if (connectedStableIds.contains(stableId)) {
                    // 已连接：幂等返回成功（复用现有连接，不重复连接）
                    QLog.d(TAG, "设备已连接，幂等返回成功（不重复连接）stableId=" + stableId);
                    listener.onConnected(session);
                } else {
                    // 连接进行中：合并回调——连接结果出来时所有调用方一起收到，只连一次
                    QLog.d(TAG, "设备连接进行中，合并到同一连接的回调 stableId=" + stableId);
                    mergedListenersByStableId
                            .computeIfAbsent(stableId, k -> new java.util.concurrent.CopyOnWriteArrayList<>())
                            .add(listener);
                }
                return;
            }
        }
        // 状态 2：释放中 → 排队（同一设备重复点击只保留最后一次），GATT 完全关闭后自动连接
        if (releasingStableIds.contains(stableId)) {
            pendingByStableId.put(stableId, new ConnectRequest(device, listener, reconnectPolicy, params, connectParams, maxConnectAttempts));
            QLog.d(TAG, "设备释放中，连接请求已排队 stableId=" + stableId + "（GATT 完全关闭后自动连接）");
            return;
        }
        // 状态 3：池满 → 立即失败（failFast）或全局等待队列（同设备去重：保留最新请求）
        if (activeSessions.size() >= maxPoolSize) {
            if (failFastWhenFull) {
                QLog.w(TAG, "连接池已满，立即失败 mac=" + mac + "（当前 " + activeSessions.size() + "/" + maxPoolSize + "）");
                listener.onFailed(mac, com.qingzhu.ble.sdk.core.common.ErrorCode.POOL_FULL_REJECTED,
                        "已达到最大连接上限（" + activeSessions.size() + "/" + maxPoolSize + "）");
                return;
            }
            QLog.w(TAG, "连接池已满，mac=" + mac + " 进入等待队列");
            ConnectRequest req = new ConnectRequest(device, listener, reconnectPolicy, params, connectParams, maxConnectAttempts);
            waitingQueue.removeIf(r -> r.getDevice().getStableId().equals(stableId));
            if (!waitingQueue.offer(req)) {
                QLog.e(TAG, "等待队列也已满，直接拒绝 mac=" + mac);
                rejectionPolicy.onRejected(req);
            }
            return;
        }
        // 统一入口：扫描缓存里有该设备的新鲜记录就直接连，没有则等设备重新被扫到再连
        executeConnect(new ConnectRequest(device, listener, reconnectPolicy, params, connectParams, maxConnectAttempts));
    }

    /** 把请求交给建连闸门串行执行（同一时刻只有一台在建连） */
    private void acquireSlot(ConnectRequest request, QDevice device) {
        connectGate.execute(() -> doAcquireSlot(request, device));
    }

    private void doAcquireSlot(ConnectRequest request, QDevice device) {
        PoolConnectListener listener = request.getListener();
        String mac = device.getMac();
        String stableId = device.getStableId();

        // 兜底闸门：万一有并发路径（等待扫描/队列补位）同时走到这里，同一台设备也只允许一个连接
        String existingMac = activeMacByStableId.get(stableId);
        if (existingMac != null && activeSessions.get(existingMac) != null) {
            Session active = activeSessions.get(existingMac);
            if (connectedStableIds.contains(stableId)) {
                QLog.d(TAG, "设备已连接，幂等返回成功（不重复连接）stableId=" + stableId);
                listener.onConnected(active);
            } else {
                QLog.d(TAG, "设备连接进行中，合并到同一连接的回调 stableId=" + stableId);
                mergedListenersByStableId
                        .computeIfAbsent(stableId, k -> new java.util.concurrent.CopyOnWriteArrayList<>())
                        .add(listener);
            }
            return;
        }

        // 设备正在异步释放（上一条链路还没完全关闭）：这个请求是在释放前就排进闸门的，
        // 此刻不能直接开新链路（会和正在关闭的旧链路交叉，表现为刚连上就被关掉的“迟到链路”）。
        // 转入释放期间的排队表，GATT 完全关闭并等从机复位后自动执行；已有更新的请求则丢弃这个旧请求。
        if (releasingStableIds.contains(stableId)) {
            ConnectRequest existing = pendingByStableId.putIfAbsent(stableId, request);
            if (existing == null) {
                QLog.d(TAG, "设备释放中，闸门内的连接请求转入排队 stableId=" + stableId);
            } else if (existing != request) {
                // 释放期间已有更新的排队请求（取最后一次），这个旧请求被取代：必须回调失败，避免调用方悬挂
                QLog.d(TAG, "设备释放中，闸门内的旧连接请求被更新的排队请求取代 stableId=" + stableId);
                listener.onFailed(mac, com.qingzhu.ble.sdk.core.common.ErrorCode.INTERNAL_ERROR,
                        "连接请求已被新的请求取代");
            }
            return;
        }

        // 二次池满检查：requestConnect 的检查在闸门外，多个请求可能同时看到"池没满"而全部放行。
        // 闸门是串行的，这里是"最后一关"——池满则转排队，避免并发建连把槽位冲爆（突破 maxPoolSize）。
        if (activeSessions.size() >= maxPoolSize) {
            QLog.w(TAG, "闸门内二次检查：连接池已满，mac=" + mac + " 转入等待队列");
            waitingQueue.removeIf(r -> r.getDevice().getStableId().equals(stableId));
            if (!waitingQueue.offer(request)) {
                rejectionPolicy.onRejected(request);
            }
            return;
        }

        ProtocolType type = device.getProtocolType();
        ProtocolPlugin plugin = ProtocolRegistry.get(type);
        if (plugin == null) {
            QLog.e(TAG, "acquireSlot 失败：协议未注册 type=" + type
                    + "（请检查 App 启动时是否调用了 QingzhuBle.registerProtocol）");
            listener.onFailed(mac, com.qingzhu.ble.sdk.core.common.ErrorCode.PROTOCOL_NOT_FOUND, "协议未注册: " + type);
            return;
        }
        Session session = SessionRegistry.getOrCreate(mac, type);
        int copied = device.copyMetadataInto(session);
        // 公司 SDK 定制的协议参数复制进 Session（key 加 "param." 前缀），协议步骤/Filter 读取
        if (request.getParams() != null) {
            request.getParams().copyInto(session);
        }
        // 记录稳定标识：断开释放时据此清理 ScanCache 里的地址数据
        session.put(SessionKeys.STABLE_ID, stableId);
        activeSessions.put(mac, session);
        activeMacByStableId.put(stableId, mac);
        java.util.List<com.qingzhu.ble.sdk.core.connect.ConnectStepSpec> steps = plugin.connectSteps();
        QLog.d(TAG, "acquireSlot 成功，开始跑连接流程 mac=" + mac
                + " 连接流程=" + (steps != null && !steps.isEmpty() ? "自定义" + steps.size() + "步" : "默认流程")
                + " 从Device复制了" + copied + "条metadata进Session");

        // 建连闸门放行信号：本台建连到终态（成功/失败）后，下一台才能开始建连
        final java.util.concurrent.CountDownLatch established = new java.util.concurrent.CountDownLatch(1);
        gateLatchByMac.put(mac, established);
        ConnectListener connectListener = new ConnectListener() {
            @Override
            public void onConnected(Session s) {
                QLog.i(TAG, "连接成功 mac=" + mac);
                connectedStableIds.add(stableId);
                request.resetReconnectAttempts(); // 重连成功也重置计数（新一轮意外断开重新计）
                activeRequestByStableId.put(stableId, request);
                policyByStableId.put(stableId, request.getReconnectPolicy());
                stableIdByMac.put(mac, stableId);
                // 注册意外断开监听：连接建立后设备悄悄掉了，连接池据此感知并驱动自动重连
                GattClient client = s.get(SessionKeys.GATT_CLIENT);
                if (client != null) {
                    client.setUnexpectedCloseListener(() -> onUnexpectedDisconnect(stableId, mac));
                    // 认证完成后（onGattReady 已过）才提优先级：连接参数更新窗口不再撞上 discoverServices/MTU/认证，
                    // 对齐旧 SDK 时序（连接成功瞬间不 requestHighPriority，认证完成后再延迟 100ms 提优先级）。
                    // 立即提优先级会导致 HCI LE Connection Update 撞上认证写帧 → 链路断/超时（部分设备第二次连接尤其明显）。
                    client.requestHighPriorityDelayed();
                }
                // 状态就绪后放行建连闸门（外部回调可能耗时，放在放行之后）
                established.countDown();
                // 长连接保活：协议声明了心跳指令才启动（未声明/关闭时静默，无开销）
                com.qingzhu.ble.sdk.core.connect.HeartbeatManager.get().start(mac, stableId, s);
                // 首个调用方 + 合并进来的所有调用方，收到同一个连接结果
                listener.onConnected(s);
                notifyMergedListeners(stableId, merged -> merged.onConnected(s));
            }

            @Override
            public void onFailed(Session s, String failedStep, int errorCode, String message) {
                try {
                    QLog.e(TAG, "连接失败 mac=" + mac + " 步骤=" + failedStep
                            + " errorCode=" + errorCode + " message=" + message);
                    String failMsg = "步骤[" + failedStep + "]失败: " + message;
                    // 整轮重试：某一步失败不原地重试，而是清理后从头再来（默认失败重试 1 次）
                    int attempt = request.nextConnectAttempt();
                    if (attempt < request.getMaxConnectAttempts()) {
                        QLog.w(TAG, "连接失败，整轮重试 " + attempt + "/" + request.getMaxConnectAttempts()
                                + " stableId=" + stableId + "（" + failMsg + "）");
                        retryRound(s, request, mac, stableId, attempt);
                        return;
                    }
                    // 全部尝试耗尽：重连轮次中继续下一轮；否则终态失败
                    ReconnectPolicy rp = policyByStableId.get(stableId);
                    if (rp != null) {
                        QLog.w(TAG, "重连轮次连接失败，继续下一轮自动重连 stableId=" + stableId);
                        releaseInternal(mac, true); // 清理本轮连接状态，保留重连登记
                        scheduleNextReconnectAttempt(request, mac, stableId, rp);
                        return;
                    }
                    notifyMergedListeners(stableId, merged -> merged.onFailed(mac, errorCode, failMsg));
                    listener.onFailed(mac, errorCode, failMsg);
                    release(mac);
                } finally {
                    // 无论哪条路径（重试/最终失败），都放行建连闸门
                    established.countDown();
                }
            }
        };

        // 分流：协议覆写了 connectSteps() 走自定义流程，否则走默认流程（零配置）
        // 两条流程在「GATT 建立后」由引擎统一收尾（removeBond 策略 + discoverServices + onGattReady）
        if (steps != null && !steps.isEmpty()) {
            connectionEngine.connect(session, steps, plugin, connectListener);
        } else {
            connectionEngine.connectDefault(session, plugin, request.getConnectParams(), connectListener);
        }

        // 等本台建连到终态，才放行下一台——这就是"建连串行（FIFO）"的关键闸门
        try {
            established.await(CONNECT_GATE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            gateLatchByMac.remove(mac, established);
        }
    }

    /**
     * 连接失败后的整轮重试：清理当前失败的连接状态（槽位/GATT/缓存），
     * 等从机复位后重新 executeConnect——复用扫描缓存闸门，设备重新广播才重连。
     */
    private void retryRound(
            Session session,
            ConnectRequest request,
            String mac,
            String stableId,
            int attempt
    ) {
        // 必须在 SessionRegistry.remove 之前取 client：remove 会 session.clear() 清空全部属性，
        // 之后再取永远是 null → GATT 不 disconnect/close → 系统保持到从机的链路，从机不再广播
        //（表现为设备关机再开机后扫不到，重启 App / 开关蓝牙才恢复）
        GattClient client = session.get(SessionKeys.GATT_CLIENT);
        activeSessions.remove(mac);
        activeMacByStableId.remove(stableId);
        SessionRegistry.remove(mac);
        // 清缓存：失败后断开可能导致设备地址轮换，旧地址已失效，重连必须等新广播
        com.qingzhu.ble.sdk.core.scan.ScanCache.getInstance().remove(stableId);
        long retryDelayMs = Math.min(RETRY_BASE_DELAY_MS * Math.max(1, attempt), RETRY_MAX_DELAY_MS);
        QLog.d(TAG, "连接失败重试退避 " + retryDelayMs + "ms stableId=" + stableId
                + " attempt=" + attempt + "，等待新鲜广播后再发起");
        Runnable retry = () -> executeConnect(request);
        if (client != null) {
            client.disconnect(() -> settleScheduler.schedule(retry, retryDelayMs,
                    java.util.concurrent.TimeUnit.MILLISECONDS));
        } else {
            settleScheduler.schedule(retry, retryDelayMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        }
    }

    /** 把连接结果分发给"连接进行中"合并进来的所有调用方 */
    private void notifyMergedListeners(String stableId, java.util.function.Consumer<PoolConnectListener> notify) {
        java.util.List<PoolConnectListener> merged = mergedListenersByStableId.remove(stableId);
        if (merged != null) {
            QLog.d(TAG, "分发连接结果给 " + merged.size() + " 个合并的调用方 stableId=" + stableId);
            for (PoolConnectListener l : merged) {
                try {
                    notify.accept(l);
                } catch (Exception e) {
                    QLog.e(TAG, "合并回调异常（忽略，继续分发）", e);
                }
            }
        }
    }

    /** 断开/连接失败时释放槎位，并尝试把全局等待队列里的下一个请求补上来。
     *  这是"主动断开"入口：意外断开走 {@link #onUnexpectedDisconnect}（会驱动自动重连）。 */
    public void release(String mac) {
        releaseInternal(mac, false);
    }

    /**
     * 释放清理。
     * @param preserveReconnect true = 重连轮次中的失败清理：<b>保留</b>重连登记
     *          （{@code policyByStableId}），下一轮重连照常调度；false = 用户主动断开，
     *          撤销重连登记（未发起的重连就此取消）。
     */
    private void releaseInternal(String mac, boolean preserveReconnect) {
        // 建连途中被释放：连接流程已被取消，不会再回调终态，这里主动放行建连闸门，
        // 否则闸门被这台已取消的设备占满 30s，期间所有设备的新连接都排队
        java.util.concurrent.CountDownLatch gate = gateLatchByMac.get(mac);
        if (gate != null) {
            gate.countDown();
        }
        // 停心跳（意外断开路径在 onUnexpectedDisconnect 里已停，这里重复调用是幂等的）
        com.qingzhu.ble.sdk.core.connect.HeartbeatManager.get().stop(mac);
        Session session = activeSessions.remove(mac);
        String stableId = session != null
                ? session.get(SessionKeys.STABLE_ID)
                : stableIdByMac.remove(mac); // 意外断开后的撤销场景：session 已清，靠反查表拿 stableId
        if (stableId != null) {
            activeRequestByStableId.remove(stableId);
            if (!preserveReconnect) {
                // 主动断开：撤销自动重连登记（未发起的重连就此取消）
                policyByStableId.remove(stableId);
            }
            // 立刻清理 ScanCache 里这台设备的地址数据：断开后地址可能已失效（部分设备断连必换址），
            // "缓存里有没有"成为地址新鲜度的唯一判据——重新连接必须等设备重新广播被扫到。
            // UI 层的行数据不受影响（行保留的是 QDevice 快照），等新广播到达自动刷新。
            com.qingzhu.ble.sdk.core.scan.ScanCache.getInstance().remove(stableId);
            activeMacByStableId.remove(stableId);
            connectedStableIds.remove(stableId);
            QLog.d(TAG, "【断开】", "已清理设备的扫描缓存记录 stableId=" + stableId + "（重连需等设备重新广播）");
            // 连接尚未建立就已释放（如用户在连接中点断开）：合并等待的调用方统一收到失败，避免悬挂
            notifyMergedListeners(stableId, merged -> merged.onFailed(mac,
                    com.qingzhu.ble.sdk.core.common.ErrorCode.INTERNAL_ERROR, "连接已被取消/断开"));
            GattClient client = session != null ? session.get(SessionKeys.GATT_CLIENT) : null;
            if (client != null) {
                // 标记释放中：底层 GATT 异步断开（优雅断开 + 3s 兜底，保证 onClosed 必达），
                // 完全关闭前该设备的新连接请求会排队
                releasingStableIds.add(stableId);
                QLog.d(TAG, "【断开】", "释放连接 mac=" + mac + "（等待底层 GATT 完全关闭）");
                client.disconnect(() -> onReleaseCompleted(mac, stableId));
            }
        }
        SessionRegistry.remove(mac);
        drainWaitingQueue();
    }

    /**
     * 底层 GATT 完全关闭：先不解除释放标记——再等 RELEASE_SETTLE_MS 给从机复位，
     * 之后执行该设备排队的连接请求（取最后一次点击）。等待期间新请求照常进队列并被覆盖。
     */
    private void onReleaseCompleted(String mac, String stableId) {
        QLog.i(TAG, "【断开】", "设备已完全释放 mac=" + mac + "，等待从机复位 " + RELEASE_SETTLE_MS + "ms 后接受重连");
        ConnectRequest pending = stableId != null ? pendingByStableId.remove(stableId) : null;
        settleScheduler.schedule(() -> {
            if (stableId != null) {
                releasingStableIds.remove(stableId);
            }
            // 复位等待期间新来的点击（覆盖 pending 旧值）优先
            ConnectRequest latest = stableId != null ? pendingByStableId.remove(stableId) : null;
            ConnectRequest request = latest != null ? latest : pending;
            if (request != null) {
                executeConnect(request);
                return;
            }
            drainWaitingQueue();
        }, RELEASE_SETTLE_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /**
     * 链路意外断开（非用户主动断开）：清理状态 → 通知调用方 → 按 ReconnectPolicy 自动重连。
     * 重连复用既有机制：等从机复位 + 扫描缓存重新有新鲜地址（设备重新广播被扫到）才真正发 GATT 连接；
     * 重连成功 → onConnected 再次回调且计数重置；全部耗尽 → onFailed(CONNECTION_LOST)。
     * 用户在重连等待期间主动 release(mac) 会撤销登记，未发起的重连就此取消。
     */
    private void onUnexpectedDisconnect(String stableId, String mac) {
        // 意外断开：停止心跳（重连成功后 onConnected 会重新启动）
        com.qingzhu.ble.sdk.core.connect.HeartbeatManager.get().stop(mac);
        // 已在主动释放流程中（release 先标记了 releasing）？那不是"意外"，交给原流程收尾
        if (releasingStableIds.contains(stableId) || !connectedStableIds.remove(stableId)) {
            QLog.d(TAG, "【断开】", "断开事件来自主动释放流程，跳过意外断开处理 stableId=" + stableId);
            return;
        }
        ConnectRequest request = activeRequestByStableId.remove(stableId);
        Session session = activeSessions.remove(mac);
        // 必须在 SessionRegistry.remove（会 session.clear()）之前取 client，否则拿到 null → GATT 泄漏
        GattClient client = session != null ? session.get(SessionKeys.GATT_CLIENT) : null;
        activeMacByStableId.remove(stableId);
        stableIdByMac.remove(mac);
        // 注意：policyByStableId 不能在这里移除——它是"重连是否被用户撤销"的判断依据，
        // 重连全程保持登记，仅在用户主动 release 或重试耗尽时移除
        com.qingzhu.ble.sdk.core.scan.ScanCache.getInstance().remove(stableId); // 地址已失效，重连等重新广播
        notifyMergedListeners(stableId, merged -> merged.onFailed(mac,
                com.qingzhu.ble.sdk.core.common.ErrorCode.CONNECTION_LOST, "链路意外断开"));
        if (session != null) {
            SessionRegistry.remove(mac);
        }
        if (request == null) {
            // 没有重连登记也必须关闭 GATT，否则泄漏
            if (client != null) {
                client.disconnect(null);
            }
            drainWaitingQueue();
            return;
        }
        ReconnectPolicy policy = request.getReconnectPolicy();
        QLog.w(TAG, "链路意外断开 stableId=" + stableId + " mac=" + mac
                + " 重连策略=" + policy + "（从机复位 + 重连间隔后自动重连）");
        releasingStableIds.add(stableId); // 意外断开后同样要给从机复位时间，期间新请求排队
        Runnable afterClosed = () -> settleScheduler.schedule(() -> {
            releasingStableIds.remove(stableId);
            // 等待期间用户主动 release 过 → 登记被撤销，取消重连
            if (!policyByStableId.containsKey(stableId)) {
                QLog.d(TAG, "自动重连已取消（用户主动断开）stableId=" + stableId);
                drainWaitingQueue();
                return;
            }
            scheduleNextReconnectAttempt(request, mac, stableId, policy);
        }, RELEASE_SETTLE_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        if (client != null) {
            client.disconnect(() -> afterClosed.run()); // 链路虽死也走 disconnect 收尾（close GATT 对象防泄漏，onClosed 必达）
        } else {
            afterClosed.run();
        }
    }

    /**
     * 调度下一轮自动重连：消耗一次重连计数，按策略间隔后重新 {@link #executeConnect}。
     * 计数耗尽 → 终态失败（onFailed）。这一方法是重连循环的<b>唯一推进点</b>——
     * 等待广播超时、连接失败等"本轮未成功"的出口都必须回到这里，否则重连次数形同虚设。
     */
    private void scheduleNextReconnectAttempt(ConnectRequest request, String mac, String stableId,
                                              ReconnectPolicy policy) {
        int attempt = request.nextReconnectAttempt();
        if (attempt > policy.getMaxRetries()) {
            QLog.e(TAG, "自动重连 " + policy.getMaxRetries() + " 次均失败 stableId=" + stableId);
            // 主动清理：确保底层连接彻底断开 + 清空扫描缓存。设备重新上电后若系统仍残留
            // 到旧地址的连接状态，会把它当"已连接"而不上报其广播（表现为列表永远不显示）；
            // 这里主动走一遍断连收尾，把残留的连接/缓存清干净，等设备下一次身份帧重新进入。
            hardCleanupForReconnectFailure(stableId, mac);
            policyByStableId.remove(stableId);
            notifyMergedListeners(stableId, merged -> merged.onFailed(mac,
                    com.qingzhu.ble.sdk.core.common.ErrorCode.CONNECTION_LOST,
                    "链路意外断开，自动重连 " + policy.getMaxRetries() + " 次均失败"));
            request.getListener().onFailed(mac, com.qingzhu.ble.sdk.core.common.ErrorCode.CONNECTION_LOST,
                    "链路意外断开，自动重连 " + policy.getMaxRetries() + " 次均失败");
            drainWaitingQueue();
            return;
        }
        request.getListener().onUnexpectedDisconnected(mac, attempt, policy.getMaxRetries());
        long delay = policy.intervalForAttempt(attempt);
        QLog.d(TAG, "自动重连第 " + attempt + "/" + policy.getMaxRetries()
                + " 次，" + delay + "ms 后发起 stableId=" + stableId);
        settleScheduler.schedule(() -> {
            // 发起前再次检查：用户可能刚主动断开
            if (policyByStableId.containsKey(stableId)) {
                executeConnect(request);
            } else {
                QLog.d(TAG, "自动重连已取消（用户主动断开）stableId=" + stableId);
                drainWaitingQueue();
            }
        }, delay, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /**
     * 心跳判离线（设备假死，系统未回调断开）：主动触发「意外断开 + 自动重连」流程。
     * 与 {@link #onUnexpectedDisconnect} 共享同一套收尾逻辑——清理状态 → 通知调用方 →
     * 按 {@link ReconnectPolicy} 自动重连。
     */
    public void notifyHeartbeatOffline(String stableId, String mac) {
        QLog.w(TAG, "【断开】", "心跳判离线，触发意外断开 + 重连流程 stableId=" + stableId + " mac=" + mac);
        onUnexpectedDisconnect(stableId, mac);
    }

    /**
     * 执行一个连接请求的统一入口：以"扫描缓存里有没有该设备的记录"作为地址新鲜度的唯一判据。
     * - 有记录（设备正在广播，地址是新鲜的）→ 直接连接；
     * - 没有记录（刚断开清了缓存、或设备还没重新广播）→ 挂起等待，设备重新被扫到后自动连接，
     *   超时（WAIT_SCAN_TIMEOUT_MS）则失败并提示。
     * 这样所有时序问题（旧地址、从机未复位、缓存过期）都被同一个闸门挡住：
     * 拿不到新鲜地址就不发 GATT 连接。
     */
    private void executeConnect(ConnectRequest request) {
        String stableId = request.getDevice().getStableId();
        // 所有协议都走同一个新鲜地址闸门。地址稳定的协议也不能无条件拿旧缓存盲连：
        // 设备可能休眠、复位、被其他主机占用，旧地址虽然格式没变，但此刻未必可连接。
        QDevice fresh = com.qingzhu.ble.sdk.core.scan.ScanCache.getInstance().getFresh(stableId, ADDR_FRESHNESS_MS);
        if (fresh != null) {
            QLog.d(TAG, "扫描缓存命中（地址新鲜），直接连接 stableId=" + stableId + " mac=" + fresh.getMac());
            acquireSlot(request, fresh);
            return;
        }
        waitForDeviceReappear(request);
    }

    /** 等待设备重新被扫到（每 300ms 查一次缓存；同一设备重复点击只保留最后一次请求） */
    private void waitForDeviceReappear(ConnectRequest request) {
        String stableId = request.getDevice().getStableId();
        waitingScanByStableId.put(stableId, request);
        long deadline = System.currentTimeMillis() + WAIT_SCAN_TIMEOUT_MS;
        QLog.d(TAG, "设备暂无新鲜扫描数据，等待重新广播 stableId=" + stableId
                + "（最长 " + WAIT_SCAN_TIMEOUT_MS / 1000 + "s，期间重复点击会更新请求）");
        final java.util.concurrent.ScheduledFuture<?>[] holder = new java.util.concurrent.ScheduledFuture<?>[1];
        holder[0] = settleScheduler.scheduleWithFixedDelay(() -> {
            try {
                if (waitingScanByStableId.get(stableId) != request) {
                    holder[0].cancel(false); // 被更新的请求替代，本任务作废
                    return;
                }
                QDevice fresh = com.qingzhu.ble.sdk.core.scan.ScanCache.getInstance().getFresh(stableId, ADDR_FRESHNESS_MS);
                if (fresh != null) {
                    waitingScanByStableId.remove(stableId, request);
                    holder[0].cancel(false);
                    QLog.d(TAG, "设备重新被扫到，执行连接 stableId=" + stableId + " mac=" + fresh.getMac());
                    acquireSlot(request, fresh);
                    return;
                }
                if (System.currentTimeMillis() >= deadline) {
                    waitingScanByStableId.remove(stableId, request);
                    holder[0].cancel(false);
                    QLog.e(TAG, "等待设备重新广播超时 stableId=" + stableId);
                    ReconnectPolicy rp = policyByStableId.get(stableId);
                    if (rp != null) {
                        // 重连轮次中：等不到广播也算本轮失败，继续下一轮（直到重连次数耗尽）
                        QLog.w(TAG, "本轮等待广播超时，继续自动重连 stableId=" + stableId);
                        scheduleNextReconnectAttempt(request, request.getMac(), stableId, rp);
                        return;
                    }
                    String msg = "等待设备重新广播超时（" + WAIT_SCAN_TIMEOUT_MS / 1000
                            + "s），请确认设备在广播范围内";
                    // 整轮重试后残留的合并调用方也要收到最终失败（避免悬挂）
                    notifyMergedListeners(stableId, merged -> merged.onFailed(request.getMac(),
                            com.qingzhu.ble.sdk.core.common.ErrorCode.RESCAN_TIMEOUT, msg));
                    request.getListener().onFailed(request.getMac(),
                            com.qingzhu.ble.sdk.core.common.ErrorCode.RESCAN_TIMEOUT, msg);
                }
            } catch (Exception e) {
                QLog.e(TAG, "等待扫描任务异常", e);
            }
        }, 300, 300, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /**
     * 重连耗尽后的主动清理：清掉这台设备残留的连接状态与扫描缓存。
     * 场景：设备关机再开机后，系统可能仍残留到旧地址的连接状态，把它当"已连接"而不上报广播——
     * 主动走一遍断连收尾，保证下一次身份帧到来时能从干净状态重新进入。
     */
    private void hardCleanupForReconnectFailure(String stableId, String mac) {
        // 清扫描缓存：地址已失效，重连（若有）必须等设备重新广播被扫到
        com.qingzhu.ble.sdk.core.scan.ScanCache.getInstance().remove(stableId);
        // 清残留的释放标记/活动映射/会话，避免"连接中"假象
        releasingStableIds.remove(stableId);
        activeMacByStableId.remove(stableId);
        connectedStableIds.remove(stableId);
        stableIdByMac.remove(mac);
        SessionRegistry.remove(mac);
        // 兜底：回收不属于任何活动连接的孤儿 GATT（泄漏的 GATT 会让从机保持"已连接"而不广播）
        com.qingzhu.ble.sdk.core.platform.AndroidGattClient.closeOrphanGatts(this::isActiveMac);
        QLog.d(TAG, "重连失败清理完成：已断开并清空扫描缓存 stableId=" + stableId + " mac=" + mac
                + "（等设备下一次广播身份帧重新进入）");
    }

    /** 全局等待队列补位（槎位空闲时取队首） */
    private void drainWaitingQueue() {
        if (activeSessions.size() < maxPoolSize) {
            ConnectRequest next = waitingQueue.poll();
            if (next != null) {
                executeConnect(next);
            }
        }
    }

    public int getActiveCount() {
        return activeSessions.size();
    }

    /** 该地址是否属于连接池里的活动连接（含连接进行中；含重寻址后的实际 GATT 地址） */
    public boolean isActiveMac(String mac) {
        if (mac == null) {
            return false;
        }
        for (java.util.Map.Entry<String, Session> e : activeSessions.entrySet()) {
            if (e.getKey().equalsIgnoreCase(mac)) {
                return true;
            }
            String gattAddr = e.getValue().get(SessionKeys.CURRENT_GATT_ADDR);
            if (gattAddr != null && gattAddr.equalsIgnoreCase(mac)) {
                return true;
            }
        }
        return false;
    }

    /** 活动连接地址（诊断用） */
    public java.util.Set<String> getActiveMacs() {
        return new java.util.HashSet<>(activeSessions.keySet());
    }

    public int getWaitingCount() {
        return waitingQueue.size();
    }

    /** 释放中（GATT 异步断开未完成）的设备数，诊断用 */
    public int getReleasingCount() {
        return releasingStableIds.size();
    }

    public int getCorePoolSize() {
        return corePoolSize;
    }

    /** @deprecated 语义已并入「并发容量」，用 {@link #getConcurrencyCapacity()}；本方法为了兼容保留 */
    @Deprecated
    public int getMaxPoolSize() {
        return getConcurrencyCapacity();
    }

    // ================= 并发容量（"同时连接几台"的唯一权威） =================
    //
    // 这里是整个 SDK 里唯一持有"生效并发数"的地方：
    //   · core 内置自适应只产出"建议值"，由门面调 setConcurrencyCapacity 写进来；
    //   · SDK 策略想钉死一个数，调 lockConcurrencyCapacity（拒绝后续一切写入）；
    //   · 任何要用"同时几台"的地方（门面建批量 worker、上层做并发决策）都必须读 getConcurrencyCapacity。
    // 这样"建议"与"生效"分离：算法可以随便建议，但生效永远是池子说了算。

    /**
     * 当前<b>生效</b>的并发容量：强制值优先，其次是被设置的值。
     * 上层要用"同时几台"时必须读这里，不要另存一份。
     */
    public int getConcurrencyCapacity() {
        return forcedCapacity > 0 ? forcedCapacity : maxPoolSize;
    }

    /** 是否处于强制状态（被 SDK 策略锁定：外部调整会被忽略） */
    public boolean isConcurrencyCapacityLocked() {
        return forcedCapacity > 0;
    }

    /**
     * 设置并发容量（常规路径）。处于强制状态时<b>忽略</b>——强制优先于任何建议。
     * core 内置自适应的建议值、使用方的手动校准都走这里。
     */
    public void setConcurrencyCapacity(int capacity) {
        if (forcedCapacity > 0) {
            QLog.d(TAG, "并发容量已被强制为 " + forcedCapacity + "，忽略设置请求 " + capacity);
            return;
        }
        applyCapacity(capacity);
    }

    /**
     * 强制锁定并发容量（SDK 策略，优先级最高）：覆盖设置值，且后续
     * {@link #setConcurrencyCapacity(int)} 一律被忽略，直到 {@link #unlockConcurrencyCapacity()}。
     *
     * <p>注意：这是<b>运行期策略</b>，不持久化——由集成方在 init 后按需设置。
     */
    public void lockConcurrencyCapacity(int capacity) {
        forcedCapacity = sanitizeCapacity(capacity);
        applyCapacity(forcedCapacity);
        QLog.w(TAG, "并发容量被强制锁定为 " + forcedCapacity + "（此后外部设置一律忽略）");
    }

    /** 解除强制锁定：恢复用最近一次设置的并发容量 */
    public void unlockConcurrencyCapacity() {
        if (forcedCapacity == 0) {
            return;
        }
        int unlocked = forcedCapacity;
        forcedCapacity = 0;
        QLog.w(TAG, "并发容量解除强制锁定（原 " + unlocked + "，当前生效 " + maxPoolSize + "）");
        drainWaitingQueue();
    }

    private void applyCapacity(int capacity) {
        int next = sanitizeCapacity(capacity);
        int previous = maxPoolSize;
        if (next == previous) {
            return;
        }
        maxPoolSize = next;
        QLog.d(TAG, "并发容量 " + previous + " → " + next);
        if (next > previous) {
            drainWaitingQueue(); // 放宽容量后，让排队的连接请求有机会补位
        }
    }

    private int sanitizeCapacity(int capacity) {
        return Math.max(corePoolSize, capacity);
    }
}
