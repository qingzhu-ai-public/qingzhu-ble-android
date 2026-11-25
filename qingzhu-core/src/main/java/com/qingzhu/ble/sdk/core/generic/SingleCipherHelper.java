// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.sdk.core.generic;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * 通用积木：AES-ECB 密钥流 + nonce/counter XOR 流加密。
 *
 * 对应现有系统的 SingleCipherUtil（RDLCD/TXPGGP 各有一份独立拷贝，130~172 行不等）。
 * 算法本身是"密钥 + nonce 派生密钥流，再跟明文/密文做 XOR"，是对称操作
 *（encrypt 和 decrypt 是同一段代码），不含协议私有知识，收敛成一份放进 core 的通用积木区。
 *
 * 密钥/nonce 从哪来（是硬编码 π 值还是握手派生）完全是协议插件的事，
 * 这个类只负责"给定 key + nonce，对 data 做流加密"这个纯函数操作。
 */
public final class SingleCipherHelper {

    private SingleCipherHelper() {
    }

    /** 生成密钥流：用 AES-ECB 对 nonce（补齐到 16 字节）加密得到 16 字节密钥流块 */
    private static byte[] keystreamBlock(byte[] key, byte[] nonce, int counter) {
        try {
            byte[] block = new byte[16];
            System.arraycopy(nonce, 0, block, 0, Math.min(nonce.length, 12));
            block[12] = (byte) (counter >> 24);
            block[13] = (byte) (counter >> 16);
            block[14] = (byte) (counter >> 8);
            block[15] = (byte) counter;

            Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
            return cipher.doFinal(block);
        } catch (Exception e) {
            throw new IllegalStateException("生成密钥流失败", e);
        }
    }

    /** 对称操作：encrypt(key, nonce, encrypt(key, nonce, data)) == data */
    public static byte[] xorTransform(byte[] key, byte[] nonce, byte[] data) {
        byte[] out = new byte[data.length];
        int blockIndex = 0;
        int offset = 0;
        while (offset < data.length) {
            byte[] ks = keystreamBlock(key, nonce, blockIndex++);
            int len = Math.min(16, data.length - offset);
            for (int i = 0; i < len; i++) {
                out[offset + i] = (byte) (data[offset + i] ^ ks[i]);
            }
            offset += len;
        }
        return out;
    }
}
