# TASK-061 Phase 2B3B2E：通用 retained tracking 显式 step 设计复核

## 任务身份

- 任务 ID：`TASK-061 / Phase 2B3B2E 通用 retained tracking 显式 step 设计复核`
- 仓库根目录：`D:\gptuser\projects\ai-novel-reader-app2`
- 基准分支与 HEAD：`main / 8ce774429da1c3f7139a221bc241c34d81a2efdd`
- 当前未提交改动：仓库存在大量 TASK-059～061 WIP；本任务涉及的两个生产文件为 untracked WIP，feature 测试文件已修改，禁止清理、回退或从零重写。
- 执行模型：DeepSeek（纯文本）

## 运行预算

- 推理等级：`max`
- 最长运行时间：25 分钟。理由：用户已允许放宽思考时间；任务限定为四个文件和一个明确状态边界，但需要核对 crash replay、直接前驱和多章 identity。
- 累计 Token 上限：无。用户已明确不设置 Token 上限；仍受 25 分钟硬超时、单任务锁和受限沙箱约束。
- 预计读取文件数与明确清单：7 个，见“必读资料”。
- 预计执行命令/测试数：最多 6 个只读搜索/查看命令；不构建、不运行模拟器、不修改文件。
- 提前停止条件：范围需要扩张、需要读取密钥/其他项目、同一问题重复推理、发现必须改 schema 或无法在四个目标文件内表达。

## 目标

只读复核将 ordinal 4 的 retirement→Provider→tracking→aggregate 边界推广到 ordinal 6 及以后时的最小可靠设计。必须回答显式 target step 命令、直接前驱完成证明、精确 replay、越序/碰撞拒绝和现有 planner identity 是否足够，并给出按方法划分的修改建议与测试矩阵；不要修改工作树。

## 当前现场与已有 WIP

- 已存在的实现：`createNextRetainedTrackingStage` 只处理 ordinal 4；retirement evidence、Provider-open/commit、planner exact Stage identity 和同章 aggregate 已闭环。
- 已存在的测试：ordinal 4 的 retirement replay/并发/碰撞，以及 Fake Provider 正向/replay/aggregate 回滚均已通过 API 30/API 35。
- 已知失败或缺口：`requireNextRetainedTrackingReady`、`requireRetainedTrackingStageAllowed`、`requireRetainedAggregateBaselineAvailable` 和前驱 helper 硬编码第一保留章；无显式 target ordinal 的 next API 对更后章节 crash replay 有歧义。
- 必须延续、不得从零重写的部分：schema v15 execution/step/retirement、deterministic Job/Stage、tracking factory/commit repository、aggregate writer、planner identity 和现有 ordinal 4 兼容入口。

## 必读资料

1. `AGENTS.md`
2. `docs/24-AI-DEVELOPMENT-PROTOCOL.md`
3. `docs/ai/CURRENT-CONTEXT.md`
4. `reports/2026-08-08-105-task-061-phase-2b3b2d-retained-tracking-aggregate.md`
5. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterEditRebuildStageRepository.kt`
6. `core/database/src/main/kotlin/app/zhijuan/core/database/library/ChapterEditRebuildPlanRepository.kt`
7. `core/database/src/androidTest/kotlin/app/zhijuan/core/database/ChapterEditRebuildPlanDatabaseTest.kt`

除上述清单和代码直接引用外，不得递归扫描整套文档、历史会话、备份或无关模块；如确需查看 feature Fake Provider 测试，只允许额外读取 `feature/generation/src/androidTest/kotlin/app/zhijuan/feature/generation/ChapterTrackingProjectionEndToEndTest.kt` 中包含 `retainedChapter` 和 helper 的局部。

## 范围

允许修改：

- 无。本任务严格只读。

明确不在范围：

- schema/迁移、UI、总 runner、context/consistency、TEST-033 的完整实现、真实 Provider、密钥、物理设备、其他项目、正式状态文档。

## 不可破坏的约束

- 项目隔离：不得访问或修改其他项目副本。
- 多模态：无多模态输入。
- 安全与隐私：不得读取或显示 API Key；诊断不得展开正文、提示词、人物名称或完整 ID/hash。
- 状态机与幂等：每个后续 step 必须由显式 ordinal 唯一定位；exact replay 不得猜测“next”，不得跳过未完成直接前驱。
- 数据库与事务：退役旧 tracking/timeline/search、创建 replacement Stage、插入 retirement 仍是一个事务；tracking 与 aggregate 仍是另一个外层事务。不得把两者合并成跨网络事务。
- 联网与费用：不调用真实 API、不产生费用。
- 兼容性与构建基线：保留 `createNextRetainedTrackingStage` 的 ordinal 4 兼容语义，普通 tracking guard 不得放宽。
- 需要保留的用户/未提交改动：全部现有 WIP。

## 实施要求

1. 判断新增显式命令/方法的最小形态，说明是否保留旧 wrapper。
2. 给出通用 target ordinal 与 chapter index 的公式/校验，以及直接前一章 tracking+aggregate 的完整成功证据。
3. 检查现有 `authorizedRetainedTrackingProjectionIdsForPlan` 对多 retirement 是否已足够，列出必须补的身份/状态约束。
4. 给出越序、旧命令 replay、completed replay、identity collision、current range 变化、前驱 Stage/aggregate 缺失的测试矩阵。
5. 不允许采用自动选择最小未完成 step、预建全部 Stage、恢复旧 tracking 为 VALID、删除普通顺序保护或只靠 VALID 槽判断完成等捷径。

## 验收标准

- [ ] 明确一个主方案，不并列多个无结论方案。
- [ ] 覆盖 ordinal 4 兼容与 ordinal 6+ 显式目标。
- [ ] 覆盖直接前驱、replay、并发、碰撞和事务边界。
- [ ] 逐个指出 StageRepository / planner / database test 需要改哪些方法。
- [ ] 不修改文件、不运行真实 API、不宣布 TASK-061/TEST-033 完成。

## 验证命令

本任务只读，不运行 Gradle 或模拟器。可运行：

```powershell
git status --short
git diff --check
```

未运行的验证必须写明原因，不能写成通过。

## 回交格式

请严格按以下标题返回：

1. `完成内容`
2. `修改文件`
3. `验证`
4. `未完成/风险`
5. `需要 Sol 处理`
6. `假设`

在“完成内容”中先给唯一主方案和严重风险，再给方法级修改清单与测试矩阵。不要宣布整个 TASK 完成，也不要更新正式完成状态；由 Sol 根据差异和测试证据确认。
