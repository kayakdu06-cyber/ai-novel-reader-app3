# TASK-083 Phase 5B：Provider-open 日周期换日核心

## 任务身份

- 任务 ID：`TASK-083 / Phase 5B provider-open daily rollover core`
- 仓库根目录：`D:\gptuser\projects\ai-novel-reader-app2`
- 基准分支与 HEAD：`main` / `8ce774429da1c3f7139a221bc241c34d81a2efdd`
- 当前连续 WIP：257 条以上 `git status --short`，全部属于用户持续开发；禁止 reset/clean/checkout/覆盖/整理
- 执行模型：DeepSeek V4 Flash（纯文本编码）

## 运行预算

- 推理等级：`max`
- 最长运行时间：单次 45 分钟；该窄任务经过 Phase 5A 约 18 分钟只读审计且涉及跨事务状态机，使用项目允许的最高安全护栏。用户允许等待真实返回；若正常运行未完成，由 Sol 续接同一窄任务
- 累计 Token 上限：无
- 预计读取文件：本任务列出的 14 个生产/测试文件及其直接引用
- 预计命令：只允许静态搜索、编辑和 JVM/compile 级验证；不运行模拟器、不联网、不调用 Provider
- 提前停止条件：需要 schema/migration/trigger 变化、需要修改 artifact 内容、需要新建 Attempt、需要触碰真实 Provider adapter/profile，或允许范围外才能继续

## 目标

在 `claimForProviderOpen` 真正打开 Provider 之前，按当前 DAILY policy 的 IANA zone 和 `validatedAt` 检查 reservation 日键。若已换日，用专用原子事务结束并释放旧的未发送 v1 Attempt，然后以有限、脱敏的专用异常要求持久 runner 重新准备；同日行为保持不变。

本切片不创建替代 Attempt、不复制 artifact、不自动调用 Provider、不宣称 total runner 已完成。

## 当前现场与已有 WIP

- Phase 4B 已实现 `finalizeUsageAndReleaseReservationAfterProviderProof`，只允许 `REQUEUE_PROVEN_NOT_EXECUTED` 使用；不得把跨午夜伪装成 Provider proof。
- `claimForProviderOpen` 当前只重验 Attempt/Usage/reservation/Job/Stage/lease，没有日键重验。
- `PersistedRequestSendPermit` 是一次性内存 permit；旧 Attempt 状态变化后必须永远失效。
- reservation 的 daily key、Attempt reservation identity 和 `(stage_id, attempt_no)` 均不可修改/复用。
- `recordRequestIntent` 每次正常创建新 Attempt 都让 attemptCount +1，且要求 `< maxAttempts`。
- 旧未发送 Attempt 已经占一次 attempt；本阶段不篡改计数。

## 必读资料

1. `AGENTS.md`
2. `docs/24-AI-DEVELOPMENT-PROTOCOL.md`
3. `docs/ai/CURRENT-CONTEXT.md` 第 39 节
4. `reports/2026-08-09-127-task-083-phase-1-persistent-budget-design.md` 第 3、6 节
5. `reports/2026-08-09-132-task-083-phase-4b-confirmed-not-executed-release.md`
6. `reports/2026-08-09-133-task-083-phase-5a-cross-midnight-design-audit.md`
7. `core/model/src/main/kotlin/app/zhijuan/core/model/StandardErrorCode.kt`
8. `core/task/src/main/kotlin/app/zhijuan/core/task/RequestAttemptStateMachine.kt`
9. `core/task/src/main/kotlin/app/zhijuan/core/task/GenerationStageStateMachine.kt`
10. `core/task/src/main/kotlin/app/zhijuan/core/task/GenerationJobStateMachine.kt`
11. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationDao.kt`
12. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationRequestAuditRepository.kt`
13. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/BudgetDao.kt`
14. 以上状态机、`PersistentBudgetReservationDatabaseTest`、`GenerationDatabaseTest` 与 `AuditedStreamingProviderExecutorTest` 的直接相关测试

## 允许修改

- `core/model/src/main/kotlin/app/zhijuan/core/model/StandardErrorCode.kt`
- 三个 state machine 生产文件和对应现有测试
- `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationDao.kt`
- `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationRequestAuditRepository.kt`
- `core/database/src/androidTest/kotlin/app/zhijuan/core/database/PersistentBudgetReservationDatabaseTest.kt`
- 必要时 `GenerationDatabaseTest.kt`
- `feature/generation/src/androidTest/kotlin/app/zhijuan/feature/generation/AuditedStreamingProviderExecutorTest.kt`

不得修改：schema/entity/migration/trigger、`GenerationStreamingDraftRepository` 的 artifact 内容路径、Provider 实现、网络设置、正式文档和任务状态。

## 冻结实现决策

### 1. 专用语义

- 新增 `StandardErrorCode.DAILY_BUDGET_PERIOD_EXPIRED_BEFORE_SEND`，retry disposition 为有限自动重试。
- Attempt 新事件只允许 `INTENT_RECORDED -> FAILED_RETRYABLE`。
- Stage 新事件：
  - 有剩余次数：`REQUEST_INTENT_RECORDED -> READY`；
  - 已到上限：`REQUEST_INTENT_RECORDED -> NEEDS_ACTION`，可使用另一个明确事件，不能借用 PAUSE/UNKNOWN/Provider proof。
- Job 有剩余次数时使用专用 rollover 事件 `RUNNING -> READY`；到上限可使用既有 `USER_ACTION_REQUIRED -> NEEDS_ACTION`，但 `pause_or_stop_reason` 必须保存专用错误码名称。

### 2. 当前日键

在 claim 的同一个 Room 事务中：

1. 先完成现有 permit evidence 验证；
2. 读取当前 DAILY `GLOBAL` head 和 revision；scope/key/zone/createdAt 必须有效，zone 必须受 `BudgetDailyPeriodKeyV1` 支持；
3. 用 `validatedAt` 计算当前日键；
4. 与已验证 reservation 的 `dailyPeriodKey` 相同：继续现有 Job/来源/lease 校验和 claim；
5. 不同：绝不 heartbeat、绝不 mint claimed permit、绝不打开 artifact/adapter，执行换日事务并在事务提交后抛出专用脱敏异常。

普通限额 revision 变化不撤销已经授予的 reservation；同 policy 链 zone 不允许变化。这里只以规范日键是否变化触发 rollover。

### 3. 专用原子释放

新增与 Provider-proof 名称和前置条件分离的 DAO `@Transaction` 入口。可抽取低层私有共用 helper，但两个公开事务入口必须分别验证语义，不能添加通用 boolean release 开关。

换日事务顺序：

1. Attempt 必须仍为 INTENT_RECORDED，`sentAt/providerRequestId/finishedAt/httpStatus/outputHash/standardErrorCode` 均为空，enforcement v1；
2. Usage 必须 UNKNOWN/PROVISIONAL 且所有 token/cost/currency/catalog/finalized 值为空；
3. reservation 必须 RESERVED、accounted 精确等于 estimate、releasedAt/settledAt 为空，且身份/日键与 Attempt/Usage 一致；
4. Attempt CAS 为 FAILED_RETRYABLE，错误码为专用码，updatedAt/finishedAt 都为 `validatedAt`；
5. 专用 usage+reservation 事务把 Usage 封为 UNKNOWN/FINAL、reservation 变为 RELEASED；二者共享 `validatedAt`；
6. 若 `stage.attemptCount < stage.maxAttempts`：Stage READY、Job READY；否则 Stage NEEDS_ACTION、Job NEEDS_ACTION；
7. Stage/Job 租约清空，写后回读五类状态并精确核对；任一步失败整个外层 claim 事务回滚。

### 4. 返回/异常和 replay

- 事务内部返回有限 disposition，事务提交后再抛 `DailyBudgetPeriodRolloverRequiredException`；不能在 `withTransaction` 内直接抛该业务异常导致整个 rollover 回滚。
- 异常只能包含 `retryAllowed` 或有限枚举，不包含 ID、日期、zone、金额、token、destination 或 snapshot。
- 同一旧 permit 第一次 rollover 后再次使用，应因持久 evidence 已变化而得到 stale/专用已处理结果，绝不能重复释放或进入 Provider。
- 两个并发 claim 最多一个完成 rollover；另一个失败关闭。不要把并发 CAS 失败伪装成 Provider unknown。

## 不可破坏约束

- 不调用真实/Fake Provider 作为生产行为；测试 fake 只用于证明调用次数为 0。
- 不修改代理、DNS、防火墙、网络或物理设备。
- 不读取、输出或记录 API key。
- 不释放 legacy v0；v0 仍不能 Provider-open。
- 不让普通断网、超时、UNKNOWN、用户暂停或 Provider proof 进入此路径。
- 不修改旧 reservation 日键，不删除 Attempt/Usage/reservation/artifact。
- 不创建替代 Attempt；Phase 5C 才处理重新准备。
- 所有现有 WIP 必须保留。

## 测试要求

至少覆盖：

1. UTC（或固定 IANA zone）同日 claim 保持旧行为，状态/占用不变；
2. 午夜边界前后 1ms 触发换日；旧 Attempt/Usage/reservation、Stage/Job、租约和错误码精确正确；
3. Book 聚合释放旧占用，旧日 daily 聚合无非 RELEASED 占用；
4. 有剩余 attempt -> READY；已到 maxAttempts -> NEEDS_ACTION，不能重新进入 runner route；
5. 旧 permit replay 不重复释放；两个并发 claim 最多一个 rollover；
6. sent/SENT/STREAMING、已 FINAL Usage、已 SETTLED/RELEASED reservation、错误日策略/缺 head/非法倒退时间全部失败关闭且零写；
7. Phase 4B Provider-proof release 测试继续通过且两种专用错误语义不混用；
8. executor 跨午夜时 adapter.generate/open 调用为 0，草稿不被打开，实际 `GenerationRequest` 不发送；
9. API30/35 所需 Android 测试由 Sol 后续运行，DeepSeek 不运行模拟器。

## 验收命令

DeepSeek 可运行最小 JVM/compile 验证；若时间不足，保留给 Sol，不得虚报通过：

```powershell
.\gradlew.bat :core:task:test :core:database:compileDebugKotlin :core:database:compileDebugAndroidTestKotlin :feature:generation:compileDebugAndroidTestKotlin --offline --no-daemon
```

## 回交格式

1. `完成内容`
2. `修改文件`
3. `验证`
4. `未完成/风险`
5. `需要 Sol 处理`
6. `假设`

不要修改正式状态文档，不要宣布 Phase 5B/TASK-083 完成；由 Sol 审查差异并运行双 API 测试后确认。
