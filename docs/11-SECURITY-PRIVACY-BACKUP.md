# 织卷安全、隐私、备份与升级

## 1. 威胁范围

要防范：

- API 密钥出现在日志、模板、导出或系统云备份；
- 手机遗失、借用或最近任务缩略图暴露小说正文；
- 恶意/错误中转站地址通过重定向窃取鉴权头；
- 数据库升级失败清空书；
- 换机或卸载后没有可恢复备份；
- 正式签名密钥丢失导致后续 APK 无法覆盖安装；
- 导入包路径穿越、超大解压或损坏当前库；
- 调试构建、崩溃报告或通知泄露敏感内容。

不承诺防范已经取得设备 root 权限、运行时完全控制和解锁凭据的高级攻击者，但设计不得留下明文低成本泄露。

## 2. 数据分类

| 等级 | 数据 | 默认保护 |
|---|---|---|
| S0 极敏感 | API 密钥、自定义鉴权头、加密主密钥 | Keystore 包装、独立密文、绝不日志/模板/普通备份 |
| S1 敏感 | 小说正文、人物设定、模板内容、草稿 | 加密存储、后台遮挡、默认不进诊断 |
| S2 私密元数据 | 书名、阅读进度、模型使用、费用台账 | 加密库、通知最小化 |
| S3 非敏感 | 主题、字号、公开许可证 | DataStore/资源文件可存 |

## 3. 密钥管理

- 首次使用生成随机数据加密密钥，由 Android Keystore 中不可导出的包装密钥保护。
- API Key 按连接独立加密；业务对象只保存 `secretRefId`。
- 界面只展示密钥尾部 4 位；复制密钥不是常规功能。
- 修改基础地址不自动沿用密钥到跨域最终地址，需重新测试。
- 删除连接时立即删除可用 secret；历史请求只保留提供方/模型的脱敏快照。
- Keystore 密钥失效时停止生成并进入恢复页，不用空密钥覆盖旧数据。
- Release 禁止自定义信任所有证书、跳过 hostname verification 或记录请求头。

### TASK-014 正式 Secret Store 基线

`core:security` 已实现独立于数据库口令的 `AndroidSecretStore`：

- 每条 API 密钥或敏感请求头生成随机 `secretRefId`，业务层只持有引用；
- 每条记录使用独立 Android Keystore AES-256-GCM 别名，别名还包含单调 `keyVersion`，不复用数据库口令包装密钥；
- 密文与 `secretRefId`、用途、尾部显示、状态、keyVersion 和创建时间通过 AAD 绑定；篡改元数据会导致认证失败；
- 创建和轮换 API 消费调用方传入的 ByteArray，并在成功或失败后清零；读取只通过可关闭 `SecretLease` 暂借，关闭、轮换、撤销或 SecretStore 失效都会清零明文缓冲区；
- 轮换保持 `secretRefId` 不变，先用新 keyVersion 加密并原子替换记录，再删除旧 Keystore 别名；连接不会落入“新密钥、旧引用”半状态；
- 撤销先原子改写为无密文墓碑，再删除 Keystore 密钥；即使删除步骤或进程中断，也不会重新变成可读 secret；
- Keystore 条目缺失、失效、密文损坏、用途不匹配或元数据篡改均失败关闭，不自动生成替代密钥覆盖旧记录；
- 列表 API 只返回用途、尾四位、状态和时间等 descriptor，不提供批量明文读取；SecretStore 不可用时 descriptor 也不可列出；
- 文件位于 `noBackupFilesDir/security/api-secrets`，仍由 TASK-016 对 Android 备份规则做双重排除和发布验证。

应用级设备凭据/生物识别流程已取消；现有 SecretStore 若保留内部 lock/unlock 原语，也不得对外接成 TASK-097 产品功能。TASK-031 已接入向导临时密钥：原文不进入可保存 Compose 状态，检查后只显示尾四位；换服务/地址、退出或异常进程重启时撤销临时记录。连接表只保存 `secretRefId`，编辑轮换和删除调用 `revoke` 的正式接线属于 TASK-032。

## 4. 小说和模板加密

正式选型需通过技术尖峰，候选包括经过维护的 SQLCipher/等价方案或应用字段加密。选型标准：

- 与 Room 迁移和事务兼容；
- 真实中文章节读取性能可接受；
- 能保护数据库、WAL/SHM、临时文件、草稿和恢复点；
- 密钥轮换和故障恢复有明确路径；
- 许可证可接受；
- 不自行设计密码算法或非标准模式。

如果全文搜索需要明文派生索引，索引必须同等保护；不能为了搜索把正文复制到未加密数据库。

### TASK-015 正式内容存储基线

TASK-015 已把三类内容收束到两个明确事实源：

- 正式小说正文、模板和派生记忆只存于 SQLCipher Room 数据库；生产路径不允许用普通 Room factory 打开正式库，也不建立未加密正文镜像；
- 未完成流式草稿和迁移恢复点存于 `noBackupFilesDir/content/protected-artifacts` 下的随机引用文件，不把书名、章节名、jobId 或 attemptId 写进文件名。

受保护 artifact 格式使用标准 Android Keystore AES-256-GCM，而不是自定义密码算法：

- 每个 artifact 独立 key，密钥别名由随机 UUID 引用派生，不复用 API secret 或数据库口令包装密钥；
- 文件头通过空明文 GCM 标签认证；类型、引用、修订、keyVersion、时间和块大小被绑定，修改元数据会失败；
- 内容以 64 KiB 分块，每块使用随机 IV，AAD 绑定头摘要、连续序号和明文长度；
- 认证尾记录绑定总块数、总长度和 SHA-256，缺尾、截断、重排、插入、尾随数据或密文篡改均不能通过完整验证；
- 草稿换版采用 expectedRevision + `AtomicFile`；写入中断保留旧版，过期执行器不能覆盖新版；
- 创建/替换的 ByteArray 输入在成功或失败后清零；小草稿只通过可关闭 lease 读取，关闭、替换、删除或 App 锁定会清零借出缓冲；
- 恢复点用流式 API 处理，16 GiB 格式上限、64 KiB 单次读缓冲避免无界内存；调用方仍须把解密结果写入可丢弃 staging，并在完整验证后才切换；
- 删除时先移除 Keystore key，再删除文件；即使进程中断留下密文，也没有可用解密 key。

设备验收已经覆盖：正式正文在 SQLCipher 主库/WAL/SHM 的明文扫描、草稿与 2 MiB 恢复点的文件扫描、旧修订拒绝、写入中断回滚、用途错误、密钥缺失、密文篡改、锁定清零和删除 key。物理设备闪存取证、真实多 GiB 恢复点、磁盘写满与迁移编排仍保留为发布门禁，未被本任务伪装成已完成。

### TASK-043 流式草稿运行时边界

- 每次外部 attempt 在写 RequestIntent 前先分配空的随机 UUID 草稿；数据库审计失败会销毁该 artifact，避免“请求没成立但草稿长期漂浮”；
- Provider 增量只进入可清零内存缓冲和 Keystore 加密 artifact。默认 2 秒或 32 KiB 才写一次磁盘，避免逐 token 频繁换版；单阶段明文硬上限 4 MiB，越界失败关闭；
- 检查点继续使用 `AtomicFile` 与 expectedRevision。自审发现只枚举基础 `.zjaf` 会漏掉崩溃时仅剩的 `.bak/.new`，现已统一识别三种文件名、按随机引用去重，并让 `AtomicFile.openRead` 执行备份恢复；
- 流缓冲、临时快照和调用方字节输入在关闭、冲突、异常或消费后清零；旧 writer 一旦修订冲突便不可继续写；
- 崩溃识别会做 artifact 类型、引用唯一性、数据库状态和完整性检查。缺失、重复引用、损坏或现场变化不会被自动删除或伪装成可重试；
- 正式成功按 Stage 提交时间保留 24 小时，失败/取消/未知结果保留 7 天，孤立 artifact 保留 24 小时。清理在进程互斥区内再次核对数据库引用，避免并发绑定期间误删；
- TASK-043 全部网络执行测试使用本地 fake adapter；真实密钥、真实 Provider 和物理设备均未使用。草稿不是正式章节，只有 TASK-045 的 SQLCipher 事务可以更新 `ChapterVersion`。

### TASK-044 结构化校验与修复隐私边界

- 原始结构化输出只从受保护 artifact 的可关闭明文 lease 读取；读取后校验修订和 SHA-256，租约关闭即清零借出字节；
- 严格解析器在进入 JSON 对象模型前执行字节、深度、节点、成员、数组、字符串和数字长度上限，防止畸形/超大输入造成无界资源使用；
- 校验结果、错误报告和修复计划的公开字符串只含 schema、大小和枚举问题码，不含正文、字段值、artifact 引用或 output hash；问题路径必须有界且不能含控制字符，正式契约应使用静态路径；
- 修复提示不重放原始创作提示，把无效输出封装为数据并明确其中任何指令都不可改变修复任务；只有大小不超过修复上限的合法 UTF-8 内容可进入一次修复；
- 本阶段设备测试继续只用本地 fake adapter，真实 API、真实密钥和物理设备写入均为 0。

### TASK-045 正式章节提交隐私边界

- 提交许可的构造范围受限，并绑定 Attempt、Stage、artifact 修订/hash、校验时间和租约；其默认字符串以及提交结果默认字符串不泄露这些关联值或正文；
- 加密草稿只在首次提交前通过可关闭 lease 复核修订和 SHA-256；成功 Stage 的精确重放依赖 SQLCipher 中的持久证据，因此草稿按策略清理后仍能幂等恢复；
- `outputReferenceJson` 仅含版本化 ID/hash，不含正文、结构化字段值、API 密钥或 Provider 原始响应；正文和派生记忆只进入 SQLCipher 主库；
- 内容、结构化 JSON 与 ID 均有硬上限和来源校验。所有 TASK-045 测试使用本地数据库夹具，在明确指定的 API 35/API 30 模拟器运行；真实 API、真实密钥、物理设备写入仍为 0。

## 5. 屏幕隐私功能边界（已取消）

2026-08-06 用户明确决定不需要应用锁、生物识别、`FLAG_SECURE` 或最近任务缩略图遮挡，TASK-097 与 TEST-089 因此取消。正式实现不得再为这些功能增加页面、开关、依赖、凭据流程或发布门禁。

这项取消不改变以下边界：

- 通知仍默认只显示“新章节已完成”“生成已暂停”等通用文案，不显示书名、人物或正文；
- API 密钥、SQLCipher 数据库、加密草稿、系统备份排除和手动加密备份继续按本文保护；
- App 不承诺在设备已解锁、系统截图、录屏或最近任务缩略图中额外隐藏屏幕内容，用户使用设备自身锁屏能力承担该层保护。

## 6. Android 系统备份策略

Android 默认自动备份可能包含大多数应用文件，因此不能依赖“本地 App”这一假设。配置：

- Android 12+ 使用 `data-extraction-rules` 明确排除密钥、数据库、WAL/SHM、草稿、日志、恢复点和缓存；
- 旧系统使用对应 `full-backup-content` 排除规则；
- 备份行为必须通过 `adb backup/restore` 或官方测试方法验证；
- `allowBackup` 和设备迁移行为在 release 清单中明确，不能只在 debug 验证；
- 系统云备份不承担织卷恢复职责，用户使用应用内加密备份包。

参考：[Android Auto Backup](https://developer.android.com/identity/data/autobackup)、[Backup security best practices](https://developer.android.com/privacy-and-security/risks/backup-best-practices)。

### TASK-016 自动备份排除基线

织卷采用“系统备份全部关闭，用户只使用应用内加密备份”的产品策略：

- 主清单固定 `android:allowBackup="false"`；API 35 安装后的 `ApplicationInfo.FLAG_ALLOW_BACKUP` 为 0，Android Backup Manager 对包返回 `Backup is not allowed`；
- Android 12+ 同时提供 `data-extraction-rules`，在 `cloud-backup` 和 `device-transfer` 两个模式下分别排除全部九个域：`root/file/database/sharedpref/external/device_root/device_file/device_database/device_sharedpref`；
- Android 11 及以下同时提供 `full-backup-content`，同样逐项排除九个域；只排除 `root` 并不等于排除数据库、文件和 SharedPreferences，本任务已修复此前的不完整规则；
- cloud backup 额外设置 `disableIfNoEncryptionCapabilities="true"` 作为纵深保护，但它不替代全域排除和 `allowBackup=false`；
- 自动验证脚本检查源码、Debug 合并清单和 Release 合并清单，防止库清单或构建变体覆盖三个关键属性；设备测试解析安装 APK 中的两套 XML，确保没有 `<include>`、没有漏域、没有非根路径；
- Android 16 QPR2 的跨平台传输需要显式 `cross-platform-transfer` 和目标 iOS 身份参数；织卷没有 iOS 对端，也没有声明该节，不启用这条导出路径。

API 35 的现代规则验证与 API 30（Android 11）的旧规则运行时验证均已通过。API 30 使用全新、空白、项目专属的 `zhijuan_api30_clean` AVD；3 项安装包规则测试全部通过，`bmgr backupnow app.zhijuan.reader.debug` 明确返回 `Backup is not allowed`。测试没有复用其他项目、旧 AVD 或快照。小米 Android 16 物理测试机因系统拒绝 USB 安装而未执行，未尝试绕过设备限制；它属于后续厂商实机复验，不再阻塞 Android 11 发布门禁。

## 7. 手动加密备份

### 7.1 默认包含

- 小说、章节和版本；
- 故事圣经、规划和运行记忆；
- 模板、来源链和使用快照；
- 未完成任务的安全检查点；
- 用量台账；
- 非敏感设置与连接描述（不含 secret）；
- manifest、schema、哈希和应用版本。

### 7.2 默认排除

- API 密钥和自定义敏感头；
- Keystore 密钥；
- 完整网络请求和响应；
- 可重建缓存；
- 备份口令本身。

用户即使选择“包含连接”，也只包含地址、协议、模型和非敏感头。恢复后首次调用要求重新输入密钥。

### 7.3 加密要求

- 用户提供备份口令；采用成熟 KDF 和认证加密，参数写入 manifest；
- 每个包使用随机 salt 和 nonce；
- 口令不保存到包内或应用偏好；
- 写入目标时先使用临时文档/文件，全部验证后再最终命名；
- 备份完成后立即执行解密清单和哈希自检；
- 显示书数、章节数、模板数、大小和时间供人工核对。

### 7.4 M0.8 加密流与原子恢复尖峰结论

- 新增 `:core:backup`，用固定版本包头、随机 128-bit salt、随机 nonce 前缀、PBKDF2-HMAC-SHA256 和分块 AES-256-GCM 验证流式加密。每块使用独立 nonce，并把包头、块序号和明文长度作为 AAD。
- 当前 PBKDF2 默认基线为 600,000 次，参数与 KDF/cipher ID 写入包头，并在派生密钥前执行迭代次数、块大小、明文长度和块数量上限检查。该默认值是可运行基线，不冻结正式备份格式；进入 `TASK-100` 时仍需比较 Android 可用的 Argon2id 实现、许可证、包体和中档机性能。
- 1 GiB 级目标采用有界分块读写；JVM 测试以 64 MiB 模式流证明单次读取请求不超过 64 KiB，不把整包读入内存。
- 备份写入使用同目录临时文件，完整解密和 SHA-256 自检后才要求原子替换；目标存储不支持原子替换时失败关闭，不退化为先删旧文件。
- 恢复先解密到活动库同文件系统的临时文件，执行调用方校验并创建旧库恢复点，再原子替换。错口令、认证损坏、schema 校验失败和提交前中断都保持活动库字节不变。
- Android 15 模拟器内部存储的原子替换成功；1 MiB 数据在 600,000 次 PBKDF2 下加密+解密约 3.95 秒，因此正式 UI 必须在后台执行并显示可中断进度。

尖峰尚未覆盖多文件 manifest、Room/WAL 一致快照、SAF/DocumentsProvider 的最终发布语义、磁盘写满/拔盘、真实 1 GiB 包和进程在原子系统调用瞬间被杀。正式恢复仍必须先把外部备份复制/解密到应用内部同文件系统，再切换活动库。

## 8. 恢复策略

1. 只读取 manifest 并检查格式上限；
2. 验证口令和认证标签；
3. 检查可用空间，至少保留当前库+恢复包展开+安全余量；
4. 导入到内部临时目录；
5. 在临时库运行所需迁移；
6. 校验外键、章节引用、内容哈希、书/模板数量；
7. 为当前库创建恢复点；
8. 原子切换活动库；
9. 启动后再次做轻量完整性检查；
10. 用户确认后清理临时文件。

任何一步失败都保留当前库。恢复支持“替换当前数据”为首发唯一模式；合并恢复涉及复杂 ID/来源冲突，延期到 P2。

## 9. 备份提醒与演练

- 完成第一本中篇/长篇后提示首次备份；
- 之后根据“新增 10 章或 14 天”提醒，可关闭；
- 设置页显示最近成功备份时间和是否做过恢复演练；
- 每个稳定版发布前用上一版本真实样本完成备份→升级→恢复演练；
- 只有文件存在不算备份成功，必须通过自检。

## 10. 数据库升级

- 每个版本提供显式 Room Migration；
- 为所有支持起点运行迁移测试和真实大库测试；
- 正式版禁止 `fallbackToDestructiveMigration`；官方文档明确此回退会永久删除表中数据；
- 迁移前检查空间并建立加密恢复点；
- 失败进入只读恢复模式，允许导出旧库/诊断，不反复尝试破坏性迁移；
- 迁移完成后才提高 schema 活动版本。

### TASK-017 当前迁移证据

- `ZhijuanMigrations.ALL` 是生产启动与测试共同使用的唯一迁移注册表，新增版本时不再分别维护多处清单；
- v1、v2、v3 三个受支持起点都已迁移至 v4，并逐项核对书、快照、章节、正文版本、当前版本指针、正文/快照哈希；
- 对已有的生成审计、用量账本、故事圣经、大纲、记忆头和章节摘要执行迁移后计数与关键值核对；
- 每条迁移路径都执行 `PRAGMA integrity_check` 和 `PRAGMA foreign_key_check`；
- 使用生产 `EncryptedZhijuanDatabaseFactory` 与真实 SQLCipher/Keystore 包装口令完成加密 v1→v4，关闭后数据库与 WAL/SHM 中正文 canary 为 0；
- 人工构造未来 v5 加密库后，v4 App 会拒绝打开；随后以原版本重开仍能读取原小说，证明没有破坏性降级清库；
- 上述 4 项专项测试在 API 35 与 API 30 各执行一次，均通过。

参考：[Room 数据库迁移](https://developer.android.com/training/data-storage/room/migrating-db-versions)。

## 11. 网络安全

- 远程连接只允许 HTTPS；Android 9+ 默认禁用明文流量，织卷不全局放开。
- Ollama 的 HTTP 仅对用户明确配置的本机/局域网 host 建立窄范围策略。
- 禁止信任所有证书；证书错误必须如实显示。
- 默认禁止跨 host 重定向；所有跨 host 请求剥离鉴权头。
- 自定义头分敏感/非敏感，敏感头使用 secret 存储。
- URL 日志去除 query/fragment；请求体和响应体默认不记录。
- 导入模板中的 URL/请求头字段不执行。

### M0.9 自定义端点安全尖峰结论

- Release/Main 配置同时声明 `android:usesCleartextTraffic="false"` 和 `<base-config cleartextTrafficPermitted="false">`；API 35 设备策略查询对全局和远程 host 均返回不允许明文。
- 代码层再次校验 endpoint：远程仅 HTTPS；base URL 禁止内嵌用户名/口令、query 和 fragment，避免秘密混入 URL、历史记录或日志。
- 本机/私网 IP 的 HTTP 只在显式确认标记下通过领域校验，但当前平台配置仍会拒绝实际连接。ADR-006 未完成前不通过放开全局明文来“临时解决”。
- 不使用全信任 `TrustManager`、跳过 hostname 校验或“仍然连接”按钮。自签/错误证书标准化为 `TLS_FAILED`，自动重试为否。
- 自动连接重试关闭；同 origin HTTPS 的 GET/HEAD 可有限跟随，所有付费 POST 重定向、跨 origin、降级、循环和异常目标均失败关闭。
- 脱敏摘要只保留 method、去除 query/fragment 的 URL 和 header 名称集合，不包含任何 header 值、正文或密钥。

### TASK-021 当前安全传输基线

- 新增 `:provider:transport`，服务商 adapter 不直接持有 `AndroidSecretStore`、`OkHttpClient` 或 `EncryptedDiagnosticStore`；
- 端点只能从已校验的连接 base URL 加安全路径段构造，拒绝 traversal、绝对 URL 替换、URL 凭据和敏感 query 名；
- 公开 header 通道拒绝鉴权、cookie、token/secret/credential 命名及连接登记的敏感头；密钥只能通过用途匹配的 Secret Store lease 注入；
- API key lease 和正文输入在使用后清零；由于 HTTP header API 必须形成短生命周期字符串，含密钥的 OkHttp request 不返回业务层，响应中的 request 副本会移除密钥 header，并要求及时关闭 response lease；
- 所有付费 POST 重定向拒绝；跨 origin GET 在目标请求创建前拒绝；自动连接重试保持关闭；
- 同一 requestId 只允许一个活动请求，取消可覆盖等待响应头和流读取，重复取消不会创建新网络动作；
- 声明长度和未知长度流都受硬字节上限保护；响应只开放受限 header 白名单，cookie/鉴权响应头不开放；
- `CREDENTIAL_UNAVAILABLE` 与远端 `AUTH_FAILED` 分开，避免把本地 Keystore 故障误导成“API 密钥填写错误”；
- JVM 8 项、API 35/API 30 各 1 项真实 Keystore/HTTPS/加密诊断集成测试通过，真实凭据未使用。

参考：[Android Network Security Configuration](https://developer.android.com/privacy-and-security/security-config)。

## 12. 诊断与崩溃

- 默认采用本地脱敏诊断，不接第三方云崩溃平台。
- 错误事件包含状态码、适配器版本、阶段、时间和设备信息，不含正文和密钥。
- 导出前显示内容清单，用户可预览文本文件。
- 若以后接入远程崩溃服务，必须另立隐私评审和开关，不能默默加入。

### TASK-018 当前诊断基线

- `:core:diagnostics` 不提供自由文本日志入口，只接受事件码、类别、严重级别、操作、标准错误码、协议、HTTP 状态、布尔值和数值；
- 连接、endpoint、模型、书、任务、阶段和 attempt 等调用方输入只保存带域分离的 SHA-256 截断哈希，不保存原值；
- `Throwable` 只提取最多四层异常类型，不记录 message、stack trace 文本、cause message 或 suppressed message；
- 编码格式有版本、事件数、字段长度和总大小上限，畸形或尾随数据失败关闭；
- 本地记录默认最多 512 条/512 KiB，超限只淘汰最旧结构化事件；
- 记录复用 `AndroidProtectedArtifactStore`，使用独立 `DIAGNOSTIC_LOG` 类型和 Keystore key 加密、AtomicFile 换版；索引只含随机引用；
- 密文、索引或 key 不可用时返回 `FAILED_CLOSED`，绝不改写为 `.log`、`.txt` 或 JSONL 明文兜底；
- 4 项 JVM 测试与 API 35/API 30 各 3 项 Android 测试通过；解密后的编码记录和落盘文件中，敏感值/正文 canary 均为 0。

当前已完成安全事件、存储底座和 Provider HTTP 传输接线。数据库、备份和生成编排的正式事件接线仍在其后续任务完成；诊断包预览/导出属于 TASK-104，不能把当前描述为完整诊断界面。

## 12.1 外部模型数据边界

- 首次对某个远程 host 发真实生成请求前，显示目的地和数据类别：故事设定、相关摘要/正文片段、阶段要求。
- 连接测试使用固定无私人信息文本，不从书库抽样。
- 确认记录绑定 scheme/host/port/protocol；任一变化后失效。
- 外部服务是否保留、训练或人工审查数据由其条款和账户设置决定，织卷不能承诺代为删除。
- 本地删除小说不会自动删除已发送给模型服务的内容，也不会清除用户另存的旧备份和明文导出；删除确认页应说明这一点。
- TXT/Markdown 导出不加密，保存前显示简短风险提示；加密项目备份与明文阅读导出必须在命名和图标上明显区分。

## 13. 签名和更新

- 正式包名在首次 release 前确定，建议 `com.<个人命名空间>.zhijuan`，一旦安装使用后避免更改。
- `versionCode` 单调增加，`versionName` 使用语义版本。
- Android 更新要求新 APK 与已安装 App 使用兼容的同一签名证书；丢失自管理签名密钥会导致无法原位升级。
- 正式 keystore、密码和恢复说明做两份离线加密备份，放在不同介质；不提交代码仓库、不放项目备份包。
- 每次发布用已安装旧版执行覆盖安装，验证书、模板、密钥引用和阅读位置。
- 发布 APK 同时保存 SHA-256、版本、构建时间、Git 提交和迁移范围。

参考：[Android App Signing](https://developer.android.com/studio/publish/app-signing)、[How app updates work](https://developer.android.com/google/play/app-updates)。

## 14. 2026+ 开发者验证风险

Android 开发者验证在 2026–2027 分阶段实施，直接 ADB 安装目前有豁免路径，但设备侧安装规则仍可能变化。项目发布计划必须在每次大版本前复核官方状态；若只在少量个人设备安装，可评估官方的 limited distribution 方案，而不能在最后打包时才发现身份/包名问题。

参考：[Developer verification FAQ](https://developer.android.com/developer-verification/guides/faq)、[Limited distribution](https://developer.android.com/developer-verification/guides/limited-distribution)。

## 15. 安全发布闸门

- release APK 中无测试密钥、固定私人地址和调试证书信任；
- 模板/项目备份/诊断包密钥扫描为 0；
- 系统备份排除规则实机通过；
- 跨 host 重定向不发送秘密；
- 默认通知不显示书名、人物或正文；
- 从最老支持版本迁移成功且数据计数/哈希正确；
- 错误备份口令、损坏包、低空间恢复均不破坏当前库；
- 签名密钥双备份和覆盖安装演练完成。

## 16. 章前上下文的隐私边界（TASK-054）

- 上下文快照可能同时包含人物资料、情节事实、上一章摘要和用户临时补充，按正文同等级敏感数据处理，只保存在 SQLCipher 加密库中。
- 本地组装不调用 Provider，也不创建 RequestAttempt/Usage；候选项、已选正文、manifest 和 payload 禁止进入普通日志、崩溃信息或诊断包。
- Stage 的可观察字符串与 output reference 只暴露版本、ID、计数、预算和 hash，不回显实际人物资料或章节内容。
- Provider-open 只发送 manifest 明确选中的内容，并仍受当前 host 的外部数据目的地确认约束；host 改变后不能沿用旧确认。
- 加密备份可以随项目数据保存快照以保证恢复一致性，但默认不包含 API 密钥；系统自动备份排除规则继续覆盖数据库及其派生文件。

## 17. 章节续接的受保护数据边界（TASK-055）

- 父 Attempt 的累计正文只从受保护 artifact 解密到当前租约持有者内存，并立即写入新 Attempt 的独立加密 artifact；存储接口会在消费后清零传入字节数组，不建立明文中间文件。
- `chapter-draft.v1` 的 JSON 外壳只在增量解码器内存在；只有已完成解码且位于 `body` 字符串中的正文码点可以进入加密草稿。
- 续接提示中的保存尾窗和精确锚点属于敏感正文。普通对象字符串、错误消息、诊断事件、请求快照和工作报告不得输出其内容，只记录版本、长度、hash 和枚举结果。
- 锚点不匹配、非法转义、损坏代理项或 4 MiB 上限触发时失败关闭；不得把原始响应转储为 `.txt`/`.json`，也不得为了排错回退到未加密缓存。
- 崩溃恢复没有 Provider、连接读取或发送权限，只能对已经持久化的分类、artifact、hash 和 Usage 做本地结算；因此恢复本身不能泄露数据或新增费用。
- TASK-055 双 AVD 测试、Release/R8 和统一安全扫描通过；该结论不替代物理设备 Keystore/OEM 发布门禁。

## 18. 章节记忆的隐私与完整性边界（TASK-056）

- 冻结正文和提取结果属于敏感创作内容，只存在于 SQLCipher、受保护 artifact 和当前请求的可清理内存；普通日志、异常、对象字符串、output reference 和工作报告不得包含正文或摘要原文。
- 已知实体只发送完成提取所需的 ID、名称、类型和成年人状态；不得发送 API key、连接密文或模板私有运行数据。
- Provider 输出不能改变年龄、成年人状态、真实人物标记或稳定身份；未知实体和越权 Canon 失败关闭。
- 提交前验证受保护 artifact 的类型、修订、原始 hash 和规范 JSON hash；来源正式版本在联网前/提交前双重检查，阻断换版本后的陈旧请求。
- 当前安全扫描覆盖源码和 15 个 APK，0 个疑似密钥命中；真实 API 0，实体设备写入 0。物理设备/OEM 门禁仍保留。

## 19. 时间线与伏笔的隐私与完整性边界（TASK-057）

- Provider 只收到本次投影必需的冻结章节、同版本记忆、既有伏笔和故事实体；API key 仍只在安全传输出口短暂读取，不进入快照、输出或台账。
- 事件证据和伏笔证据限制为短文本并存入 SQLCipher；output reference、日志、异常和报告只记录 ID、hash、枚举和数量，不复制正文或证据原文。
- 参与者/地点/可见实体使用数据库白名单；模型不能借投影新增人物、修改年龄/成年人状态、改变真实人物标记或篡改稳定身份。
- Provider-open 与提交前均重验四组来源快照；已验证 artifact 还要复核类型、修订、原始 hash 与规范 JSON hash。任一变化都不能写入半套状态。
- schema v8 新表仍位于同一 SQLCipher 数据库，并继续被 Android 云备份/设备迁移排除。源码和 15 个 APK 安全扫描为 0 个疑似密钥命中；真实 API 0、实体设备写入 0。

## 20. 一致性检查的隐私与完整性边界（TASK-058）

- 候选正文只存在于受保护 artifact、SQLCipher 和当前可清理请求内存。普通日志、异常、对象字符串、报告文件及 `ConsistencyReport.issuesJson` 均不得复制正文或证据摘录。
- 模型输出只能引用 Unicode 码点范围和请求白名单中的实体/伏笔/过程节点 ID；不能通过自由 evidence/suggestion 字段把正文再次保存到报告。
- 请求规范绑定候选正文 hash、本地检查快照、场景契约、已知实体/证据及过程节点；持久 `inputHash` 不匹配时在 Provider 打开前失败关闭。
- 成年状态、年龄、真实人物标记和角色类型来自冻结结构化事实并独立复核；相关场景门禁失败不会降级发送。
- TASK-058 的统一扫描覆盖源码和 15 个现存 APK，疑似密钥 0；真实 API 0，实体设备安装/写入/设置变更 0。物理设备/OEM 门禁仍保留。

## 21. 外部数据目的地确认内核（TASK-064 Phase 2E4B）

- canonical destination 只表示接收数据的 origin；request path 不影响“发给谁”，protocol 作为独立绑定维度。
- 确认 hash 同时绑定 policy/disclosure version、scheme、host、effective port 和 protocol；不绑定 API Key、模型、书名或正文。
- 新连接默认未确认；连接测试继续使用固定无私人信息文本，不会借此写入 disclosure 接受事实。
- 接受事务以数据库当前 endpoint 为准并用 endpoint CAS，回读每次动态重算；数据库字段被修改或损坏时不会依赖 UI 清空才失效。
- `ConnectionProfileEntity`、binding 与 evidence 的字符串表示均不展开 base URL、密钥尾号或 binding hash。
- 该内核不是 Provider permit。预算、RequestIntent、exact lease 和一次性发送许可未完成前，真实小说请求继续关闭。
