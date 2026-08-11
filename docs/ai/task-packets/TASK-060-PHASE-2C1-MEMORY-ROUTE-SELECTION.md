# TASK-060 / Phase 2C1：强制、最近与 FTS 权威记忆路线选择提案

## 任务身份

- 任务 ID：`TASK-060 / Phase 2C1`
- 仓库根目录：`D:\gptuser\projects\ai-novel-reader-app2`
- 基准分支与 HEAD：`main` / `8ce7744`；不得回退，继续当前 dirty WIP。
- 当前未提交改动：TASK-059 完整 WIP、TASK-060 Phase 1A–1C、2A、2B1、2B2、隔离脚本、测试与报告均未提交；必须全部保留。
- 执行模型：DeepSeek V4 Flash（纯文本，只读审计与补丁提案）。

## 运行预算

- 推理等级：`max`。
- 最长运行时间：20 分钟。理由：需要同时审计旧 TASK-054 上下文选择、六类 FTS hydration 与长期小说的强制/最近路线，判断去重和优先级后才能提出局部补丁。
- 累计 Token 上限：无总上限（用户持续授权）；仍受 20 分钟硬上限。
- 预计读取文件：14 个以内，仅限下方清单和直接类型定义。
- 预计只读命令：最多 8 个。
- 提前停止条件：需要 schema/migration、必须重写整个 TASK-054、需要改变 Provider/网络、无法在不丢强制事实的前提下有界选择，或范围必须扩展到其他模块。

## 目标

先审计，再提出一个最小生产补丁，为章前上下文建立单独、可测试的记忆路线选择层：

1. 强制路线：当前硬事实与已经到期的未解决伏笔不能依赖 FTS 偶然命中；
2. 最近路线：上一章与固定最近摘要不能因搜索词不同而消失；
3. 相关路线：复用 Phase 2B1/2B2 的 FTS recall + authoritative hydration；
4. 三路按 `(sourceType, sourceId)` 去重，强制 > 最近 > FTS，保留明确路由/命中/遗漏/索引重建证据；
5. 本阶段只产出权威、已排序、可交给上下文映射层的选择结果，不修改现有 `chapter-context-manifest.v1`、不调用 Provider、不直接接入 `ChapterContextAssemblyRepository`。

最终应给 Sol 可审查的完整 unified diff 提案；不得写仓库。

## 已确认的现状和严重风险

- `MemorySearchRecallRepositoryV1` 已有 32/16/16 查询配额、每探针 16、最终 128、固定排序和查询指纹。
- `MemorySearchHydrationRepositoryV1` 已在最多六次批量查询内重读六类权威行，旧 Bible/旧章节/归档/已解决/hash 不匹配只剔除并返回重建标记。
- 旧 `ChapterContextAssemblyRepository` 尚未使用 FTS。它直接读取最多 512 facts、8 summaries、512 events、256 timelines、128 foreshadows。
- 旧代码把 `HARD_CANON` 和 `STORY_CANON` 都映射成必需 `BIBLE_HARD_FACT`，长篇中会把大量章节事实变成不可裁剪内容；同时 facts 查询按最早创建排序并截断 512，可能既阻塞预算又漏掉更相关的新事实。
- 旧 `activeForeshadowsForContext` 先按 importance/updated 截断 128，再判断是否到期；低重要度但已到期的伏笔可能被静默挤掉。该查询也没有验证来源章节仍为 current、来源章 `< targetChapterIndex`。
- 旧最近摘要查询已经验证 current version 与 `< target`，最近 8 章方向正确；上一章在预算策略中是必需项。
- 初始 Story Bible `hardFacts` 最多 256；章节记忆提取只能产生 `STORY_CANON/INFERRED`，不能产生 `HARD_CANON`。
- 现有 `ChapterContextBudgetPolicyV1` 最多接收 2,048 candidates；必需项不可裁剪，可选项整项省略。

## 必读资料

1. `AGENTS.md`
2. `docs/24-AI-DEVELOPMENT-PROTOCOL.md`
3. `docs/ai/CURRENT-CONTEXT.md`
4. `docs/06-AI-GENERATION-SYSTEM.md` 第 8、23 节
5. `docs/09-DATA-MODEL.md` 记忆、章节版本与上下文部分
6. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterContextAssemblyRepository.kt`
7. `core/task/src/main/kotlin/app/zhijuan/core/task/ChapterContextBudgetPolicy.kt`
8. `core/database/src/main/kotlin/app/zhijuan/core/database/memory/MemoryDao.kt`
9. `core/database/src/main/kotlin/app/zhijuan/core/database/memory/MemorySearchBackfillRows.kt`
10. `core/database/src/main/kotlin/app/zhijuan/core/database/search/MemorySearchRecallRepository.kt`
11. `core/database/src/main/kotlin/app/zhijuan/core/database/search/MemorySearchHydrationRepository.kt`
12. `core/database/src/main/kotlin/app/zhijuan/core/database/search/MemorySearchDocumentFactory.kt`
13. `core/database/src/androidTest/kotlin/app/zhijuan/core/database/MemorySearchHydrationDatabaseTest.kt`
14. `core/database/src/androidTest/kotlin/app/zhijuan/core/database/ChapterContextAssemblyDatabaseTest.kt`

不要读取历史报告、日志、密钥、其他项目或 C 盘。

## 建议范围

允许提议修改：

- `core/database/src/main/kotlin/app/zhijuan/core/database/memory/MemoryDao.kt`
- `core/database/src/main/kotlin/app/zhijuan/core/database/memory/MemorySearchBackfillRows.kt`（仅无 schema 的查询投影）
- 新建 `core/database/src/main/kotlin/app/zhijuan/core/database/search/MemoryContextRouteSelectionRepository.kt`

明确不在范围：

- 不修改 Room entity/schema/migration。
- 不修改 Phase 2A/2B1 排序、配额、指纹或 Phase 2B2 权威比较。
- 不修改 `ChapterContextAssemblyRepository`、`ChapterContextBudgetPolicy`、manifest/schema/Provider payload。
- 不写测试、状态文档或报告。
- 不接 Stage、runner、UI、Provider、网络或真实生成 API。

## 不可破坏的约束

- 单书、目标章 `>=1`，章节来源必须为 current 且 `< targetChapterIndex`。
- `HARD_CANON` 强制来源只能是当前 Bible 或合法当前章节来源；不得把所有 `STORY_CANON` 自动升级成强制项。
- 到期伏笔定义保持旧行为：`targetStartChapterIndex <= target` 或 `targetEndChapterIndex <= target`；必须仍为 `VALID`、未 `RESOLVED/ABANDONED`，来源章为空或 current 且 `< target`。
- 上一章摘要必须能被最近路线保留；固定最近窗口最多 8 条，必须 current 且 `< target`。
- 强制来源不能被可选来源挤掉或静默截断。若硬边界可能超过，必须查询 `limit+1` 并返回明确 overflow/omission 证据，供后续上下文层联网前阻断；不得悄悄只取前 N 条。
- FTS 相关路线必须调用已有 recall 与 hydration，不复制检索、hash 或权威状态逻辑。
- 最终按强制 > 最近 > FTS 分组；组内必须确定性排序；同一 source identity 只出现一次，但可累计多条 route evidence。
- 选择结果最多 512 项；如果强制+最近已超过边界，返回显式不可用/overflow 结果，不丢强制数据；FTS 超出只报告有界省略。
- 结果、命中、异常与 `toString()` 不得展开正文、JSON、人物名、source/document ID、searchTerms、用户补充或计划。
- 不在查询/结果中信任派生指针；返回的 source 必须是权威 wrapper。
- 保持 App Provider 调用 0、物理设备写入 0、Git remote 0。

## 需要重点回答的审计问题

1. 新路线层能否通过调用现有 recall/hydration 保持事务边界，还是应只编排两者而不再包外层事务？
2. current Bible 的 256 个 HARD_CANON 加上 due foreshadows、最近 8 条与最多 128 FTS，如何在 512 总上限内既不静默漏强制项又保留明确 overflow 证据？
3. Canon fact 同时带 Bible 和 chapter 来源时，强制判定与去重如何保持 Phase 2B2 语义？
4. 最近摘要与 FTS 同时命中时，如何合并路由证据并保持最近路线优先？
5. 旧 `activeForeshadowsForContext` 的 current-version/未来章漏洞是否需要由本阶段新增的精确查询关闭？
6. 提议中的公共/内部类型是否足够让下一阶段映射到 `ChapterContextCandidate`，且不迫使上下文层依赖默认实体字符串？

## 验收标准

- [ ] 最多 512 项，强制 > 最近 > FTS，稳定顺序和 source identity 去重。
- [ ] HARD_CANON、due foreshadow 不依赖 FTS，且超界不静默丢失。
- [ ] 上一章和最近 8 个 current summary 可被稳定保留。
- [ ] FTS 复用 2B1/2B2，失效指针不进入选择结果，重建标记保留。
- [ ] 未来章、旧章节版本、旧 Bible、resolved/abandoned/stale/archived 不进入权威选择。
- [ ] 结果提供足够但脱敏的 route、count、overflow、fingerprint/rebuild 证据。
- [ ] 不改 schema、上下文 manifest、Stage、Provider 或其他模块。

## Sol 后续验证

Sol 应用并补测试后至少执行：

```powershell
.\gradlew.bat :core:database:compileDebugKotlin :core:database:compileDebugAndroidTestKotlin --offline --no-daemon
.\gradlew.bat :core:database:connectedDebugAndroidTest --offline --no-daemon -Pandroid.testInstrumentationRunnerArguments.class=app.zhijuan.core.database.MemoryContextRouteSelectionDatabaseTest
```

随后在 API 30/API 35 执行 core/database 全量、安全扫描与 `git diff --check`。

## 回交格式

1. `审计结论`
2. `建议契约与排序`
3. `修改文件`
4. `验证`
5. `未完成/风险`
6. `需要 Sol 决策`
7. `Unified diff`

只返回文字和完整 unified diff；不得写仓库，不得宣布 Phase 2C1/TASK-060 完成。
