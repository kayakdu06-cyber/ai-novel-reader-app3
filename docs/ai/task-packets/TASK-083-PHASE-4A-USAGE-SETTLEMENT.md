# TASK-083 Phase 4A：Usage 与 reservation 唯一原子结算

## 任务身份

- 任务 ID：`TASK-083 / Phase 4A usage settlement core`
- 仓库根目录：`D:\gptuser\projects\ai-novel-reader-app2`
- 基准分支与 HEAD：`main` / `8ce774429da1c3f7139a221bc241c34d81a2efdd`
- 当前连续 WIP：254 条 `git status --short`，SHA-256=`54b7cd49fb3cb16d4cc180aa5216cc1f6ba0199de4a3ea5d1a78b6a434b0029e`；全部属于用户持续开发，禁止 reset/clean/checkout/覆盖/整理
- 执行模型：DeepSeek V4 Flash（纯文本编码）

## 运行预算

- 推理等级：`max`
- 最长运行时间：30 分钟；这是一个只修改 DAO 唯一结算入口及其专项测试的窄任务
- 累计 Token 上限：无
- 预计读取文件数：规程/现场/模板 3 个、设计/状态 3 个、生产代码 5 个、测试 2 个，共约 13 个
- 预计执行命令：一次 core:database 主机编译/JVM；不得启动模拟器
- 提前停止条件：需要改 schema/entity/migration/trigger、需要改 feature/app/provider、权限阻塞、需要真实 Provider，或同一编译失败重复两次

## 目标

把 budget enforcement v1 的 reservation 结算收敛到现有 `GenerationDao.recordUsage` 的同一 Room 事务中。任何调用方只要通过该唯一 Usage 入口写入 PROVISIONAL、FINAL 或迟到 Provider usage，reservation 都必须按冻结规则保持或确定性结算；legacy v0 行保持现有行为。

本包只完成 `RESERVED -> SETTLED` 与 `SETTLED -> SETTLED` 的 Usage 同步。Provider 明确未执行的 `RELEASED`、Provider-open 跨日重建和运行时 profile/adapter 目的地绑定不在本包范围。

## 当前现场与已有 WIP

- schema v17 已有不可变 policy/head 和每 Attempt 唯一 `request_budget_reservation`；触发器允许 `RESERVED -> SETTLED`、`SETTLED -> SETTLED`、`RESERVED -> RELEASED`、`RELEASED -> SETTLED`，并保护身份字段和单调时间。
- Phase 3A/3B 已让公开 RequestIntent 在单事务内建立 enforcement v1 Attempt、UNKNOWN/PROVISIONAL Usage、RESERVED reservation 和 Stage，并在 Provider-open 前重验精确 reservation。
- `GenerationDao.recordUsage` 已是二十多个成功、失败、取消与恢复路径共同调用的唯一 Usage 写入口，但当前只更新 `usage_ledger`，完全没有同步 reservation。
- `recordUsage` 已支持 FINAL 精确 replay，以及 FINAL UNKNOWN/ESTIMATED 被迟到 PROVIDER_REPORTED 替换；必须延续这些语义，不从零改写所有上层仓库。
- `PersistentBudgetReservationDatabaseTest` 已覆盖预留、三层上限、单/双 Room 并发和重启余额；应在该类继续增加真实 Room 结算证据。

## 必读资料

1. `AGENTS.md`
2. `docs/24-AI-DEVELOPMENT-PROTOCOL.md`
3. `docs/ai/CURRENT-CONTEXT.md` 第 34～37 节
4. `docs/ai/TASK-PACKET-TEMPLATE.md`
5. `reports/2026-08-09-127-task-083-phase-1-persistent-budget-design.md` 第 3.3、5、6、8 节
6. `reports/2026-08-09-130-task-083-phase-3b-public-request-v1.md`
7. `docs/14-COST-CONTROL.md` 的 TASK-083 现行语义
8. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationDao.kt`
9. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationEntities.kt`
10. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/BudgetEntities.kt`
11. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/BudgetDao.kt`
12. `core/database/src/main/kotlin/app/zhijuan/core/database/LibraryDatabaseGuards.kt` 的 reservation/Usage 触发器（只读）
13. `core/database/src/androidTest/kotlin/app/zhijuan/core/database/PersistentBudgetReservationDatabaseTest.kt`
14. `core/database/src/androidTest/kotlin/app/zhijuan/core/database/GenerationDatabaseTest.kt` 中现有 recordUsage/recovery 测试

除上述清单和代码直接引用外，不得递归扫描整套文档、历史会话、报告、备份或其他项目。

## 范围

允许修改：

- `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationDao.kt`
- `core/database/src/androidTest/kotlin/app/zhijuan/core/database/PersistentBudgetReservationDatabaseTest.kt`
- 如专项测试确实需要复用现有测试环境，可最小修改 `core/database/src/androidTest/kotlin/app/zhijuan/core/database/BudgetedRequestTestSupport.kt`

明确不在范围：

- `BudgetEntities.kt`、`LibraryDatabaseGuards.kt`、schema、migration、数据库版本
- `GenerationUnknownResultRecoveryRepository.kt` 的 release 行为
- feature/app/provider、Provider adapter/profile、公开生成接口、runner 注册
- docs/reports/status（由 Sol 收口）
- 真实或 Fake Provider 调用、模拟器运行、网络/代理/DNS/凭据配置

## 不可破坏的约束

- 项目隔离：不得访问或修改 `D:\gptuser\projects\ai-novel-reader` 或其他目录。
- 安全与隐私：不读取、输出或记录 API Key；异常和 `toString` 不增加 reservation/attempt/connection/金额/币种/hash 明文。
- 唯一入口：不得要求二十多个上层调用方另外调用预算仓库；v1 reservation 同步必须发生在 `GenerationDao.recordUsage` 自身事务内。
- legacy 兼容：budget enforcement v0 Attempt 没有 reservation，必须完全保留现有 Usage 写入、replay 和迟到升级行为。
- PROVISIONAL：可更新 Usage，但 v1 reservation 必须保持 `RESERVED`，accounted 仍为原估计。
- FINAL UNKNOWN：v1 reservation 变为 `SETTLED`，accounted token/cost/currency 保留原估计，`settledAt` 取本次 Usage 的 `updatedAt`。
- FINAL ESTIMATED/PROVIDER_REPORTED：v1 reservation 变为 `SETTLED`，accounted 必须被终值确定性替换；token 使用 `totalTokens`，cost/currency 使用 Usage 终值（两者均空时保持空，不伪造价格）。实际值可高于预留，必须如实保存，后续请求自然被聚合阻断。
- 迟到 Provider usage：FINAL UNKNOWN/ESTIMATED 可被 PROVIDER_REPORTED 升级，Usage 与 reservation 必须在同一事务确定性替换；reservation 原 `settledAt` 不变，只单调推进 `updatedAt`。
- replay：相同 FINAL replay 返回同一 Usage，同时必须确认 v1 reservation 已处于与该 Usage 一致的 SETTLED 状态；不得重复累计，不使用 delta。
- 并发/损坏：v1 Attempt 缺 reservation、reservation 不属于该 Attempt、状态/计入值冲突或 CAS 丢失时整笔事务失败关闭，Usage 与 reservation 都不能留下半更新。
- 金额和 token 只能使用 `Long`；不得用 Float/Double，不做币种换算。
- 不允许为兼容测试增加 v0 发送旁路、默认预算、nullable budget 或测试开关。

## 实施要求

1. 在 `GenerationDao` 内新增最小 reservation 查询/CAS SQL，使 `recordUsage` 无需调用平行 repository 就能在自己的 `@Transaction` 内完成 v1 结算。
2. 在任何 Usage 写入前读取 Attempt；v0 沿用旧逻辑，v1 必须验证 enforcement version、非空 reservation ID、reservation attempt 身份和当前 Usage/预留组合。
3. PROVISIONAL 只更新 Usage并回读验证 reservation 仍 RESERVED/估计值未变。
4. 第一次 FINAL 按来源执行 UNKNOWN 保留估计或 known 终值替换，并将 reservation 设为 SETTLED；Usage 更新、reservation 更新和回读校验必须同事务。
5. 已 FINAL 的相同 replay 必须回读验证 reservation 一致；迟到 PROVIDER_REPORTED 升级必须同时升级 Usage 与 reservation。其他冲突继续拒绝。
6. 新增真实 in-memory Room Android 测试，至少覆盖：
   - PROVISIONAL known update 不结算 reservation；
   - FINAL UNKNOWN 保留估计并 SETTLED；
   - FINAL ESTIMATED/PROVIDER_REPORTED 以终值替换，包含高于预留的实际 token；
   - 相同 FINAL replay 不重复累计；
   - FINAL UNKNOWN/ESTIMATED 的迟到 Provider 升级替换终值且 `settledAt` 不变；
   - v0 Usage 仍按原行为工作；
   - v1 reservation 丢失/错态/错绑或结算 CAS 冲突时没有 Usage/reservation 半更新。
7. 不删除、放松或跳过现有断言。

## 验收标准

- [ ] 所有生产 `recordUsage` 调用自动获得 v1 reservation 原子结算，无需上层改动。
- [ ] UNKNOWN、known、迟到升级、replay 和 legacy v0 行为均有专项证据。
- [ ] 任一 reservation 证据错误都在事务内失败关闭，不留下半结算。
- [ ] 无 schema/trigger/feature/app/provider/docs 改动。
- [ ] 主机 core:database 编译与 JVM 测试通过。

## 验证命令

```powershell
$env:GRADLE_USER_HOME='D:\gptuser\cache\gradle'
$env:ANDROID_USER_HOME='D:\gptuser\cache\android'
.\gradlew.bat :core:database:test :core:database:compileDebugKotlin :core:database:compileDebugAndroidTestKotlin --offline --no-daemon --console=plain '-Dkotlin.compiler.execution.strategy=in-process'
```

不得启动模拟器。未运行的验证必须在回交中写明原因，不能写成通过。

## 回交格式

请严格按以下标题返回：

1. `完成内容`
2. `修改文件`
3. `验证`
4. `未完成/风险`
5. `需要 Sol 处理`
6. `假设`

不要宣布整个 TASK-083 或 Phase 4 完成，不要更新正式状态；由 Sol 根据差异和模拟器证据确认。
