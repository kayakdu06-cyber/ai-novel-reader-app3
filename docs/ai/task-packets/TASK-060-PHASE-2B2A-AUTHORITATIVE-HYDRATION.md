# TASK-060 / Phase 2B2A：六类权威记忆 hydration 生产实现提案

## 任务身份

- 任务 ID：`TASK-060 / Phase 2B2A`
- 仓库根目录：`D:\gptuser\projects\ai-novel-reader-app2`
- 基准分支与 HEAD：`main` / `8ce7744`；不得回退，继续当前 dirty WIP。
- 当前未提交改动：TASK-059 完整 WIP、TASK-060 Phase 1A–1C、2A、2B1、DeepSeek 隔离脚本和报告均未提交；必须全部保留。
- 执行模型：DeepSeek V4 Flash（纯文本，只读补丁提案）

## 运行预算

- 推理等级：`max`
- 最长运行时间：20 分钟。理由：需要核对六类 Room 查询、来源版本边界和工厂 hash；已拆为只做生产代码，不要求测试与文档。
- 累计 Token 上限：无总上限（用户持续要求）；仍受 20 分钟硬上限。
- 预计读取文件数：12 个，见下方明确清单；不要递归读取历史报告。
- 预计执行命令：最多 4 个只读搜索/读取命令；只读提案模式禁止构建和写文件。
- 提前停止条件：需要 schema/migration、必须改变 Phase 2B1 排序、权限阻塞、范围需扩张或无法用静态脱敏错误实现。

## 目标

给出可由 Sol 直接审查和 `apply_patch` 的完整最小补丁：按 Phase 2B1 的最多 128 个检索指针，分六类批量重读同一本 SQLCipher/Room 数据库中的权威行；只保留仍有效、仍属于当前正式章节版本/当前 Bible 且严格早于目标章的来源，并用 `MemorySearchDocumentFactoryV1` 重新派生指针，复核 source hash 和全部派生元数据。

本任务只做生产 DAO 和 hydration repository，不写测试、报告或状态文档，不接上下文和 Provider。

## 当前现场与已有 WIP

- `MemorySearchRecallRepositoryV1.recall()` 已返回最多 128 个 `MemorySearchRecallHitV1`，包含已排序的 `MemorySearchDocumentEntity` 指针和三路命中计数。
- FTS 指针是派生数据，不是发送给模型的权威记忆；Phase 2B2 必须重新读取六类表。
- 六类来源：`STORY_ENTITY`、`CHAPTER_SUMMARY`、`ENTITY_EVENT`、`CANON_FACT`、`TIMELINE_EVENT`、`FORESHADOW`。
- `MemorySearchDocumentFactoryV1.from(...)` 是唯一派生/hash 规则。比较时应把数据库指针 `rowId` 归零，再与工厂结果精确比较；禁止复制另一套 hash 算法。
- 已有 `EntityEventSearchBackfillRow`、`CanonFactSearchBackfillRow`、`TimelineEventSearchBackfillRow`、`ForeshadowSearchBackfillRow` 可携带权威行和 chapterIndex。
- `MemoryDao` 已有分页回填和上下文查询，但没有按 128 个 source ID 批量 hydration 的完整接口。
- Phase 2B1 结果和输入类的 `toString()` 已脱敏；不要重写 2B1。

## 必读资料

1. `AGENTS.md`
2. `docs/24-AI-DEVELOPMENT-PROTOCOL.md`
3. `docs/ai/CURRENT-CONTEXT.md`
4. `docs/06-AI-GENERATION-SYSTEM.md` 第 4、5、7、8、10 节
5. `docs/09-DATA-MODEL.md` 记忆与章节版本部分
6. `core/database/src/main/kotlin/app/zhijuan/core/database/memory/MemoryEntities.kt`
7. `core/database/src/main/kotlin/app/zhijuan/core/database/memory/MemoryDao.kt`
8. `core/database/src/main/kotlin/app/zhijuan/core/database/memory/MemorySearchBackfillRows.kt`
9. `core/database/src/main/kotlin/app/zhijuan/core/database/search/MemorySearchDocumentFactory.kt`
10. `core/database/src/main/kotlin/app/zhijuan/core/database/search/MemorySearchRecallRepository.kt`
11. `core/database/src/main/kotlin/app/zhijuan/core/database/search/MemorySearchIndexWriter.kt`
12. `core/database/src/main/kotlin/app/zhijuan/core/database/ZhijuanDatabase.kt`

除上述文件和它们的直接类型定义外，不得扫描其他项目、历史报告、日志、密钥或 C 盘。

## 范围

允许在最终回交中提议修改：

- `core/database/src/main/kotlin/app/zhijuan/core/database/memory/MemoryDao.kt`
- `core/database/src/main/kotlin/app/zhijuan/core/database/search/MemorySearchHydrationRepository.kt`（新文件）
- 如确有必要，可在 `MemorySearchBackfillRows.kt` 增加不改变 schema 的 `@Embedded` 查询投影。

明确不在范围：

- 不修改 Room entity、schema 版本、migration 或导出 schema。
- 不修改 Phase 2B1 查询/排序/指纹。
- 不写 Android/JVM 测试、报告、状态文档。
- 不接最近章节、强制硬事实、上下文预算、Stage、runner、UI 或 Provider。
- 不修改 Gradle、网络、安全脚本、DeepSeek 脚本和其他任务文件。

## 不可破坏的约束

- 项目隔离：不得访问/修改 `D:\gptuser\projects\ai-novel-reader` 或任何相似目录。
- 只读补丁提案：不得使用 shell、PowerShell、Python、.NET、重定向、`WriteAllText` 或其他方式写文件；最终消息内给完整 unified diff。
- 安全与隐私：所有 `toString()` 和异常必须脱敏，不输出正文、JSON、名称、描述、source ID、document ID、searchTerms 或输入对象默认 data-class 字符串。
- 数据库：hydration 在单个 `database.withTransaction` 中完成；最多六个批量查询，不允许对 128 个命中逐条 N+1 查询；每组 ID 先去重并有界。
- 权威性：不得相信指针内的正文/hash/importance/chapterIndex。必须从权威行重新调用现有 factory，并与 `hit.document.copy(rowId = 0)` 精确比较。
- 状态和版本：
  - Story entity：同书且 `archivedAt == null`；当前模型中它是可变的稳定实体，不强制 source Bible 等于 head。
  - Summary/Event/Timeline：`VALID`，来源章节版本仍是该章 `current_version_id`，章节严格早于目标章。
  - Canon fact：`VALID`；Bible 来源必须等于当前 `book_memory_head.current_bible_revision_id`；章节来源必须仍为 current 且早于目标章。没有命中这两种来源的事实不进入结果。
  - Foreshadow：`memoryStatus == VALID` 且状态不是 `RESOLVED/ABANDONED`；有 source chapter 时必须仍为 current 且早于目标章，无 source chapter 时允许全局来源。
- 可用性：已过期、已删除、当前版本变化或 hash/元数据不匹配的派生指针不进入 hydrated hits，但不应让整章失败；结果必须报告 `rejectedPointerCount` 和 `indexRebuildRequired = rejectedPointerCount > 0`。输入自身结构损坏、重复 source 身份、跨书或超过 128 条时失败关闭。
- 顺序：有效 hydrated hits 必须保持 Phase 2B1 的既有排序，不自行重排。
- 联网与费用：真实 API 0；不得调用 App Provider。
- 保留所有现有 dirty WIP，不执行 reset/clean/checkout/commit/remote。

## 建议结果契约

可以调整命名，但语义必须等价：

- `sealed interface MemorySearchAuthoritativeSourceV1`：六个强类型 wrapper；每个 wrapper 自定义脱敏 `toString()`。
- `MemorySearchHydratedHitV1(recallHit, authoritativeSource)`：自定义脱敏 `toString()`。
- `MemorySearchHydrationResultV1(hits, inputPointerCount, rejectedPointerCount, indexRebuildRequired)`：计数自洽，最多 128，`toString()` 不展开 hits。
- `MemorySearchHydrationRepositoryV1(database).hydrate(bookId, targetChapterIndex, recallResult)`。

输入 recall result 的 query fingerprint 和计数可以继续保留在结果中，但不得复制或输出源数据。

## 实施要求

1. 为六类 source ID 增加批量 Room 查询，稳定按源 ID 排序；空 ID 组由 repository 跳过，不依赖 Room 对空 `IN` 的特殊行为。
2. 查询本身尽量过滤同书、有效状态、当前版本/当前 Bible 和目标章边界；repository 再做独立 Kotlin 复核。
3. 为每个权威行调用现有 factory：Story/Summary 直接；Event/Fact/Timeline/Foreshadow 使用查询返回的 chapterIndex。
4. 按 `(sourceType, sourceId)` 建 map，拒绝重复；逐个原排序 hit 查找权威值并精确重派生比较。
5. 缺失、失效或重派生不一致只计为 rejected；合法 hit 保持输入顺序。
6. 任何对象字符串和异常不得泄露内含的权威实体。
7. 最终只返回完整 unified diff，不写磁盘，不宣布 Phase 2B2/TASK-060 完成。

## 验收标准

- [ ] 六类都能批量查询，最多六个查询，无 N+1。
- [ ] 章节来源只接受 current version 且 `< targetChapterIndex`。
- [ ] Bible fact 只接受当前 Bible；旧 Bible fact 被拒绝。
- [ ] stale/resolved/abandoned/archived 被拒绝但计数，不使整章失败。
- [ ] 工厂重派生的完整指针/hash 与输入不一致时拒绝并要求重建索引。
- [ ] 有效结果保持 2B1 顺序，计数自洽且全部字符串表示脱敏。
- [ ] 不改 schema/migration，不触碰联网/Provider/其他任务。

## 验证命令

本次是只读提案，不运行构建。Sol 应用后执行：

```powershell
.\gradlew.bat :core:database:compileDebugKotlin --offline --no-daemon
```

之后由 Sol 新增并运行双 AVD hydration 测试和全量回归。

## 回交格式

请严格按以下标题返回：

1. `完成内容`
2. `修改文件`
3. `验证`
4. `未完成/风险`
5. `需要 Sol 处理`
6. `假设`
7. `Unified diff`

不要宣布整个 TASK 完成，不要更新正式状态。最终 diff 中不要包含本任务包或任何文档改动。
