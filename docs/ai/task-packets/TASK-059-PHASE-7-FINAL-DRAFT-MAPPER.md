# TASK-059 第七阶段 DeepSeek：最终候选提交草稿映射器

## 任务身份

- 任务 ID：`TASK-059 / Phase 7 / final candidate commit draft mapper`
- 仓库根目录：`D:\gptuser\projects\ai-novel-reader-app2`
- 当前分支/基线：`main` / `8ce774429da1c3f7139a221bc241c34d81a2efdd`
- 当前状态：存在大量 Sol 延续中的 TASK-059 未提交 WIP，必须原样保留；不得重写、格式化、清理或回退现有文件。
- 执行模型：DeepSeek V4 Flash（纯文本）

## 运行预算

- 推理等级：`max`
- 最长运行时间：30 分钟（用户允许为较复杂的小任务适当延长；仍以完成即停为准）
- 总 Token 上限：按用户明确要求不设置；目标完成后立即停止
- 允许读取：本任务包、项目规则文件及下方明确列出的 6 个业务文件
- 允许修改：仅新增下方列出的 2 个文件
- 提前停止：需要修改第三个业务文件、需要数据库/Gradle 配置变化、定向测试连续失败 2 次、发现设计假设不成立或权限/隔离异常

## 目标

新增一个纯 Kotlin 映射器，把已经通过各阶段校验并已转换成数据库行草稿的 MEMORY、TRACKING、CONSISTENCY 结果，与当前候选正文、候选历史和四类 artifact evidence，唯一组装成 `ChapterFinalCandidateCommitDraftV1`。

本任务只负责“确定性组装和联网前/数据库前的来源一致性校验”，不读取数据库、不读取加密 artifact、不执行最终提交、不创建第二套策略。

## 允许读取的业务文件

1. `feature/generation/src/main/kotlin/app/zhijuan/feature/generation/ChapterCandidateDerivedStagePersistenceCoordinator.kt`
2. `feature/generation/src/main/kotlin/app/zhijuan/feature/generation/ChapterMemoryExtractionPersistenceMapper.kt`
3. `feature/generation/src/main/kotlin/app/zhijuan/feature/generation/ChapterTrackingProjectionPersistenceMapper.kt`
4. `feature/generation/src/main/kotlin/app/zhijuan/feature/generation/ChapterConsistencyPersistenceMapper.kt`
5. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterFinalCandidateCommitRepository.kt`
6. `feature/generation/src/test/kotlin/app/zhijuan/feature/generation/ChapterCandidateDerivedStagePersistenceCoordinatorTest.kt`

除编译器明确指出的直接类型定义外，禁止 `rg --files`、`Get-ChildItem -Recurse`、递归 glob 或扫描整个模块/仓库。若列表不足，停止并在回交中列出所缺文件，不得自行扩大范围。

## 允许修改

只能新增：

- `feature/generation/src/main/kotlin/app/zhijuan/feature/generation/ChapterFinalCandidateCommitDraftMapper.kt`
- `feature/generation/src/test/kotlin/app/zhijuan/feature/generation/ChapterFinalCandidateCommitDraftMapperTest.kt`

禁止修改任何现有源码、测试、文档、脚本、Gradle 文件、数据库 schema/DAO 或其他项目。

## 必须实现的公开形状

在新实现文件中新增：

```kotlin
data class ChapterFinalCandidateCommitMappingSpecV1(
    val candidate: ChapterCandidatePipelineIdentityV1,
    val expectedCurrentVersionId: String?,
    val candidateContent: String,
    val maximumAutomaticRevisions: Int,
    val candidateContentHashHistory: List<String>,
    val artifacts: List<ChapterFinalCandidateArtifactEvidenceV1>,
    val memory: ChapterMemoryDerivedDraft,
    val tracking: ChapterTrackingProjectionDerivedDraft,
    val consistency: ChapterConsistencyDerivedDraftV1,
    val committedAt: Long,
)

object ChapterFinalCandidateCommitDraftMapperV1 {
    fun map(spec: ChapterFinalCandidateCommitMappingSpecV1): ChapterFinalCandidateCommitDraftV1
}
```

允许为验证增加私有函数、常量和安全的 `toString()`；不得改变上述字段和入口名称。

## 确定性校验要求

`map` 必须在返回草稿前至少证明：

1. `candidateContent` 非空，SHA-256 等于 `candidate.contentHash`；
2. 历史数量等于 `revisionIndex + 1`，最后一个 hash 等于当前候选，全部是小写 64 位 SHA-256 且不重复；
3. `maximumAutomaticRevisions` 为 1..2，当前修订序号不超过它；
4. artifact evidence 恰好包含 BODY、MEMORY、TRACKING、CONSISTENCY 各一份，没有重复或缺失；
5. BODY 的 `canonicalOutputHash` 等于当前候选正文 hash；
6. MEMORY 的 `canonicalOutputHash` 等于 `memory.extractionContentHash`；
7. TRACKING 的 `canonicalOutputHash` 等于 `tracking.trackingContentHash`；
8. 最终一致性 gate 必须是 `ACCEPT_CANDIDATE`；
9. memory summary、tracking projection、consistency report 的候选版本 ID、章节 ID/序号或正文来源 hash（字段存在时）与当前候选一致；
10. tracking projection 和 consistency report 的 `generationStageId` 必须分别等于对应 evidence 的 `stageId`；
11. `committedAt` 必须非负；映射出的派生数据库行时间必须已经等于 `committedAt`，不能在这里偷偷改时间；
12. `expectedCurrentVersionId` 非空时必须是现有标识符格式；
13. 任何正文、JSON、artifact 内容不得写入错误消息或 `toString()`。

若某个数据库行类型没有章节 ID 字段，只核对它实际拥有的来源字段，不要发明字段或改数据库类型。

## 映射规则

成功时直接构造一个 `ChapterFinalCandidateCommitDraftV1`：

- 候选身份、正文、修订次数、历史、expected current version、时间来自 spec；
- artifacts 按 `role.ordinal` 排序后写入，保证确定性；
- summary/entityEvents/canonFacts 及 memory hash 来自 `memory`；
- projection/timeline/foreshadow rows 及 tracking hash 来自 `tracking`；
- consistency report 与 report hash 来自 `consistency`；
- `consistencyOutputContentHash` 来自 CONSISTENCY evidence 的 `canonicalOutputHash`，不要用 combined report hash 代替；
- 不重新执行现有三个 persistence mapper，不重新解析 Provider 输出，不做 IO。

## 最小测试

新测试文件至少包含 4 项纯 JVM 单元测试：

1. 合法的未修订候选能映射，所有字段和四个输出 hash 来源正确，artifacts 顺序固定；
2. `consistency.gate` 为 `REVISE_CANDIDATE` 时拒绝；
3. 正文 hash 与当前候选不一致时拒绝；
4. 任一 MEMORY/TRACKING/CONSISTENCY evidence 的 canonical hash 与派生草稿不一致，或 evidence role 缺失/重复时拒绝。

测试可构造最小数据库行 fixture；不要复制大型生产解析器，不要调用数据库、Android、网络或真实 Provider。

## 不可破坏的约束

- 修改前必须确认 Git 根目录精确等于 `D:/gptuser/projects/ai-novel-reader-app2`。
- 不读取、显示或记录 API Key、真实正文 artifact 或私人内容。
- 不调用织卷 App 内部真实 Provider API；只允许离线 JVM 测试。
- 不修改状态机、修订策略、数据库、DAO、schema 或现有 WIP。
- 禁止 `reset`、`checkout`、`clean`、全仓格式化、覆盖现有文件和添加 Git remote。
- 不宣布整个 TASK-059 完成。

## 验收命令

只运行：

```powershell
.\gradlew.bat :feature:generation:testDebugUnitTest --tests "app.zhijuan.feature.generation.ChapterFinalCandidateCommitDraftMapperTest" --offline
```

不要运行统一离线门禁；统一门禁由 Sol 审查差异后执行。

## 回交格式

1. `完成内容`
2. `修改文件`
3. `验证`
4. `未完成/风险`
5. `需要 Sol 处理`
6. `假设`

必须同时报告是否严格遵守读取/修改清单。若没有产生两个新文件和可审查差异，任务视为未交付。
