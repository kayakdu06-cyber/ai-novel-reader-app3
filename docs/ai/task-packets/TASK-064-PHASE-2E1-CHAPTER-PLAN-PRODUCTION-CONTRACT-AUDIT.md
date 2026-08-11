# TASK-064 Phase 2E1：普通章节计划生产合同审计

## 任务身份

- 任务 ID：`TASK-064 / Phase 2E1 normal chapter-plan production contract audit`
- 仓库根目录：`D:\gptuser\projects\ai-novel-reader-app2`
- 基准分支与 HEAD：`main @ 8ce774429da1c3f7139a221bc241c34d81a2efdd`
- 当前未提交改动：220 条连续 WIP；任务开始时 `git status --short` 的 SHA-256 为 `aea947bd69aade7bd206865966297c04f38390d2f010c5e27e61cbd89486f94f`；不得 reset、clean、checkout、覆盖或整理任何改动
- 执行模型：DeepSeek（纯文本）

## 运行预算

- 推理等级：`max`
- 最长运行时间：30 分钟；这是本次明确窄化后的首次审计，尚不满足 45 分钟条件。若它仅因正常推理在 30 分钟超时且没有权限、范围或重复失败阻塞，Sol 可把同一窄任务后续放宽到 45 分钟
- 累计 Token 上限：无
- 预计读取文件数与明确清单：基础 35 个文件（其中 10 个文档只读明确章节）；允许用限定 `rg` 追踪直接调用者，总数不超过 45
- 预计执行命令/测试数：10～30 个只读 `rg` / `Get-Content` / `git`；不构建、不测试、不修改
- 提前停止条件：需要访问其他项目、读取密钥/小说正文/个人数据、修改文件、读取超过 45 个文件，或必须先做未声明的跨模块架构决策才能继续

## 目标

只审计由 `ChapterContextAssemblyJobFactory` 创建、在 context 成功后激活的普通 `BUILD_CHAPTER_PLAN` Stage。完整追踪它从冻结来源、runner route、Provider-open、请求审计、结构化输出、持久提交、下一 `DRAFT_CHAPTER` Stage，到崩溃恢复和精确 replay 的生产链，并明确哪些环节已经存在、哪些只是通用底层原语、哪些完全不存在。

必须重点回答：普通 `chapter-plan.v1` 是否拥有独立严格 schema/parser、业务交叉校验、持久化映射/提交事务和 initial-draft successor；若没有，应从哪些请求前已经存在的 ID、版本和 hash 组成独立 `zhijuan.chapter-plan-source.v1`，结果应写入现有哪类权威数据，而不是把首章快车道 `first-chapter-bootstrap.v1` 或窗口规划 `arc-plan.v1` 错当成普通章内计划。

本次只输出证据审计、缺口矩阵和最小实现切片，不修改代码，不注册 route，不调用 Provider。

## 当前现场与已有 WIP

- 已存在：
  - Phase 2B `GenerationRunnerCurrentStageRouteSnapshot` 绑定 current `RUNNING + PREPARING` exact Job+Stage 双租约；
  - Phase 2D3 registry 已安全注册纯本地 `CHAPTER_CONTEXT_ASSEMBLY_V1`；context 成功后原子激活同 Job 的普通 `BUILD_CHAPTER_PLAN`；
  - 普通 plan Stage 当前冻结 `promptBundleVersion`、`outputSchemaId=chapter-plan.v1`、context Stage/input/policy/manifest 和 progression gate，但没有 `sourcePolicyVersion`；
  - `ChapterContextAssemblyRepository.requireProviderOpenAllowed` 能在 Provider 打开前重新构建并核对 ContextSnapshot payload；
  - `PromptBundleProviderBridge.prepare` 能为 `BUILD_CHAPTER_PLAN` 返回通用 Remote 准备结果；
  - 通用 RequestIntent、Attempt、Usage、protected artifact、严格结构化校验、单次格式修复和 UNKNOWN 原语已经存在。
- 已存在的验证：数据库 JVM 86/86、生成 JVM 131/131；API 30/API 35 数据库各 218/218、生成各 42/42；统一离线门禁 801 actionable tasks 通过。
- 已知缺口：
  - resolver 无普通 chapter-plan route，runner 会在下一轮 route resolution 失败关闭；
  - 全仓生产搜索只发现 `chapter-plan.v1` 的 Prompt Bundle 声明和 context factory 引用，尚未发现普通 plan 的严格输出 contract/parser/commit repository；
  - initial candidate DRAFT 也尚无请求前可得的独立冻结合同；不得把 plan audit 的缺口用伪造 candidate binding 掩盖。
- 必须延续：Phase 2A～2D3 route/exact-token/registry、RequestIntent/Usage、artifact、validation、UNKNOWN、progression gate 和 context Provider-open 双门禁；不得从零复制第二套远程执行链。

## 必读资料

1. `AGENTS.md`
2. `docs/24-AI-DEVELOPMENT-PROTOCOL.md`
3. `docs/ai/CURRENT-CONTEXT.md`（重点 21～28 节）
4. `docs/06-AI-GENERATION-SYSTEM.md`（重点 2、4.1、6、21～23、34～40 节）
5. `docs/07-API-ADAPTER-SPEC.md`（重点 24～29 节）
6. `docs/08-TECHNICAL-ARCHITECTURE.md`（重点 16～20、31～37 节）
7. `docs/09-DATA-MODEL.md`（重点 DATA-006、15～18 节）
8. `docs/10-STATE-MACHINES.md`（重点 5.3～5.7、16～20、37～43 节）
9. `docs/13-ERROR-HANDLING.md`（重点 23～27 节）
10. `docs/14-COST-CONTROL.md`（重点 3、7、12 节）
11. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterContextAssemblyJobFactory.kt`
12. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterContextAssemblyRepository.kt`
13. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterProgressionGateRepository.kt`
14. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationRunnerStageRouteResolver.kt`
15. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationRunnerExecutionLeaseRepository.kt`
16. `feature/generation/src/main/kotlin/app/zhijuan/feature/generation/GenerationRunnerExecutorRegistry.kt`
17. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationRequestAuditRepository.kt`
18. `feature/generation/src/main/kotlin/app/zhijuan/feature/generation/PromptBundleProviderBridge.kt`
19. `core/task/src/main/kotlin/app/zhijuan/core/task/PromptBundleContract.kt`
20. `feature/generation/src/main/kotlin/app/zhijuan/feature/generation/AuditedStreamingProviderExecutor.kt`
21. `feature/generation/src/main/kotlin/app/zhijuan/feature/generation/StructuredOutputValidationCoordinator.kt`
22. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationOutputValidationRepository.kt`
23. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/FirstChapterFastLaneJobFactory.kt`
24. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/FirstChapterFastLaneCommitRepository.kt`
25. `feature/generation/src/main/kotlin/app/zhijuan/feature/generation/FirstChapterBootstrapStructuredOutput.kt`
26. `feature/generation/src/main/kotlin/app/zhijuan/feature/generation/ArcWindowPlanningStructuredOutput.kt`
27. `feature/generation/src/main/kotlin/app/zhijuan/feature/generation/ArcWindowPlanningPersistenceMapper.kt`
28. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ArcWindowPlanningCommitRepository.kt`
29. 直接相关测试：
    - `core/database/src/test/kotlin/app/zhijuan/core/database/generation/ChapterContextAssemblyJobFactoryTest.kt`
    - `core/database/src/androidTest/kotlin/app/zhijuan/core/database/ChapterContextAssemblyDatabaseTest.kt`
    - `core/database/src/test/kotlin/app/zhijuan/core/database/generation/GenerationRunnerStageRouteResolverTest.kt`
    - `feature/generation/src/androidTest/kotlin/app/zhijuan/feature/generation/GenerationRunnerExecutorRegistryAndroidTest.kt`
    - `feature/generation/src/test/kotlin/app/zhijuan/feature/generation/FirstChapterBootstrapStructuredOutputTest.kt`
    - `feature/generation/src/test/kotlin/app/zhijuan/feature/generation/ArcWindowPlanningStructuredOutputTest.kt`

基础清单共 35 个文件，其中 10 个文档只读取明确章节。不得超过 45 个实际文件。允许用以下限定搜索追踪直接调用者：

```powershell
rg -n "chapter-plan\.v1|BUILD_CHAPTER_PLAN|ChapterContextAssemblyJobFactory|loadForChapterPlanStage|requireProviderOpenAllowed|ValidatedOutputCommitPermit|OutlineNodeType\.CHAPTER|DRAFT_CHAPTER" core/database/src core/task/src feature/generation/src -g "*.kt"
```

不得递归扫描 reports、备份、缓存、历史会话、build 产物或其他项目；需要扩展文件时在回交的“假设”中列出。

## 范围

允许修改：

- 无；本任务为只读审计。即使普通受限启动模式允许 workspace write，也不得使用任何写入命令或编辑工具。

明确不在范围：

- 不新增或修改 Kotlin、Gradle、测试、文档、schema、migration、DAO、registry；
- 不实现 `chapter-plan.v1`，不实现 initial draft，不接其他 remote route；
- 不调用真实或 Fake Provider，不运行 Gradle/模拟器；
- 不读取、显示或推测 API Key、host、小说正文、prompt payload、ContextSnapshot 内容和业务 ID/hash；
- 不访问 `D:\gptuser\projects\ai-novel-reader`。

## 不可破坏的约束

- 项目隔离：只读 app2；任务前后 status 的条目和 SHA-256 必须一致。
- 身份：普通 context-based chapter plan、首章快车道 bootstrap、窗口 `arc-plan.v1` 必须是三条不同路线；不能仅凭 `BUILD_CHAPTER_PLAN` phase 分发。
- 租约：未来 executor 必须消费 Phase 2B exact Job+Stage token；同 owner 不等于同 token，不能重新 acquire 或替换 token。
- 状态机：必须区分 PREPARING 新请求、已经持久化的 RequestIntent/Attempt 恢复、VALIDATING/COMMITTING 本地恢复、UNKNOWN 用户裁决和 SUCCEEDED replay。
- 数据库：必须明确 plan 输出写入何种权威对象、Stage/Usage/后继 DRAFT/cursor 由哪个单一事务提交；不能假设跨 repository 自动原子。
- 联网与费用：0 Provider 调用、0 费用；普通 plan 真正联网前仍必须完成预算预留、目的地确认、RequestIntent 和一次性 send permit。
- 隐私：回交只使用类名、方法名、状态名和通用字段名，不复制正文、prompt、manifest/payload 或具体业务标识。
- 兼容：不把 Android 测试 helper、首章 bootstrap 或 arc window commit 冒充普通章内计划生产入口。

## 实施要求

1. 输出一条从“context 成功激活 plan”到“plan 持久提交并激活 initial DRAFT”的生产调用链表；每步写明类/方法、输入证据、状态前后、是否网络、是否事务，以及当前状态是完整/底层原语/测试装配/不存在。
2. 对以下能力逐项标记“生产完整 / 仅底层原语 / 仅测试装配 / 不存在”：
   - 请求前可得的独立 plan source policy 与严格 parser；
   - runner route resolution 和 exact-token executor；
   - Prompt Bundle/场景成年人门禁/ContextSnapshot 装配；
   - 目的地与三层预算预留；
   - RequestIntent/Attempt/Usage/一次性发送；
   - Provider structured non-streaming 或 streaming 的实际执行选择；
   - `chapter-plan.v1` provider schema、严格 parser 和业务交叉校验；
   - validated artifact 的持久映射和业务提交；
   - initial DRAFT successor 的冻结来源和 cursor 推进；
   - FORMAT_INVALID 单次修复、拒绝/失败/取消、UNKNOWN、崩溃恢复和 SUCCEEDED replay。
3. 专节比较三条容易混淆的路线：普通 context-based `chapter-plan.v1`、`first-chapter-bootstrap.v1`、`arc-plan.v1`。列出可复用的通用机制和绝对不能复用的业务 schema/commit。
4. 根据请求前已经存在的持久事实，提出 `zhijuan.chapter-plan-source.v1` 的最小字段集合。每个字段必须说明来源、为何需要、在哪个门禁重验；禁止使用 Provider 返回后才知道的 candidate version/hash。
5. 判断普通 plan 输出应当：a) 新建独立数据实体；b) 形成新 OutlineRevision/CHAPTER node；c) 只作为受保护 artifact + Stage output reference；或 d) 其他现有权威结构。必须引用当前数据模型、下游 scene execution contract 和 replay 需求给出证据，不得只凭偏好。
6. 列出 registry 现在直接注册该 route 会产生的 P0/P1 风险，至少覆盖重复付费请求、错 schema 提交、丢失 plan 后仍推进 DRAFT、context/current head 漂移、UNKNOWN 自动重发和成功后重复写入。
7. 给出最小实现顺序，拆成 2～5 个后续子阶段；每阶段限定 1～4 个生产文件和必要测试，写明依赖和退出证据。若现有产品文档不足以裁决持久模型，明确指出需要 Sol 先补哪条 ADR，而不是擅自选型。

## 验收标准

- [ ] 生产入口与通用底层原语、测试 helper 完全区分。
- [ ] 普通 plan、bootstrap 和 arc plan 三条合同没有混用。
- [ ] source identity 只使用请求前持久事实，并覆盖 context/progression/prompt/target/current plan 失效。
- [ ] Attempt、费用、Provider、validation、业务 commit、DRAFT successor、cursor 和恢复所有者明确。
- [ ] 能明确回答当前是否缺少 `chapter-plan.v1` schema/parser/commit，而不是把字符串声明当成实现。
- [ ] 后续实现切片不新增第二套远程状态机，不使用 phase fallback，不伪造 Provider 输出。
- [ ] 无文件改动、无构建、无 Provider 调用、无敏感内容输出。

## 验证命令

```powershell
git status --short
git diff --name-only
```

只用于确认任务前后没有新增修改。Gradle 与模拟器按任务范围不运行，必须如实写明。

## 回交格式

请严格按以下标题返回：

1. `完成内容`
   - 先给端到端生产调用链表
   - 再给能力矩阵
   - 再给三路线比较、source policy 字段、持久模型结论、P0/P1 风险和后续切片
2. `修改文件`：必须为“无”
3. `验证`
4. `未完成/风险`
5. `需要 Sol 处理`
6. `假设`

不要宣布 TASK-064、Phase 2E 或 chapter-plan route 完成，不要修改正式状态。
