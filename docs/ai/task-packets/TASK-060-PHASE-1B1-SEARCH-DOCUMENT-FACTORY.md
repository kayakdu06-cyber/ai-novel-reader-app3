# TASK-060 / 第 1B1 阶段：生产记忆检索文档构造器

## 任务身份

- 任务 ID：`TASK-060 / Phase 1B1 / Memory search document factory`
- 仓库根目录：`D:\gptuser\projects\ai-novel-reader-app2`
- 基准分支与 HEAD：`main` / `8ce7744`（不得回退，以当前未提交工作区为准）
- 当前未提交改动：包含已验收 TASK-059 WIP，以及 TASK-060 第 1A 阶段的 production FTS v9 schema、迁移、DAO 和工作汇报 80；全部保留
- 执行模型：DeepSeek（纯文本，只读补丁提案模式）

## 运行预算

- 推理等级：`max`
- 最长运行时间：15 分钟
- 累计 Token 上限：不设置（用户持续授权）
- 预计读取文件数：10 个，见“必读资料”；只可额外读取这些文件直接引用的 model enum
- 预计执行命令/测试数：只允许 Git 根/状态和只读文件检索；不运行 Gradle、Android 工具或写入探针
- 提前停止条件：需要扩大到提交仓库/数据库 schema；补丁超过两个新文件；数据契约存在无法由当前范围解决的歧义；只读或权限问题

## 目标

提交一个完整、最小、可由 Sol 使用 `apply_patch` 落地的补丁提案：新增纯 Kotlin `MemorySearchDocumentFactoryV1` 与 JVM 测试，把六类正式记忆行确定性转换成第 1A 阶段的 `MemorySearchDocumentEntity`。本任务不允许直接写文件，不接事务，不改 DAO/schema。

## 当前现场与已有 WIP

- `SearchIndexText` 已稳定实现中文 Han 单字/双字 ASCII token。
- `MemorySearchDocumentEntity` 已在正式 schema v9 注册，字段为 rowid、document ID、book/source identity、chapter/story order、importance、source hash、search terms、updated time。
- 第 1A 阶段 API 30 数据库模块 115/115，通过；不能重写或改名。
- 当前缺口是把正式 `StoryEntity`、`ChapterSummary`、`EntityEvent`、`CanonFact`、`TimelineEvent`、`ForeshadowItem` 转成不含源正文的索引行。

## 必读资料

1. `AGENTS.md`
2. `docs/24-AI-DEVELOPMENT-PROTOCOL.md`
3. `docs/ai/CURRENT-CONTEXT.md`
4. `docs/adr/ADR-004-chinese-search.md`
5. `core/database/src/main/kotlin/app/zhijuan/core/database/search/SearchIndexText.kt`
6. `core/database/src/main/kotlin/app/zhijuan/core/database/search/MemorySearchDocumentEntity.kt`
7. `core/database/src/main/kotlin/app/zhijuan/core/database/memory/MemoryEntities.kt`
8. `core/model/src/main/kotlin/app/zhijuan/core/model/MemoryModel.kt`
9. `core/database/src/test/kotlin/app/zhijuan/core/database/search/SearchIndexTextTest.kt`
10. `reports/2026-08-04-80-task-060-production-fts-schema.md`

## 范围

允许在最终回交中提议新增：

- `core/database/src/main/kotlin/app/zhijuan/core/database/search/MemorySearchDocumentFactory.kt`
- `core/database/src/test/kotlin/app/zhijuan/core/database/search/MemorySearchDocumentFactoryTest.kt`

明确不在范围：

- 不直接调用 `apply_patch`，不创建/修改/删除任何文件；只返回补丁文本
- 不修改 Entity、DAO、database v9、migration、memory/generation 提交仓库、上下文组装、App/UI、Provider 或状态文档
- 不运行构建测试，不调用真实 API，不操作模拟器/物理设备

## 不可破坏的约束

- 工厂必须是纯确定性 Kotlin，无数据库、时间、随机数、网络或 Android 依赖。
- 新增内部 enum `MemorySearchSourceTypeV1`，固定六类：`STORY_ENTITY`、`CHAPTER_SUMMARY`、`ENTITY_EVENT`、`CANON_FACT`、`TIMELINE_EVENT`、`FORESHADOW`；持久值使用 enum name。
- 对外 API：`MemorySearchDocumentFactoryV1.from(...)` 为六类源行分别提供重载；事件/事实/时间线/伏笔缺少章节号时由调用者显式传入可空或非空 `chapterIndex`，不能猜测数据库关系。
- `rowId` 固定 0，交给 Room 自增。
- `documentId` 必须是固定 schema 前缀加 SHA-256，hash 输入为 `bookId + NUL + sourceType + NUL + sourceId`；不得直接拼接不受控 ID。
- `sourceContentHash` 使用固定 v1 canonical payload 后 SHA-256；payload 必须覆盖实际用于搜索的文本和会影响索引元数据的来源字段，不依赖 data class `toString()`、JSON object key 顺序或系统 locale。
- 只索引用户可读值，不索引 JSON 字段名和不透明 source/entity ID。对 JSON 字段严格解析并按数组顺序、对象 key 排序后递归收集字符串叶值；非法 JSON 失败关闭。
- 正式索引行不得包含源中文、完整 JSON 或提示词；`searchTerms` 只能来自 `SearchIndexText.indexTerms` 的 ASCII token。
- `ChapterSummary`、`EntityEvent`、`CanonFact`、`TimelineEvent` 只接受 `DerivedDataStatus.VALID`；其他状态返回 null。
- `StoryEntity` 只接受 `archivedAt == null`；`ForeshadowItem` 只接受 `memoryStatus == VALID` 且状态不是 `RESOLVED/ABANDONED`；不可召回行返回 null。
- 可搜索文本为空或只含标点时返回 null，不插入空 FTS 行。
- importance 必须固定在 0..100：summary/foreshadow 使用原值并校验；story entity 固定 100；canon 依次 `HARD_CANON=100, STORY_CANON=80, PLAN_ONLY=60, INFERRED=40`；entity event 使用 `confidenceMicros / 10_000` 并校验 0..1,000,000；timeline 固定 70。
- 来源文本/JSON、叶值数量和生成 token 必须有明确常量上限，超限失败关闭，避免极端行造成内存放大；上限需足以容纳现有合法生成结构。
- 错误信息不得回显源文本、JSON、人物名或 search terms。

## 每类索引文本

1. `StoryEntity`：canonical name + aliases JSON 的字符串叶值 + stable definition JSON 的字符串叶值。
2. `ChapterSummary`：summary JSON 的字符串叶值。
3. `EntityEvent`：attribute key + old/new/evidence JSON 的字符串叶值 + 可空 story time expression。
4. `CanonFact`：fact text + fact payload JSON 与 scope JSON 的字符串叶值。
5. `TimelineEvent`：name + story time expression + participants/constraints JSON 的字符串叶值。
6. `ForeshadowItem`：description；不索引 visible entity ID。

## 测试要求

至少覆盖：

1. 六类合法来源的 source type、stable document ID、chapter/story order、importance、updatedAt；
2. 中文正文不会原样出现在 `searchTerms`，但 `SearchIndexText.matchExpression` 所需 token 可命中；
3. JSON key 不进入 token，JSON 对象 key 顺序变化不改变 source hash/search terms；
4. stale/failed/archived/resolved/abandoned 与空可搜索内容返回 null；
5. 非法 JSON、importance/confidence 越界和规模上限失败关闭，且异常消息不包含输入内容；
6. 改变用户可读来源值会改变 source hash；改变不透明 ID 只改变 document ID/来源身份，不把 ID 写进 search terms。

## 验收标准

- [ ] 最终回交含两个文件的完整 apply_patch-compatible 补丁提案。
- [ ] 代码不存源文本、不过度索引 JSON keys/IDs，输出 token 纯 ASCII。
- [ ] canonicalization、hash、排序和限制均确定性，不依赖 locale/toString。
- [ ] 测试覆盖六类及失败路径。
- [ ] 不宣布 TASK-060 完成，不更新正式状态。

## 验证命令

本次为 `-PatchProposalOnly`，DeepSeek 不运行任何构建或测试。Sol 应用并审查补丁后运行：

```powershell
.\gradlew.bat :core:database:testDebugUnitTest --tests 'app.zhijuan.core.database.search.MemorySearchDocumentFactoryTest' --offline --no-daemon
```

## 回交格式

1. `完成内容`
2. `修改文件`（说明为提案、实际 0 文件）
3. `补丁提案`（单个完整 diff fenced block）
4. `验证`
5. `未完成/风险`
6. `需要 Sol 处理`
7. `假设`

不要直接写文件，不要宣布整个 TASK-060 完成。
