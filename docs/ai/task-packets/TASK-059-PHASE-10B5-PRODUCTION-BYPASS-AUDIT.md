# TASK-059 第十阶段 B5：最终提交生产旁路只读审计

## 任务身份

- 任务 ID：`TASK-059 / Phase 10B5 / final commit production bypass audit`
- 仓库根目录：`D:\gptuser\projects\ai-novel-reader-app2`
- 基准分支与 HEAD：`main` / `8ce774429da1c3f7139a221bc241c34d81a2efdd`；保留全部未提交 WIP。
- 执行模型：DeepSeek V4 Flash，纯文本，只读审计。

## 运行预算

- 推理等级：`max`。
- 最长运行时间：15 分钟。
- 累计 Token 上限：不设置（用户明确要求；完成本审计即停）。
- 预计读取：4 个强制入口、本任务包、7 个指定生产文件；允许对三个 `src/main` 根执行精确符号搜索，并只读取直接命中的生产文件片段。
- 预计命令：一次 `git status --short`，不超过 8 次 `rg`/定点读取；不构建、不测试、不联网。
- 提前停止：需要写文件、需要读取其他项目/密钥/日志、需要递归读取大测试或整仓文档、15 分钟内无法形成带文件与行号的事实结论。

## 目标

独立复核正式源代码里是否存在能绕过 `ChapterFinalCandidateCommitStageExecutorV1` → `ChapterFinalCandidateCommitCoordinatorV1`，直接发布 AI 候选章节或手工拼装最终提交草稿的实际调用路径。区分“当前被生产代码调用的路径”“仅定义但未接线的旧/底层 API”“正常人工编辑路径”，不得把类公开或文件存在本身误报为已经被调用。

## 当前现场与已有 WIP

- `ChapterFinalCandidateCommitStageExecutorV1` 已新增：READY 精确领取、PREPARING/COMMITTING 同 owner 恢复、SUCCEEDED 零提交，然后只调用最终协调器。
- `ChapterFinalCandidateCommitCoordinatorV1` 已新增：恢复、映射、策略复核后调用最终原子仓库。
- App 的前台生成服务此前只发现通知、控制、维护和状态观察，没有发现按 `GenerationPhase` 分发工作的总 runner。
- `ChapterGenerationCommitRepository`、`LibraryDao.commitChapterVersion` 等旧或底层写入口仍存在；必须依据真实调用点分类，不能仅凭名称判断为漏洞。
- 当前改动很多且未提交，不得清理、回退、格式化或改写任何文件。

## 必读资料

1. `AGENTS.md`
2. `docs/24-AI-DEVELOPMENT-PROTOCOL.md`
3. `docs/ai/CURRENT-CONTEXT.md`
4. 本任务包
5. `feature/generation/src/main/kotlin/app/zhijuan/feature/generation/ChapterFinalCandidateCommitStageExecutor.kt`
6. `feature/generation/src/main/kotlin/app/zhijuan/feature/generation/ChapterFinalCandidateCommitCoordinator.kt`
7. `feature/generation/src/main/kotlin/app/zhijuan/feature/generation/ChapterFinalCandidateCommitDraftMapper.kt`
8. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterFinalCandidateCommitRepository.kt`
9. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterGenerationCommitRepository.kt`
10. `core/database/src/main/kotlin/app/zhijuan/core/database/library/LibraryDao.kt`
11. `app/src/main/java/app/zhijuan/reader/generation/GenerationForegroundService.kt`

允许对 `app/src/main`、`core/database/src/main`、`feature/generation/src/main` 执行下列符号的精确 `rg`：上述四个 final 类型、`ChapterGenerationCommitRepository`、`commitChapterVersion`、`insertChapterVersion`、`ChapterVersionEntity(`、`GenerationPhase`。只读取直接命中的必要片段。

## 范围

允许修改：

- 无。只读审计，最终结论写在模型回交中。

明确不在范围：

- 任何代码、测试、文档、状态、Gradle、Room schema、Provider、UI 或脚本改动；
- 原项目 `D:\gptuser\projects\ai-novel-reader`；
- 构建、测试、模拟器、真实设备和 App 内真实 API。

## 不可破坏的约束

- 不得使用 `apply_patch`、`WriteAllText`、重定向、格式化器或任何写文件手段。
- 不得读取或输出 API Key、`.codex/deepseek-key.local`、小说正文、用户数据或 DeepSeek 历史日志。
- 必须以实际生产调用点为证据；定义、构造函数可见性、测试调用和注释都不能单独证明存在生产旁路。
- 必须区分 AI 自动生成最终提交与用户手工编辑/普通书库操作。
- 不宣布 TASK-059 完成，不更新正式状态。

## 实施要求

1. 列出 executor → coordinator → final repository 的当前生产依赖方向及每层实际调用点。
2. 搜索所有能插入 `ChapterVersionEntity` 或改变 `currentVersionId` 的 `src/main` 实现，并逐项分类用途与实际调用情况。
3. 明确回答：当前是否存在实际总 runner；新 executor 是否已有生产调用方；是否发现生产代码绕过 executor/coordinator 发布 AI 候选。
4. 对仍公开但目前未被调用的旧/底层入口说明它是“潜在未来误用面”还是“已有实际旁路”，不要混淆。
5. 如果发现真实旁路，给出最小修复建议和建议文件，但不得修改。

## 验收标准

- [ ] 每个结论都有仓库内文件与行号证据。
- [ ] 明确区分实际调用、仅定义、测试和人工编辑用途。
- [ ] 未修改任何文件，未运行构建/测试，未访问其他项目或密钥。
- [ ] 给出“发现真实旁路 / 未发现真实旁路 / 证据不足”三者之一的明确结论。

## 验证命令

不运行构建和测试。只在开始与结束各查看一次 `git status --short`，确认审计未产生新差异；已有 WIP 不得当作本任务产生。

## 回交格式

请严格按以下标题返回：

1. `结论`
2. `实际调用链`
3. `所有写入口分类`
4. `风险与最小建议`
5. `验证与未修改证明`
6. `需要 Sol 处理`
7. `假设`

不要宣布完整 TASK-059 完成，不要修改任何文件。
