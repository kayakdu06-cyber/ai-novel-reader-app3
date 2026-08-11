# TASK-061 / Phase 2A：编辑后重建影响计划与版本栅栏审计

## 任务身份

- 任务 ID：`TASK-061 / Phase 2A`
- 仓库根目录：`D:\gptuser\projects\ai-novel-reader-app2`
- 基准分支与 HEAD：`main` / `8ce7744`；继续当前 dirty WIP，禁止回退或清理。
- 已完成前置：Phase 1 新增 `ChapterUserEditRepository`，原子保存 `USER_EDIT` 版本、旧派生 stale、旧 FTS 清理、`EDITED/UNKNOWN` CAS 和后续正文保留；双 API 数据库各 146/146，工作汇报 91。
- 执行模型：DeepSeek V4 Flash（纯文本，只读架构审计与局部补丁提案）。

## 运行预算

- 推理等级：`max`。
- 最长运行时间：20 分钟。
- 累计 Token 上限：无总上限；仍受 20 分钟硬上限。
- 预计读取文件：18 个以内，仅限清单及直接类型定义。
- 预计只读命令：最多 10 个。
- 提前停止条件：需要直接删除/覆盖历史派生数据、需要绕过 Provider-open/费用门禁、无法在不改 schema 的情况下诚实产出计划，或范围必须扩大到实际联网执行。

## 目标

审计并提出一个最小、只读的“编辑后重建影响计划”生产边界。它不执行重建、不建 Attempt、不调用 Provider，只读取当前 SQLCipher 权威状态并生成确定性、脱敏、可重验的计划：

1. 明确编辑章、后续已提交章节和默认“保留后续正文”的选择；
2. 按章节和依赖顺序列出需要的 memory、tracking、aggregate、context、consistency 工作；
3. 冻结每个 current chapter version 及 content hash，形成稳定 plan hash；
4. 提供 `requireCurrentMatches(plan)` 栅栏，任何 current/version/hash/范围变化都在后续联网或持久建 Job 前失败；
5. 明确哪些步骤当前可执行，哪些因 tracking/aggregate 的唯一约束或历史 foreshadow 投影缺口必须等待后续 schema/执行器支持；不能用删除保护条件假装可执行。

最终只返回架构结论和完整 unified diff 提案；不得写仓库。

## 已确认的严重约束

- `chapter_summary.chapter_version_id` 唯一；同一 current version 不能直接插第二份 summary。
- `chapter_tracking_projection.chapter_version_id` 唯一；同一 current version 不能直接插第二个 projection。
- `aggregate_state_projection(book_id, through_chapter_index)` 唯一；stale 后不能直接插同章替代行。
- `foreshadow_item` 是可变当前投影，`foreshadow_transition` 是追加历史；transition 保存状态变化但不保证完整保存 visible IDs/importance 等每次历史属性。
- `ChapterTrackingProjectionSourceRepository.loadCurrentVersion` 在任一后续章节已有 current version 时拒绝较早章 tracking，且要求当前 version 尚无 projection。
- 用户编辑第 N 章后，Phase 1 只把 N 章直接派生、N..latest aggregate、N+1..latest context/report 和未来章节状态失效；后续正文/current version 保留。
- 现有 memory/tracking commit 都要求严格 Stage/Attempt/Usage/artifact/lease 证据，并且不允许覆盖已有派生行。
- 当前 App 没有按 phase 分发的总 runner；Phase 2A 不能冒充实际后台重建已接通。

## 必读资料

1. `AGENTS.md`
2. `docs/24-AI-DEVELOPMENT-PROTOCOL.md`
3. `docs/ai/CURRENT-CONTEXT.md`
4. `docs/01-PRD.md` 的 PR-011
5. `docs/03-USER-FLOWS.md` 的编辑流程
6. `docs/06-AI-GENERATION-SYSTEM.md` 第 10、25 节
7. `docs/09-DATA-MODEL.md` 的 ChapterVersion、派生表和 TASK-057/061 记录
8. `docs/15-TEST-PLAN.md` 的 TEST-032/033 与第 25 节
9. `core/database/src/main/kotlin/app/zhijuan/core/database/library/ChapterUserEditRepository.kt`
10. `core/database/src/main/kotlin/app/zhijuan/core/database/library/LibraryDao.kt`
11. `core/database/src/main/kotlin/app/zhijuan/core/database/library/LibraryEntities.kt`
12. `core/database/src/main/kotlin/app/zhijuan/core/database/memory/MemoryDao.kt`
13. `core/database/src/main/kotlin/app/zhijuan/core/database/memory/MemoryEntities.kt`
14. `core/database/src/main/kotlin/app/zhijuan/core/database/memory/DerivedAuditEntities.kt`
15. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterMemoryExtractionJobFactory.kt`
16. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterMemoryExtractionCommitRepository.kt`
17. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterTrackingProjectionJobFactory.kt`
18. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterTrackingProjectionCommitRepository.kt`
19. `core/database/src/androidTest/kotlin/app/zhijuan/core/database/ChapterUserEditDatabaseTest.kt`

不要读取历史报告、日志、密钥、其他项目或 C 盘。

## 建议范围

允许提议新增：

- `core/database/src/main/kotlin/app/zhijuan/core/database/library/ChapterEditRebuildPlanRepository.kt`
- `core/database/src/androidTest/kotlin/app/zhijuan/core/database/ChapterEditRebuildPlanDatabaseTest.kt`

仅在确有必要时允许提议新增无 schema 的 `MemoryDao`/`LibraryDao` 只读查询。

明确不在范围：

- 不修改 entity、schema、migration、trigger 或现有派生唯一索引。
- 不创建 GenerationJob/Stage/Attempt/Usage，不打开 Provider，不估算或扣除真实费用。
- 不克隆章节版本，不删除/更新旧正文或旧派生历史，不把 stale 行恢复为 VALID。
- 不修改现有 memory/tracking/context commit、source guard、runner 或 UI。
- 不宣称 TEST-033 已完成。

## 建议契约

请审计而非机械接受以下建议：

- `ChapterEditRebuildPlanRequest(bookId, editedChapterId, editedVersionId)`；edited version 必须仍为 current、source=`USER_EDIT`、status=`EDITED`、consistency=`UNKNOWN` 且 parent 存在。
- `FutureChapterPolicy.KEEP_EXISTING` 为固定默认；Phase 2A 不接受自动删除。结果说明另一个未来选项 `REGENERATE_FROM_NEXT`，但不执行。
- `ChapterEditRebuildStepType` 至少区分：`EXTRACT_EDITED_MEMORY`、`REBUILD_STORY_TRACKING`、`REBUILD_AGGREGATE_STATE`、`REASSEMBLE_CONTEXT`、`RECHECK_CONSISTENCY`。
- 每个 step 包含 chapterIndex、source current version ID/hash、是否需要 Provider、是否被现有数据模型阻塞、依赖的前一 step ordinal；默认字符串必须脱敏。
- plan 只覆盖从 edited chapter 到“当前最高已提交章节”；中间只有 PLANNED、没有 current 的章不能伪造工作。
- plan hash 必须覆盖 policy version、book/edit identity、每个 current version/hash/status/consistency 与完整步骤/阻塞原因；不得覆盖正文。
- `requireCurrentMatches(plan)` 必须重建同一计划并比较 hash，不能只检查 edited version。
- 有后续正文时 `hasLaterCommittedChapters=true`、默认 keep；用户参与应最少，但真实费用确认仍留给现有费用门禁。

## 必须回答的审计问题

1. 在不改 schema 的前提下，哪些重建步骤确实可执行，哪些只是计划且必须明确 blocked？
2. 后续 current version 已有 summary/tracking 时，是否存在任何不丢历史的安全重建路径？若没有，必须明确拒绝 clone/update/delete 捷径。
3. 只读计划应放 library 还是 generation 包，怎样避免把 UI 决策和执行器细节耦合？
4. 计划 hash 和重验需要冻结哪些权威字段，才能防止编辑后又发生用户修改或生成提交？
5. 无后续正文与有后续正文两种情况的步骤和 blocker 应如何不同？
6. 如何让结果足够支持后续费用确认和 Job 创建，同时不包含正文、JSON、人物名或搜索词？

## 验收标准

- [ ] 10 章编辑第 3 章时生成稳定、顺序明确的计划，默认保留第 4–10 章正文。
- [ ] 无后续章时计划不虚构后续工作；有后续章时明确用户选择和执行 blocker。
- [ ] edited/current version、hash 或后续 current 集合变化时，旧计划重验失败。
- [ ] plan/step/request/result 默认字符串不泄漏正文、书/章/版本 ID、JSON 或人物名。
- [ ] 不写数据库、不建 Job/Attempt、不联网、不改 schema。
- [ ] 不声称实际重建或 TEST-033 已完成。

## Sol 后续验证

```powershell
.\gradlew.bat :core:database:compileDebugKotlin :core:database:compileDebugAndroidTestKotlin --offline --no-daemon
.\gradlew.bat :core:database:connectedDebugAndroidTest --offline --no-daemon '-Pandroid.testInstrumentationRunnerArguments.class=app.zhijuan.core.database.ChapterEditRebuildPlanDatabaseTest'
```

随后由 Sol 做双 API、全量回归、安全扫描和 `git diff --check`。

## 回交格式

1. `可行性结论`
2. `唯一约束与安全边界`
3. `建议契约`
4. `修改文件`
5. `验证`
6. `未完成/风险`
7. `需要 Sol 决策`
8. `Unified diff`

只返回文字和完整 unified diff；不得写仓库，不得宣布 Phase 2A/TASK-061 完成。
