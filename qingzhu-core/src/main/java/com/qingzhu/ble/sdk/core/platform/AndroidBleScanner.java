// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.sdk.core.platform;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import com.qingzhu.ble.sdk.core.common.ErrorCode;
import com.qingzhu.ble.sdk.core.common.QLog;
import com.qingzhu.ble.sdk.core.scan.ScanConfig;
import com.qingzhu.ble.sdk.core.scan.ScanRecord;
import com.qingzhu.ble.sdk.core.scan.ScanResultSink;

import java.util.List;
/**
 * core 内置的 Android 扫描适配：驱动 BluetoothLeScanner 产生广播，
 * 喂给 QingzhuBle.onScanResult 进入协议无关的扫描引擎。
 *
 * 扫描质量保障（对标 Nordic scanner 库的能力）：
 * 1. SCAN_MODE_LOW_LATENCY 低延迟交付广播；
 * 2. onScanFailed 自愈：偶发失败自动重启，错误码映射到 ErrorCode 1304~1306，
 *    重试耗尽/不支持时上报 ScanListener.onScanFailed；
 * 3. 蓝牙状态监控：关闭时停扫描清资源，重新打开时自动恢复——包括
 *    "扫描中蓝牙被关"与"蓝牙没开就先 startScan"两种情况都不会静默死掉；
 * 4. 硬件过滤下沉：所有已注册协议都声明 scanFilter() 时构建原生 ScanFilter
 *    （芯片 offload，省电 + 8.1+ 灭屏可收包）；任一协议未声明则退回全量软件识别。
 *    机型适配与扫描参数由公司既有逻辑负责，本类不做任何机型相关处理。
 */
public final class AndroidBleScanner {

    private static final String TAG = "AndroidBleScanner";
    private static final long RETRY_DELAY_MS = 1000;
    /** 系统限流（TOO_FREQUENTLY）时的加长退避 */
    private static final long RATE_LIMIT_RETRY_DELAY_MS = 5000;
    private static final int MAX_RETRY = 5;
    /** 系统扫描限流：Android 7+ 30s 内 startScan 超过 5 次会被静默拒绝（多数 ROM 不回调 onScanFailed） */
    private static final long START_WINDOW_MS = 30_000;
    /** 窗口内最多允许的 startScan 次数（留 1 次余量） */
    private static final int MAX_STARTS_IN_WINDOW = 4;

    // 系统回调错误码（android.bluetooth.le.ScanCallback 未在当前 compileSdk 暴露常量，按 AOSP 定义本地化）
    private static final int SYS_ERR_ALREADY_STARTED = 1;
    private static final int SYS_ERR_REGISTRATION_FAILED = 2;
    private static final int SYS_ERR_INTERNAL_ERROR = 3;
    private static final int SYS_ERR_FEATURE_UNSUPPORTED = 4;
    private static final int SYS_ERR_OUT_OF_HARDWARE_RESOURCES = 5;
    private static final int SYS_ERR_SCANNING_TOO_FREQUENTLY = 6;

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private BluetoothLeScanner scanner;
    private int retryCount = 0;
    /** 当前生效的扫描配置：蓝牙关闭自动恢复时要用同一份配置续扫 */
    private volatile ScanConfig config;
    /** 硬件扫描过滤（裸系统 ScanFilter 列表，由门面传入；null = 不过滤，全量广播进软件识别） */
    private volatile List<ScanFilter> hardwareFilters;
    /** 显式传入的系统扫描设置（裸 ScanSettings，由门面直接构造；null = 用 config 字段 + 内置默认） */
    private volatile ScanSettings explicitSettings;
    /** 是否处于"希望扫描"状态（start 与 stop 之间为 true，含蓝牙关闭期间） */
    private boolean scanning = false;
    private boolean receiverRegistered = false;
    /** 最近的 startScan 时间戳（限流窗口判断用，仅主线程访问） */
    private final java.util.ArrayDeque<Long> recentStarts = new java.util.ArrayDeque<>();
    /** 限流延迟中的重启任务（单 pending） */
    private Runnable pendingRestartTask;
    /** 已收到过广播的地址（诊断日志去重） */
    private final java.util.Set<String> seenAddresses = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** 扫描结果/失败的回调（由上层注入，替代绕道门面单例） */
    private volatile ScanResultSink resultSink;

    // ---- 扫描看门狗：息屏/灭屏后系统扫描「僵尸」的自愈 ----
    // 现象：部分 ROM（尤其三星）息屏再亮屏后，扫描器「注册成功但不再投递广播」（且不回调
    // onScanFailed），表现为「扫描运行中但累计广播=0」。此时只能 stopScan + startScan 重新注册恢复。
    // 策略：运行中持续零广播超过阈值 → 自动重启扫描；连续触发则阈值翻倍（退避），收到任意广播即重置；
    // 重启复用 startInternal，受限流保护（30s/4 次），不会打爆系统限流。
    private static final long WATCHDOG_IDLE_MS_INIT = 30_000;       // 首次 30s 零广播触发
    private static final long WATCHDOG_IDLE_MS_MAX = 5 * 60_000;    // 退避封顶 5min
    private static final long WATCHDOG_TICK_MS = 10_000;            // 检查周期
    private volatile long lastAdvertAt = 0;                         // 最近一次系统投递广播时间（0=从未）
    private long watchdogIdleMs = WATCHDOG_IDLE_MS_INIT;            // 当前触发阈值（退避用）
    private boolean watchdogScheduled = false;
    /** 看门狗 Runnable（固定引用，保证 postDelayed/removeCallbacks 用同一对象） */
    private final Runnable watchdogTask = this::checkWatchdog;

    // ---- 周期性扫描刷新：预防系统扫描「隐形降级」 ----
    // 系统扫描偶尔会进入「没死但变差」的状态（偶发丢广播、扫描变慢），不回调 onScanFailed、
    // 也不完全零广播，看门狗检测不到。每隔 N 分钟强制 stopScan + startScan 重新注册一次，
    // 只重置系统扫描器、不清理上层设备快照/缓存，客户端数据不受影响。
    private static final long PERIODIC_REFRESH_MS = 5 * 60_000;
    private final Runnable periodicRefreshTask = this::periodicRefresh;
    private boolean periodicRefreshScheduled = false;

    /** stopScan 后重新 startScan 的最小间隔：stopScan 是异步落地，立即 startScan 会撞上
     *  「旧扫描未完全停止」的竞态，表现为 ALREADY_STARTED 或「注册成功但永不投递」。
     *  经验值 3s 稳妥（个别 ROM 需要更久，可据此调整）。 */
    private static final long STOP_START_GAP_MS = 3_000;
    /** 最近一次 stopScan 时间戳（stop→start 间隔保护用，elapsedRealtime） */
    private long lastStopAt = 0;

    private final BroadcastReceiver adapterStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
            if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) {
                QLog.w(TAG, "蓝牙关闭，暂停扫描（恢复蓝牙后自动重启）");
                // 只停系统扫描器，保留"希望扫描"状态；蓝牙恢复时无缝续扫
                stopInternal();
            } else if (state == BluetoothAdapter.STATE_ON) {
                if (scanning) {
                    QLog.d(TAG, "蓝牙已开启，自动恢复扫描");
                    retryCount = 0;
                    startInternal();
                }
            }
        }
    };

    /** 屏幕状态监听：息屏期间系统扫描可能「僵尸」，亮屏时主动自检/重启（比看门狗 30s 更快） */
    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            if (!Intent.ACTION_SCREEN_ON.equals(intent.getAction()) || !scanning) {
                return;
            }
            long idle = android.os.SystemClock.elapsedRealtime() - lastAdvertAt;
            // 亮屏且已持续零广播超过首次阈值（30s）→ 扫描大概率僵尸，主动重启恢复。
            // 不无脑重启：扫描还活着（有广播）时不动它，避免频繁亮屏触发无谓的 stopScan+startScan。
            if (idle >= WATCHDOG_IDLE_MS_INIT) {
                QLog.d(TAG, "屏幕亮起且已零广播 " + idle + "ms，主动重启扫描");
                restart("screen-on");
            }
        }
    };
    private boolean screenReceiverRegistered = false;

    private final ScanCallback callback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            handle(result);
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            // setReportDelayMs > 0 时系统攒批回调——逐条喂引擎，上层契约不变
            for (ScanResult result : results) {
                handle(result);
            }
        }

        @SuppressLint("MissingPermission")
        private void handle(ScanResult result) {
            // 系统投递广播 = 扫描还活着：喂狗（重置零广播计时 + 退避阈值）
            lastAdvertAt = android.os.SystemClock.elapsedRealtime();
            watchdogIdleMs = WATCHDOG_IDLE_MS_INIT;
            byte[] raw = result.getScanRecord() != null ? result.getScanRecord().getBytes() : null;
            if (raw == null) return;
            // 【排查埋点】每个地址只打印首次（按地址去重防刷屏）：设备重新上电后是否收到它的新地址广播
            String addr = result.getDevice().getAddress();
            if (seenAddresses.add(addr)) {
//                QLog.d(TAG, "收到新地址广播 mac=" + addr + " rssi=" + result.getRssi());
            }
            ScanRecord record = new ScanRecord(
                    result.getDevice().getAddress(),
                    result.getRssi(),
                    raw,
                    null // 厂商/服务数据由 ScanRecord 按需提取并缓存
            );
            String name = result.getDevice().getName();
            ScanResultSink sink = resultSink;
            if (sink != null) {
                sink.onScanResult(record, name);
            }
        }

        @Override
        public void onScanFailed(int sysErrorCode) {
            long delay = sysErrorCode == SYS_ERR_SCANNING_TOO_FREQUENTLY
                    ? RATE_LIMIT_RETRY_DELAY_MS : RETRY_DELAY_MS;
            if (sysErrorCode == SYS_ERR_ALREADY_STARTED) {
                // stopScan 未落地就 startScan 的竞态：重置后重开即可，不算错误
                QLog.w(TAG, "系统返回 ALREADY_STARTED，重置后重开");
                main.postDelayed(() -> {
                    stopInternal();
                    startInternal();
                }, 200);
                return;
            }
            if (sysErrorCode == SYS_ERR_FEATURE_UNSUPPORTED) {
                notifyScanFailure(ErrorCode.SCAN_FAILED_FEATURE_UNSUPPORTED,
                        "设备不支持所需扫描特性（系统 errorCode=4）");
                return;
            }
            // 2=REGISTRATION_FAILED 3=INTERNAL_ERROR 5=OUT_OF_HARDWARE_RESOURCES 6=TOO_FREQUENTLY
            if (retryCount < MAX_RETRY) {
                retryCount++;
                QLog.e(TAG, "扫描失败 sysCode=" + sysErrorCode + "，"
                        + delay + "ms 后第 " + retryCount + "/" + MAX_RETRY + " 次自动重启");
                main.postDelayed(() -> {
                    stopInternal();
                    startInternal();
                }, delay);
                return;
            }
            int code = sysErrorCode == SYS_ERR_SCANNING_TOO_FREQUENTLY
                    ? ErrorCode.SCAN_FAILED_TOO_FREQUENTLY : ErrorCode.SCAN_FAILED_INTERNAL;
            notifyScanFailure(code, "系统扫描失败且重试 " + MAX_RETRY + " 次仍失败（sysCode=" + sysErrorCode + "）");
        }
    };

    public AndroidBleScanner(Context context) {
        this.context = context.getApplicationContext();
    }

    /** 注入扫描结果/失败的回调（由上层 ScanEngine 注入） */
    public void setResultSink(ScanResultSink sink) {
        this.resultSink = sink;
    }

    /** 开始扫描（重置失败重试计数；蓝牙未开时保持待命，开启后自动续扫）。配置为 null 走默认 */
    public void start() {
        start(ScanConfig.builder().build());
    }

    /** 开始扫描（带配置：系统扫描参数按"config 显式 > 内置默认"生效；蓝牙恢复时用同一份配置续扫） */
    public void start(ScanConfig config) {
        start(config, null, null);
    }

    /**
     * 开始扫描（带配置 + 硬件过滤）。hardwareFilters 是裸系统 ScanFilter 列表（公司 ID /
     * Service UUID 等 offload 条件），由门面直接构造传入——core 不翻译、不封装，系统加什么
     * filter 字段门面直接写。传 null = 不过滤（全量广播进软件识别）。蓝牙恢复时沿用同一份配置。
     */
    public void start(ScanConfig config, List<ScanFilter> hardwareFilters) {
        start(config, null, hardwareFilters);
    }

    /**
     * 开始扫描（带配置 + 裸系统扫描设置 + 硬件过滤）。settings 是门面直接构造的裸 ScanSettings——
     * 完全自定义系统扫描参数（扫描模式/上报延迟/PHY/匹配模式等，含系统未来新增的任意字段），
     * core 不翻译、不预设；传 null 则回落用 config 字段 + 内置默认。蓝牙恢复时沿用同一份设置。
     */
    public void start(ScanConfig config, ScanSettings settings, List<ScanFilter> hardwareFilters) {
        this.config = config != null ? config : ScanConfig.builder().build();
        this.explicitSettings = settings;
        this.hardwareFilters = hardwareFilters;
        retryCount = 0;
        scanning = true;
        registerAdapterStateReceiver();
        registerScreenReceiver();
        startInternal();
    }

    @SuppressLint("MissingPermission")
    private void startInternal() {
        if (!scanning) return;
        // stop→start 间隔保护：距上次 stopScan 不足 STOP_START_GAP_MS 时，延迟到间隔满再启动。
        // 覆盖所有路径（手动重扫 / 看门狗 / 周期刷新 / onScanFailed 自愈 / 蓝牙恢复），避免
        // stopScan 异步落地未完成就 startScan，撞上「旧扫描未停」的竞态（ALREADY_STARTED / 注册成功但永不投递）。
        long sinceStop = android.os.SystemClock.elapsedRealtime() - lastStopAt;
        if (sinceStop < STOP_START_GAP_MS) {
            long wait = STOP_START_GAP_MS - sinceStop;
            QLog.d(TAG, "距上次 stopScan " + sinceStop + "ms，延迟 " + wait + "ms 再启动（避开 stop→start 竞态）");
            main.postDelayed(this::startInternal, wait);
            return;
        }
        if (pendingRestartTask != null) {
            main.removeCallbacks(pendingRestartTask);
            pendingRestartTask = null;
        }
        // 限流保护：30s 内 startScan 次数逼近系统上限时推迟启动。超限的 startScan 在多数 ROM 上
        // 「注册成功但永不投递」且不回调 onScanFailed——这正是扫描静默死亡的主要诱因之一
        long now = android.os.SystemClock.elapsedRealtime();
        while (!recentStarts.isEmpty() && now - recentStarts.peekFirst() > START_WINDOW_MS) {
            recentStarts.pollFirst();
        }
        if (recentStarts.size() >= MAX_STARTS_IN_WINDOW) {
            long wait = START_WINDOW_MS - (now - recentStarts.peekFirst()) + 500;
            QLog.w(TAG, "30s 内 startScan 已 " + recentStarts.size() + " 次，为避开系统限流推迟 " + wait + "ms 启动");
            pendingRestartTask = () -> {
                pendingRestartTask = null;
                startInternal();
            };
            main.postDelayed(pendingRestartTask, wait);
            return;
        }
        BluetoothManager bm = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = bm != null ? bm.getAdapter() : null;
        if (adapter == null || !adapter.isEnabled()) {
            // 蓝牙未开：保持待命（receiver 在 STATE_ON 时会重新走到这里）
            QLog.w(TAG, "蓝牙未开启，扫描待命（开启后自动开始）");
            return;
        }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner != null) {
            ScanSettings settings = explicitSettings != null ? explicitSettings : buildSettings();
            List<ScanFilter> filters = resolveFilters(hardwareFilters);
            try {
                recentStarts.addLast(now);
                scanner.startScan(filters, settings, callback);
                QLog.d(TAG, "扫描已启动（settings=" + settings + ", nativeFilters="
                        + (filters != null ? filters.size() : 0) + "）");
                // 扫描已启动：给首次扫描一个宽限期，并调度看门狗 + 周期刷新
                lastAdvertAt = android.os.SystemClock.elapsedRealtime();
                watchdogIdleMs = WATCHDOG_IDLE_MS_INIT;
                scheduleWatchdog();
                schedulePeriodicRefresh();
            } catch (SecurityException e) {
                // 无 BLUETOOTH_SCAN 权限（Android 12+）：必须上报失败并清理状态，
                // 否则上层 scanning 标志误判为 true、scanEngine 残留，授权后 restartScan 无法真正重启
                QLog.e(TAG, "startScan 缺少权限: " + e.getMessage());
                scanner = null;
                notifyScanFailure(ErrorCode.SCAN_PERMISSION_MISSING, "缺少蓝牙扫描权限，请先授权");
            } catch (Exception e) {
                QLog.e(TAG, "startScan 异常: " + e.getMessage()
                        + "（常见原因：callbackType/numOfMatches 需要硬件 filter 配合，"
                        + "或设备不支持所选 PHY/扩展广播）");
                scanner = null;
            }
        }
        // 扫描重启用两类机制（都受限流保护，不会打爆系统 30s/5 次限制）：
        // 1. 明确事件：蓝牙关→开、onScanFailed、外部 restart()（如长连接重连耗尽）；
        // 2. 扫描看门狗：运行中持续零广播（息屏后系统扫描「僵尸」）→ 自动 stopScan + startScan 重新注册。
        //    带退避（连续触发阈值翻倍、收到广播重置），周围真没设备时也会退到低频，避免空转。
    }

    /**
     * 外部触发的扫描重启（如长连接设备重连耗尽后：设备马上可能重新开机，此时必须保证扫描是活的）。
     * 重新注册系统扫描；受限流保护，不会触发系统 30s/5 次限制。仅在"希望扫描"状态下生效。
     */
    public void restart(String reason) {
        main.post(() -> {
            if (!scanning) {
                return;
            }
            QLog.w(TAG, "外部请求重启扫描：" + reason);
            retryCount = 0;
            stopThenStart();
        });
    }

    /** 调度看门狗检查（单 pending，避免重复调度） */
    private void scheduleWatchdog() {
        if (watchdogScheduled || !scanning) {
            return;
        }
        watchdogScheduled = true;
        main.postDelayed(watchdogTask, WATCHDOG_TICK_MS);
    }

    /** 看门狗检查：运行中持续零广播超过阈值就自动重启扫描（息屏后「僵尸」自愈） */
    private void checkWatchdog() {
        watchdogScheduled = false;
        if (!scanning) {
            return;
        }
        long idle = android.os.SystemClock.elapsedRealtime() - lastAdvertAt;
        if (idle >= watchdogIdleMs) {
            QLog.w(TAG, "扫描看门狗：持续 " + idle + "ms 零广播，自动重启扫描（下次阈值 " + watchdogIdleMs + "ms）");
            watchdogIdleMs = Math.min(watchdogIdleMs * 2, WATCHDOG_IDLE_MS_MAX);
            stopThenStart(); // 内部 startInternal 会重新 scheduleWatchdog
            return;
        }
        scheduleWatchdog();
    }

    /** 调度周期性扫描刷新（单 pending） */
    private void schedulePeriodicRefresh() {
        if (periodicRefreshScheduled || !scanning) {
            return;
        }
        periodicRefreshScheduled = true;
        main.postDelayed(periodicRefreshTask, PERIODIC_REFRESH_MS);
    }

    /** 周期性强制刷新：重新注册系统扫描（保留上层设备快照/缓存），预防扫描隐形降级 */
    private void periodicRefresh() {
        periodicRefreshScheduled = false;
        if (!scanning) {
            return;
        }
        QLog.d(TAG, "周期性扫描刷新：重新注册系统扫描（保留设备快照）");
        stopThenStart(); // 内部 startInternal 会重新调度看门狗 + 周期刷新
    }

    /** 停 → 开：startInternal 内部会做 stop→start 间隔保护（距上次 stop 不足 3s 自动延迟） */
    private void stopThenStart() {
        stopInternal();
        startInternal();
    }

    /**
     * 按"config 显式 > 内置默认"构建系统 ScanSettings：
     * - 内置默认 = LOW_LATENCY（快速发现场景的最优占空比）；
     * - ScanConfig 显式设置的字段逐个覆盖（null = 不干预）；
     * - 逃生舱最后执行（可覆盖一切，含常用参数）。
     * 机型适配逻辑不属于 core——需要按机型调整默认值时，在公司侧的平台适配/接入层设置 config 默认。
     */
    @SuppressLint("WrongConstant")
    private ScanSettings buildSettings() {
        ScanSettings.Builder b = new ScanSettings.Builder();
        // core 内置默认 = 严格对齐旧架构 LollipopScanner：低延迟 + 逐条上报 + 扩展广播。
        // <b>不显式设 callbackType / matchMode / numOfMatches / phy</b>：前三者是「硬件 filter 专用」
        // 参数，配合 match-all 空 filter 会诱导部分 ROM 切入 offload 攒批路径，把广播攒起来延迟投递
        //（表现为「扫很久才出来」），用系统默认即可；phy 同理回默认 1M。协议若需调整，在 scanConfig()
        // 里显式声明，core 会按「协议 > core 默认」合并覆盖。
        b.setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY);
        b.setReportDelay(0);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b.setLegacy(false);
        }
        ScanConfig cfg = config;
        if (cfg != null) {
            if (cfg.getScanMode() != null) {
                b.setScanMode(cfg.getScanMode());
            }
            if (cfg.getReportDelayMs() != null) {
                b.setReportDelay(cfg.getReportDelayMs());
            }
            if (cfg.getCallbackType() != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                b.setCallbackType(cfg.getCallbackType());
            }
            if (cfg.getMatchMode() != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                b.setMatchMode(cfg.getMatchMode());
            }
            if (cfg.getNumOfMatches() != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                b.setNumOfMatches(cfg.getNumOfMatches());
            }
            if (cfg.getPhy() != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                b.setPhy(cfg.getPhy());
            }
            if (cfg.getLegacy() != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                b.setLegacy(cfg.getLegacy());
            }
            java.util.function.Consumer<Object> customizer = cfg.getPlatformSettingsCustomizer();
            if (customizer != null) {
                customizer.accept(b); // 逃生舱：实参为 ScanSettings.Builder，最后执行，可覆盖一切
            }
        }
        return b.build();
    }

    /**
     * 解析最终要传给系统的 ScanFilter 列表。
     * 门面没显式传 filter 时，8.1+（O_MR1）默认传一个 match-all 空 filter——
     * 解决"部分机型（三星等）灭屏后无 filter 的扫描拿不到结果"的问题
     *（参考 base 的 BaseLollipopScanner 构造时同款处理：P 用空 filter、O_MR1 用空 filter）。
     * 低版本无需，返回 null（不过滤）。
     */
    private static List<ScanFilter> resolveFilters(List<ScanFilter> explicit) {
        if (explicit != null) {
            return explicit;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            return java.util.Collections.singletonList(new ScanFilter.Builder().build());
        }
        return null;
    }

    @SuppressLint("MissingPermission")
    public void stop() {
        scanning = false;
        unregisterAdapterStateReceiver();
        unregisterScreenReceiver();
        main.removeCallbacksAndMessages(null);
        pendingRestartTask = null;
        stopInternal();
    }

    @SuppressLint("MissingPermission")
    private void stopInternal() {
        // 停止系统扫描的同时取消看门狗 + 周期刷新（两者都只在系统扫描活跃时才有意义）
        watchdogScheduled = false;
        main.removeCallbacks(watchdogTask);
        periodicRefreshScheduled = false;
        main.removeCallbacks(periodicRefreshTask);
        lastStopAt = android.os.SystemClock.elapsedRealtime();
        if (scanner != null) {
            try {
                scanner.stopScan(callback);
            } catch (Exception e) {
                QLog.w(TAG, "stopScan 异常（可忽略）: " + e.getMessage());
            }
            scanner = null;
        }
    }

    private void notifyScanFailure(int errorCode, String message) {
        QLog.e(TAG, "扫描失败最终上报 errorCode=" + errorCode + " " + message);
        scanning = false;
        stopInternal();
        unregisterAdapterStateReceiver();
        ScanResultSink sink = resultSink;
        if (sink != null) {
            sink.onScanFailed(errorCode, message);
        }
    }

    // ---------------- 蓝牙状态监控 ----------------

    private void registerAdapterStateReceiver() {
        if (receiverRegistered) return;
        IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) {
            // 系统受保护广播，NOT_EXPORTED 安全
            context.registerReceiver(adapterStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(adapterStateReceiver, filter);
        }
        receiverRegistered = true;
    }

    private void unregisterAdapterStateReceiver() {
        if (!receiverRegistered) return;
        try {
            context.unregisterReceiver(adapterStateReceiver);
        } catch (Exception ignored) {
        }
        receiverRegistered = false;
    }

    private void registerScreenReceiver() {
        if (screenReceiverRegistered) return;
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
        try {
            context.registerReceiver(screenReceiver, filter);
            screenReceiverRegistered = true;
        } catch (Exception e) {
            QLog.w(TAG, "注册屏幕状态监听失败（可忽略）: " + e.getMessage());
        }
    }

    private void unregisterScreenReceiver() {
        if (!screenReceiverRegistered) return;
        try {
            context.unregisterReceiver(screenReceiver);
        } catch (Exception ignored) {
        }
        screenReceiverRegistered = false;
    }

}
