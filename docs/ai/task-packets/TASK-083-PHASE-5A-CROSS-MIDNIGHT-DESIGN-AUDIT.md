# TASK-083 Phase 5A：Provider-open 跨午夜重预留设计审计

## 任务身份

- 任务 ID：`TASK-083 / Phase 5A cross-midnight design audit`
- 仓库根目录：`D:\gptuser\projects\ai-novel-reader-app2`
- 基准分支与 HEAD：`main` / `8ce774429da1c3f7139a221bc241c34d81a2efdd`
- 当前连续 WIP：257 条 `git status --short`，全部属于用户持续开发；禁止 reset/clean/checkout/覆盖/整理
- 执行模型：DeepSeek V4 Flash（纯文本只读架构审计）

## 运行预算

- 推理等级：`max`
- 最长运行时间：单次 45 分钟（项目启动器与协作规程允许的最高安全护栏）；用户允许长时间等待真实返回，若正常运行未结束由 Sol 自动续接同一窄审计，不把单次护栏到时判为设计失败
- 累计 Token 上限：无
- 不运行 Gradle、模拟器、网络、Provider 或写文件命令
- 提前停止条件：发现必须修改 schema/entity/migration/trigger 才能正确实现，或当前资料不足以证明可行事务

## 目标

只读审计 Provider-open 时 `validatedAt` 已进入 reservation 日键之后的新 DAILY period 的正确处理方式。必须保持：旧日未发送请求不消费旧日额度；新请求重新竞争新日额度；同书占用不因换日重置；没有 Provider 调用或半状态；一次性 permit、加密草稿、GenerationRequest、timing identity 与数据库最新 Attempt 全部一致。

本任务不写代码、不改文档、不宣称 Phase 5 或 TASK-083 完成。输出应给 Sol 一份可以直接形成实现任务包的具体设计，而不是泛泛建议。

## 已知不可破坏事实

- schema v17 每个 enforcement v1 Attempt 恰好一条不可删除 reservation；Attempt 的 reservation ID、reservation 的 attempt/daily/policy/destination identity 均不可修改。
- 旧 reservation 不能改日键。因此正确方案若继续自动发送，原则上必须建立新的 Attempt、Usage、reservation；不得原地篡改旧行。
- 旧 Attempt 尚为 `INTENT_RECORDED`，Stage 为 `REQUEST_INTENT_RECORDED`，Usage 为 UNKNOWN/PROVISIONAL，reservation 为 RESERVED；adapter 尚未打开。
- 旧 reservation 的释放不能走普通 `recordUsage`，否则 UNKNOWN FINAL 会结算为 SETTLED+estimate。
- Provider-open permit 是内存一次性对象；进程重启不能凭空重造发送许可。
- `GenerationStreamingDraftRepository` 当前要求一个加密草稿只被一个 Attempt 引用；续接实现也坚持每 Attempt 独立 artifact。
- `GenerationRequest.attemptId`、可选 timing context、ClaimedRequestSend 和数据库最新 Attempt 必须一致；不能数据库换 Attempt 而实际发送旧 Attempt 身份。
- Stage `maxAttempts` 不能被跨午夜无限绕过。旧未发送 Attempt 是否占用次数、替代状态与 retry parent 必须明确且可由状态机证明。
- 普通断网、超时、未知结果与跨午夜“尚未打开 Provider”语义不同；本阶段不能放宽未知结果自动重发。
- 不修改网络、代理、DNS、防火墙；不调用 App 内真实 Provider；不写物理设备。

## 必读资料

1. `AGENTS.md`
2. `docs/24-AI-DEVELOPMENT-PROTOCOL.md`
3. `docs/ai/CURRENT-CONTEXT.md` 第39节
4. `reports/2026-08-09-127-task-083-phase-1-persistent-budget-design.md` 第3、6节
5. `reports/2026-08-09-130-task-083-phase-3b-public-request-v1.md`
6. `reports/2026-08-09-132-task-083-phase-4b-confirmed-not-executed-release.md`
7. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationRequestAuditRepository.kt`
8. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/PersistentBudgetReservationRepository.kt`
9. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationStreamingDraftRepository.kt`
10. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationDao.kt` 的 intent/usage/reservation/lease 查询与事务
11. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationEntities.kt`
12. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/BudgetEntities.kt`
13. `core/database/src/main/kotlin/app/zhijuan/core/database/LibraryDatabaseGuards.kt` 相关 trigger（只读）
14. `core/task/src/main/kotlin/app/zhijuan/core/task/RequestAttemptStateMachine.kt`
15. `core/task/src/main/kotlin/app/zhijuan/core/task/GenerationStageStateMachine.kt`
16. `feature/generation/src/main/kotlin/app/zhijuan/feature/generation/AuditedStreamingProviderExecutor.kt`
17. `feature/generation/src/main/kotlin/app/zhijuan/feature/generation/GenerationTimingRecording.kt`
18. `provider/common/src/main/kotlin/app/zhijuan/provider/common/GenerationRequest.kt`
19. 直接相关 Android/JVM 测试；不得递归扫描整套文档、历史会话、备份或其他项目

## 必答问题

1. 正确的持久状态序列是什么？请逐步列出旧 Attempt/Usage/reservation、Stage/Job、替代 Attempt/Usage/reservation 的前后状态和时间约束。
2. 是否必须新增状态机事件？若必须，给出最窄事件与合法转换；若不必须，说明怎样不借用语义错误的 CANCELLED/PAUSE/UNKNOWN。
3. 新 Attempt/Usage/reservation ID 由谁生成？随机还是确定性？如何处理事务提交后进程崩溃、同一旧 permit 重入和两个并发 Provider-open claim？
4. 新 Attempt 是否消耗 `maxAttempts`？旧未发送 Attempt 是否算一次？不能通过午夜循环绕过上限。
5. 旧 UNKNOWN/PROVISIONAL Usage 应如何封账，旧 reservation 如何唯一 RELEASE；为何不会与 Phase4B Provider-proof release 混用？
6. 新 reservation 如何只重置 DAILY 而继续让 BOOK 聚合包含新占用，并如何在新日额度不足时保证旧状态、新候选和 Stage 全部一致？
7. 加密草稿应复制到新 artifact 还是允许共享？请结合现有唯一引用、崩溃清理和明文零中间文件约束回答。
8. 内存 `PersistedRequestSendPermit`、`PersistedStreamingRequest`、`ClaimedStreamingRequest`、Provider `GenerationRequest`、timing context 如何与替代 Attempt 对齐？是否应返回“重新准备”结果而不是在 claim 内自动替换？请比较两种方案对当前无 total runner 事实的影响。
9. 哪一层拥有整个操作：GenerationRequestAuditRepository、GenerationStreamingDraftRepository、AuditedStreamingProviderExecutor 或未来 total runner？给出最小允许修改文件清单。
10. 精确列出正向、额度拒绝、并发、崩溃/重入、旧permit、artifact复制失败、attempt上限、API30/35午夜边界和无Provider调用测试矩阵。

## 评审偏好

- 优先选择能在当前代码中原子证明且不会伪造 permit 的方案；如果“claim 内自动替换并直接发送”无法满足 artifact/GenerationRequest/timing/崩溃一致性，应明确否决并推荐两阶段重新准备结果。
- 用户希望操作尽量少，因此可以由未来/现有协调器自动消费“重新准备”结果，但不能为了表面自动化在数据库层伪造上层请求对象。
- 任何方案都必须说明当前 Phase 5 切片能否真正接到现有执行链；不能把只实现DAO原语写成端到端完成。

## 输出格式

1. `现有真实流程`
2. `严重缺口`
3. `推荐状态与事务设计`
4. `拒绝的替代方案及原因`
5. `最小文件范围`
6. `测试矩阵`
7. `需要 Sol 决策的剩余点`

不得修改任何文件，不得运行测试，不得把 TASK-083/Phase5 标记完成。
