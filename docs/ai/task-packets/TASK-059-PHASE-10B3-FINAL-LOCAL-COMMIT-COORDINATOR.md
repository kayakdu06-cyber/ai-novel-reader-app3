# TASK-059 第十阶段 B3：唯一的本地最终提交协调器

## 任务身份

- 任务 ID：`TASK-059 / Phase 10B3 / final local commit coordinator`
- 仓库根目录：`D:\gptuser\projects\ai-novel-reader-app2`
- 基准分支与 HEAD：`main` / `8ce774429da1c3f7139a221bc241c34d81a2efdd`；保留全部未提交 WIP。
- 执行模型：DeepSeek V4 Flash，纯文本，允许在严格单文件范围内写代码。

## 运行预算

- 推理等级：`max`。
- 最长运行时间：20 分钟。理由：需要审计 9 个既有契约并完成一次 Kotlin 编译，用户已允许适当延长。
- 累计 Token 上限：不设置（用户明确要求不设总量上限；本任务完成即停）。
- 预计读取：强制入口、本任务包和下列 9 个生产文件；不要读取 Android 大测试、报告或其他文档。
- 预计命令：一次 `git status --short`、必要的定点搜索、最多一次 `:feature:generation:compileDebugKotlin --offline`。
- 提前停止：需要修改第二个生产文件、需要 Room schema/DAO/状态机改动、遇到权限阻塞、或发现本任务契约无法用既有 API 实现。

## 目标

新增一个生产协调器，把 final Stage v3 的数据库恢复快照、四类受保护 artifact、三套既有 persistence mapper 和最终原子提交仓库接成唯一的本地提交入口。它不得联网、不得调用模型、不得复制数据库事务，也不得绕过既有有限修订策略。

## 当前现场与已有 WIP

- `ChapterFinalCandidateRecoveryRepository.load` 已在单个只读事务中恢复并复核 final Stage、Job、章节、BODY→MEMORY→TRACKING→CONSISTENCY 链、Attempt、FINAL Usage、artifact evidence 和三份模型快照。
- 返回值新增 `candidateRouteBindingHash: String?`：这是产生当前候选正文时的 route binding；它不同于 `source.routeBindingHash`（接受当前候选的最终一致性策略 binding）。revision 0 必须为 null，revision 1+ 必须非空。
- `ChapterFinalCandidateArtifactRecoveryCoordinator.recover` 只通过受保护 artifact lease 恢复正文和三类严格解析模型，不保留 ByteArray。
- final Stage source 已是 v3，含 canonical 一致性映射快照、快照 hash、原一致性请求 binding、完整候选 history、expected current version、最大修订数、CONSISTENCY 前驱和最终策略 route binding。
- 三套 persistence mapper 与 `ChapterFinalCandidateCommitDraftMapperV1` 已完成且必须复用。
- `ChapterFinalCandidateCommitRepositoryV1.commit` 是唯一正式发布事务；它会再次复核 artifact、租约、来源、当前版本、外键、并发和 replay。
- `GenerationStateRepository.transitionStage(... PREPARING, LOCAL_OUTPUT_READY, ...)` 是唯一 `PREPARING → COMMITTING` 入口。

## 必读资料

1. `AGENTS.md`
2. `docs/24-AI-DEVELOPMENT-PROTOCOL.md`
3. `docs/ai/CURRENT-CONTEXT.md`
4. 本任务包
5. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterFinalCandidateRecoveryRepository.kt`
6. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterFinalCandidateCommitRepository.kt`（只读公开 draft/result/repository API、commit 状态和 lease 语义，不重写事务）
7. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationStateRepository.kt`
8. `feature/generation/src/main/kotlin/app/zhijuan/feature/generation/ChapterFinalCandidateArtifactRecoveryCoordinator.kt`
9. `feature/generation/src/main/kotlin/app/zhijuan/feature/generation/ChapterFinalConsistencyMappingSnapshot.kt`
10. `feature/generation/src/main/kotlin/app/zhijuan/feature/generation/ChapterFinalCandidateCommitDraftMapper.kt`
11. 三个 mapper：`ChapterMemoryExtractionPersistenceMapper.kt`、`ChapterTrackingProjectionPersistenceMapper.kt`、`ChapterConsistencyPersistenceMapper.kt`
12. `feature/generation/src/main/kotlin/app/zhijuan/feature/generation/ChapterRevisionRequest.kt` 中公开的 `ChapterRevisionRequestFactoryV1.issuesFrom`
13. `core/task/src/main/kotlin/app/zhijuan/core/task/ChapterRevisionPolicy.kt`

除上述清单和它们直接需要的类型定义外，不得递归扫描整仓、日志、历史会话、密钥、原项目或 Android 大测试。

## 范围

允许新增且只允许修改：

- `feature/generation/src/main/kotlin/app/zhijuan/feature/generation/ChapterFinalCandidateCommitCoordinator.kt`

明确不在范围：

- 任何现有文件、测试、文档、Room schema/DAO、Provider、网络、UI、原项目。

## 不可破坏的约束

- 只操作 app2；保留全部已有未提交改动。
- 协调器是纯本地路径：不得建立网络请求、Attempt、Usage 或模型调用。
- 不重新实现 artifact 解密、严格 parser、persistence mapper、状态机或最终 SQLCipher 事务。
- 不把正文、结构化 JSON、名称、hash、ID 或模型快照写入错误消息、日志、`toString`。
- 任何快照、候选、策略、Stage 状态或 mapping 失败时必须在 `PREPARING → COMMITTING` 前失败；已经是 `COMMITTING` 时保留可恢复状态并向上抛错。
- `READY` 尚未持有提交租约，`SUCCEEDED` 已应由上层观察为完成；这两个状态都必须在读取 artifact 前拒绝，不由本协调器猜测获取租约或重放已清理 artifact。
- 不捕获并吞掉异常；不清理 artifact；最终事务仍是最后一道独立门禁。

## 实施要求

1. 新增 `ChapterFinalCandidateCommitCoordinatorV1`。构造函数注入以下四个既有生产对象：
   - `ChapterFinalCandidateRecoveryRepository`
   - `ChapterFinalCandidateArtifactRecoveryCoordinator`
   - `GenerationStateRepository`
   - `ChapterFinalCandidateCommitRepositoryV1`
2. 暴露：

```kotlin
suspend fun commit(
    finalStageId: String,
    leaseToken: GenerationLeaseToken,
    requestedAt: Long,
): ChapterFinalCandidateCommitResultV1
```

3. 先 `load(finalStageId)`，确认返回 ID 一致、`requestedAt >= finalStageUpdatedAt`。只接受 `PREPARING` 或 `COMMITTING`；其他状态在 artifact 恢复前失败。
4. 严格调用 `ChapterFinalConsistencyMappingSnapshotCodecV1.parseAndVerify`，并调用 `contentHash` 对回 `source.consistencyMappingSnapshotContentHash`；快照 request binding 对回 source。
5. 把快照 expectation 与 final source 的候选版本、正文 hash、章节 ID/index 逐项对回。不得只相信 inner snapshot 或只相信 outer source。
6. 然后调用 artifact recovery。实际正文的 Unicode 码点数和 UTF-8 字节数必须分别等于快照中的 body 计数；错误不得回显正文。
7. 构造 `ChapterCandidatePipelineIdentityV1`，其中 `routeBindingHash = recovered.candidateRouteBindingHash`；额外要求 revision 0 对应 null，revision 1+ 对应非空。
8. 从四类 evidence 中按 role 取得 MEMORY/TRACKING/CONSISTENCY Stage ID；必须每类恰好一个。使用恢复出的对应模型快照和同一个 `mappingTime` 调用三套 mapper：
   - 若初始状态是 `PREPARING`，`mappingTime = requestedAt`；
   - 若初始状态是 `COMMITTING`，`mappingTime = finalStageUpdatedAt`，以便崩溃恢复重建完全相同的派生行。
9. 用 consistency mapper 的 gate 精确重建 `ChapterRevisionPolicyInputV1`：正文 hash/history、快照正文数与最小数、`completedAutomaticRevisions = source.revisionIndex`、快照总修订尝试数/最大尝试数、场景契约、`ChapterRevisionRequestFactoryV1.issuesFrom(gate)`。
10. 重新调用 `ChapterRevisionPolicyV1.evaluate`。只接受 `AcceptCandidate`，并要求 candidate hash、maximumAutomaticRevisions 与 source 相等；`routingBindingHash(input)` 必须等于 `source.routeBindingHash`。这是防止恢复时绕过原有限策略的硬门禁。
11. 只调用 `ChapterFinalCandidateCommitDraftMapperV1.map` 组装最终 draft。artifact 列表使用恢复仓库返回的原列表；expected current、history、上限全部来自 final source。
12. 所有解析、恢复、映射和策略校验完成后：
   - 初始状态 `PREPARING`：通过 `GenerationStateRepository.transitionStage` 以当前 lease 和 `mappingTime` 执行 `LOCAL_OUTPUT_READY`，并确认结果确为 `COMMITTING`；
   - 初始状态 `COMMITTING`：不重复转换。
13. 最后调用且只调用 `ChapterFinalCandidateCommitRepositoryV1.commit(finalStageId, leaseToken, draft)`，直接返回其结果。
14. 可以新增私有的脱敏 helper（例如 UTF-8 长度计算）；不得新增平行公开模型或第二套策略。

## 验收标准

- [ ] 只新增授权的一个 Kotlin 文件。
- [ ] PREPARING 在全部本地验证后才进入 COMMITTING；COMMITTING 可确定性恢复。
- [ ] v3 snapshot、实际 artifact、candidate identity、三套 mapper、有限策略和最终事务全部串联。
- [ ] READY/SUCCEEDED 不读 artifact；不联网、不创建 Attempt/Usage、不泄露内容。
- [ ] 修订候选使用 candidate route binding，最终接受复核使用 final route binding，二者不混用。
- [ ] Kotlin 编译通过，或准确报告唯一编译阻塞。

## 验证命令

```powershell
.\gradlew.bat :feature:generation:compileDebugKotlin --offline --no-daemon --console=plain
```

不运行 Android 测试和统一门禁；由 Sol 在审查与补测试后执行。

## 回交格式

请严格按以下标题返回：

1. `完成内容`
2. `修改文件`
3. `验证`
4. `未完成/风险`
5. `需要 Sol 处理`
6. `假设`

不要宣布完整 TASK-059 完成，不要更新正式状态文档。
