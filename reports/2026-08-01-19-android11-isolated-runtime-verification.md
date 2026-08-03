# 织卷工作汇报 19：Android 11 隔离运行时验证

> 日期：2026-08-01  
> 对应：TASK-016 补充验收  
> 结论：Android 11 旧版备份规则的运行时发布门禁已关闭

## 1. 风险边界

本轮按“不得把其他项目带入织卷”的要求执行：

- 停止继续下载新镜像；
- 只读检查标准 Android SDK 与 AVD 位置；
- 不复制、不修改、不启动任何其他项目或既有 AVD；
- 只使用 `D:\gptuser\tools\android-sdk` 中的系统镜像；
- 新建独立空白 AVD `zhijuan_api30_clean`，数据仅位于 `D:\gptuser\cache\android-user\avd`；
- 不绕过物理手机的 USB 安装限制。

## 2. 镜像核验

找到并核验：

`D:\gptuser\tools\android-sdk\system-images\android-30\default\x86_64`

元数据声明 Android API 30、x86_64、AOSP Default Image、revision 10；关键启动文件 `system.img`、`vendor.img`、`userdata.img`、`kernel-ranchu` 与 `ramdisk.img` 均存在。模拟器实际启动后返回 Android 11 / API 30。

## 3. 验证结果

在 API 30 空白 AVD 上只安装织卷当前工程产出的：

- `app-debug.apk`；
- `app-debug-androidTest.apk`。

执行结果：

| 验证项 | 结果 |
|---|---|
| 清单禁止系统备份 | 通过 |
| Android 12+ 规则资源完整性 | 通过 |
| Android 11 及以下九域排除规则 | 通过 |
| Backup Manager 实际拒绝应用备份 | `Backup is not allowed` |

仪器测试为 `OK (3 tests)`。由此，Android 11 旧规则不再只是静态 XML 校验，而是完成了系统运行时验收。

## 4. 物理测试机状态

已识别小米 Android 16 / API 36 测试机。第一次安装织卷调试包时，设备返回：

`INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`

为避免绕过设备安全控制，本轮立即停止真机安装，没有读取个人数据、没有安装测试包、没有更改设备安全设置。待设备侧明确允许 USB 安装后，可在同一台设备重复 3 项测试，补充厂商系统证据；这不影响 API 30 门禁已经关闭的结论。

## 5. 工程改进

`scripts/verify-device-backup-policy.ps1` 新增 `-Serial` 参数。多设备同时连接时，验证脚本现在必须明确选择目标设备，避免把命令误发到另一台手机或模拟器。

## 6. 下一步

自动进入 TASK-017：数据库迁移可靠性框架，覆盖全部受支持起始版本、生产 SQLCipher 路径、数据计数/哈希/外键校验，以及缺失迁移时禁止破坏性清库。
