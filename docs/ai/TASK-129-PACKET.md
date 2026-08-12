# TASK-129 纯文本任务包：3～5 章自动队列

## 任务身份

- 任务 ID：`TASK-129`
- 仓库根目录：`D:\gptuser\projects\ai-novel-reader-app3`
- 基准：`main` / `0af179e`
- 当前未提交改动：用户维护的 `AGENTS.md`、`.claude/`、`CLAUDE.md`，不得修改或纳入提交。
- 执行者：Sol；本任务不需要调用 DeepSeek。

## 目标

只在 `:feature:generation` 增加有界章节循环：复用现有 `GenerationTotalRunnerPort` 顺序执行 3～5 个已冻结章节 Job；仅当前章正式完成后，才请求准备下一章；任何暂停、恢复、竞争或人工处理状态立即停止，不开启后章 Provider 请求。

## 当前现场

- TASK-128 已有唯一 Room persistent runner、五条有限 route、FGS/WorkManager 共用入口和 Fake 单章闭环。
- 每章仍是独立 Job；现有 runner 只负责一个 Job，不应扩大或修改其高影响接口。
- 普通用户点击开始、首章/下一章冻结起点和 UI 接线属于 TASK-130。

## 模块范围

- 主模块：`:feature:generation`。
- 允许修改：该模块的章节序列编排器、Fake 专项测试和 TASK-129 文档。
- 禁止修改：`:core`、`:data`、`:provider`、其他 feature、`:app`、数据库 schema、UI、真实 Provider。
- 禁止新增模块、feature 实现依赖、第二个 Stage runner、通用 route/fallback 或运行时 skill。

## 不可破坏约束

- 章节循环只调用既有 `GenerationTotalRunnerPort`；不复制其 Stage 游标、lease、heartbeat 或 Provider-open 逻辑。
- 请求准备下一章前，当前 Job 结果必须为 `COMPLETED`。
- 下一章准备端口必须按书和 ordinal 幂等；相同请求只能得到同一冻结 Job，冲突失败关闭。
- 单次目标只能是 3～5 章，ordinal 必须严格连续且唯一。
- 非 `COMPLETED` 结果直接返回；不得准备、执行或猜测下一章。
- 暂停期间 Provider-open 增量必须为 0；恢复仍从持久 Job 重新交给同一个 runner。
- Fake only；App 内真实 Provider 调用 0。

## 实施批次

1. 新增最小章节序列结果、准备端口和有界循环；不改现有 runner 接口。
2. JVM 专项验证 3～5 章边界、连续 ordinal、只在正式完成后准备下一章，以及暂停/恢复不越章。
3. 一个 Room + Fake Android 连续场景复用现有单章闭环，验证 3～5 章、正式正文可读、义务/状态证据和无重复 Provider-open。
4. 更新状态、当前上下文和工作汇报；运行最小模块测试、边界检查和安全扫描。

## 验收

- [ ] 3、4、5 章目标均受合同约束；2 或 6 章在执行前拒绝。
- [ ] 每章 Job 仅由既有 runner 执行，后章只在前章 `COMPLETED` 后准备。
- [ ] ordinal 连续且不重复；同一下一章准备 replay 不产生第二 Job。
- [ ] 同一 3～5 章场景内一次暂停/恢复，暂停时不新增 Provider-open，前章正式版本仍可读。
- [ ] 混合 fixture 的义务和人物/关系/机制/道具等状态变化可按每章证据重放。
- [ ] 真实 Provider 调用 0。

## 最小验证

```powershell
gradle :feature:generation:testDebugUnitTest
gradle :feature:generation:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<TASK-129专项类>
powershell -ExecutionPolicy Bypass -File scripts/verify-module-boundaries.ps1
powershell -ExecutionPolicy Bypass -File scripts/security-scan.ps1 -SkipArtifacts
```

TASK-129 不重复全量双 API、Release/R8 或 20 章测试；这些没有覆盖本次新增风险。

## 停止条件

- 若必须修改现有 `GenerationTotalRunnerPort`、数据库事务、真实 Provider 或 UI 才能继续，停止扩展并把缺口交给 TASK-130。
- 若专项测试证明现有正式提交无法提供连续性证据，只修直接阻断；不得顺手扩展通用架构。
