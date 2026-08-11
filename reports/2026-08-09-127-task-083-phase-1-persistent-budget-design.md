# 工作汇报 127：TASK-083 Phase 1 持久三层预算设计冻结

> 日期：2026-08-09  
> 项目：织卷（app开发2）  
> 唯一仓库：`D:\gptuser\projects\ai-novel-reader-app2`  
> 本阶段性质：架构与数据库原子性设计；无业务代码、无 schema 变更、无 Provider 调用

## 1. 本阶段结论

TASK-083 的实现边界已经冻结。正式预算不能继续依赖内存 `BudgetEngine` 或 Job 的 `budgetSnapshotJson`，而要建立三个持久事实：

1. 不可覆盖的单书/每日预算策略修订与当前 head；
2. 每个新 Attempt 唯一、不可删除的请求预算预留；
3. Usage 终态与预留计入值同事务结算的唯一 DAO 入口。

单次请求上限保存在本次 reservation；单书和每日上限来自当前 policy head。所有非 RELEASED reservation 的 `accounted` 值共同构成当前占用，不另建可能与明细漂移的余额计数器。

## 2. 为什么不能“先查余额，再写请求”

如果两个执行器先分别读取同一个旧余额，再各自插入一笔请求，它们可能都认为预算足够。正式事务改为：

```text
读取并验证 Stage/Job/lease
  -> 在同一事务插入候选 reservation（先取得写竞争权）
  -> 聚合时包含候选 reservation
  -> 检查 request/book/daily 三层上限
       超限 -> 抛出有限预算拒绝，整笔事务回滚
       通过 -> 写 Attempt + UNKNOWN/PROVISIONAL Usage + Stage INPUT_FROZEN
```

因此失败方不会留下 reservation、Attempt、Usage 或 Stage 半状态。除同一数据库实例的并发测试外，必须增加两个 Room 实例指向同一文件的竞争测试，避免测试只证明 Room 进程内队列化。

## 3. schema v17 方向

### 3.1 `budget_policy_revision`

- 只允许 `BOOK` 与 `DAILY`；REQUEST 上限属于单次 reservation。
- 保存范围、稳定 key、连续 revision、父 revision、token/金额上限、币种、日预算 IANA zone、策略版本和创建时间。
- 同一链的 scope/key/book/zone 不可变化；禁止 fork、UPDATE 和 DELETE。

### 3.2 `budget_policy_head`

- `(scope, scopeKey)` 唯一指向当前策略修订。
- 新修订与 head CAS 同事务；调高/调低均保留历史。
- head 只决定后续新 reservation 的上限。已经持久成功的 reservation 是已授予额度，不因后续改限被回写；用户暂停/停止走既有 Job 控制状态机。

### 3.3 `request_budget_reservation`

- 每个 v17 Attempt 恰好一条；保存 request 上限、最坏合理估计、当前 accounted、book/daily policy、daily period 和目的地确认快照。
- 状态只有 `RESERVED / SETTLED / RELEASED`。
- 初始 `accountedTokens = estimatedTokens`；价格未知时金额为 NULL，但 token 绝不为空。
- UNKNOWN FINAL 保留原估计；已知终态按总 token 重算；只有 Provider 明确证明未执行才 RELEASED 为 0。
- 迟到 Provider usage 按终值重算 accounted，不能用 `+= delta`；实际高于预留也必须如实保存，后续请求因此被阻断。

### 3.4 legacy Attempt

- `request_attempt` 增加 `budgetEnforcementVersion` 和 reservation 身份；v16 历史行迁移为 v0，新请求只能为 v1。
- v0 可继续本地恢复和 Usage 结算，但永远不能重新获得 Provider-open permit。
- 不伪造 v16 UNKNOWN reservation：项目事实基线是 v17 前 App 内真实 Provider 调用为 0，且 plan route 一直未注册。旧测试/开发审计行原样保留，不冒充真实预算占用。

## 4. 目的地与真实发送对象必须同一份

只在数据库里验证 connection disclosure 还不够；执行器随后收到的 `ProviderConnectionProfile` 也必须与 reservation 捕获的 connectionId、protocol 和 canonical destination 完全一致，且 adapter protocol 必须一致。否则调用方可能通过“数据库确认 A、实际传入 B”绕过目的地确认。

正式顺序为：

1. RequestIntent 事务动态读取并验证当前 disclosure；
2. reservation 保存脱敏 binding 身份；
3. Provider-open 再动态重读数据库证据；
4. 打开 adapter 前，用不可变 profile 重新计算同一 binding 并与 permit 匹配。

## 5. 唯一结算入口

现有 `recordUsage` 被二十多个提交、失败、取消和恢复路径复用。如果每个上层仓库分别结算预算，必然出现漏接或重放重复。因此 v17 必须把 reservation 结算收敛进 `GenerationDao.recordUsage` 的同一事务：

- PROVISIONAL usage 不释放 reservation；
- FINAL UNKNOWN 保留估计；
- FINAL ESTIMATED/PROVIDER_REPORTED 按终值更新 accounted；
- FINAL estimated/unknown 的迟到 Provider 升级同时修正 reservation；
- Provider 明确未执行的恢复事务是唯一 RELEASE 入口；
- replay 必须返回同一结果，不能重复累计。

## 6. 每日周期

- 日键由持久 daily policy 的 IANA zone 与 epoch 时间通过唯一规范函数计算，格式仍兼容 `yyyy-MM-dd|ZoneId`。
- RequestIntent 的日键必须与 `createdAt` 严格一致，不能由调用方随意换 key 绕过上限。
- Provider-open 若已经跨到新日，不消费旧日 reservation，必须释放/重建到新日；单书层不重置。
- 同一 daily policy 链不允许静默换 zone。

## 7. DeepSeek 只读复核

- 任务包：`docs/ai/task-packets/TASK-083-PHASE-1-PERSISTENT-BUDGET-DESIGN-AUDIT.md`
- run：`20260809-075939-b1d748ce`
- 模式：read-only / patch proposal only / max reasoning / 25 分钟 / 无 Token 上限
- 实际耗时：约 10 分 39 秒
- Token：总 1,367,455；cached input 1,169,920；output 55,701；reasoning 41,887
- 结果：0 权限请求、0 文件写入；运行前后 `git status --short` 均 233 条。

Sol 采纳其“双连接并发证据”和“结算收敛到唯一 DAO 入口”两项高风险建议；修正其两点不适用判断：

- 聚合必须包含同书/同日跨所有历史策略修订的有效 reservation，不能通过切换 head 重置已用量；
- v16 之前真实 Provider 为 0，不能为历史 UNKNOWN 测试行伪造 token 估计或错误声称存在旧真实费用。

## 8. 实现与验收顺序

1. Phase 2：schema v17、policy revision/head、reservation 实体、迁移和数据库保护；
2. Phase 3：目的地+预算+RequestIntent 原子事务、双连接并发和跨日/重启；
3. Phase 4：`recordUsage` 唯一结算、UNKNOWN、迟到 usage、未执行释放和真实 profile 绑定；
4. Phase 5：双 API 全量、Release/R8、安全扫描、文档收口；
5. 全部通过前 `CHAPTER_PLAN_V1` 继续未注册，App 内真实生成 API 仍为 0。

## 9. 当前验证边界

本阶段没有改业务代码或 schema，因此没有运行 Gradle、模拟器或统一门禁。可继承的上一阶段证据仍是：core:model 17/17、双 API 数据库各 222/222、801-task 离线门禁通过；这些证据不能冒充 TASK-083 已实现。

