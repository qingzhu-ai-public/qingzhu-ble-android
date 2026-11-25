// SPDX-License-Identifier: MIT
package dev.qingzhu.bthome.advertiser

import java.util.UUID

/**
 * 连接路线用的 GATT 标识（教学扩展，不属于 BTHome 标准）。
 *
 * BTHome 的目标是用广播包共享数据，规范里没有定义连接后的 GATT 格式。
 * 为了把「连接 → 订阅 → 写入」这条线在同一个项目里练到，从端在标准广播之外
 * 额外开放一个自定义服务：主端连上来以后，通知和写入用的都是同一套 BTHome 帧。
 * 用 128 位随机 UUID，避免和任何已登记的 16 位 UUID 撞车。
 */
object TeachingGatt {
    val SERVICE_UUID: UUID = UUID.fromString("9d1f0001-5c6b-4e7a-9b0c-1d2e3f4a5b6c")
    val CHARACTERISTIC_UUID: UUID = UUID.fromString("9d1f0002-5c6b-4e7a-9b0c-1d2e3f4a5b6c")

    /** 标准的客户端特征配置描述符，主端往这里写 0x0001 表示订阅通知。 */
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
}
