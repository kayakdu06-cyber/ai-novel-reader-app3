# TASK-064 Phase 2A：派生链冻结 route identity 解析器

## 任务身份

- 任务 ID：`TASK-064 / Phase 2A derived-stage route identity`
- 仓库根目录：`D:\gptuser\projects\ai-novel-reader-app2`
- 基准分支与 HEAD：`main` / `8ce7744`；仓库中存在大量 TASK-059～064 未提交 WIP，必须原样保留
- 当前未提交改动：Phase 1A～1D 已完成 persistent queue、Job/Stage 原子租约和 heartbeat envelope；尚无 contract-aware dispatcher
- 执行模型：DeepSeek（纯文本）

## 运行预算

- 推理等级：`max`
- 最长运行时间：30 分钟。理由：范围只有一个纯解析器和一个 JVM 测试文件，但必须复用四套既有严格来源解析器并覆盖错误身份，不能用 phase 猜测
- 累计 Token 上限：无
- 预计读取文件数：10 个，见必读资料；只可沿直接符号引用少量扩展
- 预计命令：`core:database` JVM 测试与 Kotlin 编译，共 1～2 个；不操作 ADB
- 提前停止条件：需要 schema/DAO/entity/migration 变更、需要修改 feature/provider/app、需要放松既有严格解析、连续两次相同编译失败

## 目标

新增一个纯本地、无写入的有限 route identity 解析器。它必须从 `GenerationStageEntity.phase + targetType + inputSourcesJson` 的冻结 `sourcePolicyVersion/schemaVersion/artifactRole` 识别派生链 Stage，尤其必须把同属 `EXTRACT_MEMORY` 的章节记忆与剧情追踪分开；未知、畸形、冲突或 hash 不匹配的输入一律失败关闭。

## 当前现场与已有 WIP

- `GenerationPhase.EXTRACT_MEMORY` 同时承载 `ChapterMemoryExtractionJobFactory` 和 `ChapterTrackingProjectionJobFactory`，只看 phase 会调用错 executor。
- 普通/编辑重建 memory 与 tracking 已有严格 `parseAndVerify`，schema v1/v2 的重建 binding 已有正式解析器。
- 候选链 Stage 已有 `ChapterCandidateStageBindingV1.parseAndVerify`；它用 `artifactRole` 区分 BODY/MEMORY/TRACKING/CONSISTENCY，并校验 phase/target/hash。
- 最终提交 Stage 已有 `ChapterFinalCommitStageBindingV1.parseAndVerify`，冻结 policy 为 v3。
- Phase 1B queue snapshot 只有 phase/target，不读取 payload；本阶段只建立 route identity 原语，不接 runner，不调用 executor。

## 必读资料

1. `AGENTS.md`
2. `docs/24-AI-DEVELOPMENT-PROTOCOL.md`
3. `docs/ai/CURRENT-CONTEXT.md` 第 17～20 节
4. `core/model/src/main/kotlin/app/zhijuan/core/model/GenerationPhase.kt`
5. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationEntities.kt`
6. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterMemoryExtractionJobFactory.kt`
7. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterTrackingProjectionJobFactory.kt`
8. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterCandidateArtifactSealRepository.kt` 的 `ChapterCandidateArtifactRoleV1` 与 `ChapterCandidateStageBindingV1`
9. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterConsistencyOutcomeRepository.kt` 的 `ChapterFinalCommitStageBindingV1`
10. 现有三个 JVM factory/binding 测试：`ChapterMemoryExtractionJobFactoryTest.kt`、`ChapterTrackingProjectionJobFactoryTest.kt`、`ChapterCandidateStageBindingTest.kt`

不得递归扫描历史日志、备份、其他项目或无关模块。

## 范围

允许修改：

- 新建 `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationRunnerStageRouteResolver.kt`
- 新建 `core/database/src/test/kotlin/app/zhijuan/core/database/generation/GenerationRunnerStageRouteResolverTest.kt`
- 仅当消除版本字符串重复确有必要时，可把 `ChapterCandidateStageBindingV1` 与 `ChapterFinalCommitStageBindingV1` 内现有 `SOURCE_POLICY_VERSION` 从 `private` 改成 `internal`；不得改变值、解析逻辑或其他行为

明确不在范围：

- 不修改 DAO、entity、Room schema、migration、database wiring、Gradle。
- 不修改 Phase 1A～1D、feature/provider/app/UI/WorkManager/timing。
- 不创建或推进 Job/Stage/Attempt/Usage，不调用 Provider，不构造请求，不写业务数据。
- 不实现 planning/context/draft 的普通 route；本阶段只覆盖下方明确列出的派生链 route。
- 不更新正式任务状态、报告或其他文档。

## 不可破坏的约束

- 项目根目录必须精确匹配 app2；不访问其他项目。
- 解析器必须先把 `inputSourcesJson` 作为严格 JSON object 读取，拒绝非 object、畸形 JSON、缺失或非字符串 `sourcePolicyVersion`；错误消息不得拼接 JSON、ID、target 或其他 payload。
- 不得使用 `GenerationPhase` 单独决定 route，也不得用字段是否“看起来存在”的宽松启发式猜测。
- 命中某个 `sourcePolicyVersion` 后，必须调用该 policy 已有的完整 `parseAndVerify`，让 schema、root keys、phase/target、targetId、inputVersionHash、binding/hash 全部继续由权威解析器验证。
- 普通/重建 memory 与 tracking 必须在完整验证后根据正式 rebuild binding 是否存在区分 v1/v2 route；不得只读 `schemaVersion` 数字就相信它。
- 候选链必须根据完整解析后的 `artifactRole + phase` 映射；任何未列出的组合失败关闭。
- 最终提交只接受完整通过 v3 final binding 的 Stage。
- route/result 的 `toString()`（如果新增结果对象）不得暴露 Stage/Job ID、inputSourcesJson、targetId、hash 或其他 payload。
- 真实/Fake Provider 调用均为 0；不得操作 ADB、物理设备或网络。

## 有限 route 集合

名称可按项目风格做非常小的调整，但语义必须一一对应且不能合并：

1. `FORMAL_CHAPTER_MEMORY_V1`：普通 memory source schema v1。
2. `EDIT_REBUILD_CHAPTER_MEMORY_V2`：带正式 `chapterEditRebuild` binding 的 memory schema v2。
3. `FORMAL_CHAPTER_TRACKING_V1`：普通 tracking source schema v1。
4. `EDIT_REBUILD_CHAPTER_TRACKING_V2`：带正式 `chapterEditRebuild` binding 的 tracking schema v2。
5. `CANDIDATE_CHAPTER_DRAFT_V1`：candidate policy，role BODY，phase DRAFT_CHAPTER。
6. `CANDIDATE_CHAPTER_MEMORY_V1`：candidate policy，role MEMORY，phase EXTRACT_MEMORY。
7. `CANDIDATE_CHAPTER_TRACKING_V1`：candidate policy，role TRACKING，phase EXTRACT_MEMORY。
8. `CANDIDATE_CHAPTER_CONSISTENCY_V1`：candidate policy，role CONSISTENCY，phase CHECK_CONSISTENCY。
9. `CANDIDATE_CHAPTER_REVISION_V1`：candidate policy，role BODY，phase REVISE_CHAPTER。
10. `FINAL_CHAPTER_COMMIT_V3`：final commit source policy v3，phase COMMIT_CHAPTER。

`NORMALIZE_INPUT`、planning、context、未绑定普通 DRAFT、`UPDATE_FUTURE_PLAN` 及任何未知 policy 在本阶段都必须明确失败，不得返回 generic/unknown route 给调用方继续执行。

## 实施要求

1. 提供有限 `enum class GenerationRunnerStageRoute` 与纯 `resolve(stage: GenerationStageEntity)` 入口；可用内部小 helper，但不要复制四套权威解析器的字段级校验。
2. 只为选择权威解析器读取 `sourcePolicyVersion`；一旦选中，完整验证必须委托现有 parser。
3. route 解析不得产生数据库或文件写入，不得捕获并吞掉权威 parser 的错误后尝试“次优 route”。
4. 新增 JVM 测试，尽量用现有生产 factory/stageSetup 构造合法 Stage，不用手写假 payload 冒充正向合同。
5. 测试 helper 可以把 `GenerationStageSetup` 转成 `GenerationStageEntity`，但必须保留真实 inputVersionHash、phase、target 和 JSON。

## 测试要求

至少覆盖：

1. memory v1 与带合法 rebuild binding 的 v2 分别命中不同 route。
2. tracking v1 与带合法 rebuild binding 的 v2 分别命中不同 route。
3. candidate BODY(DRAFT)、MEMORY、TRACKING、CONSISTENCY、BODY(REVISE) 五种合法组合各命中唯一 route。
4. 合法 final commit v3 命中最终本地提交 route。
5. 同为 `EXTRACT_MEMORY` 时，把 memory payload 的 phase/target 或 policy/schema 改成 tracking 身份不得误路由，反向同理。
6. 未知 policy、缺 policy、非 object、畸形 JSON、未知 schema、额外 root 字段、错误 inputVersionHash 均失败。
7. candidate 的 role/phase 不兼容、final commit 错 phase/target 均失败。
8. 若新增结果对象，验证 `toString()` 不包含 ID/hash/payload；enum 本身无需额外包装。

测试只断言异常类型/有限消息，不打印整个 payload。

## 验收标准

- [ ] `EXTRACT_MEMORY` 不再能仅靠 phase 混淆 memory 与 tracking。
- [ ] 10 个 route 全部来自权威冻结 policy 解析；未知/畸形/冲突输入失败关闭。
- [ ] 没有数据库写入、Provider 调用、schema 或状态机变化。
- [ ] 既有 factory/binding 测试不回退。
- [ ] 新增测试覆盖正向 route 和关键负例。

## 验证命令

```powershell
$root=(git rev-parse --show-toplevel).Trim()
if ($root -cne 'D:/gptuser/projects/ai-novel-reader-app2') { throw "Wrong repository: $root" }
$env:GRADLE_USER_HOME='D:\gptuser\cache\gradle'
$env:TEMP='D:\gptuser\cache\temp\ai-novel-reader-app2'
$env:TMP=$env:TEMP
./gradlew :core:database:testDebugUnitTest --tests '*GenerationRunnerStageRouteResolverTest' --offline --no-daemon
./gradlew :core:database:testDebugUnitTest --offline --no-daemon
```

不运行 connectedAndroidTest、统一 Release/R8 或安全产物扫描；交给 Sol。

## 回交格式

请严格按以下标题返回：

1. `完成内容`
2. `修改文件`
3. `验证`
4. `未完成/风险`
5. `需要 Sol 处理`
6. `假设`

不要宣布 TASK-064 完成，不要更新正式状态。
