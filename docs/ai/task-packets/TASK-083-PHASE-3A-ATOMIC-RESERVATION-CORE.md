# TASK-083 Phase 3A：RequestIntent 原子 reservation 核心

## 任务身份

- 任务 ID：`TASK-083 / Phase 3A atomic reservation core`
- 仓库根目录：`D:\gptuser\projects\ai-novel-reader-app2`
- 分支 / HEAD：`main` / `8ce774429da1c3f7139a221bc241c34d81a2efdd`
- 当前连续 WIP：243 条 `git status --short`，SHA-256=`de0a51de1c3449c83ce8810f6ee1448c73e8360e7902674275fc5ca356a001d0`；禁止 reset/clean/checkout/覆盖/整理
- 执行模型：DeepSeek V4 Flash，纯文本编码

## 运行预算

- reasoning：`max`
- 最长运行：35 分钟
- 累计 Token 上限：无
- 不运行模拟器，不调用真实/Fake Provider，不读取或打印任何密钥
- 若无法在时限内完成，必须保留边界内可审查改动并诚实说明未完成项

## 目标

在 schema v17 Phase 2 WIP 上增加一个尚未接入公开 `GenerationRequestAuditRepository` 的内部原子核心：动态读取当前 BOOK/DAILY policy 与已接受 disclosure，派生 daily key，先插入候选 reservation 获取数据库写竞争，再聚合包含候选自身的 book/day accounted；通过后在同一 Room 外层事务调用既有 `GenerationDao.recordRequestIntent`，写 enforcement v1 Attempt + UNKNOWN/PROVISIONAL Usage + Stage。任何拒绝或后续失败必须整笔回滚。

本包不切换公开生产入口、不做 Usage 结算、不做 Provider-open、不做双 Room 并发，不宣称 TASK-083 完成。

## 必读资料

1. `AGENTS.md`
2. `docs/24-AI-DEVELOPMENT-PROTOCOL.md`
3. `docs/ai/CURRENT-CONTEXT.md` 第35节
4. `docs/ai/TASK-PACKET-TEMPLATE.md`
5. `reports/2026-08-09-127-task-083-phase-1-persistent-budget-design.md`
6. `reports/2026-08-09-128-task-083-phase-2-schema-policy-core.md`
7. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/BudgetDao.kt`
8. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/BudgetEntities.kt`
9. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/PersistentBudgetPolicyRepository.kt`
10. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationDao.kt` 的 `NewRequestIntent` 和 `recordRequestIntent`
11. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationRequestAuditRepository.kt` 只读，理解公开入口；不得修改
12. `core/database/src/main/kotlin/app/zhijuan/core/database/connection/ConnectionDao.kt` disclosure evidence
13. `core/database/src/androidTest/kotlin/app/zhijuan/core/database/PersistentBudgetPolicyDatabaseTest.kt`
14. `core/database/src/androidTest/kotlin/app/zhijuan/core/database/GenerationDatabaseTest.kt` 的 RequestIntent fixture/事务测试

不得递归读取全部报告或原项目。

## 允许修改

- `core/database/src/main/kotlin/app/zhijuan/core/database/generation/BudgetDao.kt`
- `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationDao.kt`
- 可新增 `core/database/src/main/kotlin/app/zhijuan/core/database/generation/PersistentBudgetReservationRepository.kt`
- 可新增 `core/database/src/androidTest/kotlin/app/zhijuan/core/database/PersistentBudgetReservationDatabaseTest.kt`

不得修改 docs/reports/task packet、schema/entity/migration/guards、`GenerationRequestAuditRepository`、connection DAO/entity、feature/app/provider、Gradle 或其他测试。

## 精确实现合同

### 1. 保持 legacy 兼容

- 给内部 `NewRequestIntent` 增加默认 `budgetEnforcementVersion=0` 与 `budgetReservationId=null`。
- `GenerationDao.recordRequestIntent` 构造 Attempt 时原样写这两项；既有直接调用仍保持 v0/null。
- 不在本包允许任何 Provider-open；公开入口切换属于后续 Phase 3B。

### 2. 新内部 draft 与有限错误

新增内部、脱敏的数据合同，至少包含：reservationId、request max tokens、可空 request max cost/currency、estimated tokens、可空 estimated cost/currency、estimate source version、connectionId。ID/时间/Long/币种配对必须验证；token 必须正；金额非负或按现有 reservation guard 要求；`toString` 不得展开 ID、金额、currency、destination、hash。

拒绝异常必须只暴露有限 `BudgetScope`/有限原因（至少 LIMIT_EXCEEDED、MONETARY_ESTIMATE_UNAVAILABLE、CURRENCY_MISMATCH、POLICY_UNAVAILABLE），message/toString 不得带 ID、数值、币种、zone、destination 或 hash。

### 3. 外层单 Room 事务顺序

新增内部 repository 方法，例如：

```kotlin
suspend fun recordBudgetedRequestIntent(
    intent: NewRequestIntent,
    budget: RequestBudgetReservationDraft,
    leaseToken: GenerationLeaseToken,
): ...
```

在一个 `database.withTransaction` 内严格执行：

1. 读取 Stage/Job/Book，只用于选择当前 policy 与构造候选；最终状态/lease/retry 校验仍由既有 `recordRequestIntent` 完成。
2. 读取当前 BOOK head/revision；缺失失败关闭，不创建默认值。
3. 读取当前 DAILY `GLOBAL` head/revision；缺失失败关闭；daily zone 必须受支持。
4. 通过 `connectionDao.readAcceptedDataDisclosureEvidence(connectionId)` 动态验证当前 disclosure；不要信任调用方 destination/protocol/hash。
5. policy/head 创建/更新时间不得晚于 `intent.createdAt`；disclosure acceptedAt 必须不晚于请求时间。
6. 由 daily policy zone 与 `intent.createdAt` 计算 canonical daily key；忽略/覆盖 caller 的 legacy daily key，不允许调用方选日期。
7. 构造 `RESERVED` candidate：accounted 精确等于 estimate，policy/destination/disclosure 全来自当前数据库证据。
8. **在读取任何余额聚合前先 INSERT candidate**；它必须是本事务的第一笔预算写入。
9. 聚合包含 candidate 在内的全部同 book、同 daily key、`status != RELEASED` reservation；不得按当前 policyId 过滤，换 revision 不能重置 token。
10. 校验 request/book/daily token hard limit。request 使用本 reservation 的 requestMax；book/daily 使用当前 policy limit。
11. 金额上限仅在相应 request/policy 配置时检查。若配置金额上限，则 candidate 和该 scope 全部非 RELEASED accounted cost 必须非空且 currency 与该 limit 完全相同；任何 null/不同币种均保守拒绝，禁止跨币种换算或只求匹配行之和。
12. 任一拒绝抛有限异常，让候选 INSERT 回滚。
13. 调用现有 `recordRequestIntent`，但传入 derived daily key、enforcement v1 和 reservationId；Attempt/Usage/Stage 任何失败也让 candidate 回滚。
14. 写后回读并校验 reservation/Attempt/Usage/Stage 完全存在且匹配，再返回脱敏结果。

所有金额/Token使用Long；禁止Float/Double。SQL SUM溢出或数据库竞争错误必须失败关闭，不可当作余额充足。

### 4. DAO 查询

`BudgetDao` 可新增：

- insert reservation；
- 按id/attempt回读；
- book聚合：全部非RELEASED，返回token总和、同币种cost总和、cost null数量、cost不同币种数量；
- daily key聚合同上。

聚合必须包含候选自身；不得维护平行counter row；不得按policy revision过滤。

### 5. 本包测试

新增真实 in-memory Room Android 测试，至少覆盖：

1. 正向：candidate/reservation + enforcement v1 Attempt + UNKNOWN/PROVISIONAL Usage + Stage在同一事务完成，daily key由epoch+zone派生，不采用caller key。
2. request token超限：reservation/Attempt/Usage为0，Stage仍PREPARING且attemptCount=0。
3. book token超限：同样零半状态。
4. daily token超限：同样零半状态。
5. policy配置金额但estimate为null或币种不符：保守拒绝且零半状态。
6. disclosure缺失/失效或后续Attempt写失败：candidate回滚。
7. policy head换revision后，聚合仍包含旧revision reservation的token。

本包不要求并发；Sol 后续补同 Room 与两个 Room 实例同文件竞争。

## 验证命令

只允许主机编译/JVM，不启动模拟器：

```powershell
$env:GRADLE_USER_HOME='D:\gptuser\cache\gradle'
$env:ANDROID_USER_HOME='D:\gptuser\cache\android'
.\gradlew.bat :core:database:test :core:database:compileDebugKotlin :core:database:compileDebugAndroidTestKotlin --offline --no-daemon --console=plain '-Dkotlin.compiler.execution.strategy=in-process'
```

## 回交格式

1. 完成内容
2. 修改文件
3. 验证
4. 未完成/风险
5. 需要 Sol 处理
6. 假设

不得宣布 Phase 3/TASK-083 完成。
