# 工作汇报 131：TASK-083 Phase 4A Usage 与 reservation 唯一原子结算

> 日期：2026-08-09  
> 项目：织卷（app开发2）  
> 唯一仓库：`D:\gptuser\projects\ai-novel-reader-app2`  
> 阶段边界：只完成 Usage 结算；不包含跨日重预留、未执行释放或运行时目的地匹配

## 1. 阶段结论

Phase 4A 已完成。budget enforcement v1 Attempt 的 Usage 和 reservation 现在只通过 `GenerationDao.recordUsage` 的同一个 Room 事务结算，二十多个成功、失败、取消和恢复调用方不需要各自补一套预算逻辑。

- PROVISIONAL Usage 不释放也不结算，reservation 保持 `RESERVED` 和原估计占用。
- FINAL UNKNOWN 把 reservation 变为 `SETTLED`，但保留原估计 token/金额，避免把未知费用当成 0。
- FINAL ESTIMATED/PROVIDER_REPORTED 用终值确定性替换 accounted；实际 token 高于预留也如实保存。
- FINAL UNKNOWN/ESTIMATED 的迟到 Provider 报告会同时修正 Usage 与 reservation，原 `settledAt` 不变。
- 相同 FINAL replay 只回读同一结果，不做增量累加。
- enforcement v0 沿用既有 Usage 行为，不伪造 reservation。

## 2. 生产实现

`GenerationDao.recordUsage` 在任何写入前读取 Attempt、Usage 和 v1 reservation，并验证 reservation ID、Attempt/Job/Stage、Book 与 daily period 身份。

首次 FINAL 通过带旧状态、旧更新时间和旧 accounted 全字段的精确 CAS 把 reservation 从 `RESERVED` 推进为 `SETTLED`；迟到 Provider 报告通过同样的全字段 CAS 更新 `SETTLED` 终值。Usage 写入、reservation 写入和两者回读复核共享外层 `@Transaction`，任一 CAS 丢失、身份不符或回读不一致都会让整笔事务回滚。

异常信息只描述有限状态，不包含 reservation、attempt、connection、金额、币种或 hash 身份。

## 3. DeepSeek 执行与 Sol 审查

- 任务包：`docs/ai/task-packets/TASK-083-PHASE-4A-USAGE-SETTLEMENT.md`
- 运行：`20260809-114953-91e6dcc2`
- 模型：DeepSeek V4 Flash，`max`，45 分钟安全上限，无累计 Token 上限
- 实际耗时：约 14 分 48 秒，正常完成，0 权限请求
- Token：总 2,946,971；cached input 2,556,928；output 77,572；reasoning 49,887

DeepSeek 交付了正确的唯一入口方向和 11 项新测试，但 Sol 没有原样接受：

1. 把 reservation CAS 从只核对状态/`settledAt` 加固为同时核对旧 `updatedAt` 与旧 accounted token/cost/currency；
2. 为迟到 Provider 升级增加 Usage+reservation 写后回读复核；
3. 增加 reservation 与 Usage 的 Book/daily period 身份核验及损坏负例；
4. 全量回归发现一项旧断言仍期待 UNKNOWN FINAL 后 reservation 为 `RESERVED`，按新合同修正为 `SETTLED` 并核对估计占用和结算时间。

## 4. 测试证据

主机：

- `core:database` JVM/编译/KSP/AndroidTest 编译通过；
- 完整离线门禁中的统一 JVM 继续为 590/590。

模拟器：

- `PersistentBudgetReservationDatabaseTest`：API 30、API 35 各 23/23；
- `core:database` 全量：API 30、API 35 各 252/252；
- `feature:generation` 全量：API 30、API 35 各 42/42；
- App 恢复维护专项：API 30、API 35 各 2/2。

完整门禁：

- `scripts/verify-build.ps1 -Offline` 成功；
- 801 actionable tasks；Debug、Release、Lint/Vital、R8 和 Release APK 通过；
- `SECURITY_SCAN_TESTS_OK`、`SECURITY_SCAN_OK`；
- 源码与 5 个构建产物扫描通过；
- 备份排除策略通过。

所有设备命令只使用 `emulator-5556` 和 `emulator-5558`；物理设备写入 0，App 内真实 Provider 调用 0，Git remote 仍为空。

## 5. 尚未完成

TASK-083 仍未关闭。下一阶段必须继续完成：

1. Provider 明确证明未执行时，在同一恢复事务把 reservation 唯一推进为 `RELEASED` 且 accounted 清零；
2. 已释放后出现高可信迟到 Usage 时恢复为 `SETTLED` 并重新计入；
3. Provider-open 跨午夜时释放旧日预留并在新日重新竞争，单书占用不能被重置；
4. 实际 `ProviderConnectionProfile`、adapter protocol 与 reservation 中 connection/protocol/canonical destination 的运行时匹配。

`CHAPTER_PLAN_V1` 继续未注册，当前 APK 仍不能描述成已经能够自动生成完整小说。
