# TASK-064 Phase 1B：持久 runner queue 与 Job heartbeat

## 任务身份

- 任务 ID：`TASK-064 / Phase 1B runner queue + Job heartbeat`
- 仓库根目录：`D:\gptuser\projects\ai-novel-reader-app2`
- 基准分支与 HEAD：`main` / `8ce7744`；仓库包含 TASK-059～064 的大量未提交 WIP，必须原样保留
- 当前未提交改动：Phase 1A 已修改 `GenerationDao.kt`、`GenerationMaintenanceRepository.kt`、`GenerationDatabaseTest.kt`，新增 Job-only 过期租约恢复；不得回退或重写
- 执行模型：DeepSeek（纯文本）

## 运行预算

- 推理等级：`max`
- 最长运行时间：30 分钟。理由：只有数据库队列、一个新 repository 和 Android 测试，但包含 Room 事务与并发 CAS，需要完整推理和编译收敛
- 累计 Token 上限：无
- 预计读取文件数与明确清单：10 个，见“必读资料”；仅可沿直接符号引用追加少量读取
- 预计执行命令/测试数：2～3 个，先 AndroidTest Kotlin 编译，再按条件运行数据库 JVM；不要操作 ADB 或物理设备
- 提前停止条件：需要 schema 迁移、需要扩大到 feature/provider、连续两次同类编译失败、发现任务边界与现有业务提交事务冲突

## 目标

在现有 `generation_job.current_stage_id`、Job/Stage 状态与 lease 字段上补齐最小持久 runner queue：有界扫描并精确领取 `READY Job + current READY Stage`，以及持有原 Job lease token 的同一 runner 在 Stage 原子推进后继续 heartbeat 并读取新的 current Stage。并发领取必须只成功一个，过期或错误 token 不能被复活。

## 当前现场与已有 WIP

- `GenerationDao.acquireJobLease` 已用 Job 状态 CAS 完成 `READY -> RUNNING` 并写完整 Job lease；`heartbeatJobLease` 会精确匹配 owner/acquired/heartbeat 且拒绝超时 lease。
- 各业务 commit/seal repository 已在自己的 Room 事务中将 Stage 成功、创建/激活下一 Stage、推进 `current_stage_id` 或完成 Job；runner 不能重复这些写入，也不能新增第二套 cursor。
- Phase 1A 已处理“Job lease 已领取但 Stage lease 尚未领取后进程崩溃”的过期恢复。
- 缺口：没有有界、稳定排序、可精确复验的 READY Job 队列；也没有一个只持原 token、续 Job heartbeat 后读取 current Stage 的 runner repository。
- 正常多阶段 Job 在 Stage 交接后仍是 `RUNNING` 且保留同一 Job lease。不能只扫描/领取 READY Job，也不能在每个 Stage 重领 Job lease。

## 必读资料

1. `AGENTS.md`
2. `docs/24-AI-DEVELOPMENT-PROTOCOL.md`
3. `docs/ai/CURRENT-CONTEXT.md` 的第 17 节
4. `docs/06-AI-GENERATION-SYSTEM.md` 的 TASK-064/runner 相关章节
5. `docs/08-TECHNICAL-ARCHITECTURE.md` 的 TASK-064/lease 相关章节
6. `docs/10-STATE-MACHINES.md` 的 Job/Stage lease 与 Phase 1A 章节
7. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationDao.kt`
8. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationStateRepository.kt`
9. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationEntities.kt`
10. `core/database/src/androidTest/kotlin/app/zhijuan/core/database/GenerationDatabaseTest.kt`

除上述清单和代码直接引用外，不得递归扫描整套文档、历史会话、备份、日志或无关模块；需要扩展读取范围时先在回交中说明。

## 范围

允许修改：

- `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationDao.kt`
- 新建 `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationRunnerQueueRepository.kt`
- `core/database/src/androidTest/kotlin/app/zhijuan/core/database/GenerationDatabaseTest.kt`

明确不在范围：

- 不修改 Room entity、schema 版本、migration、`ZhijuanDatabase.kt` 或导出 schema。
- 不修改 feature、provider、app、UI、WorkManager、定时器或 timing emitter。
- 不创建/发送 RequestAttempt，不获取 Stage lease，不调用 Provider，不写业务输出，不推进 `current_stage_id`。
- 不处理 CREATED 激活、RETRY_WAIT、暂停/停止安全点、UNKNOWN/RECOVERY、dispatcher 和 contract 解析。
- 不更新正式任务状态、工作汇报或其他文档。

## 不可破坏的约束

- 项目隔离：根目录必须精确为 `D:/gptuser/projects/ai-novel-reader-app2`；不得读取或修改其他相似项目。
- 安全与隐私：公开 snapshot/scan/claim 类型的 `toString()` 不得泄露 `bookId`、targetId、input hash、input sources、lease owner 或其他正文/连接信息；队列本身不要读取 input sources JSON。
- 状态机与幂等：只允许领取 `READY Job`，其 `current_stage_id` 必须指向同 Job 的 `READY Stage`，且 Stage 三个 lease 字段全部为空。Job 领取后 Stage 必须保持 READY、无 lease、attemptCount 不变。
- 精确候选：scan 后 claim 必须在同一 Room 事务重读并复验候选的 Job id/status/currentStageId/updatedAt 与 Stage id/status/updatedAt/无 lease；任一变化 stale-fail、零写入。可复用现有 `acquireJobLease`，不得放松它的 CAS。
- 有界队列：`observedAt` 快照边界；稳定按 `generation_job.updated_at ASC, generation_job.job_id ASC`；使用 `limit + 1` 判定 `hasMore`，公开 limit 必须有限且防溢出。
- 续跑：只接受调用方内存中持有的精确 `GenerationLeaseToken`；通过现有 `heartbeatJobLease` 续租后再读取同一 Job 的 current Stage。不得按 owner 字符串从数据库“认领”旧进程租约，不得重启后复用/收养旧 token。
- 过期与竞争：到达超时边界的 lease 不能 heartbeat 复活；错误 owner/acquiredAt、外部状态变化和 current Stage 断链必须失败关闭。
- 数据库与事务：scan 只读；单候选 claim 和 heartbeat+load 各自使用单一外层 Room 事务。不得引入第二 runner cursor 或 shadow 状态。
- 联网与费用：不联网，不调用真实或 Fake Provider，不产生费用。
- 兼容性：保持 Phase 1A 逻辑与既有测试；不要格式化或改写无关代码。

## 建议公开契约（可按现有风格微调命名，但语义不能缩减）

1. 一个不含敏感 payload 的候选类型，保存精确复验所需 Job/Stage identity、status 与 updatedAt。
2. 一个 `GenerationRunnerQueueScan(candidates, hasMore)`。
3. 一个 claim 结果，至少含 Job lease token 与当前 Stage 的有限路由快照；`toString()` 脱敏。
4. `scanReadyJobs(observedAt, limit)`：有界稳定扫描。
5. `claimReadyJob(candidate, runnerOwnerId, claimedAt)`：精确候选 + Job lease CAS。
6. `heartbeatAndLoadCurrentStage(jobId, jobLeaseToken, heartbeatAt)`：续同一 Job lease，返回最新 current Stage；支持业务事务已经把 current Stage 从 A 推进到 B 的正常多阶段续跑。
7. runner owner id 必须是 1～128 个安全字符 `[A-Za-z0-9._:-]`，不能把自由文本/设备信息写入 lease owner。

若 Room projection 更适合使用 internal row 类型，可以增加 internal 类型；不得把 `input_sources_json` 或 `user_intent_json` 加入队列 projection。

## 实施要求

1. 在 DAO 增加 READY Job/current Stage 的最小 projection 查询，以及 claim/heartbeat 事务所需的精确 current row 读取；优先复用现有 lease 写方法。
2. 新 repository 负责参数验证、limit+1、精确候选复验、claim、heartbeat 与有限 snapshot 映射。
3. 至少新增以下 Android 数据库测试：
   - 稳定排序、limit 与 hasMore，且 future-updated row 不进入 observedAt 快照；
   - 两个不同 runner 并发 claim 同一候选，精确一个成功；
   - scan 后 Job updatedAt/currentStage 或 Stage status/lease 变化会 stale-fail 且不覆盖新事实；
   - claim 只改 Job 为 RUNNING + lease，不改 Stage/Attempt/attemptCount；
   - 原 token heartbeat 后能读取原 Stage；业务事务推进 currentStage 后，同一 token heartbeat 能读取新 Stage，而 Job 不被重领；
   - 错 owner、错 acquiredAt、过期边界都不能续跑或复活；
   - 非法 runner owner、limit 与倒退时间被拒绝。
4. 并发测试不得靠 sleep；使用 coroutine barrier/并发启动并断言精确一个成功。
5. 不允许先查再在事务外更新、吞掉 stale 异常、把失败默认为成功、用 Job phase 代替后续 frozen contract dispatcher。

## 验收标准

- [ ] READY 队列有界、稳定、有 observedAt，且 claim 精确复验。
- [ ] 并发领取只有一个写入者，失败者不改变 Job/Stage。
- [ ] 同一 Job token 能跨 Stage handoff 续租并读取新的 current Stage。
- [ ] 过期或错误 token 不可复活；旧进程 lease 不被按 owner 收养。
- [ ] Stage、Attempt、业务 cursor、schema 和 Provider 均未被本阶段改变。
- [ ] 所有新增公开数据对象 `toString()` 脱敏。

## 验证命令

```powershell
$root = (git rev-parse --show-toplevel).Trim()
if ($root -cne 'D:/gptuser/projects/ai-novel-reader-app2') { throw "Wrong repository: $root" }
$env:GRADLE_USER_HOME='D:\gptuser\cache\gradle-app2'
./gradlew :core:database:compileDebugAndroidTestKotlin --offline --no-daemon
```

```powershell
$root = (git rev-parse --show-toplevel).Trim()
if ($root -cne 'D:/gptuser/projects/ai-novel-reader-app2') { throw "Wrong repository: $root" }
$env:GRADLE_USER_HOME='D:\gptuser\cache\gradle-app2'
./gradlew :core:database:testDebugUnitTest --offline --no-daemon
```

不运行统一 Release/R8，不操作 ADB。未运行的验证必须如实写明。

## 回交格式

请严格按以下标题返回：

1. `完成内容`
2. `修改文件`
3. `验证`
4. `未完成/风险`
5. `需要 Sol 处理`
6. `假设`

不要宣布 TASK-064 整体完成，也不要更新正式状态；由 Sol 审查差异和双 API 测试后确认。
