# TASK-083 Phase 2：schema v17 与预算策略核心

## 任务身份

- 任务 ID：`TASK-083 / Phase 2 schema and policy core`
- 仓库根目录：`D:\gptuser\projects\ai-novel-reader-app2`
- 基准分支与 HEAD：`main` / `8ce774429da1c3f7139a221bc241c34d81a2efdd`
- 当前未提交改动：234 条连续 WIP；`git status --short` SHA-256=`93ef3049eb336cfa315f8c36f5a8116bca5d61d664dbfb8ef0cec9c2d25574ad`；不得 reset/clean/checkout/覆盖/整理
- 执行模型：DeepSeek（纯文本）

## 运行预算

- 推理等级：`max`
- 最长运行时间：30 分钟
- 累计 Token 上限：无
- 预计读取文件数：15 个以内，严格按“必读资料”与允许修改清单
- 预计执行命令/测试数：`git status`、有限 `rg/Get-Content`、最多 4 条 Gradle 编译/JVM/AndroidTest 编译；不得启动模拟器
- 提前停止条件：需要修改允许列表外业务文件、迁移设计与当前 schema 不兼容、重复构建失败、权限阻塞或需要真实 Provider

## 目标

在现有连续 WIP 上实现 TASK-083 Phase 2 的 schema v17 与预算策略核心：三张预算表、RequestAttempt legacy/enforcement 字段、连续迁移、Room guards/type converters，以及可原子激活 BOOK/DAILY policy revision 的仓库与离线测试。本阶段不接 RequestIntent reservation 写入、不结算 Usage、不注册 plan route。

## 当前现场与已有 WIP

- schema 当前为 v16，迁移注册连续到 `MIGRATION_15_16`。
- `BudgetModel.kt` 已有 `BudgetScope/Limit/Counter/...`，`BudgetEngine` 是纯内存原型。
- RequestAttempt 只在 `GenerationDao.recordRequestIntent` 构造；因此可增加带默认值的 enforcement/reservation 字段而不批量改实体构造点。
- DEC-073 与工作汇报127已冻结：policy revision/head + 每Attempt reservation；聚合明细而不建counter row；legacy v0不得未来Provider-open。
- Phase 1 DeepSeek只读审计已完成；不要重做架构或扩大到 Usage/Provider。

## 必读资料

1. `AGENTS.md`
2. `docs/24-AI-DEVELOPMENT-PROTOCOL.md`
3. `docs/ai/CURRENT-CONTEXT.md` 第34节
4. `docs/ai/TASK-PACKET-TEMPLATE.md`
5. `reports/2026-08-09-127-task-083-phase-1-persistent-budget-design.md`
6. `docs/18-DECISION-LOG.md` DEC-073
7. `core/model/src/main/kotlin/app/zhijuan/core/model/BudgetModel.kt`
8. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationEntities.kt`
9. `core/database/src/main/kotlin/app/zhijuan/core/database/ZhijuanDatabase.kt`
10. `core/database/src/main/kotlin/app/zhijuan/core/database/ZhijuanMigrations.kt` 末尾及最近迁移
11. `core/database/src/main/kotlin/app/zhijuan/core/database/LibraryDatabaseGuards.kt` request/usage/immutable helper
12. `core/database/src/main/kotlin/app/zhijuan/core/database/library/LibraryTypeConverters.kt`
13. `core/database/src/androidTest/kotlin/app/zhijuan/core/database/ZhijuanMigrationTest.kt` 注册表、最新迁移和 latest-schema helper
14. `core/database/src/androidTest/kotlin/app/zhijuan/core/database/ConnectionDatabaseTest.kt` 的 Room 测试样式
15. `core/database/build.gradle.kts`

不得递归读取全部历史报告或原项目。为复用 Book fixture 可有限查看 `GenerationDatabaseTest.seedBook`。

## 允许修改

- `core/model/src/main/kotlin/app/zhijuan/core/model/BudgetModel.kt`
- 可新增 `core/model/src/test/.../BudgetDailyPeriodKeyV1Test.kt`
- 可新增 `core/database/src/main/kotlin/app/zhijuan/core/database/generation/BudgetEntities.kt`
- 可新增 `core/database/src/main/kotlin/app/zhijuan/core/database/generation/BudgetDao.kt`
- 可新增 `core/database/src/main/kotlin/app/zhijuan/core/database/generation/PersistentBudgetPolicyRepository.kt`
- `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationEntities.kt`
- `core/database/src/main/kotlin/app/zhijuan/core/database/ZhijuanDatabase.kt`
- `core/database/src/main/kotlin/app/zhijuan/core/database/ZhijuanMigrations.kt`
- `core/database/src/main/kotlin/app/zhijuan/core/database/LibraryDatabaseGuards.kt`
- `core/database/src/main/kotlin/app/zhijuan/core/database/library/LibraryTypeConverters.kt`
- `core/database/src/androidTest/kotlin/app/zhijuan/core/database/ZhijuanMigrationTest.kt`
- 可新增 `core/database/src/androidTest/kotlin/app/zhijuan/core/database/PersistentBudgetPolicyDatabaseTest.kt`
- Room KSP 正常生成/更新的 `core/database/schemas/.../17.json`

不允许修改 docs/reports/task packet、GenerationDao、GenerationRequestAuditRepository、Usage结算、feature/app/provider、Gradle版本、原项目。

## 精确数据合同

### 1. core:model

- 新增 `BudgetReservationStatus { RESERVED, SETTLED, RELEASED }`。
- 新增 `BudgetDailyPeriodKeyV1`：输入非负 epochMillis 与受限 IANA zone id，唯一输出 `yyyy-MM-dd|ZoneId`；拒绝空/过长/非法 zone；字符串不泄露其他数据。不要使用系统默认时区。
- 可为 `BudgetScope`/status 增加 Room converter，但不要在 model 引入 Room。

### 2. `budget_policy_revision`

字段：

- `budget_policy_id` PK TEXT
- `scope`（只允许 BOOK/DAILY）
- `scope_key` TEXT
- `revision_no` INTEGER >0
- `parent_budget_policy_id` nullable self FK RESTRICT
- `book_id` nullable FK book RESTRICT
- `daily_zone_id` nullable TEXT
- `max_tokens` INTEGER >0
- `max_cost_micros` nullable INTEGER >0
- `currency` nullable 3位大写，与金额同现
- `policy_version` 固定 `zhijuan.budget-policy.v1`
- `created_at` non-negative

约束/索引：`UNIQUE(scope,scope_key,revision_no)`、`UNIQUE(parent_budget_policy_id)`、book/created索引；revision禁止UPDATE/DELETE。BOOK要求scopeKey==bookId且zone null；DAILY要求scopeKey固定`GLOBAL`、book null、zone非空；parent必须同scope/key/book/zone且revision连续。

### 3. `budget_policy_head`

- 复合PK `(scope,scope_key)`；`current_budget_policy_id` FK revision RESTRICT 且唯一；`updated_at` non-negative。
- insert/update trigger验证指向revision身份相同；scope/key不可更新，current revision只能沿合法直接子revision前进，时间不倒退；禁止DELETE。

### 4. `request_budget_reservation`

本阶段只建表和guards，不创建生产行。字段：

- `budget_reservation_id` PK
- `attempt_id` UNIQUE（暂不FK attempt，Phase3插入顺序为reservation先于attempt）
- `job_id`、`stage_id`（复合FK generation_stage RESTRICT）
- `book_id` FK book RESTRICT
- `status` BudgetReservationStatus
- `request_max_tokens`、nullable request max cost/currency
- `estimated_tokens`、nullable estimated cost/currency、nullable `estimate_source_version`
- `accounted_tokens`、nullable accounted cost/currency
- `book_policy_id`、`daily_policy_id` 两个FK revision RESTRICT
- `daily_period_key`
- `connection_id`、`normalized_destination`、`protocol_id`、`disclosure_version`、`disclosure_binding_hash`、`disclosure_accepted_at`
- nullable `settled_at`、nullable `released_at`、`created_at`、`updated_at`

索引至少覆盖：attempt unique；book+status+created；dailyPeriod+status+created；job/stage；两个policy id；status+updated。insert trigger验证身份非空、正数/非负、cost/currency同现、BOOK/DAILY policy身份与book匹配、RESERVED初始accounted==estimate、终态时间字段组合、binding hash格式。UPDATE只允许有限status/accounted/time变化，identity/estimate/policy/destination不可变；DELETE禁止。Phase2测试只需证明非法直接SQL被guard拒绝，不实现结算转换全部业务方法。

### 5. `request_attempt`

- 新增 `budget_enforcement_version INTEGER NOT NULL DEFAULT 0` 与 nullable `budget_reservation_id`，并建唯一索引。
- Kotlin entity默认仍为0/null，保证Phase2旧路径编译/运行；Phase3将新RequestIntent改为1+reservation。
- insert/update guard：version只允许0/1；v0必须reservationId null；v1必须reservationId非空且存在完全匹配attempt/job/stage的reservation；这两个身份字段不可更新。
- Provider-open拒绝v0属于Phase3，本阶段不要改。

### 6. Policy repository

公开最小方法：

- `activateBookPolicy(policyId, bookId, BudgetLimit, activatedAt)`
- `activateDailyPolicy(policyId, zoneId, BudgetLimit, activatedAt)`
- current read方法可按需要提供脱敏结果。

仓库在单Room事务中：验证identifier/time/zone/book；读取head；构造revisionNo=1或current+1和parent；同链daily zone不可变化；插入revision；初次插head或CAS推进head；并发/陈旧失败整笔回滚。不要自动创建默认预算、不要从Job JSON猜限制。返回对象toString不得展开金额、policy id、book id或zone。

## 不可破坏的约束

- schema迁移只创建结构和把旧attempt字段回填为0/null；不得伪造旧reservation或改Usage。
- `LibraryDatabaseGuards.install` 在onCreate/onOpen/migration都安装新guard，trigger命名稳定且可重复执行。
- 不修改当前RequestIntent/Provider-open，Phase2无行为开关。
- 所有Long精确、无Float/Double。
- 不读取/打印密钥，不调用真实/Fake Provider，不操作模拟器或物理设备。
- 只用 `apply_patch` 修改文本；Room/KSP生成schema可由构建产生。不得用WriteAllText/cat/Python写文件。

## 测试要求

至少覆盖：

1. daily key UTC/Asia-Shanghai边界、非法zone、确定性；
2. 初次BOOK/DAILY激活与current read；
3. 第二revision parent/revision/head正确；
4. daily换zone、book不存在、倒退时间、重复/分叉identity失败且head不变；
5. policy revision/head直接UPDATE/DELETE或错身份SQL被guard拒绝；
6. reservation非法初始状态/错误policy身份/篡改identity/DELETE被拒绝；
7. v16→v17保留旧Attempt/Usage，新增字段0/null，三表存在且旧Attempt无reservation；
8. migration registry仍连续。

## 验证命令

```powershell
$env:GRADLE_USER_HOME='D:\gptuser\cache\gradle'
.\gradlew.bat :core:model:test :core:database:test --offline --no-daemon --console=plain
```

```powershell
$env:GRADLE_USER_HOME='D:\gptuser\cache\gradle'
.\gradlew.bat :core:database:compileDebugKotlin :core:database:compileDebugAndroidTestKotlin --offline --no-daemon --console=plain
```

不得启动模拟器。未运行或失败必须如实回交。

## 回交格式

1. `完成内容`
2. `修改文件`
3. `验证`
4. `未完成/风险`
5. `需要 Sol 处理`
6. `假设`

不得宣布TASK-083完成，不得更新正式状态文档。
