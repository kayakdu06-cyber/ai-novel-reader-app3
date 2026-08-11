# TASK-059 第十阶段 A2：只生成恢复仓库

## 任务身份

- 任务 ID：`TASK-059 / Phase 10A2 / recovery repository only`
- 仓库：`D:\gptuser\projects\ai-novel-reader-app2`
- 基线：`main` / `8ce774429da1c3f7139a221bc241c34d81a2efdd`，保留全部未提交 WIP。
- 模型：DeepSeek V4 Flash，纯文本，只读补丁提案，`max` 推理。

## 为什么再次拆分

上一轮 `20260804-113133-75c55ea2` 因反复读取大文件在 25 分钟超时，没有最终补丁。不要重复该读取方式。本轮只输出一个新生产文件，不读取 Android 测试，不修改现有文件，不运行命令或测试。

## 运行预算

- 最长运行：15 分钟；总 Token 上限不设置；完成即停。
- 只读 4 份强制入口和下面 3 个精确代码片段；禁止整文件读取大测试、最终提交仓库、DAO 或 Entities。
- 停止：需要修改第二个文件、需要 schema/DAO 变化、无法独立完成一个新文件。

## 目标

只新增：

`core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterFinalCandidateRecoveryRepository.kt`

它提供数据库侧只读 `load(finalStageId)`，在一个 Room 事务内恢复并严格验证当前 final candidate 的 BODY→MEMORY→TRACKING→CONSISTENCY 四段证据，返回 final 来源、固定顺序 artifact evidence 和三份派生模型快照。它不是提交授权，最终提交仓库以后仍会独立复核。

## 必读内容（严格限制）

1. `AGENTS.md`
2. `docs/24-AI-DEVELOPMENT-PROTOCOL.md`
3. `docs/ai/CURRENT-CONTEXT.md`
4. 本任务包
5. `ChapterCandidateArtifactSealRepository.kt` 仅第 167–275 行（同包 internal sealed evidence/Parser）
6. `ChapterConsistencyOutcomeRepository.kt` 仅第 53–190 行（final source/binding）
7. `GenerationDao.kt` 仅第 50–125 行（只读查询签名）

禁止读取 `ChapterFinalCandidateCommitDatabaseTest.kt`、完整 `ChapterFinalCandidateCommitRepository.kt`、日志、会话、密钥、原项目或其他文件。需要的实体字段和枚举已经在下文给全，不要再读大文件。

## 已冻结可用 API

同包现有 API：

```kotlin
internal data class ChapterCandidateSealedStageEvidenceV1(
    val role: ChapterCandidateArtifactRoleV1,
    val stageId: String,
    val attemptId: String,
    val artifactRefId: String,
    val artifactRevision: Int,
    val rawOutputHash: String,
    val canonicalOutputHash: String,
    val sourceBindingHash: String,
    val candidateChapterVersionId: String,
    val candidateContentHash: String,
    val chapterId: String,
    val chapterIndex: Int,
    val revisionIndex: Int,
    val nextStageId: String,
    val routeBindingHash: String?,
) {
    fun toArtifactEvidence(): ChapterFinalCandidateArtifactEvidenceV1
}

internal object ChapterCandidateSealedStageEvidenceParserV1 {
    fun parseAndVerify(stage: GenerationStageEntity): ChapterCandidateSealedStageEvidenceV1
}

internal object ChapterCandidateStageBindingV1 {
    fun parseAndVerify(stage: GenerationStageEntity): ChapterCandidateStageSourceV1
}

internal object ChapterFinalCommitStageBindingV1 {
    fun parseAndVerify(stage: GenerationStageEntity): ChapterFinalCommitStageSourceV1
}
```

注意：实际 `ChapterCandidateStageBindingV1` / `ChapterFinalCommitStageBindingV1` 是 public object，但 `parseAndVerify` 是 internal，本文件同模块可调用。

所需 Entity 字段：

- Stage：`stageId, jobId, phase, targetType, targetId, status, maxAttempts, inputSourcesJson, outputReferenceJson, updatedAt`
- Job：`jobId, bookId, status, currentStageId`
- Attempt：`attemptId, jobId, stageId, status, standardErrorCode, inputHash, outputHash, streamDraftRef, modelSnapshotJson`
- Usage：`attemptId, bookId, status`
- DAO：`findStage, findJob, findAttempt, findUsageForAttempt, attemptsForStage`
- `ZhijuanDatabase.withTransaction {}` 可用。

枚举：

- final 可恢复 Stage：`READY, PREPARING, COMMITTING`；已完成审计可接受 `SUCCEEDED`。其他 Stage 状态拒绝。
- 未完成 final 对应 Job：`RUNNING, PAUSING` 且 `currentStageId == finalStageId`。
- `SUCCEEDED` final 对应 Job：`COMPLETED` 且 `currentStageId == finalStageId`。
- 四段候选 Stage 必须 `SUCCEEDED`；Attempt 必须 `SUCCEEDED`；Usage 必须 `FINAL`。

## 返回类型

在同一新文件中定义脱敏不可变结果，字段至少为：

```kotlin
val finalStageId: String
val jobId: String
val bookId: String
val finalStageStatus: GenerationStageStatus
val finalStageUpdatedAt: Long
val source: ChapterFinalCommitStageSourceV1
val artifacts: List<ChapterFinalCandidateArtifactEvidenceV1> // 固定 role ordinal 顺序
val memoryModelSnapshotJson: String
val trackingModelSnapshotJson: String
val consistencyModelSnapshotJson: String
```

`toString()` 只允许状态、chapterIndex、revisionIndex、artifact 数量等非敏感摘要；不得包含 ID、JSON、hash、history 或 artifact ref。

## 必须验证

1. `finalStageId` 符合 `[A-Za-z0-9._:-]{1,128}`；final Stage 是 `COMMIT_CHAPTER`、`CHAPTER`、maxAttempts=1，使用 v2 binding 且 target 与 source chapter 一致。
2. final/Job 状态组合严格符合上文；不得获取租约或修改状态。
3. 从 final source predecessor 反向读取 CONSISTENCY，再通过每个派生 Stage 的 `ChapterCandidateStageBindingV1.predecessorStageId` 依次得到 TRACKING、MEMORY、BODY；不得按 phase 搜索或猜测。
4. 四段 Stage 同 Job、同 chapter、SUCCEEDED，sealed evidence role 与期望相同，candidate version/hash/chapter/index/revision 与 final source完全一致。
5. sealed `nextStageId` 严格形成 BODY→MEMORY→TRACKING→CONSISTENCY→FINAL。
6. MEMORY/TRACKING/CONSISTENCY 的 input source：role 等于自身输出 role，predecessor 等于直接前驱，候选身份完全相同，route binding 等于直接前驱 sealed output route。
7. CONSISTENCY sealed output route 等于 final source route。不要要求 BODY/MEMORY/TRACKING route 等于本次 ACCEPT route。
8. BODY：revision 0 必须是 `DRAFT_CHAPTER`；revision > 0 必须是 `REVISE_CHAPTER`，且其 input source role 为 BODY、revision 为 final revision-1、chapter 相同、previous content hash 等于 final history 的倒数第二项、current/previous candidate version 与 hash 不相同、route/request binding 非空。
9. 每段 Attempt 同 Job/Stage、SUCCEEDED、无标准错误、是 `attemptsForStage(stageId).lastOrNull()`；input/output/artifact ref 与 evidence 一致。
10. 每个 Usage 同 Book 且 FINAL。
11. MEMORY/TRACKING/CONSISTENCY `modelSnapshotJson` 各自非空、长度 ≤65,536、由严格 `Json` 解析为 `JsonObject`。不要规范化或重写字符串。
12. 任何失败抛 `StaleGenerationStateException` 或统一 `IllegalArgumentException`；消息不得回显正文、JSON、hash、history、artifact ref、model snapshot。

## 范围和输出纪律

- 最终只返回一个包含该新文件的完整 `apply_patch` 块。
- 不调用 apply_patch，不写文件，不读测试，不运行 Gradle、网络、Provider 或设备。
- 不新增 DAO/schema，不复制最终提交事务，不返回 Entity/ByteArray。
- 不更新文档，不宣布 TASK-059 完成。

## 回交

按“完成内容 / 补丁提案 / 验证（未运行） / 未完成风险 / 需要 Sol 处理 / 假设”返回；补丁只能新增一个文件。
