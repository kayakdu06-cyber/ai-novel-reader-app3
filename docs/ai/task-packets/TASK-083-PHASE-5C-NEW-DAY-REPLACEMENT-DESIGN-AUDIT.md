# TASK-083 Phase 5C：新日替代请求与受保护种子复制设计审计

## 任务身份

- 任务 ID：`TASK-083 / Phase 5C new-day replacement design audit`
- 仓库根目录：`D:\gptuser\projects\ai-novel-reader-app2`
- 基准分支与 HEAD：`main` / `8ce774429da1c3f7139a221bc241c34d81a2efdd`
- 当前连续 WIP：约 268 条 `git status --short`，均属于用户持续开发；禁止 reset/clean/checkout/覆盖/整理
- 执行模型：DeepSeek V4 Flash（纯文本只读架构审计）

## 运行预算

- 推理等级：`max`
- 最长运行时间：30 分钟；这是已经过 Phase 5A/5B 设计与实现的窄后续，只审计三个生产边界和两个测试入口
- 累计 Token 上限：无
- 预计读取：本任务列出的 12 个文件及其直接引用
- 预计命令：只允许 `git status`、`rg`、`Get-Content`、`git diff` 等只读命令；不运行 Gradle、模拟器、Provider、网络或写文件命令
- 提前停止条件：正确方案必须新增 schema/migration/trigger，或必须扩展到真实 Provider/profile/total runner 注册才能成立

## 目标

只读审计 Phase 5B 已把旧未发送请求原子释放并将 Stage/Job 重新排队之后，如何在持久 runner 重新取得精确 Job+Stage 租约后，安全创建“新日、新 Attempt、新 reservation、新受保护 artifact”的替代请求。必须证明旧跨日证据、最新父 Attempt、精确租约和新 RequestIntent 在同一个 Room 事务中重新核验；非空旧草稿只允许有界内存复制并清零，空草稿也必须分配新的唯一 artifact。

本任务不写代码、不改文档、不运行测试、不注册 route、不调用 App Provider、不宣称 Phase 5C/TASK-083 完成。

## 当前现场与冻结事实

- Phase 5B 已完成：跨日时旧 Attempt=`FAILED_RETRYABLE/DAILY_BUDGET_PERIOD_EXPIRED_BEFORE_SEND`，旧 Usage=`UNKNOWN/FINAL` 且无值，旧 reservation=`RELEASED` 且 accounted=0；有剩余次数时 Stage/Job=`READY` 并清租约，达到上限则二者=`NEEDS_ACTION`。
- 持久 runner 的正常续跑顺序为：scan READY → claim Job（Job RUNNING，Stage 仍 READY）→ acquire current Stage lease（Stage PREPARING）→ resolve exact route。
- `GenerationDao.recordRequestIntent` 会把新 Attempt No 设为 `stage.attemptCount + 1`，并要求 retry parent 是同 Stage 的最新可重试 Attempt；成功后 Stage=`REQUEST_INTENT_RECORDED`、attemptCount+1。
- `PersistentBudgetReservationRepository.recordBudgetedRequestIntent` 已把候选 reservation、三层聚合、Attempt、UNKNOWN/PROVISIONAL Usage、Stage 放在同一 Room 事务中；候选 reservation 是事务内第一笔预算写。
- `GenerationStreamingDraftRepository.prepareBeforeSendInternal` 先创建唯一受保护 artifact，再持久化 RequestIntent；数据库失败会删除新 artifact。`createAndClear` 会清零传入 ByteArray。
- `AndroidProtectedArtifactStore.readBytes` 返回 lease，lease close 会清零内部 ByteArray；不得写明文临时文件。
- 旧未发送 artifact 通常为空，但续写种子可能非空；旧 Attempt/artifact 不能删除、修改、共享或复用。
- `GenerationStreamingDraftRepository` 的 `LIFECYCLE_LOCK` 已覆盖 prepare 与 cleanup，可用于同进程 artifact 生命周期串行化。
- Phase 5C 只交付可由未来 persistent runner 调用的准备原语与设备测试；当前 total runner 仍未完成，`CHAPTER_PLAN_V1` 仍未注册。

## 必读资料

1. `AGENTS.md`
2. `docs/24-AI-DEVELOPMENT-PROTOCOL.md`
3. `docs/ai/CURRENT-CONTEXT.md` 第 40 节
4. `reports/2026-08-09-133-task-083-phase-5a-cross-midnight-design-audit.md`
5. `reports/2026-08-09-134-task-083-phase-5b-provider-open-daily-rollover.md`
6. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationDao.kt` 的 `recordRequestIntent`、Attempt/Usage/reservation 查询
7. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/PersistentBudgetReservationRepository.kt`
8. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationRequestAuditRepository.kt`
9. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationStreamingDraftRepository.kt`
10. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationRunnerQueueRepository.kt`
11. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationRunnerExecutionLeaseRepository.kt`
12. `core/security/src/main/kotlin/app/zhijuan/core/security/AndroidProtectedArtifactStore.kt` 与 `ProtectedArtifact.kt`
13. `core/database/src/androidTest/kotlin/app/zhijuan/core/database/PersistentBudgetReservationDatabaseTest.kt` 相关 fixture
14. `feature/generation/src/androidTest/kotlin/app/zhijuan/feature/generation/AuditedStreamingProviderExecutorTest.kt` 相关 prepare/rollover fixture

除直接引用外，不得递归扫描整套历史文档、会话、备份或其他项目。

## 必答问题

1. 最窄生产 API 应放在 `GenerationStreamingDraftRepository`、`GenerationRequestAuditRepository` 和 `PersistentBudgetReservationRepository` 的哪几层？请给出具体方法职责，不要让 runner 自行拼 artifact 或直调低层 DAO。
2. 怎样复用现有三层预算事务而不复制约 300 行逻辑，同时保留一个语义明确的 daily-rollover replacement 公开内部入口，而不是通用 boolean 开关？
3. 专用事务应逐字段重验哪些旧父 Attempt、旧 Usage、旧 reservation、Stage/Job/lease/latest-attempt 事实？新 draft 的 input/snapshot/connection/budget estimate 是否必须与旧请求一致，哪些字段必须不同？
4. 新 artifact 在数据库事务前创建时，怎样处理 artifact 成功但事务失败、旧 artifact 读取后被清理/替换、两个并发替代准备，以及进程崩溃？现有 `LIFECYCLE_LOCK` 和 orphan cleanup 是否足够，是否需要记录旧 descriptor revision？
5. 非空种子怎样从 `ProtectedArtifactLease` 复制到一个有界 ByteArray，并保证源 lease 与副本最终都清零；空种子怎样仍创建新的 artifact？列出所有异常路径的清零责任。
6. 是否应强制新 RequestIntent 的 retry parent 等于旧 rollover Attempt、attempt/model/protocol/connection/input snapshot 全部等价、artifact ref/Attempt ID/Usage ID/reservation ID 全部不同、createdAt 单调？若不强制，请说明安全理由。
7. 新日额度不足时，应保留什么状态？旧父已经正确 RELEASED；新 candidate 必须整体回滚，Stage 是否继续 PREPARING+持有租约以便上层处理，还是需要本切片新增状态转换？
8. 列出 API30/API35 必需测试：空/非空复制、新日键、Attempt No/parent、Book继续累计、Daily重置、额度拒绝、旧证据篡改、错误/过期lease、并发只一胜者、artifact创建/DB失败清理、旧 artifact 保留、Provider调用0、明文临时文件0。

## 评审偏好与不可破坏约束

- 优先在现有 schema v17 和现有状态机内完成；发现确需迁移必须明确停止。
- 旧父的专用错误码、FINAL UNKNOWN Usage、RELEASED reservation 和 latest-attempt 身份必须在创建新 candidate 的同一 Room 事务内重验；事务外预检查不能作为授权。
- 新 reservation 由新 `createdAt`、当前 BOOK/DAILY policy 和动态 disclosure 派生；旧 reservation 日键/策略/目的地不可修改。
- BOOK 聚合继续包含其他非 RELEASED 占用；新 DAILY 只包含新日非 RELEASED 占用。
- 不共享旧 artifact ref，不修改或删除旧 artifact，不写明文文件，不把正文/种子放入日志、异常、`toString` 或数据库快照。
- 不打开 Provider，不修改网络/代理/DNS/防火墙，不写物理设备，不读取/输出 API key。
- 不注册 total runner route，不实现实际 profile/adapter destination matching（下一阶段）。
- 不修改任何文件。

## 输出格式

1. `推荐分层与方法签名`
2. `专用事务逐字段验证`
3. `Artifact 复制与崩溃恢复`
4. `拒绝的方案`
5. `最小实现文件范围`
6. `测试矩阵`
7. `需要 Sol 最终裁决`

不得宣布 Phase 5C/TASK-083 完成。
