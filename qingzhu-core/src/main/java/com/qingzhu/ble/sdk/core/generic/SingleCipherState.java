// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.sdk.core.generic;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * 通用积木：不同协议 共用的流加密状态机，对应现有 SingleCipherUtil。
 *
 * 关键真实细节（与之前简化版 SingleCipherHelper 的固定 nonce 不同，那是错的）：
 * 1. nonce 不是固定值，是用认证挑战随机数（8字节，取前4字节）做 CMAC 得到 16 字节结果，
 *    取前 12 字节作为 nonceBuffer[4:16]，nonceBuffer[0:4] 是从 0 开始递增的 counter（小端）；
 * 2. 发送和接收共用同一个流状态实例——同一条连接上无论是发命令还是收应答，
 *    counter 都在同一个状态对象上递增，不是发送/接收各自独立初始化。
 *
 * 因此这个状态对象必须挂在 Session 上，且整个连接生命周期内只创建一次
 *（认证成功那一刻创建，断开时随 Session.clear() 销毁），不能在每次 encrypt/decrypt 时重新构造。
 */
public final class SingleCipherState {

    private final byte[] nonceBuffer = new byte[16];
    private final SecretKeySpec keySpec;
    private int counter;

    public SingleCipherState(byte[] key, byte[] challenge) {
        this.keySpec = new SecretKeySpec(key, "AES");
        byte[] temp = new byte[4];
        System.arraycopy(challenge, 0, temp, 0, 4);
        byte[] cbc = CmacHelper.cmac(key, temp); // 16 字节结果，只用前 12 字节
        System.arraycopy(cbc, 0, nonceBuffer, 4, 12);
        this.counter = 0;
    }

    /**
     * 原地对 data 做流加密/解密（XOR 对称，同一个方法即可用于两个方向）。
     * 每处理完一个 16 字节块，counter 自增并写回 nonceBuffer 前 4 字节，供下一块使用——
     * 这一步必须和真实设备的 counter 演进完全同步，跨发送/接收共享。
     */
    public synchronized void transform(byte[] data) {
        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec);

            int size = data.length;
            int times = 0;
            while (size > 0) {
                byte[] keystream = cipher.doFinal(nonceBuffer);
                int xorLength = Math.min(size, nonceBuffer.length);
                int start = nonceBuffer.length * times;
                for (int i = 0; i < xorLength; i++) {
                    data[start + i] ^= keystream[i];
                }
                counter++;
                times++;
                writeCounterLittleEndian();
                size -= nonceBuffer.length;
            }
        } catch (Exception e) {
            throw new IllegalStateException("流加密处理失败", e);
        }
    }

    private void writeCounterLittleEndian() {
        // 对应现有 ByteUtil.toLengthByteArray(counter, 4) + reverse —— 结果就是小端序 4 字节
        nonceBuffer[0] = (byte) counter;
        nonceBuffer[1] = (byte) (counter >> 8);
        nonceBuffer[2] = (byte) (counter >> 16);
        nonceBuffer[3] = (byte) (counter >> 24);
    }
}
