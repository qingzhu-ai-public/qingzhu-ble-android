// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.sdk.core.generic;

import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.macs.CMac;
import org.bouncycastle.crypto.params.KeyParameter;

/**
 * 通用积木：AES-CMAC(128)。
 *
 * 这段算法本身不含任何协议私有知识（不区分是 RDLCD 还是 TXPGGP 在用），
 * 现有系统里 TagEncryptUtil 在两个协议模块里各有一份逐行相同的拷贝
 *（《单协议架构解析-TXPGGP.md》第6节观察点7），新架构里只保留这一份，
 * 两个协议的 AuthCmacStep 都调用这里，不再复制。
 */
public final class CmacHelper {

    private static final int MAC_SIZE_BITS = 128;

    private CmacHelper() {
    }

    /** 计算 16 字节 CMAC-128 */
    public static byte[] cmac(byte[] key, byte[] message) {
        CMac mac = new CMac(new AESEngine(), MAC_SIZE_BITS);
        mac.init(new KeyParameter(key));
        mac.update(message, 0, message.length);
        byte[] out = new byte[mac.getMacSize()];
        mac.doFinal(out, 0);
        return out;
    }

    /** 校验一段挑战值的 CMAC 是否与设备回传的 tag 匹配（按前 compareLen 字节比较） */
    public static boolean verify(byte[] key, byte[] challenge, byte[] deviceTag, int compareLen) {
        byte[] expected = cmac(key, challenge);
        if (deviceTag.length < compareLen || expected.length < compareLen) {
            return false;
        }
        for (int i = 0; i < compareLen; i++) {
            if (expected[i] != deviceTag[i]) {
                return false;
            }
        }
        return true;
    }
}
