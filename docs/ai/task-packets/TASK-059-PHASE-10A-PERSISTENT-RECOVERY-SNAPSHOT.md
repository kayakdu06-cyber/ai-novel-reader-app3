# TASK-059 第十阶段 A：最终候选持久恢复快照

## 任务身份

- 任务 ID：`TASK-059 / Phase 10A / persistent recovery snapshot`
- 仓库：`D:\gptuser\projects\ai-novel-reader-app2`
- 基线：`main` / `8ce774429da1c3f7139a221bc241c34d81a2efdd`，保留全部未提交 TASK-059 WIP。
- 模型：DeepSeek V4 Flash，纯文本，只读补丁提案，`max` 推理。

## 运行预算

- 最长运行：25 分钟；总 Token 上限不设置；完成即停。
- 预计读取：强制规则 4 份、业务源 6 份、测试 1 份；不得递归扫描。
- 不运行 Gradle、不写文件。
- 停止：需要清单外业务文件、需要 schema migration、需要改变最终提交事务、无法给出完整补丁或出现隔离异常。

## 目标

新增一个数据库侧只读恢复仓库。给定 final `COMMIT_CHAPTER` Stage ID，它必须在同一只读事务中，从 final Stage v2 来源封套反向恢复唯一且连续的 BODY→MEMORY→TRACKING→CONSISTENCY 封存链，并返回最终执行器重建草稿所需的 artifact evidence、三段派生模型快照和冻结候选来源。

本阶段只读取并验证数据库证据；不读取 artifact 明文、不生成派生行、不获取租约、不推进状态、不调用最终提交、不实现执行器。

## 当前 WIP

- `ChapterFinalCommitStageBindingV1.parseAndVerify` 已严格解析 final Stage v2，含候选身份、预期父版本、修订上限、完整 hash 历史、CONSISTENCY 前驱和 route binding。
- `ChapterCandidateSealedStageEvidenceParserV1` 已严格解析候选 Stage 的封存输出，但目前是 private 且只返回部分字段；Provider-open guard 正在使用它，不能破坏既有行为。
- `ChapterFinalCandidateCommitRepositoryV1.requireArtifactEvidence` 已在最终写事务内独立复核四段 Stage/Attempt/Usage/output chain。新恢复仓库不是新权威，不能删除、放宽或替代该最终复核。
- feature 层 `ChapterFinalCandidateArtifactRecoveryCoordinator` 已负责 BODY→MEMORY→TRACKING→CONSISTENCY 的 artifact lease、字节 hash 与严格 Parser；本阶段不要修改它。
- Android 最终候选夹具 `prepareAcceptedCandidatePipeline()` 已建立完整四段封存链并把 final Stage 推进到 `COMMITTING`。

## 必读文件

1. `AGENTS.md`
2. `docs/24-AI-DEVELOPMENT-PROTOCOL.md`
3. `docs/ai/CURRENT-CONTEXT.md`
4. 本任务包
5. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterCandidateArtifactSealRepository.kt`
6. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterConsistencyOutcomeRepository.kt`
7. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterFinalCandidateCommitRepository.kt`（只读 `requireArtifactEvidence`、`requireFinalCommitStageBinding`，不得改）
8. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationDao.kt`（只读查询签名）
9. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationEntities.kt`（只读 Stage/Attempt/Usage/Job 字段）
10. `core/database/src/androidTest/kotlin/app/zhijuan/core/database/ChapterFinalCandidateCommitDatabaseTest.kt`（只读现有最终测试及夹具）

不得读取日志、会话、密钥、原项目或无关文档。

## 允许补丁文件

只允许补丁涉及：

1. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterCandidateArtifactSealRepository.kt`
2. 新文件 `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterFinalCandidateRecoveryRepository.kt`
3. `core/database/src/androidTest/kotlin/app/zhijuan/core/database/ChapterFinalCandidateCommitDatabaseTest.kt`

不得修改最终提交仓库、DAO、entity、schema、feature 文件或文档。

## 必须实现

### 1. 复用封存输出解析

- 把现有 `ChapterCandidateSealedStageEvidenceV1` 和 Parser 调整为同包 `internal`，并让解析结果能够提供构造 `ChapterFinalCandidateArtifactEvidenceV1` 所需的全部字段：role、stage/attempt/artifact 标识、revision、raw/canonical/source hash，以及候选身份、next Stage、route binding。
- 继续 exact keys、严格 JSON、标识/hash/范围验证；校验 Stage ID 与解析上下文一致。
- 不改变 Provider-open guard 的成功与失败语义，不改变 output JSON schema。

### 2. 只读恢复仓库

建议公开 API：`ChapterFinalCandidateRecoveryRepositoryV1(database).load(finalStageId)`；命名可等价但必须清楚。

返回的不可变、脱敏结果至少包含：

- final Stage ID、Job ID、Book ID、final Stage 当前状态和更新时间；
- 完整 `ChapterFinalCommitStageSourceV1`；
- 按 BODY→MEMORY→TRACKING→CONSISTENCY 固定顺序排列的四个 `ChapterFinalCandidateArtifactEvidenceV1`；
- MEMORY、TRACKING、CONSISTENCY 三个 `modelSnapshotJson`（可额外保留 BODY 快照，但不能返回整个 Entity）。

`toString()` 不得包含模型 JSON、artifact ID/hash、候选历史或正文。

所有读取必须在一个 `database.withTransaction` 中完成，并验证：

1. final Stage 是目标 chapter 的 `COMMIT_CHAPTER`、maxAttempts=1，v2 input hash 有效；final ID 合法。
2. 非 SUCCEEDED final Stage 只允许可恢复的本地发布状态（至少覆盖现有夹具的 `COMMITTING`，并合理覆盖执行器可能读取的 `READY/PREPARING`），Job 必须为 RUNNING/PAUSING 且 currentStageId 指向它；SUCCEEDED 如支持，只能匹配 COMPLETED Job。不得允许 PENDING、远程请求/流式状态、NEEDS_ACTION、FAILED、CANCELLED 等冒充可恢复发布。
3. 从 final source 的 predecessor 开始，严格反向得到 CONSISTENCY→TRACKING→MEMORY→BODY；不得按“同 phase 最新一条”猜测。
4. 四个 Stage 必须同 Job/同 chapter、均 SUCCEEDED、phase 与 role 合法，封存输出候选 version/hash/chapter/index/revision 与 final source 完全一致。
5. 每段输出的 `nextStageId` 必须形成 BODY→MEMORY→TRACKING→CONSISTENCY→FINAL 连续链；每个派生 Stage 的冻结输入必须指向直接前驱，并保持正确 role、候选身份和 route binding。初始 BODY 为 DRAFT/revision 0；修订 BODY 为 REVISE 且必须保留其合法冻结来源。
6. CONSISTENCY 封存输出 route binding 必须等于 final source route binding；前面三段的 route binding 只能按既有候选链规则传递，不能错误要求它等于本次 ACCEPT route。
7. 每份 evidence 指向的 Attempt 必须同 Job/Stage、SUCCEEDED、无错误、是该 Stage 的最后 Attempt；input/output/artifact ref 与 evidence 相等。
8. 每个 Attempt 的 Usage 必须属于该 Book 且为 FINAL；三份返回的模型快照必须非空、最多 65,536 字符且是严格 JSON object。错误消息统一描述证据过期/损坏，不回显 JSON、正文、hash、artifact ref 或模型内容。
9. 仅返回快照，不对最终提交作授权。现有 `ChapterFinalCandidateCommitRepositoryV1` 的事务内复核必须保持原样。

## 测试要求

在现有 Android 测试文件最小新增：

1. 完整夹具在 final Stage 为 COMMITTING 时成功恢复；断言 final/job/book/source，artifact role 固定顺序，四个 stage/attempt 与夹具一致，三份模型快照等于持久值。
2. 一个数据库损坏负例：例如把 MEMORY Attempt 的 `model_snapshot_json` 改成非 object/畸形 JSON，恢复必须失败，正式章节、summary、report 均不得产生，final Stage/Job 不前进。
3. 一个链路负例：篡改某封存输出 `nextStageId` 或前驱关系后恢复失败，且同样无正式写入。

不要复制新建整套大夹具；复用 `prepareAcceptedCandidatePipeline()`。测试不能读取真实 Provider、网络或物理设备。

## 不可破坏约束

- 只读补丁提案：不要调用 apply_patch、编辑、Python/PowerShell/.NET/shell 写入；最终只输出一个完整 `apply_patch` 块。
- 不调用 Provider、真实 API、网络或设备；不运行测试。
- 不添加 remote、不 reset/checkout/clean、不改变 schema。
- 不返回 Entity 或可变 ByteArray，不把恢复快照变成最终事务的替代权威。
- 不宣布 TASK-059 完成，不更新正式状态文档。

## Sol 验证

由 Sol 审查并应用补丁后，在唯一 API 35 模拟器上显式指定 serial 运行：

```powershell
.\gradlew.bat :core:database:connectedDebugAndroidTest `
  -Pandroid.testInstrumentationRunnerArguments.class=app.zhijuan.core.database.ChapterFinalCandidateCommitDatabaseTest `
  --offline --rerun-tasks
```

随后运行：

```powershell
scripts/verify-build.ps1 -Offline
```

## 回交

按“完成内容 / 补丁提案 / 验证（写明未运行） / 未完成风险 / 需要 Sol 处理 / 假设”返回。补丁只能包含三个允许文件。
