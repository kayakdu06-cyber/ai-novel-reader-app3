# TASK-059 第九阶段：最终 COMMIT Stage 完整来源绑定

## 任务身份

- 任务 ID：`TASK-059 / Phase 9 / final commit source binding`
- 仓库：`D:\gptuser\projects\ai-novel-reader-app2`
- 基线：`main` / `8ce774429da1c3f7139a221bc241c34d81a2efdd`，保留全部未提交 TASK-059 WIP。
- 模型：DeepSeek V4 Flash，纯文本，只读补丁提案，`max` 推理。

## 运行预算

- 最长运行：20 分钟；总 Token 上限不设置；完成即停。
- 预计读取：强制规则 4 份、业务源 5 份、测试 3 份；不得递归扫描。
- 不运行 Gradle、不写文件。
- 停止：需要清单外业务文件、需要 schema migration、需要改变最终事务行为、无法给出完整补丁或出现隔离异常。

## 目标

把最终 COMMIT Stage 的 `inputSourcesJson` 提升为可严格恢复的唯一来源封套：除现有候选身份外，必须冻结 `expectedCurrentVersionId`、`maximumAutomaticRevisions` 和完整 `candidateContentHashHistory`。最终提交仓库在发布前重新解析并核对该封套与提交草稿，拒绝调用方在重启或重放时偷换修订上限、历史或预期父版本。

本阶段不恢复 artifact、不生成派生行、不实现执行器；只完善最终 Stage 的来源契约与最终仓库复核。

## 当前 WIP

- `ChapterRevisionPolicyDecisionV1.AcceptCandidate` 已由 Sol 增加 `maximumAutomaticRevisions`，比例/非适用为 1、严格为 2；定向策略测试通过。必须直接使用该字段，禁止在数据库模块复制模式判断。
- `ChapterConsistencyOutcomeRepositoryV1.finalCommitStage` 当前只冻结 candidate/version/hash/chapter/revision/predecessor/route hash，信息不足。
- `ChapterFinalCandidateCommitRepositoryV1` 当前验证四段 artifact 链和最终草稿，但没有严格解析 final Stage 输入来源。
- Phase 8 已新增受保护 artifact 恢复器；不要修改或调用它。

## 必读文件

1. `AGENTS.md`
2. `docs/24-AI-DEVELOPMENT-PROTOCOL.md`
3. `docs/ai/CURRENT-CONTEXT.md`
4. 本任务包
5. `core/task/src/main/kotlin/app/zhijuan/core/task/ChapterRevisionPolicy.kt`
6. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterConsistencyOutcomeRepository.kt`
7. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterFinalCandidateCommitRepository.kt`
8. `feature/generation/src/main/kotlin/app/zhijuan/feature/generation/ChapterCandidateConsistencyRoutingCoordinator.kt`
9. `feature/generation/src/test/kotlin/app/zhijuan/feature/generation/ChapterConsistencyAcceptanceGateTest.kt`（只看 routingSpec）
10. `core/database/src/androidTest/kotlin/app/zhijuan/core/database/ChapterFinalCandidateCommitDatabaseTest.kt`（只看最终提交测试、prepareAcceptedCandidatePipeline、prepareConsistencyRoute、finalDraft）
11. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationEntities.kt` 或实际定义 `GenerationStageEntity` 的单一文件（仅在需要核对字段时读取）

不得读取日志、会话、密钥、原项目或无关文档。

## 允许补丁文件

只允许修改：

1. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterConsistencyOutcomeRepository.kt`
2. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterFinalCandidateCommitRepository.kt`
3. `feature/generation/src/main/kotlin/app/zhijuan/feature/generation/ChapterCandidateConsistencyRoutingCoordinator.kt`
4. `feature/generation/src/test/kotlin/app/zhijuan/feature/generation/ChapterConsistencyAcceptanceGateTest.kt`
5. `core/database/src/androidTest/kotlin/app/zhijuan/core/database/ChapterFinalCandidateCommitDatabaseTest.kt`

不得新增文件，不得修改 core task 的现有 WIP，不得改文档。

## 必须实现

### 1. 最终来源封套

在 `generation` 包中建立 `ChapterFinalCommitStageSourceV1` 与 `ChapterFinalCommitStageBindingV1`（可放在 `ChapterConsistencyOutcomeRepository.kt`），负责唯一构造和严格解析最终 COMMIT Stage：

- 保留现有字段：pipeline/source policy、candidate version/hash、chapter ID/index、revision index、predecessor Stage、route binding；
- 新增：`expectedCurrentVersionId`（nullable JSON string）、`maximumAutomaticRevisions`（1..2）、`candidateContentHashHistory`（JSON array）；
- exact keys；identifier/hash/范围校验；history 数量必须为 revision+1、全部小写 SHA-256、无重复、末项等于 candidate hash；revision 不超过 maximum；
- Stage 必须是同 chapter 的 `COMMIT_CHAPTER`、maxAttempts=1，`inputVersionHash` 必须按 policy version + 完整 JSON 复算一致；
- 构造仍使用 `StageIdempotencyKey`，不得改变其他状态机字段。

### 2. 路由传递

- `ChapterCandidateConsistencyRoutingSpecV1` 新增 `expectedCurrentVersionId: String?` 并校验 identifier；默认字符串继续脱敏。
- coordinator 把它传入 `ChapterConsistencyOutcomeDraftV1`。
- `ChapterConsistencyOutcomeDraftV1` 新增同字段并校验。
- Accept 路径用 `decision.maximumAutomaticRevisions` 与 `policyInput.candidateContentHashHistory` 构造最终来源封套；REVISE/NEEDS_ACTION 行为不得改变。

### 3. 最终仓库复核

在 `ChapterFinalCandidateCommitRepositoryV1.commit` 的事务内、任何正式行写入前解析最终来源，并要求：

- candidate version/hash/chapter/revision 与 draft 一致；
- expected current version、maximum automatic revisions、完整 history 与 draft 一致；
- predecessor Stage 等于 CONSISTENCY evidence Stage；route hash 与 sealed consistency output 一致；
- final Stage 输入损坏、hash 陈旧或调用方改草稿时失败，正式章节/派生数据/Stage/Job 均不前进。

不要删除最终仓库现有 artifact、Attempt、Usage、lineage 和 lease 复核；这是额外早期门禁。

## 测试要求

最小增量：

1. feature routing fixture 显式传 `expectedCurrentVersionId = null`，保持现有 JVM 测试编译与行为；
2. Android 成功管线在提交前解析 final Stage source，断言 null expected current、上限 1、history 为初始 BODY hash、predecessor 为 consistency Stage；
3. 新增 Android 负例：把合法 draft 的 `maximumAutomaticRevisions` 从 1 改 2，最终提交必须失败，版本/summary/report 不产生，final Stage 保持 COMMITTING，Job 保持 RUNNING；
4. 如空间允许，再覆盖 expected current 或 history 不匹配之一；不要为凑数量复制大夹具。

## 安全/纪律

- 只读补丁提案：不要调用 apply_patch、编辑、Python/PowerShell/.NET/shell 写入；最终只输出一个完整 `apply_patch` 块。
- 不调用 Provider、真实 API、网络或设备；不运行测试。
- 错误与 `toString()` 不得包含正文、完整 JSON、candidate history 内容或模型快照。
- 不添加 remote、不 reset/checkout/clean、不改变 schema。
- 不宣布 TASK-059 完成。

## Sol 验证

```powershell
.\gradlew.bat :feature:generation:testDebugUnitTest --tests "app.zhijuan.feature.generation.ChapterConsistencyAcceptanceGateTest" --offline --rerun-tasks
```

Android 专项由 Sol 在已存在的项目模拟器上用显式 serial 运行；物理设备保持只读。随后运行：

```powershell
scripts/verify-build.ps1 -Offline
```

## 回交

按“完成内容 / 补丁提案 / 验证（写明未运行） / 未完成风险 / 需要 Sol 处理 / 假设”返回。补丁只能包含五个允许文件。
