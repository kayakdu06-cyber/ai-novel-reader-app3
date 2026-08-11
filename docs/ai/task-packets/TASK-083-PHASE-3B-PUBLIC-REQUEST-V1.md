# TASK-083 Phase 3B：公开 RequestIntent v1 reservation 接线

## 任务身份

- 任务 ID：`TASK-083 / Phase 3B public RequestIntent v1 wiring`
- 仓库根目录：`D:\gptuser\projects\ai-novel-reader-app2`
- 分支 / HEAD：`main` / `8ce774429da1c3f7139a221bc241c34d81a2efdd`
- 当前连续 WIP：246 条 `git status --short`，SHA-256=`d209738c3dfcde3cb278399cc347bcdb952901e0d25b2eb5276b002ef748e198`；全部属于用户持续开发，禁止 reset/clean/checkout/覆盖/整理
- 执行模型：DeepSeek V4 Flash，纯文本编码

## 运行预算

- reasoning：`max`
- 最长运行：40 分钟
- 累计 Token 上限：无
- 不运行模拟器，不调用真实/Fake Provider，不读取或打印任何密钥
- 若无法在时限内完成，保留边界内可审查改动并明确列出未完成项

## 目标

在已经双 API 验证的 Phase 3A 原子 reservation core 上，把 core:database 的唯一公开 RequestIntent/streaming prepare 路径切换为显式 v1 reservation。调用方必须提供预算 draft；公开入口不得再直接调用 legacy `GenerationDao.recordRequestIntent`。签发和 claim Provider permit 时必须绑定并重验 v1 Attempt 与精确 RESERVED reservation，v0/null Attempt 必须失败关闭。

本包只收敛 core:database 生产 API 和 core:database 自身测试。feature/app AndroidTest 的调用签名与预算 fixture 由 Sol 在回交后统一更新；不得为了让旧测试继续运行而保留可发送的 v0 overload/default/fallback。

## 必读资料

1. `AGENTS.md`
2. `docs/24-AI-DEVELOPMENT-PROTOCOL.md`
3. `docs/ai/CURRENT-CONTEXT.md` 第34～36节
4. `docs/ai/TASK-PACKET-TEMPLATE.md`
5. `reports/2026-08-09-127-task-083-phase-1-persistent-budget-design.md`
6. `reports/2026-08-09-128-task-083-phase-2-schema-policy-core.md`
7. `reports/2026-08-09-129-task-083-phase-3a-atomic-reservation-core.md`
8. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/PersistentBudgetReservationRepository.kt`
9. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationRequestAuditRepository.kt`
10. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationStreamingDraftRepository.kt`
11. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterDraftContinuationRepository.kt` 的 continuation prepare
12. `core/database/src/androidTest/kotlin/app/zhijuan/core/database/GenerationDatabaseTest.kt` 的 public RequestIntent 测试与 helper
13. `core/database/src/androidTest/kotlin/app/zhijuan/core/database/ChapterFinalCandidateCommitDatabaseTest.kt` 的 RequestIntent helper
14. `core/database/src/test/kotlin/app/zhijuan/core/database/generation/GenerationRequestAuditPolicyTest.kt`

不得递归读取全部报告或原项目。

## 允许修改

- `core/database/src/main/kotlin/app/zhijuan/core/database/generation/PersistentBudgetReservationRepository.kt`
- `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationRequestAuditRepository.kt`
- `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationStreamingDraftRepository.kt`
- `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterDraftContinuationRepository.kt`
- `core/database/src/androidTest/kotlin/app/zhijuan/core/database/GenerationDatabaseTest.kt`
- `core/database/src/androidTest/kotlin/app/zhijuan/core/database/ChapterFinalCandidateCommitDatabaseTest.kt`
- `core/database/src/test/kotlin/app/zhijuan/core/database/generation/GenerationRequestAuditPolicyTest.kt`
- 可新增一个仅供 core:database androidTest 使用的共享 fixture：`core/database/src/androidTest/kotlin/app/zhijuan/core/database/BudgetedRequestTestSupport.kt`

不得修改 docs/reports/task packet、schema/entity/migration/guards、BudgetDao、GenerationDao、connection DAO/entity、feature/app/provider、Gradle 或其他测试。

## 精确实现合同

### 1. 显式预算输入，不留 v0 fallback

- 将 `RequestBudgetReservationDraft` 调整为 feature/app 调用方可构造的公开脱敏合同；保持字段和 Phase 3A 校验，不得增加默认 connection、默认额度、默认价格或数据库自动 policy。
- `GenerationRequestAuditRepository.persistBeforeSend` 必须显式接收 `RequestBudgetReservationDraft`，调用 `PersistentBudgetReservationRepository.recordBudgetedRequestIntent`；禁止再直接调用 `GenerationDao.recordRequestIntent`。
- `GenerationStreamingDraftRepository.prepareBeforeSend`、内部 continuation prepare 和 `ChapterDraftContinuationRepository.prepareContinuationBeforeSend` 必须显式接收并原样传递 budget draft。
- 不得保留旧的两参数 streaming prepare / draft+lease audit overload，不得用可空 budget/default budget/测试开关保留 v0 发送旁路。
- legacy `GenerationDao.recordRequestIntent` 默认 v0/null只为旧本地恢复/迁移测试保留；它不能获得公开 send permit。

### 2. 公共 draft 不再接受 caller daily key

- 从公开 `RequestIntentDraft` 删除 `dailyPeriodKey`；RequestIntent policy不再验证调用方日键。
- 转内部 `NewRequestIntent` 时可使用固定、非敏感、永不落库的占位值；Phase 3A 必须在写入前用 policy zone+epoch覆盖它。不得从 snapshot JSON 猜日键。

### 3. permit 精确绑定 reservation

- `PersistedRequestSendPermit` 内部保存精确 reservation ID；`toString` 继续脱敏。claim 后的对象也必须保留该内部绑定，供 mark sent/stream started 的重复证据验证。
- `persistBeforeSend` 只在 v1 reservation/Attempt/UNKNOWN PROVISIONAL Usage/Stage 全部提交并回读后签发 permit。
- `validatePermitEvidence` 必须同时验证：Attempt是enforcement v1；Attempt reservation ID与permit完全相等；reservation存在且为RESERVED；reservation attempt/job/stage/book与Attempt/Usage一致；Usage仍PROVISIONAL且daily key与reservation相等。任一不符抛有限 stale 状态错误。
- `claimForProviderOpen` 必须因此永久拒绝v0/null Attempt、缺失/错误/非RESERVED reservation。不要在本包做实际profile/adapter目的地比较，那属于后续 Provider-open绑定阶段。
- markRequestSent/markStreamStarted仍要重复上述持久证据校验，不能只信任内存 claimed 对象。

### 4. core:database 测试 fixture

- 可新增共享 androidTest support：在测试已创建Book/Stage后，为该Book创建一次高但有限的BOOK policy、一次UTC DAILY policy和一个已接受disclosure的固定测试连接，并返回每Attempt唯一的 `RequestBudgetReservationDraft`。
- 测试额度使用有限Long（例如10亿scope、每请求100万、estimate 1），禁止Long.MAX_VALUE；无金额上限，不伪造价格。
- fixture只能存在于androidTest，不得进入main。不得删除或弱化现有测试断言。
- 更新GenerationDatabaseTest与ChapterFinalCandidateCommitDatabaseTest的公开prepare调用，所有公开产生的Attempt必须断言v1且有reservation；caller daily key相关断言改为UTC policy派生日键。
- direct `NewRequestIntent` 测试可继续v0/null，但不得走claim Provider-open。

### 5. 必增回归

至少覆盖：

1. 公开audit/stream prepare成功后Attempt为v1、reservation精确绑定、Usage daily key等于policy派生值，permit只能在完整提交后签发。
2. budget超限或ledger/Attempt后续冲突时reservation/Attempt/Usage/Stage四者零半状态；已经存在的其他reservation不被回滚。
3. 直接legacy v0 `recordRequestIntent` 即使构造内部permit，也在`claimForProviderOpen`联网前失败，Stage不进入REQUEST_SENT/STREAMING。
4. permit reservation身份不匹配或reservation非RESERVED时联网前失败；不得通过只改内存ID绕过。
5. secret-bearing snapshot仍在任何reservation写入前拒绝；测试artifact清理行为保持。
6. permit/draft/result/异常字符串不泄露reservation、attempt、connection、金额、币种、destination或hash。

## 验证命令

只允许主机core:database编译/JVM，不启动模拟器；feature/app因签名更新留给Sol，本包不修改它们：

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

不得宣布 TASK-083、Phase 3 或 Provider-open目的地绑定完成。
