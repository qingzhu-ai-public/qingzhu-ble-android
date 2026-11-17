// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.sdk.core.ble;

import android.bluetooth.le.ScanFilter;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.qingzhu.ble.adaptive.AdaptiveConcurrency;
import com.qingzhu.ble.sdk.core.cache.DeviceInfoCache;
import com.qingzhu.ble.sdk.core.action.ActionHandler;
import com.qingzhu.ble.sdk.core.action.ActionRouter;
import com.qingzhu.ble.sdk.core.action.JsonResponse;
import com.qingzhu.ble.sdk.core.connect.ConnectParams;
import com.qingzhu.ble.sdk.core.connect.GattClient;
import com.qingzhu.ble.sdk.core.connect.GattClientFactory;
import com.qingzhu.ble.sdk.core.connect.HeartbeatManager;
import com.qingzhu.ble.sdk.core.connect.SessionKeys;
import com.qingzhu.ble.sdk.core.device.QDevice;
import com.qingzhu.ble.sdk.core.platform.AndroidBleScanner;
import com.qingzhu.ble.sdk.core.platform.AndroidGattClient;
import com.qingzhu.ble.sdk.core.pool.ConnectionPool;
import com.qingzhu.ble.sdk.core.pool.PoolConnectListener;
import com.qingzhu.ble.sdk.core.pool.ReconnectPolicy;
import com.qingzhu.ble.sdk.core.protocol.ProtocolPlugin;
import com.qingzhu.ble.sdk.core.protocol.ProtocolRegistry;
import com.qingzhu.ble.sdk.core.scan.ScanConfig;
import com.qingzhu.ble.sdk.core.scan.ScanEngine;
import com.qingzhu.ble.sdk.core.scan.ScanListener;
import com.qingzhu.ble.sdk.core.scan.ScanResultSink;
import com.qingzhu.ble.sdk.core.session.Session;
import com.qingzhu.ble.sdk.core.settings.BleSettings;
import com.qingzhu.ble.sdk.core.session.SessionRegistry;
import com.qingzhu.ble.sdk.core.task.TaskScheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * BleAdapter 的 Android 实现：把 platform 包里的底层引擎（ConnectionPool / ScanEngine /
 * TaskScheduler / HeartbeatManager / AndroidGattClient / AndroidBleScanner）包成平台无关的
 * {@link BleAdapter}。任务内核只认 BleAdapter，不碰这些 Android 类。
 *
 * <p>职责只是「组装 + 接线」：引擎的踩坑经验（建连串行、扫描限流、孤儿 GATT 回收、心跳）都在
 * platform 包里原样保留，这里只负责把它们按 BleAdapter 的形状组织起来。
 */
public final class AndroidBleAdapter implements BleAdapter {

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ConnectionPool connectionPool;
    private final TaskScheduler taskScheduler = new TaskScheduler();

    private volatile AndroidBleScanner platformScanner;
    private volatile ScanEngine scanEngine;

    public AndroidBleAdapter(Context context) {
        this.context = context.getApplicationContext();
        // 设备恒定字段本地缓存：SDK 内部私有存储（filesDir），供扫描冷启动补全，跨平台无感
        DeviceInfoCache.init(this.context);
        this.connectionPool = ConnectionPool.defaultPool();

        // 心跳探活接线：判离线 → 连接池触发「意外断开 + 自动重连」
        HeartbeatManager.get().setOfflineListener(
                (mac, stableId) -> connectionPool.notifyHeartbeatOffline(stableId, mac));
        // 心跳发送：复用 send 链路（走按 mac 串行队列，与业务指令不并发）
        HeartbeatManager.get().setSender((mac, message, cb) -> {
            Session session = SessionRegistry.get(mac);
            if (session == null) {
                cb.onReply(false);
                return;
            }
            String protocolId = session.getProtocolType().getId();
            taskScheduler.submit(session, onDone -> ActionRouter.execute(mac, protocolId, message,
                    null, new ActionHandler.ActionCallback() {
                        @Override
                        public void onResult(JsonResponse response) {
                            cb.onReply(response.isSuccess());
                            onDone.run();
                        }
                    }));
        });

        // 注入 core 内置的 Android GATT 实现（可被测试替身覆盖）
        GattClientFactory.setDefaultProviderIfAbsent(
                address -> new AndroidGattClient(this.context, address));

        // 内建连接自适应：硬件体检 + 恢复历史经验，自动写进并发容量
        AdaptiveConcurrency.get().init(this.context);
        connectionPool.setConcurrencyCapacity(AdaptiveConcurrency.get().recommend());
    }

    // ============ 扫描 ============

    @Override
    public void startScan(ScanSink sink) {
        main.post(() -> {
            if (scanEngine != null) {
                return; // 已在扫描
            }
            // 扫描超时：ble.scanTimeoutSec（>0 = 定时扫描，到时自动停；0 = 持续扫描）
            int timeoutSec = BleSettings.get().getInt("ble.scanTimeoutSec");
            long timeoutMs = timeoutSec > 0 ? timeoutSec * 1000L : 0L;
            ScanEngine engine = new ScanEngine(new ScanListener() {
                @Override
                public void onScanResults(java.util.List<QDevice> devices) {
                    sink.onDevices(devices);
                }

                @Override
                public void onScanFailed(int errorCode, String message) {
                    sink.onScanFailed(errorCode, message);
                }

                @Override
                public void onScanStopped(java.util.List<QDevice> devices) {
                    sink.onScanStopped(devices);
                }
            }, ScanConfig.builder().setTimeout(timeoutMs).build(), self -> main.post(() -> {
                if (scanEngine == self) {
                    scanEngine = null;
                    if (platformScanner != null) {
                        platformScanner.stop();
                    }
                }
            }));
            scanEngine = engine;
            engine.start();

            if (platformScanner == null) {
                platformScanner = new AndroidBleScanner(context);
            }
            platformScanner.setResultSink(new ScanResultSink() {
                @Override
                public void onScanResult(com.qingzhu.ble.sdk.core.scan.ScanRecord record, String deviceName) {
                    ScanEngine e = scanEngine;
                    if (e != null) {
                        e.onScanResult(record, deviceName);
                    }
                }

                @Override
                public void onScanFailed(int errorCode, String message) {
                    ScanEngine e = scanEngine;
                    if (e != null) {
                        e.onPlatformScanFailed(errorCode, message);
                    }
                }
            });
            // 收集所有已注册协议声明的扫描配置 + 硬件过滤厂商 ID，让每个协议有自己的扫描规则：
            // 协议只声明「系统扫描参数」(scanConfig) 和「厂商 ID」(scanManufacturerIds)，core 负责
            // 合并配置 + 构建原生 ScanFilter 下沉到芯片 offload（对齐旧架构的厂商 ID 过滤，扫得快）。
            // 关键：只有【所有协议都声明了厂商 ID】才下沉硬件过滤——存在靠名称/内容识别的协议
            // （如 无厂商数据协议，广播没有厂商数据段）时，厂商 filter 会把它们的广播在芯片层直接丢弃，
            // 必须退回全量广播进软件识别。
            ScanConfig protocolConfig = null;
            List<ScanFilter> hardwareFilters = null;
            boolean allProtocolsDeclareIds = true;
            List<ScanFilter> collected = new ArrayList<>();
            for (ProtocolPlugin plugin : ProtocolRegistry.all()) {
                try {
                    ScanConfig pc = plugin.scanConfig();
                    if (pc != null) {
                        protocolConfig = ScanConfig.merge(protocolConfig, pc);
                    }
                } catch (Exception ignored) {
                }
                int[] ids = plugin.scanManufacturerIds();
                if (ids == null || ids.length == 0) {
                    allProtocolsDeclareIds = false;
                    continue;
                }
                for (int id : ids) {
                    collected.add(new ScanFilter.Builder()
                            .setManufacturerData(id, new byte[]{}).build());
                }
            }
            if (allProtocolsDeclareIds && !collected.isEmpty()) {
                hardwareFilters = collected;
            }
            ScanConfig effective = ScanConfig.merge(protocolConfig,
                    ScanConfig.builder().setTimeout(0).build());
            platformScanner.start(effective, null, hardwareFilters);
        });
    }

    @Override
    public void stopScan() {
        main.post(() -> {
            ScanEngine engine = scanEngine;
            scanEngine = null;
            if (engine != null) {
                engine.stop();
            }
            if (platformScanner != null) {
                platformScanner.stop();
            }
        });
    }

    @Override
    public void restartScanner() {
        main.post(() -> {
            if (platformScanner != null) {
                // 只重新注册系统扫描器（stopScan + startScan），不动 scanEngine——设备快照保留，不闪空列表
                platformScanner.restart("manual-restart");
            }
        });
    }

    @Override
    public void clearScanCache() {
        ScanEngine engine = scanEngine;
        if (engine != null) {
            engine.clear();
        }
    }

    // ============ 连接 ============

    @Override
    public void connect(QDevice device, ConnectParams params, ReconnectPolicy reconnectPolicy, ConnectSink sink) {
        connectionPool.requestConnect(device, new PoolConnectListener() {
            @Override
            public void onConnected(Session session) {
                sink.onConnected(session.getMac());
            }

            @Override
            public void onFailed(String mac, int errorCode, String message) {
                sink.onFailed(mac, errorCode, message);
            }
        }, reconnectPolicy != null ? reconnectPolicy : ReconnectPolicy.OFF, null, params, 2, true);
    }

    @Override
    public void disconnect(String mac) {
        connectionPool.release(mac);
    }

    // ============ 发数据 ============

    @Override
    public void send(String mac, String json, Map<String, Object> attachments, SendSink sink) {
        Session session = SessionRegistry.get(mac);
        if (session == null) {
            sink.onFailed("设备未连接: " + mac);
            return;
        }
        String protocolId = session.getProtocolType().getId();

        // 按 mac 串行（TaskScheduler）：单设备收发不并发，防串包
        taskScheduler.submit(session, onDone -> ActionRouter.execute(mac, protocolId, json,
                attachments, new ActionHandler.ActionCallback() {
                    @Override
                    public void onResult(JsonResponse response) {
                        if (response.isSuccess()) {
                            sink.onDone(response.getDataJson());
                        } else {
                            sink.onFailed(response.getMessage());
                        }
                        onDone.run();
                    }

                    @Override
                    public void onProgress(int sentBytes, int totalBytes) {
                        sink.onProgress(sentBytes, totalBytes);
                    }
                }));
    }

    @Override
    public void abortSend(String mac) {
        taskScheduler.clear(mac);
    }

    /** 大数据发送前请求高优先级后的等待时长：让连接参数更新生效（requestConnectionPriority 无完成回调，
     *  只能固定延迟兜底）。实测连接参数更新需要 ~400ms（日志：请求后 interval 在 ~400ms 才更新），
     *  100ms 太短会导致刷图写到一半参数才更新、链路断（status=59），所以大数据用 500ms 兜底。 */
    private static final long LARGE_TRANSFER_PRIORITY_WAIT_MS = 500;

    @Override
    public void prepareForLargeTransfer(String mac, Runnable onReady) {
        Session session = SessionRegistry.get(mac);
        GattClient client = session != null ? session.get(SessionKeys.GATT_CLIENT) : null;
        if (client != null) {
            client.requestHighPriority();
        }
        // 等固定时长让连接参数更新生效（requestConnectionPriority 无完成回调，用固定延迟兜底）
        main.postDelayed(onReady, LARGE_TRANSFER_PRIORITY_WAIT_MS);
    }

    // ============ 能力 ============

    @Override
    public void setConcurrency(int n) {
        if (n <= 0) {
            connectionPool.unlockConcurrencyCapacity();
            connectionPool.setConcurrencyCapacity(AdaptiveConcurrency.get().recommend());
        } else {
            connectionPool.lockConcurrencyCapacity(n);
        }
    }

    @Override
    public int concurrency() {
        return connectionPool.getConcurrencyCapacity();
    }
}
