# ADR-001：Android 平台和构建基线

- 状态：Accepted
- 日期：2026-08-01

## 决策

- `minSdk = 29`（Android 10）。
- `compileSdk = 36`。
- `targetSdk = 36`。
- AGP 固定 `9.2.1`，Gradle Wrapper 固定 `9.4.1`。
- Kotlin 固定 `2.3.21`，JVM bytecode/toolchain 目标 17。
- Android App 模块使用 AGP 9 默认启用的 built-in Kotlin，不再应用 `org.jetbrains.kotlin.android`；纯 JVM 模块仍应用 Kotlin JVM 插件。
- 构建进程使用现有 JDK 21；AGP 9.2 要求的最低 JDK 是 17。
- Compose 使用稳定 BOM `2026.06.00`。
- 只使用稳定版 AndroidX；实验性依赖需单独 ADR。
- AndroidX 依赖若要求 compileSdk 37 而未提供本阶段必需能力，则固定到兼容 API 36 的最近稳定版；首例为 Lifecycle 2.10.0。

## 原因

- 当前 D 盘 Android SDK 已安装 API 35、36 和 Build Tools 36.0.0，无需为首个骨架再下载平台。
- Android 10 覆盖足够现代的安全和后台行为，同时高于 Room 2.8 的最低 API 23。
- AGP 9.2 官方兼容 Gradle 9.4.1、Build Tools 36.0.0 和 JDK 17+；固定版本可避免动态升级破坏可复现构建。
- 首个工程不依赖 API 37 功能，因此暂不安装 API 37。

## 影响

- 不支持 Android 9 及更早版本。
- Android 15/16 的后台、通知、备份和隐私行为必须进入测试矩阵。
- 每次升级 AGP/Kotlin/Compose BOM 都必须独立提交并跑全量构建测试。

## 依据

- [AGP 9.2 compatibility](https://developer.android.com/build/releases/agp-9-2-0-release-notes)
- [Compose BOM](https://developer.android.com/develop/ui/compose/bom)
- [Room stable releases](https://developer.android.com/jetpack/androidx/releases/room)
