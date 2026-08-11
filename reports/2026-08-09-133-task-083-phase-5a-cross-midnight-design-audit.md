# 工作汇报 133：TASK-083 Phase 5A 跨午夜重预留设计审计

> 日期：2026-08-09  
> 项目：织卷 Android App  
> 范围：只读设计审计；没有修改生产代码、没有运行 Gradle/模拟器、没有调用 App Provider

## 1. 结论

Provider-open 时如果当前日键已经不同于 reservation 的日键，不能继续使用旧 Attempt，也不能原地修改 reservation。正确流程是：

1. 在打开 adapter 之前检测换日；
2. 在一个 Room 事务里结束旧的未发送 Attempt、把 UNKNOWN/PROVISIONAL Usage 封为 UNKNOWN/FINAL、把旧 reservation 变为 RELEASED；
3. Stage/Job 释放租约并回到持久队列；
4. runner 重新取得租约后，按新时间创建新的 Attempt、Usage、reservation 和发送许可；
5. 实际 Provider request、timing context 和新 Attempt 必须重新绑定，旧内存 permit 永远不能继续使用。

因此明确否决“在 claim 事务内部替换 Attempt 后直接拿旧 `GenerationRequest` 发送”的方案。那会让数据库身份、加密草稿、一次性 permit 和实际 Provider 请求互相错位。

## 2. Sol 对 DeepSeek 建议的两项修正

### 2.1 新 Attempt 必须消耗一次 attempt

`request_attempt` 对 `(stage_id, attempt_no)` 有唯一索引，`generation_stage.attempt_count` 也是后续路由和 `maxAttempts` 的权威边界。因此不能让替代 Attempt 复用相同 attemptNo，也不能只增加 attemptNo 而不增加 attemptCount。

最终策略：

- 旧的未发送 RequestIntent 已经算一次 attempt；
- 换日重建使用下一 attemptNo，并正常增加 attemptCount；
- 如果旧 Attempt 已经用完 `maxAttempts`，释放旧日占用后转入 `NEEDS_ACTION`，不能通过午夜循环绕过上限；
- 默认自动重试次数足够时，正常路径仍然无用户操作。

### 2.2 不盲目复制普通请求，但必须保留续写种子

普通首次请求的未发送流草稿通常为空，重新准备时新建空 artifact 即可。续写请求不同：其旧 artifact 已保存由成功父 Attempt 派生的加密种子；换日后最新 Attempt 会变成失败的 rollover Attempt，原有续写仓库不能再把它误当成成功父 Attempt。

因此后续 replacement-preparation 必须：

- 为新 Attempt 建立独立 artifact；
- 普通空草稿可新建；
- 非空续写种子从旧 rollover Attempt 的受保护 artifact 读取并通过 `createAndClear` 写入新 artifact；
- 不共享 artifact，不建立明文中间文件，旧 artifact 交给现有 retention 清理。

这部分放在 Phase 5C，不塞进 Provider-open claim 事务。

## 3. 冻结的状态设计

新增持久审计语义 `DAILY_BUDGET_PERIOD_EXPIRED_BEFORE_SEND`：

- Attempt：`INTENT_RECORDED -> FAILED_RETRYABLE`；
- Usage：`UNKNOWN/PROVISIONAL -> UNKNOWN/FINAL`，所有用量和费用值仍为空；
- Reservation：`RESERVED -> RELEASED`，accounted token 清零、费用和币种为空；
- 有剩余 attempt：Stage `REQUEST_INTENT_RECORDED -> READY`，Job `RUNNING -> READY`；
- 没有剩余 attempt：Stage/Job 都进入 `NEEDS_ACTION`；
- Stage/Job 租约必须清空；旧 Permit 因持久状态已变化而永久失效。

跨午夜释放必须有独立 DAO 入口，不能复用名称和前置语义属于 `Provider CONFIRMED_NOT_EXECUTED` 的 Phase 4B 入口。两条路径可以复用低层 CAS，但必须分别验证专用错误码、旧 Attempt 状态、未发送证据、Usage 和 reservation 精确旧值。

## 4. 日键来源

Provider-open 重新读取当前 DAILY policy head/revision，并通过唯一 `BudgetDailyPeriodKeyV1` 用 `validatedAt` 计算当前日键。同一 policy 链的 IANA zone 不允许变化；已授予 reservation 不因普通限额修订被回写，只有日键实际变化才触发 rollover。

新 reservation 仍由标准 RequestIntent 事务读取当时的 BOOK/DAILY head 并竞争额度：

- DAILY 只统计新日；
- BOOK 继续跨日统计所有非 RELEASED reservation；
- 新日额度不足时，新候选事务完整回滚，旧 Attempt 已处于干净、可审计的终态，不存在 Provider 半发送。

## 5. 实现切片

- Phase 5B：Provider-open 换日检测、专用释放事务、状态机、上限分支、旧 permit 失效和零 Provider 调用测试。
- Phase 5C：runner 重新准备原语、新 ID、新 reservation、新 artifact，以及普通/续写种子的安全重建。
- Phase 5D：把重新准备结果接到后续 total runner 的远程 route 循环；当前生产 total runner 尚未完成，不能提前宣称端到端自动生成已经接通。

## 6. DeepSeek 只读审计

- 任务包：`docs/ai/task-packets/TASK-083-PHASE-5A-CROSS-MIDNIGHT-DESIGN-AUDIT.md`
- run：`20260809-132802-f5f3a58a`
- 模式：read-only / patch proposal only / max reasoning / 单次 45 分钟安全护栏 / 无累计 Token 上限
- 实际耗时：约 18 分 19 秒
- Token：总 4,457,940；cached input 3,912,064；output 94,938；reasoning 59,943
- 结果：正常完成、0 权限请求、0 Provider、0 测试、0 生产文件写入。

## 7. 本阶段未宣称完成的内容

- 尚未实现 Phase 5B/5C 代码；
- 尚未运行 JVM、API 30、API 35 或统一离线门禁；
- 尚未接实际 Provider profile/adapter canonical destination 匹配；
- TASK-083 仍为进行中。

