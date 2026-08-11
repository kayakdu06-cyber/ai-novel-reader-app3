# TASK-064 Phase 1A：空闲 RUNNING Job 租约崩溃恢复

## 任务身份

- 任务 ID：`TASK-064 / Phase 1A 空闲 RUNNING Job 租约崩溃恢复`
- 仓库根目录：`D:\gptuser\projects\ai-novel-reader-app2`
- 基准分支与 HEAD：`main` / `8ce774429da1c3f7139a221bc241c34d81a2efdd`
- 当前未提交改动：大量 TASK-059～063 WIP；`GenerationDao.kt` 已有约 28 行既有新增，`GenerationDatabaseTest.kt` 有 1 行既有调整，必须在原有内容上最小增量修改
- 执行模型：DeepSeek（纯文本）

## 运行预算

- 推理等级：`max`。
- 最长运行时间：25 分钟。理由：任务只有一个明确崩溃窗口和三个允许文件，但涉及 Room 事务、精确租约 CAS、双维护器竞争和 Android instrumentation 测试。
- 累计 Token 上限：无。仍受 25 分钟硬超时、单任务锁和项目隔离约束。
- 预计读取文件数与明确清单：10 个，见“必读资料”。
- 预计执行命令/测试数：最多 10 个只读查看命令、最多 2 个离线编译/JVM 命令；不要启动模拟器或连接物理设备。
- 提前停止条件：需要 schema/迁移、新 runner/dispatcher、真实 Provider、扩大允许文件范围；原生补丁/权限首次阻塞；同一编译问题重复两次。

## 目标

关闭一个确定的持久卡死窗口：Job 已从 READY 领取为 RUNNING 并持有 Job lease，但当前 Stage 仍是 READY 且还没有 Stage lease 时进程崩溃，现有 `GenerationMaintenanceRepository` 因只扫描有 lease 的 Stage 而永远看不到该 Job。增加有限扫描与精确 CAS，使过期的这种空闲 Job 可安全回到 READY，且不能抢走已开始执行或已心跳更新的工作。

## 当前现场与已有 WIP

- `GenerationMaintenanceRepository.scanExpiredExecutionLeases` 只从 `leasedStagesForMaintenance` 开始扫描，要求 Stage 和 Job 两层租约都存在并过期。
- `GenerationDao.acquireJobLease` 会把 Job 从 READY 转 RUNNING 并写入 Job lease；此后到 `acquireStageLease` 之间存在崩溃窗口。
- 当前 Stage 如果仍为 READY、Stage 三个 lease 字段全空，代表尚未取得 Stage 执行权；这个窗口没有 RequestAttempt，也不允许修改 Stage。
- `GenerationJobStateMachine` 已允许 RUNNING + `RECOVERY_REQUEUED` → READY。
- `compareAndSetJobStatus` 只按 Job status CAS，不能用于本任务，因为它没有同时匹配旧 lease token、heartbeat、current stage 和 Stage 无 lease 证据。
- 必须延续现有 DAO、lease policy 和 maintenance 结构；不得从零重写。

## 必读资料

1. `AGENTS.md`
2. `docs/24-AI-DEVELOPMENT-PROTOCOL.md`
3. `docs/ai/CURRENT-CONTEXT.md`
4. `docs/ai/task-packets/TASK-064-PHASE-1-PERSISTENT-RUNNER-BOUNDARY-AUDIT.md`
5. `core/model/src/main/kotlin/app/zhijuan/core/model/GenerationState.kt`
6. `core/task/src/main/kotlin/app/zhijuan/core/task/GenerationJobStateMachine.kt`
7. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationLease.kt`
8. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationDao.kt`
9. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationMaintenanceRepository.kt`
10. `core/database/src/androidTest/kotlin/app/zhijuan/core/database/GenerationDatabaseTest.kt` 中现有 lease、maintenance、并发测试与底部 helper

除上述清单和代码直接引用外，不得扫描无关文档、其他项目、日志、备份或密钥文件。

## 范围

允许修改：

- `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationDao.kt`
- `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationMaintenanceRepository.kt`
- `core/database/src/androidTest/kotlin/app/zhijuan/core/database/GenerationDatabaseTest.kt`

明确不在范围：

- 任何 schema 版本、Entity、migration 或导出 schema JSON。
- 新 runner、dispatcher、Stage executor、READY 队列扫描、Job 正常 heartbeat 循环、RETRY_WAIT、UNKNOWN、watchdog、timing 接线。
- 修改现有状态机、Provider、App、feature、Gradle、文档状态或报告。
- 真实联网、模拟器、物理设备、Git 操作。

## 不可破坏的约束

- 项目隔离：不得访问或修改其他项目副本。
- 多模态：无。
- 安全与隐私：候选和 `toString` 不得泄露原始 ID、正文、输入、endpoint、Provider request id 或 secret。
- 状态机与幂等：只允许 RUNNING Job 在当前 Stage 仍为 READY 且 Stage lease 三字段全部为空时恢复到 READY；Stage 状态和 Attempt 计数必须完全不变。
- 数据库与事务：恢复前重读并验证证据；写入必须使用新的精确 CAS，同时匹配 Job status、current Stage、Job lease owner/acquired/heartbeat，并在 SQL 中再次证明当前 Stage 仍为 READY 且三项 Stage lease 为空。不能用现有仅 status CAS。
- 时间：必须使用 `GenerationLeasePolicy.isExpired`；`observedAt` 不得早于 Job/Stage persisted time；临界点 `now - heartbeat == timeout` 视为过期，沿用现有策略。
- 联网与费用：不调用真实 API。
- 兼容性：现有有 Stage lease 的维护路径不改变；不改 schema，因此无需迁移。
- 保留全部既有未提交改动；不得 reset、clean、checkout、commit 或添加 remote。

## 实施要求

1. 在 DAO 增加一个有界、稳定排序的查询，只返回：Job `RUNNING`、Job lease 完整且 heartbeat 已早于/等于 cutoff、`updated_at <= observedAt`、current Stage 属于该 Job、Stage `READY`、Stage 三个 lease 字段全部为空、Stage `updated_at <= observedAt`。按 Job heartbeat、job id 排序并接受 limit。
2. 在 DAO 增加专用精确 CAS：把上述 Job 改为 `READY`，清空 Job lease，保留 current Stage；WHERE 必须匹配 candidate 的 Job lease owner/acquired/heartbeat、currentStageId、RUNNING，并用 `EXISTS` 再次证明 Stage 仍 READY 且无 lease。`updated_at` 只前进。
3. 在 `GenerationMaintenanceRepository` 增加有限候选/扫描结果类型、`scanExpiredIdleJobLeases` 和 `requeueExpiredIdleJobLease`（可用同等清晰命名）。候选 `toString` 必须 redacted。
4. scan 使用 `limit + 1` 得到准确 `hasMore`，上限沿用 maintenance 的 1..100；`observedAt < timeout` 快速返回空。
5. requeue 必须在一个 Room 事务中重读 Job/Stage、核对 exact candidate、校验 expiry/单调时间、用状态机证明 RUNNING+RECOVERY_REQUEUED→READY，再执行专用 CAS；CAS 0 行抛 `StaleGenerationStateException`。
6. 不改变 current Stage，不创建 Attempt，不改变 `attempt_count`、`next_retry_at`、错误码或 Stage `updated_at`。
7. 在现有 `GenerationDatabaseTest` 增加至少四个测试：
   - Job lease 到期、Stage READY 无 lease时被扫描并恢复；Job READY/lease 清空，Stage 完全不变。
   - 未到期不扫描；精确到 timeout 临界点才扫描。
   - scan 后 Stage 被另一 executor 领取，恢复必须 stale-fail，Job 保持 RUNNING且新 Stage lease 保留。
   - 两个维护器并发恢复，同一候选只能一个成功；另一个 stale-fail，最终状态正确且无重复副作用。
8. 若容易实现，再覆盖 stable ordering/hasMore；不要为了这个可选用例扩大范围。

## 验收标准

- [ ] 崩溃窗口可恢复，不再永久 RUNNING。
- [ ] 活跃、刚心跳或已取得 Stage lease 的执行绝不会被抢回 READY。
- [ ] 恢复使用 exact lease/current-stage/Stage-ready CAS，不依赖只按 status 的宽 CAS。
- [ ] Stage 与 Attempt 事实零变化。
- [ ] 双维护器收敛为一次恢复。
- [ ] 现有有 Stage lease 的 maintenance 行为不变。
- [ ] 无 schema、Provider、feature 或文档状态变更。

## 验证命令

允许先运行离线编译；不要启动模拟器：

```powershell
$env:GRADLE_USER_HOME='D:\gptuser\cache\gradle'
.\gradlew.bat :core:database:compileDebugAndroidTestKotlin --offline --no-daemon --console=plain
```

如果 restricted-token 下 Gradle 阻塞，记录一次证据后停止，不要绕过。Android 测试由 Sol 在 API 30/API 35 模拟器运行。

## 回交格式

请严格按以下标题返回：

1. `完成内容`
2. `修改文件`
3. `验证`
4. `未完成/风险`
5. `需要 Sol 处理`
6. `假设`

不要宣布 TASK-064 完成，不要更新完成状态；由 Sol 审查差异并运行双模拟器测试。
