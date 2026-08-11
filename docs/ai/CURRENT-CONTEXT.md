# app开发2 当前 AI 交接现场

> 更新时间：2026-08-09  
> 用途：供 Sol、DeepSeek 和后续 Codex 任务在开发前快速建立同一事实基线。

## 1. 仓库身份

- 唯一项目根目录：`D:\gptuser\projects\ai-novel-reader-app2`
- Git 分支：`main`
- 记录本文时的 HEAD：`8ce7744`
- Git remote：无
- 本副本与 `D:\gptuser\projects\ai-novel-reader` 完全隔离，不得互相同步或混用工作区。

HEAD 只用于识别本文写入时的现场，不是要求执行者回退到该提交。开始工作时必须重新查看当前 HEAD、`git status --short` 和相关差异。

## 2. 当前里程碑

- M0：完成。
- M1：进行中。
- M2：进行中。
- `TASK-059 有限修订与提交门禁`：正式状态为完成。
- `TASK-060 中文 FTS 多路召回`：正式状态为完成。
- `TASK-061 编辑后派生失效和重建`：正式完成。Phase 2B3B2E 已把 schema v15 retirement-bound Provider→tracking→aggregate 泛化到显式 ordinal 4/6/8…，并完成 TEST-033 10 章场景与旧派生上下文排除。
- `TASK-062 脱敏生成时序、基准时钟和报告器`：正式完成。schema v16、phase 隔离、boot-bound monotonic duration、失败终态、首段有限探测和 BODY Fake 流接线已通过双 API 全量回归。
- `TASK-063 固定延迟/慢流/断流 Fake Provider 性能夹具`：正式完成。独立 Fake 模块、虚拟分钟级慢流、断流/UNKNOWN/取消、失败可见分位数和 20 个参考 BODY 负载已通过双 API。
- 正式下一阶段：TASK-064 全阶段 dispatcher 与持久 total runner（Fake only）。
- 真实生成 API 调用基线：0；继续开发默认使用本地规则、Fake Provider 和离线测试。
- 物理设备项目仍是发布门禁，不得把模拟器证据描述成实机证据。

完整的测试计数和构建基线以 `docs/22-WORK-STATUS.md` 为准。

## 3. TASK-059 的既定目标

TASK-059 必须实现并证明：

1. `BLOCKER/MAJOR` 可触发有限次数修订，`MINOR/NONE` 不被错误升级；
2. 比例模式最多 1 次自动修订，细写模式最多 2 次自动修订；
3. 每次修订生成新的候选版本标识和内容 hash；原候选的记忆、时间线/伏笔和一致性检查结果不能复用；
4. 修订后重新执行记忆提取、故事追踪和一致性检查；
5. 正文不变、候选循环、长度失败或次数耗尽时进入明确的 `NEEDS_ACTION`，禁止无限互改；
6. 最终正文、摘要/人物事件/事实、时间线/伏笔、ConsistencyReport、FINAL Usage、Stage、Job 和书籍进度在同一事务中提交；
7. 候选 `ChapterVersion` 在最终提交前不得成为正式版本；外键失败、并发冲突或中途故障必须整体回滚；
8. 精确 replay 不重复发布，不覆盖用户已经编辑过的当前版本。

依据：`01-PRD.md`、`03-USER-FLOWS.md`、`09-DATA-MODEL.md`、`10-STATE-MACHINES.md`、`15-TEST-PLAN.md`、`19-IMPLEMENTATION-BACKLOG.md`。

## 4. TASK-059 实现与收口记录

以下文件已经存在，必须先审计，不得从零重写：

| 区域 | 文件 | 当前可见意图 |
|---|---|---|
| 修订策略 | `core/task/src/main/kotlin/app/zhijuan/core/task/ChapterRevisionPolicy.kt` | 次数上限、严重度、循环/正文不变/长度失败和确定性修订计划 |
| 策略测试 | `core/task/src/test/kotlin/app/zhijuan/core/task/ChapterRevisionPolicyTest.kt` | 比例/细写上限、循环、正文不变、稳定排序等 |
| 请求构造 | `feature/generation/src/main/kotlin/app/zhijuan/feature/generation/ChapterRevisionRequest.kt` | 候选正文、报告、问题、场景和 schema 的纯文字绑定 |
| 流式协调 | `feature/generation/src/main/kotlin/app/zhijuan/feature/generation/ChapterRevisionStreamingCoordinator.kt` | 修订结果校验和无法继续时的状态结算 |
| 请求测试 | `feature/generation/src/test/kotlin/app/zhijuan/feature/generation/ChapterRevisionRequestTest.kt` | 请求确定性、零温度、无需修订和成人场景门禁 |
| 候选封存 | `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterCandidateArtifactSealRepository.kt` | 候选正文/记忆/追踪/检查 artifact 的来源绑定和阶段推进 |
| 修订结算 | `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterRevisionOutcomeRepository.kt` | 正文不变、循环等质量失败进入 `NEEDS_ACTION` |
| 最终提交 | `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterFinalCandidateCommitRepository.kt` | 正文、派生数据、Usage、Stage/Job 的最终事务 |
| 数据库测试 | `core/database/src/androidTest/kotlin/app/zhijuan/core/database/ChapterFinalCandidateCommitDatabaseTest.kt` | 原子发布、外键回滚、精确 replay 和并发去重 |

这些文件构成 TASK-059 的起始 WIP；下列阶段记录说明它如何被继续补齐。最终完成状态只以本节末尾的双 API、Release/R8 和统一门禁证据为准。

2026-08-04 已完成 TASK-059 第一阶段“候选 Stage Provider-open 来源门禁”：候选 MEMORY、TRACKING、CONSISTENCY 与 REVISE BODY 会逐级验证同 Job/章节的已封存直接前驱；损坏 JSON、错误 next Stage、陈旧 hash/revision 和跨章节来源均在领取发送权前失败。API 35 专项 10/10、统一离线门禁和安全扫描通过，真实 Provider 0 次。完整 TASK-059 仍未完成，详见工作汇报 64。

同日继续完成修订正文响应后的质量失败结算：只有过短、正文未变化和候选循环可使用该入口；Stage/Job/最终 Usage 原子进入 `NEEDS_ACTION`，相同原因可精确重放，冲突原因拒绝且不会发布正式章节。API 35 专项现为 11/11，统一离线门禁通过，详见工作汇报 65。

同日继续完成一致性检查后的请求前有限分流：`ChapterRevisionPolicyV1` 是接受、自动修订和额度耗尽的唯一决策源；接受只创建本地最终提交 Stage，自动修订只创建绑定当前候选与已封存一致性前驱的 REVISE Stage，额度耗尽则把一致性 Stage、Job 和最终 Usage 原子结算为 `NEEDS_ACTION`，不创建后继、不发布候选版本。完整策略输入及结果形成持久 route binding hash，同结果但问题集合或其他依据变化的重放也会拒绝。API 35 专项现为 13/13，371 项统一离线门禁、安全扫描和备份排除检查通过，详见工作汇报 66。

随后接通修订请求与新候选的持久交接：REVISE Stage 新增精确 request source binding，Provider-open 会同时核对一致性前驱、策略 route hash 和 Attempt input hash；成功响应由 `ChapterRevisionCandidateRepositoryV1` 重新读取加密 artifact、严格解码并复算码点数，再核对策略、历史、来源候选与新 hash，原子封存新的 BODY 并建立新 MEMORY Stage。修订结果指纹覆盖正文长度、候选历史和来源，artifact 清理后的精确 replay 仍可用，改策略或改长度则拒绝。API 35 专项现为 16/16，371 项统一离线门禁、安全扫描和备份排除检查通过，详见工作汇报 67。

同日继续完成修订后派生链的生产封存接线：`ChapterCandidateDerivedStagePersistenceCoordinatorV1` 把已审计的 MEMORY/TRACKING 结果、冻结请求、最终 Usage 和当前候选身份装成唯一数据库封存草稿；修订结果 binding 必须在 MEMORY→TRACKING→CONSISTENCY 中逐段保持一致，只有一致性分流点可以生成新的策略 route binding。封存仓库成功或精确 replay 时直接返回自身生成的最终 artifact 证据，避免上层根据 opaque permit 手工拼装。新增 4 项 JVM，API 35 专项 17/17，371 项统一离线门禁、安全扫描和备份排除检查通过，详见工作汇报 68。完整 TASK-059 仍未完成。

随后装配一致性结果的唯一生产分流入口：`ChapterCandidateConsistencyRoutingCoordinatorV1` 从同一冻结候选、已接受模型报告、本地报告、场景契约和候选历史生成 gate、有限策略输入、精确 revision request 与数据库路线；修订 seed 必须与当前一致性 Job 的 `generationId` 相同。MINOR、MAJOR、额度耗尽、候选错配和跨 Job seed 均有 JVM 证据，独立重跑 8/8。

同日新增 `ChapterFinalCandidateCommitDraftMapperV1`：它把当前正文/lineage、MEMORY/TRACKING/CONSISTENCY 派生草稿及四类唯一 artifact evidence 确定性组装成最终提交草稿，只接受 ACCEPT gate，并核对候选版本、同书派生行、正文与派生输出 hash、Stage、报告 hash 和派生时间。DeepSeek 在 30 分钟上限、无总 Token 上限、`max` 推理下交付两个新文件；后续加固补丁因 Windows 原生 `apply_patch` 的分离可写根限制改由 Sol 根据 DeepSeek 设计落地。最终映射器 JVM 7/7、371 项统一离线门禁、安全扫描和备份排除检查通过，详见工作汇报 70。完整 TASK-059 仍未完成。

同日新增 `ChapterFinalCandidateArtifactRecoveryCoordinator`：进程重启后只通过受保护 artifact store 的 lease，按 BODY→MEMORY→TRACKING→CONSISTENCY 固定顺序读取四类明文；逐项核对 descriptor 身份、类型、revision、raw hash、canonical hash，并复用现有三套严格结构化 Parser，结果对象不保留 ByteArray，错误和默认字符串不包含正文/JSON。DeepSeek 在只读补丁提案模式、25 分钟上限、无总 Token 上限和 `max` 推理下约 20 分钟交付完整提案；Sol 应用后修正 fake lease 复用数组的测试隔离缺陷。新增 JVM 5/5、371 项统一离线门禁、安全扫描和备份排除检查通过，详见工作汇报 71。完整 TASK-059 仍未完成。

同日把 ACCEPT 路线的 final COMMIT Stage 来源封套升级为 v2：冻结 expected current version、场景策略给出的自动修订上限、完整候选 hash 历史、CONSISTENCY 前驱和 route binding；最终仓库在任何正式行写入前重新解析 input hash 与 exact keys，并与提交草稿和四段封存链逐项核对。`AcceptCandidate` 现在显式携带同一策略计算出的上限，数据库层不复制模式判断。DeepSeek 在只读提案模式、20 分钟上限、无总 Token 上限和 `max` 推理下约 9 分 49 秒交付；Sol 将不兼容封套版本从 v1 修正为 v2。JVM 路由 8/8、策略 7/7，API 35 最终候选专项 19/19，371 项统一门禁、安全扫描和备份排除通过，详见工作汇报 72。完整 TASK-059 仍未完成。

同日新增 `ChapterFinalCandidateRecoveryRepository`：它在单个只读 Room 事务中从 final v2 前驱反向恢复唯一 CONSISTENCY→TRACKING→MEMORY→BODY 链，核对同 Job/书/章节、Stage 状态和 direct predecessor/next Stage、每段最后成功 Attempt、FINAL Usage、artifact evidence 与三份严格模型快照；初始 BODY 与 REVISE BODY 分别验证，并把修订正文的旧 hash 绑定到完整 history 的上一项。第一次 DeepSeek 三文件任务因反复读取大文件在 25 分钟超时且没有最终补丁；拆为单一新文件后约 5 分 20 秒交付完整提案。Sol 修正提案中 suspend DAO 调用和初始 DRAFT 被误解析为修订 binding 两项错误，并补同书章节门禁与修订链测试。API 35 最终候选专项 23/23，371 项统一门禁、安全扫描和备份排除通过，详见工作汇报 73。完整 TASK-059 仍未完成。

同日新增 `ChapterFinalConsistencyMappingSnapshotCodecV1`：它只从已绑定的一致性请求和同一个 routing spec 捕获本地报告、expectation、场景契约、原请求 source binding 与三个有限修订数值，输出 48 KiB 内的严格 canonical JSON，为 64 KiB final Stage 外层封套保留余量；根、报告、问题、expectation 和场景对象均按 exact keys/强类型/稳定排序解析，并复核正文 hash/码点数、场景 hash、标准集合和过程节点集合。快照明确排除正文、人物名称、evidence payload、提示词、API 与模型输出正文。DeepSeek 在只读单文件任务中以 `max` 推理约 7 分 30 秒正常交付；Sol 修复 enum 泛型推断编译问题，并让 capture 立即复走严格解析校验。新增 JVM 7/7，371 项统一门禁、安全扫描和备份排除通过，详见工作汇报 74。完整 TASK-059 仍未完成。

随后把 ACCEPT final COMMIT Stage 来源封套升级为 v3：生产一致性协调器只在 `AcceptCandidate` 时捕获映射快照及 canonical hash，数据库要求快照、hash 与原请求 source binding 同时存在，并以嵌套 JsonObject 写进 Stage；REVISE/NEEDS_ACTION 禁止夹带。final Stage 整体仍限制 64 KiB，v2、缺/多字段、错误类型、错 hash/binding 和 stale input hash 均失败关闭。恢复仓库与最终提交仓库额外把 v3 request binding 对回 CONSISTENCY seal。DeepSeek 单文件只读任务以 `max` 推理约 4 分 44 秒交付，Sol 完成 feature/恢复/提交接线及原子负例。JVM 相关 15 项、API 35 最终候选专项 25/25、371 项统一门禁通过，详见工作汇报 75。完整 TASK-059 仍未完成。

同日新增 `ChapterFinalCandidateCommitCoordinatorV1`：它是 final Stage 的唯一纯本地提交入口，严格解析 v3 快照、恢复四类受保护 artifact、调用既有 memory/tracking/consistency mapper、重建有限修订策略并复核 final route，全部成功后才把 PREPARING 推进到 COMMITTING，再调用既有最终 SQLCipher 事务；COMMITTING 重启按持久时间重建，READY/SUCCEEDED 不读取 artifact。恢复结果新增独立的 candidate route binding，避免与最终接受 binding 混用。DeepSeek 在 `workspace-write`、`max`、20 分钟、无总 Token 上限下约 8 分 55 秒完成单文件实现和编译；Sol 加固可测试边界并补 6 项 JVM。相关 JVM 33/33、API 35 最终候选专项 25/25、371 项统一门禁通过，详见工作汇报 76。完整 TASK-059 仍未完成。

同日新增 `ChapterFinalCandidateCommitStageExecutorV1`：READY 时精确领取一次 final Stage lease 并核对完整持久证据，PREPARING/COMMITTING 只允许同一 owner 使用原 token 恢复，SUCCEEDED 零提交，其他状态和陈旧证据失败关闭；取得 token 后只调用唯一最终协调器，不复制恢复、映射、策略或事务。审计确认当前 App 没有按 phase 分发的总 runner，因此本阶段只交付未来 runner 可调用的专用入口，不伪装整 App 已接通。DeepSeek 以 `workspace-write`、`max`、15 分钟、无总 Token 上限约 2 分 57 秒完成单文件编译，但首次补丁失败后使用 `WriteAllText`，违反项目编辑工具规则；Sol 逐行审查并通过 `apply_patch` 重新落地/加固。执行器 JVM 8/8、最终提交相关链 41/41、371 项统一门禁通过，详见工作汇报 77。完整 TASK-059 仍未完成。

随后完成生产旁路只读审计：正式 `src/main` 未发现绕过 executor/coordinator 发布 AI 候选的实际调用点；新链内部已接好但头部无总 runner，旧 `ChapterGenerationCommitRepository` 与 `LibraryDao.commitChapterVersion` 同样零生产调用，属于潜在未来误用面而非当前旁路。DeepSeek 以 `read-only`、`max`、15 分钟、无总 Token 上限约 3 分 35 秒独立复核，开始/结束 Git 状态一致，Sol 再次核对关键符号和调用关系，详见工作汇报 78。

最终全量回归揭示并修复旧 DRAFT Stage 合法数组来源被候选 binding 误判的兼容问题；合法非对象继续走未绑定旧链，畸形 JSON 和当前候选 policy object 保持严格失败关闭。Compose 全量的固定截图名冲突和 IME/LazyColumn 测试竞态也以测试专用改动消除。API 35/API 30 均为 App 45、Database 114、Generation 28，共 187/187；Release/R8、467 项 JVM、371 项统一门禁、安全扫描、备份排除与 diff 检查通过。TASK-059 由 Sol 在当前模块边界正式标记完成，详见工作汇报 79；当前 App 仍无总 runner，不能描述为整 App 已接通。

## 5. 下一步边界

1. TASK-062 已完成测量契约和 BODY Fake 接线；下一步 TASK-063 只建立可重复的固定延迟、慢流和断流 Fake 夹具及 P50/P95/最慢值报告，不提前实现 total runner 或真实 Provider 基准。
2. TASK-059 的 COMMIT_CHAPTER executor 是未来总 runner 的唯一接入点；不得恢复使用旧 `ChapterGenerationCommitRepository` 发布 AI 候选。
3. 当前没有总 runner，生成链仍不是整 App 可自动执行状态；在后续调度任务完成前必须持续明确这一限制。
4. 继续只使用本地规则、Fake Provider 和项目专用模拟器；未经用户另行授权不调用 App 内真实生成 API，不向物理设备写入。

## 6. 当前未提交的隔离文件

记录本文时，仓库已有下列 app开发2 专用 DeepSeek 隔离改动：

- `.gitignore`
- `.codex/`
- `scripts/get-deepseek-key.ps1`
- `scripts/set-deepseek-key.ps1`
- `scripts/start-deepseek-codex.ps1`

它们属于当前工作现场，不是 TASK-059 的代码。后续执行者必须保留，不得清理、回退或复制到其他项目。

## 7. DeepSeek 使用状态

- 项目级模型：`DeepSeek-V4-Flash`
- 输入能力：仅文本
- 密钥：不在仓库文档中保存；是否已设置必须通过本地脚本验证，不得打印密钥
- 安全验证：`scripts/start-deepseek-codex.ps1 -ValidateOnly`
- 启动入口：`scripts/start-deepseek-codex.ps1`
- 默认运行门禁：Windows `unelevated` restricted-token + `workspace-write`，额外仅允许 app2 的 Codex 会话目录、临时目录和共享 Gradle 缓存，15 分钟，1,000,000 累计 Token，`max` 推理等级（用户 2026-08-04 明确要求后续 DeepSeek 统一使用最高推理强度）。
- 默认上下文窗口：131,072 Token；100,000 Token 时触发自动压缩，防止单次任务无限膨胀。
- 并发门禁：同一时间只允许一个 app开发2 DeepSeek 任务。
- 本地运行目录：Codex 会话在 `D:\gptuser\cache\codex\ai-novel-reader-app2`，运行日志在 `D:\gptuser\logs\ai-novel-reader-app2\deepseek`。
- 用户已在 2026-08-04 持续授权 Sol 按任务需要调用项目隔离的 DeepSeek V4 Flash 编码模型，无需逐次确认。该授权不包含“织卷”App 内部的真实生成调用或其他项目。

DeepSeek 启动后的第一项工作应是读取根目录 `AGENTS.md`、`docs/24-AI-DEVELOPMENT-PROTOCOL.md`、本文件和收到的任务包。

2026-08-04 已完成启动器修复和真实基础设施冒烟测试：最终运行的有效沙箱为 `workspace-write`，DeepSeek 在受限 app2 临时目录成功完成写入、精确读回和删除，退出码为 0，累计 41,270 Token；探针已删除，仓库无额外业务改动，日志未发现密钥形态内容，运行结束后无 DeepSeek 残留进程。此前两次模型请求虽正常返回 0，但工具实际被 Windows 自动降级的 `read-only` 策略拦截；因此后续验收必须同时检查最终回交、stderr 和有效 sandbox，不能只看进程退出码。操作说明与故障退出码见 `docs/ai/DEEPSEEK-RUNBOOK.md`。

## 8. TASK-060 实现与完成记录（2026-08-04 至 2026-08-05）

- 第 1A 阶段已把正式 SQLCipher 书库升级到 schema v9，并加入 `memory_search_document`、FTS4 外部内容表、同步触发器、DAO 和 8→9 迁移；见工作汇报 80。
- 第 1B 阶段已完成六类正式记忆源的隐私受限索引文档、稳定替换/删除 writer，以及初始 Bible、章节记忆、故事追踪、最终候选发布和旧版兼容发布的同事务接线；见工作汇报 81。
- API 30 不支持当前用法中的显式 `AND` FTS 增强解析，查询已改为所有目标版本可用的空格隐式 AND；不得改回显式 `AND`。
- 当前 core/database API 30 全量为 117/117；分阶段生成端到端专项为 12/12；正式检索 JVM 为 18/18。
- 2026-08-05 二次审计后先完成跨任务安全门禁修复：Provider `sk-` 规则增加左边界，不再把 `task-059/task-060` 文件名误报为密钥；源码扫描不再排除 `reports/**`；新增 4 项 PowerShell 回归覆盖普通任务报告、报告 canary、Provider 样式和 APK canary。统一离线门禁现在实际执行 `assembleRelease`，797 个 Gradle task、源码与 5 个 APK 安全扫描、备份排除均通过，详见工作汇报 83。
- 第 1C 阶段已完成 schema v10 按书回填标记、六类来源稳定 keyset 分页、单外层事务重建、失败整体回滚和首次上下文前 ensure-ready；合法 40,000 字符旧 JSON 叶子兼容问题也已由全量回归发现并修复。API 35/API 30 的 core/database 均为 121/121，统一门禁 797 tasks、Release/R8、安全扫描和备份排除通过，详见工作汇报 84。
- 第 2A 阶段已完成纯函数召回探针编译器：目标章→用户补充→目标弧固定顺序、严格 JSON 字符串提取、单 FTS token、跨路由去重、共享 256 叶子与 128 探针等硬上限；JVM 14/14，详见工作汇报 85。
- 第 2B1 阶段已完成正式数据库命中累计：编译超出 128 的正常输入改为有界省略并返回遗漏数；三条路线会先保留 32/16/16 个执行名额，避免详细章计划挤掉用户补充和目标弧。召回在单 Room 事务内逐探针查询，每探针 16、总查询 64、最终文档 128，按文档/来源身份去重并累计三路命中，排除其他书和未来章节，输出固定排序、完整计数与脱敏 SHA-256 查询指纹。core/database JVM 65/65，API 35/API 30 各 126/126，详见工作汇报 86。
- 第 2B2 阶段已完成六类权威 hydration：召回的最多 128 个派生指针按 source type 去重分组，在单 Room 事务内最多六次批量重读权威行；Story/Summary/Event/Fact/Timeline/Foreshadow 分别复核归档/有效状态、当前 Bible 或当前章节版本、严格早于目标章、未解决状态，并通过唯一 factory 重派生后除 rowid 外逐字段精确比较。失效、缺失或不匹配指针只被有证据剔除并要求索引重建，不使整章失败；结构损坏、重复来源与跨书输入仍失败关闭。core/database JVM 65/65，API 35/API 30 各 131/131，详见工作汇报 87。
- 第 2C1 阶段已完成权威记忆路线选择：在一个 Room 事务中先加载 current HARD_CANON、来源 current 且已到期的未解决伏笔和最近 8 个 current summary，再运行 2B1/2B2 FTS；按强制→最近→FTS 与 source identity 去重，core 项被 FTS 命中时只累计三路命中证据而不移动。普通章节 STORY_CANON 不再全部变成不可裁剪硬事实；强制+最近超过 512 时返回空选择和明确 overflow，完全跳过 FTS。core/database JVM 65/65，API 35/API 30 各 136/136，详见工作汇报 88。
- 第 2C2 阶段已把 2C1 结果接入现有 `ChapterContextCandidate`、确定性预算和不可变 manifest：HARD_CANON/到期伏笔/上一章摘要保持必需，普通 STORY_CANON、旧摘要/事件/时间线/开放伏笔只在 FTS 相关时进入；每个实体属性只保留最新事件作为当前状态。组装发现坏指针时自动完整重建一次；强制超界或重建后仍损坏时联网前阻断。Provider-open 会重新执行完整权威选择、候选映射与预算，payload hash 或完整路线证据任一变化都拒绝旧快照。core/database JVM 65/65，API 35/API 30 各 139/139，详见工作汇报 89。
- 最终固定集阶段在正式加密 `ZhijuanDatabase` 写入 10,000 条生产索引文档，20 个固定中文人物/地点/物品/伏笔词在 API 30/API 35 均为 20/20；无关查询为空、replay 一致、三路 41 探针仍取回全部目标。热查询中位约为 API 30 6.07 ms、API 35 4.35 ms。
- 固定集发现原 `gXXXX_YYYY` 双字 token 会被 Android FTS4 按下划线拆开，导致不相邻汉字可能误命中。现改为全字母数字 v2 token，回填 schema 升到 2；旧 v1 标记会自动整书重建。“甲乙”不再命中“甲丙乙”，v1→v2 自动重建在双 API 通过。
- TASK-060 已于 2026-08-05 完成：core/database JVM 65/65，API 30/API 35 数据库全量各 143/143，详见工作汇报 90。下一步为 TASK-061。

## 9. TASK-061 当前记录（2026-08-06）

- Phase 1 新增 `ChapterUserEditRepository` 和编辑专用 CAS。命令显式绑定 book/chapter/expected current/new version，正文 hash 只在 repository 内计算；事务顺序为失效前捕获搜索 identity、旧派生 stale、保存新不可变 `USER_EDIT` 版本、CAS 为 `EDITED/UNKNOWN`、删除旧搜索来源。
- 精确 replay 要求同新版本 ID、parent、source、正文/hash 与 current 状态完全一致；同 ID 不同正文、跨书、错章、过期 current、倒退时间和非完成章节均失败关闭。generic `LibraryDao.commitChapterVersion(USER_EDIT)` 暂保留为低层测试夹具，生产入口必须使用新 repository。
- TEST-032 使用 10 个已提交章节：编辑第 3 章后旧版本保留，第 4–10 章正文/current 不变，派生、聚合、后续上下文/报告和旧 FTS 按既定失效图处理。API 30/API 35 定向各 3/3、数据库全量各 146/146；统一离线门禁 797 tasks、Release/R8、安全扫描和备份排除通过，详见工作汇报 91。
- Phase 1 没有创建重建 Job、调用 Provider 或伪造新摘要/索引。Phase 2 必须解决存在后续已提交章节时 tracking 重建会拒绝的问题，以章节顺序重建受影响派生链并完成 TEST-033；在此之前 TASK-061 不得标记完成。
- Phase 2A 新增 `ChapterEditRebuildPlanRepository`：单 Room 事务批量读取编辑点至最新章的 current version/正文 hash/状态和 tracking，生成稳定排序的冻结章节、步骤、依赖、blocker 与 `planHash`；`requireCurrentMatches` 在执行前重建完整计划，任一 current 或派生状态变化都会拒绝旧计划。
- 10 章编辑第 3 章的固定结果是 32 步：编辑章 memory 1 项 READY，其余 31 项因派生唯一槽、tracking 顺序保护、aggregate 无重建 writer 或依赖阻塞而 BLOCKED；17 项标记为将来可能需要 Provider，后 7 章正文/current 保留。计划阶段 Job/Stage/Attempt/Usage 和业务表写入均为 0。
- DeepSeek Phase 2A 只读运行 `20260805-133041-8930ec46` 使用 `max` 推理约 10 分钟，累计 Token 820,370（缓存 673,024，输出 68,212）。Sol 补回其提案遗漏的后续章节 tracking 步骤，并将逐章查询/O(n²) 依赖扫描改为两次批量查询和 O(1) 前驱引用。
- API 30/API 35 定向各 4/4、数据库全量各 150/150；统一离线门禁 797 tasks、Release/R8、安全扫描和备份排除通过。Phase 2A 没有完成 TEST-033；下一步必须先设计派生历史版本化和伏笔状态 replay，再接有序执行。
- Phase 2B1 将正式 Room schema 升到 v11：summary/tracking/aggregate/transition 的业务槽允许多代 STALE，数据库触发器保证每槽最多一个 VALID；七类派生历史只允许 `VALID → STALE`，禁止恢复、内容/来源篡改与删除。authority 查询只返回 VALID，显式 history 查询稳定返回全部代；Phase 2A tracking 批量读取同时限定 current version。
- DeepSeek 大审计运行 `20260805-140811-eb0471c5` 在 `max` 推理和 25 分钟硬上限结束，只有上下文审计、没有最终补丁或工作树写入；窄差异复核 `20260805-145447-3010979f` 约 5 分 55 秒完成，指出 batch tracking 混读历史风险，Sol 已改为 VALID+current 查询并进一步补齐七类 DELETE/不可变保护。
- Phase 2B1 定向测试在 API 30/API 35 各 18/18，数据库全量各 152/152；统一离线门禁 797 tasks、496 项 JVM、Release/R8、源码与 5 APK 安全扫描、备份排除和 diff 检查通过。App 内真实 Provider 调用 0、物理设备写入 0。
- Phase 2B1 没有实现 `foreshadow_item` rewind、aggregate 重建 writer、跨章有序执行或 TEST-033；这些边界继续保持阻塞，当前 App 仍无总 runner。
- Phase 2B2A 将正式 Room schema 升到 v12：每条伏笔 transition 唯一绑定完整 after-state revision，严格 codec 覆盖 `foreshadow_item` 全字段并以规范 JSON/SHA-256 校验；两条生产提交路径在同一事务内共用 post-CAS writer。revision 只允许 `VALID → STALE`，必须先于 transition 失效；v11 旧数据不伪造快照。
- DeepSeek 只读审计 `20260805-225927-6ce8af71` 使用 `max` 推理、累计 302,353 Token、无工作树写入；其确认无 P0 并指出 final replay 强比 later-current 的 P1。Sol 修复该误拒绝，并进一步阻止旧 Stage 用旧章序覆盖 later-current 伏笔索引。
- Phase 2B2A 在 API 30/API 35 的 migration+memory 定向各 20/20、final commit 各 27/27、tracking E2E 各 3/3，core/database 全量各 155/155。实际 rewind、区间 replay、aggregate writer、TEST-033 和总 runner 均未完成；App 内真实 Provider 调用 0、物理设备写入 0。
- Phase 2B2B 将正式 Room schema 升到 v13：新增不可变 rewind 审计和 plan 唯一约束；`ForeshadowProjectionRewindRepository` 在一个事务中重验完整计划，读取编辑点至最新章的全部 transition，选择编辑点前 current-version 的最后可信 revision，按 revision→transition 失效区间，以全字段 CAS 恢复基线、把区间新生 item 保持/置为 STALE，并删除/重建受影响 FTS。
- legacy 缺基线只有区间第一条是 `PLANT(null → PLANTED)` 时才能证明编辑前不存在；其他操作失败关闭。执行末尾复算 before/baseline/after 集合 hash 并写审计；同一 ID 精确 replay 零写入，同 plan 不允许另一 ID 重复占用。Phase 1 已经标为 STALE 的新生 item 保留原失效时间。
- DeepSeek 只读代码审计 `20260805-235105-afbf576a` 使用 `max` 推理，约 12 分 46 秒，总 Token 2,319,118（缓存输入 2,061,440，输出 72,473），无工作树写入，结论为无 P0/P1。Sol 采纳并修复“已 STALE item 时间污染”和“审计时间早于编辑版本”两项 P2，并补回归。第一次尝试因外层 60 秒超时被中止，残留子进程已显式停止，未使用该次不完整结果。
- Phase 2B2B 在 API 30/API 35 的 migration+rewind 定向各 12/12，core/database 全量各 159/159；统一离线门禁 797 actionable tasks，Debug/Release/Lint/R8、JVM、安全扫描脚本、源码与 5 APK 安全扫描及备份排除均通过。App 内真实 Provider 调用 0、物理设备写入 0。aggregate writer、跨章有序 Job/Stage、TEST-033 和总 runner 仍未完成。
- Phase 2B3A 新增 `AggregateStateWriterRepository` 与严格 `zhijuan.aggregate-state.v1`：只从 current-version-bound 最新实体属性、当前活动伏笔和同章有效 tracking 重算有界 CURRENT_STATE；来源绑定 tracking projection/stage 及四类 hash。旧槽头转 STALE 后插入确定性新代，精确 replay 零写入，并发不同证据失败关闭；计划升级 v2 并能把严格匹配的新头识别为 `ALREADY_SATISFIED`。
- DeepSeek 只读设计审计 `20260806-002111-4d3f22a9` 使用 `max` 推理约 9 分 47 秒，总 Token 1,551,234（缓存输入 1,293,696，输出 57,333，推理 35,967），无工作树写入。Sol 采纳权威重算和无需 schema 迁移的方向，但拒绝直接应用其过宽 payload 与遗漏写后已满足判定的补丁，并补上 tracking 代次绑定。
- Phase 2B3A 的 writer+plan 定向在 API 30/API 35 各 11/11，core/database 全量各 166/166；统一离线门禁 797 actionable tasks，Debug/Release/Lint/R8、JVM、安全扫描脚本、源码与 5 APK 安全扫描及备份排除均通过。App 内真实 Provider 调用 0、物理设备写入 0。跨章有序 Job/Stage、TEST-033 和总 runner 仍未完成。
- Phase 2B3B1 将正式 Room schema 升到 v14：`chapter_edit_rebuild_execution` 和 `chapter_edit_rebuild_step` 是不可变准备证据，唯一绑定 edited version、rewind、stable fence、完整 current 章节范围和准备时真实 summary/tracking/aggregate 基线；`initialPlanHash` 不再承担长期执行身份。
- `ChapterEditRebuildExecutionRepository.prepare` 用单一外层 Room 事务包住 audited rewind、计划/时间重验、稳定 fence 计算和 ledger 写入。精确 replay 零写入，另一 identity 被拒绝；即使 rewind 已执行后再触发门禁失败，也会随 ledger 一起回滚。
- DeepSeek 只读设计审计 `20260806-011339-0d8f70cf` 的稳定账本与专门许可方向被采纳；其“一次创建全部 Stage”方案因后续 tracking 来源尚未存在且 Stage 来源不可变而未采用。Phase 2B3B1 不创建 Job/Stage/Attempt/Usage，不调用 Provider。
- migration+plan/ledger 定向在 API 30/API 35 各 9/9，`core/database` 全量各 171/171；统一离线门禁 797 actionable tasks，Debug/Release/Lint/R8、JVM、源码与 5 APK 安全扫描及备份排除通过。动态 Stage 执行、TEST-033 和总 runner 仍未完成。
- Phase 2B3B2A 不升级 schema：严格 `chapterEditRebuild` v2 binding 进入 memory Stage input/hash，确定性 Job/Stage 与 v14 execution/step 在同一事务核验和创建；普通 v1 memory Stage 保持兼容。Provider-open 与 commit 均重验 execution、stable fence、首个 PENDING step、完整 current 范围和来源。
- DeepSeek `20260806-054907-37278d3b` 使用 max 推理，约 5 分 58 秒触发 1,000,000 Token 守卫（实际 1,008,159），无最终消息、无新文件和无可审查代码差异；Sol 随后独立实现并审查。本轮不把 DeepSeek 的 Token 消耗当作代码贡献。
- Phase 2B3B2A `core/database` JVM 66/66；`ChapterEditRebuildPlanDatabaseTest` 在 API 30/API 35 各 12/12，数据库模块全量各 175/175；源码安全扫描与 diff 检查通过。未运行本阶段统一 Release/R8 门禁。App 内真实 Provider 调用 0、物理设备写入 0；tracking→aggregate、TEST-033 和总 runner 仍未完成。
- Phase 2B3B2B1 不升级 schema：tracking factory 新增严格 v2 rebuild binding；第一章 tracking 只接受 prepared-SATISFIED memory 全字段指纹，或确定性 memory Job/Stage/最新成功 Attempt/FINAL Usage/严格 output reference/权威 memory 行的完整成功链。普通 tracking 顺序守卫继续拒绝存在后续已提交章的中间重建，只有 stable-fence 专用来源许可可通过；Provider-open 与 commit 均已接线。
- Phase 2B3B2B1 的 Fake Provider E2E 实际走完绑定 memory 请求审计、流式响应、严格解析、Attempt/FINAL Usage、memory commit，再创建 tracking，不使用手工 summary 冒充远程成功。`core/database` JVM 67/67、`feature/generation` JVM 117/117；API 30/API 35 数据库各 178/178、生成各 29/29，安全扫描与 diff 检查通过。未运行统一 Release/R8；tracking 输出提交、aggregate、后续章节、TEST-033 和总 runner 仍未完成，真实 Provider 与物理设备写入为 0。

## 10. 2026-08-06 路线重排（用户已确认开工）

- 用户要求可靠可用优先，不为尽快拼合 App 牺牲状态、恢复、费用或来源约束；同时明确不能接受一章等待 10 分钟。
- 新增 `docs/25-RELIABILITY-AND-GENERATION-PERFORMANCE-ROADMAP.md`：普通参考章首段 P95 20 秒、正文结束 P95 180 秒、正式提交 P95 240 秒；第一章首段 P95 90 秒、正式提交 P95 300 秒；5 分钟进入慢服务安全处置，10 分钟为 P0 发布阻断。
- 速度分为本地调度、首段、正文、派生/检查和正式提交，生成中正文不再冒充正式章。现有候选链仍按 BODY→MEMORY→TRACKING→CONSISTENCY→可选修订→提交；先测量再决定安全并行或模型角色路由。
- 规划新增 TASK-062～069：时序、Fake 性能夹具、total runner、生成中正文投影、watchdog、关键路径优化、20 章 Fake 闭环和受控真实模型档案。
- 应用锁、生物识别、`FLAG_SECURE` 和最近任务遮挡明确取消；FEAT-081/082、TASK-097、TEST-089 不再实现。数据库/密钥/备份/通知和传输安全不变。
- 用户已用“开工”确认路线。Phase 2B3B2B2 已完成第一 tracking→aggregate 原子推进；继续 TASK-061 后续章节与 TEST-033，然后进入 TASK-062。仍未调用真实 API。

## 11. 2026-08-08 Phase 2B3B2B2 恢复与完成

- 第一章 rebuild tracking 提交已与同章 aggregate writer 接入同一 Room 外层事务；aggregate 失败会回滚 tracking、时间线、伏笔 transition/revision、FTS、FINAL Usage 和 Stage/Job 完成状态。精确 replay 只验证 tracking/aggregate 均已满足，不用变化后的 planHash 再写一代 aggregate。
- 新增 Fake Provider 正向闭环、aggregate 失败整笔回滚、精确 replay 零重复，以及 Stage 创建后 aggregate 槽变化会在 Provider-open 前失败关闭的测试。普通 tracking 路径保持原行为。
- 2026-08-08 按工作汇报 102 不改代码先恢复验证：数据库 JVM 67/67、生成 JVM 117/117；`ChapterEditRebuildPlanDatabaseTest` 双 API 各 16/16；`ChapterTrackingProjectionEndToEndTest` 双 API 各 5/5；数据库模块双 API 各 179/179；生成模块双 API 各 31/31。全部 0 失败、0 错误、0 跳过。
- `scripts/security-scan.ps1 -SkipArtifacts` 返回 `SECURITY_SCAN_OK`，`git diff --check` 返回 0，仅有既有换行提示。真实 Provider 0、物理设备写入 0、Git remote 为空。
- Phase 2B3B2B2 已由 Sol 正式标记完成，详见工作汇报 103。本子阶段未运行统一 Release/R8；TASK-061 仍因后续保留章节有序重建与 TEST-033 未完成而保持进行中。

## 12. 2026-08-08 Phase 2B3B2C 后续章节退役准备

- 正式 Room schema 升为 v15，新增不可变 `chapter_edit_rebuild_tracking_retirement`。证据精确绑定 execution/step、准备时 tracking baseline、退役后的 tracking 指纹、排序 timeline ID 集合与内容指纹、确定性 replacement Job/Stage 和退役时间；更新、删除及不满足 provenance 的插入由数据库触发器拒绝。
- `ChapterEditRebuildStageRepository.createNextRetainedTrackingStage` 当前只推进编辑章后的第一章。它在同一 Room 事务中验证前章 tracking+aggregate、冻结 current 范围和准备基线，退役旧 tracking/timeline、删除对应 FTS，读取真实 current source，创建 replacement Job/Stage 并插入证据。并发收敛为同一 Stage，identity collision 整笔回滚，精确 replay 零写入。
- DeepSeek 宽泛只读审计先后在 15 分钟和用户允许放宽后的 30 分钟到达硬上限；第二次累计约 2,001,338 Token，均没有最终回交、代码差异或权限请求。Sol 未继续第三次宽泛审计，独立完成架构决策、实现和验证；后续 DeepSeek 只用于窄文件级任务。
- `core/database` JVM 70/70；`ChapterEditRebuildPlanDatabaseTest` API 30/API 35 各 19/19；v14→v15 迁移各 1/1；数据库模块全量各 183/183。`SECURITY_SCAN_OK`，`git diff --check` 返回 0。真实 Provider 0、物理设备写入 0、Git remote 为空。
- 本子阶段未运行统一 Release/R8。新 replacement Stage 还不能通过现有第一章硬编码的 Provider-open/commit 门禁；planner 尚不能把 retirement-bound replacement tracking 识别为 `ALREADY_SATISFIED`，同章 aggregate、通用逐章循环、TEST-033 和总 runner 均未完成。

## 13. 2026-08-08 Phase 2B3B2D 首个保留章节完成

- `ChapterEditRebuildStageRepository` 已用 retirement evidence 授权 ordinal 4 replacement Stage 的 Provider-open 与 commit，并提供受保护的 bound source loader；current range、first tracking+aggregate、deterministic Job/Stage、retirement 和 source 任一变化都会在发送或提交前失败关闭。
- planner 只把 `generationStageId` 精确指向 retirement-bound replacement Stage 的新 `VALID` tracking 认作本 execution 的结果；任意同槽 VALID 行不能冒充。tracking commit 与 aggregate writer 仍共享外层 Room 事务。
- Fake Provider 正向路径完成 replacement tracking、timeline、aggregate、FINAL Usage、Stage/Job 和精确 replay；aggregate 故障会回滚本次新派生及结算，但保留 retirement 和旧 `STALE` 历史，Stage 停在 `COMMITTING` 可恢复边界。
- `core/database` JVM 70/70、`feature:generation` JVM 117/117；`ChapterTrackingProjectionEndToEndTest` 双 API 各 7/7，计划套件各 19/19，数据库模块各 183/183，生成模块各 33/33。`SECURITY_SCAN_OK`，`git diff --check` 为 0。真实 Provider 0、物理设备写入 0、Git remote 为空。
- 本阶段由 Sol 完成跨模块事务与身份裁决，没有再次调用 DeepSeek。用户已允许放宽 DeepSeek 时长，但此前两次宽泛审计均未收敛；后续只把显式文件/方法/测试边界的窄任务交给 DeepSeek，可按任务包记录延长时限。
- 本历史截面当时仍未完成 ordinal 6+、TEST-033 和统一 Release/R8；这些状态已由下方第 14 节更新。总 runner 始终属于 TASK-064。

## 14. 2026-08-08 Phase 2B3B2E 与 TASK-061 收口

- `ChapterEditRebuildStageRepository.createRetainedTrackingStage` 接受显式偶数 `targetStepOrdinal>=4`，章节/版本/类型只从 immutable ledger 推导；旧 `createNextRetainedTrackingStage` 保持 ordinal 4 兼容。ordinal 6+ 创建前必须证明直接前驱 deterministic tracking Stage/Job、权威 projection 与 aggregate 完成，且时间不晚于当前 `createdAt`。
- authorized retirement 改为从 ordinal 4 开始的连续章节/ordinal/时间前缀；缺口、错位、倒序、identity 或 evidence 损坏都会停止较后授权。不同目标 ordinal exact replay 独立，不自动猜 next，也不预建未来 Stage。schema 保持 v15。
- 数据库计划套件新增 ordinal 6 正向、前驱未完成和时间下界负例，双 API 各 22/22；三章 Fake Provider E2E 走通 ordinal 6 后整类双 API 各 8/8。
- TEST-033 新增 10 章编辑第 3 章：旧第 2–10 章 tracking 建立后，重建第 3 章并按 ordinal 4–16 重建第 4–10 章。结果为 retirement 7、旧 STALE tracking 8、当前 VALID tracking 9、当前第 3–10 章 aggregate 8，第 4–10 章正文/current version 保留，planner 对第 3–10 章 tracking/aggregate 全部 ALREADY。
- 生产 `MemoryContextRouteSelectionRepositoryV1` 另证实旧摘要 STALE、旧 FTS 行删除，只选择新 current version 的 replacement summary；TEST-033 以有序重建与旧派生不进入权威上下文完成。
- execution 是 immutable PREPARED fence，不新增 mutable completion 字段；完成由权威 planner 推导。TASK-061 原语已完成，自动选择下一步、重启续跑、双执行器和 context/consistency phase 调度归 TASK-064 total runner。
- DeepSeek 窄只读审计 `20260808-173725-9e7badef` 使用 max 推理和 25 分钟时限，约 12 分 35 秒正常结束，总 Token 3,106,883；无权限请求、无代码写入。Sol 采纳显式目标/直接前驱/连续前缀/独立 replay/无迁移方向，并额外实现时间单调证明。
- 最终验证：数据库 Android API 30/API 35 各 187/187，生成 Android 各 35/35；数据库 JVM 70/70，生成 JVM 117/117。`scripts/verify-build.ps1 -Offline` 通过 797 actionable tasks、Debug/Release、Lint/Vital、R8、扫描器自测、5 个产物安全扫描和备份排除；`git diff --check` 为 0。真实 Provider 0、物理设备写入 0、Git remote 为空。
- 本历史截面记录时 TASK-063 仍处于待办；TASK-062 与 TASK-063 均已由下方第 15、16 节更新为完成。不得提前调用 App 内真实 Provider，也不得把 TASK-064 total runner 描述为已完成。

## 15. 2026-08-08 TASK-062 脱敏生成时序完成

- `core:diagnostics` 新增有限 phase/milestone/outcome、确定性域分离指纹、epoch+elapsedRealtime+boot 时钟、首段有限探测与失败关闭报告器。持续时间只在同 boot 计算，跨 boot、缺事件、回退和失败终态不猜值。
- 正式 Room schema 升至 v16，新增 append-only `generation_timing_event`，包含 phase-aware Stage/Attempt 索引、固定 milestone-phase、非负计数、前驱、同 boot 单调、正式提交终止、UPDATE/DELETE 保护；v15→v16 不伪造旧事件。
- `AuditedStreamingProviderExecutor` 通过可选 timing context 接入 BODY：Heartbeat 与 NOT_SENT 失败不冒充首字节；正常、截断、拒绝、未知、暂停、取消和无终态断流均能得到有限结束结果。旧调用默认 no-op 兼容。
- DeepSeek 只读架构审计 `20260808-184643-6858d55d` 使用 max 推理和 25 分钟时限，约 7 分 54 秒正常结束；总 Token 711,706，无权限请求、无代码写入。Sol 修正其缺少 STAGE_STARTED/COMMIT_STARTED、body 公式、可选修订和 boot 识别等问题，并额外补 phase 隔离与失败终态。
- API 30/API 35：数据库各 192/192、生成各 37/37；JVM diagnostics 10/10、database 70/70、generation 120/120。App 内真实 Provider 0、物理设备写入 0、Git remote 为空。
- 统一离线门禁通过 797 actionable tasks、Debug/Release、Lint/Vital、R8、扫描器自测、源码与 5 个 APK 扫描和备份排除。测试假密钥最初被 Kotlin 常量折叠进 androidTest APK并由扫描器正确拦截，改为运行时字符数组 canary、重建 APK 后最终扫描 0 命中。
- TASK-063 已由下方第 16 节更新为完成。TASK-064 total runner、TASK-065 生成中正文、TASK-066 watchdog 和真实模型速度仍未完成，不得从本节推断 App 已可自动整章生成。

## 16. 2026-08-08 TASK-063 确定性 Fake 性能夹具完成

- 新增独立 JVM `provider:fake` 模块与有限脚本 DSL，支持固定延迟、慢流、Text/Structured、usage、拒绝、有限失败、UNKNOWN、取消和无终态 EOF；App/正式 feature 没有 implementation 依赖。
- `VirtualFakeStreamClock` 在可取消调度点后推进时间，不真实等待几分钟。每个 collection 只累计自身已完成 Wait，避免共享时钟并发污染；统计不保存正文、prompt、endpoint、secret 或 request id。
- `GenerationTimingBenchmarkReporter` 使用 nearest-rank 输出 P50/P95/最慢值，并保留 total/available/NotApplicable/各 unavailable reason。20 个正式提交样本均为 `MISSING_EVENT`，因此 TASK-064 没有被冒充完成。
- 20 个 2,500～3,450 字 Fake BODY 的固定结果：首字节 10.9/11.8/11.9 秒，首段 18.35/19.70/19.85 秒，正文结束 147/174/177 秒（P50/P95/最慢）。
- 虚拟 301 秒无终态 EOF 通过真实 RequestIntent、Room、加密草稿和时序账本后进入 `UNKNOWN_RESULT`，Provider 调用数为 1。主动 5 分钟 watchdog 仍归 TASK-066。
- DeepSeek 写入运行 `20260808-201004-b5d24248` 使用 max、30 分钟硬上限、无 Token 上限，约 19 分 38 秒正常结束，总 Token 2,202,934。Sol 修复其共享时钟并发误算和 total token 缺失后复测。
- API 30/API 35 `feature:generation` 各 39/39；统一门禁 801 actionable tasks、JVM 537/537、Release/R8、源码与 5 APK 安全扫描和备份排除通过。
- 下一任务是 TASK-064；真实 Provider、物理设备写入和 Git remote 均保持 0/无。

## 17. 2026-08-08 TASK-064 Phase 1A 空闲 Job lease 恢复完成

- TASK-064 已正式进入实现，但整体仍是进行中。Sol 先确认不新增 schema v17/runner shadow table：现有 `current_stage_id`、状态、租约、Attempt 和业务提交事务继续作为唯一恢复事实。
- DeepSeek 只读审计 `20260808-210256-fbe80952` 使用 max、30 分钟上限、无 Token 上限，约 5 分 14 秒结束，总 Token 1,312,501；指出 Job lease 领取后、Stage lease 前崩溃会永久 RUNNING。Sol 采纳该 P0 缺口，但修正“只扫描 READY Job”和“只按 phase 分发”两项不完整建议。
- DeepSeek 写入运行 `20260808-211241-a473f0d6` 使用 max、25 分钟上限、无 Token 上限，约 8 分钟结束，总 Token 1,793,201；只修改授权的 DAO、maintenance 和数据库测试并通过 AndroidTest 编译。
- 新维护路径只扫描 RUNNING Job 的过期完整 lease，current Stage 必须属于该 Job、仍 READY 且三项 Stage lease 为空。恢复事务重读后用专用 CAS 匹配 Job owner/acquired/heartbeat/currentStage，并在 SQL 中再次证明 Stage READY+无 lease；Stage 与 Attempt 零变化。
- Sol 额外要求候选 heartbeat 必须与重读事实完全相等，并新增篡改 heartbeat 零写入回归。扫描后 Stage 被领取会 stale-fail；双维护器仅一个成功。
- API 30/API 35 定向 `GenerationDatabaseTest` 各 57/57，数据库 Android 全量各 197/197，数据库 JVM 70/70；`SECURITY_SCAN_OK`、diff check 0。真实 Provider 0、物理设备写入 0、Git remote 无。
- 下一子阶段：runner queue 必须同时支持领取 READY Job 与同 owner RUNNING Job 续跑，并建立双层 heartbeat；之后再做基于冻结 contract/schema identity 的 dispatcher。`EXTRACT_MEMORY` 不能只凭 phase 区分 memory 与 tracking。

## 18. 2026-08-08 TASK-064 Phase 1B runner queue 与 Job heartbeat 完成

- 新增 `GenerationRunnerQueueRepository`：有界 `observedAt` READY scan、稳定排序、limit+1/hasMore、精确候选重验、Job lease claim，以及持原 token heartbeat 后读取最新 current Stage。没有新增 schema、runner table 或第二 cursor。
- READY 查询要求 Job/Stage 三项 lease 均为空；claim 在单 Room 事务内匹配 Job/currentStage/status/updatedAt 和 Stage/status/updatedAt/no-lease 后复用既有 CAS。Stage、Attempt、attemptCount 与业务 cursor 不被 queue 修改。
- 正常 Stage A→B 由业务提交事务推进后，同一 Job token 继续 heartbeat 并读取 B；进程重启不能按 owner 字符串收养旧 lease。错误 owner/acquiredAt 与 timeout 临界均不可复活。
- DeepSeek 写入运行 `20260808-213707-7e4fe1b6` 使用 max、30 分钟硬上限、无 Token 上限，约 23 分 21 秒结束，总 Token 4,412,800；产生了可审查代码。Sol 补强 Job lease-free 查询、异常 projection 失败关闭和残留 lease 回归。
- `GenerationDatabaseTest` API 30/API 35 各 64/64；`core:database` Android 全量各 204/204；数据库 JVM 70/70，AndroidTest 编译通过。真实 Provider 0、物理设备写入 0、Git remote 仍为空。
- TASK-064 整体仍进行中。下一子阶段先审计 Stage lease/heartbeat 已有所有权与 executor 接口，完成双层 heartbeat 执行包络；随后按冻结 contract/schema identity 分发，不能只看 phase。

## 19. 2026-08-08 TASK-064 Phase 1C 原子 current Stage lease 完成

- 审计确认网络流式 executor 已自行 heartbeat Stage，final commit executor 已能 same-owner resume；total runner 缺的是持 Job token 原子领取 current Stage 和双 lease 共同续租的数据库边界。
- 新增 `GenerationRunnerExecutionLeaseRepository`。acquire 只接受 RUNNING Job、精确 Job token、same-owner、current READY Stage；同事务先 heartbeat Job 再 acquire Stage。双 heartbeat 允许 RUNNING/PAUSING/STOPPING，但要求 currentStage、两 token 与同 owner 全匹配，任一失败整体回滚。
- DeepSeek `20260808-221907-e4212150` 使用 max、30 分钟、无 Token 上限，在硬超时终止；总 Token 3,654,893，无 final，只落 repository 主体且没有测试。Sol 补 identifier/same-owner 门禁和 5 个测试，并修正测试毫秒单位后独立验收。
- API 30/API 35 `GenerationDatabaseTest` 各 69/69；数据库 Android 全量各 209/209；数据库 JVM 70/70、AndroidTest 编译通过。真实/Fake Provider 0、物理设备写入 0、Git remote 无。
- TASK-064 仍进行中。下一子阶段是确定性 heartbeat scheduling envelope：在 action 存活期间按间隔调用原子双 heartbeat，正确处理取消、lease 丢失、业务 commit 推进 cursor/完成 Job 的竞态；之后才进入 contract-aware dispatcher。

## 20. 2026-08-08 TASK-064 Phase 1D heartbeat scheduling envelope 完成

- 新增 `GenerationRunnerHeartbeatEnvelope`：action 与下一 heartbeat tick 竞争；action 先完成则取消 waiter，tick 先到则调用 Phase 1C 原子双 heartbeat。默认 15 秒，时钟/waiter 可注入。
- heartbeat 失败后读取权威 Job：原 token 下 cursor 已推进，或 Job 已进入清 lease 的 COMPLETED/PAUSED/STOPPED/NEEDS_ACTION/BLOCKED，视为业务 durable boundary，停止旧 heartbeat并等待 action；当前 Stage 未变或 token 丢失则取消 action并传播失败。
- 新增 5 个无 sleep JVM 测试。`feature:generation` JVM 全量 125/125；API 30/API 35 Android 各 39/39。首轮 1 个 `assertSame` 测试因协程异常栈恢复生成等价实例失败，改为类型/消息断言后通过。
- 本阶段由 Sol 直接实现，没有调用 DeepSeek。真实/Fake Provider 0、物理设备写入 0、Git remote 无。
- TASK-064 仍进行中。下一子阶段是 frozen contract/schema-aware dispatcher：建立有限 route identity/registry，尤其不能用 `EXTRACT_MEMORY` phase 同时猜 memory 与 tracking；先接纯本地/已存在 executor 的最小 route，再扩到完整 Fake 第一章。

## 21. 2026-08-09 TASK-064 Phase 2A 派生 route identity 完成

- 新增 `GenerationRunnerStageRouteResolver` 与 10 个有限 route：普通/编辑重建 memory、普通/编辑重建 tracking、五类 candidate role+phase，以及 final commit v3。route enum 不携带 ID、hash 或 payload。
- 解析器只读取严格 JSON object 的 `sourcePolicyVersion` 来选择唯一权威 parser；随后完整验证 schema/root/phase/target/targetId/inputVersionHash/binding。parser 失败不吞掉、不尝试 fallback，`EXTRACT_MEMORY` 不再仅凭 phase 分发。
- candidate/final 现有 policy 常量只从 `private` 调整为 `internal` 供 resolver 复用，值和解析逻辑不变；未改 DAO、entity、schema、migration、状态机、Provider 或 Gradle。
- DeepSeek 写入运行 `20260808-234024-85842439` 使用 max、30 分钟硬上限、无 Token 上限，约 15 分 28 秒正常结束；总 Token 2,402,928、cached input 2,136,064、output 58,593、reasoning 40,611。Sol 独立审查并复测。
- resolver JVM 11/11，`core:database` JVM 81/81；API 30/API 35 数据库 Android 各 209/209。`SECURITY_SCAN_OK`、diff check 0、Git remote 为空；真实/Fake Provider 0、物理设备写入 0。
- TASK-064 仍进行中。下一子阶段把 route resolution 绑定到 current Job/Stage 的精确、同 owner、未过期租约；之后才建立有限 executor registry。planning/context/普通 draft route、完整 Fake 第一章和统一 Release/R8 均未完成。

## 22. 2026-08-09 TASK-064 Phase 2B current leased Stage route binding 完成

- `GenerationRunnerExecutionLeaseRepository.resolveCurrentStageRoute` 在同一只读 Room 事务中验证 Job/Stage 仍为 current `RUNNING + PREPARING`、精确双 token、同 owner、双 heartbeat 未到 60 秒临界、时间单调且 attempts 未耗尽，然后才调用 Phase 2A 权威 parser。
- 新增 `GenerationRunnerCurrentStageRouteSnapshot`，同时携带有限 route、精确执行租约快照和 attempt 上下界；其构造器为 `internal`。原始 `GenerationRunnerStageRouteResolver` 也改为 `internal`，feature 层不能手工构造 Stage/route 绕过数据库事实。
- 新增 5 个 Android 数据库测试。首轮 API 35 全类 74 项仅因测试误以为 PREPARING 暂停会停在 PAUSING 而失败；正式控制实际直接进入 PAUSED。测试改为故障注入隔离 PAUSING 分支后 74/74，生产规则未放松。
- `core:database` JVM 81/81；API 30/API 35 数据库 Android 全量各 214/214，0 失败、0 跳过。`SECURITY_SCAN_OK`、diff check 0、Git remote 为空；真实/Fake Provider 0、物理设备写入 0。
- 本阶段由 Sol 直接实现和审查，未调用 DeepSeek，因为改动位于数据库租约授权边界。TASK-064 仍进行中；下一子阶段建立只接受绑定快照的有限 executor registry，并审计每个 route 的唯一生产入口。planning/context/普通 draft route、完整 Fake 第一章和统一 Release/R8 仍未完成。

## 23. 2026-08-09 TASK-064 Phase 2C1 executor 生产入口审计

- DeepSeek 只读审计运行 `20260809-004534-fb703b2c` 使用 max、30 分钟硬上限、无 Token 上限，约 10 分 19 秒正常完成；总 Token 1,511,631、cached input 1,166,464、output 47,523、reasoning 24,471。sandbox 为 read-only，任务前后 status 均 209 条，没有代码差异或权限请求。
- 审计确认 9 个 remote route 均不能直接进入 registry：formal memory/tracking 缺完整生产提交桥；edit-rebuild 缺 Stage repository→runner 桥；candidate draft/revision 缺生产 seal 调用；candidate 派生/consistency 虽有 coordinator+seal/route 组合，仍缺 runner 输入装配、恢复和防重复发送证明。
- final commit v3 是唯一 local 且已有 executor/coordinator/recovery/replay 的 route，但 Sol 复核否决了“可直接接线”结论：现 `ChapterFinalCandidateCommitStageExecutorV1.execute` 只接收 `leaseOwnerId`，会重新读取 persisted token，不能证明仍是 Phase 2B 快照中的 exact Stage token。
- 下一子阶段先给 final executor 增加只接受 exact persisted Stage token 的 bound 入口，并证明错误 acquiredAt、同 owner 新 token、超时与状态变化均不 commit；之后才建立最小 registry。TASK-064 继续进行中，真实/Fake Provider 0。

## 24. 2026-08-09 TASK-064 Phase 2C2 final exact-token bound executor 完成

- `ChapterFinalCandidateCommitStageExecutorV1.executeBound` 新增 total-runner 专用入口：只接受 PREPARING/COMMITTING exact Stage token，不 acquire READY Stage，不用同 owner 最新 token替换调用方 token。
- persisted token 必须与调用方 token 完整相等；heartbeat/updatedAt 时间必须单调，默认 60 秒租约临界已过期时在 coordinator 前失败。SUCCEEDED 只读返回 `AlreadySucceeded`。
- 新增 4 个 JVM 测试：exact token PREPARING/COMMITTING、同 owner 不同 acquiredAt、超时临界/READY、SUCCEEDED replay。executor 定向 12/12，generation JVM 全量 129/129；API 30/API 35 generation Android 各39/39。
- `SECURITY_SCAN_OK`、diff check 0、Git remote 为空；真实/Fake Provider 0、物理设备写入 0。本阶段没有 registry、schema、migration、Attempt 或 Provider 变化。
- TASK-064 仍进行中。下一子阶段建立只接受 Phase 2B 绑定快照的最小 registry，仅注册 final commit v3；其余 9 个 route 显式失败关闭。

## 25. 2026-08-09 TASK-064 Phase 2C3 最小有限 registry 完成

- 新增 `GenerationRunnerExecutorRegistryV1`，公开入口只接受 Phase 2B 的数据库绑定快照。执行前复核 `RUNNING + PREPARING`、same-owner、时间单调和双 lease 未过期。
- 注册集合严格只有 `FINAL_CHAPTER_COMMIT_V3`；该分支把快照 exact Stage token 原样传给 `executeBound`。其余九条 remote route 在穷举分支中显式失败，没有 generic/phase fallback，也没有 Provider 调用。
- 新增 2 个 JVM 和 2 个 real Room Android 集成测试。首轮定向 Android 因测试 `@Before` 表达式体意外返回非 Unit 产生 initializationError，改为显式 Unit 后 2/2；生产代码未放松。
- `feature:generation` JVM 131/131；API 30/API 35 Android 各41/41。统一 `scripts/verify-build.ps1 -Offline` 通过 801 actionable tasks、Debug/Release、Lint/Vital、R8、扫描器自测、源码与 5 APK 安全扫描和备份排除。
- 本阶段由 Sol 直接实现、审查和验收，没有调用 DeepSeek；真实/Fake Provider 0、物理设备写入 0、Git remote 为空。
- TASK-064 仍进行中。下一步先完成 candidate draft 的唯一生产 adapter 与 exact-token/恢复/防重复发送证明，再考虑扩大 registry；其余 remote route、planning/context/普通 draft、多阶段循环和完整 Fake 第一章仍未完成。

## 26. 2026-08-09 TASK-064 Phase 2D1 candidate draft 合同审计

- DeepSeek 只读运行 `20260809-014555-0cb91ec2` 使用 max、30 分钟硬上限、无 Token 上限，约 9 分 48 秒正常完成；总 Token 2,781,479、cached input 2,406,656、output 40,780、reasoning 21,731。任务前后 status 均 215 条，无权限请求和代码写入。
- 审计与 Sol 复核确认：`CANDIDATE_CHAPTER_DRAFT_V1` 当前不能接线。candidate BODY binding 要求请求前不存在的 candidate version/hash；Provider-open guard 只允许 bound BODY 用于 REVISE；seal/recovery 又把 revisionIndex=0 初始 DRAFT 当无 candidate input source 的根节点。
- 生产 candidate `stageSetup` 调用只有 derived/revision successor 三处，没有 initial DRAFT factory；`ChapterDraftStreamingCoordinator.ReadyForValidation` 的生产消费者只有 revision coordinator。测试手工 phase-only BODY→MEMORY 闭环不构成生产入口。
- 当前 route 继续在 Phase 2C3 registry 中显式失败关闭。不得通过伪造 candidate hash、放宽 guard、复制 generic Provider executor或直接操作 cursor解决。
- 下一子阶段先从已持久化的 planning/context/scene contract 设计 request 前可得的 initial-draft source contract 与生产 Stage factory；随后再实现 exact-token streaming/validation/seal/recovery adapter。TASK-064 仍进行中，真实/Fake Provider 0。

## 27. 2026-08-09 TASK-064 Phase 2D2 context route identity 完成

- `ASSEMBLE_CONTEXT` factory 只为本地 context Stage 写入 `zhijuan.chapter-context-assembly-source.v1`；chapter-plan successor 不写该 policy。
- factory、repository 与 resolver 共享唯一严格 parser，验证精确字段集、固定版本/schema、空依赖、预算、prompt hash、progression 自哈希、chapter id/index 和完整 input hash；解析结果字符串不泄露冻结输入。
- route enum 新增 `CHAPTER_CONTEXT_ASSEMBLY_V1`。registry 白名单仍严格只有 final commit，context route 显式未注册；DeepSeek 因任务边界未改 feature，Sol 补齐该 fail-closed 分支。
- DeepSeek `20260809-020430-8f974ec6` 使用 max、30 分钟、无 Token 上限，约 20 分 37 秒正常完成并产生可审查差异；无权限请求、Provider、物理设备或越界文件写入。
- 数据库 JVM 86/86；加固后定向 19/19；API 30/API 35 数据库全量各214/214、加固后 context 各5/5；feature 正式与 AndroidTest Kotlin 编译通过。
- TASK-064 仍进行中。下一阶段 Phase 2D3 为 context repository 增加 exact Job+Stage token bound 入口，再把该纯本地 route 加入 registry；initial draft、chapter plan、远程派生链与 Fake 第一章仍未完成。

## 28. 2026-08-09 TASK-064 Phase 2D3 context exact-token registry 完成

- `ChapterContextAssemblyRepository` 新增只接受 Phase 2B bound snapshot 的 `assembleBound`。exact Job/Stage token、current cursor、`RUNNING + PREPARING`、same owner、heartbeat/租约与 attempt 边界在 context 业务提交的同一个 Room 事务内重验；旧 `assemble` 与 bound 入口共用唯一内部业务路径。
- 已成功 context Stage 支持只读 durable replay，不重复 snapshot 或 plan 激活。registry 白名单现严格为 final commit+context；context executor 收到原始 snapshot/time，九条 remote route继续显式关闭。
- DeepSeek `20260809-025115-1315827e` 使用 max、30分钟、无Token上限，正好触发硬超时；总Token 8,520,253、cached input 7,762,688、output 162,050、reasoning 113,599，无final且留下未完成WIP。Sol 修复超时残留的括号结构错误、完成租约加固、测试与独立验收后才确认阶段完成。
- `core:database` JVM 86/86、`feature:generation` JVM 131/131；API 30/API 35 database 各218/218、generation 各42/42，0失败、0错误、0跳过。一次双模块并跑遇到 Windows logcat 文件锁，随后四条模块命令拆开并分别成功退出。
- `scripts/verify-build.ps1 -Offline` 通过801 actionable tasks、Debug/Release、Lint/Vital、R8、扫描器自测、源码与5个APK安全扫描和备份排除。真实/Fake Provider 0、物理设备写入0、Git remote为空。
- 用户允许继续放宽 DeepSeek 思考时长；启动器、协议、模板和运行手册现保持默认15分钟、通常15～30分钟，只有同一窄任务已在30分钟正常推理超时且无其他阻塞时，才可在新任务包说明理由后提高到最多45分钟。`-ValidateOnly -MaxRunMinutes 45 -NoTotalTokenLimit` 已通过隔离探针。
- TASK-064 仍进行中。下一阶段先为 context 成功后激活的 `BUILD_CHAPTER_PLAN` 建立独立严格 route identity 与 exact-token production executor，再推进 initial draft；其余 remote route、多阶段循环和完整 Fake 第一章仍未完成。

## 29. 2026-08-09 TASK-064 Phase 2E1 普通 chapter-plan 合同审计

- context 成功会激活普通 `BUILD_CHAPTER_PLAN`，但其输入当前缺 `sourcePolicyVersion`；resolver/registry 因而失败关闭。生产代码没有普通 `chapter-plan.v1` 严格 parser/业务 validator、exact-token远程执行器、commit repository或initial DRAFT successor。
- 已有ContextSnapshot/progression Provider-open重算、RequestIntent/Attempt/Usage、artifact、结构化校验、一次格式修复和UNKNOWN只是通用原语；目的地与TASK-083/084三层预算原子预留仍未进入该请求路径。
- DeepSeek 首次运行 `20260809-041913-9c111e91` 因断网退出1，无final/写入；网络恢复后的 `20260809-054854-76d0c42d` 以max、30分钟、无Token上限约9分54秒正常完成，总Token3,132,957，无工作树改动。默认status仍220条及原SHA-256；`-uall`显示279只是展开未跟踪目录，不是文件新增或丢失。
- Sol 复核发现成功 `STREAM_DRAFT` artifact 默认24小时可清理，因此否决“仅artifact+output reference作为长期计划来源”。DEC-068确定：plan commit严格解析并规范化有界计划，在同一SQLCipher事务动态创建initial DRAFT，并把规范计划、plan/context/progression identity与hash冻结进DRAFT不可变输入；plan output reference保存hash与DRAFT Stage ID。无需schema migration，不写窗口级OutlineRevision。
- 规范计划目标上限48KiB，完整DRAFT input envelope仍受64KiB上限。Phase 2E1未运行Gradle/模拟器；上一基线保持数据库JVM86/86、生成JVM131/131，双API数据库各218/218、生成各42/42和801-task离线门禁。
- 下一阶段Phase 2E2只增加`zhijuan.chapter-plan-source.v1`、严格输入parser与`CHAPTER_PLAN_V1` route，不注册、不调用Provider；之后再分别完成输出合同、预算/目的地、exact-token执行、原子提交和initial DRAFT。

## 30. 2026-08-09 TASK-064 Phase 2E2 chapter-plan 严格来源身份完成

- 普通`BUILD_CHAPTER_PLAN` root新增`sourcePolicyVersion=zhijuan.chapter-plan-source.v1`；context与plan仍由同一工厂创建，但拥有互不混淆的policy。
- `parseAndVerifyChapterPlan`严格验证phase/target/attempts、精确root、bundle/`chapter-plan.v1`、唯一context依赖、context Stage ID/input hash/policy/manifest、progression自哈希/target/index和完整input hash；有限结果隐藏所有Stage/hash identity。
- resolver新增`CHAPTER_PLAN_V1`且只通过plan policy+唯一parser命中。registry在穷举分支显式`notRegistered`，注册集合仍精确为final+context两项；没有Provider、RequestIntent、Attempt、Usage、费用或状态推进。
- DEC-069明确不复制bookId/chapterId/contextSnapshotId：Stage/Job、progression和context Stage已经保存权威身份，动态currentness留给后续exact-token Provider-open事务重验，避免第二份漂移事实。
- 验证：factory/resolver/registry定向8+15+2；数据库JVM90/90、生成JVM131/131；API30/API35数据库各218/218、生成各42/42；801-task统一门禁、Release/R8、源码与5个APK安全扫描、备份排除均通过。真实/Fake Provider0、物理设备写入0、Git remote为空。
- 下一阶段Phase2E3先实现严格、有界的`chapter-plan.v1`输出schema/parser/业务交叉校验，继续不注册；目的地/三层预算、exact-token远程执行、DEC-068提交/initial DRAFT和完整Fake首章仍未完成。

## 31. 2026-08-09 TASK-064 Phase 2E3 chapter-plan 严格输出合同完成

- 新增`ChapterPlanOutputContractV1`与`ChapterPlanOutputParser`：48KiB、最多12场景、全章64过程节点、exact schema/本地reader、稳定object-key规范化和SHA-256；重复key、未知字段、乱序、超限和错误类型均失败关闭。
- 动态`ChapterPlanExpectationV1`/`ChapterPlanBusinessValidatorV1`核对章节/context双hash、已知人物、POV参与关系、成年人虚构集合及既有`SceneExecutionContract`。Blocked不能构造请求 expectation；Allowed必须至少一个相关场景；NotApplicable禁止模型自行增加相关场景。
- 严格相关场景至少3个有序过程节点，每节点冻结动作、反应、空间、身体、衣着/物品与感官变化，并要求剧情余波；非严格场景禁止伪造过程节点。该结构为后续DRAFT和consistency逐节点证明提供来源，不以粗俗词汇数量代替完整过程。
- 新增9项JVM，generation JVM全量140/140；API30/API35 generation各42/42；801-task统一离线门禁、Release/R8、源码与5个APK安全扫描、备份排除通过。
- 当前断网，因此本阶段没有启动DeepSeek，由Sol直接实现和审查。真实/Fake Provider0、物理设备写入0、无schema/migration/DAO/registry变化；plan仍未注册。
- 下一阶段Phase2E4先审计并补齐plan请求的目的地与三层预算持久预留/输入绑定，再设计exact-token远程执行；DEC-068原子提交/initial DRAFT及完整Fake首章仍未完成。

## 32. 2026-08-09 TASK-064 Phase 2E4A 目的地与预算前置审计完成

- `ConnectionProfileEntity` 已有 `normalizedDestination` 与 disclosure 三字段，但保存路径把 destination 直接写成 base URL、确认字段恒为 null；全仓没有正式接受、失效或 Provider-open 校验调用。
- `BudgetEngine` 只有领域测试调用；`GenerationJobEntity.budgetSnapshotJson` 是不可变意图快照，`UsageLedgerEntity` 是用量事实，二者均不能承担并发持久 reservation。
- `recordRequestIntent` 已能原子创建 Attempt+UNKNOWN/PROVISIONAL Usage，但没有在同一事务竞争 request/book/daily 三层余额。直接接 plan 会留下并发超支的 P0 漏洞。
- DEC-071 冻结实现顺序：2E4B目的地规范化/确认/动态失效；随后TASK-083独立持久预算policy+reservation并与RequestIntent同事务；最后才是plan不可变请求绑定、exact-token executor和DEC-068提交。
- 确认体验按“首次新目的地或目的地变化一次、正常章节自动复核”设计，不做逐章弹窗。`CHAPTER_PLAN_V1`继续未注册，本阶段Provider/Attempt/Usage/schema/migration变化均为0。

## 33. 2026-08-09 TASK-064 Phase 2E4B 外部数据目的地确认内核完成

- 新增`ExternalDataDestinationBindingV1`：canonical `scheme://host:effectivePort`+protocol+disclosure version+policy version稳定hash；同origin拼写/path/default port/DNS尾点复用，origin/protocol/version变化失效，非法URI失败关闭。
- `ConnectionDao.acceptDataDisclosureForCurrentDestination`在Room事务内按connection/base URL/protocol CAS写既有disclosure字段并立即回读动态验证；新连接只存canonical destination且保持未确认。evidence明确不是send permit。
- `ConnectionProfileEntity`、binding与evidence字符串均脱敏。无schema/migration；历史未确认连接保持失败关闭，首次接受时规范destination。
- DeepSeek只读审计`20260809-071823-4baf2bb8`以max、15分钟、无Token上限约5分36秒完成，未发现P1生产缺陷；Sol采纳host/hash/version/IPv6/端口测试和既有entity toString加固。
- 验证：core:model JVM17/17；Connection定向双API各6/6；core:database双API各222/222；801-task统一门禁、Debug/Release、Lint/Vital、R8、源码与5 APK扫描、备份排除通过。一次JUnit表达式体签名错误已显式Unit修正并全量复验。
- `CHAPTER_PLAN_V1`仍未注册，Provider/Attempt/Usage变化为0。下一阶段进入TASK-083持久三层预算policy/reservation，并与RequestIntent事务原子结合；随后才设计plan exact-token executor。

## 34. 2026-08-09 TASK-083 Phase 1 持久预算设计冻结

- DEC-073冻结schema v17方向：不可变BOOK/DAILY policy revision+CAS head、每enforcement v1 Attempt唯一不可删除reservation；request上限在reservation，book/daily聚合同范围全部非RELEASED明细，不以Job snapshot或平行counter作余额。
- RequestIntent事务必须先插入候选reservation取得写竞争权，再聚合包含自身的accounted值；超限时reservation/Attempt/UNKNOWN PROVISIONAL Usage/Stage四者整笔回滚。除单Room并发外，必须用两个Room实例指向同一文件证明竞争。
- 所有FINAL与迟到Provider Usage在`GenerationDao.recordUsage`唯一事务入口按终值结算。UNKNOWN保留estimate，实际超预留仍保存；只有Provider证明未执行可RELEASED，迟到高可信usage可重新SETTLED。
- daily key由持久IANA zone+epoch唯一生成；跨午夜未发送请求重新预留。实际Provider profile与adapter protocol也必须匹配reservation的connection/protocol/canonical destination，不能确认A后发送B。
- v16历史Attempt迁移为budget enforcement v0，可继续本地恢复/结算但不能Provider-open；v17前真实Provider调用为0，因此不为旧UNKNOWN测试行伪造reservation。
- DeepSeek只读审计`20260809-075939-b1d748ce`使用max、25分钟、无Token上限约10分39秒完成，总Token1,367,455；0权限请求/0写入，status前后233条。Sol采纳双连接竞争与唯一结算入口，修正其策略聚合和legacy真实费用假设。
- 本阶段无业务代码/schema/测试运行；下一阶段实现schema v17、policy/reservation迁移与数据库保护，plan route继续未注册。

## 35. 2026-08-09 TASK-083 Phase 2 schema v17 与 policy core 完成

- 正式主库升级到schema v17：新增不可变BOOK/DAILY policy revision、CAS head、每Attempt唯一reservation；旧Attempt只回填enforcement v0/null，不伪造历史占用。
- `BudgetDailyPeriodKeyV1`只使用显式IANA zone+epoch；policy repository在单Room事务内追加连续revision并推进head，不存在book、换daily zone、倒退时间、重复ID或fork均回滚。
- 数据库trigger要求reservation初始只能RESERVED且accounted=estimate，冻结identity/estimate/policy/destination并绑定Job–Book；只允许有限前进和RELEASED→SETTLED迟到回补。Phase2仍不创建生产reservation。
- DeepSeek workspace-write运行`20260809-081637-6c382bb4`使用max、30分钟、无Token上限，达到超时守卫且无final，但在允许文件内留下实现。Sol修复API30 java.time兼容、状态/币种/Job–Book guards、缺失测试和跨schema迁移helper错误。
- 验证：core:model 23/23、core:database JVM90/90；API30/API35数据库全量各226/226；`SECURITY_SCAN_OK`、diff check 0、remote为空。真实/Fake Provider0、物理设备写入0。
- 下一阶段把候选reservation与RequestIntent原子接线并做单Room/双Room同文件并发拒绝；随后收敛Usage结算、跨日重预留、v0 Provider-open阻断和真实profile/adapter目的地匹配。plan继续未注册。

## 36. 2026-08-09 TASK-083 Phase 3A 原子 reservation core 完成

- 新内部入口在单Room事务内读取权威Stage/Job/BOOK+DAILY policy和当前disclosure，由policy zone+epoch派生日键；先插入RESERVED candidate再聚合包含自身的三层占用，超限或后续Attempt写入失败时reservation/Attempt/Usage/Stage整笔回滚。
- book/daily聚合覆盖相同scope全部非RELEASED reservation且不按policy revision过滤。金额上限存在时，缺金额/币种或任一异币种均保守拒绝，不换汇、不只累计匹配行。
- RequestIntent兼容字段默认仍为v0/null；原子入口成功时才写v1+reservation ID并回读核对reservation、UNKNOWN/PROVISIONAL Usage和Stage。公开`GenerationRequestAuditRepository`尚未切换，不能把Phase3A描述为生产发送已受保护。
- Sol补充同Room双协程、两个Room实例同WAL文件和关闭重开后三类竞争证据：每次只允许一个100-token胜者，失败方四表零写入，重开后第三个60-token请求仍被150-token书级上限拒绝。
- DeepSeek运行`20260809-091858-713b52af`使用max、35分钟、无Token上限，约19分44秒正常完成，总Token4,043,379；0权限请求，修改范围符合任务包。Sol独立审查并补并发测试。
- 验证：core:database JVM90/90；API30/API35 reservation专项各11/11、数据库全量各237/237；`SECURITY_SCAN_OK`、diff check 0、remote为空。真实/Fake Provider0、物理设备写入0。
- 下一阶段切换唯一公开RequestIntent路径到v1 reservation并收敛调用方；随后实现Usage终值结算、UNKNOWN/RELEASE/迟到usage、跨日重预留、v0 Provider-open阻断和实际profile/adapter目的地匹配。plan继续未注册。

## 37. 2026-08-09 TASK-083 Phase 3B 公开 RequestIntent v1 完成

- 公开`RequestIntentDraft`删除caller日键；streaming/continuation prepare全部强制显式`RequestBudgetReservationDraft`，无旧overload/default/null fallback。公开audit只走Phase3A原子reservation入口。
- permit/claimed request内部绑定精确reservation ID；claim、mark sent和mark stream started分别要求持久Stage处于INTENT_RECORDED、INTENT_RECORDED、SENT，并重验Attempt v1、UNKNOWN/PROVISIONAL Usage、Job/Stage/Book和同一`RESERVED` reservation。legacy v0、错ID、缺失或RELEASED均联网前失败。
- core、feature与App旧测试调用已统一迁移到有限token budget；feature五个profile使用同connection/protocol/`https://example.invalid:443`测试证据。App维护测试使用随机本地policy/connection，不调用Provider。
- DeepSeek主体运行`20260809-100215-c47df2ef`在max、40分钟、无Token上限时超时且无final，但留下边界内WIP；Sol完成编译、安全加固和测试。窄迁移运行`20260809-110036-9b5c0f9b`约11分9秒正常完成，总Token3,209,194，Sol独立审查。
- API30全量首次发现双Room测试在第二实例onOpen重复安装trigger时偶发`SQLITE_BUSY`；只调整测试顺序为双实例先打开再写fixture。专项随后API30连续3次、API35连续2次各11/11。
- 最终验证：core:database JVM91/91、feature JVM140/140、统一JVM590/590；API30/API35数据库各240/240、生成各42/42、App维护专项各2/2；801-task统一门禁、Release/R8、源码与5 APK扫描、备份排除、diff检查通过。真实Provider0、物理设备写入0、remote为空。
- TASK-083仍进行中：下一阶段实现Usage终值settlement、UNKNOWN/明确未执行RELEASE/迟到usage、跨午夜未发送重预留与实际profile/adapter canonical destination匹配。`CHAPTER_PLAN_V1`继续未注册。

## 38. 2026-08-09 TASK-083 Phase 4A Usage 唯一原子结算完成

- enforcement v1的所有Usage写入继续使用既有`GenerationDao.recordUsage`唯一入口；Attempt/Usage/reservation在同一Room事务读取、CAS、回读。PROVISIONAL保持RESERVED；FINAL UNKNOWN转SETTLED但保留estimate；已知FINAL用终值替换accounted；迟到Provider同步升级Usage与reservation且保留首次settledAt。
- 结算不使用delta；相同FINAL replay只验证并返回同一行。实际token高于预留仍如实保存；金额缺失不伪造价格。legacy v0保持原Usage行为且没有reservation。
- Sol在DeepSeek实现上补充旧updatedAt+旧accounted全字段CAS、迟到升级写后回读和Book/daily period身份校验；全量回归暴露一项旧UNKNOWN断言，按新合同修正为SETTLED+estimate。
- DeepSeek`20260809-114953-91e6dcc2`使用max、45分钟安全上限、无Token上限，约14分48秒正常完成，总Token2,946,971，0权限请求。Sol独立审查后才确认阶段完成。
- 验证：双API reservation各23/23、数据库各252/252、生成各42/42、App恢复专项各2/2；统一JVM590/590；801-task离线门禁、Release/R8、安全扫描和备份排除通过。真实Provider0、物理设备写入0、remote为空。
- TASK-083仍进行中：下一阶段完成Provider明确未执行的唯一RELEASE、RELEASED后迟到usage、跨午夜未发送重预留与实际profile/adapter canonical destination匹配。`CHAPTER_PLAN_V1`继续未注册。

## 39. 2026-08-09 TASK-083 Phase 4B 明确未执行释放与迟到回补完成

- 只有既有恢复策略裁决`REQUEUE_PROVEN_NOT_EXECUTED`后，repository私有分支才调用专用Usage+release事务；普通`recordUsage`没有release开关。Attempt必须同审计时间已CAS为`FAILED_RETRYABLE`，Usage必须UNKNOWN/PROVISIONAL且无用量值，v1 reservation必须仍RESERVED/accounted=estimate。
- 专用事务把Usage封为UNKNOWN/FINAL并将reservation变为RELEASED/accounted清零；外层同一Room事务随后把Stage/Job回READY。错状态、身份、时间、旧accounted或回读不一致时五类状态整笔回滚。legacy v0只封账Usage。
- FINAL PROVIDER_REPORTED迟到时，`recordUsage`支持RELEASED→SETTLED，保留releasedAt、以迟到时间写首次settledAt并按终值恢复book/daily占用；精确replay不重复，UNKNOWN/ESTIMATED不得复活。
- DeepSeek`20260809-123220-9e8bc700`使用max、45分钟安全上限、无Token上限，约27分44秒正常完成，总Token8,817,163，0权限请求。Sol独立审查并修复已FINAL Usage可释放的过宽边界、补齐旧时间CAS和v0/v1写后验证。
- API30首次专项因SQL SUM无行返回NULL而暴露测试断言错误，修正断言后双API专项各30/30；数据库各259/259、生成各42/42、App恢复各2/2。一次API30 UTP/ADB瞬时超时启动0项，通道恢复后原样重跑2/2。统一JVM590/590、801-task离线门禁、Release/R8、安全扫描和备份排除通过；真实Provider0、物理设备写入0、remote为空。
- TASK-083仍进行中：下一阶段实现Provider-open跨午夜未发送重预留；随后完成实际profile/adapter canonical destination匹配。`CHAPTER_PLAN_V1`继续未注册。

## 40. 2026-08-09 TASK-083 Phase 5A/5B 跨午夜审计与 Provider-open 旧请求释放完成

- Phase5A冻结：换日检查不能在Provider-open之后做，也不能在claim内直接复用旧Attempt或创建替代Attempt；旧Attempt已经消耗一次attempt，达到`maxAttempts`时必须停止自动重试。非空续写草稿需在Phase5C复制到新的受保护工件。
- Phase5B在`claimForProviderOpen`事务内从当前DAILY head/revision的持久IANA zone与`validatedAt`计算日键。同日继续精确lease/来源/heartbeat；不同日绝不签发claimed permit、绝不打开草稿或adapter。
- 专用换日事务要求精确未发送v1 Attempt、UNKNOWN/PROVISIONAL空Usage、RESERVED且accounted=estimate的reservation、当前Stage/Job/租约/最新Attempt；随后原子写`FAILED_RETRYABLE`、UNKNOWN/FINAL、RELEASED/accounted0，并按剩余attempt把Stage/Job转READY或NEEDS_ACTION且清空租约。业务异常在事务提交后抛出。
- 旧permit replay和并发第二个claim按stale失败；SENT请求不会被释放。Executor设备测试证明adapter调用0且加密草稿revision/updatedAt/0字节不变。
- DeepSeek设计审计`20260809-132802-f5f3a58a`约18分19秒正常完成；Sol否决其复用attemptNo/在claim内创建替代请求建议。实现运行`20260809-135237-280a5812`达到45分钟护栏但留下真实WIP；测试运行`20260809-144801-9b9d1753`异常退出且无测试差异，测试由Sol完成。三次运行均无权限请求或Provider调用。
- 验证：API30/API35 reservation各35/35、executor各18/18；数据库全量各264/264、generation各43/43；统一JVM592/592；801-task离线门禁、Debug/Release、Lint/Vital、R8、安全扫描与备份排除通过。真实Provider0、物理设备写入0、Git remote为空。
- TASK-083仍进行中：Phase5C需在持久runner重新取得精确租约后创建新日新Attempt/reservation，并对非空续写种子做新的受保护工件复制；随后完成实际profile/adapter canonical destination匹配。`CHAPTER_PLAN_V1`继续未注册。

## 41. 2026-08-09 TASK-083 Phase 5C 新日替代请求准备完成

- 新增专用replacement入口；Phase5B回READY后，queue重新claim Job并领取当前Stage lease，调用方必须提供包含同owner精确Job+Stage token、状态和heartbeat的真实执行快照。数据库事务会重新读取最新父Attempt、UNKNOWN/FINAL空Usage、RELEASED/accounted0 reservation、current cursor、attemptCount和未过期双租约。
- 新请求使用唯一Attempt/Usage/reservation/artifact、`attemptNo=parent+1`和`retryParentAttemptId=parent`；请求快照、input hash、request limit、estimate、币种、来源版本和connection沿用父请求。当前disclosure可有更晚acceptedAt，但canonical destination、protocol、version和binding不得变化。
- 当前DAILY policy从新createdAt派生不同日键。新reservation只占新日daily，但重新计入同书非RELEASED总额；新日quota拒绝时candidate/Attempt/Usage零写入，Stage保持PREPARING并保留当前lease。
- 旧受保护草稿在有界ByteArray内读取/清零，空或非空都创建新的加密artifact；禁止明文临时文件或共享旧引用。数据库失败删除新artifact，旧descriptor/content不变。普通prepare遇到换日父Attempt直接失败，不能绕过专用复制和双租约。
- DeepSeek只读设计审计`20260809-155744-bbeaf68d`使用max、无Token上限，约16分12秒正常完成，总Token4,180,592，0写入、0权限请求。Sol采用其工件先建/数据库失败清理与事务内父证据重验方向，并加固调用方授权、普通路径旁路和current disclosure约束。
- 验证：API30/API35 reservation各40/40、executor各21/21；数据库全量各269/269、generation各46/46；统一JVM592/592。完整离线门禁记录于工作汇报135。真实Provider0、物理设备写入0、Git remote为空。
- TASK-083仍未关闭：下一阶段完成Executor实际`ProviderConnectionProfile`、adapter protocol与reservation冻结connection/canonical destination/protocol的精确匹配。Phase5C只交付repository级准备能力，没有注册total runner路线；`CHAPTER_PLAN_V1`继续未注册。

## 42. 2026-08-09 TASK-083 Phase 5D 实际 Provider-open 目的地匹配完成

- 新增脱敏、短生命周期`ProviderOpenDestinationEvidence`；actual connection/canonical origin/protocol由实际`ProviderConnectionProfile`派生，不能由reservation自证，也不保留原始base URL。
- `claimForProviderOpen`生产入口强制要求evidence，并在同一事务中比较实际证据、当前动态accepted disclosure和reservation冻结的connection/destination/protocol/version/binding/acceptedAt。错误目的地优先于同日heartbeat和跨日release，失败时Attempt/Usage/reservation/Stage/Job零写入且permit可重试。
- executor先验证profile/adapter protocol，再用同一不可变profile派生evidence领取claim；打开受保护草稿前再次派生并匹配。claimed send、mark sent和mark stream started持续绑定同一证据。
- DeepSeek只读设计审计`20260809-165246-d326a6d6`使用max完成，0写入/0权限请求；其最终输出编码异常但边界建议可用。实现、修正和最终验收由Sol完成，尤其将关键匹配放在工件/网络之前，而不是网络之后。
- 验证：core:model新证据测试通过；API30/API35 reservation各43/43、executor各23/23；数据库全量各272/272、generation各48/48；801-task统一离线门禁、Debug/Release、Lint/Vital、R8、安全扫描和备份排除通过。真实Provider0、物理设备写入0、Git remote为空。
- TASK-083按持久预算与实际发送目的地门禁边界正式完成。`CHAPTER_PLAN_V1`仍未注册，total runner尚未闭环；下一项是TASK-064 chapter-plan exact-token远程执行、严格解析、DEC-068原子提交与initial DRAFT。

## 43. 2026-08-09 TASK-064 Phase 2E5A chapter-plan exact-token 请求准备完成

- 审计发现Phase2B route snapshot携带exact Job+Stage双租约，但通用`prepareBeforeSend`只消费Stage token；若直接注册plan会在route解析与RequestIntent之间留下Job token/cursor/attempt TOCTOU旁路。
- 新bound preparation只接受`CHAPTER_PLAN_V1` snapshot，并在创建v1 reservation、Attempt、UNKNOWN/PROVISIONAL Usage和推进Stage的同一Room事务内重读Job/Stage，核对route、current cursor、双token、同owner、heartbeat、60秒租约与attempt上下界。
- generic prepare现在拒绝普通`BUILD_CHAPTER_PLAN`，损坏plan来源也不能回落；`firstChapterBootstrap`保持既有兼容。公开streaming负例会删除先建的新加密草稿，数据库零半状态；bound正例由Attempt唯一引用新工件。
- 本阶段由Sol直接实现和审查，未调用DeepSeek：变更位于数据库授权/原子性边界，且现有WIP已明确。真实/Fake Provider0、物理设备写入0、schema/migration/DAO/registry变化0。
- 验证：API30/API35 reservation各47/47，数据库模块各276/276，generation模块各48/48；core database与generation JVM通过；801-task离线门禁、Debug/Release、Lint/Vital、R8、安全扫描和备份排除通过。
- `CHAPTER_PLAN_V1`继续未注册。下一阶段Phase2E5B先确定权威`ChapterPlanExpectationV1`与请求快照来源，避免把每个成人档章节都错误标成相关场景；随后接request factory、Fake streaming/严格解析、DEC-068原子提交与initial DRAFT。

## 44. 2026-08-09 TASK-064 Phase 2E5B 权威逐章场景意图设计冻结

- 唯一权威来源确定为 arc-window v2 的逐章 brief，并持久化进不可变 CHAPTER OutlineNode；不能从全书内容档、人物存在性或 chapter-plan 模型临时决定本章相关性。
- 每章冻结 `relevantSceneIntent=NOT_APPLICABLE|PLANNED`、`plannedRelevantSceneCount=0|1..8` 和最多12个不重复 `participantCharacterIds`。普通 plan expectation 必须精确约束相关场景数量与计划参与者覆盖，不能少写、加戏或换人。
- 创建、Provider-open、commit 都从目标 v2 node、当前 Story Bible、Prompt Bundle、context/progression 重算同一规范 expectation；Stage input 冻结 canonical JSON/hash。未知/非人物/未确认成年/年龄缺失/<18参与者全部失败关闭。
- 新合同为 `arc-plan.v2` / `zhijuan.arc-window-policy.v2` / CHAPTER node schema 2；不新增 Room 表。旧 v1 窗口不猜测回填、不默认为无相关场景，普通 plan Job 创建时拒绝并要求重建窗口；首章 fast-lane 独立兼容。
- DeepSeek只读审计`20260809-182724-905084d6`使用max、无Token上限，约5分30秒完成；前后工作树均273项，0差异、0权限请求、0真实Provider。Sol采纳权威意图与Stage冻结方向并定稿字段、上限和失败关闭边界。
- 下一步Phase2E5C实现arc-window v2输出、验证、持久化和测试；在Fake执行、严格plan提交与initial DRAFT闭环前，`CHAPTER_PLAN_V1`继续未注册。

## 45. 2026-08-11 项目总交接与备份检查点

- 新增 `docs/ai/HANDOFF-2026-08-11.md`，把产品需求、组合式能力修正、代码真实完成度、TASK-064 断点、测试基线、真实 API 规则、下一阶段顺序和恢复方法整理为可脱离对话使用的总交接。
- 最新产品裁决优先于 Phase 2E5B 的单一场景合同：在 Phase 2E5C 前先完成 `BookCapabilityManifest`、章级 capability activation、统一 StoryEvent/state delta/plot obligation 和冲突优先级的最小合同。成人场景、恋爱、系统、修仙、道具等是可组合能力，不是互斥题材，也不能因全书启用就在每章注入上下文。
- 当前代码断点不变：Phase 2E5A exact-token chapter-plan 请求准备已完成；Phase 2E5B 只有设计；`CHAPTER_PLAN_V1` 未注册；Fake/真实 Provider 纵向闭环、DEC-068 提交、initial DRAFT 和总 runner 尚未完成。
- 下一可交付目标是 3～5 章、可安装、可从 App 内配置并使用真实 DeepSeek V4 Flash、能够边生成边阅读和重启恢复的亲手验证 APK，不要求立刻完成全部正式版功能。
- 本次只整理、提交和备份，没有继续实现功能，没有调用织卷 App 真实 Provider，也没有重新运行完整 Gradle/模拟器矩阵。最近完整验证仍以 Phase 2E5A 的双 API 30/35 和 801-task 离线门禁记录为准。
- 整理前现场为 96 个已跟踪修改、252 个未跟踪文件；Kotlin 399 个、约 112,031 行，其中生产约 64,261 行、测试约 47,321 行。所有非忽略 WIP 将作为一个完整 Git 快照提交；`.codex/deepseek-key.local`、缓存、数据库、APK 和签名材料继续排除。
- 用户本次授权创建 Git 备份并上传私有远程。备份完成后的远程、最终 HEAD 和离线 bundle 校验信息记录在 `docs/ai/HANDOFF-2026-08-11.md` 与 `D:\gptuser\backups\ai-novel-reader-app2\2026-08-11\BACKUP-MANIFEST.md`。
- 备份完成后不要自动继续写功能代码；先按总交接恢复上下文并等待用户明确的开发/真实测试指令。
