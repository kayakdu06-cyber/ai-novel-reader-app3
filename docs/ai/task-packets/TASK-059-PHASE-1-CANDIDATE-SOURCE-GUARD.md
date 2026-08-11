# TASK-059 第一阶段任务包：候选 Stage 来源门禁

## 任务身份

- 任务 ID：`TASK-059 / Phase 1 / Candidate Stage Provider-open source guard`
- 仓库根目录：`D:\gptuser\projects\ai-novel-reader-app2`
- 基准分支与 HEAD：`main` / `8ce774429da1c3f7139a221bc241c34d81a2efdd`
- 当前未提交改动：隔离/工作流文件 `.gitignore`、`AGENTS.md`、`docs/README.md`、`.codex/`、`docs/24-AI-DEVELOPMENT-PROTOCOL.md`、`docs/ai/`、`reports/2026-08-04-63-deepseek-bounded-launcher-fix.md`、`scripts/get-deepseek-key.ps1`、`scripts/invoke-deepseek-codex-child.ps1`、`scripts/set-deepseek-key.ps1`、`scripts/start-deepseek-codex.ps1`；TASK-059 WIP 文件 `ChapterCandidateArtifactSealRepository.kt`、`GenerationRequestAuditRepository.kt`、`ChapterMemoryExtractionJobFactory.kt`、`ChapterTrackingProjectionJobFactory.kt`、`ChapterFinalCandidateCommitDatabaseTest.kt`
- 执行模型：DeepSeek（纯文本）

## 运行预算

- 推理等级：`max`（用户 2026-08-04 明确要求所有 DeepSeek 工作使用最高推理强度）
- 最长运行时间：15 分钟
- 累计 Token 上限：1,000,000
- 预计读取文件：16 个。仅限 `AGENTS.md`、三份 AI 协议/上下文/本任务包、TASK-059 相关编号文档段落，以及“必读资料”列出的 6 个源代码/测试文件；不得递归扫描整个仓库、历史会话或其他项目
- 预计执行命令/测试：不超过 12 条；优先精确搜索、差异审查、`:core:database:testDebugUnitTest` 与 `:core:database:compileDebugAndroidTestKotlin`，不得运行设备测试或真实 Provider
- 提前停止条件：Git 根目录不一致、需要超出允许文件范围、需要 schema/迁移/UI/生产协调器改动、权限阻塞、同一测试连续失败两次且没有新证据、达到任一预算门禁、发现未声明的高风险或用户改动冲突

## 目标

延续现有 TASK-059 WIP，为候选 BODY → MEMORY → TRACKING → CONSISTENCY 链补上独立的 Provider 开启前来源门禁。合法候选 Stage 只有在其冻结 binding 与同 Job 内已成功封存的直接前驱输出完全一致时才可继续；篡改、陈旧或跨 Job/章节 lineage 必须在 Provider 打开前失败。

本阶段只解决候选 Stage binding、前驱封存证据和 Provider-open 接线，不实现完整 TASK-059 编排，不宣布 TASK-059 完成。

## 当前现场与已有 WIP

- 已存在的实现：
  - `ChapterCandidateStageBindingV1` 与 `ChapterCandidateStageSourceV1` 已能生成/解析候选 Stage 的冻结来源。
  - `ChapterCandidateArtifactSealRepositoryV1` 已把已校验候选 artifact 封存到 Stage output reference，并原子创建下一 Stage。
  - `GenerationRequestAuditRepository.requireJobAllowsProviderOpen` 已统一调用章节推进、上下文、正式记忆和正式追踪来源守卫。
  - 当前 WIP 已新增候选 Stage 来源守卫、统一 Provider-open 前置调用，并移除了正式记忆/追踪守卫对候选 binding 的无验证跳过；必须审计其严格性并补齐缺失测试，不得从零重写。
  - `ChapterFinalCandidateCommitDatabaseTest` 已通过真实 Room 路径直接驱动 BODY/MEMORY/TRACKING/CONSISTENCY/COMMIT 流程。
- 已存在的测试：`ChapterRevisionPolicyTest`、`ChapterRevisionRequestTest`、`ChapterFinalCandidateCommitDatabaseTest`。
- 已知失败或缺口：
  - 备份现场记录专项数据库测试曾在候选 memory Provider-open 来源识别处失败。
  - 当前候选 Stage 独立来源守卫尚未获得足量回归证据；现有新增测试只明确覆盖了 predecessor `nextStageId` 篡改，尚未证明合法全链、跨 Job/章节、旧 hash/revision、损坏 JSON、正式 memory/tracking 回归。
  - 使用正式 Prompt Bundle 的候选 `CHECK_CONSISTENCY/REVISE_CHAPTER` 会被普通章节推进逻辑要求不存在的 `chapterProgressionGate`。
  - 这些 WIP 尚未接入完整生产级整章协调器，本阶段不得伪装成已经接通。
- 必须延续、不得从零重写：现有 binding、seal repository、统一 RequestIntent/Provider-open 审计和最终提交专项测试。

## 必读资料

1. `AGENTS.md`
2. `docs/24-AI-DEVELOPMENT-PROTOCOL.md`
3. `docs/ai/CURRENT-CONTEXT.md`
4. `docs/01-PRD.md` PR-008 及第 13–15 节；`docs/03-USER-FLOWS.md` 第 16–19 节；`docs/06-AI-GENERATION-SYSTEM.md` 第 24–26 节；`docs/08-TECHNICAL-ARCHITECTURE.md` 第 21–23 节；`docs/09-DATA-MODEL.md` 的 Stage/Attempt/Usage/ConsistencyReport 与章节提交证据；`docs/10-STATE-MACHINES.md` 的提交与 NEEDS_ACTION；`docs/13-ERROR-HANDLING.md`、`docs/14-COST-CONTROL.md`、`docs/15-TEST-PLAN.md`、`docs/17-ACCEPTANCE-CRITERIA.md` 中 TASK-059 相关段落；`docs/19-IMPLEMENTATION-BACKLOG.md` TASK-059；`docs/22-WORK-STATUS.md` 下一步。
5. 源代码和测试：
   - `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterCandidateArtifactSealRepository.kt`
   - `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationRequestAuditRepository.kt`
   - `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterMemoryExtractionJobFactory.kt`
   - `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterTrackingProjectionJobFactory.kt`
   - `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterProgressionGateRepository.kt`
   - `core/database/src/androidTest/kotlin/app/zhijuan/core/database/ChapterFinalCandidateCommitDatabaseTest.kt`

## 范围

允许修改：

- `ChapterCandidateArtifactSealRepository.kt`
- `GenerationRequestAuditRepository.kt`
- 只有确有必要时：`ChapterMemoryExtractionJobFactory.kt`、`ChapterTrackingProjectionJobFactory.kt`、`ChapterProgressionGateRepository.kt`
- `core/database/src/test/` 与 `core/database/src/androidTest/` 下直接相关测试

明确不在范围：

- `feature:generation` 的整章生产协调器、App UI、Provider 适配器、模型配置、数据库 schema/迁移。
- 任务完成状态、backlog、工作状态、追踪矩阵、正式工作汇报。
- `.codex/`、DeepSeek 启动脚本、现有未提交隔离/协议文档。
- 原项目 `D:\gptuser\projects\ai-novel-reader` 和任何其他副本。

## 不可破坏的约束

- 项目隔离：开始修改、构建或测试前确认 Git 根目录严格等于本任务包中的仓库根目录。
- 安全与隐私：不得读取、输出或记录 API Key；异常与测试输出不得包含正文或模型快照原文。
- 状态机与幂等：合法 replay 必须继续可用；同一封存前驱只能激活与其 output reference 精确一致的 next Stage。
- 数据库与事务：Provider-open 守卫只读并失败关闭；不得在授权检查中创建/修改 ChapterVersion 或派生数据。
- 联网与费用：不得调用织卷 App 内部真实 Provider，不得产生真实生成费用；测试只使用本地数据库/假数据。
- 兼容性：不得新增依赖、表、字段或迁移。
- 保留所有任务身份中列出的未提交隔离与文档改动，不得清理、回退、覆盖或提交。

## 实施要求

1. 先审计并在回交说明 BODY → MEMORY → TRACKING → CONSISTENCY → COMMIT 的 binding/output-reference 校验链，再改代码。
2. 实现候选 Stage Provider-open 来源守卫或等价单一入口。它必须：
   - 严格区分“不是候选 binding”和“声称是候选但格式/lineage 无效”，不得吞掉后者；
   - 验证当前 Stage 与 Job、章节、角色/phase、candidate version/hash、chapter index、revision index；
   - 要求直接前驱同 Job、同章节、状态 `SUCCEEDED`，且其封存 output reference 的 pipeline/role/candidate version/hash/revision/nextStageId 与当前 binding 精确一致；
   - 固定 BODY→MEMORY→TRACKING→CONSISTENCY 顺序；修订 BODY 的前驱只能是已封存 CONSISTENCY；
   - 对跨 Job/章节、错误前驱、旧 hash、错误 revision、错误 nextStageId、损坏 JSON 失败关闭。
3. 接到统一 `GenerationRequestAuditRepository` 的 Provider-open claim。合法候选 Stage 校验后不再被正式版本 memory/tracking guard或普通 `chapterProgressionGate` 逻辑误杀；非候选 Stage 的现有门禁保持。
4. 不允许以简单 `return` 或扩大 `runCatching(...).getOrDefault(false)` 代替安全校验。
5. 补测试覆盖合法候选链、前驱 output/binding 篡改、跨 Job/章节或错误 nextStage、非候选正式 memory/tracking 回归。
6. 若必须扩大到完整编排，停止扩展并在回交说明，不得擅自完成 TASK-059 全部剩余内容。

## 验收标准

- [ ] 合法候选 MEMORY、TRACKING、CONSISTENCY、REVISE Stage 在统一 Provider-open 门禁中通过。
- [ ] predecessor 或 candidate lineage 任一字段不一致时，在 Provider 打开前拒绝。
- [ ] 候选 `CHECK_CONSISTENCY/REVISE_CHAPTER` 不因缺少普通 progression evidence 被误判，同时不能绕过候选 lineage 校验。
- [ ] 正式版本 memory/tracking 来源守卫无回归。
- [ ] 无真实 API、密钥、远程、schema、UI 或任务完成状态改动。

## 验证命令

确保缓存和临时目录仍在 `D:\gptuser`：

```powershell
.\gradlew.bat :core:database:testDebugUnitTest :core:database:compileDebugAndroidTestKotlin --offline --no-daemon
```

如已有项目模拟器且能按明确 serial 隔离，可只运行 `ChapterFinalCandidateCommitDatabaseTest`；不得选择或写入物理设备。无法确保设备隔离时只编译 AndroidTest，并写明未运行设备测试。

统一离线门禁由 Sol 审查后决定：

```powershell
scripts/verify-build.ps1 -Offline
```

## 回交格式

严格按以下标题返回：`完成内容`、`修改文件`、`验证`、`未完成/风险`、`需要 Sol 处理`、`假设`。不要宣布整个 TASK 完成，不要更新正式完成状态。

## Sol 验收记录（2026-08-04）

- 阶段状态：通过；仅 TASK-059 Phase 1 完成，完整 TASK-059 继续进行。
- DeepSeek：以 `max` 推理运行，正式受限任务在 1,002,947 Token 时由硬门禁终止，未形成可采纳差异；Sol 独立完成审查与实现。
- 专项证据：API 35 `ChapterFinalCandidateCommitDatabaseTest` 10/10 通过。
- 统一证据：`scripts/verify-build.ps1 -Offline` 通过，安全扫描与备份排除策略通过。
- 外部调用：织卷真实 Provider 0 次，物理设备写入 0。
- 报告：工作汇报 64。
