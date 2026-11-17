// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.sim.generic

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.os.ParcelUuid
import android.util.Log
import java.util.Locale

/**
 * 可编程广播器：把任意字节作为 0xFFE0 Service Data 广播出去。
 *
 * 传入的 ParcelUuid 是 Bluetooth Base UUID 派生的 16 位 UUID（0000FFE0-...），
 * Android 的 AdvertiseData.Builder 会把它编码成 **16-bit Service Data**
 * （AD type 0x16，UUID 小端两字节 E0 FF），而不是 128-bit（0x21）。
 * 下面的展示串与分段说明都按实际编码生成，避免界面与真实字节对不上。
 *
 * 可选：把 6 字节稳定 deviceMac 作为 Manufacturer Data 广播，Company ID 默认用
 * SIG 测试段 0xFFFF，不冒充任何已注册公司 ID。主端只关心 Company ID 后的
 * ≥6 字节，据此提取稳定身份 deviceMac。
 */
class GenericBeacon(private val adapter: BluetoothAdapter?) {

    companion object {
        private const val TAG = "GenericBeacon"

        /** 广播承载的 16 位 Service UUID（0xFFE0，测试用，避免与常见设备冲突） */
        const val SERVICE_UUID_16 = 0xFFE0
        private const val SERVICE_UUID_128 = "0000FFE0-0000-1000-8000-00805F9B34FB"

        /** AD Type: Flags */
        private const val AD_TYPE_FLAGS = 0x01
        /** AD Type: Service Data - 16-bit UUID */
        private const val AD_TYPE_SERVICE_DATA_16 = 0x16
        /** AD Type: Manufacturer Specific Data */
        private const val AD_TYPE_MANUFACTURER_DATA = 0xFF
        /** 实测系统自动追加的 Flags：LE General Discoverable */
        private const val FLAGS_LE_GENERAL_DISCOVERABLE = 0x02
    }

    private val callback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.d(TAG, "广播启动成功")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "广播启动失败 errorCode=$errorCode")
        }
    }

    /**
     * 开始广播。
     *
     * @param payload      Service Data 字节（作为 0xFFE0 Service Data 广播，可空）。
     * @param mfgCompanyId Manufacturer Data 的 Company Identifier（小端 2 字节）；
     *                     传 null 表示不携带 Manufacturer Data。
     * @param deviceMac    6 字节 deviceMac；与 mfgCompanyId 同时非空时一起作为 Manufacturer Data 广播。
     */
    @SuppressLint("MissingPermission")
    fun startAdvertising(payload: ByteArray, mfgCompanyId: Int?, deviceMac: ByteArray?) {
        val advertiser = adapter?.bluetoothLeAdvertiser
        if (advertiser == null) {
            Log.e(TAG, "本机不支持 BLE 广播")
            return
        }
        // 重启前先停掉旧的，避免叠加
        stopAdvertising()

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        val dataBuilder = AdvertiseData.Builder()
            .addServiceData(ParcelUuid.fromString(SERVICE_UUID_128), payload)
        if (mfgCompanyId != null && deviceMac != null && deviceMac.size >= 6) {
            dataBuilder.addManufacturerData(mfgCompanyId, deviceMac)
        }
        val data = dataBuilder.build()
        advertiser.startAdvertising(settings, data, callback)
    }

    @SuppressLint("MissingPermission")
    fun stopAdvertising() {
        adapter?.bluetoothLeAdvertiser?.stopAdvertising(callback)
    }

    /**
     * 构造"实际广播出去"的完整 AD 结构 hex 字符串（用于日志 / 界面展示）。
     *
     * 顺序与实测 raw 一致：Flags（系统追加）→ Manufacturer Data（可选）→ Service Data。
     * 返回格式：空格分隔的大写 hex。
     */
    fun buildAdvPacketHex(payload: ByteArray, mfgCompanyId: Int?, deviceMac: ByteArray?): String {
        val sb = StringBuilder()

        // AD[0] Flags —— 系统自动追加
        sb.append(String.format(Locale.US, "%02X %02X %02X ",
            2, AD_TYPE_FLAGS, FLAGS_LE_GENERAL_DISCOVERABLE))

        // AD[1] Manufacturer Specific Data（可选）
        if (mfgCompanyId != null && deviceMac != null && deviceMac.size >= 6) {
            sb.append(String.format(Locale.US, "%02X %02X ",
                1 + 2 + deviceMac.size, AD_TYPE_MANUFACTURER_DATA))
            // Company ID 小端字节序
            sb.append(String.format(Locale.US, "%02X %02X ",
                mfgCompanyId and 0xFF, (mfgCompanyId ushr 8) and 0xFF))
            for (b in deviceMac) sb.append(String.format(Locale.US, "%02X ", b))
        }

        // AD[2] Service Data - 16-bit UUID（0xFFE0 小端 E0 FF）
        sb.append(String.format(Locale.US, "%02X %02X ",
            1 + 2 + payload.size, AD_TYPE_SERVICE_DATA_16))
        sb.append(String.format(Locale.US, "%02X %02X ",
            SERVICE_UUID_16 and 0xFF, (SERVICE_UUID_16 ushr 8) and 0xFF))
        for (b in payload) sb.append(String.format(Locale.US, "%02X ", b))

        return sb.toString().trimEnd()
    }

    /**
     * 构造可读的分段解释文本（用于界面"每段含义说明"区域）。
     * 分段、顺序、长度与 [buildAdvPacketHex] 保持一致。
     */
    fun explainAdvPacket(payload: ByteArray, mfgCompanyId: Int?, deviceMac: ByteArray?): String {
        val sb = StringBuilder()
        val payloadHex = payload.joinToString(" ") { String.format(Locale.US, "%02X", it) }

        // Flags 段
        sb.append("■ AD[0]  Flags\n")
        sb.append("  hex:    02 01 02\n")
        sb.append("  length: 2\n")
        sb.append("  type:   0x01 (Flags)\n")
        sb.append("  flags:  0x02 = LE General Discoverable\n")
        sb.append("  说明:   由安卓系统自动追加，不可关闭\n\n")

        // Manufacturer Data 段（可选）
        if (mfgCompanyId != null && deviceMac != null && deviceMac.size >= 6) {
            val mfgLen = 1 + 2 + deviceMac.size
            val mfgIdLow = mfgCompanyId and 0xFF
            val mfgIdHigh = (mfgCompanyId ushr 8) and 0xFF
            val macHex = deviceMac.joinToString(" ") { String.format(Locale.US, "%02X", it) }
            val macStr = deviceMac.joinToString(":") { String.format(Locale.US, "%02X", it) }
            sb.append("■ AD[1]  Manufacturer Specific Data\n")
            sb.append("  hex:    ")
                .append(String.format(Locale.US, "%02X ", mfgLen))
                .append(String.format(Locale.US, "FF %02X %02X ", mfgIdLow, mfgIdHigh))
                .append("$macHex\n")
            sb.append("  length: $mfgLen\n")
            sb.append("  type:   0xFF (Manufacturer Specific Data)\n")
            sb.append("  Company ID: 0x${String.format(Locale.US, "%04X", mfgCompanyId)}\n")
            sb.append("  data:   $macStr\n")
            sb.append("  说明:   Company ID 使用 SIG 测试段 0xFFFF，不冒充任何已注册公司；\n")
            sb.append("          data 段为 6 字节稳定 deviceMac，主端按\"Company ID 后 6 字节\"\n")
            sb.append("          提取 MAC 作 stableId，广播地址轮换后仍是同一台设备\n\n")
        } else {
            sb.append("■ AD[1]  Manufacturer Specific Data  ——未携带\n")
            sb.append("  说明:   在控制页清空 Company ID 即可关闭该段；此时主端拿不到 MAC metadata，\n")
            sb.append("          stableId 会回退到当前 bleMac，重启广播后主端会当新设备\n\n")
        }

        // Service Data 段（16-bit UUID）
        val sdLen = 1 + 2 + payload.size
        sb.append("■ AD[2]  Service Data（16-bit UUID 0xFFE0）\n")
        sb.append("  hex:    ")
            .append(String.format(Locale.US, "%02X 16 E0 FF", sdLen))
            .append(if (payload.isNotEmpty()) " $payloadHex" else "")
            .append("\n")
        sb.append("  length: $sdLen\n")
        sb.append("  type:   0x16 (Service Data - 16-bit UUID)\n")
        sb.append("  UUID:   0xFFE0（小端 E0 FF；Android 自动压缩 128-bit 字面值）\n")
        sb.append("  payload: ${payload.size} 字节")
            .append(if (payload.isEmpty()) "（空）" else "（hex: $payloadHex）")
            .append("\n")
        sb.append("  说明:   主端识别 generic 从机的唯一依据——只要存在该 Service Data 即认领\n")

        return sb.toString()
    }
}
