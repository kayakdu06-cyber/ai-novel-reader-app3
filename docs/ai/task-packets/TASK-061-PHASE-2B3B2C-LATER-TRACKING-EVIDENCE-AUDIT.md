# TASK-061 Phase 2B3B2C：后续章节追踪重建证据审计

## 任务身份

- 任务 ID：`TASK-061 / Phase 2B3B2C 后续章节追踪重建证据审计`
- 仓库根目录：`D:\gptuser\projects\ai-novel-reader-app2`
- 基准分支与 HEAD：`main` / 以当前工作树为准；不得 reset、checkout、clean、commit
- 当前未提交改动：存在大量 TASK-059～061、测试、文档和隔离配置 WIP，全部保留
- 执行模型：DeepSeek（纯文本，只读审计）

## 运行预算

- 推理等级：`max`
- 最长运行时间：15 分钟
- 累计 Token 上限：不设置；仍受 15 分钟硬上限约束
- 预计读取文件数：不超过 13 个，仅限下方清单及其直接类型引用
- 预计命令/测试数：只读搜索和阅读；不构建、不测试、不写文件
- 提前停止条件：需要访问清单外大范围代码、需要真实 Provider、权限阻塞、重复失败或无法在时限内形成单一建议

## 目标

审计 TASK-061 在“第一章 tracking→aggregate 已闭环”之后，如何安全推进第二章及后续保留正文的 tracking→aggregate。必须给出一个明确主方案，重点解决：旧 VALID tracking/timeline 如何与新确定性 Stage 原子交接，以及动态计划如何可靠识别“本次 execution 已重建的新 tracking”，不能把它误判为旧槽位占用。

本次只读，不修改任何文件。请在“schema v15 不可变 per-step outcome/retirement 证据”与“无迁移、仅靠现有 Stage binding/ledger 推导”之间作出单一选择；可靠性优先，不以少改代码为最高目标。

## 当前现场与已有 WIP

- Room schema v14 已有不可变 `chapter_edit_rebuild_execution` / `chapter_edit_rebuild_step`，步骤只记录准备时 `PENDING/SATISFIED` 和旧 summary/tracking/aggregate 基线。
- 第一章 edited-memory、tracking、aggregate 已通过正式 Job/Stage/Attempt/Usage、Provider-open/commit 双门禁闭环。
- 后续章节的 current 正文与 memory 保留；准备时旧 VALID tracking 被记录为 baseline，因此计划当前返回 `DERIVED_VERSION_SLOT_OCCUPIED`。
- edit 后 rewind 已把影响区间 foreshadow transition/revision 设为 STALE 并恢复投影；但后续章节自己的旧 tracking/timeline 仍 VALID。
- 现有 `ChapterEditRebuildStageBindingV1` 已包含 execution/fence/step/source，确定性 Job/Stage ID 由 binding 推导。
- 现有 `commitAggregateAfterTrackingIfBound`、tracking authorization 和 aggregate baseline 检查硬编码第一章。
- 关键缺口：后续 tracking 新头写入后，普通 planner 对任何非首章 VALID tracking 仍返回 `DERIVED_VERSION_SLOT_OCCUPIED`；`AggregateStateWriterRepository` 又要求传入的当前 plan 中 aggregate step 为 READY。
- 必须延续当前实现，不得从零重写，不得放宽普通 tracking 的章节顺序守卫。

## 必读资料

1. `AGENTS.md`
2. `docs/24-AI-DEVELOPMENT-PROTOCOL.md`
3. `docs/ai/CURRENT-CONTEXT.md`
4. `docs/15-TEST-PLAN.md` 中 TEST-033
5. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterEditRebuildStageRepository.kt`
6. `core/database/src/main/kotlin/app/zhijuan/core/database/library/ChapterEditRebuildExecutionEntities.kt`
7. `core/database/src/main/kotlin/app/zhijuan/core/database/library/ChapterEditRebuildExecutionDao.kt`
8. `core/database/src/main/kotlin/app/zhijuan/core/database/library/ChapterEditRebuildPlanRepository.kt`
9. `core/database/src/main/kotlin/app/zhijuan/core/database/library/AggregateStateWriterRepository.kt`
10. `core/database/src/main/kotlin/app/zhijuan/core/database/memory/MemoryDao.kt`
11. `core/database/src/main/kotlin/app/zhijuan/core/database/search/MemorySearchIndexWriter.kt`
12. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterTrackingProjectionCommitRepository.kt`
13. `core/database/src/androidTest/kotlin/app/zhijuan/core/database/ChapterEditRebuildPlanDatabaseTest.kt`

除上述清单和直接类型定义外，不得递归扫描整套仓库、历史报告、日志、备份或其他项目。

## 范围

允许：

- 只读分析后续章节 tracking/timeline/search retirement、Stage 创建、commit、aggregate 和 replay 的事务边界。
- 明确最小 schema（若需要）、字段、唯一索引、外键、不可变触发器和查询边界。
- 给出 planner 或 execution-specific permit 如何识别权威新 tracking 的文件级方案。
- 给出第二章最小 Android 数据库测试矩阵。

不在范围：

- 修改文件、构建或测试。
- context/consistency 总 runner、UI、模板、真实生成质量和速度优化。
- 调用 App 真实 Provider、读取密钥、联网或产生费用。
- 宣布 TASK-061 / TEST-033 完成。

## 不可破坏的约束

- 只访问 app2；不得访问 `D:\gptuser\projects\ai-novel-reader` 或其他副本。
- 后续 current 正文和版本原样保留；历史 tracking/timeline 不删除、不覆盖，只允许 `VALID→STALE` 后新增一代。
- timeline 的 FTS/search 文档是派生缓存，可删除旧 source identity，但必须与权威 stale 在同一事务。
- stale old tracking/timeline 与创建确定性新 Job/Stage 必须原子；失败不能留下“旧头已 stale 但无可恢复工作”。
- 并发启动只能收敛为一个 Stage；重放不得二次 stale、二次请求或跳章。
- Provider-open 与 commit 都必须重验 execution、stable fence、目标步骤、current source、前驱 completion 和 retirement/outcome 证据。
- 新 tracking commit 后必须能可靠推进同章 aggregate；不得通过假造 planner 状态或放宽普通路径实现。
- 错误和 `toString()` 脱敏；不保存正文、API Key 或连接密钥到新证据表。

## 必答问题

1. 是否必须 schema v15？只能给一个结论，并说明为什么另一路不足以证明 planner 看到的是本次 execution 的新 tracking。
2. 若需 v15：给出最小表名、字段、主键/唯一索引、外键、状态或不可变策略、迁移和 trigger；区分“retirement 已原子准备”与“tracking/aggregate 已完成”的证据。
3. 第二章 Stage 创建事务内必须按什么顺序验证 baseline、捕获 timeline/search identities、stale、创建 Job/Stage、写证据和回读？重放如何判定？
4. 新 tracking commit 后 planner 或 aggregate writer 如何验证 `projection.generationStageId` 确实等于该 execution/step 的确定性 Stage，而不是任意 VALID 头？
5. 前一章 tracking+aggregate、本章旧 baseline、本章 current source、后一章尚未推进，分别用什么持久证据证明？
6. 进程在 stale 后、Stage 创建后、Provider 返回后、tracking commit 后、aggregate 写后崩溃，以及两个 worker 并发时，恢复语义是什么？
7. 哪些现有函数应泛化，哪些第一章逻辑应保留；给出最小文件修改清单和 API30/API35 测试矩阵。

## 验收标准

- [ ] 单一架构结论，不罗列多个未决选项。
- [ ] planner/aggregate 对新 tracking 的身份判断有不可伪造持久证据。
- [ ] stale + Stage 创建、tracking commit + aggregate、replay/并发/崩溃边界清楚。
- [ ] 普通 tracking 顺序守卫保持不变。
- [ ] 给出可以先只实现“第二章 retirement + Stage 创建”的最小安全切片。

## 验证命令

本次禁止构建和测试，只允许 `rg` 与 `Get-Content -Encoding UTF8`。

## 回交格式

请严格按以下标题返回：

1. `当前事实`
2. `唯一推荐架构`
3. `持久化与 schema 决策`
4. `原子事务、并发与恢复`
5. `planner 与 aggregate 身份证明`
6. `最小实施切片`
7. `API 30/API 35 测试矩阵`
8. `未解决风险与假设`

不要输出思考过程，不要修改文件，不要更新正式任务状态。
