# 织卷 / 2026-08-05 当前系统只读风险审计

## 任务身份

- 任务 ID：`SYSTEM-AUDIT-2026-08-05`
- 仓库根目录：`D:\gptuser\projects\ai-novel-reader-app2`
- 基准分支与 HEAD：`main` / `8ce774429da1c3f7139a221bc241c34d81a2efdd`
- 当前现场：TASK-059 已按模块边界收口；TASK-060 已完成 1A/1B，1C 尚未落地；工作区包含大量必须保留的未提交 WIP。
- 执行模型：DeepSeek V4 Flash（纯文本、只读审计）

## 运行预算

- 推理等级：`max`
- 最长运行时间：20 分钟。此次为跨模块安全审计，15 分钟不足以完成调用链核查，因此记录为 20 分钟。
- 累计 Token 上限：无。用户此前持续授权本项目 DeepSeek 任务不设置总 Token 上限；运行时限、项目隔离和单任务锁仍保留。
- 预计读取：约 45 个直接文件，另允许使用 `rg` 查询这些文件中符号的 `src/main` 生产调用点。
- 预计命令：只读搜索、读取、`git status --short`、`git diff --check`；禁止 Gradle、ADB、网络和写文件。
- 提前停止条件：需要读取密钥、需要访问其他项目、范围需要扩大到图片/物理设备、发现无法用当前源码证明的猜测，或运行时限耗尽。

## 目标

对当前实际源码和未提交 WIP 做一次独立、证据化的静态风险审计，找出可能造成丢书、密钥或正文泄露、重复付费、并发重复提交、状态机卡死、数据库升级失败、错误恢复覆盖数据或 Android 发布配置泄露的真实缺陷。只报告能指向具体文件和触发路径的问题；不要把明确列入 backlog 且尚未接通的产品功能笼统称为漏洞。

## 当前现场与已有 WIP

- 现有安全底座包括 SQLCipher、Keystore Secret Store、受保护 artifact、发送前持久审计、租约、未知结果保守恢复、严格 Provider 适配器和系统备份排除。
- TASK-059 增加候选修订、恢复、最终本地协调器和 COMMIT_CHAPTER 专用执行入口；当前没有总 runner。
- TASK-060 1A/1B 增加 v9 FTS4 外部内容索引、六类搜索文档 factory/writer 和生产事务接线；旧书首次回填、多路召回和固定中文召回集尚未完成。
- 工作区相对 HEAD 有大量修改和新增文件。不得清理、回退、提交或覆盖。

## 必读资料

1. `AGENTS.md`
2. `docs/24-AI-DEVELOPMENT-PROTOCOL.md`
3. `docs/ai/CURRENT-CONTEXT.md`
4. `docs/11-SECURITY-PRIVACY-BACKUP.md`
5. `docs/13-ERROR-HANDLING.md`
6. `docs/14-COST-CONTROL.md`
7. `docs/15-TEST-PLAN.md`
8. `docs/17-ACCEPTANCE-CRITERIA.md`
9. `docs/19-IMPLEMENTATION-BACKLOG.md`
10. `docs/22-WORK-STATUS.md`
11. `reports/2026-08-04-79-task-059-compatibility-and-closure.md`
12. `reports/2026-08-04-80-task-060-production-fts-schema.md`
13. `reports/2026-08-04-81-task-060-atomic-production-indexing.md`
14. `app/src/main/AndroidManifest.xml`
15. `app/build.gradle.kts`
16. `app/src/main/kotlin/app/zhijuan/reader/ZhijuanApplication.kt`
17. `app/src/main/kotlin/app/zhijuan/reader/MainActivity.kt`
18. `core/database/src/main/kotlin/app/zhijuan/core/database/ZhijuanDatabase.kt`
19. `core/database/src/main/kotlin/app/zhijuan/core/database/ZhijuanMigrations.kt`
20. `core/database/src/main/kotlin/app/zhijuan/core/database/EncryptedZhijuanDatabaseFactory.kt`
21. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationDao.kt`
22. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationRequestAuditRepository.kt`
23. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterFinalCandidateCommitRepository.kt`
24. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterGenerationCommitRepository.kt`
25. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterFinalCandidateRecoveryRepository.kt`
26. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterCandidateArtifactSealRepository.kt`
27. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterContextAssemblyRepository.kt`
28. `core/database/src/main/kotlin/app/zhijuan/core/database/search/MemorySearchDao.kt`
29. `core/database/src/main/kotlin/app/zhijuan/core/database/search/MemorySearchDocumentEntity.kt`
30. `core/database/src/main/kotlin/app/zhijuan/core/database/search/MemorySearchDocumentFactory.kt`
31. `core/database/src/main/kotlin/app/zhijuan/core/database/search/MemorySearchIndexWriter.kt`
32. `core/database/src/main/kotlin/app/zhijuan/core/database/search/SearchIndexText.kt`
33. `feature/generation/src/main/kotlin/app/zhijuan/feature/generation/ChapterFinalCandidateCommitCoordinator.kt`
34. `feature/generation/src/main/kotlin/app/zhijuan/feature/generation/ChapterFinalCandidateCommitStageExecutor.kt`
35. `feature/generation/src/main/kotlin/app/zhijuan/feature/generation/ChapterFinalCandidateArtifactRecoveryCoordinator.kt`
36. `feature/generation/src/main/kotlin/app/zhijuan/feature/generation/ChapterCandidateConsistencyRoutingCoordinator.kt`
37. `feature/generation/src/main/kotlin/app/zhijuan/feature/generation/AuditedStreamingProviderExecutor.kt`
38. `provider/transport/src/main/kotlin/app/zhijuan/provider/transport/SecureProviderHttpTransport.kt`
39. `core/security/src/main/kotlin/app/zhijuan/core/security/AndroidSecretStore.kt`
40. `core/security/src/main/kotlin/app/zhijuan/core/security/AndroidProtectedArtifactStore.kt`
41. `scripts/verify-build.ps1`
42. `scripts/verify-no-secrets.ps1`（若名称不同，用 `rg --files scripts` 定位唯一安全扫描脚本）
43. `core/database/src/androidTest/kotlin/app/zhijuan/core/database/ChapterFinalCandidateCommitDatabaseTest.kt`
44. `core/database/src/androidTest/kotlin/app/zhijuan/core/database/MemoryDatabaseTest.kt`
45. `core/database/src/androidTest/kotlin/app/zhijuan/core/database/ZhijuanMigrationTest.kt`

如果清单中的路径因实际命名不同而不存在，只能在同一模块使用 `rg --files` 定位对应生产文件；不得递归读取其他项目、备份、日志、会话或 `.codex` 密钥文件。

## 审计范围

允许：

- 读取当前仓库源码、测试和上述文档。
- 使用 `rg` 核查危险 API、日志、明文 Room 打开、破坏性迁移、网络重试/重定向、未受控 `generate()`、旧提交入口的生产调用、FTS 删除/替换和 final commit 调用链。
- 运行 `git status --short` 与 `git diff --check`。
- 输出只读审计结论。

明确不在范围：

- 修改任何源码、测试、文档、配置或任务状态。
- 运行 Gradle、Android 工具、ADB、模拟器、真实 Provider、联网探测或任何付费接口。
- 读取、显示或搜索 `.codex/deepseek-key.local`、环境变量、C 盘内容、日志中的密钥或其他项目。
- 对成人内容质量、图片、UI 像素、物理设备或真实模型效果做猜测。

## 不可破坏的约束

- 项目隔离：只能访问 `D:\gptuser\projects\ai-novel-reader-app2`。
- 实际文件写入必须为 0；不得用 shell、PowerShell、Python、.NET、重定向或补丁工具写文件。
- 不调用“织卷”App 内部真实 API，不产生费用。
- 不把尚未实现的总 runner、三层预算、目的地确认、模板 UI、备份恢复、阅读器等规划功能直接定为代码漏洞；只有当当前可达生产路径错误地宣称、绕过或暴露它们时才定级。
- 不把测试数量当作安全证明；必须说明触发路径、受影响数据和当前测试是否覆盖。
- 不输出正文、Prompt、JSON 原文、API Key、端点、secretRefId 或其他敏感值。

## 审计方法与严重度

每个发现必须包含：

1. 严重度：`S0`（可丢书/泄密/突破预算/错误覆盖/无法升级）、`S1`（核心生成或恢复不可用/数据错乱）、`S2`（主要可靠性或防御纵深缺口）、`S3`（文档/维护性问题）。
2. 精确文件与行号。
3. 可复现的调用/状态/迁移场景。
4. 为什么现有门禁或测试没有阻止它。
5. 最小修复方向，但不要写补丁。
6. 置信度：高/中/低。低置信度不得列入最终“确认漏洞”，只能列为待 Sol 复核。

必须重点核查：

- 是否存在生产明文数据库打开或 `fallbackToDestructiveMigration`。
- Android Manifest、备份、导出组件、PendingIntent、Service/Receiver 暴露和 Release debug 配置。
- 密钥、正文、请求/响应、服务端 error message 是否进入日志、异常、`toString`、Room 非加密字段或普通文件。
- Provider POST 是否可能被 OkHttp/业务重试、重定向、重复 runner 或未知结果错误地重放。
- 预算未接线时，当前 UI 或生产 runner 是否存在真实付费调用可达路径。
- Job/Stage/Attempt/Usage 的专用事务是否存在生产旁路。
- final candidate 恢复/提交的 owner、租约、时间、hash、artifact、来源链和 replay 是否有 TOCTOU 或跨书/跨章缺口。
- v1→v9 迁移完整性、v9 FTS 表/触发器、外部内容 FTS 同步、书删除/章节替换/旧书无索引的行为。
- FTS token 文档是否可能保存原始中文、JSON 或过量内容；API 30 隐式 AND 不得被误改为显式 AND。
- 取消、进程死亡、COMMITTING 恢复、Stage 成功后 artifact 清理的边界。
- 当前大规模未提交 WIP 是否导致“报告称已通过、但源码/测试/构建现场尚未形成可恢复版本”的工程风险。
- 文档完成状态是否互相矛盾到足以误导后续开发或发布。

## 验收标准

- [ ] 实际文件修改为 0；开始/结束 `git status --short` 一致。
- [ ] S0/S1 发现均有具体生产触发路径和文件行号；没有证据则明确写“未确认”。
- [ ] 区分“已确认缺陷”“高风险未完成门禁”“防御纵深建议”“文档状态不一致”。
- [ ] 明确说明当前系统能否发布/日常使用、是否能真实自动生成、是否具备费用硬限制。
- [ ] 不宣布 TASK-060 或整个项目完成。

## 回交格式

1. `审计结论`
2. `已确认缺陷（按 S0→S3）`
3. `高风险未完成门禁`
4. `安全边界检查结果`
5. `误报排除`
6. `建议修复顺序`
7. `需要 Sol 复核`
8. `实际修改与验证`

