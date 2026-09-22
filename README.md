# qingzhu-ble-android

青竹 Blue 的 Android BLE SDK 与配套示例工程（`qz-ble-sdk` + `QZBleDemo`）。

📖 文档站：<https://qingzhu-blue.github.io/qingzhu-blue/>

> **当前状态：设计阶段。** 仓库结构与接口边界已定，SDK 代码尚未落地。
> 文档站 Android 栏目正在补齐背景知识（总览 / 权限适配 / 常见问题排查）。

## 规划结构

```
qingzhu-ble-android/
├── qz-ble-sdk/          # 青竹 BLE 封装
├── example/             # QZBleDemo（完整 Demo）
│   ├── scan/            # 扫描 & 设备列表
│   ├── connect/         # 连接 & 自动重连
│   ├── gatt/            # 服务发现 / 读写 / Notify
│   ├── ota/             # OTA 升级
│   └── log/             # 日志调试
└── README.md
```

**SDK 与 Example 同一个仓**，不做成两个仓。理由：Demo 就是接入范例，跟 SDK 一起发版才能保证「能跑」和「怎么跑」不脱节；拆开会立刻出现 Demo 落后 SDK 一个版本的经典问题。

## QZBleDemo 覆盖的链路

一个 Demo 把整条链路走完，不拆成一堆 `scanner-demo` / `ota-demo` 那种教学片段：

扫描设备 · 设备列表 · 连接设备 · 服务发现 · Characteristic 读写 · Notify · 数据解析 · 自动重连 · OTA · 日志调试

## 路线图

- [ ] `qz-ble-sdk` 骨架：扫描 / 连接 / 服务发现 / 读写 / Notify 的基础封装
- [ ] 连接状态机与重连策略（超时、退避、系统回收后的恢复）
- [ ] 权限适配层（Android 12+ `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT` 与旧版本兼容）
- [ ] OTA（DFU）通道
- [ ] `QZBleDemo` 完整示例
- [ ] 发布到 Maven / JitPack（分发方式待定）

## 待定项

- 语言（Kotlin / Java）、最低支持版本
- 分发方式与开源许可证
- 设备协议层：帧格式、UUID 约定、错误码的规范化定义 —— 计划与文档站同源维护

## 相关仓库

- [`qingzhu-blue`](https://github.com/qingzhu-blue/qingzhu-blue) —— 官网与文档站（BLE 知识、工程实践）

## 参与

SDK 尚未进入编码阶段，现阶段最有用的反馈是**你想解决的具体问题**：
在 [Issues](https://github.com/qingzhu-blue/qingzhu-ble-android/issues) 里描述你的设备与场景即可。
