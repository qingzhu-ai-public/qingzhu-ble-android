// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.sim.eddystone;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.os.Build;
import android.os.ParcelUuid;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Locale;

/**
 * Eddystone Beacon 广播器：构造并广播 Eddystone 帧。
 *
 * 布局（对齐 Eddystone 官方规范，规范见 github.com/google/eddystone）：
 * - 主广播包：Eddystone-UID 帧（Service Data 0xFEAA）——设备身份；
 * - 扫描响应包：Eddystone-TLM 帧——遥测数据（电压/温度/广播计数/开机秒数）。
 *
 * 注意：广告计数/秒数是模拟值（从启动时刻开始累计），温度/电压固定为模拟值。
 */
public final class EddystoneBeacon {

    private static final String TAG = "EddystoneBeacon";

    /** Eddystone 的 16 位服务 UUID（官方规范固定值） */
    public static final int EDDYSTONE_SERVICE_UUID = 0xFEAA;

    /** Namespace（10 字节）——模拟一台"出厂写死"的设备身份前缀 */
    private static final byte[] NAMESPACE = {
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A
    };

    private final BluetoothAdapter adapter;
    private final long startElapsedSeconds = System.currentTimeMillis() / 1000;

    private volatile long advCount = 0;

    public EddystoneBeacon(BluetoothAdapter adapter) {
        this.adapter = adapter;
    }

    /**
     * Instance（6 字节）：用设备指纹哈希生成稳定且因机而异的身份。
     *
     * <p>Android 6+ 起第三方 App 拿不到真实蓝牙 MAC，{@code BluetoothAdapter.getAddress()}
     * 固定返回 {@code 02:00:00:00:00:00}；若直接拿它当 Instance，所有模拟器的
     * Eddystone 身份都会一模一样，UID 帧也就失去了「设备身份」的意义。
     */
    private byte[] instanceBytes() {
        String seed = Build.MANUFACTURER + "/" + Build.MODEL + "/" + Build.FINGERPRINT;
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(seed.getBytes(StandardCharsets.UTF_8));
            return Arrays.copyOf(hash, 6);
        } catch (NoSuchAlgorithmException e) {
            return new byte[]{0x01, 0x02, 0x03, 0x04, 0x05, 0x06};
        }
    }

    // ---------------- 帧构造 ----------------

    /**
     * Eddystone-UID 帧（18 字节）：
     * [0]=0x00, [1]=TxPower(int8), [2..11]=Namespace, [12..17]=Instance
     */
    public byte[] buildUidFrame() {
        byte[] f = new byte[18];
        f[0] = 0x00;
        f[1] = (byte) -20; // TxPower = -20 dBm
        System.arraycopy(NAMESPACE, 0, f, 2, 10);
        System.arraycopy(instanceBytes(), 0, f, 12, 6);
        return f;
    }

    /**
     * Eddystone-TLM 帧（14 字节）：
     * [0]=0x20, [1]=Version(0), [2..3]=Voltage mV(BE), [4]=温度整数(sint8),
     * [5]=温度小数(/256), [6..9]=广播计数(BE), [10..13]=开机秒数(BE)
     */
    public byte[] buildTlmFrame() {
        advCount++;
        byte[] f = new byte[14];
        f[0] = 0x20;
        f[1] = 0x00;
        int voltage = 2950; // 模拟电池电压 mV
        f[2] = (byte) (voltage >> 8);
        f[3] = (byte) voltage;
        double temp = 26.5; // 模拟温度 °C
        f[4] = (byte) (int) temp;
        f[5] = (byte) ((temp - (int) temp) * 256);
        f[6] = (byte) (advCount >> 24);
        f[7] = (byte) (advCount >> 16);
        f[8] = (byte) (advCount >> 8);
        f[9] = (byte) (long) advCount;
        long uptime = System.currentTimeMillis() / 1000 - startElapsedSeconds;
        f[10] = (byte) (uptime >> 24);
        f[11] = (byte) (uptime >> 16);
        f[12] = (byte) (uptime >> 8);
        f[13] = (byte) uptime;
        return f;
    }

    /** 当前设备身份字符串（日志展示用）：namespace + instance */
    public String eddystoneId() {
        StringBuilder sb = new StringBuilder();
        for (byte b : NAMESPACE) sb.append(String.format(Locale.US, "%02X", b));
        for (byte b : instanceBytes()) sb.append(String.format(Locale.US, "%02X", b));
        return sb.toString();
    }

    // ---------------- 广播启停 ----------------

    private final AdvertiseCallback callback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            Log.d(TAG, "广播启动成功");
        }

        @Override
        public void onStartFailure(int errorCode) {
            Log.e(TAG, "广播启动失败 errorCode=" + errorCode);
        }
    };

    /** 启动广播：主包 = UID 帧，扫描响应 = TLM 帧 */
    @SuppressLint("MissingPermission")
    public void startAdvertising() {
        BluetoothLeAdvertiser advertiser = adapter != null ? adapter.getBluetoothLeAdvertiser() : null;
        if (advertiser == null) {
            Log.e(TAG, "本机不支持 BLE 广播（bluetoothLeAdvertiser 为 null）");
            return;
        }
        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(true) // 可连接：主端才能建立 GATT
                .setTimeout(0)
                .build();

        AdvertiseData advData = new AdvertiseData.Builder()
                .setIncludeDeviceName(false) // 不含名称，保证 UID 帧（21 字节 AD）不超限
                .setIncludeTxPowerLevel(false)
                .addServiceData(ParcelUuid.fromString("0000FEAA-0000-1000-8000-00805F9B34FB"),
                        buildUidFrame())
                .build();

        AdvertiseData scanResponse = new AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addServiceData(ParcelUuid.fromString("0000FEAA-0000-1000-8000-00805F9B34FB"),
                        buildTlmFrame())
                .build();

        advertiser.startAdvertising(settings, advData, scanResponse, callback);
    }

    @SuppressLint("MissingPermission")
    public void stopAdvertising() {
        BluetoothLeAdvertiser advertiser = adapter != null ? adapter.getBluetoothLeAdvertiser() : null;
        if (advertiser != null) {
            advertiser.stopAdvertising(callback);
        }
    }
}
