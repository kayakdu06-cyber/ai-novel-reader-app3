# 工作汇报 128：TASK-083 Phase 2 schema v17 与预算策略核心

## 1. 阶段结论

TASK-083 Phase 2 已完成并通过双 Android 版本全量数据库回归。正式主库从 schema v16 连续迁移到 v17，新增不可覆盖的 BOOK/DAILY 预算策略修订、当前 head 和每 Attempt 唯一 reservation 结构；旧 Attempt 明确迁移为 budget enforcement v0/null，不伪造历史预算占用。

本阶段只完成持久结构、策略激活和数据库保护，不宣称三层预算已经可用于真实发送。RequestIntent 原子预留、并发余额竞争、Usage 结算、跨日重预留和 Provider-open v0 阻断属于下一阶段；`CHAPTER_PLAN_V1` 继续未注册。

## 2. 已实现内容

### 2.1 schema v17

- 新增 `budget_policy_revision`：保存连续 revision/parent、BOOK 或 DAILY 身份、token/金额/币种上限、daily IANA zone、policy version 和创建时间。
- 新增 `budget_policy_head`：以 `(scope, scope_key)` 为主键，只能指向同链首 revision 或当前 head 的直接子 revision。
- 新增 `request_budget_reservation`：冻结 request estimate/limit、两层 policy、daily period、Job/Stage/Book、连接/protocol/canonical destination 与 disclosure binding。
- `request_attempt` 新增 `budget_enforcement_version INTEGER NOT NULL DEFAULT 0` 和唯一可空 `budget_reservation_id`。
- `MIGRATION_16_17` 只创建结构并回填旧 Attempt 为 0/null；不创建旧 reservation，不修改旧 Usage。
- Room schema 17 已生成并由迁移校验使用，identity hash 为 `35964eb8e730406d1d7659cee32857ea`。

### 2.2 策略与日键

- `BudgetDailyPeriodKeyV1` 只接受非负 epoch 与显式受支持 IANA zone，输出唯一 `yyyy-MM-dd|ZoneId`，从不读取系统默认时区。
- `PersistentBudgetPolicyRepository` 在单个 Room 事务中验证 book/zone/time，追加连续 revision，并插入或 CAS 推进 head。
- 同一 daily 链不能换 zone；不存在的书、倒退时间、重复 policy ID 和分叉 parent 均整笔失败，head 不移动。
- 激活与 current read 返回对象的 `toString` 不展开 policy ID、book ID、zone 或金额。

### 2.3 数据库保护

- policy revision 禁止 UPDATE/DELETE；head 禁止 DELETE，只能按直接子 revision 和非倒退时间前进。
- reservation 首次 INSERT 只能是 `RESERVED`，且 accounted 必须精确等于 estimate；不能直接伪造 SETTLED/RELEASED。
- reservation 的 identity、estimate、policy、daily period 和目的地证据不可修改；Job 所属 Book 必须与 reservation Book 一致。
- 状态只允许 `RESERVED→SETTLED/RELEASED`、`SETTLED→SETTLED` 终值修正、`RELEASED→SETTLED` 迟到高可信用量回补；时间证据不能倒退或被擦除。
- v0 Attempt 必须无 reservation；v1 Attempt 必须引用 attempt/job/stage 完全匹配的既有 reservation，两个身份字段写入后不可修改。
- 金额使用 Long micros，币种必须是三位大写 A–Z；没有 Float/Double。

## 3. DeepSeek 执行与 Sol 审查

- 任务包：`docs/ai/task-packets/TASK-083-PHASE-2-SCHEMA-POLICY-CORE.md`
- 运行：`20260809-081637-6c382bb4`
- 模式：workspace-write、max reasoning、30 分钟、无累计 Token 上限。
- 用量：总 Token 6,044,661；cached input 5,584,256；output 107,061；reasoning output 71,101。
- 结果：30 分钟守卫超时，无 final message；但已在允许清单内留下 11 个源/测试文件改动，没有权限请求或越界文件修改。

Sol 没有把超时结果直接视为完成，逐项审查并修正：

1. 将 API 30 不支持的 `LocalDate.ofInstant` 改为兼容路径；首次设备测试已真实复现并关闭该问题。
2. 收紧 reservation 首次状态、accounted/estimate 金额一致性、Job–Book 绑定、币种字符和有限状态转换。
3. 保留 RELEASED→SETTLED 的 release 时间证据，禁止倒退或擦除历史时间。
4. 新增独立 policy/guard 数据库测试，覆盖 revision/head、回滚、分叉、重复 ID、非法 SQL、v1 Attempt 绑定和迟到结算状态。
5. 修复 DeepSeek 把 v17 断言无条件放进 v10 迁移 helper 的测试分层错误；各版本只检查当时应存在的结构。

## 4. 验证证据

- `core:model` JVM：23/23，0 失败、0 错误、0 跳过。
- `core:database` JVM：90/90，0 失败、0 错误、0 跳过。
- `compileDebugKotlin`、`compileDebugAndroidTestKotlin`：通过。
- API 30 `emulator-5556`：预算/迁移定向 4/4；数据库全量 226/226。
- API 35 `emulator-5558`：预算/迁移定向 4/4；数据库全量 226/226。
- 补充分叉/重复 ID 后，预算策略类在 API 30/API 35 各 3/3 再次通过。
- `scripts/security-scan.ps1 -SkipArtifacts`：`SECURITY_SCAN_OK`。
- `git diff --check`：退出 0；Git remote 为空。
- 真实 Provider 调用 0，Fake Provider 调用 0，物理设备写入 0。

中间失败均已保留为修复依据：API 30 首次定向测试暴露 java.time 兼容问题；第一次 226 项全量回归暴露跨 schema 测试 helper 错误。修复后均在 API 30 与 API 35 复验通过，没有通过删除生产约束规避测试。

## 5. 下一阶段边界

Phase 3 必须把 reservation 正式并入唯一 RequestIntent 事务：先写候选 reservation 取得数据库写竞争，再聚合包含自身的 request/book/daily accounted，超限则 reservation/Attempt/Usage/Stage 四者零写入；并用单 Room 并发及两个 Room 实例指向同一文件证明不会双花。

之后仍需完成：

- 所有 FINAL/迟到 Usage 在 `GenerationDao.recordUsage` 唯一入口按终值结算；
- UNKNOWN 保留 estimate，实际超预留仍如实保存，只有 Provider 证明未执行才能 RELEASE；
- 跨午夜未发送请求重新预留；
- v0 Attempt 永久拒绝 Provider-open；
- 实际 Provider profile、adapter protocol 与 reservation 的 disclosure/canonical destination 完全匹配。

在上述证据完成前，不注册 chapter-plan route，不调用 App 内真实付费生成 API。
