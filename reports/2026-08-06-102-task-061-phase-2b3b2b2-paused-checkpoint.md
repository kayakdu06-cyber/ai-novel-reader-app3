# 工作汇报 102：TASK-061 Phase 2B3B2B2 暂停现场

> 日期：2026-08-06  
> 项目：织卷 Android App  
> 唯一仓库：`D:\\gptuser\\projects\\ai-novel-reader-app2`  
> 状态：实现与定向验证完成；模块全量回归被用户暂停，暂不宣布本子阶段完成

## 1. 暂停时已经实现的内容

本轮把第一章的 rebuild tracking 提交与同章 aggregate 重算放进同一个 Room 事务：

1. rebuild tracking 输出通过原有严格解析和来源复核后，先写 tracking、时间线、伏笔 transition/revision 和 FTS；
2. 在 Stage、Usage 结算成功前，调用既有 `AggregateStateWriterRepository`，从当前权威 tracking、实体属性和活动伏笔重算 aggregate；
3. aggregate 任一校验失败时，外层事务会同时回滚 tracking、时间线、伏笔、FTS、FINAL Usage 和 Stage/Job 完成状态；
4. 精确 replay 不重新生成 aggregate，而是重建当前计划并验证 tracking 与 aggregate 都已经 `ALREADY_SATISFIED`；
5. tracking Stage 创建后、Provider-open 前如果 aggregate 唯一槽被其他写入改变，会在联网前失败关闭；
6. 普通非编辑重建 tracking 路径保持原行为，不会被迫创建 aggregate。

Room schema 仍为 v14，没有新增迁移。

## 2. 为什么 replay 只验证而不再次调用 writer

aggregate 的确定性 ID 包含 `planHash`。首次写入后，计划中的 aggregate 步骤会从 `READY` 变成 `ALREADY_SATISFIED`，计划哈希也随事实变化。若 replay 再调用 writer，可能按新哈希构造另一个 ID。

因此当前实现采用：

- 首次 commit：要求 tracking 已满足、aggregate 为 `READY`，执行 writer；
- 精确 replay：要求 tracking 与 aggregate 均为 `ALREADY_SATISFIED`，只核验证据，不重复写入。

这避免了崩溃重放产生第二代同证据 aggregate。

## 3. 新增的重要测试场景

### 3.1 正向 Fake Provider 闭环

`ChapterTrackingProjectionEndToEndTest` 新增编辑重建场景，实际执行：

`用户编辑 → memory 基线/准备 execution → tracking 请求审计 → Fake 流式响应 → 严格解析 → tracking 原子提交 → aggregate 原子重算 → 精确 replay`

断言 tracking 与 aggregate 各只有一份，replay 不产生重复 transition、revision、FTS 或 aggregate。

### 3.2 aggregate 失败整笔回滚

测试构造一个违反当前章节边界的未来章活动伏笔，使 aggregate writer 在 tracking 业务写入后拒绝提交。断言：

- tracking、timeline、transition、aggregate 均为 0；
- Stage 保持 `COMMITTING`，Job 保持 `RUNNING`；
- Usage 保持 `PROVISIONAL`，没有伪造 FINAL 结算。

这证明不是“tracking 成功、aggregate 失败”的半完成状态。

### 3.3 Provider-open 前聚合槽变化

`ChapterEditRebuildPlanDatabaseTest` 新增场景：tracking Stage 创建后插入意外 aggregate，Provider-open 必须拒绝；没有创建 Attempt，也没有写入 tracking。

## 4. 已取得的验证证据

### 4.1 编译与 JVM

- `:core:database:compileDebugAndroidTestKotlin`：通过；
- `core/database` JVM：67/67，0 失败、0 错误、0 跳过；
- `feature/generation` JVM：117/117，0 失败、0 错误、0 跳过。

### 4.2 Android 定向测试

- `ChapterEditRebuildPlanDatabaseTest`：API 30 为 16/16，API 35 为 16/16；
- `ChapterTrackingProjectionEndToEndTest`：API 30 为 5/5，API 35 为 5/5；
- 定向测试均 0 失败。

只使用项目模拟器：

- API 30：`emulator-5556`；
- API 35：`emulator-5558`。

App 内真实 Provider 调用为 0，物理设备写入为 0，Git remote 仍为空。

## 5. 被暂停的验证

API 30 的 `core/database` 179 项全量测试运行到终端已报告的 153/179、0 failed 时，用户要求暂停；随后已向 Gradle 进程发送中止信号，进程退出码为 1。

这不是测试失败，但也不能算作全量通过。下列证据尚未取得：

- API 30 `core/database` 全量最终 179/179；
- API 35 `core/database` 全量 179/179；
- API 30/API 35 `feature/generation` 全量（预计各 31 项）；
- 本子阶段安全扫描与 `git diff --check`；
- 统一 Release/R8 离线门禁。

因此 `Phase 2B3B2B2` 保持“实现完成、验证未收口”，TASK-061 继续为进行中。

## 6. 下次恢复的严格顺序

1. 确认 Git 根目录精确为 `D:/gptuser/projects/ai-novel-reader-app2`；
2. 读取根 `AGENTS.md`、`docs/24-AI-DEVELOPMENT-PROTOCOL.md`、`docs/ai/CURRENT-CONTEXT.md` 和本报告；
3. 不改代码，先重跑 API 30/API 35 的 `core/database` 全量；
4. 再跑 API 30/API 35 的 `feature/generation` 全量；
5. 执行 `scripts/security-scan.ps1 -SkipArtifacts` 与 `git diff --check`；
6. 全部通过后，才把 Phase 2B3B2B2 标记完成并同步架构、数据模型、状态机、测试计划、追踪矩阵和任务状态文档；
7. 然后进入后续保留章节的有序重建与 TEST-033，不直接跳到 UI 或 TASK-062。

恢复时仍不得调用 App 内真实 Provider，不得写入物理设备，不得添加 Git remote，也不得从原项目副本同步文件。

## 7. DeepSeek 说明

本轮没有调用 DeepSeek。上一轮约 100 万 Token 后没有可审查代码差异；当前改动属于跨 tracking/aggregate/Usage/Stage 的高风险原子事务，已由 Sol 直接实现并用正向、回滚和 replay 场景验证。暂停后也没有继续发起任何模型编码任务。
