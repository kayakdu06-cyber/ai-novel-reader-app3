# TASK-128 任务包：单章 persistent total runner

> 状态：已完成（2026-08-13）。实现与验证证据见 `WORK-REPORT-2026-08-13-TASK-128.md`。

## 任务身份

- 任务 ID：TASK-128
- 仓库根目录：`D:\gptuser\projects\ai-novel-reader-app3`
- 基准分支与 HEAD：`main` / `da53335`
- 当前未提交改动：用户持有的 `AGENTS.md`、`.claude/`、`CLAUDE.md`；另有 TASK-127 报告空白修正
- 执行者：Sol；允许 DeepSeek 仅做边界明确的纯文本代码审计/实现

## 目标

复用现有 Room Job/Stage 游标、双租约、有限 route registry 和 TASK-125～127 executor，形成唯一 persistent total runner。用 Fake Provider 完成一章从现有 plan 起点到正式 ChapterVersion 提交的闭环。

## 当前现场与缺口

- 已有 READY Job/current Stage 查询、双 lease、route snapshot、有限 registry、context/plan/body/post-analysis/final executor 接口。
- slim 输入删除了已完成 WIP `GenerationRunnerQueueRepository` 和 `GenerationRunnerHeartbeatEnvelope`，但 DAO、状态机和文档仍依赖它们；这是 total runner 的直接编译/运行阻塞，需原模块最小恢复。
- registry 已登记五条纵切 route，但没有唯一 Job 循环。
- plan/body/post-analysis 只有 executor 接口，生产 exact-token 参数装配尚未统一。
- FGS 与 WorkManager 目前只做控制/维护，尚未调用同一个 runner。

## 模块锁

主模块：`:feature:generation`。

允许的必要修复：`:data` 仅恢复被 slim 删除、但 DAO 和已确认架构仍引用的持久队列仓库，以及专项闭环发现的候选谱系/route 身份严重阻断；不得新增表、migration 或第二游标。

禁止修改：`:core`、`:provider`、其他 feature、`:app`、UI、真实连接和 API 密钥。

## 不可破坏约束

- 只有一个生产 dispatcher/runner；route 只来自 Room exact-token snapshot。
- 未登记 route 显式失败，不得 fallback。
- runner 不提前推进 cursor；只由各业务事务推进 Stage/Job。
- Job/Stage exact token、heartbeat、控制意图、Attempt/Usage 和预算门禁继续生效。
- pause/stop 不开启下一远程 Stage；UNKNOWN 不自动重发。
- Fake only；真实 Provider 调用 0。
- 不新增模块、表、通用插件、抽象扩展点或 UI。

## 实施批次

1. `:data`：恢复持久 READY queue 的扫描、竞争领取和同 token 续跑。
2. `:feature:generation`：恢复 heartbeat envelope；新增唯一 runner 的有界 Job 循环和确定性结果模型。
3. `:feature:generation`：完成五条 registry route 的 exact-token executor 组装，正常本地提交后继续读 current Stage。
4. `:feature:generation`：FGS/WorkManager 共用 runner 入口；Fake 单章端到端。

## 最小验收

- PREPARING/STREAMING/ANALYZING/COMMITTING 使用确定性持久状态夹具恢复，不重复做四次物理杀进程。
- 两个 runner 竞争同一 READY Job，只有一个取得并写入。
- 一个 Fake 单章用例覆盖 plan → body → post-analysis → final commit，最终 Job/Stage/Attempt/Usage/Chapter 一致。
- 同一端到端用例注入一次 pause 或 stop 安全点，证明不会开启后继请求。
- FGS 与 WorkManager 只调用同一 runner port，不维护第二套状态。
- 相关 JVM + 一个真实交接 Android 集成测试、模块边界、安全扫描、`assembleDebug test` 通过。

## 明确不做

- 下一章自动创建（TASK-129）。
- UI 开始按钮（TASK-130）。
- 书架/阅读器（TASK-131）。
- App 内真实 Provider（TASK-132）。
- 物理杀进程（TASK-133）。

## 验证

```powershell
gradle :data:testDebugUnitTest :feature:generation:testDebugUnitTest
gradle :feature:generation:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<TASK-128专项类>
powershell -ExecutionPolicy Bypass -File scripts/verify-module-boundaries.ps1
powershell -ExecutionPolicy Bypass -File scripts/security-scan.ps1 -SkipArtifacts
gradle assembleDebug test
```

## 回交

每批次记录：实际差异、验证、风险、真实 Provider 调用数。不得把接口存在写成端到端完成。
