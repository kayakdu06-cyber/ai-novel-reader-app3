# app开发3 精简迁移与模块化整改记录

> 日期：2026-08-11
> 唯一项目：`D:\gptuser\projects\ai-novel-reader-app3`
> 状态：Phase 1–8 已完成；最终产物已验证

## 1. 输入与基线

| 输入 | 只读来源 / 基线 | SHA-256 / 提交 |
|---|---|---|
| app开发2 Git 历史 | `D:\gptuser\projects\ai-novel-reader-app2` | `89ec64da146b27d6186ef71c32c5b1a565e2f52c` |
| 三模块精简包 | `C:\Users\du\Downloads\ai-novel-reader-slim (2).zip` | `66b93aab228c6eb1da2bc1a37eefc1a37ecf8edd7131d20ca722b13dd933bbbc` |
| 权威模块化方案 | `C:\Users\du\Downloads\MODULARIZATION-PLAN.md` | `5be15db1f6b0360794610ff3d24fd1b36a0a649646b0c530250a53d2a2810139` |

精简包顶层为 `ai-novel-reader-slim`，共 623 个 ZIP 条目；输入项目模块为 `:app / :engine / :data`。

## 2. 方案矛盾裁决

`MODULARIZATION-PLAN.md` 多处写“11 模块”，但明确目录树、职责章节和目标列表只列出十个模块：`core、data、provider、connection、creation、generation、reader、library、template、app`。

本次按明确列出的十模块执行，原因是：

- 不凭空创造职责不明的第十一个模块；
- 方案把 `generation-core + generation-service` 明确标为未来可选拆分，不是本次必做模块；
- 目标是形成真实编译边界，不用空目录或 sourceSets 回指旧目录凑数。

唯一允许的 feature 到 feature 依赖为 `:feature:template -> :feature:creation`；其余 feature 不得直接依赖其他 feature 实现。

## 3. 精简包 CHANGELOG 审查

### Step 5：安全层

- CHANGELOG 声称安全层 11 个文件都有引用，因此没有删除。
- 本次继续保留安全层并迁入 `:data`；不得为了模块数量破坏 Keystore、加密 envelope、受保护工件或流式草稿链。

### Step 6：生成流水线

- CHANGELOG 声称删除七个未引用文件。
- 上传包实际源树和 app2 基线的差异将以 Git diff、编译和测试共同判断；不能只相信“无引用”文字结论。
- 其中 runner registry、heartbeat 等同名能力在 app2 后期曾重新实现，精简包是否删除正确必须由三模块基线构建和模块迁移后的测试证明。

## 4. app2 到 app3 的导入变化

- 使用本地无硬链接 Git clone 保留 app2 全部提交历史。
- 删除仅限 app3 工作树中的旧文件，保留 `.git`，再同步精简包顶层内容。
- 移除克隆生成的 app2 本地 remote，避免任何 push 写回 app2。
- app2 旧交接文档改名并标记为历史；app3 新建独立 `CURRENT-CONTEXT.md`。
- 当前生效的 `.codex`、DeepSeek 启动器、安全扫描器、AI 协作规程和任务模板已改绑 app3 路径；历史 reports/task-packets 不篡改。

## 5. Phase 1–8 记录

| Phase | 目标 | 迁移/实现 | 依赖边界 | 验证 | Git |
|---|---|---|---|---|---|
| 0 | 导入精简包和独立绑定 | 保留 app2 Git 历史，导入 ZIP；恢复 8 个被误删但仍被生产代码引用的 Repository/Codec；补 JVM HTTP 测试依赖；把已不存在的协议枚举用例改为模型错配/非流式拒绝 | app3 与 app2 隔离；仍为原始 `app/engine/data` 依赖 | `assembleDebug test` 成功，116 tasks；JVM 123/123 | `c2456dc` |
| 1 | `:core` | 从 `data` 实体迁移 13 个 model 与 13 个 task/状态机文件；新增纯 Kotlin/JVM 17 模块 | 无 Android import、无项目依赖；app/data/engine 显式依赖 core | `:core:test assembleDebug test` 成功，118 tasks；JVM 123/123 | `e3ddc46` |
| 2 | `:provider` | 迁移 common、capability-storage、OpenAI Chat、stream、transport、Fake Provider 及其 JVM/Android 测试，共 46 个 Kotlin 文件 | 仅依赖 `core + data`；app/engine 显式依赖 provider；旧三模块中 provider 文件为 0 | `:provider:test assembleDebug test` 成功，153 tasks；JVM 123/123 | `eb8ebc3` |
| 3 | `:feature:connection` | 迁移连接网关/持久 Repository、连接列表、连接向导与首次说明，共 5 个生产文件；模块内保留网关安全和向导 UI 测试，跨功能导航测试留在 app | 仅 `core + data + provider`；Hilt 绑定在功能模块编译；共享数据库名下沉 data | `:feature:connection:test assembleDebug test` 成功，190 tasks；JVM 123/123 | `9bb6296` |
| 4 | `:feature:creation` | 迁移创建网关、模型/标准化、极简创建与费用确认，共 5 个生产文件、2 个 JVM 测试、2 个 Android UI 测试 | 仅 `core + data`；创建入口改收最小 `CreationConnectionSelection`，由 app 映射连接快照，消除 creation→connection | `:feature:creation:test assembleDebug test` 成功，231 tasks；JVM 123/123 | `afc709a` |
| 5 | `:feature:generation` | 真实移动原 `engine`，并合入前台 Service、通知、控制网关、恢复/维护 Worker；40 个生产文件、2 个 JVM 测试、4 个 Android 测试 | 仅 `core + data + provider`；服务/权限/通知资源由模块自持；打开 App 的 PendingIntent 不引用壳实现 | `:feature:generation:test assembleDebug test` 成功，239 tasks；JVM 123/123 | `0688a33` |
| 6 | 跨模块契约 | `core.contract` 新增连接选择、生成控制、书架三类稳定 DTO/interface；连接与生成模块用 Hilt `@Binds` 绑定真实实现；新增 2 个 core 契约测试 | core 契约无 Android/Room/Provider 类型；功能实现不进入 app；Hilt 聚合编译通过 | `:core:test :feature:connection:test :feature:generation:test assembleDebug test` 成功，238 tasks；JVM 125/125 | `0b493f0` |
| 7 | reader/library/template | 新增三个真实 Android Library；reader 可读取已完成章节并控制生成暂停/停止；library 通过加密 Room 的只读门面实现 core Repository；template 读取当前版本、来源与分类并复用 creation 草稿生成“按此重开”输入 | reader/library 仅 `core + data`；template 仅 `core + data + creation`，是唯一 feature 例外；DAO 不泄漏到 feature | 三模块独立测试及整仓 `assembleDebug test` 成功，361 tasks；JVM 130/130 | `1f3f144` |
| 8 | app 纯壳 | app 生产代码只保留 Application、Activity、Compose 导航组装和主题；Activity 注入连接/创建接口而非实现；新增可重复执行的模块边界校验脚本 | app 的 production `implementation(project(...))` 仅为 6 个 feature；旧调试探针所需 core/data 限定为 `debugImplementation`；Android 集成测试的 core/provider 限定为 `androidTestImplementation` | 强制重跑 `assembleDebug test --rerun-tasks` 成功，361/361 tasks；JVM 130/130；边界审计无环 | `0e7cfa7` |

## 6. 最终构建与测试

- `assembleDebug`：成功
- `test`：成功
- 强制重跑：`gradlew.bat assembleDebug test --rerun-tasks`
- 构建结果：`BUILD SUCCESSFUL in 1m 27s`，361 actionable tasks，361 executed
- 测试结果：22 个 JVM suite，130 tests，0 failures，0 errors，0 skipped
- 依赖无环：通过，明确模块数为 10
- feature 实现依赖审计：通过；唯一例外为 `:feature:template -> :feature:creation`
- app 生产依赖：只依赖 6 个 feature
- 安全扫描：源码与最终 APK 均通过，扫描 1 个 APK
- Kotlin/Kotlin DSL 代码量：271 个受 Git 跟踪文件，60,576 行
- 最终日志：`D:\gptuser\logs\ai-novel-reader-app3\final-build-test.log`
- 边界日志：`D:\gptuser\logs\ai-novel-reader-app3\module-boundaries-final.log`
- 安全日志：`D:\gptuser\logs\ai-novel-reader-app3\security-final.log`

### Phase 0 基线证据

- ZIP 原样导入首次无法编译，原因是 Step 6 的“未引用”判断错误；生产代码仍引用被删类型。
- 从 app2 未修改源文件只读恢复：`ChapterProgressionGateRepository.kt`、`PromptBundleBindingRepository.kt`、`ChapterContextAssemblyJobFactory.kt`、`ChapterMemoryExtractionJobFactory.kt`、`GenerationRequestAuditRepository.kt`、`PersistentBudgetReservationRepository.kt`、`ForeshadowProjectionRewindRepository.kt`、`DiagnosticEventCodec.kt`。
- `app` JVM 测试补入现有 version catalog 已声明的 `mockwebserver3` 与 `okhttp-tls`。
- `ProviderProtocol` 在精简包只剩 `OPENAI_CHAT_COMPAT`，因此不再伪造其他协议；契约测试保留模型快照错配，Fake Provider 增加模型错配前置拒绝，并保留非流式拒绝。
- 日志：`D:\gptuser\logs\ai-novel-reader-app3\phase0-final.log`。

### Phase 7 新功能边界证据

- `LibraryReadStore` 和 `TemplateReadStore` 只暴露稳定只读数据，Room DAO 继续保持 data 内部可见。
- 阅读器对尚未提交正文的章节返回 `Pending`，不会把流式碎片或空文本伪装成可读成品；已经完成的章节可立即打开，不等待后续章节。
- 模板重开结果保留 `templateId/revisionId/revisionNo/origin/sourceBook/categories/contentHash`；篇幅不符合短篇 80、中篇 300、长篇 301–10000 规则时直接拒绝，不静默缩水。
- 日志：`D:\gptuser\logs\ai-novel-reader-app3\phase7.log`。

### Phase 2 测试稳定性修复

`SecureProviderHttpTransportTest` 原用例假定“第一次 cancel 返回后，第二次 cancel 必然早于后台请求清理”，存在真实线程竞态。现在仍严格要求第一次为 `CANCELLATION_REQUESTED`、请求最终为 Cancelled、最终状态为 `NOT_ACTIVE`，只允许竞态窗口内第二次返回 `ALREADY_REQUESTED` 或已经清理后的 `NOT_ACTIVE`；未修改生产取消逻辑。

## 7. APK

- 路径：`D:\gptuser\projects\ai-novel-reader-app3\app\build\outputs\apk\debug\app-debug.apk`
- 大小：47,278,936 bytes
- SHA-256：`692bb864c4aab2c83706ea322c06d142329fb14963c9a22770a054524793332b`

## 8. 备份

- 备份 ZIP：`D:\gptuser\backups\ai-novel-reader-app3\2026-08-11\ai-novel-reader-app3-2026-08-11-verified.zip`（封装中）
- SHA-256：待填写
- 可读性/恢复校验：待执行
- 排除项：`.gradle`、`.kotlin`、所有模块 `build`、本地密钥、`local.properties`、签名文件

## 9. Git 同步

- 公开远端：`https://github.com/kayakdu06-cyber/ai-novel-reader-app3`
- 本地 `main` 已设置跟踪 `origin/main`。
- Phase 0–8 的逐阶段提交已推送；备份哈希元数据将在 ZIP 生成后另行提交并推送。

## 10. 未完成风险

- 本次完成的是十模块架构整改和可编译的 reader/library/template 最小功能边界，不等于阅读器、书架和模板的全部产品 UI 已经完成。
- app 的旧 M0 调试探针仍直接使用 data/core，但仅存在于 `src/debug`，不会扩大 release 生产代码依赖；后续可迁入专门测试夹具模块。
- 各业务功能仍各自持有加密数据库 handle，符合迁移前既有模式但不是最终理想生命周期管理；后续应建立进程级共享数据库提供器。
- 不调用真实 Provider，因此真实内容质量、费用和端到端网络行为不在本次验收范围。
