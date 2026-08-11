# TASK-064 Phase 1C：当前 Stage 原子领取与双层 lease heartbeat

## 任务身份

- 任务 ID：`TASK-064 / Phase 1C atomic current-stage leases`
- 仓库根目录：`D:\gptuser\projects\ai-novel-reader-app2`
- 基准分支与 HEAD：`main` / `8ce7744`；大量 TASK-059～064 未提交 WIP 必须保留
- 当前未提交改动：Phase 1A/1B 已完成；`GenerationDatabaseTest.kt` 当前 64 个测试，`GenerationRunnerQueueRepository.kt` 已提供精确 Job claim 与 Job heartbeat
- 执行模型：DeepSeek（纯文本）

## 运行预算

- 推理等级：`max`
- 最长运行时间：30 分钟。理由：只允许一个新 repository 和一个既有测试文件，但需要证明两次 lease 写入的单事务回滚与并发竞争
- 累计 Token 上限：无
- 预计读取文件数：8 个，见必读资料；只能沿直接符号引用少量扩展
- 预计命令：AndroidTest Kotlin 编译、数据库 JVM，共 2～3 个；不操作 ADB
- 提前停止条件：需要 schema/DAO/entity 变更、需要修改 feature/provider、无法复用既有 lease CAS、连续两次相同编译失败

## 目标

新增 runner 执行租约 repository：持有精确 Job token 的当前 runner，只能原子领取该 Job 的 current READY Stage；活跃执行期间可在单一 Room 事务内续 Job 与 Stage 两层 heartbeat。任一 token、状态、currentStage、过期或时间证据失败时必须整笔零写入。

## 当前现场与架构裁决

- Phase 1B 已让 runner 领取 READY Job，并持同一个 Job token 跨业务 Stage handoff 续跑。
- `GenerationStateRepository.acquireStageLease` 只按 Stage 自身状态领取，不验证调用者是否拥有当前 Job，也不验证 Stage 是否仍是 current Stage；total runner 不能直接把它作为唯一入口。
- 网络 `AuditedStreamingProviderExecutor` 已通过 `GenerationStreamingDraftRepository` 自行 heartbeat Stage；`ChapterFinalCandidateCommitStageExecutorV1` 会在 READY 时领取 Stage。后续 total runner 可先用本阶段原子入口把 Stage 变为 PREPARING，再让既有 executor 走 same-owner resume；不得复制 executor/commit 逻辑。
- 维护器只有在相关 lease 过期后恢复。Phase 1C 不改变维护、重试、控制或 Provider 行为。

## 必读资料

1. `AGENTS.md`
2. `docs/24-AI-DEVELOPMENT-PROTOCOL.md`
3. `docs/ai/CURRENT-CONTEXT.md` 第 17～18 节
4. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationLease.kt`
5. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationStateRepository.kt`
6. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationRunnerQueueRepository.kt`
7. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationDao.kt` 的 find/acquire/heartbeat 方法
8. `core/database/src/androidTest/kotlin/app/zhijuan/core/database/GenerationDatabaseTest.kt` 的 Phase 1A/1B 测试与 helper

不得递归扫描历史日志、备份、其他项目或无关模块。

## 范围

允许修改：

- 新建 `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationRunnerExecutionLeaseRepository.kt`
- `core/database/src/androidTest/kotlin/app/zhijuan/core/database/GenerationDatabaseTest.kt`

禁止修改：

- 不修改 DAO、entity、schema、migration、`ZhijuanDatabase.kt`。
- 不修改 Phase 1A/1B repository、feature/provider/app/UI/WorkManager/timing。
- 不创建 Attempt、不调用 Provider、不解析 contract、不推进 currentStage、不提交业务输出。
- 不更新任务状态、报告或其他文档。

## 不可破坏的约束

- 项目根目录必须精确匹配 app2；不访问其他项目。
- runner owner id 与 Job token owner 必须相同，并继续限制为 1～128 位 `[A-Za-z0-9._:-]`；不得按 owner 扫描或收养旧 token。
- `acquireCurrentStageLease` 只接受 Job=`RUNNING`、精确 Job token、Job.currentStageId=`stageId`、Stage 属于 Job、Stage=`READY` 且无 lease。`PAUSING/STOPPING` 不得开启新 Stage。
- Stage 领取前在同一 Room 外层事务续 Job heartbeat，再复用既有 Stage lease CAS；Stage 领取失败必须回滚 Job heartbeat。
- `heartbeatCurrentExecutionLeases` 允许 Job 在 RUNNING/PAUSING/STOPPING，但必须精确匹配 Job token、currentStage、Stage token、Stage 的 lease-owned status。依次复用既有 heartbeat 方法且由外层事务保证任一失败时两层都不变。
- acquired/heartbeat 时间不能倒退；到达 timeout 边界即过期，不能复活。
- 不放松现有 CAS 或超时策略，不新增 shadow cursor/lease。
- 返回对象的 `toString()` 必须隐藏 jobId、stageId、owner；不能携带 payload、target、input、intent 或连接信息。
- 真实/Fake Provider 调用均为 0；不得操作 ADB 或物理设备。

## 建议接口

可按现有命名风格微调，但语义不可缩减：

1. `GenerationRunnerExecutionLeaseSnapshot`：有限 job/stage status、两个精确 token、两个 heartbeatAt，字段可供内存执行；自定义脱敏 `toString()`。
2. `acquireCurrentStageLease(jobId, jobLeaseToken, stageId, runnerOwnerId, acquiredAt)`。
3. `heartbeatCurrentExecutionLeases(jobId, jobLeaseToken, stageId, stageLeaseToken, heartbeatAt)`。
4. 所有写入在 `database.withTransaction` 中复用现有 DAO `heartbeatJobLease`、`acquireStageLease`、`heartbeatStageLease`；不要新增宽 UPDATE。

## 测试要求

至少覆盖：

1. 正向原子领取：Job token 保持 acquiredAt，Job heartbeat 前进；Stage READY→PREPARING 获得同 owner token；Attempt/attemptCount/currentStage 不变。
2. 两协程并发领取同一 current Stage，精确一个成功，无 sleep。
3. 错 Job token、Job 非 RUNNING、stageId 非 current、Stage 已被别人领取或不属于 Job均失败，Job heartbeat 不被提前保留。
4. 双层 heartbeat 正向同时前进；RUNNING 为主，若便于用正式控制入口建立 PAUSING/STOPPING，可补其正向，但不得用生产代码外的宽松捷径改变语义。
5. Stage token 错误/Stage 过期时，先尝试的 Job heartbeat 也必须因事务回滚保持原值。
6. Job token 错误/Job 过期时，Stage heartbeat 保持原值。
7. currentStage 在领取或 heartbeat 前已推进时失败关闭，旧 Stage 不被续租。
8. owner、负时间和倒退时间边界。

并发测试使用 coroutine barrier/async，不用 `sleep`。直接 SQL 只可用于构造无法通过正式 API形成的损坏负例，不能用于正向路径。

## 验收标准

- [ ] 只有 Job token owner 能领取当前 READY Stage。
- [ ] 领取与双 heartbeat 均是单事务；任何第二步失败都会回滚第一步。
- [ ] 并发只有一个 Stage owner；没有 Attempt、Provider 或业务 cursor 写入。
- [ ] 过期/错误 token 不可复活；日志字符串脱敏。
- [ ] Phase 1A/1B 不回退，schema 不变。

## 验证命令

```powershell
$root=(git rev-parse --show-toplevel).Trim()
if ($root -cne 'D:/gptuser/projects/ai-novel-reader-app2') { throw "Wrong repository: $root" }
$env:GRADLE_USER_HOME='D:\gptuser\cache\gradle'
$env:TEMP='D:\gptuser\cache\temp\ai-novel-reader-app2'
$env:TMP=$env:TEMP
./gradlew :core:database:compileDebugAndroidTestKotlin :core:database:testDebugUnitTest --offline --no-daemon
```

不运行 connectedAndroidTest、统一 Release/R8 或安全产物扫描；交给 Sol。

## 回交格式

1. `完成内容`
2. `修改文件`
3. `验证`
4. `未完成/风险`
5. `需要 Sol 处理`
6. `假设`

不要宣布 TASK-064 完成，不要更新正式状态。
