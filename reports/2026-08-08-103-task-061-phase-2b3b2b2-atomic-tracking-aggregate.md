# 工作汇报 103：TASK-061 Phase 2B3B2B2 tracking 与 aggregate 原子推进

> 完成日期：2026-08-08  
> 项目：织卷 Android App  
> 唯一仓库：`D:\\gptuser\\projects\\ai-novel-reader-app2`  
> 状态：本子阶段完成；TASK-061 仍进行中

## 1. 本阶段结果

第一章编辑重建的 tracking 提交现在会在同一个 Room 外层事务中推进同章 aggregate：

1. tracking 输出先经过严格 schema、Attempt、来源、execution、stable fence 和 current range 复核；
2. tracking、时间线、伏笔 transition/revision 与 FTS 写入后，立即调用既有 `AggregateStateWriterRepository`；
3. aggregate 只从当前权威 tracking、current-version-bound 实体状态和活动伏笔重算；
4. aggregate 失败会回滚本次 tracking、时间线、伏笔、FTS、FINAL Usage 和 Stage/Job 完成状态；
5. 精确 replay 不按变化后的 planHash 再生成 aggregate，只验证 tracking 与 aggregate 已经严格 `ALREADY_SATISFIED`；
6. tracking Stage 创建后若 aggregate 槽被其他写入改变，会在 Provider-open 前失败关闭；
7. 普通 tracking 路径保持原行为，不会被 TASK-061 强制附带 aggregate。

本阶段不升级 Room schema，正式数据库继续为 v14。

## 2. 为什么必须使用同一外层事务

如果先把 tracking 标记成功、再单独写 aggregate，进程可能在两步之间崩溃，留下“时间线和伏笔已经换代，但当前状态仍是旧代”的混合现场。后续章节会把这套混合事实当成输入，造成连续性错误。

当前实现把两者放进 tracking commit 已有的 Room 事务。即使 aggregate writer 在内部使用 `withTransaction`，Room 会加入同一外层事务；正向和故障注入测试均证明没有半提交。

## 3. replay 决策

aggregate 确定性 ID 包含首次写入时的 planHash。首次提交后，计划步骤会从 `READY` 变成 `ALREADY_SATISFIED`，合法计划哈希随事实变化。

因此：

- 首次提交要求 tracking 已满足、aggregate 为 `READY`，然后调用 writer；
- 成功 Stage 的 replay 要求当前 tracking 与 aggregate 均已严格满足，只核验既有代次；
- replay 不调用 writer，避免用新 planHash 创建第二代同证据 aggregate。

## 4. 关键测试

### 4.1 正向 Fake Provider 闭环

端到端实际执行：

`用户编辑 → execution/ledger → tracking RequestIntent/Attempt → Fake 流式响应 → 严格解析 → tracking 业务写入 → aggregate 重算 → Usage/Stage/Job 成功 → 精确 replay`

断言 tracking 和 aggregate 各只有一份有效代，replay 不重复 transition、revision、FTS 或 aggregate。

### 4.2 aggregate 失败整笔回滚

测试注入违反当前章边界的未来活动伏笔，使 aggregate writer 拒绝提交。最终：

- tracking、timeline、transition、aggregate 均没有残留；
- Stage 保持 `COMMITTING`，Job 保持 `RUNNING`；
- Usage 保持 `PROVISIONAL`，没有伪造 FINAL 用量。

### 4.3 Provider-open 前竞态

tracking Stage 创建后插入意外 aggregate，Provider-open 直接拒绝；Attempt 与 tracking 均不会产生。

## 5. 最终验证证据

### 5.1 JVM

- `core/database`：67/67；
- `feature/generation`：117/117；
- 0 失败、0 错误、0 跳过。

### 5.2 Android API 30 / API 35

- `ChapterEditRebuildPlanDatabaseTest`：每套 16/16；
- `ChapterTrackingProjectionEndToEndTest`：每套 5/5；
- `core/database` 全量：每套 179/179；
- `feature/generation` 全量：每套 31/31；
- 0 失败、0 错误、0 跳过。

使用的隔离模拟器：

- API 30：`zhijuan_api30_clean` / `emulator-5556`；
- API 35：`zhijuan_api35_clean` / `emulator-5558`。

模拟器、SDK、Gradle、日志和临时文件均位于 `D:\\gptuser`。没有向物理设备安装或写入。

### 5.3 静态与安全

- `scripts/security-scan.ps1 -SkipArtifacts`：`SECURITY_SCAN_OK`；
- `git diff --check`：返回 0，仅有既有 LF→CRLF 提示；
- App 内真实 Provider 调用：0；
- 新付费请求：0；
- Git remote：仍为空。

本子阶段没有运行统一 797-task Release/R8 离线门禁。该门禁仍在 TASK-061 完成整个后续章节区间和 TEST-033 后执行，不能沿用旧阶段数字冒充当前最终证据。

## 6. DeepSeek 使用说明

本子阶段没有调用 DeepSeek。暂停前实现已由 Sol 完成，恢复任务只需依据工作汇报 102 补齐双 API 全量与静态门禁；重新委派不会增加独立代码证据。

## 7. 尚未完成与下一阶段

TASK-061 下一阶段处理第二章及以后保留正文的有序重建：

1. 只在上一章 aggregate 已严格满足后推进下一章；
2. 受控失效该章旧 tracking/aggregate 基线，不删除历史；
3. 用该章 current 正文和刚重建的前章状态动态创建真实 Stage；
4. 支持中途崩溃、精确 replay、并发调度和来源变化失败关闭；
5. 最终完成多章 TEST-033。

在此之前，TASK-061 仍不能标记完成，App 也仍没有全生成链 total runner。
