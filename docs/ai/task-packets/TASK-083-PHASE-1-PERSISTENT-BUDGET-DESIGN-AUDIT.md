# TASK-083 Phase 1：持久三层预算数据库设计只读审计

## 任务身份

- 任务 ID：`TASK-083 / Phase 1 persistent budget design audit`
- 仓库根目录：`D:\gptuser\projects\ai-novel-reader-app2`
- 基准分支与 HEAD：`main` / `8ce774429da1c3f7139a221bc241c34d81a2efdd`
- 当前未提交改动：233 条连续 WIP；`git status --short` 的 SHA-256 为 `111a943291511250c3530be5f82639a78bb314d8cdc7e7b09074fdeee6f626b0`；不得 reset、clean、checkout、覆盖或整理任何改动
- 执行模型：DeepSeek（纯文本）

## 运行预算

- 推理等级：`max`
- 最长运行时间：25 分钟；这是数据库原子性与跨状态结算的窄设计审计，不授权实现
- 累计 Token 上限：无
- 预计读取文件数与明确清单：14 个，见“必读资料”
- 预计执行命令/测试数：只读 `git status`、`rg`、`Get-Content`；不得运行 Gradle、模拟器、联网测试或 App Provider
- 提前停止条件：需要扩张到未列文件、发现任务包事实不成立、权限阻塞、重复失败或任何写文件需求

## 目标

只读审计 Sol 拟定的 schema v17 持久预算方案，找出会造成并发超支、重启绕过、跨日错记、未知用量释放为零、迟到 Provider usage 重复/漏记、目的地变化后仍发送或历史迁移无法结算的 P0/P1 缺陷。不得实现代码；请给出最小修正建议和必须具备的事务/测试不变量。

## 当前现场与已有 WIP

- 已存在的实现：
  - `BudgetEngine` 是纯内存 request/book/daily 领域原型；无生产调用。
  - `GenerationDao.recordRequestIntent` 在一个 Room 事务中创建 Attempt、UNKNOWN/PROVISIONAL Usage，并把 Stage 推进到 `REQUEST_INTENT_RECORDED`。
  - `UsageLedger` 支持 UNKNOWN→ESTIMATED→PROVIDER_REPORTED 单向升级，FINAL estimated/unknown 可被一次迟到 Provider 报告纠正。
  - schema v16；没有 budget policy、head、reservation 或 counter 表。
  - 外部目的地确认内核已完成 canonical origin、版本化 binding、CAS 接受与动态读取；evidence 不是发送许可。
- 已存在的测试：`BudgetEngineTest`、`GenerationDatabaseTest`、`ZhijuanMigrationTest`、`ConnectionDatabaseTest`。
- 已知失败或缺口：当前 RequestIntent 事务没有持久预算竞争；`budgetSnapshotJson` 只是不可变意图，不是余额；`CHAPTER_PLAN_V1` 未注册。
- 必须延续、不得从零重写的部分：Attempt/Usage 单向升级、Stage/Job/lease 状态机、现有目的地 binding、v1→v16 连续迁移、所有连续 WIP。

## Sol 拟定方案（请审计，不要直接实现）

### A. schema v17

1. `budget_policy_revision`
   - 不可变 revision；范围只允许 `BOOK` 或 `DAILY`。
   - 保存 `policyId/scope/scopeKey/revisionNo/parentPolicyId/bookId/dailyZoneId/maxTokens/maxCostMicros/currency/policyVersion/createdAt`。
   - BOOK 的 scopeKey 精确等于 bookId、无 zone；DAILY 使用固定 global scopeKey、无 bookId、有 IANA zone。
   - 父 revision 必须同范围/同 key/同 book/同 zone 且 revisionNo 连续；禁止 fork、UPDATE、DELETE。
2. `budget_policy_head`
   - `(scope, scopeKey)` 唯一指向当前 revision；新 revision 与 CAS 推进 head 同一事务。
   - 调高或调低上限都创建新 revision，不覆盖历史。日预算 zone 在同一 head 链内不可改变。
3. `request_budget_reservation`
   - 每个新 Attempt 恰好一条、attemptId 唯一；保存 request 上限、最坏合理 token/金额估计、当前 accounted 值、创建时 book/daily policy revision、dailyPeriodKey、目的地 binding 快照和状态。
   - 状态有限为 `RESERVED/SETTLED/RELEASED`；身份、估计、策略和目的地字段不可变，只允许有限状态/计入值更新，禁止 DELETE。
   - `accountedTokens` 初始等于 estimatedTokens；已知终态改为 actual total tokens；UNKNOWN FINAL 仍保留 estimate；明确 Provider 未执行才允许 RELEASED=0。
   - 价格未知时 cost 保持 NULL，但 token 始终非空并受硬上限。
4. `request_attempt`
   - 新增 `budgetEnforcementVersion`。v16 历史行迁移为 0；v17 新请求只能为 1，且必须拥有 reservation。历史 v0 允许按旧规则完成，但不能生成新的 Provider-open permit。

### B. 原子 RequestIntent

- `NewRequestIntent/RequestIntentDraft` 增加有限预算草稿与 `connectionId + expected disclosure binding/protocol/version`。
- 唯一生产 `recordRequestIntent` 事务重新读取 exact Stage/Job/lease、当前 connection disclosure、当前 BOOK/DAILY policy head，按 policy zone 从 `createdAt` 计算并核对 dailyPeriodKey。
- 在同一 Room/SQLCipher 写事务中计算历史 `accounted` 总量，检查 request/book/daily token 及可比较币种金额，再创建 Attempt、UNKNOWN/PROVISIONAL Usage、reservation、Stage 转换；任一步失败整笔零写入。
- 不用 `GenerationJob.budgetSnapshotJson` 作计数器。

### C. 并发与计数来源

- v1 不另建可漂移 counter row；book/daily 当前占用由不可删除 reservation 的 `accounted` 列按索引聚合。
- Room 写事务必须让两个并发 reservation 串行竞争；测试要用两个独立 coroutine/connection 对同一共享日上限竞争，并证明最多一个成功、失败方 Attempt/Usage/reservation/Stage 写入均为零。
- 对所有 token/金额与聚合使用 Long 精确加法、合理上界和溢出失败关闭；不使用 Float/Double。

### D. Provider-open 与结算

- permit 必须绑定 reservationId。Provider-open 事务重新验证 Attempt/Usage/reservation、exact lease、当前目的地 binding、当前 policy heads、当前 aggregate 不超限，并要求 `validatedAt` 仍属于 reservation 的 dailyPeriodKey；跨午夜未发送请求必须重新预留。
- Usage PROVISIONAL 更新不释放 reservation。Usage FINAL 与 reservation 结算在同一事务：
  - PROVIDER_REPORTED/ESTIMATED 的 total tokens 更新 accounted tokens；
  - UNKNOWN FINAL 保留原 token estimate，不写 0；
  - 可比较币种金额更新 accounted cost；不能比较时保留原保守金额或 NULL，不跨币种相加；
  - FINAL estimated/unknown 被迟到 Provider 报告升级时，同一事务按新值幂等修正 accounted，不重复累计；即使实际值使预算超限也必须保存真实用量，并阻止后续请求。
- 只有 Provider 明确证明未执行的既有恢复事务可把 reservation 变为 RELEASED；若之后仍出现更高可信 Provider usage，则恢复为 SETTLED 并重新计入。

### E. 明确不在本阶段

- 不实现 PriceCatalog、创建前整书费用范围、用量 UI、预算弹窗或真实 Provider 调用。
- 不注册 `CHAPTER_PLAN_V1`，不修改 App Provider adapter。

## 必读资料

1. `AGENTS.md`
2. `docs/24-AI-DEVELOPMENT-PROTOCOL.md`
3. `docs/ai/CURRENT-CONTEXT.md` 的第 32～33 节
4. `docs/14-COST-CONTROL.md`
5. `docs/18-DECISION-LOG.md` 的 DEC-071、DEC-072
6. `reports/2026-08-09-125-task-064-phase-2e4a-destination-budget-audit.md`
7. `core/model/src/main/kotlin/app/zhijuan/core/model/BudgetModel.kt`
8. `core/task/src/main/kotlin/app/zhijuan/core/task/BudgetEngine.kt`
9. `core/task/src/test/kotlin/app/zhijuan/core/task/BudgetEngineTest.kt`
10. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationEntities.kt`
11. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationDao.kt` 中 intent/usage 相关部分
12. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationRequestAuditRepository.kt`
13. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationUnknownResultRecoveryRepository.kt` 中结算/未执行证明相关部分
14. `core/database/src/main/kotlin/app/zhijuan/core/database/connection/ConnectionDao.kt`

不得递归读取全部历史报告、全部 Android 测试或原项目副本。若需要查看 Room migration/trigger 写法，可额外只读 `ZhijuanDatabase.kt`、`ZhijuanMigrations.kt` 末尾和 `LibraryDatabaseGuards.kt` 中相关 helper。

## 范围

允许修改：无。本任务为 `read-only` 设计审计。

明确不在范围：

- 不写 Kotlin、SQL、文档、task packet、测试或 schema JSON。
- 不调整现有未提交 WIP，不运行格式化，不更新任务状态。
- 不调用真实/Fake Provider，不运行 Gradle/模拟器，不读取密钥。

## 不可破坏的约束

- 项目隔离：不得访问或修改其他项目副本。
- 安全与隐私：输出不得包含 base URL、binding hash、小说内容、密钥或快照正文；只使用表/字段/有限枚举名称。
- 状态机与幂等：不能通过删除历史 Usage/Attempt/reservation“修复”计数；重试/续接/格式修复必须新 reservation。
- 数据库与事务：reservation、Attempt、UNKNOWN/PROVISIONAL Usage 和 Stage INPUT_FROZEN 必须同成同败。
- 联网与费用：不调用 App 内真实 API，不产生费用。
- 兼容性：schema v16 历史 Attempt 必须可迁移、可完成现有恢复，但不能获得新的无预算发送许可。
- 保留所有未提交改动。

## 审计要求

1. 按 P0/P1/P2 列出缺陷；没有相应缺陷时明确写“未发现”。
2. 重点回答：
   - 聚合 reservation 而不建 counter row，在 Room/SQLite 事务下能否可靠防并发超支；需要什么事务形态或测试才成立？
   - policy head 变化、跨午夜、UNKNOWN FINAL、RELEASED 后迟到 usage 的状态转换是否自洽？
   - v16 legacy attempt 如何既兼容结算又禁止重新打开 Provider？
   - 哪些数据库 trigger/index/unique/FK 是不可缺的？
   - 方案是否会在 replay 时重复记账，或在实际用量大于预留时错误回滚真实 usage？
3. 建议必须最小、可落地，不要扩大到 UI、PriceCatalog 或完整 runner。

## 验收标准

- [ ] 只读完成，任务前后 Git 状态没有新增差异。
- [ ] 给出明确 P0/P1 结论与事务不变量。
- [ ] 覆盖并发、重启、跨日、未知价格、UNKNOWN、迟到 usage、未执行释放和 legacy migration。
- [ ] 不把建议写成已完成代码。

## 验证命令

```powershell
git status --short
```

不得运行构建或测试。

## 回交格式

请严格按以下标题返回：

1. `完成内容`
2. `P0/P1/P2 审计结论`
3. `必须冻结的事务与状态不变量`
4. `最小修正建议`
5. `未完成/风险`
6. `需要 Sol 处理`
7. `假设`

不要宣布 TASK-083 完成，不要更新正式状态。
