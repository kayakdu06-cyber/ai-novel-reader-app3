# 工作汇报 112：TASK-064 Phase 1C 原子执行租约

日期：2026-08-08  
项目：织卷 Android App  
唯一仓库：`D:\gptuser\projects\ai-novel-reader-app2`

## 1. 本阶段结论

Phase 1C 已完成。持有精确 Job token 的 runner 现在可以在一个 Room 事务中领取该 Job 的 current READY Stage；执行期间也可以在一个事务中同时续 Job 与 Stage heartbeat。任一 token、owner、状态、时间、超时或 cursor 证据失败时，不会留下只更新一层 lease 的部分状态。

这仍是数据库原语，不是自动定时器，也不是 dispatcher。没有 Provider 调用、Attempt 创建、业务输出提交或 cursor 推进权限。

## 2. 为什么需要这一层

旧 `acquireStageLease(stageId)` 只验证 Stage 自己，不知道调用者是否还拥有 Job，也不知道 Stage 是否仍是 `current_stage_id`。如果 total runner 直接使用它，理论上可能领取已经被业务事务越过的 Stage。

同时，Job 与 Stage heartbeat 若分两次独立事务更新，第二次失败会留下“Job 看起来活跃、Stage 已失效”的部分事实。Phase 1C 把这些检查和写入收进单事务。

## 3. 实现内容

- `acquireCurrentStageLease`：验证 RUNNING Job、精确 Job token、same-owner、currentStage、Stage 归属、READY/no-lease；先续 Job，再取得 Stage lease。
- `heartbeatCurrentExecutionLeases`：验证 currentStage、两个精确 token 与 same-owner；在同一事务续两层 heartbeat。
- `PAUSING/STOPPING` 只允许续正在执行的 lease，不能开启新 Stage。
- Job/Stage/owner 的日志字符串脱敏；不读取 payload、input、intent、target 或连接信息。
- 不新增 DAO 宽更新、schema、migration、runner cursor 或 shadow state。

## 4. DeepSeek 与 Sol 分工

- 运行 ID：`20260808-221907-e4212150`。
- 配置：DeepSeek V4 Flash、max 推理、30 分钟硬上限、无累计 Token 上限。
- 结果：30 分钟超时终止；总 Token 3,654,893，缓存输入 3,113,984，输出 138,232，推理输出 106,786；没有 final 回交。
- DeepSeek 只落了 repository 主体，没有新增任何 Phase 1C 测试，因此不能独立算完成。
- Sol 保留可用主体，补充 identifier/same-owner 门禁、5 个 Android 数据库测试、真实事务回滚场景和双 API 验收。

本轮说明：放宽到 30 分钟能保留代码差异，但不能保证模型完成复杂事务测试；后续不再无限延长同一任务，仍以 30 分钟为上限，超时由 Sol 接管。

## 5. 测试与修正

新增测试覆盖：

- current Stage 正向原子领取，Stage `READY→PREPARING`，Job token acquiredAt 不变。
- 两协程并发领取同一 Stage，精确一个成功。
- Job heartbeat 已尝试后 Stage 因时钟证据拒绝，外层事务回滚 Job heartbeat。
- 双 heartbeat 正向、错误 Stage token、混合 owner，全都没有部分写入。
- Job 仍活跃但 Stage 到达 60,000ms timeout 时，Job heartbeat 回滚。
- cursor 从 Stage A 推进到 B 后，旧 A 不可被 heartbeat 续活。

第一次 API 30 运行出现 1 个测试失败，原因是测试把 60 秒超时误写成 64 毫秒；修正为 50,000/60,004 毫秒边界后通过。这是测试单位错误，不是生产 lease policy 放松。

最终结果：

- AndroidTest Kotlin/Room 编译：通过。
- `core:database` JVM：70/70。
- API 30/35 定向 `GenerationDatabaseTest`：各 69/69。
- API 30/35 数据库 Android 全量：各 209/209。
- 最终 0 失败、0 错误、0 跳过。

## 6. 仍未完成

- 每 15 秒实际调度 heartbeat 的可取消协程 envelope。
- action 完成、cursor 推进、Job 完成/暂停与 heartbeat 同时发生时的竞态裁决。
- frozen contract/schema-aware dispatcher 与各 executor registry。
- RETRY_WAIT、UNKNOWN/RECOVERY、控制安全点、全 phase timing 和 Fake 第一章闭环。
- 统一 Release/R8、真实模型档案与物理设备验收。

## 7. 下一步

实现一个只负责生命周期的 heartbeat scheduling envelope：用可注入时钟做秒级确定性测试，在 action 存活时调用 Phase 1C 原子 heartbeat；action 结束立即停止；lease 丢失要停止后继调度；业务提交已推进 cursor 或完成 Job的正常竞态要能区分。完成后生成下一份阶段工作汇报，再进入 contract-aware dispatcher。
