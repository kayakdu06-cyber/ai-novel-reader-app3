# TASK-064 Phase 1：持久 total runner 边界与最小状态原语审计

## 任务身份

- 任务 ID：`TASK-064 / Phase 1 持久 total runner 边界与最小状态原语审计`
- 仓库根目录：`D:\gptuser\projects\ai-novel-reader-app2`
- 基准分支与 HEAD：`main` / `8ce774429da1c3f7139a221bc241c34d81a2efdd`
- 当前未提交改动：约 196 条状态行，包含 TASK-059～063 的既有 WIP 和已验证代码；只读审计，不得修改、回退、清理或覆盖任何文件
- 执行模型：DeepSeek（纯文本）

## 运行预算

- 推理等级：`max`。
- 最长运行时间：30 分钟。理由：该子任务只裁决 runner 的领取、游标和 Stage 交接边界，但需要同时核对 Room 事务、双执行器、租约、恢复和既有动态 Stage 链；用户已允许放宽 DeepSeek 思考时长。
- 累计 Token 上限：无。仍受 30 分钟硬超时、单任务锁和项目隔离约束。
- 预计读取文件数与明确清单：17 个，见“必读资料”。
- 预计执行命令/测试数：最多 14 个只读搜索/查看命令；不构建、不测试、不修改文件。
- 提前停止条件：需要读取密钥或其他项目；需要扩大到 Provider 实现、UI、watchdog、生成中正文或数据库迁移实现；无法在所列文件和其直接引用内证明结论；同一问题反复推理不收敛。

## 目标

只读审计 TASK-064 第一阶段的最小可靠边界：判断现有 `generation_job.current_stage_id`、Job/Stage 状态和租约是否足以承载持久 total runner，还是确有必要增加新的持久游标；给出一个不会重复发网、不会重复提交、能在进程重启和双执行器下收敛的 phase dispatcher/runner 方法级设计。不要写代码。

## 当前现场与已有 WIP

- TASK-062 已有脱敏时序表、monotonic/boot 时钟与 BODY 接线；TASK-063 已有确定性 Fake Provider。
- `GenerationJobSetupRepository` 会创建 Job、PENDING Stage 并把 Job 的 `current_stage_id` 指向第一 Stage。
- `InitialPlanningCommitRepository`、`ChapterContextAssemblyRepository`、`ChapterCandidateArtifactSealRepository` 和最终提交仓库已在同一事务内把成功 Stage 结算、激活后继 Stage 并推进 Job 游标，或完成 Job。
- `GenerationStateRepository` 已有 CREATED→READY、Job lease、PENDING→READY、Stage lease、heartbeat 与过期租约回收原语。
- `ChapterFinalCandidateCommitStageExecutorV1` 是未来 runner 的唯一 `COMMIT_CHAPTER` 入口。
- 当前没有正式 phase dispatcher/total runner；现有 Android E2E 都由测试代码手工调用状态转换和协调器。
- 必须延续现有 WIP，不得从零重写状态机、提交仓库、Provider 执行器或候选链。

## 必读资料

1. `AGENTS.md`
2. `docs/24-AI-DEVELOPMENT-PROTOCOL.md`
3. `docs/ai/CURRENT-CONTEXT.md`
4. `docs/19-IMPLEMENTATION-BACKLOG.md` 中 TASK-064 行
5. `docs/25-RELIABILITY-AND-GENERATION-PERFORMANCE-ROADMAP.md` 中 TASK-064、失败语义和速度门槛
6. `core/model/src/main/kotlin/app/zhijuan/core/model/GenerationState.kt`
7. `core/model/src/main/kotlin/app/zhijuan/core/model/GenerationPhase.kt`
8. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationEntities.kt`
9. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationDao.kt`
10. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationStateRepository.kt`
11. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationJobSetupRepository.kt`
12. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/InitialPlanningCommitRepository.kt`
13. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterContextAssemblyRepository.kt`
14. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterCandidateArtifactSealRepository.kt`
15. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterFinalCandidateCommitRepository.kt`
16. `feature/generation/src/main/kotlin/app/zhijuan/feature/generation/ChapterFinalCandidateCommitStageExecutor.kt`
17. `feature/generation/src/main/kotlin/app/zhijuan/feature/generation/GenerationTimingRecording.kt`

除上述清单和代码直接引用外，不得递归扫描整套文档、历史会话、备份或无关模块；确需读取直接引用时，在最终回交列出文件和原因。

## 范围

允许修改：

- 无。本任务严格只读。

明确不在范围：

- 任何代码、schema、迁移、文档或测试修改。
- Provider adapter、真实联网、请求内容、密钥、费用、UI、WorkManager/Service 生命周期实现。
- TASK-065 生成中正文、TASK-066 watchdog、TASK-067 优化、TASK-068 故障矩阵。
- 从创建快照生成完整 Job 图的产品编排细节；本次只裁决已持久 Job 的领取、当前 Stage 分发、交接和恢复边界。

## 不可破坏的约束

- 项目隔离：不得访问或修改其他项目副本。
- 多模态：无多模态输入。
- 安全与隐私：runner 结果/日志只允许有限枚举、ID 指纹、计数和时间；不得保存正文、prompt、endpoint、secret、provider request id 或异常自由文本到新的诊断事实。
- 状态机与幂等：Job/Stage 状态机、Attempt intent-before-send、UNKNOWN 不自动重发、动态候选 Stage lineage 和现有 idempotency key 均不可绕过。
- 数据库与事务：不得提出“先推进游标再提交业务”或跨事务猜成功；Stage 成功、后继激活与 Job 游标推进继续由现有业务提交事务裁决。
- 联网与费用：不得调用真实 API，不得产生费用。
- 兼容性：不更改既有 537/537 JVM、双 API 39/39 与 Release/R8 基线。
- 保留当前全部未提交 WIP；不得 reset、clean、checkout、commit 或添加 Git remote。

## 审计要求

1. 画出已持久 Job 从 `CREATED` 到首 Stage 可执行、Stage 成功交给后继、Job 完成/暂停/NEEDS_ACTION/UNKNOWN 的精确状态表；区分 runner 可以做的 CAS 与只能由现有提交仓库做的事务。
2. 判断是否需要 schema v17 或新 runner 表。若不需要，说明 `current_stage_id`、状态和租约如何构成恢复游标；若需要，只能给出不可替代的证据，不能因为“更清晰”就加表。
3. 定义最小接口：runner tick 输入/输出、phase dispatcher 结果枚举、Stage executor 接口、时钟/worker identity、一次 tick 的最大副作用；给出 Kotlin 类型和方法签名草案，但不写实现。
4. 定义 CREATED/READY/RUNNING/PAUSING/PAUSED/NEEDS_ACTION/BLOCKED/STOPPING/STOPPED/COMPLETED 的处理；特别说明 RUNNING Job 在重启后仍持有未过期/已过期 Job lease 时怎么办。
5. 定义 PENDING/READY/PREPARING/REQUEST_INTENT_RECORDED/STREAMING/VALIDATING/COMMITTING/RETRY_WAIT/UNKNOWN_RESULT/RECOVERY_REQUIRED/NEEDS_ACTION/SUCCEEDED/CANCELLED 的处理；严禁把“executor 返回异常”直接等价为“可重试”。
6. 分析双执行器竞争：Job lease 与 Stage lease 的获取顺序、同一 owner/不同 owner、提交后游标已推进但 runner 仍持有旧对象、StaleGenerationStateException 的正确归类。
7. 指出当前 DAO/Repository 缺少的最小只读或 CAS 原语，并按风险排序。优先复用现有提交事务，不能让 runner 直接调用 internal DAO 绕过仓库门禁。
8. 明确 TASK-062 事件应由 runner 在何处发射：CHAPTER_REQUESTED、STAGE_QUEUED、STAGE_STARTED、各派生开始/结束、COMMIT_STARTED/COMMIT_COMPLETED、CHAPTER_COMPLETED、NEXT_CHAPTER_STARTED；不得让失败路径伪造成功时点。
9. 给出 Phase 1 最小实现文件清单和测试矩阵，至少覆盖：首 tick 激活、双 runner 竞争、进程重启前请求、请求后 UNKNOWN、Stage 提交后游标推进、暂停安全点、最终 COMMIT 唯一入口、Fake-only 且真实 Provider 0。
10. 列出任何会使当前 TASK-064 验收目标无法成立的既有设计缺口，并区分“本阶段必须修”与“后续 064 子阶段可修”。

## 验收标准

- [ ] 只给一个推荐架构，不并列堆砌多个方案。
- [ ] 明确回答是否需要新 schema/runner 表，并给出持久恢复证据。
- [ ] 每个状态的处理有有限结果，不用 catch-all 自动重试。
- [ ] 双执行器和重启不会重复发网或重复提交。
- [ ] COMMIT_CHAPTER 唯一指向 `ChapterFinalCandidateCommitStageExecutorV1`。
- [ ] 给出逐文件/逐方法的 Phase 1 实现顺序与自动化测试矩阵。
- [ ] 不修改文件、不运行测试、不宣布 TASK-064 完成。

## 验证命令

本任务只读，不运行 Gradle 或模拟器。允许：

```powershell
git status --short
rg -n "currentStageId|acquireJobLease|acquireStageLease|compareAndAdvanceJobStage|compareAndCompleteJobAfterStage" core feature
```

## 回交格式

请严格按以下标题返回：

1. `完成内容`
2. `推荐架构与状态表`
3. `逐文件实施清单`
4. `测试矩阵`
5. `未完成/风险`
6. `需要 Sol 处理`
7. `假设`

不要宣布整个 TASK 完成，也不要更新正式完成状态；由 Sol 根据后续差异和测试证据确认。
