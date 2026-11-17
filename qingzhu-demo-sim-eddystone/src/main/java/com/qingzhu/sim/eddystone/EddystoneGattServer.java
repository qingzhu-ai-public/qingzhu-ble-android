// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.sim.eddystone;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 模拟从机的 GATT Server（参考 lib-gateway-lcd 从端实现模式：CCCD 订阅跟踪 + 通知队列）。
 *
 * 服务布局（取 Eddystone GATT Configuration Service 的 UUID 命名空间，特征为模拟器简化定义）：
 * - service:  a3c87500-8ed3-4bdf-8a39-a01bebede295
 * - command:  a3c87502-...  （PROPERTY_WRITE | WRITE_NO_RESPONSE，App 写命令进来）
 * - status:   a3c87503-...  （PROPERTY_NOTIFY，从机 notify 应答出去），带 CCCD(2902)
 *
 * 应用层约定：请求 {"action":"xxx"} → 响应 {"action":"xxx","payload":{...}}（UTF-8 JSON）。
 */
public final class EddystoneGattServer {

    private static final String TAG = "EddystoneGattServer";

    public static final String SERVICE_UUID = "a3c87500-8ed3-4bdf-8a39-a01bebede295";
    public static final String CHARACTERISTIC_COMMAND_UUID = "a3c87502-8ed3-4bdf-8a39-a01bebede295";
    public static final String CHARACTERISTIC_STATUS_UUID = "a3c87503-8ed3-4bdf-8a39-a01bebede295";
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    public interface LogListener {
        void log(String line);
    }

    private final Context context;
    private final LogListener logListener;
    private final Handler main = new Handler(Looper.getMainLooper());

    private BluetoothGattServer gattServer;
    private BluetoothGattCharacteristic commandCharacteristic;
    private BluetoothGattCharacteristic statusCharacteristic;

    private final Set<String> notificationEnabledDevices = new HashSet<>();
    private final ConcurrentLinkedQueue<NotifyData> notifyQueue = new ConcurrentLinkedQueue<>();
    private boolean sendingNotify = false;

    private static final class NotifyData {
        final BluetoothDevice device;
        final byte[] data;

        NotifyData(BluetoothDevice device, byte[] data) {
            this.device = device;
            this.data = data;
        }
    }

    public EddystoneGattServer(Context context, LogListener logListener) {
        this.context = context.getApplicationContext();
        this.logListener = logListener;
    }

    private void log(String line) {
        Log.d(TAG, line);
        if (logListener != null) {
            main.post(() -> logListener.log(line));
        }
    }

    @SuppressLint("MissingPermission")
    public boolean start() {
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        if (manager == null || manager.getAdapter() == null) {
            log("蓝牙不可用");
            return false;
        }
        gattServer = manager.openGattServer(context, callback);
        if (gattServer == null) {
            log("openGattServer 失败");
            return false;
        }

        BluetoothGattService service = new BluetoothGattService(
                UUID.fromString(SERVICE_UUID), BluetoothGattService.SERVICE_TYPE_PRIMARY);

        // 命令特征：App 写入（WRITE | WRITE_NO_RESPONSE 都支持，兼容不同主端写法）
        commandCharacteristic = new BluetoothGattCharacteristic(
                UUID.fromString(CHARACTERISTIC_COMMAND_UUID),
                BluetoothGattCharacteristic.PROPERTY_WRITE
                        | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE);

        // 状态特征：从机 notify 应答
        statusCharacteristic = new BluetoothGattCharacteristic(
                UUID.fromString(CHARACTERISTIC_STATUS_UUID),
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ);
        statusCharacteristic.addDescriptor(new BluetoothGattDescriptor(
                CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE));

        service.addCharacteristic(commandCharacteristic);
        service.addCharacteristic(statusCharacteristic);

        boolean ok = gattServer.addService(service);
        log(ok ? "GATT 服务添加成功: " + SERVICE_UUID : "GATT 服务添加失败");
        return ok;
    }

    @SuppressLint("MissingPermission")
    public void stop() {
        if (gattServer != null) {
            gattServer.close();
            gattServer = null;
        }
        notificationEnabledDevices.clear();
        notifyQueue.clear();
    }

    private final BluetoothGattServerCallback callback = new BluetoothGattServerCallback() {

        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                log("主设备已连接: " + device.getAddress());
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                log("主设备已断开: " + device.getAddress());
                notificationEnabledDevices.remove(device.getAddress());
                notifyQueue.clear();
                sendingNotify = false;
            }
        }

        @Override
        public void onMtuChanged(BluetoothDevice device, int mtu) {
            log("MTU 协商完成: " + mtu);
        }

        @Override
        public void onDescriptorWriteRequest(BluetoothDevice device, int requestId,
                                             BluetoothGattDescriptor descriptor, boolean preparedWrite,
                                             boolean responseNeeded, int offset, byte[] value) {
            if (CCCD_UUID.equals(descriptor.getUuid())) {
                boolean enabled = value != null
                        && Arrays.equals(value, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                if (enabled) {
                    notificationEnabledDevices.add(device.getAddress());
                } else {
                    notificationEnabledDevices.remove(device.getAddress());
                }
                log("设备 " + device.getAddress() + (enabled ? " 订阅了通知" : " 取消订阅通知"));
                descriptor.setValue(value);
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value);
            } else {
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null);
            }
        }

        @Override
        public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
                                                 BluetoothGattCharacteristic characteristic,
                                                 boolean preparedWrite, boolean responseNeeded,
                                                 int offset, byte[] value) {
            String command = value != null ? new String(value, StandardCharsets.UTF_8) : "";
            log("收到命令: " + command + "（来自 " + device.getAddress() + "）");

            // 先响应写请求，再异步处理命令并 notify 应答（与从端参考实现一致）
            if (responseNeeded) {
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value);
            }
            handleCommand(device, command);
        }

        @Override
        public void onNotificationSent(BluetoothDevice device, int status) {
            sendingNotify = false;
            processNotifyQueue();
        }
    };

    /** 处理一条命令：构造 {"action":"...","payload":{...}} 应答并 notify 出去 */
    private void handleCommand(BluetoothDevice device, String command) {
        String action;
        JSONObject data = new JSONObject();
        try {
            JSONObject root = new JSONObject(command);
            action = root.optString("action", "unknown");
            JSONObject reqData = root.optJSONObject("data");
            if (reqData != null) {
                data = reqData; // 请求里带的字段原样回显，方便联调验证
            }
        } catch (Exception e) {
            action = "invalid";
        }

        JSONObject payload = new JSONObject();
        try {
            payload.put("action", action);
            payload.put("echo", data);
            payload.put("timestamp", System.currentTimeMillis());
            payload.put("message", "来自 Eddystone 模拟从机的应答");
        } catch (Exception e) {
            log("构造应答失败: " + e.getMessage());
            return;
        }

        JSONObject response = new JSONObject();
        try {
            response.put("action", action);
            response.put("payload", payload);
        } catch (Exception e) {
            return;
        }

        enqueueNotify(device, response.toString().getBytes(StandardCharsets.UTF_8));
    }

    @SuppressLint("MissingPermission")
    private void enqueueNotify(BluetoothDevice device, byte[] data) {
        if (!notificationEnabledDevices.contains(device.getAddress())) {
            log("设备未订阅通知，无法应答");
            return;
        }
        notifyQueue.offer(new NotifyData(device, data));
        if (!sendingNotify) {
            processNotifyQueue();
        }
    }

    @SuppressLint("MissingPermission")
    private void processNotifyQueue() {
        if (sendingNotify) {
            return;
        }
        NotifyData next = notifyQueue.poll();
        if (next == null) {
            return;
        }
        sendingNotify = true;
        statusCharacteristic.setValue(next.data);
        boolean ok = gattServer.notifyCharacteristicChanged(next.device, statusCharacteristic, false);
        if (!ok) {
            // notifyCharacteristicChanged 返回 false（设备未订阅等），丢弃本条并继续处理队列
            Log.w(TAG, "notify 发送失败");
            sendingNotify = false;
            processNotifyQueue();
        }
        // 成功的情况等待 onNotificationSent 回调后继续
    }
}
