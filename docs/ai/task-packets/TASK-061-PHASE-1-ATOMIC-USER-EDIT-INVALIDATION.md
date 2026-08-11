# TASK-061 / Phase 1：用户编辑章节的原子提交与失效提案

## 任务身份

- 任务 ID：`TASK-061 / Phase 1`
- 仓库根目录：`D:\gptuser\projects\ai-novel-reader-app2`
- 基准分支与 HEAD：`main` / `8ce7744`；不得回退，必须继续当前 dirty WIP。
- 当前未提交改动：TASK-059、TASK-060 的完整实现、测试、文档、隔离脚本与报告均未提交；必须全部保留，不得从零重写或清理工作树。
- 执行模型：DeepSeek V4 Flash（纯文本，只读审计与补丁提案）。

## 运行预算

- 推理等级：`max`。
- 最长运行时间：20 分钟。理由：需要审计现有章节版本 CAS、跨 DAO Room 事务、记忆失效图和搜索索引清理顺序，并提出可独立验证的局部补丁。
- 累计 Token 上限：无总上限（用户持续授权）；仍受 20 分钟硬上限。
- 预计读取文件：16 个以内，仅限下方清单和这些文件直接引用的类型定义。
- 预计只读命令：最多 10 个。
- 提前停止条件：需要 schema/migration、必须改动 Provider/网络、必须实现跨章节顺序重建、权限阻塞，或范围必须扩展到其他模块。

## 目标

先审计，再提出一个最小生产补丁，使用户编辑一个已经存在正文版本的章节时，在同一个本地数据库事务中：

1. 保存新的不可变 `USER_EDIT` 版本并保留旧版本；
2. 通过预期旧版本做 CAS，将章节切换到新版本并标记为 `EDITED / UNKNOWN`；
3. 对旧版本触发已有派生数据失效图，并删除旧版本及其受影响记忆的 FTS 文档；
4. 保留所有后续章节正文，只把其一致性状态及受影响上下文/报告标为未知或失效；
5. 对精确 replay、并发旧请求、跨书身份、事务回滚和日志隐私提供确定行为。

本阶段只关闭“用户编辑提交绕过失效与搜索清理”的生产缺口；不实现跨章节顺序重建、不创建联网任务、不调用 Provider。最终只给 Sol 可审查的完整 unified diff 提案，不得写仓库。

## 当前现场与已有 WIP

- `LibraryDao.commitChapterVersion` 能提交 `ChapterVersionSource.USER_EDIT`，但只插入版本并切换 current，不能协调 `MemoryDao` 和搜索索引，也不会把章节设置为 `EDITED / UNKNOWN`。
- `MemoryDao.markDerivedDataStaleForReplacedChapter(bookId, replacedVersionId, updatedAt)` 已在单事务内覆盖旧版本的摘要、事件、事实、时间线、受影响伏笔与追踪投影、从编辑章起的聚合投影、后续上下文/一致性报告，以及后续章节 `CONSISTENCY_UNKNOWN`；它还校验版本所属书并支持幂等重放。
- `MemorySearchIndexWriterV1.identitiesForReplacedChapter` 会在失效前收集需要删除的全部搜索 source identity；`deleteIdentities` 可在同一事务内删除。
- `ChapterFinalCandidateCommitRepositoryV1` 与 `ChapterGenerationCommitRepositoryV1` 已有正确参考顺序：先捕获旧索引 identity，再失效派生数据，最后删除旧搜索文档。
- 生产 `src/main` 当前没有用户编辑入口调用者；因此需要独立 repository 契约，不能假装 generic DAO 已经安全。
- 多个 Android 数据库测试用 `commitChapterVersion(... USER_EDIT ...)` 建夹具。除非有充分理由，本阶段不要禁止这个低层 DAO 值并大面积改写无关夹具；请把未来误用风险作为审计结论说明。
- `ChapterTrackingProjectionSourceRepository.loadCurrentVersion` 会在存在后续已提交章节时拒绝重建；这是 Phase 2 的跨章顺序重建问题，不应在本阶段绕过。

## 必读资料

1. `AGENTS.md`
2. `docs/24-AI-DEVELOPMENT-PROTOCOL.md`
3. `docs/ai/CURRENT-CONTEXT.md`
4. `docs/01-PRD.md` 中 `PR-011`
5. `docs/03-USER-FLOWS.md` 中章节编辑流程
6. `docs/06-AI-GENERATION-SYSTEM.md` 中编辑后失效图和重建约束
7. `docs/09-DATA-MODEL.md` 中章节版本、记忆失效和编辑重建部分
8. `docs/13-DECISION-LOG.md` 中 `DEC-050`
9. `docs/15-TEST-PLAN.md` 中 `TEST-032`、`TEST-033`（Phase 1 只实现 `TEST-032` 的原子编辑与失效）
10. `core/database/src/main/kotlin/app/zhijuan/core/database/library/LibraryDao.kt`
11. `core/database/src/main/kotlin/app/zhijuan/core/database/library/LibraryEntities.kt`
12. `core/database/src/main/kotlin/app/zhijuan/core/database/memory/MemoryDao.kt`
13. `core/database/src/main/kotlin/app/zhijuan/core/database/search/MemorySearchIndexWriter.kt`
14. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterFinalCandidateCommitRepository.kt`
15. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterGenerationCommitRepository.kt`
16. `core/database/src/androidTest/kotlin/app/zhijuan/core/database/MemoryDatabaseTest.kt`
17. `core/database/src/androidTest/kotlin/app/zhijuan/core/database/LibraryDatabaseTest.kt`

仅在编译契约需要时读取上述文件直接引用的 entity/enum/数据库 getter；不要读取历史报告、日志、密钥、其他项目或 C 盘。

## 建议范围

允许提议修改：

- `core/database/src/main/kotlin/app/zhijuan/core/database/library/LibraryDao.kt`（只允许新增编辑专用 CAS/查询，不重写 generic commit）
- 新建 `core/database/src/main/kotlin/app/zhijuan/core/database/library/ChapterUserEditRepository.kt`
- 新建 `core/database/src/androidTest/kotlin/app/zhijuan/core/database/ChapterUserEditDatabaseTest.kt`

明确不在范围：

- 不修改 Room entity、schema 或 migration。
- 不实现后台重建队列、跨章节顺序重建、费用确认或“从下一章重新生成”。
- 不修改现有 AI final commit、memory extraction、tracking projection 或 context assembly 行为。
- 不修改 UI、ViewModel、Stage runner、Provider、网络、真实生成 API。
- 不修改状态文档、报告或其他测试。
- 不直接把补丁写入仓库。

## 不可破坏的约束

- 仓库隔离：只读 `D:\gptuser\projects\ai-novel-reader-app2`，不得访问或修改相似原项目。
- 事务：新版本插入、旧派生失效、current CAS、章节状态、一致性状态、旧搜索 identity 删除必须全成或全败。
- 身份：必须同时验证 `bookId`、`chapterId`、`expectedCurrentVersionId`，且旧 current version 确实属于该章与该书；错误身份在任何持久写入前失败。
- 版本：旧版本正文和行必须保留；新版本 `parentVersionId` 指向预期旧版本，source 固定 `USER_EDIT`，版本号单调递增。
- 完整性：正文 hash 必须在 repository 内按现有项目规则计算并保存，不接受调用方伪造 hash。
- CAS：只有 current 仍等于预期旧版本才可发布；并发过期请求必须失败且整个事务回滚。
- 状态：编辑章切换后为 `EDITED`，一致性为 `UNKNOWN`；后续章节正文和 current version 不能被删除或替换。
- 搜索：必须在调用 stale cascade 前捕获旧版本所有受影响 identity，并在同一事务删除；不为尚未重建的新版本伪造搜索文档。
- replay：完全相同的请求 ID/新版本 ID 与内容重放不得创建第二个版本或重复推进版本号；同 ID 不同内容必须冲突失败。返回结果不能泄漏正文。
- 时间：拒绝明显倒退或不合法的编辑时间，所有失效时间与提交时间使用同一个规范化时间。
- 隐私：异常、结果和 `toString()` 不得包含正文、人物名、API key、完整 JSON、搜索词或长 ID 清单。
- 保持 App Provider 调用 0、物理设备写入 0、Git remote 0。

## 需要重点回答的审计问题

1. 最小公开/内部命令和结果契约应包含哪些字段，如何保证 replay 不依赖调用方提供 hash？
2. 编辑专用 CAS 应新增在 `LibraryDao` 还是复用现有 CAS 后再单独更新 consistency；如何避免中间状态和并发窗口？
3. repository 中捕获 identity、调用 stale cascade、插入新版本、CAS、删除索引的最佳事务顺序是什么？每个失败点是否完整回滚？
4. 如何区分“精确 replay 已经成功”与“新版本 ID 冲突”“expected current 已过期”？
5. 是否应在 Phase 1 禁止 generic `commitChapterVersion(USER_EDIT)`？如果建议禁止，必须列出全部夹具影响和迁移成本；优先不要扩大本阶段修改面。
6. TEST-032 的 10 章场景中，哪些直接派生、聚合、上下文、报告、伏笔、tracking 和 search 行应失效或删除，哪些后续正文必须保留？
7. 现有 stale cascade 对编辑章自身 consistency report 的语义是否存在明确缺口？不要擅自扩展查询；请把证据和建议交给 Sol。

## 实施要求

1. 提出完整可编译 unified diff，不只给伪代码。
2. repository 使用 `ZhijuanDatabase.withTransaction` 协调 library/memory/search，复用现有失效与索引 writer，不复制它们的查询。
3. 新 DAO CAS 必须原子设置 current version、`EDITED`、`UNKNOWN`、updatedAt，并通过 expected old version 限定。
4. 新测试至少覆盖：10 章编辑第 3 章、旧版本保留、新版本字段、直接派生失效、后续正文保留且一致性未知、后续上下文/报告失效、旧搜索文档删除、精确 replay、并发过期请求回滚、跨书/错章回滚、同 ID 不同内容冲突。
5. 测试不得访问网络或真实 Provider，不使用真实 API key，不向物理设备写入。
6. 不用吞异常、无条件 upsert、先提交后补失效、自动删除后续章节等捷径。

## 验收标准

- [ ] 编辑操作是一个本地数据库原子事务，任何验证/CAS/删除失败都不留下半成品。
- [ ] 新 `USER_EDIT` 版本成为 current，旧版本仍可读取，编辑章状态为 `EDITED / UNKNOWN`。
- [ ] 旧版本相关派生行按已有失效图变为 stale，旧搜索 identity 被删除。
- [ ] 第 4–10 章正文与 current version 原样保留，但现有一致性未知与上下文/报告失效规则生效。
- [ ] 精确 replay 幂等；旧 expected current、跨书、错章、版本 ID 冲突均失败并回滚。
- [ ] 没有 schema/migration、Provider、联网、后台 job、跨章重建或 UI 改动。
- [ ] 返回值、异常与日志不暴露用户正文。

## Sol 后续验证

Sol 应用并审查补丁后至少执行：

```powershell
.\gradlew.bat :core:database:compileDebugKotlin :core:database:compileDebugAndroidTestKotlin --offline --no-daemon
.\gradlew.bat :core:database:connectedDebugAndroidTest --offline --no-daemon -Pandroid.testInstrumentationRunnerArguments.class=app.zhijuan.core.database.ChapterUserEditDatabaseTest
```

随后在 API 30/API 35 执行相关数据库全量测试、安全扫描与 `git diff --check`。未执行的验证必须明确写未执行，不能写成通过。

## 回交格式

1. `审计结论`
2. `建议契约与事务顺序`
3. `修改文件`
4. `验证`
5. `未完成/风险`
6. `需要 Sol 决策`
7. `Unified diff`

只返回文字和完整 unified diff；不得写仓库，不得宣布 Phase 1/TASK-061 完成。
