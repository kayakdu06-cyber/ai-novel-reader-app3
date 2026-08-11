# TASK-064 Phase 2E5B：chapter-plan expectation 权威来源只读审计

## 任务身份

- 任务 ID：`TASK-064 / Phase 2E5B chapter-plan expectation source audit`
- 仓库根目录：`D:\gptuser\projects\ai-novel-reader-app2`
- 基准分支与 HEAD：`main` / `8ce774429da1c3f7139a221bc241c34d81a2efdd`
- 当前未提交改动：长期连续 WIP，包含 TASK-059～083 生产代码、测试、文档与 DeepSeek 隔离文件；必须原样保留，不得清理或回退
- 执行模型：DeepSeek（纯文本）

## 运行预算

- 推理等级：`max`
- 最长运行时间：30 分钟；这是单一架构审计，不需要45分钟
- 累计 Token 上限：无
- 预计读取文件数与明确清单：14 个，见“必读资料”
- 预计执行命令/测试数：只读搜索/读取；不运行 Gradle、不启动模拟器、不修改文件
- 提前停止条件：需要扩展到 schema migration、发现无法由现有持久事实决定、权限阻塞、范围需要扩张或重复失败

## 目标

审计普通 `CHAPTER_PLAN_V1` 在 Provider 请求前构造 `ChapterPlanExpectationV1` 所需的唯一权威来源。比较至少三条路线，给出最小、可持久重验、不会把成人呈现档误解为“每章必有相关场景”的推荐方案，并列出具体字段、版本化、生产者/消费者门禁、兼容与测试矩阵。只交付文本审计，不写代码。

## 当前现场与已有 WIP

- 已存在的实现：chapter-plan严格route、48KiB输出合同、动态`ChapterPlanExpectationV1`/validator、TASK-083预算/目的地门禁、Phase2E5A exact Job+Stage RequestIntent准备。
- 已存在的测试：plan parser/validator JVM 9项；Phase2E5A reservation专项双API各47项，数据库各276项，generation各48项。
- 已知失败或缺口：`WindowChapterBriefV1`只含标题、目标、冲突、转折、结果、钩子和continuity；没有本章相关场景意图或参与人物。`ReadyChapterContext`只公开payload/hash/count，不直接给expectation。不能从全书成人呈现档推断每章相关。
- 必须延续、不得从零重写的部分：`SceneExecutionContract`、`PromptBundleCatalogV1.resolveScene`、`ChapterPlanExpectationV1`、arc-window持久OutlineNode、context manifest、progression gate、DEC-068/070/074和Phase2E5A bound preparation。

## 必读资料

1. `AGENTS.md`
2. `docs/24-AI-DEVELOPMENT-PROTOCOL.md`
3. `docs/ai/CURRENT-CONTEXT.md`（重点第43节）
4. `reports/2026-08-09-138-task-064-phase-2e5a-chapter-plan-bound-request-preparation.md`
5. `docs/06-AI-GENERATION-SYSTEM.md`（第43、46节）
6. `docs/18-DECISION-LOG.md`（DEC-068、DEC-070、DEC-074）
7. `core/task/src/main/kotlin/app/zhijuan/core/task/PromptBundleContract.kt`
8. `feature/generation/src/main/kotlin/app/zhijuan/feature/generation/ChapterPlanStructuredOutput.kt`
9. `feature/generation/src/main/kotlin/app/zhijuan/feature/generation/ArcWindowPlanningStructuredOutput.kt`
10. `feature/generation/src/main/kotlin/app/zhijuan/feature/generation/ArcWindowPlanningPersistenceMapper.kt`
11. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ArcWindowPlanningCommitRepository.kt`
12. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterContextAssemblyRepository.kt`
13. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterProgressionGateRepository.kt`
14. `feature/generation/src/test/kotlin/app/zhijuan/feature/generation/ArcWindowPlanningStructuredOutputTest.kt` 与 `ChapterPlanStructuredOutputTest.kt`

除上述清单和代码直接引用外，不得递归扫描整套文档、历史会话、备份或无关模块；需要扩展读取范围时在回交中说明。

## 范围

允许修改：

- 无。只读审计，工作树不得产生任何差异。

明确不在范围：

- 不实现代码、schema、migration、DAO、UI、Provider、prompt文案或测试。
- 不注册 `CHAPTER_PLAN_V1`。
- 不调用App真实Provider或DeepSeek以外服务。
- 不把“用户选择成人呈现档”直接当作“每章必须有相关场景”。

## 不可破坏的约束

- 项目隔离：不得访问或修改其他项目副本。
- 多模态：本任务无图像输入，不得延伸UI判断。
- 安全与隐私：不得输出正文、人物名、endpoint、凭据或hash实值；只讨论字段和合同。
- 状态机与幂等：推荐方案必须可在Provider-open和commit时从持久事实重算，支持replay，不能依赖短生命周期内存布尔值。
- 数据库与事务：说明是否需要schema migration；优先评估复用既有不可变OutlineNode payload/Stage input，不能另造可漂移shadow状态。
- 联网与费用：0真实API、0Provider调用、0费用。
- 兼容性与构建基线：minSdk29/API30+35；旧arc-window输出和现有首章fast-lane不得被静默误解释。
- 保留全部当前未提交改动。

## 审计要求

1. 比较至少三案：
   - 扩展`WindowChapterBriefV1`/持久CHAPTER OutlineNode，显式冻结逐章相关场景意图和参与人物；
   - 把`SceneExecutionContract.Allowed`改成允许0个相关场景，由chapter-plan模型自行决定；
   - 在context/plan之间新增本地派生或独立持久意图。
2. 对每案分析：权威性、模型注入风险、每章误强制风险、成年虚构人物门禁、重启/replay、版本兼容、迁移复杂度、initial DRAFT来源和测试成本。
3. 推荐一个最小方案，并给出精确字段、enum/版本、hash绑定、生产者和消费者、失败关闭条件。
4. 明确旧`arc-plan.v1`/既有持久OutlineNode如何处理：拒绝、重建窗口、还是兼容为NotApplicable；不得用猜测迁移。
5. 指出`ReadyChapterContext`是否需要新增公开结构，或能否从权威Room事实单独构造expectation，避免把自由文本payload当指令。
6. 给出最小实现切片和测试矩阵，但不要写代码。

## 验收标准

- [ ] 推荐方案有唯一权威来源且可在Provider-open/commit重算。
- [ ] 不把成人呈现档等同于每章相关场景。
- [ ] 不允许chapter-plan模型自行增加未计划的相关场景。
- [ ] 成年、虚构和参与人物身份有失败关闭来源。
- [ ] 旧数据/旧合同处理明确，不猜测回填。
- [ ] 不产生代码或工作树差异。

## 验证命令

无构建命令。回交前仅报告 `git status --short` 前后计数与是否一致；不得打印完整敏感差异。

## 回交格式

请严格按以下标题返回：

1. `完成内容`
2. `方案比较`
3. `推荐合同`
4. `兼容与失败关闭`
5. `测试矩阵`
6. `未完成/风险`
7. `需要 Sol 处理`
8. `假设`

不要宣布整个 TASK 完成，不要更新正式状态。
