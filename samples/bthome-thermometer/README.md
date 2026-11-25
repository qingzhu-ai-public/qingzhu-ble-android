# BTHome 温湿度计：一个 APK，两个角色

青竹蓝牙学堂 Android 第十四课「综合实战：BTHome 温湿度计完整 App」的配套源码。
课程页面：<https://qingzhu-ai-public.github.io/qingzhu-blue/android/14-bthome-project.html>

- **从端（传感器）**：手机把模拟的温湿度编码成 BTHome v2 广播发出去，Home Assistant、ESPHome、nRF Connect 都能直接收到。
- **主端（接收器）**：只扫 0xFCD2 的广播，解析、按设备合并、10 秒没广播就标离线。
- **连接路线（教学扩展）**：主端连上从端，订阅通知，再写一条指令改从端的读数。

不依赖任何 BLE SDK，只用 Android 原生 API + Kotlin 协程。

## 先看这里

| 想看什么 | 文件 |
|---|---|
| 对象 ID 表（规范里那张表的子集） | [`protocol/BthomeObjects.kt`](app/src/main/java/dev/qingzhu/bthome/protocol/BthomeObjects.kt) |
| 解析：字节 → 读数 | [`protocol/BthomeParser.kt`](app/src/main/java/dev/qingzhu/bthome/protocol/BthomeParser.kt) |
| 编码：读数 → 字节，31 字节预算 | [`protocol/BthomeEncoder.kt`](app/src/main/java/dev/qingzhu/bthome/protocol/BthomeEncoder.kt) |
| 主端扫描、按地址合并、离线 | [`scanner/`](app/src/main/java/dev/qingzhu/bthome/scanner/) |
| 主端连接、订阅通知、写入 | [`scanner/BthomeGattClient.kt`](app/src/main/java/dev/qingzhu/bthome/scanner/BthomeGattClient.kt) |
| 从端广播、热更新、扫描响应 | [`advertiser/BthomeAdvertiser.kt`](app/src/main/java/dev/qingzhu/bthome/advertiser/BthomeAdvertiser.kt) |
| 从端 GATT 服务 | [`advertiser/BthomeGattServer.kt`](app/src/main/java/dev/qingzhu/bthome/advertiser/BthomeGattServer.kt) |
| 三种假数据源 | [`advertiser/FakeSensorSource.kt`](app/src/main/java/dev/qingzhu/bthome/advertiser/FakeSensorSource.kt) |
| 单元测试（官方样例 + 边界值） | [`app/src/test/`](app/src/test/java/dev/qingzhu/bthome/protocol/) |

```
app/src/main/java/dev/qingzhu/bthome/
├── protocol/                  纯 Kotlin，不碰 Android API，能在电脑上跑测试
│   ├── BthomeObjects.kt
│   ├── BthomeParser.kt
│   └── BthomeEncoder.kt
├── scanner/                   主端
│   ├── BthomeScanner.kt
│   ├── BthomeRepository.kt
│   └── BthomeGattClient.kt
├── advertiser/                从端
│   ├── BthomeAdvertiser.kt
│   ├── BthomeGattServer.kt
│   ├── TeachingGatt.kt
│   └── FakeSensorSource.kt
└── ui/
    ├── MainActivity.kt
    ├── scanner/ScannerScreen.kt
    └── advertiser/AdvertiserScreen.kt  +  AdvertiserViewModel.kt
```

## 规范在哪

| 资源 | 地址 | 拿什么 |
|---|---|---|
| 格式规范 | <https://bthome.io/format> | 广播结构、设备信息字节、全部对象 ID 表 |
| 加密规范 | <https://bthome.io/encryption> | AES-CCM、nonce、防重放 |
| 文档源码 | <https://github.com/home-assistant/bthome.io> | 可追踪更新 |
| Python 参考解析器 | <https://github.com/Bluetooth-Devices/bthome-ble> | 每种对象类型的测试样例 |

## 不编译，直接装

| 文件 | 说明 |
|---|---|
| [bthome-thermometer-v1.0-debug.apk](../../downloads/bthome-thermometer-v1.0-debug.apk) | Debug 签名，Android 8.0+，主端和从端在同一个 App 里 |
| [SHA256SUMS.txt](../../downloads/SHA256SUMS.txt) | 校验和 |

```bash
adb install -r downloads/bthome-thermometer-v1.0-debug.apk
```

两台手机各装一份：一台开从端广播，另一台开主端扫描。只有一台手机也可以自己扫自己（见下文）。

## 构建

独立 Gradle 工程，不加入仓库根目录的 `settings.gradle`，不影响主 SDK 构建。

```bash
# 在仓库根目录执行
./gradlew -p samples/bthome-thermometer :app:testDebugUnitTest   # 解析器 / 编码器 / 预算
./gradlew -p samples/bthome-thermometer :app:assembleDebug       # APK
```

APK 输出：`samples/bthome-thermometer/app/build/outputs/apk/debug/app-debug.apk`

环境：JDK 17+、Gradle 8.13、compileSdk 35、minSdk 26（`startAdvertisingSet` 从 Android 8.0 起才有）。

## 怎么玩

### 广播路线（规范内的部分）

1. 手机 A 打开 App → 「从端」页 → 数据源选「正弦波」→ 开始广播。
2. 手机 B 打开 App → 「主端」页 → 开始扫描。
3. 列表里出现 A，温度和湿度每秒变一次。

只有一台手机也可以：从端开始广播后切回主端页，自己扫自己。

三种数据源各验一类问题：

| 数据源 | 验证什么 |
|---|---|
| 正弦波 | UI 刷新、RSSI 抖动下读数稳定 |
| 手动滑杆拉到 -5.5 | sint16 符号扩展，主端应显示 -5.50 而不是 650.50 |
| 手动滑杆拉到 0 | 零值显示 0.00，而不是空 |
| 剧本 | 走一遍 -5.5 / 0 / 25.06 / 40 / -40，最后发 105% 湿度，主端应标「越界」 |
| 从端点停止 | 主端约 10 秒后把这台设备标成离线 |

### 连接路线（教学扩展）

BTHome 的目标是用广播包共享数据，规范里没有定义连接后的 GATT 格式（见 [Scope & Constraints](https://bthome.io/#scope-constraints)）。
为了在同一个项目里把「连接 → 订阅 → 写入」练到，从端额外开放了一个自定义服务，**不属于 BTHome 标准**：

| 项 | UUID | 说明 |
|---|---|---|
| 服务 | `9d1f0001-5c6b-4e7a-9b0c-1d2e3f4a5b6c` | 主服务 |
| 特征 | `9d1f0002-5c6b-4e7a-9b0c-1d2e3f4a5b6c` | NOTIFY + WRITE |
| CCCD | `00002902-0000-1000-8000-00805F9B34FB` | 标准，写 `01 00` 订阅 |

通知和写入的字节，和广播里的 BTHome 数据完全一样，所以主从两端共用同一个 `parse()` / `encode()`。

1. 从端页勾选「允许主端连接」→ 开始广播（可连接广播）。
2. 主端页点列表里这台设备 → 连接 → 自动发现服务 → 自动写 CCCD。
3. 连接面板开始滚动显示通知帧。
4. 点「写入：把从端温度设为 30.00 °C」→ 从端解析后切到手动数据源并把温度设为 30 → 广播同步更新。

注意：可连接广播在被连上后会停，断开后系统自动恢复；被连上期间其它主端扫不到它，会显示离线，这是正常现象。

## 协议速查

```
广播包（Legacy，31 字节预算）
02 01 06                              Flags，系统自动加，3 字节
0A 16 D2FC 40 02C409 03BF13           Service Data，BTHome 在这里
   └ 类型 0x16 └ UUID 小端 D2FC = 0xFCD2

BTHome 数据
40          设备信息：bit0 加密 / bit2 触发式 / bit5-7 版本；0x40 = v2 不加密定时
02 C409     对象 0x02 温度，sint16 小端，2500 × 0.01 = 25.00 °C
03 BF13     对象 0x03 湿度，uint16 小端，5055 × 0.01 = 50.55 %
```

硬规则：

- 对象 ID 必须从小到大排列；接收端遇到不认识的对象 ID 就停止解析。
- 每个字节先 `and 0xFF`，再按小端拼，有符号的做符号扩展。
- 主包放 Flags + Service Data，设备名放扫描响应，两边各 31 字节。
- 读数变了用 `AdvertisingSet.setAdvertisingData()` 热更新，不停广播；上一次没回调前再设会被丢，所以代码里用 `updating / dirty` 合并。
- `onScanFailed` 必须处理。错误码 6 是启停太频繁，退避 1s / 2s / 4s 重试，最多 3 次。

## 已知边界

- 不处理加密帧：`encrypted = true` 的帧直接忽略，解密和 counter 校验留作进阶。
- 只登记了 5 个对象，要支持更多对象在 `Obj` 枚举里加一行即可。
- 连接路线没有做断线重连和写入队列，每次只发一条，按回调再发下一条。
- BLE 收发路径按规范和 Android 文档编写，单元测试只覆盖协议层；真机行为请按课程第 9 步的 9 条验收清单逐项确认。

## License

[MIT](../../LICENSE) © 2026 Qingzhu BLE SDK contributors
