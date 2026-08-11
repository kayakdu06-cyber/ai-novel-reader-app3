# TASK-061 Phase 2B3B：编辑后跨章有序重建设计审计

## 任务身份

- 任务 ID：`TASK-061 / Phase 2B3B 编辑后跨章有序重建设计审计`
- 仓库根目录：`D:\gptuser\projects\ai-novel-reader-app2`
- 基准分支与 HEAD：`main` / `8ce7744`；仅用于识别独立副本，不得回退工作树
- 当前未提交改动：存在大量 TASK-059～061、DeepSeek 隔离、测试、文档和报告 WIP，全部必须保留
- 执行模型：DeepSeek（纯文本）

## 运行预算

- 推理等级：`max`（用户持续要求最高推理强度）
- 最长运行时间：20 分钟
- 累计 Token 上限：不设置（用户明确允许；仍受 20 分钟硬上限约束）
- 提高时限理由：本任务同时涉及动态 rebuild plan、历史失效、远程 Stage、Provider-open、提交 replay 和跨章崩溃恢复，需完成一次跨文件只读设计审计；不得因外层短超时只返回中间思考
- 预计读取文件数：不超过 24 个，限于下方清单和这些文件直接引用的类型定义
- 预计执行命令/测试数：只读搜索与阅读；不构建、不测试、不写文件
- 提前停止条件：必须读取清单外大范围代码、需要真实 Provider、权限阻塞、需要接触其他项目、重复读取失败或无法在 20 分钟内形成最终结论

## 目标

审计 TASK-061 Phase 2B3B 的最小可靠实现路径：在保留第 4～10 章正文的前提下，让编辑章 memory、从编辑点开始逐章 tracking、每章 aggregate，以及必要的 context/consistency 工作严格按依赖推进；每个远程步骤必须继续复用正式 Job/Stage/Attempt/Usage/Provider-open/commit 边界，崩溃后可恢复，不能通过删除普通 tracking 顺序保护来实现。

本次只读，不修改任何文件。请重点判断是否必须引入 schema v14 的 rebuild execution/step 账本，还是可仅凭现有 generation_job/stage 与权威表安全实现；给出单一推荐，不要只罗列方案。

## 当前现场与已有 WIP

- Phase 1 已把编辑版本设为 current `USER_EDIT/EDITED/UNKNOWN`，保留后续 current version/正文；编辑旧来源派生、编辑点及以后 aggregate、后续 context/report 按规则 STALE。
- Phase 2A `ChapterEditRebuildPlanRepository` 生成动态计划，`planHash` 覆盖当前步骤状态；任何一步完成后 planHash 会变化。因此它可做“此刻状态”栅栏，但不能未经设计直接作为整个长执行的永久 ID。
- Phase 2B1～2B2B 已支持多代 STALE 历史、完整伏笔 revision 和受审计区间 rewind；rewind 后伏笔 current 回到编辑点前可信基线，区间 transition/revision 已失效。
- Phase 2B3A 已完成 `AggregateStateWriterRepository` 和计划 v2：aggregate 从权威 current 状态重算，绑定同章 tracking 代次，旧头 STALE，新头 VALID，精确 replay；不新增 schema。
- 当前计划仍把存在后续已提交章时的中间 tracking 标为 `TRACKING_ORDER_GUARD`，把当前版本已有旧 tracking 头标为 `DERIVED_VERSION_SLOT_OCCUPIED`。
- 普通 `ChapterTrackingProjectionSourceRepository.loadCurrentVersion` 拒绝任何后续已提交章节，这是正确的普通生成保护；`ChapterTrackingProjectionCommitRepository` 在提交时再次调用同一保护，不能全局删除。
- 后续章节的 summary/entity/fact 与正文仍可保留，但旧 tracking/timeline 属于编辑前伏笔链，需要按章先 STALE 再重建；旧历史不得删除。
- 当前 memory/tracking 各自已有严格 source factory、Provider-open guard、Attempt/Usage、结构校验和原子 commit；commit 可以激活同 Job 中已经存在的下一 Stage，但后续 tracking source hash 依赖前一步新结果，不能提前伪造。
- 当前 App 没有总 phase runner。本阶段只允许新增 TASK-061 专用执行入口或账本，不得顺便宣称整 App 已接通。

## 必读资料

1. `AGENTS.md`
2. `docs/24-AI-DEVELOPMENT-PROTOCOL.md`
3. `docs/ai/CURRENT-CONTEXT.md`
4. `docs/06-AI-GENERATION-SYSTEM.md` 第 10、27 节
5. `docs/09-DATA-MODEL.md` 第 22～25 节
6. `docs/10-STATE-MACHINES.md` 第 22 节
7. `docs/15-TEST-PLAN.md` TEST-032/033 与第 25～30 节
8. `docs/19-IMPLEMENTATION-BACKLOG.md` TASK-061 行
9. `core/model/src/main/kotlin/app/zhijuan/core/model/GenerationState.kt`
10. `core/model/src/main/kotlin/app/zhijuan/core/model/GenerationPhase.kt`
11. `core/database/src/main/kotlin/app/zhijuan/core/database/library/ChapterEditRebuildPlanRepository.kt`
12. `core/database/src/main/kotlin/app/zhijuan/core/database/library/ForeshadowProjectionRewindRepository.kt`
13. `core/database/src/main/kotlin/app/zhijuan/core/database/library/AggregateStateWriterRepository.kt`
14. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationEntities.kt`
15. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationJobSetupRepository.kt`
16. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationStateRepository.kt`
17. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationDao.kt`
18. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterMemoryExtractionJobFactory.kt`
19. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterMemoryExtractionCommitRepository.kt`
20. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterTrackingProjectionJobFactory.kt`
21. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterTrackingProjectionCommitRepository.kt`
22. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterContextAssemblyRepository.kt`
23. `core/database/src/main/kotlin/app/zhijuan/core/database/memory/MemoryDao.kt`
24. `core/database/src/androidTest/kotlin/app/zhijuan/core/database/AggregateStateWriterDatabaseTest.kt`

除清单和直接引用的类型定义外，不得递归扫描整套仓库、历史会话、日志、备份或其他项目。

## 范围

允许：

- 只读分析现有 plan、Job/Stage、source guard、memory/tracking commit、rewind、aggregate 和 DAO 契约。
- 给出推荐的持久执行身份、稳定冻结范围 hash、动态进度、step 证据、并发、崩溃恢复和 replay 语义。
- 明确普通 tracking 顺序保护与 edit-rebuild 专用许可如何共存。
- 判断旧 tracking/timeline/FTS 应在何时、以什么事务和证据转 STALE；判断是否需要 schema v14 审计/执行表。
- 明确远程步骤是单一长 Job 动态追加 Stage，还是确定性单步 Job；只能推荐一个主方案并说明排除另一个的理由。
- 明确 Phase 2B3B 是否应同时执行 context/consistency，还是先只闭合 memory→tracking→aggregate 关键链；必须按 TEST-033 和当前代码事实给出边界。
- 给出 Sol 可直接实施的文件级清单和双 API 测试矩阵。

明确不在范围：

- 修改文件、运行构建/测试、创建迁移或提交补丁。
- 调用 App 内真实 Provider、读取密钥、产生费用、生成小说正文。
- 实现通用总 runner、UI、模板或“从下一章重织”。
- 删除后续正文、覆盖/删除历史派生、放宽普通生成保护。
- 宣布 Phase 2B3B、TEST-033 或 TASK-061 完成。

## 不可破坏的约束

- 只能访问 app2 独立副本，绝不访问或修改 `D:\gptuser\projects\ai-novel-reader`。
- 后续 current 章节版本和正文必须原样保留。
- 普通 tracking 入口的“不得越过后续已提交章”保护必须保留；专用 rebuild 许可必须严格绑定编辑身份、冻结范围、目标章和已完成前驱。
- 旧 summary/tracking/timeline/transition/aggregate 等历史不得删除或原地覆盖，只允许既有契约允许的 `VALID → STALE` 后新增一代。
- 任何 stale + Job/Stage 创建必须考虑原子性；不能留下“旧头已 stale 但没有可恢复工作”的裂缝。
- 每个远程请求仍必须经过正式 Provider-open、Attempt、Usage、lease、格式校验和 commit 双重来源复核；不能伪造输出。
- 动态 planHash 会随合法进度变化；推荐方案必须区分稳定执行 fence 与动态状态 hash。
- 并发启动同一编辑重建只能有一个权威执行；崩溃重启不得重复 staling、重复请求或跳章。
- 错误和默认 `toString()` 必须脱敏；stage binding 不保存正文、API Key 或连接密钥。
- 不得把专用执行入口描述为整 App 总 runner。

## 必答问题

1. 当前 planHash 随步骤状态变化，稳定 execution ID/fence 应覆盖什么、排除什么？需要单独 persisted execution/step 表吗？
2. 一个长 Job 动态追加 Stage与一系列确定性单步 Job，哪种更符合现有 repository、崩溃恢复、费用和 source hash 依赖？为什么？
3. 编辑章 memory、每章 tracking、每章 aggregate 的唯一合法顺序是什么？context/consistency 在 TEST-033 前是否必须进入同一执行链？
4. 后续 current 版本已有旧 VALID tracking/timeline 时，如何在不误 stale 新代、不删除历史的情况下原子准备目标章并建立可恢复远程工作？FTS 如何同步？
5. tracking Stage 的 edit-rebuild binding 最少包含哪些字段？Provider-open 和 commit 各自如何重验？普通 v1 source binding 如何保持兼容？
6. 如何证明目标章是“第一个尚未完成的 tracking”，前一章 tracking/aggregate 已是本次执行产生或严格接受的代次，后面的章尚未提前重建？
7. 如何处理：进程在 stale 后崩溃、Job 已建未发请求、Provider 返回后 current 改变、commit 成功但 aggregate 未写、aggregate 写后重试、两个 worker 同时推进？
8. schema v14 若必须，列出最小表/字段/索引/外键/触发器/迁移；若不必须，说明现有哪组唯一约束足以承担 execution ledger。
9. 给出 P0/P1/P2 风险、最小文件改动和正向/并发/崩溃/篡改/legacy/双 API 测试矩阵。

## 验收标准

- [ ] 给出一个主推荐架构，而非多个无法决策的备选。
- [ ] 稳定 execution fence、动态计划状态、远程 Stage source 和权威派生头边界清楚。
- [ ] 普通 tracking 顺序保护未被删除或弱化。
- [ ] stale + 创建工作、commit + 后继推进、aggregate + 下一章解锁均有原子或可证明恢复语义。
- [ ] TEST-033 的完成边界与仍未完成的总 runner/UI/真实质量验证被明确区分。
- [ ] 方案可用 Fake Provider 和项目专用 API 30/API 35 模拟器验证。

## 验证命令

本次禁止构建、测试和任何工作树写入。只允许只读命令：

```powershell
git status --short
rg -n "ChapterEditRebuild|TrackingProjection|GenerationJob|GenerationStage" <明确文件>
Get-Content -Encoding UTF8 <明确文件>
```

## 回交格式

请严格按以下标题返回：

1. `当前事实`
2. `P0/P1/P2 风险`
3. `唯一推荐架构`
4. `持久数据与 schema 决策`
5. `逐步事务与恢复语义`
6. `source binding 与普通路径兼容`
7. `TEST-033 边界与测试矩阵`
8. `Sol 实施清单`
9. `未决问题与假设`

不要输出思考过程，不要修改文件，不要更新正式状态。
