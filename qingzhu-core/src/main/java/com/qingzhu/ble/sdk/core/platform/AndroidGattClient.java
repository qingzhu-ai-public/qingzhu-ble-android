// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.sdk.core.platform;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

import com.qingzhu.ble.sdk.core.common.ErrorCode;
import com.qingzhu.ble.sdk.core.common.GattError;
import com.qingzhu.ble.sdk.core.common.QLog;
import com.qingzhu.ble.sdk.core.connect.ConnectCallback;
import com.qingzhu.ble.sdk.core.connect.ConnectParams;
import com.qingzhu.ble.sdk.core.connect.GattClient;
import com.qingzhu.ble.sdk.core.settings.BleSettings;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * core 内置的 {@link GattClient} Android 平台实现，封装 {@link BluetoothGatt}。
 *
 * 之前放在 demo 里（AndroidGattClient），接入方必须自己 new 一个并调用
 * GattClientFactory.setProvider(...) 注入——现在由 QingzhuBle.init(Context) 默认注入，
 * 接入方不再需要关心这一层；单测或特殊平台仍可通过 GattClientFactory.setProvider 覆盖。
 *
 * <h3>单 GATT 操作互斥</h3>
 * Android BLE 栈同一连接同时只可靠支持一个未完成的 GATT 操作。本实现内置检测：
 * 上一个操作的回调未到达就发起下一个操作时，打 W 级日志；调用了
 * {@link #setStrictOperationAssertion(boolean)}（单测/dev 建议开启）时直接抛
 * {@link IllegalStateException}，把违反契约的调用方当场暴露出来。
 */
public final class AndroidGattClient implements GattClient, NativeGatt {

    /** 严格模式开关：true 时并发 GATT 操作直接抛异常（默认 false，仅打警告）。单测建议开启。 */
    private static volatile boolean strictOperationAssertion = false;

    public static void setStrictOperationAssertion(boolean strict) {
        strictOperationAssertion = strict;
    }

    /**
     * 连接成功后是否请求<b>高连接优先级</b>（{@code CONNECTION_PRIORITY_HIGH}）。
     *
     * <p>这是系统 API 里对"传输吞吐"影响最大的开关之一：BLE 默认连接参数偏省电（连接间隔长、
     * 从机延迟大），提升优先级后可显著加快刷图 / 批量下发 / OTA 的速度。旧 SDK
     *（{@code BaseConnTagManager.onConnectStateChange}）在 {@code STATE_CONNECTED} 时调用了它，
     * 本类对齐该行为——否则新 SDK 在传输速度上会明显落后于旧实现。
     *
     * <p>配置：{@code settings().highConnectionPriority(false)}（默认 true）。
     * 个别机型若因高优先级出现异常可全局关闭；注意这是全局开关，会影响所有协议。
     */
    public static void setHighConnectionPriorityEnabled(boolean enabled) {
        BleSettings.get().highConnectionPriority(enabled);
    }

    /**
     * 便捷方法：设置默认写类型（等价于 {@code settings().writeType(n)}）。
     * 可选值见设置项 {@code ble.writeType} 的说明（DEFAULT / NO_RESPONSE / SIGNED）。
     */
    public static void setDefaultWriteType(int writeType) {
        BleSettings.get().writeType(sanitizeWriteType(writeType));
    }

    public static int getDefaultWriteType() {
        return sanitizeWriteType(BleSettings.get().writeType());
    }

    /** 写类型只接受系统定义的三种；其余一律回退 DEFAULT（防止配错把写入搞挂） */
    private static int sanitizeWriteType(int writeType) {
        if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                || writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                || writeType == BluetoothGattCharacteristic.WRITE_TYPE_SIGNED) {
            return writeType;
        }
        QLog.w(TAG, "非法写类型 " + writeType + "，回退 WRITE_TYPE_DEFAULT");
        return BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
    }

    /** 提升连接优先级的延迟：等建连流程（MTU/认证）全部走完再改连接参数，避开参数更新窗口。与旧 SDK SERVICE_DISCOVERY_DELAY_MS=100 对齐 */
    private static final long PRIORITY_DELAY_MS = 100;

    /**
     * 请求高连接优先级（{@code CONNECTION_PRIORITY_HIGH}）——<b>延迟到连接就绪之后</b>执行。
     *
     * <p>为什么不能在 {@code STATE_CONNECTED} 时立即请求：{@code requestConnectionPriority}
     * 会触发 HCI {@code LE Connection Update}（连接参数更新），更新窗口期内紧随其后的
     * discoverServices / MTU / 认证写帧可能失败——实测表现为「连接成功 0.5~2s 后 write 报
     * 无效句柄、链路断开、设备换址重连」的死循环。旧 SDK 配的
     * {@code SERVICE_DISCOVERY_DELAY_MS=100} 正是这个原因。
     *
     * <p>本方法由连接池在「连接就绪（认证完成）」后调用，再延迟 {@link #PRIORITY_DELAY_MS}；
     * 此时业务指令尚未开始（心跳最早空闲 10s 才发），参数更新窗口完全避开业务收发。
     * 请求失败只记日志，绝不影响连接。开关：{@code ble.highConnectionPriority}（默认 true）。
     */
    @SuppressLint("MissingPermission")
    @Override
    public void requestHighPriorityDelayed() {
        MAIN.postDelayed(() -> {
            BluetoothGatt g = gatt;
            if (g == null) {
                return; // 连接已释放
            }
            // 高连接优先级 + 2M PHY 受同一个开关控制：短连接（连-发-断）/ 不支持参数更新的设备
            // 应整体关闭，否则 requestConnectionPriority 触发的 HCI 连接参数更新会撞上指令回执
            // 窗口，导致链路断开（status=59）、指令误判失败
            if (!BleSettings.get().getBoolean("ble.highConnectionPriority")) {
                return;
            }
            // 高连接优先级（缩短连接间隔，提升吞吐）。不请求 2M PHY——见 requestHighPriority 注释，
            // PHY 切换时机不可控会撞上后续收发、断链（status=59）
            try {
                boolean ok = g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
                QLog.d(TAG, "requestConnectionPriority(HIGH) 已请求（连接就绪后延迟 "
                        + PRIORITY_DELAY_MS + "ms）结果=" + ok);
            } catch (Exception e) {
                QLog.w(TAG, "requestConnectionPriority 异常（忽略，不影响连接）: " + e.getMessage());
            }
        }, PRIORITY_DELAY_MS);
    }

    /** 立即请求高连接优先级（大数据发送前调用，见 BleAdapter.prepareForLargeTransfer）。
     *  只请求连接参数 HIGH、<b>不请求 2M PHY</b>——2M PHY 切换时机不可控，实测会在大块下发
     *  刚开始时（writeBulk 后 ~50ms）触发 PHY 切换、链路断开（status=59）。1M PHY 足够，
     *  吞吐提升主要靠连接间隔缩短（HIGH），不靠 PHY。 */
    @SuppressLint("MissingPermission")
    @Override
    public void requestHighPriority() {
        if (!BleSettings.get().getBoolean("ble.highConnectionPriority")) {
            return; // 开关关闭
        }
        BluetoothGatt g = gatt;
        if (g == null) {
            return;
        }
        try {
            boolean ok = g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
            QLog.d(TAG, "requestConnectionPriority(HIGH) 已请求结果=" + ok);
        } catch (Exception e) {
            QLog.w(TAG, "requestConnectionPriority 异常（忽略）: " + e.getMessage());
        }
    }

    @SuppressLint({"MissingPermission", "NewApi"})
    @Override
    public void request2MPhy() {
        BluetoothGatt g = gatt;
        if (g == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        BluetoothManager bm = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = bm != null ? bm.getAdapter() : null;
        if (adapter == null || !adapter.isLe2MPhySupported()) {
            QLog.d(TAG, "request2MPhy：本机不支持 2M PHY，跳过");
            return;
        }
        try {
            g.setPreferredPhy(BluetoothDevice.PHY_LE_2M_MASK,
                    BluetoothDevice.PHY_LE_2M_MASK,
                    BluetoothDevice.PHY_OPTION_NO_PREFERRED);
            QLog.d(TAG, "request2MPhy 已请求（2M PHY）");
        } catch (Exception e) {
            QLog.w(TAG, "request2MPhy 异常（忽略，不影响连接）: " + e.getMessage());
        }
    }

    private static final String TAG = "AndroidGattClient";
    /** 使能通知的瞬态失败重试：最多尝试次数 + 每次重试前的延迟 */
    private static final int NOTIFY_MAX_ATTEMPTS = 3;
    private static final long NOTIFY_RETRY_DELAY_MS = 300;
    /**
     * connectGatt 后等待 onConnectionStateChange 的兜底超时。Android BLE 栈偶发不回调连接状态，
     * 不兜底的话上游（连接池建连闸门）会一直等到闸门超时（30s）才放行——表现为
     * "一台设备卡住，后面所有设备的建连都被堵着"。
     */
    private static final long DEFAULT_CONNECT_CALLBACK_TIMEOUT_MS = 20_000L;
    private static volatile long connectCallbackTimeoutMs = DEFAULT_CONNECT_CALLBACK_TIMEOUT_MS;
    private static final String UUID_SUFFIX = "-0000-1000-8000-00805f9b34fb";

    /**
     * 设置系统连接状态回调兜底超时。部分慢设备建立 GATT 需要 13~20 秒，
     * 过短会在系统尚未回调时被误判为失败并触发无意义重试。
     */
    public static void setConnectCallbackTimeoutMs(long timeoutMs) {
        connectCallbackTimeoutMs = Math.max(5_000L, timeoutMs);
    }

    public static long getConnectCallbackTimeoutMs() {
        return connectCallbackTimeoutMs;
    }
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private final Context context;
    private final String address;
    private BluetoothGatt gatt;
    /** 优雅断开的兜底任务：3 秒内没收到 DISCONNECTED 回调就强制 close（防 GATT 泄漏） */
    private Runnable disconnectFallback;
    /** 连接回调兜底任务：connectGatt 后系统迟迟不回调连接状态时，判定连接失败 */
    private Runnable connectTimeout;
    /** 断开完成通知（onClosed 只触发一次） */
    private Runnable closedNotify;
    private volatile NotificationListener notificationListener;
    /** 设备主动上报的持久订阅监听器（与一问一答的应答监听器独立，不被 send 覆盖） */
    private volatile NotificationListener subscriptionListener;
    /** 链路意外断开监听（被动断开时触发；主动 disconnect 不算） */
    private volatile Runnable unexpectedCloseListener;
    /** 断开时是否解除系统配对（removeBond）：由协议 {@link ProtocolPlugin#removeBondOnDisconnect()} 决定，连接时写入 */
    private volatile boolean removeBondOnDisconnect = false;
    /** 主动关闭标记：disconnect() 置位，connect() 复位——用于区分主动断开与意外断开 */
    private final java.util.concurrent.atomic.AtomicBoolean closing =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private final ConcurrentHashMap<String, GattCallback> pendingCallbacks = new ConcurrentHashMap<>();
    /** 无响应批量写（流水线）的写完成回调队列：FIFO 匹配 onCharacteristicWrite，支持多片 in-flight */
    private final java.util.Queue<GattCallback> bulkWriteCallbacks = new java.util.concurrent.ConcurrentLinkedQueue<>();
    /** GATT 链路拥塞（0x8F=143）：批量并发刷图/OTA 时 BLE 控制器缓冲满会回这个码，属瞬时可恢复，退避重试而非直接失败 */
    private static final int GATT_CONNECTION_CONGESTED = 0x8F;
    /** 单次 writeBulk 内允许的连续拥塞重试上限，超过判定真失败（防止链路持续拥塞时死循环） */
    private static final int MAX_CONGESTION_RETRIES = 30;

    public AndroidGattClient(Context context, String address) {
        this.context = context.getApplicationContext();
        this.address = address;
    }

    /** 断开时是否解除系统配对（由连接引擎按协议 removeBondOnDisconnect() 写入） */
    public void setRemoveBondOnDisconnect(boolean removeBondOnDisconnect) {
        this.removeBondOnDisconnect = removeBondOnDisconnect;
    }

    /**
     * 包装一条<b>协议自己 connectGatt 建立</b>的 GATT（原生直通场景）：
     * 协议用自己的参数（transport/PHY）调 device.connectGatt 后，用本方法把得到的
     * BluetoothGatt 挂成 GattClient，后续内置步骤（发现服务/MTU/通知）仍可复用。
     *
     * 注意：这条 gatt 的 BluetoothGattCallback 由协议自己在 connectGatt 时传入，
     * 所以设备的操作结果（发现服务/MTU/描述符写/特征写）与 notify/断链都不会自动走到 core——
     * 需要协议在自己的回调里桥接（漏转发一个，对应的内置步骤就永远等不到结果、只能超时）：
     * <pre>
     *   // 协议回调里逐项转发：
     *   onCharacteristicChanged(g, ch)  -> client.onNativeNotify(ch.getUuid().toString(), ch.getValue());
     *   onServicesDiscovered(g, status) -> client.onNativeServicesDiscovered(status);
     *   onMtuChanged(g, mtu, status)    -> client.onNativeMtuChanged(mtu, status);
     *   onDescriptorWrite(g, d, status) -> client.onNativeDescriptorWrite(status);
     *   onCharacteristicWrite(g, ch, s) -> client.onNativeCharacteristicWrite(s);
     *   onConnectionStateChange(...)     -> if (DISCONNECTED) client.onNativeDisconnected(status);
     * </pre>
     */
    public static AndroidGattClient adopt(Context context, String address, BluetoothGatt gatt) {
        AndroidGattClient c = new AndroidGattClient(context, address);
        c.gatt = gatt;
        track(gatt);
        return c;
    }

    // ---- 进程内 GATT 对象登记（排查/回收泄漏的 GATT：泄漏的 GATT 会让系统保持到从机的链路，
    //      从机处于"已连接"就不广播——表现为设备重新开机后扫不到，重启 App / 开关蓝牙才恢复） ----

    private static final java.util.Set<BluetoothGatt> OPEN_GATTS = ConcurrentHashMap.newKeySet();

    private static void track(BluetoothGatt g) {
        if (g != null) {
            OPEN_GATTS.add(g);
        }
    }

    /** 当前进程内尚未 close 的 GATT 对象地址（诊断用） */
    public static java.util.List<String> openGattAddresses() {
        java.util.List<String> list = new java.util.ArrayList<>();
        for (BluetoothGatt g : OPEN_GATTS) {
            list.add(g.getDevice().getAddress());
        }
        return list;
    }

    /**
     * 回收孤儿 GATT：地址不属于任何活动连接（isActive 返回 false）的 GATT 对象一律 disconnect + close。
     * 在主线程执行。
     */
    @SuppressLint("MissingPermission")
    public static void closeOrphanGatts(java.util.function.Predicate<String> isActive) {
        MAIN.post(() -> {
            for (BluetoothGatt g : OPEN_GATTS) {
                String addr = g.getDevice().getAddress();
                if (isActive.test(addr)) {
                    continue;
                }
                QLog.w(TAG, "回收孤儿 GATT（无活动连接却未 close，会让从机保持已连接而不广播）address=" + addr);
                try {
                    g.disconnect();
                } catch (Exception ignored) {
                }
                try {
                    g.close();
                } catch (Exception ignored) {
                }
                OPEN_GATTS.remove(g);
            }
        });
    }

    // ---- NativeGatt（原生对象直通） ----

    @Override
    public BluetoothGatt getRawGatt() {
        return gatt;
    }

    @Override
    public BluetoothDevice getRawDevice() {
        BluetoothManager bm = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        return bm != null ? bm.getAdapter().getRemoteDevice(address) : null;
    }

    /** 协议自己的 GATT 回调转发设备 notify 到 core（供 AuthCmac 等"等通知应答"步骤使用） */
    public void onNativeNotify(String characteristicUuid, byte[] value) {
        dispatchNotify(characteristicUuid, value);
    }

    /** notify 统一分发：应答监听器与订阅监听器都收到 */
    private void dispatchNotify(String characteristicUuid, byte[] value) {
        NotificationListener l = notificationListener;
        if (l != null) {
            MAIN.post(() -> l.onNotify(characteristicUuid, value));
        }
        NotificationListener sl = subscriptionListener;
        if (sl != null) {
            MAIN.post(() -> sl.onNotify(characteristicUuid, value));
        }
    }

    /** 协议自己的 GATT 回调转发断链事件到 core（供连接池意外断开感知/自动重连使用） */
    public void onNativeDisconnected(int status) {
        QLog.w(TAG, "【断开】", "原生直通断链 status=" + GattError.describe(status) + " address=" + address);
        failAllPendingCallbacks(status);
        Runnable l = unexpectedCloseListener;
        if (l != null && !closing.get()) {
            MAIN.post(l);
        }
        // 链路已断：立即彻底关闭（清缓存 + close），与内置连接的 DISCONNECTED 处理对齐。
        // 否则 gatt 半开，设备端收不到完整断开而不重新广播（表现为"断开后重连不上"）。
        closeGatt(gatt);
    }

    /**
     * —— 原生直通回调桥接区 ——
     * 协议自己 connectGatt 时，BluetoothGattCallback 是协议传的那个，系统的操作结果
     * （发现服务/MTU/描述符写/特征写）只会进协议的回调。协议要"用完 core 的内置步骤
     * （DiscoverServices/RequestMtu/EnableNotification/AuthCmac/write）"，就必须把这些
     * 回调转给本类——否则对应步骤的 pendingCallback 永远收不到结果，只能等超时。
     */
    public void onNativeServicesDiscovered(int status) {
        complete("discover", status == BluetoothGatt.GATT_SUCCESS, status, "discoverServices " + GattError.describe(status));
    }

    public void onNativeMtuChanged(int mtu, int status) {
        complete("mtu", status == BluetoothGatt.GATT_SUCCESS, status, "requestMtu mtu=" + mtu + " " + GattError.describe(status));
    }

    /** PHY 切换结果（诊断日志用）：确认 2M PHY 是否真的协商成功 */
    public void onNativePhyUpdate(int txPhy, int rxPhy, int status) {
        QLog.d(TAG, "onPhyUpdate txPhy=" + describePhy(txPhy) + " rxPhy=" + describePhy(rxPhy)
                + " status=" + GattError.describe(status));
    }

    /** PHY 常量 → 可读名（1M/2M/CODED），仅诊断日志用 */
    private static String describePhy(int phy) {
        switch (phy) {
            case BluetoothDevice.PHY_LE_1M:
                return "1M";
            case BluetoothDevice.PHY_LE_2M:
                return "2M";
            case BluetoothDevice.PHY_LE_CODED:
                return "CODED";
            default:
                return String.valueOf(phy);
        }
    }

    public void onNativeDescriptorWrite(int status) {
        complete("notify", status == BluetoothGatt.GATT_SUCCESS, status, "enableNotification " + GattError.describe(status));
    }

    public void onNativeCharacteristicWrite(int status) {
        onWriteCompleted(status);
    }

    /**
     * —— 原生直通桥接工厂 ——
     * 协议要"裸调 connectGatt"（自定义 transport/PHY 参数）时，用本方法构造一个已经把
     * 6 类系统事件自动转发到 core 的 BluetoothGattCallback：连接状态（成功/断链）、发现服务、
     * MTU、描述符写、特征写、notify——协议一行都不用自己写。协议只需：
     * <pre>
     *   AtomicReference&lt;AndroidGattClient&gt; ref = new AtomicReference&lt;&gt;();
     *   BluetoothGattCallback cb = AndroidGattClient.newNativeCallback(
     *           () -&gt; ref.get() != null ? ref.get() : session.get(SessionKeys.GATT_CLIENT), connectCallback);
     *   BluetoothGatt gatt = device.connectGatt(ctx, false, cb, TRANSPORT_LE, PHY_LE_1M_MASK);
     *   AndroidGattClient client = AndroidGattClient.adopt(ctx, addr, gatt);
     *   ref.set(client);
     *   session.put(SessionKeys.GATT_CLIENT, client);
     * </pre>
     * clientSupplier 延迟取 client：adopt 发生在 connectGatt 之后，但连接回调可能先到；
     * 断开时 session 又可能已被 release 清空——所以优先用 ref 里的强引用兜底。
     */
    public static BluetoothGattCallback newNativeCallback(
            final java.util.function.Supplier<AndroidGattClient> clientSupplier,
            final ConnectCallback callback) {
        return new BluetoothGattCallback() {
            /** 连接阶段是否已定论：onConnected / onFailed 只触发一次 */
            private final java.util.concurrent.atomic.AtomicBoolean connectSettled =
                    new java.util.concurrent.atomic.AtomicBoolean(false);
            /** 连接回调兜底：原生直通路径（协议自己 connectGatt）同样可能遇到系统不回调连接状态 */
            private final Runnable connectTimeoutTask = () -> {
                if (connectSettled.compareAndSet(false, true)) {
                    QLog.e(TAG, "[native] connectGatt 后 " + getConnectCallbackTimeoutMs()
                            + "ms 未收到连接状态回调，判定连接失败");
                    callback.onFailed(ErrorCode.CONNECT_TIMEOUT,
                            "连接超时（系统 " + (getConnectCallbackTimeoutMs() / 1000) + "s 未回调连接状态）");
                }
            };

            {
                // 匿名类实例初始化块：callback 在 connectGatt 之前创建，超时窗口从此刻起算（与发起几乎同刻）
                MAIN.postDelayed(connectTimeoutTask, getConnectCallbackTimeoutMs());
            }

            private AndroidGattClient client() {
                return clientSupplier.get();
            }

            @Override
            @SuppressLint("MissingPermission")
            public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
                QLog.d(TAG, "[native] onConnectionStateChange status=" + status + " newState=" + newState);
                AndroidGattClient client = client();
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    // 提升连接优先级已挪到「连接就绪后延迟执行」（requestHighPriorityDelayed，
                    // 由连接池在认证完成后调用）——此处立即请求会触发连接参数更新，撞上随后
                    // 的 MTU/认证流程导致链路断（实测死循环），这里不再提
                    if (connectSettled.compareAndSet(false, true)) {
                        MAIN.removeCallbacks(connectTimeoutTask);
                        callback.onConnected();
                    } else {
                        // 连接阶段早已判定失败（15s 兜底超时）后系统才回调连上：上层已放弃这条连接，
                        // 没人会再管它——不立即关掉就泄漏成"系统层已连接"，从机认为已连接而不再广播
                        QLog.w(TAG, "[native] 连接已判超时失败后才连上，立即关闭这条迟到的链路 address="
                                + g.getDevice().getAddress());
                        try {
                            g.disconnect();
                        } catch (Exception ignored) {
                        }
                        try {
                            g.close();
                        } catch (Exception ignored) {
                        }
                        OPEN_GATTS.remove(g);
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    MAIN.removeCallbacks(connectTimeoutTask);
                    // 断开收尾：失败未完成的 GATT 操作 + 通知意外断开监听（主动断开不通知）
                    if (client != null) {
                        client.onNativeDisconnected(status);
                    }
                    // 关键：连接阶段"已定论"（之前 onConnected 过）后不再报连接失败。
                    // 否则设备配置完主动断开时会被误报成"原生连接断开"，进而触发连接池整轮重连，
                    // 把已成功的设备拖回失败、并干扰并发的其它设备。
                    if (connectSettled.compareAndSet(false, true)) {
                        callback.onFailed(status == 0 ? ErrorCode.CONNECTION_LOST : ErrorCode.CONNECT_GATT_FAILED,
                                "原生连接断开 status=" + status);
                    }
                }
            }

            @Override
            public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic characteristic) {
                AndroidGattClient client = client();
                if (client != null) {
                    client.onNativeNotify(characteristic.getUuid().toString(), characteristic.getValue());
                }
            }

            @Override
            public void onServicesDiscovered(BluetoothGatt g, int status) {
                AndroidGattClient client = client();
                if (client != null) {
                    client.onNativeServicesDiscovered(status);
                }
            }

            @Override
            public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
                AndroidGattClient client = client();
                if (client != null) {
                    client.onNativeMtuChanged(mtu, status);
                }
            }

            @Override
            public void onPhyUpdate(BluetoothGatt g, int txPhy, int rxPhy, int status) {
                AndroidGattClient client = client();
                if (client != null) {
                    client.onNativePhyUpdate(txPhy, rxPhy, status);
                }
            }

            @Override
            public void onDescriptorWrite(BluetoothGatt g, android.bluetooth.BluetoothGattDescriptor descriptor, int status) {
                AndroidGattClient client = client();
                if (client != null) {
                    client.onNativeDescriptorWrite(status);
                }
            }

            @Override
            public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic characteristic, int status) {
                AndroidGattClient client = client();
                if (client != null) {
                    client.onNativeCharacteristicWrite(status);
                }
            }
        };
    }

    private static UUID toUuid(String shortOrFull) {
        if (shortOrFull.length() == 4) {
            return UUID.fromString("0000" + shortOrFull.toLowerCase() + UUID_SUFFIX);
        }
        return UUID.fromString(shortOrFull);
    }

    @Override
    public void setUnexpectedCloseListener(Runnable listener) {
        this.unexpectedCloseListener = listener;
    }

    @Override
    public void connect(GattCallback callback) {
        connect(callback, null);
    }

    @Override
    @SuppressLint("MissingPermission")
    public void connect(GattCallback callback, ConnectParams params) {
        QLog.d(TAG, "connect() address=" + address);
        closing.set(false); // 新一轮连接：复位主动关闭标记
        BluetoothManager bm = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm == null || bm.getAdapter() == null) {
            QLog.e(TAG, "BluetoothManager/Adapter 为 null，设备不支持蓝牙或系统服务获取失败");
            callback.onResult(false, -1, "BluetoothManager/Adapter 为 null");
            return;
        }
        BluetoothDevice device;
        try {
            device = bm.getAdapter().getRemoteDevice(address);
        } catch (IllegalArgumentException e) {
            QLog.e(TAG, "getRemoteDevice 失败，address 格式非法: " + address, e);
            callback.onResult(false, -1, "非法 mac 地址: " + address);
            return;
        }
        beginOperation("connect");
        pendingCallbacks.put("connect", callback);
        // 连接参数（ConnectParams 声明的"连接意图"）：全部未声明时走系统默认（不传 transport/phy），
        // 声明了才用带参重载——默认行为不变，而门面暴露的 autoConnect / transport / phy 能真正生效
        final boolean autoConnect = params != null && Boolean.TRUE.equals(params.getAutoConnect());
        final Integer transport = params != null ? params.getTransport() : null;
        final Integer phy = params != null ? params.getPreferredPhy() : null;
        QLog.d(TAG, "调用 device.connectGatt(autoConnect=" + autoConnect
                + ", transport=" + transport + ", phy=" + phy + ")");
        BluetoothGattCallback nativeCb = new BluetoothGattCallback() {
            @Override
            public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
                QLog.d(TAG, "onConnectionStateChange status=" + status + " newState=" + newState
                        + "(2=CONNECTED,0=DISCONNECTED)");
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    // 提升连接优先级已挪到「连接就绪后延迟执行」（见 requestHighPriorityDelayed）：
                    // 此处立即请求会触发连接参数更新，撞上随后的发现服务/MTU/认证流程导致链路断
                    if (connectTimeout == null) {
                        // 连接阶段已定论（15s 兜底超时已触发、complete/closeGatt 已把 connectTimeout 置 null）
                        // 之后才收到 CONNECTED：这是"迟到链路"——连接其实已在系统层建立，但上层早已放弃、
                        // 不会再管它。不立即关掉就泄漏成"系统层已连接"（client_if/白名单/conn_id 残留），
                        // 从机认为已连接而不再响应新连接——表现为"连一次超时后一直连不上，重启 App 才恢复"。
                        QLog.w(TAG, "连接已判超时失败后才收到 CONNECTED（迟到链路），立即关闭防止泄漏 address="
                                + g.getDevice().getAddress());
                        try {
                            g.disconnect();
                        } catch (Exception ignored) {
                        }
                        try {
                            g.close();
                        } catch (Exception ignored) {
                        }
                        OPEN_GATTS.remove(g);
                        return;
                    }
                    complete("connect", true, 0, "connected");
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    QLog.d(TAG, "onConnectionStateChange status=" + GattError.describe(status)
                            + " newState=" + newState + "(2=CONNECTED,0=DISCONNECTED)");
                    // 链路已断：立刻失败所有未完成的操作回调（连接/发现服务/MTU/订阅/写...），
                    // 让正在执行的步骤马上得知（而不是干等 10s 超时、然后撞上已被 close 的 gatt）
                    failAllPendingCallbacks(status);
                    // 通知业务侧 notify 监听器（value=null 表示链路事件而非数据），
                    // 让"等认证应答"这类等待也立即失败，不用干等超时
                    dispatchNotify(null, null);
                    // 意外断开（非本实现发起的 disconnect）：通知监听者（连接池据此驱动自动重连）
                    if (!closing.get()) {
                        QLog.w(TAG, "【断开】", "链路意外断开 address=" + address + " status=" + GattError.describe(status));
                        Runnable l = unexpectedCloseListener;
                        if (l != null) {
                            MAIN.post(l);
                        }
                    }
                    // 断开回调到达：此刻才 close（对齐老 SDK "先 disconnect 等回调再 close" 的做法）
                    closeGatt(g);
                }
            }

            @Override
            public void onServicesDiscovered(BluetoothGatt g, int status) {
                QLog.d(TAG, "onServicesDiscovered status=" + status
                        + " serviceCount=" + (g.getServices() != null ? g.getServices().size() : -1));
                complete("discover", status == BluetoothGatt.GATT_SUCCESS, status, "discoverServices " + GattError.describe(status));
            }

            @Override
            public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
                QLog.d(TAG, "onMtuChanged mtu=" + mtu + " status=" + status);
                complete("mtu", status == BluetoothGatt.GATT_SUCCESS, status, "requestMtu mtu=" + mtu + " " + GattError.describe(status));
            }

            @Override
            public void onPhyUpdate(BluetoothGatt g, int txPhy, int rxPhy, int status) {
                QLog.d(TAG, "onPhyUpdate txPhy=" + describePhy(txPhy) + " rxPhy=" + describePhy(rxPhy)
                        + " status=" + GattError.describe(status));
            }

            @Override
            public void onDescriptorWrite(BluetoothGatt g, android.bluetooth.BluetoothGattDescriptor descriptor, int status) {
                QLog.d(TAG, "onDescriptorWrite status=" + status);
                complete("notify", status == BluetoothGatt.GATT_SUCCESS, status, "enableNotification " + GattError.describe(status));
            }

            @Override
            public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic characteristic, int status) {
                // 逐片写完成日志在大块下发（刷图上千片）时会刷屏，去掉；结果由 complete("write") /
                // writeBulk 的完成回调统一打印
                onWriteCompleted(status);
            }

            @Override
            public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic characteristic) {
                byte[] value = characteristic.getValue();
                QLog.d(TAG, "onCharacteristicChanged uuid=" + characteristic.getUuid()
                        + " value=" + bytesToHex(value));
                dispatchNotify(characteristic.getUuid().toString(), value);
            }

            @Override
            public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic characteristic, int status) {
                QLog.d(TAG, "onCharacteristicRead uuid=" + characteristic.getUuid() + " status=" + status);
                complete("read", status == BluetoothGatt.GATT_SUCCESS, status, "read " + GattError.describe(status));
            }
        };
        // 按声明的意图选择 connectGatt 重载：只有显式声明了 transport/phy 才用带参重载，
        // 未声明时保持系统默认（不改变既有连接行为）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && phy != null) {
            int t = transport != null ? transport : BluetoothDevice.TRANSPORT_LE;
            gatt = device.connectGatt(context, autoConnect, nativeCb, t, phy);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && transport != null) {
            gatt = device.connectGatt(context, autoConnect, nativeCb, transport);
        } else {
            gatt = device.connectGatt(context, autoConnect, nativeCb);
        }
        track(gatt);
        if (gatt == null) {
            QLog.e(TAG, "connectGatt 返回 null，连接发起失败（可能是权限缺失或蓝牙未开启）");
            // 必须回调失败：否则 pendingCallback 悬挂，上游（连接池建连闸门）会白等到闸门超时才放行
            complete("connect", false, ErrorCode.CONNECT_GATT_FAILED,
                    "connectGatt 返回 null（可能是权限缺失或蓝牙未开启）");
            return;
        }
        // 兜底超时：connectGatt 已发起、但系统迟迟不回调 onConnectionStateChange（Android 已知问题）。
        // 不兜底的话，连接池的建连闸门会被这一台堵到闸门超时（30s），后面所有设备都连不上。
        final Runnable timeout = () -> {
            QLog.e(TAG, "connectGatt 后 " + getConnectCallbackTimeoutMs()
                    + "ms 未收到连接状态回调，判定连接失败 address=" + address);
            complete("connect", false, ErrorCode.CONNECT_TIMEOUT,
                    "连接超时（系统 " + (getConnectCallbackTimeoutMs() / 1000) + "s 未回调连接状态）");
            // 关键：connectGatt 超时（系统没回调连接状态）时，gatt 处于半开状态，必须主动 close，
            // 否则 GATT client interface 泄漏、累积到一定程度后蓝牙栈拒绝新连接
            //（表现为"连一次断一次、之后一直连不上"）。
            closeGatt(gatt);
        };
        connectTimeout = timeout;
        MAIN.postDelayed(timeout, getConnectCallbackTimeoutMs());
    }

    private static String bytesToHex(byte[] bytes) {
        if (bytes == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02X", b));
        return sb.toString();
    }

    @Override
    @SuppressLint("MissingPermission")
    public void discoverServices(GattCallback callback) {
        QLog.d(TAG, "discoverServices()");
        if (gatt == null) {
            QLog.e(TAG, "discoverServices 失败：gatt 为 null（ConnectGatt 步骤还没成功就跑到这一步了？）");
            callback.onResult(false, -1, "gatt 未初始化");
            return;
        }
        beginOperation("discover");
        pendingCallbacks.put("discover", callback);
        gatt.discoverServices();
    }

    @Override
    @SuppressLint("MissingPermission")
    public void requestMtu(int size, GattCallback callback) {
        QLog.d(TAG, "requestMtu(" + size + ")");
        if (gatt == null) {
            QLog.e(TAG, "requestMtu 失败：gatt 为 null（连接已释放或尚未建立）");
            // 与 discoverServices 对齐：不能直接解引用 null——onGattReady 超时兜底后，
            // 仍在飞的协议钩子会走到这里，不挡住就是 NPE
            callback.onResult(false, ErrorCode.CONNECTION_LOST, "gatt 未初始化（连接已释放）");
            return;
        }
        beginOperation("mtu");
        pendingCallbacks.put("mtu", callback);
        gatt.requestMtu(size);
    }

    @Override
    @SuppressLint("MissingPermission")
    public void enableNotification(String serviceUuid, String characteristicUuid, GattCallback callback) {
        configureNotification(serviceUuid, characteristicUuid, true, ENABLE_NOTIFICATION_VALUE(),
                callback, NOTIFY_MAX_ATTEMPTS);
    }

    @Override
    @SuppressLint("MissingPermission")
    public void enableIndication(String serviceUuid, String characteristicUuid, GattCallback callback) {
        configureNotification(serviceUuid, characteristicUuid, true, ENABLE_INDICATION_VALUE(),
                callback, NOTIFY_MAX_ATTEMPTS);
    }

    @Override
    @SuppressLint("MissingPermission")
    public void disableNotification(String serviceUuid, String characteristicUuid, GattCallback callback) {
        configureNotification(serviceUuid, characteristicUuid, false, DISABLE_NOTIFICATION_VALUE(),
                callback, NOTIFY_MAX_ATTEMPTS);
    }

    /**
     * 统一配置 CCCD（使能 notify=0x01 / 使能 indicate=0x02 / 取消=0x00，带瞬态失败重试）。
     * 并发连接场景下 Android BLE 栈偶发瞬态错误（如多台设备同时写 CCCD 时返回
     * GATT_INVALID_HANDLE），这类错误不是链路问题——短暂延迟后重试通常即可成功，
     * 不能直接判死。链路断开（CONNECTION_LOST）不重试。
     */
    @SuppressLint("MissingPermission")
    private void configureNotification(String serviceUuid, String characteristicUuid, boolean enable,
                                       byte[] cccdValue, GattCallback callback, int attemptsLeft) {
        QLog.d(TAG, "configureNotification service=" + serviceUuid + " char=" + characteristicUuid
                + " enable=" + enable + " cccd=0x" + String.format("%02X", cccdValue[0])
                + " attemptsLeft=" + attemptsLeft);
        BluetoothGattCharacteristic characteristic = findCharacteristic(serviceUuid, characteristicUuid);
        if (characteristic == null) {
            QLog.e(TAG, "configureNotification 失败：特征值不存在 service=" + serviceUuid + " char=" + characteristicUuid
                    + "，请检查设备实际 GATT UUID 表是否与配方声明的一致");
            callback.onResult(false, -1, "特征值不存在: " + characteristicUuid);
            return;
        }
        beginOperation("notify");
        pendingCallbacks.put("notify", (success, code, message) -> {
            if (!success && attemptsLeft > 1 && code != com.qingzhu.ble.sdk.core.common.ErrorCode.CONNECTION_LOST) {
                QLog.w(TAG, "configureNotification 失败（" + message + "），" + NOTIFY_RETRY_DELAY_MS
                        + "ms 后重试（剩 " + (attemptsLeft - 1) + " 次）");
                MAIN.postDelayed(() -> configureNotification(
                        serviceUuid, characteristicUuid, enable, cccdValue, callback, attemptsLeft - 1),
                        NOTIFY_RETRY_DELAY_MS);
                return;
            }
            callback.onResult(success, code, message);
        });
        gatt.setCharacteristicNotification(characteristic, enable);
        android.bluetooth.BluetoothGattDescriptor descriptor =
                characteristic.getDescriptor(UUID.fromString("00002902" + UUID_SUFFIX));
        if (descriptor != null) {
            descriptor.setValue(cccdValue);
            gatt.writeDescriptor(descriptor);
        } else {
            QLog.w(TAG, "特征值 " + characteristicUuid + " 没有 CCCD 描述符，跳过描述符写入直接认为成功");
            complete("notify", true, 0, "无 CCCD，跳过描述符写入");
        }
    }

    private static byte[] ENABLE_NOTIFICATION_VALUE() {
        return new byte[]{0x01, 0x00};
    }

    private static byte[] ENABLE_INDICATION_VALUE() {
        return new byte[]{0x02, 0x00};
    }

    private static byte[] DISABLE_NOTIFICATION_VALUE() {
        return new byte[]{0x00, 0x00};
    }

    @Override
    @SuppressLint("MissingPermission")
    public boolean hasCharacteristic(String serviceUuid, String characteristicUuid) {
        boolean has = findCharacteristic(serviceUuid, characteristicUuid) != null;
        QLog.d(TAG, "hasCharacteristic service=" + serviceUuid + " char=" + characteristicUuid + " -> " + has);
        return has;
    }

    @Override
    @SuppressLint("MissingPermission")
    public void write(String serviceUuid, String characteristicUuid, byte[] data, GattCallback callback) {
        write(serviceUuid, characteristicUuid, data, BleSettings.get().getInt("ble.writeType"), callback);
    }

    @Override
    @SuppressLint("MissingPermission")
    public void write(String serviceUuid, String characteristicUuid, byte[] data, int writeType, GattCallback callback) {
        QLog.d(TAG, "write service=" + serviceUuid + " char=" + characteristicUuid + " len=" + data.length
                + " writeType=" + writeType + " data=" + bytesToHex(data));
        BluetoothGattCharacteristic characteristic = findCharacteristic(serviceUuid, characteristicUuid);
        if (characteristic == null) {
            QLog.e(TAG, "write 失败：特征值不存在 service=" + serviceUuid + " char=" + characteristicUuid);
            callback.onResult(false, -1, "特征值不存在: " + characteristicUuid);
            return;
        }
        beginOperation("write");
        pendingCallbacks.put("write", callback);
        // 应用写类型：本重载用调用方显式传入的 writeType；原 write 走全局 ble.writeType（默认 1 无响应）
        characteristic.setWriteType(sanitizeWriteType(writeType));
        characteristic.setValue(data);
        boolean ok = gatt.writeCharacteristic(characteristic);
        QLog.d(TAG, "gatt.writeCharacteristic 发起结果=" + ok + "（这只是同步发起结果，真正结果看 onCharacteristicWrite 回调）");
    }

    @Override
    @SuppressLint("MissingPermission")
    public void writeBulk(String serviceUuid, String characteristicUuid, java.util.List<byte[]> packets, GattCallback callback) {
        writeBulk(serviceUuid, characteristicUuid, packets, null, callback);
    }

    @Override
    @SuppressLint("MissingPermission")
    public void writeBulk(String serviceUuid, String characteristicUuid, java.util.List<byte[]> packets,
                          BulkProgressListener progress, GattCallback callback) {
        final BulkProgressListener progressListener = progress;
        BluetoothGattCharacteristic characteristic = findCharacteristic(serviceUuid, characteristicUuid);
        if (characteristic == null) {
            QLog.e(TAG, "writeBulk 失败：特征值不存在 service=" + serviceUuid + " char=" + characteristicUuid);
            callback.onResult(false, -1, "特征值不存在: " + characteristicUuid);
            return;
        }
        if (packets == null || packets.isEmpty()) {
            callback.onResult(true, 0, null);
            return;
        }
        final int total = packets.size();
        // 流水线窗口 + 缓冲满回退延迟：可配置（ble.bulkWriteWindow / ble.bufferRetryDelayMs）
        final int window = BleSettings.get().getInt("ble.bulkWriteWindow");
        final long retryDelay = BleSettings.get().getLong("ble.bufferRetryDelayMs");

        // 大块传输保活：总字节数达到阈值时持有 WakeLock，防刷图/OTA 期间 CPU 休眠丢包；
        // 包装完成回调，成功/失败任一出口都释放 WakeLock（带超时兜底，防异常路径泄漏）
        long totalBytes = 0;
        for (byte[] p : packets) {
            if (p != null) {
                totalBytes += p.length;
            }
        }
        final PowerManager.WakeLock wakeLock = acquireWakeLock(totalBytes);
        final GattCallback finish = (ok, code, msg) -> {
            releaseWakeLock(wakeLock);
            callback.onResult(ok, code, msg);
        };

        QLog.d(TAG, "writeBulk 开始 service=" + serviceUuid + " char=" + characteristicUuid
                + " packets=" + total + " window=" + window + " retryDelay=" + retryDelay
                + " totalBytes=" + totalBytes);
        final java.util.concurrent.atomic.AtomicInteger nextIndex = new java.util.concurrent.atomic.AtomicInteger(0);
        final java.util.concurrent.atomic.AtomicInteger inFlight = new java.util.concurrent.atomic.AtomicInteger(0);
        final java.util.concurrent.atomic.AtomicInteger completed = new java.util.concurrent.atomic.AtomicInteger(0);
        final java.util.concurrent.atomic.AtomicBoolean failed = new java.util.concurrent.atomic.AtomicBoolean(false);
        final java.util.concurrent.atomic.AtomicBoolean finished = new java.util.concurrent.atomic.AtomicBoolean(false);
        // 发送缓冲满回退次数（仅用于限频日志，避免大块下发时刷屏）
        final java.util.concurrent.atomic.AtomicInteger bufferFullCount = new java.util.concurrent.atomic.AtomicInteger(0);
        // 拥塞（0x8F）累计重试次数：拥塞是可恢复的瞬时状态，退避重发；单次 writeBulk 累计超限才判定真失败
        final java.util.concurrent.atomic.AtomicInteger congestionRetries = new java.util.concurrent.atomic.AtomicInteger(0);

        final Runnable[] pump = new Runnable[1];
        pump[0] = () -> {
            while (!failed.get() && !finished.get() && inFlight.get() < window) {
                int i = nextIndex.getAndIncrement();
                if (i >= total) {
                    break;
                }
                inFlight.incrementAndGet();
                // 先入队回调再发起写，保证 onCharacteristicWrite 的 FIFO 顺序与队列一致
                final int packetIndex = i;
                final GattCallback cb = (ok, code, msg) -> {
                    inFlight.decrementAndGet();
                    if (failed.get() || finished.get()) {
                        return;
                    }
                    if (!ok) {
                        if (code == GATT_CONNECTION_CONGESTED
                                && congestionRetries.incrementAndGet() <= MAX_CONGESTION_RETRIES) {
                            // 链路拥塞（0x8F）：非硬失败——清空在途、回退到该片，延迟后整段重发（保序）。
                            // 不直接失败：批量并发刷图时 BLE 控制器缓冲瞬时打满很常见，退避一下就能继续。
                            bulkWriteCallbacks.clear();
                            inFlight.set(0);
                            nextIndex.set(packetIndex);
                            if (congestionRetries.get() == 1 || congestionRetries.get() % 20 == 0) {
                                QLog.w(TAG, "writeBulk 链路拥塞(0x8F)，回退重发 packet=" + packetIndex
                                        + "（第 " + congestionRetries.get() + " 次）");
                            }
                            MAIN.postDelayed(() -> {
                                if (!failed.get() && !finished.get()) {
                                    pump[0].run();
                                }
                            }, Math.max(retryDelay, 10L));
                            return;
                        }
                        failed.set(true);
                        bulkWriteCallbacks.clear();
                        finish.onResult(false, code, msg);
                        return;
                    }
                    int done = completed.incrementAndGet();
                    if (progressListener != null) {
                        progressListener.onProgress(done, total);
                    }
                    if (done >= total) {
                        // 只回调一次（finished 防重入），避免重复回调导致上层重复处理
                        if (finished.compareAndSet(false, true)) {
                            QLog.d(TAG, "writeBulk 完成 total=" + total + " completed=" + done);
                            finish.onResult(true, 0, null);
                        }
                    } else {
                        pump[0].run();
                    }
                };
                bulkWriteCallbacks.offer(cb);
                characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                characteristic.setValue(packets.get(i));
                boolean ok = gatt.writeCharacteristic(characteristic);
                if (!ok) {
                    // 发送缓冲瞬时打满：回退这一片（未发出），稍后重试
                    bulkWriteCallbacks.remove(cb);
                    inFlight.decrementAndGet();
                    nextIndex.decrementAndGet();
                    // 缓冲满在大块下发时很常见（可能每片都回退一次），限频记录避免刷屏
                    if (bufferFullCount.incrementAndGet() == 1 || bufferFullCount.get() % 200 == 0) {
                        QLog.w(TAG, "writeBulk 发送缓冲满，回退重试 packet=" + i + "（累计 "
                                + bufferFullCount.get() + " 次）");
                    }
                    // 关键：若此时已无 in-flight 写，就不会再有 onCharacteristicWrite 回调来触发 pump，
                    // 必须自己延迟重试——否则大块下发（数千片）会在缓冲瞬时打满时永久卡死。
                    if (inFlight.get() == 0) {
                        if (retryDelay <= 0) {
                            // 无间隔模式：立即重试（激进，可能忙等）
                            pump[0].run();
                        } else {
                            MAIN.postDelayed(() -> {
                                if (!failed.get() && !finished.get()) {
                                    pump[0].run();
                                }
                            }, retryDelay);
                        }
                    }
                    break;
                }
            }
        };
        pump[0].run();
    }

    /**
     * 大块传输保活：总字节数达到 {@code ble.wakeLockThresholdBytes} 阈值时持有 PARTIAL_WAKE_LOCK，
     * 防止刷图/OTA 期间 CPU 休眠导致断流丢包。未达阈值、未声明 WAKE_LOCK 权限、或阈值被设为
     * Long.MAX_VALUE（关闭保活）时返回 null（静默降级，不影响传输）。
     */
    private PowerManager.WakeLock acquireWakeLock(long totalBytes) {
        long threshold = BleSettings.get().getLong("ble.wakeLockThresholdBytes");
        if (threshold <= 0 || threshold == Long.MAX_VALUE || totalBytes < threshold) {
            return null;
        }
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm == null) {
                return null;
            }
            PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG + ":bulk");
            wl.setReferenceCounted(false);
            // 带 10 分钟超时：即使异常路径忘记 release，也不会长期耗电
            wl.acquire(10 * 60_000L);
            QLog.d(TAG, "大块传输持有 WakeLock（" + totalBytes + " 字节）");
            return wl;
        } catch (Exception e) {
            QLog.w(TAG, "WakeLock 获取失败（可能未声明 WAKE_LOCK 权限），静默降级: " + e.getMessage());
            return null;
        }
    }

    private static void releaseWakeLock(PowerManager.WakeLock wl) {
        if (wl != null) {
            try {
                if (wl.isHeld()) {
                    wl.release();
                }
            } catch (Exception ignored) {
            }
        }
    }

    /** 写完成回调统一入口：优先匹配流水线批量写的 FIFO 队列，否则走普通写的 complete("write") */
    private void onWriteCompleted(int status) {
        GattCallback bulkCb = bulkWriteCallbacks.poll();
        if (bulkCb != null) {
            bulkCb.onResult(status == BluetoothGatt.GATT_SUCCESS, status, "write " + GattError.describe(status));
            return;
        }
        complete("write", status == BluetoothGatt.GATT_SUCCESS, status, "write " + GattError.describe(status));
    }

    @Override
    @SuppressLint("MissingPermission")
    public void read(String serviceUuid, String characteristicUuid, ReadCallback callback) {
        BluetoothGattCharacteristic characteristic = findCharacteristic(serviceUuid, characteristicUuid);
        if (characteristic == null) {
            callback.onRead(null, "特征值不存在: " + characteristicUuid);
            return;
        }
        // 特征不可读（无 PROPERTY_READ）：readCharacteristic 会返回 false 且不回调 onCharacteristicRead，
        // 若不主动拦截，pendingCallback 永不触发、上层干等超时（实测双面屏设备的该特征就是不可读，
        // 表现为「认证成功后 onGattReady 卡 15s 超时」）。
        if ((characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_READ) == 0) {
            QLog.d(TAG, "read 特征不可读（无 PROPERTY_READ），直接判定失败 char=" + characteristicUuid);
            callback.onRead(null, "特征不可读（无 PROPERTY_READ）");
            return;
        }
        QLog.d(TAG, "read service=" + serviceUuid + " char=" + characteristicUuid);
        beginOperation("read");
        pendingCallbacks.put("read", (success, code, message) -> {
            byte[] value = success ? characteristic.getValue() : null;
            callback.onRead(value, success ? null : message);
        });
        gatt.readCharacteristic(characteristic);
    }

    /**
     * 优雅断开（对齐老 SDK BaseBleManager.disconnectDevice 的处理顺序）：
     * 1. 只调 gatt.disconnect()——让系统走完整的断开流程并通知从机，从机收到后才能恢复广播；
     * 2. 等 DISCONNECTED 回调里再 close()（回调里同时清 GATT 缓存）；
     * 3. 3 秒兜底：回调丢失则强制 close，防止 GATT 对象泄漏。
     * 两条路径收尾时都会触发一次 {@code callback.onClosed()}（表示底层连接已完全关闭）。
     */
    @Override
    @SuppressLint("MissingPermission")
    public void disconnect(ClosedCallback callback) {
        if (gatt == null) {
            // 没有活动连接：视为立即关闭完成
            if (callback != null) {
                callback.onClosed();
            }
            return;
        }
        BluetoothGatt g = gatt;
        java.util.concurrent.atomic.AtomicBoolean notified = new java.util.concurrent.atomic.AtomicBoolean(false);
        // onClosed 只通知一次：正常回调路径和 3s 兜底路径谁先到谁生效
        this.closedNotify = () -> {
            if (notified.compareAndSet(false, true) && callback != null) {
                callback.onClosed();
            }
        };
        QLog.d(TAG, "【断开】", "disconnect() address=" + address + "（先 disconnect 等回调，3s 兜底强制 close）");
        closing.set(true); // 标记主动关闭：后续的 DISCONNECTED 回调不算"意外断开"
        // 旧 SDK 经验（mi.ard ConnTagManager 注释原文："disconnect 必须在主线程否则关不掉"）：
        // 统一 post 到主线程执行，调用方从任意线程调 disconnect 都安全
        MAIN.post(() -> {
            try {
                g.disconnect();
            } catch (Exception e) {
                QLog.w(TAG, "disconnect 异常（忽略，3s 兜底仍会 close）: " + e.getMessage());
            }
        });
        disconnectFallback = () -> {
            QLog.w(TAG, "【断开】", "断开回调 3s 未到达，强制 close address=" + address);
            closeGatt(g);
        };
        MAIN.postDelayed(disconnectFallback, 3000);
    }

    /** 关闭并彻底释放一个 GATT 连接（清缓存 + close），重复调用安全；完成后触发 closedNotify */
    @SuppressLint("MissingPermission")
    private void closeGatt(BluetoothGatt g) {
        if (disconnectFallback != null) {
            MAIN.removeCallbacks(disconnectFallback);
            disconnectFallback = null;
        }
        if (connectTimeout != null) {
            MAIN.removeCallbacks(connectTimeout); // GATT 已关闭：连接兜底超时一并撤销
            connectTimeout = null;
        }
        if (g == null) {
            return;
        }
        pendingCallbacks.clear(); // GATT 已关闭，残留的未完成操作一并清理（避免互斥表脏数据）
        try {
            refreshDeviceCache(g);
        } catch (Exception e) {
            QLog.w(TAG, "refreshDeviceCache 异常（可忽略）: " + e.getMessage());
        }
        try {
            g.close();
        } catch (Exception e) {
            QLog.w(TAG, "gatt.close 异常（可忽略）: " + e.getMessage());
        }
        OPEN_GATTS.remove(g);
        // 关闭后按协议开关决定是否解除系统配对（对齐旧 SDK safeRemoveBondDelayed）：配对过的
        // 从端（TXPGGP）重启后倾向用定向广播只发给已配对手机，普通扫描收不到。但需要系统配对
        // 的设备（加密特征/HID）必须保留配对——是否解绑由协议 removeBondOnDisconnect() 决定，
        // 默认 false（保留配对），TXPGGP 返回 true（解绑清残留）。
        if (removeBondOnDisconnect) {
            scheduleRemoveBond(g);
        }
        if (gatt == g) {
            gatt = null;
        }
        Runnable notify = closedNotify;
        closedNotify = null;
        if (notify != null) {
            notify.run();
        }
    }

    /** 解除系统配对（延迟 2s，避免与 close 过程抢资源；未配对时是 no-op）。removeBond 是 @hide API，反射调用 */
    @SuppressLint("MissingPermission")
    private void scheduleRemoveBond(BluetoothGatt g) {
        final BluetoothDevice device = g.getDevice();
        MAIN.postDelayed(() -> {
            try {
                if (device.getBondState() == BluetoothDevice.BOND_BONDED) {
                    java.lang.reflect.Method m = device.getClass().getMethod("removeBond");
                    boolean ok = (Boolean) m.invoke(device);
                    QLog.d(TAG, "连接已关闭，解除系统配对 address=" + address + " 结果=" + ok);
                }
            } catch (Exception e) {
                QLog.w(TAG, "removeBond 异常（可忽略）: " + e.getMessage());
            }
        }, 2000);
    }

    /**
     * 调系统的隐藏 refresh() 方法清空 GATT 服务缓存——重连同一设备时避免拿到过期的服务发现结果
     * （老 SDK forceCloseGatt 里的同款做法，反射失败静默跳过）。
     */
    private static boolean refreshDeviceCache(BluetoothGatt gatt) {
        try {
            java.lang.reflect.Method refresh = gatt.getClass().getMethod("refresh");
            if (refresh != null) {
                refresh.setAccessible(true);
                return (Boolean) refresh.invoke(gatt);
            }
        } catch (Exception ignored) {
            // 部分 ROM 没有该方法，静默跳过
        }
        return false;
    }

    @Override
    public void setNotificationListener(NotificationListener listener) {
        QLog.d(TAG, "setNotificationListener(" + (listener == null ? "null" : "非null") + ")");
        this.notificationListener = listener;
    }

    @Override
    public void setSubscriptionListener(NotificationListener listener) {
        QLog.d(TAG, "setSubscriptionListener(" + (listener == null ? "null" : "非null") + ")");
        this.subscriptionListener = listener;
    }

    private BluetoothGattCharacteristic findCharacteristic(String serviceUuid, String characteristicUuid) {
        if (gatt == null) {
            QLog.e(TAG, "findCharacteristic 失败：gatt 为 null");
            return null;
        }
        BluetoothGattService service = gatt.getService(toUuid(serviceUuid));
        if (service == null) {
            QLog.e(TAG, "找不到 service=" + serviceUuid + "（解析后 UUID=" + toUuid(serviceUuid) + "）。"
                    + "设备实际提供的 service 列表: " + dumpServices());
            return null;
        }
        BluetoothGattCharacteristic c = service.getCharacteristic(toUuid(characteristicUuid));
        if (c == null) {
            StringBuilder sb = new StringBuilder();
            for (BluetoothGattCharacteristic each : service.getCharacteristics()) {
                sb.append(each.getUuid()).append(" ");
            }
            QLog.e(TAG, "service=" + serviceUuid + " 下找不到 characteristic=" + characteristicUuid
                    + "。该 service 实际的 characteristic 列表: " + sb);
        }
        return c;
    }

    @SuppressLint("MissingPermission")
    private String dumpServices() {
        if (gatt == null || gatt.getServices() == null) return "(gatt未就绪)";
        StringBuilder sb = new StringBuilder();
        for (BluetoothGattService s : gatt.getServices()) {
            sb.append(s.getUuid()).append(" ");
        }
        return sb.toString();
    }

    private void complete(String key, boolean success, int code, String msg) {
        QLog.d(TAG, "complete(" + key + ") success=" + success + " code=" + code + " msg=" + msg);
        if ("connect".equals(key) && connectTimeout != null) {
            MAIN.removeCallbacks(connectTimeout); // 连接阶段已定论：撤销连接回调兜底超时
            connectTimeout = null;
        }
        GattCallback cb = pendingCallbacks.remove(key);
        if (cb == null) {
            QLog.w(TAG, "complete(" + key + ") 没有找到对应的 pendingCallback，"
                    + "可能是重复回调或者步骤已经超时被取消");
            return;
        }
        MAIN.post(() -> cb.onResult(success, code, msg));
    }

    /**
     * 单 GATT 操作互斥检查：上一个操作回调未到达时发起新操作，警告或（strict 模式）抛异常。
     * 见类注释"单 GATT 操作互斥"。
     */
    private void beginOperation(String key) {
        synchronized (pendingCallbacks) {
            if (!pendingCallbacks.isEmpty()) {
                String msg = "检测到并发 GATT 操作：[" + key + "] 在 " + pendingCallbacks.keySet()
                        + " 未完成时发起（Android 栈同一连接同时只支持一个未完成操作）";
                if (strictOperationAssertion) {
                    throw new IllegalStateException(msg);
                }
                QLog.w(TAG, msg);
            }
        }
    }

    /** 链路断开时失败所有未完成的操作回调，错误码统一为 CONNECTION_LOST（步骤据此终止配方） */
    private void failAllPendingCallbacks(int status) {
        String msg = "GATT 链路已断开 " + GattError.describe(status);
        for (String key : pendingCallbacks.keySet()) {
            GattCallback cb = pendingCallbacks.remove(key);
            if (cb != null) {
                QLog.w(TAG, "链路断开，失败未完成操作 [" + key + "] " + msg);
                MAIN.post(() -> cb.onResult(false, com.qingzhu.ble.sdk.core.common.ErrorCode.CONNECTION_LOST, msg));
            }
        }
    }
}
