# 工作汇报 106：TASK-061 通用保留章节循环与 TEST-033 收口

> 日期：2026-08-08  
> 项目：织卷 Android App  
> 工作目录：`D:\gptuser\projects\ai-novel-reader-app2`

## 1. 阶段结论

TASK-061 的底层“编辑后派生失效与有序重建”能力已经完成：从编辑章开始，可以按冻结执行账本逐章建立确定性 tracking Stage，保留章节先退役旧 tracking/timeline/search，再经本地 Fake Provider 生成新 tracking，并在同一提交事务内重算同章 aggregate。ordinal 4 的兼容入口保留，ordinal 6 及以后使用显式目标 ordinal 的通用入口。

TEST-033 已用两个互补证据关闭：

1. 10 章书编辑第 3 章后，第 3–10 章 tracking/aggregate 按直接前驱顺序完成重建；
2. 生产上下文选择器只选择编辑后的新摘要，旧摘要已为 `STALE` 且对应 FTS 行已删除，不会重新进入上下文。

TASK-061 到此完成。自动发现下一步、跨阶段持续调度、重启续跑和双执行器抢占属于 TASK-064 total runner，不在不可变 TASK-061 执行准备账本中伪造一个可变“完成状态”。

## 2. 本阶段生产实现

### 2.1 显式目标步骤

`ChapterEditRebuildStageRepository` 新增 `ChapterEditRebuildRetainedTrackingStageCommand` 和 `createRetainedTrackingStage`：

- 调用者必须给出偶数 `targetStepOrdinal`，最小为 4；
- 章节、步骤类型和来源版本不相信调用者输入，而是从不可变 execution ledger 推导；
- 原 `createNextRetainedTrackingStage` 保留为 ordinal 4 兼容包装，不改变已有调用方。

### 2.2 直接前驱和时间证明

任一保留章节 replacement Stage 创建前必须证明：

- 直接前一章的 replacement tracking Stage/Job、权威 projection 和同章 aggregate 全部完成；
- Stage、Job、projection、aggregate 的确定性身份与 execution/step 完全一致；
- 前驱完成时间不晚于当前 Stage 的 `createdAt`；
- planner 对直接前驱 tracking 与 aggregate 均返回 `ALREADY_SATISFIED`。

因此不能跳章、不能提前创建未来 Stage，也不能用同槽任意一条 `VALID` tracking 冒充本 execution 的结果。

### 2.3 连续退役前缀

Provider-open 与 commit 授权只接受从 ordinal 4 开始连续存在的退役证据前缀：4、6、8……。任一缺口、章节错位、时间倒退、replacement identity 不一致或证据损坏都会停止授权，较后的退役不能绕过较早缺口。

### 2.4 重放与事务边界

- 不同目标 ordinal 的精确 replay 互不干扰；
- 旧 tracking/timeline/search 的 retirement 与 replacement Stage 创建保持同一事务；
- tracking、timeline、FINAL Usage、Stage/Job 完成和 aggregate 保持同一提交事务；
- aggregate 失败时本次新派生整体回滚，已经完成的旧基线 retirement 保留，Stage 停在可恢复的 `COMMITTING`；
- 本阶段不需要 Room schema 升级，正式 schema 仍为 v15。

## 3. TEST-033 十章固定场景

新增 `tenChapterEditAtThreeRebuildsEveryAffectedTrackingAndAggregateInOrder`：

1. 建立 10 个已提交章节，并用本地 Fake Provider 为第 2–10 章生成旧 tracking；
2. 用户编辑第 3 章，正文版本切换到新的 `USER_EDIT` 版本；
3. 插入与新正文 hash 绑定的新摘要，准备不可变 rebuild execution；
4. 重建第 3 章 tracking+aggregate；
5. 对第 4–10 章按 ordinal 4、6、8、10、12、14、16 逐章退役并重建；
6. 对最后一个 Stage 做精确 replay，并重新计算 planner。

固定结果：

- retirement evidence：7 条；
- 第 3–10 章旧 tracking：8 条 `STALE`；
- 当前 `VALID` tracking：9 条（未受影响的第 2 章 + 新的第 3–10 章）；
- 第 3–10 章当前 `VALID` aggregate：8 条；
- 第 3–10 章 tracking/aggregate planner 状态全部为 `ALREADY_SATISFIED`；
- 第 4–10 章 current body/version 完全保留，没有为了修派生而重写正文；
- 每条新 tracking 的 `generationStageId` 精确指向对应 replacement Stage。

## 4. 上下文权威性证据

新增 `userEditedChapterContextSelectsOnlyTheReplacementSummary`，直接调用生产 `MemoryContextRouteSelectionRepositoryV1`：

- 用户编辑事务把旧摘要置为 `STALE` 并删除旧 FTS 指针；
- 新摘要绑定新的 current chapter version；
- 目标章节上下文只返回新摘要和新版本；
- 旧摘要 ID 不在选择结果中，旧搜索文档计数为 0。

这与 10 章链路中的 tracking retirement、新 projection 和 aggregate 证据共同证明：旧派生历史可以保留审计，但不会再被生产上下文当成权威来源。

## 5. 执行收口决策

`chapter_edit_rebuild_execution` 是不可变准备证据，不是可变 runner 状态。当前仅有 `PREPARED` 是有意设计：

- 完成与否由冻结步骤对应的权威 memory/tracking/aggregate 及 planner 重新推导；
- 不新增只为显示“完成”而存在、可能与真实业务表漂移的状态字段；
- 自动选择下一个 ordinal、重启续跑、并发调度和整 App 阶段编排统一交给 TASK-064。

因此本阶段关闭的是 TASK-061 的可执行原语和 TEST-033，不冒充 total runner 已存在。

## 6. DeepSeek 协作记录

本阶段前置只读审计使用项目隔离 DeepSeek V4 Flash：

- Run ID：`20260808-173725-9e7badef`；
- 推理强度：`max`；
- 允许时长：25 分钟；实际约 12 分 35 秒；
- 累计 Token：3,106,883，其中缓存输入 2,705,280，输出 38,473，推理 23,989；
- 正常退出，无权限请求、无代码写入、无可审查代码差异。

采用了其“显式目标步骤、直接前驱完成、连续前缀、独立 replay、无需 schema 迁移”的审计结论；Sol 额外补充并实现时间单调证明，完成代码、测试、差异审查和最终状态确认。用户已允许后续在有明确边界的任务中继续放宽 DeepSeek 思考时长。

## 7. 验证结果

### 7.1 定向与模块测试

- TEST-033 十章 Fake Provider：API 35 1/1，API 30 1/1；
- 上下文权威选择：API 35 1/1，API 30 1/1；
- `core:database` Android 全量：API 35 187/187，API 30 187/187；
- `feature:generation` Android 全量：API 35 35/35，API 30 35/35；
- `core:database` JVM：70/70；
- `feature:generation` JVM：117/117；
- 全部为 0 失败、0 错误、0 跳过。

### 7.2 统一发布门禁

`scripts/verify-build.ps1 -Offline`：

- 797 actionable tasks；
- Debug/Release 构建通过；
- Lint/Vital 与 Release R8 通过；
- `SECURITY_SCAN_TESTS_OK`，4 个扫描器自测用例通过；
- `SECURITY_SCAN_OK`，5 个构建产物扫描通过；
- `BACKUP_EXCLUSION_POLICY_OK`；
- `allowBackup=false`，九个备份/传输域保持排除。

`scripts/security-scan.ps1 -SkipArtifacts` 亦返回 `SECURITY_SCAN_OK`。`git diff --check` 返回 0，仅有仓库既有换行提示。

## 8. 安全与范围

- App 内真实 Provider 调用：0；
- 物理设备写入：0；
- 仅使用项目专用 API 30 `emulator-5556` 与 API 35 `emulator-5558`；
- 未添加 Git remote，未修改原项目目录；
- 未输出、读取或记录 API Key；
- 所有产出、缓存和临时文件均位于 `D:\gptuser`。

## 9. 下一步

按已确认路线进入 TASK-062：建立脱敏的生成时序、基准时钟和报告器，为后续固定延迟/慢流/断流 Fake Provider、total runner 和“一章不能等待十分钟”的硬速度门禁提供可重复测量底座。
