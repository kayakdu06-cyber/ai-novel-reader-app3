# TASK-083 Phase 3B2：feature 生成测试迁移到预算化 RequestIntent

## 任务身份

- 任务 ID：`TASK-083 / Phase 3B2 feature AndroidTest migration`
- 仓库根目录：`D:\gptuser\projects\ai-novel-reader-app2`
- 基准分支与 HEAD：`main` / `8ce774429da1c3f7139a221bc241c34d81a2efdd`
- 当前未提交改动：250 条 `git status --short`，SHA-256=`2a674eab4f39fc61a85d7641aa8ac32e251d9e08145bce81f7d46d0f41a230ed`；全部属于用户的连续开发 WIP，禁止 reset/clean/checkout/覆盖/整理
- 执行模型：DeepSeek V4 Flash（纯文本）

## 运行预算

- 推理等级：`max`
- 最长运行时间：25 分钟；本任务只迁移五个 feature AndroidTest 文件和一个共享夹具，不扩大到 app 或生产 runner
- 累计 Token 上限：无
- 预计读取文件数：根规程 3 个、Phase 3B 任务包 1 个、生产预算/请求 API 4 个、core 测试夹具 1 个、feature 测试 5 个，共约 14 个
- 预计执行命令：一次 feature AndroidTest Kotlin 编译；不得启动模拟器
- 提前停止条件：需要改 schema/migration/DAO/entity、需要改 app、权限阻塞、需要真实 Provider、或同一编译错误重复两次

## 目标

把 `feature:generation` 中仍调用旧 RequestIntent 签名的五个 Android 端到端测试迁移到 Phase 3B 的显式 v1 budget reservation。新增一个 feature androidTest 共享预算夹具，使每个测试数据库在 Book 建立后拥有有限 BOOK/UTC DAILY policy 和与该测试 Provider profile 同 ID、同 protocol、同 canonical destination 的已确认测试连接；所有 prepare 调用显式传入每 Attempt 唯一的 budget draft。

## 当前现场与已有 WIP

- Phase 3A 已实现 `PersistentBudgetReservationRepository.recordBudgetedRequestIntent` 的单事务候选 reservation、三层聚合和四表回滚。
- Phase 3B 已把公开 `RequestIntentDraft` 删除 `dailyPeriodKey`，并让 streaming/continuation prepare 必须显式接收 `RequestBudgetReservationDraft`。
- core 已通过 JVM/编译，`GenerationDatabaseTest` 已在 API 30/API 35 各 77/77。
- 当前 `:feature:generation:compileDebugAndroidTestKotlin` 只因五个测试仍保留 caller daily key 和缺少 budget 参数而失败。
- 必须延续当前接口和测试逻辑，不得恢复旧 overload/default/fallback，不得从零重写测试。

## 必读资料

1. `AGENTS.md`
2. `docs/24-AI-DEVELOPMENT-PROTOCOL.md`
3. `docs/ai/CURRENT-CONTEXT.md` 第 34～36 节
4. `docs/ai/task-packets/TASK-083-PHASE-3B-PUBLIC-REQUEST-V1.md`
5. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/PersistentBudgetPolicyRepository.kt`
6. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/PersistentBudgetReservationRepository.kt`
7. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationRequestAuditRepository.kt`
8. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationStreamingDraftRepository.kt`
9. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterDraftContinuationRepository.kt`
10. `core/database/src/androidTest/kotlin/app/zhijuan/core/database/BudgetedRequestTestSupport.kt`
11. 下列五个允许修改的 feature AndroidTest 文件

除上述清单和代码直接引用外，不得递归扫描整套文档、历史会话、报告、备份或其他项目。

## 范围

允许修改：

- `core/database/src/main/kotlin/app/zhijuan/core/database/generation/PersistentBudgetPolicyRepository.kt`
- 新增 `feature/generation/src/androidTest/kotlin/app/zhijuan/feature/generation/BudgetedGenerationTestSupport.kt`
- `feature/generation/src/androidTest/kotlin/app/zhijuan/feature/generation/AuditedStreamingProviderExecutorTest.kt`
- `feature/generation/src/androidTest/kotlin/app/zhijuan/feature/generation/InitialPlanningEndToEndTest.kt`
- `feature/generation/src/androidTest/kotlin/app/zhijuan/feature/generation/ChapterMemoryExtractionEndToEndTest.kt`
- `feature/generation/src/androidTest/kotlin/app/zhijuan/feature/generation/ChapterTrackingProjectionEndToEndTest.kt`
- `feature/generation/src/androidTest/kotlin/app/zhijuan/feature/generation/ChapterConsistencyCheckEndToEndTest.kt`

明确不在范围：

- app AndroidTest、正式 runner、Provider adapter、schema/entity/migration/DAO、预算 reservation 核心、Usage 结算、目的地运行时匹配、docs/reports/status
- 真实或 Fake Provider 调用；本任务只允许编译，不运行模拟器

## 不可破坏的约束

- 项目隔离：不得访问或修改 `D:\gptuser\projects\ai-novel-reader` 或其他目录。
- `PersistentBudgetPolicyRepository`、`BudgetPolicyActivation`、`CurrentBudgetPolicy` 可从 `internal` 调整为公开类型，构造结果的 constructor 可继续 `internal`；不得改变激活、CAS、校验、toString 或持久化语义。
- 共享夹具只存在于 `feature/generation/src/androidTest`，不得进入 main。
- 夹具必须使用有限 Long：BOOK/DAILY 例如 1,000,000,000 token，请求上限例如 1,000,000，estimate 1；禁止 `Long.MAX_VALUE`，禁止伪造价格或金额上限。
- 测试连接 base URL 为 `https://example.invalid`，normalized destination 为 `https://example.invalid:443`，protocol 为 `OPENAI_CHAT_COMPAT`；每个测试必须使用其现有 profile 的 connection ID（`connection-1`、`connection.fixture`、`connection.memory`、`connection.tracking`、`connection.consistency`），避免为后续 profile/destination 绑定埋下不一致。
- 每个 Request Attempt 的 reservation ID 必须唯一且由 attempt ID 确定性派生；`RequestIntentDraft` 不得恢复 `dailyPeriodKey`。
- Book 创建后、第一次 prepare 前激活 policy/插入连接/接受 disclosure。`ChapterConsistencyCheckEndToEndTest.setUp` 如需 suspend，改为 `runBlocking`，不使用 `runBlocking` 嵌套。
- 不删除、放松或跳过现有断言；不增加旧签名、默认 budget、nullable budget、测试开关或 v0 发送旁路。
- 不读取、打印、写入任何 API Key；不调用真实 API，不修改网络、代理、DNS 或凭据配置。

## 实施要求

1. 只为跨模块使用把 policy repository 及其公开返回类型调整到必要的 public 可见性，保持实现不变。
2. 新增 `BudgetedGenerationTestSupport`：接收 database、bookId、connectionId；激活一次有限 BOOK policy、一次 UTC DAILY policy；插入并确认与现有 profile 一致的 `.invalid` 测试连接；提供按 attemptId+connectionId 构造 `RequestBudgetReservationDraft` 的函数。
3. 五个测试都在各自 Book seed 后调用一次环境 seed；所有旧 public `RequestIntentDraft.dailyPeriodKey` 删除；所有 streaming/continuation prepare 显式传递正确 attempt/connection 的 budget。
4. 同一测试内的多个请求只复用已激活 policy/连接，不重复激活 policy；每个 attempt 使用独立 reservation ID。
5. 编译通过后搜索五个测试，证明不存在 public `dailyPeriodKey` 残留和缺 budget 的 prepare 调用。

## 验收标准

- [ ] `:feature:generation:compileDebugAndroidTestKotlin` 成功。
- [ ] 五个测试的现有 Provider profile connection ID 与 budget connection ID 一致。
- [ ] 所有公开 prepare/continuation prepare 都显式携带 budget，且没有旧 daily key。
- [ ] 无 production 语义变化，除 policy repository/result 的必要可见性。
- [ ] 无真实 Provider、无模拟器、无 schema 或 app 改动。

## 验证命令

```powershell
$env:GRADLE_USER_HOME='D:\gptuser\cache\gradle'
$env:ANDROID_USER_HOME='D:\gptuser\cache\android'
.\gradlew.bat :feature:generation:compileDebugAndroidTestKotlin --offline --no-daemon --console=plain '-Dkotlin.compiler.execution.strategy=in-process'
```

```powershell
rg -n "dailyPeriodKey|prepareBeforeSend\(|prepareContinuationBeforeSend\(" feature/generation/src/androidTest/kotlin/app/zhijuan/feature/generation/AuditedStreamingProviderExecutorTest.kt feature/generation/src/androidTest/kotlin/app/zhijuan/feature/generation/InitialPlanningEndToEndTest.kt feature/generation/src/androidTest/kotlin/app/zhijuan/feature/generation/ChapterMemoryExtractionEndToEndTest.kt feature/generation/src/androidTest/kotlin/app/zhijuan/feature/generation/ChapterTrackingProjectionEndToEndTest.kt feature/generation/src/androidTest/kotlin/app/zhijuan/feature/generation/ChapterConsistencyCheckEndToEndTest.kt
```

未运行的验证必须在回交中写明原因，不能写成通过。

## 回交格式

请严格按以下标题返回：

1. `完成内容`
2. `修改文件`
3. `验证`
4. `未完成/风险`
5. `需要 Sol 处理`
6. `假设`

不要宣布整个 TASK-083 或 Phase 3B 完成，不要更新正式状态；由 Sol 根据差异和模拟器证据确认。
