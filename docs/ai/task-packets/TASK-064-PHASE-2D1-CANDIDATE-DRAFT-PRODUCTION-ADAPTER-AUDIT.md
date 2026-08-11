# TASK-064 Phase 2D1：candidate draft 生产 adapter 与身份链审计

## 任务身份

- 任务 ID：`TASK-064 / Phase 2D1 candidate draft production adapter audit`
- 仓库根目录：`D:\gptuser\projects\ai-novel-reader-app2`
- 基准分支与 HEAD：`main @ 8ce774429da1c3f7139a221bc241c34d81a2efdd`
- 当前未提交改动：约 215 条连续 WIP；不得 reset、clean、checkout、覆盖或整理任何改动
- 执行模型：DeepSeek（纯文本）

## 运行预算

- 推理等级：`max`
- 最长运行时间：30 分钟；理由：需要沿 candidate BODY 的 Stage 创建、请求、流式 Attempt、验证、seal、恢复与 cursor 七个边界核对，但本任务只读且限定为单一路线
- 累计 Token 上限：无
- 预计读取文件数与明确清单：基础 12 个文件；允许用限定 `rg` 追踪直接调用者，总数不超过 45
- 预计执行命令/测试数：10～25 个只读 `rg` / `Get-Content`；不构建、不测试、不修改
- 提前停止条件：需要访问其他项目、读取密钥/正文/个人数据、修改文件、读取超过 45 个文件，或仅靠当前源码无法证明结论

## 目标

只审计 `CANDIDATE_CHAPTER_DRAFT_V1` 的真实生产可接线性，回答四个核心问题：初始 BODY Stage 在哪里以什么冻结合同创建；如何从 Phase 2B exact-token 快照构造唯一请求并复用现有流式执行器；成功 STOP 后由谁验证并 seal 为 BODY artifact、创建 MEMORY successor；崩溃或 UNKNOWN 时如何确保不重复发送。

必须特别核对一个可能的合同矛盾：Phase 2A resolver 只把 `ChapterCandidateStageBindingV1` 的 BODY+DRAFT 识别为 candidate draft route，但 `ChapterFinalCandidateRecoveryRepository` 对 revisionIndex=0 的初始 DRAFT body 又要求 `inputSource == null`，`ChapterCandidateArtifactSealRepository.requireCurrentCandidateBinding` 对 DRAFT 也不解析 binding。判断这是测试专用 route、未完成的迁移、合法的双形态，还是 P0 设计缺口，并给出源码证据。

本次只输出事实审计和最小实现方案，不修改代码，不注册 route，不调用 Provider。

## 当前现场与已有 WIP

- 已存在：
  - Phase 2B `GenerationRunnerCurrentStageRouteSnapshot` 绑定 current `RUNNING + PREPARING` exact 双租约；
  - Phase 2C3 registry 只注册 `FINAL_CHAPTER_COMMIT_V3`，candidate draft 明确失败关闭；
  - `ChapterDraftStreamingCoordinator`、`AuditedStreamingProviderExecutor`、`GenerationStreamingDraftRepository`、`GenerationOutputValidationRepository` 已存在；
  - `ChapterCandidateArtifactSealRepositoryV1.seal` 可将一个已验证 candidate artifact 原子封存并创建下一 Stage；
  - 多个 Android 测试能手工走 candidate BODY→MEMORY，但尚未证明存在生产 executor adapter。
- 已存在的验证：generation JVM 131/131，API 30/API 35 generation 各 41/41，统一离线门禁 801 tasks 通过。
- 已知缺口：
  - registry 尚未注册 candidate draft；
  - production source 搜索尚未找到创建 bound initial DRAFT BODY Stage 的明确工厂；
  - `ChapterDraftStreamingCoordinator` 成功 STOP 只返回 `ReadyForValidation`，没有显式 BODY seal 调用；
  - adapter/profile/request 的生产装配与 exact-token resume 归属不明。
- 必须延续：现有请求审计、Attempt/Usage、protected streaming artifact、输出验证、candidate seal、租约、UNKNOWN 和 continuation 原语；不得从零复制第二套链路。

## 必读资料

1. `AGENTS.md`
2. `docs/24-AI-DEVELOPMENT-PROTOCOL.md`
3. `docs/ai/CURRENT-CONTEXT.md`（重点 21～25 节）
4. `docs/06-AI-GENERATION-SYSTEM.md`（重点 candidate pipeline、34～37 节）
5. `docs/07-API-ADAPTER-SPEC.md`（请求审计、stream、UNKNOWN）
6. `docs/08-TECHNICAL-ARCHITECTURE.md`（重点 31～34 节）
7. `docs/10-STATE-MACHINES.md`（重点 Attempt/Stage 与 37～40 节）
8. `docs/18-DECISION-LOG.md`（DEC-061～064）
9. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationRunnerStageRouteResolver.kt`
10. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationRunnerExecutionLeaseRepository.kt`
11. `feature/generation/src/main/kotlin/app/zhijuan/feature/generation/GenerationRunnerExecutorRegistry.kt`
12. `feature/generation/src/main/kotlin/app/zhijuan/feature/generation/ChapterDraftStreamingCoordinator.kt`
13. `feature/generation/src/main/kotlin/app/zhijuan/feature/generation/AuditedStreamingProviderExecutor.kt`
14. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationStreamingDraftRepository.kt`
15. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationRequestAuditRepository.kt`
16. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterCandidateArtifactSealRepository.kt`
17. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterFinalCandidateRecoveryRepository.kt`
18. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterDraftContinuationRepository.kt`
19. 直接相关测试：
    - `core/database/src/androidTest/kotlin/app/zhijuan/core/database/ChapterFinalCandidateCommitDatabaseTest.kt`
    - `feature/generation/src/androidTest/kotlin/app/zhijuan/feature/generation/AuditedStreamingProviderExecutorTest.kt`
    - `feature/generation/src/test/kotlin/app/zhijuan/feature/generation/ChapterDraftOutputContractTest.kt`
    - `core/database/src/test/kotlin/app/zhijuan/core/database/generation/ChapterCandidateStageBindingTest.kt`

允许用以下限定搜索追踪直接调用者：

```powershell
rg -n "ChapterCandidateStageBindingV1\.stageSetup|GenerationPhase\.DRAFT_CHAPTER|ChapterDraftStreamingCoordinator|ChapterCandidateArtifactSealRepositoryV1|ReadyForValidation|recordStructuredOutputValid" core/database/src feature/generation/src
```

不得递归扫描 reports、备份、缓存、历史会话或其他项目；需要扩展文件时在回交的“假设”中列出。

## 范围

允许修改：

- 无；使用 read-only / patch-proposal-only。

明确不在范围：

- 不新增或修改 Kotlin、Gradle、测试、文档、registry；
- 不接入 planning/context/普通 draft 或其他八条 remote route；
- 不调用真实或 Fake Provider，不运行 Gradle/模拟器；
- 不读取、显示或推测 API Key、host、正文和 prompt payload；
- 不访问 `D:\gptuser\projects\ai-novel-reader`。

## 不可破坏的约束

- 项目隔离：只读 app2，任务前后 status 必须一致。
- 状态机：不得从 `REQUEST_INTENT_RECORDED` 重新 route 并发送；必须区分 PREPARING 新请求、已有 Attempt 恢复、UNKNOWN 用户确认和 LENGTH continuation。
- 租约：registry adapter 必须消费 Phase 2B exact Stage token；同 owner 不等于同 token，不能重新 acquire 或换 token。
- 数据库：必须明确 Attempt/Usage、artifact、validation、seal、successor READY 和 Job cursor 的事务所有者；不能假设跨 repository 自动原子。
- 联网/费用：0 Provider 调用、0 费用；不建议通过真实调用“验证”。
- 隐私：回交不复制正文、prompt、JSON payload、connection profile、secret、业务 ID/hash。
- 兼容：不改 schema/migration/Gradle，不把 Android 测试 helper 冒充生产入口。

## 实施要求

1. 输出一条从“Stage 创建”到“BODY sealed、MEMORY READY”的生产调用链表；每步写明类/方法、输入、状态前后、是否网络、是否事务。
2. 对以下能力逐项标记“生产完整 / 仅底层原语 / 仅测试装配 / 不存在”：
   - 初始 bound BODY Stage 创建；
   - request/profile/adapter 生产装配；
   - exact-token PREPARING 入口；
   - RequestIntent/Attempt/Usage；
   - 流式草稿与 STOP/LENGTH/INVALID/拒绝/失败/取消；
   - 输出 validation；
   - BODY seal 与 MEMORY successor；
   - crash resume、UNKNOWN、continuation、防重复发送。
3. 专节解释初始 DRAFT BODY 的 input source 矛盾，引用相关方法名和文件路径；不能只给猜测。
4. 列出 registry 现在直接注册该 route 会产生的 P0/P1 风险，尤其重复付费请求、错误 Stage 身份、已成功未 seal、continuation 重入和 cursor 漂移。
5. 给出最小实现建议：建议新增哪些 adapter/输入类型和方法签名，哪些现有方法可复用，哪些必须收紧为 exact-token；限制在 1～4 个生产文件和必要测试，不写完整代码。
6. 明确下一步应该先修 route identity/Stage factory，还是先写 executor adapter；说明依赖顺序。

## 验收标准

- [ ] 生产入口与测试 helper 完全区分。
- [ ] 初始 BODY Stage 的两种合同处理有源码证据。
- [ ] Attempt、Provider、validation、seal、cursor、UNKNOWN 所有者明确。
- [ ] exact token 与防重复发送边界明确。
- [ ] 最小方案不新增第二套持久状态或通用 Provider fallback。
- [ ] 无文件改动、无构建、无 Provider 调用、无敏感内容输出。

## 验证命令

```powershell
git status --short
git diff --name-only
```

只用于确认任务前后没有新增修改。Gradle 与模拟器按任务范围不运行，必须如实写明。

## 回交格式

请严格按以下标题返回：

1. `完成内容`
   - 先给生产调用链表
   - 再给能力矩阵
   - 再给初始 BODY 合同结论、风险和最小实现顺序
2. `修改文件`：必须为“无”
3. `验证`
4. `未完成/风险`
5. `需要 Sol 处理`
6. `假设`

不要宣布 TASK-064、Phase 2D 或 candidate draft route 完成，不要修改正式状态。
