# TASK-061 Phase 2B3B2A：动态创建编辑章记忆 Stage

## 任务身份

- 任务 ID：`TASK-061 / Phase 2B3B2A 动态创建编辑章记忆 Stage`
- 仓库根目录：`D:\gptuser\projects\ai-novel-reader-app2`
- 基准分支与 HEAD：`main` / `8ce7744`；只用于确认独立副本，不得回退或清理工作树
- 当前未提交改动：约 165 项 TASK-059～061、离线测试、隔离脚本、文档和报告 WIP，全部必须保留；只能增量续接 Phase 2B3B1
- 执行模型：DeepSeek（纯文本）

## 运行预算

- 推理等级：`max`
- 最长运行时间：15 分钟
- 累计 Token 上限：1,000,000
- 预计读取文件数与明确清单：不超过 18 个，仅限下方清单和这些文件直接引用的类型定义
- 预计执行命令/测试数：只读搜索不超过 15 次；允许修改后运行 2 组 JVM/编译测试，不运行模拟器、统一全量门禁或联网测试
- 提前停止条件：需要 schema v15、需要扩大到 tracking/aggregate、需要真实 Provider、需要访问其他项目、现有 WIP 与目标冲突、连续两次相同构建失败或 15 分钟内无法形成可审查差异

## 目标

在现有 schema v14 不变的前提下，新增 TASK-061 专用入口：根据不可变 execution/step 台账，原子、确定性地创建第一个真正需要执行的 `EDITED_MEMORY` 单步 Job/Stage。重建授权必须冻结在 Stage 的输入来源中并参与输入哈希；Provider-open 与章节记忆 commit 必须分别重验授权和权威来源。普通 `chapter-memory.v1` Stage 必须完全兼容。

本子阶段只创建并授权编辑章 memory Stage，不执行 Provider、不伪造输出、不推进 tracking/aggregate，也不宣布 TEST-033 或 TASK-061 完成。

## 当前现场与已有 WIP

- Phase 2B3B1 已有 schema v14 的 `chapter_edit_rebuild_execution` 和 `chapter_edit_rebuild_step`，二者不可更新/删除；步骤准备状态只有 `PENDING/SATISFIED`。
- `ChapterEditRebuildExecutionRepository.prepare` 已把 audited rewind、完整 current 范围、stable fence 和关键步骤放进同一个 Room 事务；它明确不创建 Job/Stage/Attempt/Usage。
- `ChapterMemoryExtractionJobFactory` 已创建普通单 Stage Job，Stage 输入严格绑定 current chapter version/content hash。
- `GenerationJobSetupRepository.create` 与 `GenerationDao.createJob` 已原子插入 Job/Stage；现有方法对重复主键会失败，专用入口必须在外层事务中实现精确 replay，而不是使用 REPLACE/IGNORE。
- `GenerationRequestAuditRepository.requireJobAllowsProviderOpen` 已依次调用章节记忆与 tracking 来源守卫。
- `ChapterMemoryExtractionCommitRepository` 已在提交时重验 Stage、Attempt、Usage、current version、来源和原子业务写入。
- 已有 `ChapterEditRebuildPlanDatabaseTest` 包含 prepare 三章夹具、精确 replay、身份冲突和整体回滚测试；必须复用并增量扩展。
- 当前缺口：没有持久化在 Stage 内的 rebuild authorization，没有确定性 Job/Stage 身份，没有专用创建入口，也没有 Provider-open/commit 对 v14 execution fence 的复核。

## 必读资料

1. `AGENTS.md`
2. `docs/24-AI-DEVELOPMENT-PROTOCOL.md`
3. `docs/ai/CURRENT-CONTEXT.md`
4. `docs/06-AI-GENERATION-SYSTEM.md` 第 27.6～27.7 节
5. `docs/09-DATA-MODEL.md` 第 26 节
6. `docs/10-STATE-MACHINES.md` 第 24 节
7. `docs/15-TEST-PLAN.md` TEST-033 与第 31 节
8. `core/database/src/main/kotlin/app/zhijuan/core/database/library/ChapterEditRebuildExecutionEntities.kt`
9. `core/database/src/main/kotlin/app/zhijuan/core/database/library/ChapterEditRebuildExecutionDao.kt`
10. `core/database/src/main/kotlin/app/zhijuan/core/database/library/ChapterEditRebuildExecutionRepository.kt`
11. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterMemoryExtractionJobFactory.kt`
12. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationJobSetupRepository.kt`
13. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationRequestAuditRepository.kt`
14. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterMemoryExtractionCommitRepository.kt`
15. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationDao.kt`
16. `core/database/src/androidTest/kotlin/app/zhijuan/core/database/ChapterEditRebuildPlanDatabaseTest.kt`
17. `core/database/src/test/kotlin/app/zhijuan/core/database/generation/ChapterMemoryExtractionJobFactoryTest.kt`

除上述清单和代码直接引用外，不得递归扫描整套文档、历史会话、备份、日志或无关模块；需要扩展读取范围时在回交中说明，不自行扩张。

## 范围

允许修改：

- 新增一个位于 `core/database/.../generation/` 或 `.../library/` 的 TASK-061 专用动态 Stage 仓库/授权文件。
- `ChapterMemoryExtractionJobFactory.kt`：为 memory Stage 增加严格、可选、向后兼容的 rebuild binding；普通 v1 输入必须保持原样。
- `GenerationRequestAuditRepository.kt`：在 Provider-open 前调用专用 rebuild 守卫。
- `ChapterMemoryExtractionCommitRepository.kt`：在写业务数据前再次调用同一授权语义的 commit 复核。
- `ChapterEditRebuildPlanDatabaseTest.kt` 和 `ChapterMemoryExtractionJobFactoryTest.kt`：新增最小正向、replay、并发/冲突、篡改和 legacy 兼容测试。

明确不在范围：

- `ZhijuanDatabase.kt`、`ZhijuanMigrations.kt`、Room schema、`LibraryDatabaseGuards.kt`；本阶段不升 schema v15。
- tracking、aggregate、context、consistency、总 runner、UI、WorkManager、预算预留实现。
- 修改 Phase 2B3B1 prepare/rewind/aggregate writer 的既有语义。
- 调用 App 内真实 Provider、读取密钥、生成小说内容、写物理设备。
- 更新正式完成状态、工作汇报、backlog 或 CURRENT-CONTEXT；这些由 Sol 审查后完成。

## 不可破坏的约束

- 项目隔离：每次修改和测试前确认 `git rev-parse --show-toplevel` 正好为 `D:/gptuser/projects/ai-novel-reader-app2`；不得访问或修改 `D:\gptuser\projects\ai-novel-reader`。
- 安全与隐私：binding、错误和 `toString()` 不保存或展开正文、提示词、API Key、连接密钥、完整用户意图或预算内容。
- 状态机与幂等：一个 execution/step 只能得到一个确定性 Job/Stage；精确 replay 返回既有行且零新增；不同 provenance、预算或用户意图撞到同一身份必须失败关闭。
- 数据库与事务：execution/step 重验、Job/Stage 创建和写后回读必须在同一 Room 外层事务；不得使用 REPLACE、IGNORE、删除后重建或可变 step 状态。
- 稳定身份：Job/Stage ID 必须由稳定 fence、step ordinal/type 和固定 policy 确定性派生，符合现有 128 字符标识符限制；不得使用当前时间或随机数参与身份。
- Stage binding：最少绑定 policy version、execution ID、stable fence、step ordinal、step type、chapter index、source chapter version/content hash；binding 必须进入 `inputSourcesJson` 和 `inputVersionHash`。建议对绑定 Stage 使用新的严格 root schemaVersion，同时让无绑定普通 v1 继续按原始键集合解析。
- Provider-open：绑定 Stage 必须重验 execution/step、Job/Stage 确定性身份、book/chapter/version/hash、完整 current 影响范围和“它是当前第一个允许启动的 PENDING 远程步骤”。普通 Stage 不得被误判为重建 Stage。
- Commit：在 memory 业务表写入前再次完成同等来源/授权复核；精确 replay 的已成功 Stage 仍可按既有 commit 语义重放。
- 兼容性：不得放宽现有严格 JSON、普通章节记忆来源 guard、Attempt/Usage/lease/format validation 或提交状态机。
- 当前 WIP：只做增量修改，不重排或格式化无关文件，不清理 165 项未提交改动。

## 实施要求

1. 定义可脱敏打印的 `ChapterEditRebuildStageBindingV1`（名称可等价）及严格 JSON 编解码；对绑定 memory Stage 使用明确 policy/schema 版本。
2. 定义专用命令和结果，例如 `createEditedMemoryStage(command)`：输入 execution ID、严格 JSON 的 user intent/budget snapshot、createdAt；输出脱敏的 Job/Stage 身份、ordinal 和 replayed。
3. 在外层事务中读取 execution 与全部 steps；当前仅接受第一个实际未满足的远程步骤为 `PENDING EDITED_MEMORY`。所有准备时 `SATISFIED` 前驱必须仍与权威基线一致；本子阶段若第一步已满足且下一步是 tracking，明确失败为“本入口不处理 tracking”，不得提前创建 tracking。
4. 重新读取从 first affected 到 last affected 的 current chapter/version/content hash，必须与 v14 step/source fence 一致；目标 summary 在新建时必须不存在。createdAt 不得早于 execution/step/源版本时间。
5. 用现有 `ChapterMemoryExtractionJobFactory` 和 `GenerationJobSetupRepository` 创建单步 Job/Stage。创建后回读并严格比较 Job/Stage/binding；若确定性身份已存在，仅在全部字段精确相同时返回 replay，否则失败。
6. Provider-open 和 commit 对绑定 Stage 调用专用授权守卫。守卫必须对普通无 binding Stage返回“不适用”，不能破坏 legacy。
7. 测试至少覆盖：普通 v1 JSON/哈希不变；绑定 JSON 严格解析与任一字段篡改失败；三章 prepare 后只创建 1 Job+1 Stage、Attempt/Usage 仍为 0；同命令 replay 零新增；不同预算/意图/时间或预占身份冲突失败；current 范围变化后创建失败且零 Job/Stage；Provider-open/commit 守卫拒绝篡改、错误 execution/step 或非第一步；错误/默认字符串脱敏。
8. 允许为了编译做最小可见性调整；不得写虚假 Fake Provider 输出来绕过真实提交证据。

## 验收标准

- [ ] 正向行为：v14 execution 的首个 PENDING edited-memory 步骤可原子创建一个确定性单步 Job/Stage。
- [ ] 失败路径：来源、fence、ordinal、type、current 范围、时间、现有身份任一不一致时零新增且失败关闭。
- [ ] 双重门禁：Provider-open 和 memory commit 都复核专用 rebuild authorization；普通 memory Stage 行为不变。
- [ ] 幂等恢复：同一命令重复或并发调用最终只有一份完全一致的 Job/Stage，replay 不产生 Attempt/Usage。
- [ ] 安全隐私：binding、错误与字符串表示不含正文、密钥或完整 JSON 载荷。
- [ ] 向后兼容：现有普通 v1 factory/parse/hash 测试继续通过；Room schema 保持 14。
- [ ] 边界诚实：未创建 tracking/aggregate，未调用 Provider，未宣布 TEST-033 或 TASK-061 完成。

## 验证命令

```powershell
$root=(git rev-parse --show-toplevel).Trim(); if($root -ne 'D:/gptuser/projects/ai-novel-reader-app2'){ throw "Wrong git root: $root" }
./gradlew :core:database:testDebugUnitTest --tests "*ChapterMemoryExtractionJobFactoryTest" --offline --no-daemon --console=plain
```

```powershell
$root=(git rev-parse --show-toplevel).Trim(); if($root -ne 'D:/gptuser/projects/ai-novel-reader-app2'){ throw "Wrong git root: $root" }
./gradlew :core:database:compileDebugAndroidTestKotlin --offline --no-daemon --console=plain
```

不运行真实 Provider、物理设备或未隔离模拟器。未运行的验证必须在回交中写明原因，不能写成通过。

## 回交格式

请严格按以下标题返回：

1. `完成内容`
2. `修改文件`
3. `验证`
4. `未完成/风险`
5. `需要 Sol 处理`
6. `假设`

不要宣布整个 Phase 2B3B2、TEST-033 或 TASK-061 完成，不要更新正式完成状态；由 Sol 根据实际差异和测试证据确认。
