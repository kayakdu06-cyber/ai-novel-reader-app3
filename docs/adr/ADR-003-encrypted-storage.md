# ADR-003：加密数据库与密钥方案

- 状态：Accepted
- 日期：2026-08-01

## 决策

- Room 2.8.4 负责数据建模、事务和迁移。
- SQLCipher for Android Community Edition 作为正式全库加密方案，当前固定 `net.zetetic:sqlcipher-android:4.17.0`；升级前必须重新执行迁移、兼容性、体积和许可证验证。
- 首次安装生成随机数据库口令；口令由 Android Keystore 中的 AES-GCM 包装密钥加密后保存。
- API secrets 使用独立记录和独立用途密钥，不直接复用数据库口令。
- 数据库、WAL/SHM、草稿、恢复点都处于同等保护范围。

## 发布前必须跟进

- Release APK 各 ABI 体积增量。
- 10 万段落规模的读写和 FTS 性能。
- 杀进程重开、Keystore 失效、系统升级和设备锁定路径。
- 在 App 的“关于/第三方许可证”页面复现 SQLCipher Community Edition 许可证，并由 TASK-105 验证 Release 包内可访问性。

## M0.5 已验证结果

- 在 Android 35 x86_64 模拟器实际运行 Room 2.8.4、SQLCipher for Android 4.17.0 和 `androidx.sqlite 2.6.2`，建库、加密读写、关闭后重开均通过。
- 数据库文件头不是明文 SQLite 头，已知正文 UTF-8 字节串未出现在数据库文件中。
- Android Keystore AES-256-GCM 同一明文两次加密得到不同 IV/密文，并可正确解密。
- schema 1 → 2 的显式加密数据库迁移保留旧正文和 FTS 召回。
- 20 万汉字单章：派生索引约 778 ms，加密写入约 732 ms，热查询约 2.37 ms；该夹具高度重复，不用于推断真实小说的索引体积。
- 发现 SQLCipher 与 Room 连接池的口令生命周期风险：口令不能在首次连接返回后立即清零。当前由数据库句柄持有，关闭 Room 后再清零；该行为已有多连接回归测试。
- 删除 Android Keystore 包装密钥后，读取既有口令包会明确失败，口令包字节保持不变；不会把“密钥失效”误当首次安装并静默生成新数据库口令。
- `sqlcipher-android:4.17.0` AAR 为 4,007,507 字节；4 个 `libsqlcipher.so` 压缩后分别约为 arm64-v8a 1,026,194、armeabi-v7a 665,859、x86 1,099,872、x86_64 1,093,975 字节，合计 3,885,900 字节。正式 APK 的最终增量仍需在 App 接入后测量。
- Community Edition 为 BSD-style 许可，要求二进制分发在用户可访问材料中复现版权、条件与免责声明；当前许可证副本已归档在 `third-party/sqlcipher-community-license.txt`，App 内许可证页面由 TASK-105 完成。

以上结果足以确定正式 schema 的技术方向，因此 ADR 改为 `Accepted`。物理设备、最终 APK 体积、许可证页面、Keystore 失效恢复 UI 和厂商系统差异仍是发布门禁；`Accepted` 不代表这些测试已经通过。

后续若物理设备或许可证审查出现不可接受结果，必须新增替代 ADR；不得临时退化为明文 Room。

## 依据

- [SQLCipher for Android](https://github.com/sqlcipher/sqlcipher-android)
- [SQLCipher Community Edition integration](https://www.zetetic.net/sqlcipher/sqlcipher-for-android-community/)
- [Android Keystore](https://developer.android.com/privacy-and-security/keystore)
- [Android cryptography guidance](https://developer.android.com/privacy-and-security/cryptography)
