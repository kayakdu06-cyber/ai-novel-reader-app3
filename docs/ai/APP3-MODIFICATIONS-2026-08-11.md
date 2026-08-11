# app开发3 精简迁移与模块化整改记录

> 日期：2026-08-11
> 唯一项目：`D:\gptuser\projects\ai-novel-reader-app3`
> 状态：执行中；本文件随每个 Phase 更新

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
| 0 | 导入精简包和独立绑定 | 保留 app2 Git 历史，导入 ZIP；恢复 8 个被误删但仍被生产代码引用的 Repository/Codec；补 JVM HTTP 测试依赖；把已不存在的协议枚举用例改为模型错配/非流式拒绝 | app3 与 app2 隔离；仍为原始 `app/engine/data` 依赖 | `assembleDebug test` 成功，116 tasks；JVM 123/123 | 待提交 |
| 1 | `:core` | 从 `data` 实体迁移 13 个 model 与 13 个 task/状态机文件；新增纯 Kotlin/JVM 17 模块 | 无 Android import、无项目依赖；app/data/engine 显式依赖 core | `:core:test assembleDebug test` 成功，118 tasks；JVM 123/123 | 待提交 |
| 2 | `:provider` | 迁移 common、capability-storage、OpenAI Chat、stream、transport、Fake Provider 及其 JVM/Android 测试，共 46 个 Kotlin 文件 | 仅依赖 `core + data`；app/engine 显式依赖 provider；旧三模块中 provider 文件为 0 | `:provider:test assembleDebug test` 成功，153 tasks；JVM 123/123 | 待提交 |
| 3 | `:feature:connection` | 迁移连接网关/持久 Repository、连接列表、连接向导与首次说明，共 5 个生产文件；模块内保留网关安全和向导 UI 测试，跨功能导航测试留在 app | 仅 `core + data + provider`；Hilt 绑定在功能模块编译；共享数据库名下沉 data | `:feature:connection:test assembleDebug test` 成功，190 tasks；JVM 123/123 | 待提交 |
| 4 | `:feature:creation` | 待执行 | `core + data` | 待执行 | 待提交 |
| 5 | `:feature:generation` | 待执行 | `core + data + provider` | 待执行 | 待提交 |
| 6 | 跨模块契约 | 待执行 | 契约在 core，功能模块实现 | 待执行 | 待提交 |
| 7 | reader/library/template | 待执行 | template→creation 为唯一例外 | 待执行 | 待提交 |
| 8 | app 纯壳 | 待执行 | app 组装全部 feature | 待执行 | 待提交 |

## 6. 最终构建与测试

待完成后填写：

- `assembleDebug`：待执行
- `test`：待执行
- 测试数量与结果：待统计
- 依赖无环：待验证
- feature 实现依赖审计：待验证
- 安全扫描：待执行

### Phase 0 基线证据

- ZIP 原样导入首次无法编译，原因是 Step 6 的“未引用”判断错误；生产代码仍引用被删类型。
- 从 app2 未修改源文件只读恢复：`ChapterProgressionGateRepository.kt`、`PromptBundleBindingRepository.kt`、`ChapterContextAssemblyJobFactory.kt`、`ChapterMemoryExtractionJobFactory.kt`、`GenerationRequestAuditRepository.kt`、`PersistentBudgetReservationRepository.kt`、`ForeshadowProjectionRewindRepository.kt`、`DiagnosticEventCodec.kt`。
- `app` JVM 测试补入现有 version catalog 已声明的 `mockwebserver3` 与 `okhttp-tls`。
- `ProviderProtocol` 在精简包只剩 `OPENAI_CHAT_COMPAT`，因此不再伪造其他协议；契约测试保留模型快照错配，Fake Provider 增加模型错配前置拒绝，并保留非流式拒绝。
- 日志：`D:\gptuser\logs\ai-novel-reader-app3\phase0-final.log`。

### Phase 2 测试稳定性修复

`SecureProviderHttpTransportTest` 原用例假定“第一次 cancel 返回后，第二次 cancel 必然早于后台请求清理”，存在真实线程竞态。现在仍严格要求第一次为 `CANCELLATION_REQUESTED`、请求最终为 Cancelled、最终状态为 `NOT_ACTIVE`，只允许竞态窗口内第二次返回 `ALREADY_REQUESTED` 或已经清理后的 `NOT_ACTIVE`；未修改生产取消逻辑。

## 7. APK

- 路径：待生成
- 大小：待填写
- SHA-256：待填写

## 8. 备份

- 备份 ZIP：待生成
- SHA-256：待填写
- 可读性/恢复校验：待执行
- 排除项：`.gradle`、`.kotlin`、所有模块 `build`、本地密钥、`local.properties`、签名文件

## 9. 未完成风险

- 模块迁移尚未完成，不能把当前三模块输入描述为十模块成品。
- 新 reader/library/template 只能在本任务范围内提供真实可编译的最小功能边界，不等于完整产品功能已经实现。
- 不调用真实 Provider，因此真实内容质量、费用和端到端网络行为不在本次验收范围。
