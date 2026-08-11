# TASK-064 Phase 2D3：context exact-token bound execution 与 registry 注册

## 任务身份

- 任务 ID：`TASK-064 / Phase 2D3 context exact-token bound execution`
- 仓库根目录：`D:\gptuser\projects\ai-novel-reader-app2`
- 基准分支与 HEAD：`main @ 8ce774429da1c3f7139a221bc241c34d81a2efdd`
- 当前未提交改动：约 220 条连续 WIP；不得 reset、clean、checkout、覆盖或整理无关改动
- 执行模型：DeepSeek（纯文本）

## 运行预算

- 推理等级：`max`
- 最长运行时间：30 分钟；理由：需要在既有 1,400 行 context repository 内做单事务重构，并同步有限 registry 与两层测试，但改动被限制为 5 个文件
- 累计 Token 上限：无
- 预计读取文件：任务包列出的 14 个文件，不得超过 24 个
- 预计命令：8～16 个只读命令，最多 3 个 Gradle JVM/编译命令；不运行 Android 模拟器
- 提前停止条件：需要改 schema/migration/DAO/Provider、需要跨越允许文件、发现无法保证单 Room 事务、权限阻塞或同因测试连续失败两次

## 目标

为已经完成 route identity 的 `CHAPTER_CONTEXT_ASSEMBLY_V1` 建立只消费 Phase 2B 数据库绑定快照的 exact-token 本地执行入口，并把它加入有限 registry。执行开始时必须在同一个 Room 事务重新验证 exact Job+Stage token、current cursor、状态、attempt、时间与租约，再复用现有 context assembly 业务逻辑；不得联网或创建 Attempt。

## 当前现场与已有 WIP

- `ChapterContextAssemblyRepository.assemble(stageId, stageToken, at)` 已完成本地上下文组装、BLOCKED、原子 snapshot/Stage/Job/cursor 提交和 SUCCEEDED replay，但活跃路径只验证 Stage exact token，Job 只验证同 owner。
- Phase 2B `GenerationRunnerCurrentStageRouteSnapshot` 携带 route、exact Job/Stage tokens、heartbeats 和 attempt bounds，构造器仅 database module 内部可见。
- Phase 2D2 已新增 `CHAPTER_CONTEXT_ASSEMBLY_V1` 严格 route identity；registry 当前显式 notRegistered，白名单只有 final commit。
- context Android 测试已有完整书/Bible/outline/memory fixture；registry Android 测试已有真实 Room leaseRoute helper。
- 最近验证：database JVM 86/86；双 API database 214/214；context 各5/5；generation 代码与 AndroidTest 编译通过。

## 必读资料

1. `AGENTS.md`
2. `docs/24-AI-DEVELOPMENT-PROTOCOL.md`
3. `docs/ai/CURRENT-CONTEXT.md`（重点 22～27）
4. `docs/06-AI-GENERATION-SYSTEM.md`（35、37、39）
5. `docs/08-TECHNICAL-ARCHITECTURE.md`（32、34、36）
6. `docs/10-STATE-MACHINES.md`（38、40、42）
7. `docs/18-DECISION-LOG.md`（DEC-062、064、066）
8. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationRunnerExecutionLeaseRepository.kt`
9. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterContextAssemblyRepository.kt`
10. `core/database/src/androidTest/kotlin/app/zhijuan/core/database/ChapterContextAssemblyDatabaseTest.kt`
11. `feature/generation/src/main/kotlin/app/zhijuan/feature/generation/GenerationRunnerExecutorRegistry.kt`
12. `feature/generation/src/test/kotlin/app/zhijuan/feature/generation/GenerationRunnerExecutorRegistryTest.kt`
13. `feature/generation/src/androidTest/kotlin/app/zhijuan/feature/generation/GenerationRunnerExecutorRegistryAndroidTest.kt`
14. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationStateRepository.kt`（只读，了解既有状态/租约入口）

除直接代码引用外，不读 reports、历史会话、备份、密钥、其他项目或无关模块。

## 范围

允许修改且仅允许修改：

1. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterContextAssemblyRepository.kt`
2. `core/database/src/androidTest/kotlin/app/zhijuan/core/database/ChapterContextAssemblyDatabaseTest.kt`
3. `feature/generation/src/main/kotlin/app/zhijuan/feature/generation/GenerationRunnerExecutorRegistry.kt`
4. `feature/generation/src/test/kotlin/app/zhijuan/feature/generation/GenerationRunnerExecutorRegistryTest.kt`
5. `feature/generation/src/androidTest/kotlin/app/zhijuan/feature/generation/GenerationRunnerExecutorRegistryAndroidTest.kt`

明确不在范围：DAO/entity/schema/migration/Gradle、route resolver/factory、Provider/Attempt/Usage、initial draft/chapter plan executor、total loop、docs/status/report。

## 不可破坏的约束

- 项目隔离：只操作 app2，保留全部约 220 条 WIP，不加 remote。
- 单事务：exact 双租约复核与 context snapshot/Stage/Job/cursor 业务写入必须属于同一个 `withTransaction`；禁止“先只读检查、事务结束、再调用旧 assemble 开新事务”的 TOCTOU 窗口。
- 入口身份：bound 入口只接受 `GenerationRunnerCurrentStageRouteSnapshot`，route 必须精确为 `CHAPTER_CONTEXT_ASSEMBLY_V1`；不得接受裸 route、ownerId 或仅 Stage token。
- 活跃执行：重新读取的 Job 必须 RUNNING、Stage 必须 PREPARING/current/same Job；Job/Stage persisted token 必须分别完整等于快照 token且同 owner；attemptCount/maxAttempts 必须与快照一致且仍有额度。
- 时间：operationAt 不倒退，两个 persisted heartbeat 不早于各自 acquiredAt，60 秒临界视为过期。
- durable replay：如果同一 context Stage 已 SUCCEEDED，可复用既有严格 replay 返回，不重复写 snapshot/推进 cursor；不得把 BLOCKED/其他状态当成功 replay。
- 兼容：旧 `assemble(stageId, stageToken, at)` 保持现有行为和测试；只能提取共享内部逻辑，不得删入口或放松旧合同。
- registry：注册集合变为严格 `{FINAL_CHAPTER_COMMIT_V3, CHAPTER_CONTEXT_ASSEMBLY_V1}`；其他 9 个 remote route仍显式 notRegistered，无 else/generic fallback。
- 安全：result/toString 不泄露 payload、hash、Job/Stage/owner、用户补充或连接信息。
- 联网费用：0 Provider、0 Attempt、0 Usage、0真实/Fake API。

## 实施要求

1. 在 repository 增加 `assembleBound(snapshot, assembledAt)`，并将既有 assemble 主体安全提取为单事务共用内部路径；bound active path 逐项重验上述 exact 事实。
2. bound path 的 BLOCKED 和 Ready 业务语义必须继续复用现有原子事务；不能复制另一套 assembly/commit 实现。
3. registry 增加一个有限可注入的 context bound executor 依赖，返回新的有限 result variant；该 executor 必须收到原始 snapshot 和 requestedAt。
4. 数据库 Android 测试至少覆盖：exact snapshot 正向提交；错误/陈旧 Job token或 Stage token；cursor/status/timeout 变化零业务写入；SUCCEEDED durable replay 不重复 snapshot。
5. registry JVM 测试更新白名单为两条，仍证明剩余 route 失败关闭；registry Android 测试用真实 Room 取得 context route snapshot，用有限 fake context executor 证明原 snapshot/time 原样传递且 final executor 未调用。
6. 不运行模拟器，由 Sol 回收后运行 API 30/API 35。

## 验收标准

- [ ] exact 双 token 与业务提交在一个 Room 事务内复核/执行。
- [ ] stale token、cursor/status/attempt/time/expiry 任一变化零 snapshot、零 Stage/Job业务写入。
- [ ] SUCCEEDED replay 不重复推进。
- [ ] registry 只注册 final+context，其他 remote route全部失败关闭。
- [ ] 旧 assemble 行为和现有测试保持。
- [ ] 0 Provider/Attempt/Usage/schema/migration。

## 验证命令

```powershell
.\gradlew.bat :core:database:compileDebugAndroidTestKotlin :feature:generation:testDebugUnitTest :feature:generation:compileDebugAndroidTestKotlin --no-daemon --offline --rerun-tasks
```

可选定向 JVM：

```powershell
.\gradlew.bat :feature:generation:testDebugUnitTest --tests '*GenerationRunnerExecutorRegistryTest' --no-daemon --offline --rerun-tasks
```

Android 双 API 与统一门禁由 Sol 执行。未运行必须明确写未运行。

## 回交格式

1. `完成内容`
2. `修改文件`
3. `验证`
4. `未完成/风险`
5. `需要 Sol 处理`
6. `假设`

不得宣布 TASK-064 或 Phase 2D 完成，不得更新正式状态文档。
