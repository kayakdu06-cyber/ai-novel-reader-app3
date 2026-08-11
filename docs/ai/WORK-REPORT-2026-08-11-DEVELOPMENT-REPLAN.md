# 工作汇报：开发路线重排与文档基线

> 日期：2026-08-11
> 项目：D:\gptuser\projects\ai-novel-reader-app3
> 工作类型：只读代码/架构审计 + 产品与开发文档；未开发功能、未调用真实 Provider

## 1. 本阶段结果

已把原先分散在可靠性路线、组合小说路线、TASK-064 WIP 和 app3 模块化记录中的信息收束为一套新的权威开发入口。

核心变化：

- 开发目标从“继续做深底层”改为“先完成 3–5 章可亲手验证纵向切片”。
- TASK-064 已完成 WIP 全部保留，剩余工作拆入 TASK-125～128，不从零重写。
- 外部写作 skill 不直接安装进 App，提炼为不可执行、版本化的 WritingPolicyPack。
- 组合题材使用书级能力清单、章级按需激活、叙事义务和统一状态变化。
- 正常章节目标采用有限远程调用和一次合并章后分析。
- DeepSeek V4 Flash 真实测试提前到第一份可验证 APK 之前，并设置分级闸门。
- 当前唯一下一任务确定为 TASK-121。

## 2. 审计证据

### 2.1 项目身份

- Git 根目录：D:/gptuser/projects/ai-novel-reader-app3。
- 远端：公开 GitHub app3 仓库。
- 没有修改 app2。

### 2.2 代码规模

- 10 个 Gradle 模块。
- 260 个受 Git 跟踪的 Kotlin 文件。
- Kotlin 约 60,100 行。
- 生产 Kotlin 约 53,881 行。
- 测试 Kotlin 约 5,803 行。

### 2.3 真实接线

已由源码确认：

- App 导航已接首次说明、连接、创建和费用确认。
- 费用确认仍显示“生成执行器尚未接入”。
- GenerationController 只有查询、暂停和停止，没有启动。
- ForegroundGenerationGateway 没有启动 total runner。
- ReaderSessionCoordinator、LibraryCatalog、TemplateCatalog 有代码和测试，但没有完整生产 UI 接线。
- GenerationRunnerStageRouteResolver 能识别多类 route，但 registry 仍是有限白名单。
- 普通 chapter-plan 已做到 exact-token 请求准备，后续 Fake 执行、严格提交和 initial DRAFT 交接未完成。

### 2.4 GitNexus 使用情况

- 按 GitNexus skill 对 app3 建立代码知识图谱。
- 索引规模：16,775 nodes、35,780 edges、324 clusters、300 flows。
- 精确符号 context 用于核对 GenerationRunnerStageRouteResolver、PromptBundleCatalogV1、ReaderSessionCoordinator、TemplateCatalog、BookCreationGateway 和 ForegroundGenerationGateway。
- 本机索引缺少 FTS 扩展，BM25 概念查询不可用；因此调用关系结论同时用 rg 和源码直接复核，未把空概念搜索当作“没有代码”。

## 3. 新增文档

| 文件 | 作用 |
|---|---|
| docs/27-DEVELOPMENT-MASTER-PLAN-V3.md | 当前权威总规划、现状、阶段、速度、工期和成功定义 |
| docs/28-WRITING-POLICY-PACK-SPEC.md | 创作策略包、组合能力、叙事义务、状态变化和 Prompt 编译 |
| docs/29-HANDS-ON-VERTICAL-SLICE-SPEC.md | 3–5 章可亲手验证 APK 的产品流程和 P0/P1 验收 |
| docs/30-IMPLEMENTATION-BACKLOG-V2.md | TASK-120～138 的任务、边界、依赖、工时和唯一下一步 |
| docs/31-REAL-API-QUALITY-PERFORMANCE-TEST-PLAN.md | DeepSeek V4 Flash 分级真实测试、安全、质量、速度和费用 |

## 4. 更新文档

- docs/README.md：新文档加入索引，25/26 标为历史输入。
- docs/02-FEATURE-INVENTORY.md：增加 FEAT-124～129。
- docs/18-DECISION-LOG.md：增加 DEC-076～080。
- docs/19-IMPLEMENTATION-BACKLOG.md：标为历史任务登记，当前顺序转到 30 号。
- docs/20-TRACEABILITY-MATRIX.md：增加新路线需求、设计和测试映射。
- docs/22-WORK-STATUS.md：记录 TASK-064 拆分与新权威入口。
- docs/ai/CURRENT-CONTEXT.md：写入下一任务和后续 AI 启动入口。

## 5. 本阶段未做

- 没有修改 Kotlin、Gradle、数据库或 UI。
- 没有运行 assembleDebug/test；本阶段只有 Markdown 变更，不声称代码获得新验证。
- 没有调用 DeepSeek 编码 Agent。
- 没有调用织卷 App 内真实 API。
- 没有安装外部小说 skill 到 App。
- 没有处理用户/其他工具现存的 AGENTS.md、.claude/ 和 CLAUDE.md 变化。

## 6. 下一步

唯一下一任务：TASK-121。

边界：

- 先实现纯本地 WritingPolicyPack 领域合同、来源、片段目录、规范 hash 和编译器骨架；
- 不做数据库大迁移；
- 不联网；
- 不做 UI；
- 不执行第三方脚本；
- 通过测试、文档、commit 和 push 后再进入 TASK-122。

## 7. 风险

- 新合并章后分析需要与现有多个记忆/追踪仓库做严谨映射，不能为省调用破坏原子性。
- 现有 60,000 行代码在纵切接通前不适合按行数大删。
- 第一份真实 API 结果可能要求调整 schema 或 Prompt；这正是提前做 TASK-132 的原因。
- 拆分后的可亲手验证工作量为 102–178 个有效工程小时；只有任务不夹带 P1 完善且合理使用有界并行时，才可能压低自然开发时间。
