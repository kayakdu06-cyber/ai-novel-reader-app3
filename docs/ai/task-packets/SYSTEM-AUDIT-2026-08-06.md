# 织卷 / 2026-08-06 当前系统第三次只读风险审计

## 任务身份

- 任务 ID：`SYSTEM-AUDIT-2026-08-06`
- 仓库根目录：`D:\gptuser\projects\ai-novel-reader-app2`
- 基准分支与 HEAD：`main` / `8ce7744`；只用于识别独立副本，不得回退工作树
- 当前现场：TASK-059、060 已按当前模块边界完成；TASK-061 进行中，至 Phase 2B3B1 schema v14 不可变执行准备账本完成；工作区有大量必须保留的未提交 WIP
- 执行模型：DeepSeek V4 Flash（纯文本、只读审计）

## 运行预算

- 推理等级：`max`
- 最长运行时间：20 分钟。此次跨 App、数据库、生成、Provider、安全、费用和发布边界，15 分钟不足，故记录 20 分钟。
- 累计 Token 上限：不设置；用户持续授权本项目不设总 Token 上限，仍受 20 分钟硬上限和单任务锁约束。
- 预计读取：不超过 55 个直接文件；允许用 `rg` 检索 `src/main` 生产调用点和 TODO/占位符，但不得递归读取报告全集、日志、会话、构建产物或其他项目。
- 预计命令：只读 `rg`、`Get-Content`、`git status --short`、`git diff --check`、`git remote -v`；禁止 Gradle、ADB、网络和写文件。
- 提前停止条件：需要读取密钥、访问其他项目、修改文件、运行真实 Provider、依赖视觉/物理设备判断、范围失控或运行时限耗尽。

## 目标

基于当前实际源码，而不是旧审计结论，对织卷做一次独立的系统偏差与重大漏洞复核。重点查找会导致丢书、正文/密钥泄露、重复付费、错误覆盖、不可恢复卡死、迁移失败、生产旁路、文档错误宣称或“看起来有模块但用户路径根本未接通”的问题。

必须区分：

1. 当前可达的已确认缺陷；
2. 接入真实小说生成或发布前必须完成的高风险门禁；
3. 正常未完成功能；
4. 已由 2026-08-05 后续工作修复的旧问题，不得重复误报。

## 当前现场与已知修复

- 2026-08-05 的工作汇报 82 曾发现安全扫描误报/漏扫、旧书 FTS 无回填、统一门禁不构建 Release。后续 TASK-060 和工作汇报 83/84 已完成：报告纳入源码扫描、4 项扫描脚本回归、真实 `assembleRelease`、源码与 5 APK 扫描、schema v10 按书回填及中断整体回滚。不得把这些旧问题按原状态重复报告；必须检查当前代码后再判断是否仍存在。
- TASK-059 的候选修订与 final commit 模块已完成，但项目文档持续声明 App 没有按 phase 分发的总 runner；需检查该声明是否准确、是否存在旁路或 UI 错误宣称。
- TASK-060 已完成 v9/v10 FTS、回填、三路召回、hydration、route selection、context/Provider-open 接线和双 API 固定中文集。
- TASK-061 schema v11～v14 已增加多代派生历史、伏笔 revision/rewind、aggregate writer 和不可变 execution/step 准备账本。Phase 2B3B1 不创建 Job/Stage/Attempt/Usage；动态 Stage、TEST-033 和总 runner 尚未完成。
- 最近统一离线门禁：797 actionable tasks，Debug/Release/Lint/R8/JVM、源码与 5 APK 安全扫描、备份排除通过；数据库 API 30/API 35 各 171/171。
- 当前没有 Git remote，HEAD 仍为早期绑定提交，存在大量未提交 WIP。

## 必读资料

1. `AGENTS.md`
2. `docs/24-AI-DEVELOPMENT-PROTOCOL.md`
3. `docs/ai/CURRENT-CONTEXT.md`
4. `docs/01-PRD.md`
5. `docs/03-USER-FLOWS.md`
6. `docs/06-AI-GENERATION-SYSTEM.md`
7. `docs/07-API-ADAPTER-SPEC.md`
8. `docs/08-TECHNICAL-ARCHITECTURE.md`
9. `docs/09-DATA-MODEL.md`
10. `docs/10-STATE-MACHINES.md`
11. `docs/11-SECURITY-PRIVACY-BACKUP.md`
12. `docs/13-ERROR-HANDLING.md`
13. `docs/14-COST-CONTROL.md`
14. `docs/15-TEST-PLAN.md`
15. `docs/16-RELEASE-PLAN.md`
16. `docs/17-ACCEPTANCE-CRITERIA.md`
17. `docs/19-IMPLEMENTATION-BACKLOG.md`
18. `docs/20-TRACEABILITY-MATRIX.md` 的 TASK-059～061 部分
19. `docs/22-WORK-STATUS.md`
20. `reports/2026-08-05-82-current-system-risk-audit.md`
21. `reports/2026-08-05-83-security-gate-remediation.md`
22. `reports/2026-08-05-84-task-060-legacy-index-backfill.md`
23. `reports/2026-08-06-97-task-061-phase-2b3b1-immutable-execution-ledger.md`
24. `app/src/main/AndroidManifest.xml`
25. `app/build.gradle.kts`
26. `app/src/main/kotlin/app/zhijuan/reader/ZhijuanApplication.kt`
27. `app/src/main/kotlin/app/zhijuan/reader/MainActivity.kt`
28. 用 `rg --files app/src/main` 定位 `ZhijuanApp`、创建页、书架、阅读器、连接向导和导航生产文件
29. `core/database/src/main/kotlin/app/zhijuan/core/database/ZhijuanDatabase.kt`
30. `core/database/src/main/kotlin/app/zhijuan/core/database/ZhijuanMigrations.kt`
31. `core/database/src/main/kotlin/app/zhijuan/core/database/LibraryDatabaseGuards.kt`
32. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationDao.kt`
33. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationRequestAuditRepository.kt`
34. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterFinalCandidateCommitRepository.kt`
35. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterGenerationCommitRepository.kt`
36. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationUnknownResultRecoveryRepository.kt`
37. `core/database/src/main/kotlin/app/zhijuan/core/database/search/MemorySearchBackfillRepository.kt`
38. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterContextAssemblyRepository.kt`
39. `core/database/src/main/kotlin/app/zhijuan/core/database/library/ChapterEditRebuildExecutionRepository.kt`
40. `core/database/src/main/kotlin/app/zhijuan/core/database/library/ChapterEditRebuildExecutionEntities.kt`
41. `feature/generation/src/main/kotlin/app/zhijuan/feature/generation/ChapterFinalCandidateCommitStageExecutor.kt`
42. `feature/generation/src/main/kotlin/app/zhijuan/feature/generation/AuditedStreamingProviderExecutor.kt`
43. `provider/transport/src/main/kotlin/app/zhijuan/provider/transport/SecureProviderHttpTransport.kt`
44. `core/security/src/main/kotlin/app/zhijuan/core/security/AndroidSecretStore.kt`
45. `core/security/src/main/kotlin/app/zhijuan/core/security/AndroidProtectedArtifactStore.kt`
46. `scripts/security-scan.ps1`
47. `scripts/test-security-scan.ps1`
48. `scripts/verify-build.ps1`

若路径名称变化，只能在同一模块用 `rg --files` 定位。不得读取 `.codex/deepseek-key.local`、环境变量、DeepSeek 日志或任何其他项目。

## 审计范围

允许：

- 只读分析生产代码、直接相关测试和文档。
- 用 `rg` 查找生产调用方、危险 API、TODO/FIXME/placeholder、`error("not implemented")`、明文数据库、破坏性迁移、未受控日志/重试/重定向、旧提交入口、预算引擎和用户路径。
- 核对 Git 可恢复性、迁移链、schema v14 ledger、Provider-open、Attempt/Usage、租约、未知结果、本地提交恢复、备份/导出组件和 Release 配置。
- 输出证据化结论与最小修复顺序。

禁止：

- 修改任何文件或状态；运行构建、测试、ADB、模拟器、网络或真实 Provider。
- 读取/输出 API Key、正文、Prompt、模型输出 JSON、secretRefId 或端点私密值。
- 把“阅读器/模板/总 runner 尚未完成”本身一律叫漏洞；只有当前产品状态错误宣称、存在可达旁路或会在接线后直接破坏数据/费用时才定级。
- 对 UI 像素、成人文本质量、真实设备或真实模型表现做无证据判断。

## 必查问题

1. 当前生产 UI 是否真的能开始小说生成；若不能，是否有文字或状态误导用户已经开始？
2. 是否存在总 phase runner、final executor/旧 commit repository 的生产调用方或旁路？
3. 三层预算是否已有数据库原子预留/结算，并且所有小说 Provider 请求都绕不过去；连接测试的真实付费例外是否仍有明确确认和硬上限？
4. COMMITTING/VALIDATING/RECOVERY_REQUIRED 的本地恢复是否仍可能卡死或重复请求；成功后 artifact 清理是否安全？
5. schema v1→v14 是否连续、fresh/migration guard 是否一致、是否存在 destructive fallback 或新增 ledger 的可伪造/不可推进风险？
6. Provider POST 的业务/HTTP 自动重试、重定向、未知结果和多个 worker 是否可能导致重复计费或密钥转发。
7. SQLCipher、Keystore、日志/异常/toString、普通文件、备份、导出组件、PendingIntent/Service/Receiver 是否有正文或密钥泄露路径。
8. TASK-060 回填/召回是否当前生产接线完整，API 30 隐式 AND 是否保持，FTS 是否保存原始中文/正文。
9. v14 prepared ledger 是否出现“永远只能 PREPARED、没有事件表”的明确未完成边界被文档误报为可执行，或是否有当前可达的数据裂缝。
10. 长篇至少 80/300/自定义章节、模板复制、边生成边阅读、书架/阅读器、手动备份恢复的实际完成度是否与正式状态文档相符。
11. 当前大量未提交 WIP、无 remote、HEAD 长期不变是否构成当前最现实的丢失/不可审查风险。
12. `docs/17-ACCEPTANCE-CRITERIA.md`、backlog、work status、current context 是否仍存在足以误导后续开发的完成状态冲突。

## 严重度与证据

- `S0`：当前可达的丢书、密钥/正文泄露、突破费用上限、错误覆盖或不可升级。
- `S1`：当前核心流程或恢复会失败/卡死/数据错误，或一接通当前已规划路径就会直接触发。
- `S2`：重要可靠性、防御纵深、工程可恢复性或状态误导问题。
- `S3`：维护性和低风险文档问题。

每个已确认问题必须给出：文件与行号、触发场景、为何现有门禁未阻止、最小修复方向、置信度。没有实际生产触发证据的问题放入“待 Sol 复核”，不得冒充确认漏洞。

## 验收标准

- [ ] 开始/结束 Git 状态一致，实际文件修改为 0。
- [ ] 明确当前能否自动生成、日常使用、发布和控制费用。
- [ ] 旧审计中已修复问题不会重复误报。
- [ ] S0/S1 有具体调用链，产品缺口与漏洞分开。
- [ ] 给出按风险和依赖排序的修复顺序。
- [ ] 不宣布 TASK-061 或整个 App 完成。

## 回交格式

1. `审计结论`
2. `已确认问题（S0→S3）`
3. `高风险未完成门禁`
4. `已验证安全边界`
5. `旧问题复核`
6. `产品完成度偏差`
7. `建议修复顺序`
8. `需要 Sol 复核`
9. `实际修改与验证`

只返回最终审计，不输出思考过程，不修改文件，不更新任务状态。
