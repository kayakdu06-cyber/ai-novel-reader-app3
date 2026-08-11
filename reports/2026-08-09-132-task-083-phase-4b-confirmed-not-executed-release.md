# 工作汇报 132：TASK-083 Phase 4B 明确未执行释放与迟到回补

日期：2026-08-09  
项目：织卷 Android App  
结论：Phase 4B 已完成并通过双 API 与完整离线门禁；TASK-083 仍未关闭。

## 1. 本阶段完成内容

- 只有恢复策略已经裁决为 `REQUEUE_PROVEN_NOT_EXECUTED` 时，才允许进入专用释放事务。普通失败、断网、超时、Provider 无结论、本地已有正文或已知 Usage 都不能释放预算。
- 同一个外层 Room 事务依次完成 Attempt→`FAILED_RETRYABLE`、Usage→UNKNOWN/FINAL、v1 reservation→`RELEASED`且accounted清零、Stage/Job→READY；任一步失败，五类状态全部回滚。
- 专用入口只接受 UNKNOWN/PROVISIONAL、全部用量与金额为空、同一审计时间的 Attempt，以及仍为 RESERVED/accounted=estimate 的同 Attempt reservation。legacy v0 只封账 Usage，不伪造 reservation。
- 已释放后若收到 FINAL `PROVIDER_REPORTED`，现有 `recordUsage` 会原子恢复 `SETTLED`，按终值重新计入 book/daily，占用可高于旧估计；保留原 `releasedAt`，以迟到报告时间写首次 `settledAt`。相同 replay 不重复，UNKNOWN/ESTIMATED 不能复活。

## 2. DeepSeek 与 Sol 审查

- DeepSeek Run：`20260809-123220-9e8bc700`。
- 模型：DeepSeek V4 Flash；推理强度 `max`；无总 Token 上限；正常完成约 27 分 44 秒。
- 使用量：总 Token 8,817,163；缓存输入 8,079,232；输出 137,513；推理输出 87,644；无权限请求。
- DeepSeek 确实生成了允许范围内的代码与测试差异。
- Sol 独立审查后发现专用入口仍容许已 FINAL Usage 继续释放，违反“释放前必须 UNKNOWN/PROVISIONAL”的边界；已改为失败关闭，并补 Attempt/审计时间一致性、旧 `releasedAt/settledAt` CAS 条件、v0/v1 UNKNOWN FINAL 写后精确回读和负向测试。

## 3. 真实测试发现与处理

- API 30 首次专项运行发现一项测试断言错误：所有 reservation 都被排除后，SQLite `SUM` 对无行集合返回 `NULL`，测试错误地期望数字 0。DAO 对该语义已有明确注释，生产代码没有错误；只把测试改为断言 `NULL` 后重跑。
- API 30 的 App 专项有一次 UTP/ADB 本地模拟器通道瞬时超时，实际启动 0 项。确认 `emulator-5556` 在线且启动完成后原命令重跑获得真实 2/2；没有改代理、DNS、防火墙或互联网配置，也未把0项执行当成通过。

## 4. 验证结果

- 主机端 `core:database` 编译、JVM 和 Android 测试源码编译通过。
- `PersistentBudgetReservationDatabaseTest`：API 30、API 35 各 30/30。
- `core:database` 全量：API 30、API 35 各 259/259。
- `feature:generation` 全量：API 30、API 35 各 42/42。
- App 恢复维护专项：API 30、API 35 各 2/2。
- `scripts/verify-build.ps1 -Offline`：801 actionable tasks；Debug、Release、Lint/Vital、R8、Release APK、统一 JVM 590/590、安全扫描4项回归、源码与5个构建产物扫描、备份排除全部通过。
- App 内真实 Provider 调用 0；物理设备写入 0；只使用 `emulator-5556` 与 `emulator-5558`；Git remote 为空。

## 5. 尚未完成与下一阶段

TASK-083 仍需完成：

1. Provider-open 时若跨过 DAILY policy 的午夜边界，原子释放旧日预留并在新日重新竞争；单书累计不能因换日重置。
2. 实际 ConnectionProfile 与具体 Provider adapter 打开连接前，精确匹配 reservation 冻结的 connection、protocol 和 canonical destination。

`CHAPTER_PLAN_V1` 仍未注册，不能把当前阶段描述为完整小说生成链已经可用。
