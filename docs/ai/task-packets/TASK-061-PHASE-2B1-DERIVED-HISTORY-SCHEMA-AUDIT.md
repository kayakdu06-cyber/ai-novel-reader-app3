# TASK-061 / Phase 2B1：派生历史槽与单一有效头 schema 审计

## 任务身份

- 任务 ID：`TASK-061 / Phase 2B1`
- 仓库根目录：`D:\gptuser\projects\ai-novel-reader-app2`
- 基准分支与 HEAD：`main` / `8ce774429da1c3f7139a221bc241c34d81a2efdd`；继续当前 dirty WIP，禁止回退、清理或覆盖未提交改动。
- 已完成前置：Phase 1 用户编辑原子失效；Phase 2A 只读影响计划与完整版本栅栏。10 章编辑第 3 章当前为 32 步、1 READY/31 BLOCKED。
- 执行模型：DeepSeek V4 Flash（纯文本，只读 schema/调用点审计和补丁提案）。

## 运行预算

- 推理等级：`max`。
- 最长运行时间：25 分钟。
- 累计 Token 上限：无总上限；仍受 25 分钟硬上限。
- 预计读取文件：22 个以内，仅限清单及代码直接引用的类型定义。
- 预计只读命令：最多 12 个；可以运行 `rg`、`git diff` 和只读编译分析，不得运行会改数据库或设备的测试。
- 提前停止条件：需要覆盖/删除历史正文或派生数据、需要放松 Provider/费用门禁、无法给出 schema v10→v11 的无损路径，或范围必须扩张到实际重建执行器。

## 目标

审计并提出 Phase 2B1 的最小 schema v11 方案，使同一个保留的 `chapter_version` 或同一个 `(book, throughChapterIndex)` 能保留多代 STALE 派生历史，同时数据库最多只允许一代 VALID 当前头；本子阶段只解决历史槽、权威读取与迁移，不执行重建、不处理完整伏笔 rewind、不建 Job、不联网。

必须给出可由 Sol 审查的完整 unified diff 提案，但不得写工作树。提案应优先小而完整：entity/index、v10→v11 migration、fresh-create guard、受影响 DAO 查询以及迁移/约束测试；如果该范围仍过大，应明确拆成更小可编译阶段，不能给半套会让权威查询返回任意历史行的方案。

## 当前现场与已有 WIP

- `ChapterSummaryEntity` 对 `chapter_version_id` 唯一。
- `ChapterTrackingProjectionEntity` 对 `chapter_version_id` 和 `generation_stage_id` 分别唯一。
- `AggregateStateProjectionEntity` 对 `(book_id, through_chapter_index)` 唯一。
- `ForeshadowTransitionEntity` 对 `(foreshadow_item_id, source_chapter_version_id)` 唯一。
- summary/tracking/aggregate/transition 都有 `DerivedDataStatus`；旧派生失效后必须继续保留，不允许 delete/replace。
- entity event、canon fact、timeline event 没有相同业务槽唯一索引，但现有按 chapter version 的 DAO 查询没有统一过滤 `VALID`；同一保留版本重建后会混入旧 STALE 行。
- `findSummaryForVersion`、`findTrackingProjectionForVersion` 等部分 DAO 以单行返回且未限定 `VALID`，如果只删除 unique index会产生任意行/Room 多行问题。
- `LibraryDatabaseGuards.callback` 会在 fresh create 与每次 open 安装触发器；相邻迁移集中在 `ZhijuanMigrations.ALL`，当前 schema 为 v10。
- Phase 2A 生产计划已在 `ChapterEditRebuildPlanRepository` 中明确暴露 `DERIVED_VERSION_SLOT_OCCUPIED` 等 blocker；Phase 2B1 完成后只能解除确实被 schema/读取契约关闭的 blocker，不能宣称 tracking 顺序和 foreshadow replay 已解决。

## 必读资料

1. `AGENTS.md`
2. `docs/24-AI-DEVELOPMENT-PROTOCOL.md`
3. `docs/ai/CURRENT-CONTEXT.md`
4. `docs/ai/task-packets/TASK-061-PHASE-2A-REBUILD-IMPACT-PLAN.md`
5. `docs/09-DATA-MODEL.md` 中派生表、schema v8/v10、TASK-061 相关段落
6. `docs/15-TEST-PLAN.md` 的 TEST-032/TEST-033 和第 25/26 节
7. `core/database/src/main/kotlin/app/zhijuan/core/database/ZhijuanDatabase.kt`
8. `core/database/src/main/kotlin/app/zhijuan/core/database/ZhijuanMigrations.kt`
9. `core/database/src/main/kotlin/app/zhijuan/core/database/LibraryDatabaseGuards.kt`
10. `core/database/src/main/kotlin/app/zhijuan/core/database/memory/MemoryEntities.kt`
11. `core/database/src/main/kotlin/app/zhijuan/core/database/memory/DerivedAuditEntities.kt`
12. `core/database/src/main/kotlin/app/zhijuan/core/database/memory/MemoryDao.kt`
13. `core/database/src/main/kotlin/app/zhijuan/core/database/library/ChapterEditRebuildPlanRepository.kt`
14. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterMemoryExtractionCommitRepository.kt`
15. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterTrackingProjectionJobFactory.kt`
16. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterTrackingProjectionCommitRepository.kt`
17. `core/database/src/main/kotlin/app/zhijuan/core/database/search/MemorySearchIndexWriter.kt`
18. `core/database/src/androidTest/kotlin/app/zhijuan/core/database/ZhijuanMigrationTest.kt`
19. `core/database/src/androidTest/kotlin/app/zhijuan/core/database/MemoryDatabaseTest.kt`
20. `core/database/src/androidTest/kotlin/app/zhijuan/core/database/ChapterEditRebuildPlanDatabaseTest.kt`

只可继续读取上述代码直接引用的 entity/type；不要读取历史报告、运行日志、密钥、其他项目或 C 盘。

## 范围

允许提议修改：

- `ZhijuanDatabase.kt`：schema version/entity 声明所需最小变化。
- `ZhijuanMigrations.kt`：无损 `MIGRATION_10_11` 与 `ALL` 注册。
- `LibraryDatabaseGuards.kt`：fresh create/open 的单一 VALID 约束或不可变历史保护。
- `MemoryEntities.kt`、`DerivedAuditEntities.kt`：仅为多代历史所需的 index/字段最小调整。
- `MemoryDao.kt`：把生产权威读取明确限定为 VALID，并新增显式 history 查询；写入接口保持 insert-only。
- `ZhijuanMigrationTest.kt`、`MemoryDatabaseTest.kt` 或一个新的有界 schema 测试。
- `ChapterEditRebuildPlanRepository.kt`：只在 schema 方案能诚实解除 `DERIVED_VERSION_SLOT_OCCUPIED` 时提出最小状态判定调整。

明确不在范围：

- 不实现实际重建 Job/Stage/Attempt/Usage、Provider 调用、费用扣除或 UI。
- 不删除/覆盖旧 summary/event/fact/timeline/tracking/aggregate/transition。
- 不改变章节正文/current version。
- 不删除 `ChapterTrackingProjectionSourceRepository` 的后续章节顺序保护。
- 不声称已解决 foreshadow current projection rewind；本阶段只保证 transition 历史槽不会强制覆盖旧行。
- 不修改 App 总 runner，不完成 TEST-033。
- 不写仓库；禁止 shell/Python/.NET 写文件或绕过 `apply_patch`。

## 必须审计的方案选择

请比较并选择一种最小可靠方案，不要机械接受建议：

1. 移除业务槽 unique index，保留普通索引，并用 `LibraryDatabaseGuards` 的 INSERT/UPDATE trigger 保证每个槽最多一行 `status='VALID'`；或
2. 新增显式 revision/head 表或 revision number，使 Room schema、查询、迁移和并发约束更清晰。

如果考虑 SQLite partial unique index，必须解释 Room 2.8.4 的 entity schema 表达/验证兼容性；不能依赖注解无法表达且 fresh-create/迁移验证可能漂移的索引。

无论选择哪一种，都必须覆盖：

- summary：每 `chapter_version_id` 最多一个 VALID，可有多条 STALE；
- tracking projection：每 `chapter_version_id` 最多一个 VALID；每 `generation_stage_id` 仍只能对应一个 projection；
- aggregate：每 `(book_id, through_chapter_index)` 最多一个 VALID；
- transition：每 `(foreshadow_item_id, source_chapter_version_id)` 最多一个 VALID；
- event/fact/timeline：权威读取只返回 VALID，历史读取显式返回全部；
- 旧 v10 数据原样保留并视作当前第一代；迁移不得重写正文/JSON/模型快照。

## 不可破坏的约束

- 所有派生行仍是 insert-only 历史，唯一允许的现有更新是 `VALID → STALE` 状态失效和既有 current projection CAS；不得通过 `INSERT OR REPLACE`、delete、先清表再建等捷径。
- “最多一个 VALID”必须由数据库并发约束保证，不能只靠 repository 先查后写。
- status 从 STALE 恢复 VALID 必须被阻止；身份/来源/正文 JSON/createdAt 不得更新。
- 所有生产单行查询必须显式限定 VALID；历史查询必须在命名上明确，不得让旧调用点静默改变为任意一行。
- 新 Stage 重放仍以 `generation_stage_id` 唯一和现有提交证据为准。
- 迁移必须覆盖 v1→最新完整路径和 v10→v11 直接路径，fresh create 与 migrated schema 必须一致。
- 默认字符串、错误和断言不得展开正文、summary JSON、人物/伏笔描述、模型输出或标识符。
- 真实 API 调用 0；物理设备写入 0。

## 必须回答的审计问题

1. 触发器方案与 revision/head 表方案中，哪个在当前 Room schema 和 dirty WIP 下更小且可长期审计？
2. 需要修改哪些现有 DAO 单行/列表查询才能避免 stale+valid 混读？逐一列出调用点风险。
3. `summaryStatus`、`aggregateStatus` 等按主键读取是否应保持不变，哪些按业务槽读取必须新增 `VALID` 条件？
4. 怎样阻止 `STALE → VALID`、内容字段 UPDATE 以及同一槽并发插入两个 VALID？
5. v10→v11 迁移应怎样重建索引而不复制或丢失任何旧行？Room 导出 schema 应有什么变化？
6. transition 放开历史槽后，为什么仍不能声称 foreshadow replay 已解决？下一阶段还缺哪些完整快照/CAS/rewind 证据？
7. Phase 2A 的 `DERIVED_VERSION_SLOT_OCCUPIED` 应在本阶段后如何细分，哪些仍必须 BLOCKED？

## 验收标准

- [ ] v10 全部旧派生行无损迁移到 v11。
- [ ] 每个业务槽允许任意多 STALE 历史，但数据库最多一个 VALID。
- [ ] 任意 `STALE → VALID` 和历史内容 UPDATE 失败关闭。
- [ ] 权威读取只返回 VALID，显式历史读取可返回稳定排序的全部代。
- [ ] 现有 generation stage replay 和 `generation_stage_id` 唯一性不被削弱。
- [ ] fresh create 与迁移 schema/guards 一致，完整迁移注册无断链。
- [ ] 不执行重建、不改正文、不联网、不声称 TEST-033 完成。

## Sol 后续验证

```powershell
.\gradlew.bat :core:database:compileDebugKotlin :core:database:compileDebugAndroidTestKotlin --offline --no-daemon
.\gradlew.bat :core:database:connectedDebugAndroidTest --offline --no-daemon '-Pandroid.testInstrumentationRunnerArguments.class=app.zhijuan.core.database.ZhijuanMigrationTest,app.zhijuan.core.database.MemoryDatabaseTest'
```

随后由 Sol 视实际差异拆分专项测试、双 API 全量回归、统一离线门禁、安全扫描和 `git diff --check`。

## 回交格式

请严格按以下标题返回：

1. `可行性结论`
2. `方案比较与选择`
3. `受影响查询/调用点`
4. `迁移与数据库约束`
5. `建议修改文件`
6. `验证`
7. `未完成/风险`
8. `需要 Sol 决策`
9. `Unified diff`

只返回文字和完整 unified diff；不得写仓库，不得宣布 Phase 2B1、Phase 2B 或 TASK-061 完成。
