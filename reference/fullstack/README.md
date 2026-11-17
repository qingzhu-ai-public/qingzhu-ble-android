# Fullstack BLE 参考工程

这是《AI 时代，如何做一个生产级 Android BLE SDK ？》中提到的 **Fullstack 教学基线源码**。

它的作用不是替代青竹 SDK，而是展示一次「让 AI 参考成熟开源项目，从零写一套教学级 BLE SDK」的实验产物：扫描会话、连接状态机、单飞 GATT 队列、分包写、Notify、最小 Demo 和模拟从端。

> 这个目录是独立 Gradle 工程，故意不加入仓库根目录的 `settings.gradle`，不会影响主 SDK 构建。

## 模块

| 模块 | 作用 |
|---|---|
| `fullstack-ble-sdk` | 教学级 BLE SDK：扫描、连接、GATT 队列、分包、Notify、超时与错误回调 |
| `fullstack-ble-demo` | 主端 Demo：权限、扫描列表、连接、发送与响应日志 |
| `fullstack-ble-peripheral-sim` | 从端模拟器：广播、GATT Server、写请求响应、串行 Notify |
| `settings.gradle` / `build.gradle` / `gradle.properties` | 独立 Gradle 工程配置 |

## 构建

在仓库根目录执行：

```bash
./gradlew -p reference/fullstack :fullstack-ble-sdk:testDebugUnitTest
./gradlew -p reference/fullstack :fullstack-ble-demo:assembleDebug
./gradlew -p reference/fullstack :fullstack-ble-peripheral-sim:assembleDebug
```

Windows：

```bat
gradlew.bat -p reference\fullstack :fullstack-ble-sdk:testDebugUnitTest
gradlew.bat -p reference\fullstack :fullstack-ble-demo:assembleDebug
gradlew.bat -p reference\fullstack :fullstack-ble-peripheral-sim:assembleDebug
```

## 边界

- 这是教学基线，不是生产级 SDK；
- 没有任务池、多设备调度、自动批次学习、稳定身份和协议插件治理；
- 只覆盖双机手工联调与基础单元测试；
- 没有系统真机兼容性、弱信号、丢包和长期稳定性数据。

青竹 SDK 与 Fullstack 的设计差异，见站点文章：

<https://qingzhu-ai-public.github.io/qingzhu-blue/#android/experience/from-runnable-to-production>

源码遵循仓库根目录的 [MIT License](../../LICENSE)。
