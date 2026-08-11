# TASK-059 第十阶段 B1：一致性最终映射快照 Codec

## 任务身份

- 任务 ID：`TASK-059 / Phase 10B1 / consistency mapping snapshot codec`
- 仓库：`D:\gptuser\projects\ai-novel-reader-app2`
- 基线：`main` / `8ce774429da1c3f7139a221bc241c34d81a2efdd`，保留全部未提交 WIP。
- 模型：DeepSeek V4 Flash，纯文本，只读补丁提案，`max` 推理。

## 运行预算

- 最长运行：15 分钟；总 Token 上限不设置；完成即停。
- 只读强制入口、本任务包和下面指定的 4 个小代码区段；不要读取 Android 大测试、最终提交仓库或其他文件。
- 不运行命令/测试，不写文件。
- 停止：需要修改第二个文件、需要移动现有类型、需要数据库/schema 变化或无法给出完整单文件补丁。

## 目标

只新增：

`feature/generation/src/main/kotlin/app/zhijuan/feature/generation/ChapterFinalConsistencyMappingSnapshot.kt`

建立一个严格、确定性、可往返的 JSON codec，冻结最终一致性 persistence mapper 在进程重启后必须恢复的最小输入：原请求 source binding、本地报告、expectation、scene contract，以及有限修订策略的三个数值。快照不得包含章节正文、人物名称、evidence payload、提示词、API 信息或模型输出正文。

本阶段只交付 codec，不接数据库、不改 final Stage、不实现执行器。

## 必读文件（只读精确范围）

1. `AGENTS.md`
2. `docs/24-AI-DEVELOPMENT-PROTOCOL.md`
3. `docs/ai/CURRENT-CONTEXT.md`
4. 本任务包
5. `ChapterConsistencyCheckRequest.kt` 第 150–305 行（Bound request 与 expectation 创建）
6. `ChapterConsistencyStructuredOutput.kt` 第 1–115 行（expectation/type）
7. `ChapterConsistencyPolicy.kt` 第 1–75 行（scene contract）
8. `ChapterLocalConsistencyChecker.kt` 第 1–32、150–185 行（local report/issue/range）

禁止读取日志、会话、密钥、原项目、大测试或完整大文件。下面已给足实现契约。

## 新类型与 API

建议（名称可等价但必须含 V1）：

```kotlin
data class ChapterFinalConsistencyMappingSnapshotV1(
    val consistencyRequestSourceBindingHash: String,
    val localReport: ChapterLocalConsistencyReport,
    val expectation: ChapterConsistencyExpectation,
    val sceneContract: ChapterSceneConsistencyContractV1,
    val minimumBodyCodePoints: Int,
    val totalRevisionAttemptsUsed: Int,
    val revisionStageMaximumAttempts: Int,
)

object ChapterFinalConsistencyMappingSnapshotCodecV1 {
    const val SCHEMA_ID = "zhijuan.chapter-final-consistency-mapping.v1"
    fun capture(
        boundRequest: BoundChapterConsistencyCheckRequest,
        spec: ChapterCandidateConsistencyRoutingSpecV1,
    ): String
    fun parseAndVerify(value: String): ChapterFinalConsistencyMappingSnapshotV1
    fun contentHash(value: String): String
}
```

`capture` 必须只从已经存在的 `boundRequest` 与 routing `spec` 复制：

- `boundRequest.sourceBindingHash/localReport/expectation/sceneContract`
- `spec.minimumBodyCodePoints/totalRevisionAttemptsUsed/revisionStageMaximumAttempts`

并先核对 expectation 的 version/hash/chapter/index/bodyCodePointCount 与 spec candidate/content 一致。不要接受调用方额外传入这些值。

`toString()` 只输出 chapter index、revision attempt 数、local issue 数等摘要；不输出 ID、hash、集合内容或 JSON。

## JSON 契约

根 exact keys：

```text
schemaVersion, schemaId, consistencyRequestSourceBindingHash,
minimumBodyCodePoints, totalRevisionAttemptsUsed, revisionStageMaximumAttempts,
localReport, expectation, sceneContract
```

- `schemaVersion` 固定 integer 1；`schemaId` 固定上面的字符串。
- 输出用 `JsonObject(linkedMapOf(...)).toString()`；集合必须固定排序，禁止依赖 Set 遍历顺序。
- 整体 UTF-8 最多 65,536 bytes；解析器 `Json { isLenient=false; ignoreUnknownKeys=false }`，根和三个嵌套 object 都 exact keys。
- 所有 hash 为小写 SHA-256；identifier 为 `[A-Za-z0-9._:-]{1,128}`。
- `contentHash(value)` 必须先 `parseAndVerify`，再对 canonical re-encode 的 UTF-8 做 SHA-256；非 canonical 输入可解析但 hash 必须基于 canonical 输出。可让 snapshot 保存 internal canonicalJson，或提供私有 encode(snapshot)。

### localReport exact keys

```text
checkerVersion, contentHash, bodyCodePointCount, bodyByteCount,
checkedCriteria, issues
```

- `checkerVersion` 必须等于 `ChapterLocalConsistencyCheckerV1.CHECKER_VERSION`。
- checkedCriteria 按 enum ordinal 严格递增、非空、无重复。
- issues 最大 64，顺序原样冻结；每项 exact keys：

```text
issueId, code, severity, criterion,
startCodePointInclusive, endCodePointExclusive, repairAction
```

- issue ID 合法；range `0 <= start < end <= max(1, bodyCodePointCount)`；枚举严格 valueOf。
- 报告 contentHash/body counts 合法；不要尝试重新检查正文，因为快照不含正文。

### expectation exact keys

```text
sourceChapterVersionId, sourceChapterContentHash, chapterId, chapterIndex,
checkSourceSnapshotHash, sceneContractHash, bodyCodePointCount,
expectedCriteria, knownEntityIds, knownForeshadowItemIds, requiredProcessNodeIds
```

- criteria 按 ordinal；三个 Set 编码为字典序 JSON array，解析后必须数组已排序、无重复，再构造 linkedSet。
- 继续使用现有 `ChapterConsistencyExpectation` constructor 的范围检查。

### sceneContract exact keys

```text
mode, intimacyDetailLevel, fadePolicy, requiredKeyProcessCoveragePercent,
fadeSubstitutionAllowed, requiresStateContinuity, requiresRelevantAftermath,
requiredProcessNodeIds, expectedCriteria, contractHash
```

- nullable integer/enum 必须是真正 JSON null 或正确类型，不接受字符串 `"null"`。
- process nodes 与 criteria 顺序严格，构造现有 `ChapterSceneConsistencyContractV1` 复核模式约束。

## 跨对象验证

无论 capture 还是 parse 后都必须验证：

1. request source binding 为小写 SHA-256；
2. local content hash/body count 等于 expectation；
3. expectation scene hash 等于 scene contract hash；
4. expectation expected criteria 等于 scene expected criteria；
5. expectation required process set 等于 scene required process list 的集合；
6. local checked criteria 至少包含 SOURCE_INTEGRITY/BASIC_READABILITY；
7. minimum body 1..1,000,000；total revision attempts ≥0；stage max 1..16；
8. 默认错误和 `toString` 不回显 JSON、hash、ID 集合或正文。

## 范围纪律

- 最终只返回一个新增文件的完整 `apply_patch` 块；不要调用 apply_patch。
- 不修改现有 Request/Mapper/Coordinator/数据库/测试/文档。
- 不添加第三方序列化依赖，不使用反射，不使用宽松 `@Serializable` 自动解码。
- 不调用 Provider、真实 API、网络或设备，不宣布 TASK-059 完成。

## Sol 后续验证

Sol 会应用审查后的补丁，自行补 JVM round-trip/unknown key/type/order/cross-object/tamper/redaction 测试，并运行统一离线门禁。

## 回交

按“完成内容 / 补丁提案 / 验证（未运行） / 未完成风险 / 需要 Sol 处理 / 假设”返回；补丁只能新增一个文件。
