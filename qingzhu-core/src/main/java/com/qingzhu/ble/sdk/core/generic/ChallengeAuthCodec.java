// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Qingzhu BLE SDK contributors

package com.qingzhu.ble.sdk.core.generic;

/**
 * 通用积木：挑战应答认证帧的编解码（moveChar 混淆 + CMAC 拼接）。
 *
 * 对应现有 TXPGGP/RDLCD 共用的 TagOperate.createAuthData/moveChar 逻辑：
 * - 发送帧 = [8字节随机数][CMAC(密钥,随机数)的前8字节]，拼成16字节后做 moveChar 混淆再发出；
 * - 校验设备应答 = 先对收到的16字节做 moveChar 逆混淆（moveChar 是自逆操作，正逆用同一个函数），
 *   前8字节是设备侧发回的随机数，后8字节是设备侧签名，
 *   本地用同样的随机数重新算 CMAC，只比较前 compareLen 字节（现有实现固定比较半长，即4字节）。
 *
 * 这段编码规则本身不含任何协议私有知识（RDLCD/TXPGGP 逐行相同），收敛成一份放进 core 的
 * 通用积木区，两个协议的 AuthCmacStep 共用，不再各自维护一份拷贝。
 */
public final class ChallengeAuthCodec {

    private ChallengeAuthCodec() {
    }

    /**
     * 构造要发给设备的认证帧。
     *
     * @param key       密钥（ASCII 字节）
     * @param challenge 8 字节随机挑战值
     * @return 16 字节、已做 moveChar 混淆的认证帧
     */
    public static byte[] buildAuthFrame(byte[] key, byte[] challenge) {
        byte[] mac = CmacHelper.cmac(key, challenge); // 16 字节结果，只取前8字节
        byte[] frame = new byte[16];
        System.arraycopy(challenge, 0, frame, 0, 8);
        System.arraycopy(mac, 0, frame, 8, 8);
        return moveChar(frame);
    }

    /**
     * 校验设备的认证应答帧。
     *
     * @param key      密钥
     * @param response 设备回传的 16 字节应答帧（moveChar 混淆过的）
     * @return true 表示认证通过
     */
    public static boolean verifyAuthResponse(byte[] key, byte[] response) {
        if (response == null || response.length < 16) {
            return false;
        }
        byte[] restored = moveChar(response.clone());
        byte[] deviceRandom = new byte[8];
        byte[] deviceSign = new byte[8];
        System.arraycopy(restored, 0, deviceRandom, 0, 8);
        System.arraycopy(restored, 8, deviceSign, 0, 8);

        byte[] expected = CmacHelper.cmac(key, deviceRandom);
        // 现有实现只比较半长（selfSignData.length/2 = 4 字节），这里保持一致的兼容行为
        for (int i = 0; i < 4; i++) {
            if (expected[i] != deviceSign[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * 按 2 字节步长头尾互换的混淆算法，是自身的逆操作（做两次等于还原）。
     * 直接搬自现有 TagOperate.moveChar，行为完全一致，包括它对非偶数长度返回 null 的边界。
     */
    public static byte[] moveChar(byte[] data) {
        if (data == null || data.length % 2 != 0) {
            return null;
        }
        int length = data.length;
        for (int i = 0; i < length / 2; i += 2) {
            byte temp = data[i];
            data[i] = data[length - i - 1];
            data[length - i - 1] = temp;
        }
        return data;
    }
}
