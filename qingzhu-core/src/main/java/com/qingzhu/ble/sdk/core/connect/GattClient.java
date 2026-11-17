// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.sdk.core.connect;

/**
 * 内置步骤（ConnectGatt/DiscoverServices/RequestMtu/EnableNotification）需要用到的
 * "真正跟 Android BluetoothGatt 打交道"的最小接口。
 *
 * 之所以单独抽出这一层，而不是让内置步骤直接 import android.bluetooth.BluetoothGatt：
 * 1. core 里的引擎/步骤代码要能脱离 Android 环境做单元测试；
 * 2. Session 持有一个 GattClient 实例（在 ConnectGattStep 执行时创建并 put 进 Session），
 *    后续步骤都从 Session 里取同一个实例操作，不允许各步骤各自持有连接对象的引用。
 *
 * 具体实现（真正封装 BluetoothGatt 的那个类）放在 core 的 android 适配层，不在这个文件里。
 *
 * <h3>单 GATT 操作契约</h3>
 * Android BLE 栈对同一连接同时只可靠支持一个未完成的 GATT 操作（写/读/MTU/描述符）。
 * 本接口的实现应当串行化操作，调用方也必须遵守：上一个操作的回调到达之前不要发起下一个。
 * 等待 notify 应答（如 AuthCmac）不算占用操作——写回调到达后操作即已完成。
 * AndroidGattClient 内置了并发操作检测（strict 模式下直接抛异常），见其类注释。
 */
public interface GattClient {

    void connect(GattCallback callback);

    /**
     * 发起连接（带连接参数）：{@link ConnectParams} 里的 autoConnect / transport / preferredPhy
     * 是"调用方声明的连接意图"，具体映射到哪个系统 API 参数由实现自行决定。
     *
     * <p>默认实现忽略参数、退化为 {@link #connect(GattCallback)}——不支持连接参数定制的实现
     *（如单测替身）无需改动。AndroidGattClient 会把它们映射到 {@code connectGatt} 的对应重载上。
     */
    default void connect(GattCallback callback, ConnectParams params) {
        connect(callback);
    }

    void discoverServices(GattCallback callback);

    void requestMtu(int size, GattCallback callback);

    /** 使能 notify：CCCD 写 0x01，设备按 notification 上报 */
    void enableNotification(String serviceUuid, String characteristicUuid, GattCallback callback);

    /** 使能 indicate：CCCD 写 0x02，只支持 indication 的特征值用它（notify 的 receive 会失败） */
    void enableIndication(String serviceUuid, String characteristicUuid, GattCallback callback);

    /** 取消订阅：CCCD 写 0x00，关闭 notify/indicate 上报（对两者都有效） */
    void disableNotification(String serviceUuid, String characteristicUuid, GattCallback callback);

    boolean hasCharacteristic(String serviceUuid, String characteristicUuid);

    void write(String serviceUuid, String characteristicUuid, byte[] data, GattCallback callback);

    /**
     * 指定写类型的写（大块传输提速用）：{@code writeType} 取值对齐 Android 系统常量——
     * {@code 1} = 无响应写（WRITE_TYPE_NO_RESPONSE：连续写不等确认，速度快，但空口无确认可能丢包，
     * 可靠性由应用层分片校验/回执兜底）、{@code 2} = 有响应写（默认）。其它值由实现回退有响应写。
     *
     * <p>默认实现退化到 {@link #write}（跟随全局 {@code ble.writeType} 配置）。
     * AndroidGattClient 会把本参数直接映射到 {@code BluetoothGattCharacteristic.setWriteType}。
     */
    default void write(String serviceUuid, String characteristicUuid, byte[] data, int writeType, GattCallback callback) {
        write(serviceUuid, characteristicUuid, data, callback);
    }

    /**
     * 无响应批量写（大块传输流水线提速）：把 {@code packets} 全部用<b>无响应写</b>连续下发，
     * 内部做滑动窗口流控（同时保持多个 in-flight 写，不等逐片 ACK，充分利用每个连接事件）。
     * 全部写完（或失败）回调一次 {@code callback}；空口无确认可能丢包，可靠性由调用方应用层
     * 分片校验/回执兜底。
     *
     * <p>默认实现：逐片退化为无响应 {@link #write(String, String, byte[], int, GattCallback)}
     * （无流水线，慢但可用）。AndroidGattClient 实现真正的流水线窗口。
     */
    default void writeBulk(String serviceUuid, String characteristicUuid,
                           java.util.List<byte[]> packets, GattCallback callback) {
        writeBulk(serviceUuid, characteristicUuid, packets, null, callback);
    }

    /**
     * 带进度回调的无响应批量写：每片写完后回调 {@code progress.onProgress(已完成片数, 总片数)}，
     * 让上层能显示传输进度——此前只在全部写完时回调一次，中途看不到进度。
     *
     * <p>默认实现：逐片退化为无响应写，逐片回调进度。
     * AndroidGattClient 实现真正的流水线窗口 + 逐片进度。
     */
    default void writeBulk(String serviceUuid, String characteristicUuid,
                           java.util.List<byte[]> packets, BulkProgressListener progress, GattCallback callback) {
        if (packets == null || packets.isEmpty()) {
            callback.onResult(true, 0, null);
            return;
        }
        final int total = packets.size();
        int[] idx = {0};
        GattCallback[] step = new GattCallback[1];
        step[0] = (ok, code, msg) -> {
            if (!ok) {
                callback.onResult(false, code, msg);
                return;
            }
            int sent = idx[0] + 1;
            if (progress != null) {
                progress.onProgress(sent, total);
            }
            if (sent >= total) {
                callback.onResult(true, 0, null);
                return;
            }
            idx[0] = sent;
            write(serviceUuid, characteristicUuid, packets.get(sent), 1, step[0]);
        };
        write(serviceUuid, characteristicUuid, packets.get(0), 1, step[0]);
    }

    /**
     * 订阅"某个特征值收到 notify"这个事件，替换掉早期靠一个共享缓存字段
     *（{@code lastAuthResponse}）瞎猜时序的做法——写入挑战帧和收到设备应答是两个独立的
     * 异步事件，中间有真实的时间差（哪怕只有几十毫秒），靠"写完立刻读缓存"永远会读到 null。
     *
     * 只允许同时存在一个监听器（override 语义）：认证这类"一问一答"场景用完即弃，
     * 下一次 setNotificationListener(null) 或者被下一次 execute 覆盖即可清理。
     */
    void setNotificationListener(NotificationListener listener);

    /**
     * 订阅「设备主动上报」的持久监听器，与 {@link #setNotificationListener} 的「一问一答应答监听器」
     * 相互独立——应答监听器被 send 覆盖/清空时，本订阅不受影响；notify 到达时两者都会收到。
     * 业务层据此区分「指令应答」与「主动上报」。传 null 注销。
     */
    default void setSubscriptionListener(NotificationListener listener) {
    }

    /**
     * 通用 BLE「直接读」：读某个特征值的当前值（不依赖 notify）。
     * 这是 GATT 的 read 操作，与「发指令等 notify 应答」是两条不同的交互路径。
     */
    void read(String serviceUuid, String characteristicUuid, ReadCallback callback);

    /**
     * 优雅断开：只发 disconnect 让系统走完整断开流程并通知从机，等底层 DISCONNECTED 回调后
     * 再 close；回调丢失时由实现内部兜底定时器强制关闭——无论走哪条路径，
     * {@code callback.onClosed()} 都保证被调用一次（表示底层连接已完全关闭，
     * 此后才可以对该设备发起新的 GATT 连接）。
     *
     * @param callback 关闭完成回调，可为 null（如步骤超时取消的路径，不关心关闭完成时机）
     */
    void disconnect(ClosedCallback callback);

    /** 便捷重载：不关心关闭完成时机的调用方（如步骤超时取消）使用 */
    default void disconnect() {
        disconnect(null);
    }

    /**
     * 注册"链路意外断开"监听（被动断开时触发一次；本实现的 disconnect() 主动断开不算）。
     * 主动 disconnect 与远程断开在系统回调里是同一个 DISCONNECTED 事件，实现内部用
     * "是否处于主动关闭流程"区分。连接池用它感知"设备悄悄掉了"，驱动自动重连。
     * 传 null 注销。
     */
    default void setUnexpectedCloseListener(Runnable listener) {
    }

    /**
     * 连接就绪后<b>延迟</b>请求高连接优先级（CONNECTION_PRIORITY_HIGH）。
     *
     * <p>为什么不在 {@code STATE_CONNECTED} 时立即请求：{@code requestConnectionPriority}
     * 会触发 HCI LE Connection Update（连接参数更新），更新窗口期内紧随其后的
     * discoverServices / MTU / 认证写帧可能失败（实测表现：连接成功 0.5~2s 后 write 报
     * 「无效句柄」、链路断开、设备换址后重连，往复循环）。默认实现为 no-op（单测替身无需实现）。
     */
    default void requestHighPriorityDelayed() {
    }

    /**
     * 请求 2M PHY（BLE 5.0 特性，吞吐翻倍）。默认实现 no-op（不支持 PHY 协商的实现无需处理）。
     * AndroidGattClient 会调用 {@code BluetoothGatt.setPreferredPhy} 协商 2M PHY；
     * 能力协商拿到从端支持 2M PHY 后由协议层调用。
     */
    default void request2MPhy() {
    }

    /**
     * 立即请求高连接优先级 + 2M PHY（大数据发送前调用，提升吞吐）。
     * 与 {@link #requestHighPriorityDelayed} 的区别：不延迟、不自动触发，由调用方在
     * 「确认要发大数据」时主动调用。默认 no-op。
     */
    default void requestHighPriority() {
    }

    interface ClosedCallback {
        void onClosed();
    }

    interface GattCallback {
        void onResult(boolean success, int errorCode, String message);
    }

    interface NotificationListener {
        void onNotify(String characteristicUuid, byte[] value);
    }

    interface ReadCallback {
        /** @param value 读取到的字节；失败时为 null */
        void onRead(byte[] value, String error);
    }

    /** 批量写进度监听：每片写完回调一次（{@code sent}=已完成片数，{@code total}=总片数） */
    interface BulkProgressListener {
        void onProgress(int sent, int total);
    }
}
