# 工作汇报 96：TASK-061 Phase 2B3A 聚合状态重建 Writer

> 日期：2026-08-06  
> 项目：织卷 Android App  
> 状态：Phase 2B3A 已完成；TASK-061 仍进行中

## 1. 本阶段结果

织卷现在具备了编辑旧章节后的单章聚合状态重建能力。系统不再复制上一代 aggregate，而是从正式加密数据库里的当前权威实体状态、活动伏笔和目标章 tracking 重新计算紧凑 CURRENT_STATE，在同一事务中把旧聚合头保留为 `STALE`，再写入一代新的 `VALID` 结果。

这解决的是“每章 aggregate 如何安全重算”的底层问题。跨章严格有序 Job/Stage 执行、TEST-033 和整 App 总 runner 尚未完成，不能描述为编辑后已经自动重建全部后续章节。

## 2. 实现内容

### 2.1 严格有界的 CURRENT_STATE

新增 `zhijuan.aggregate-state.v1`：

- 最多 256 个实体属性当前状态；同一实体同一属性只保留当前章节边界内 story order 最新的一条；
- 最多 128 个当前活动伏笔，使用既有完整伏笔规范快照；
- JSON 字段集合、类型、顺序、嵌套对象键序和 SHA-256 全部严格校验；
- 总 payload 上限 128 KiB；
- 不保存章节正文、摘要历史、时间线历史、Provider、Attempt、Usage、模型元数据、提示词或 API 信息。

### 2.2 权威来源和 tracking 代次绑定

每个 aggregate 同时绑定：

- 目标 current chapter version 与正文 hash；
- 目标章有效 tracking projection 和 generation stage；
- tracking 的 memory snapshot、prior foreshadow snapshot、output 与 payload hash。

因此 tracking 只要换代，旧 aggregate 就不能继续被计划当成 `ALREADY_SATISFIED`。未来章、旧章节版本、无效伏笔或不一致的 resolved/source/planted 引用会在写入前失败关闭。

### 2.3 单事务写入与精确 replay

`AggregateStateWriterRepository` 固定执行：

1. 核对 Phase 2A 计划 v2、编辑范围和目标章节；
2. 重验整个冻结 current chapter/version/hash 集合；
3. 读取并核对同章有效 tracking；
4. 有界读取权威实体状态和活动伏笔；
5. 生成 canonical payload、hash 和确定性 aggregate ID；
6. 精确 crash replay 直接零写入返回；
7. 重验完整计划且要求目标 aggregate 步骤为 `READY`；
8. 当前槽旧 `VALID` 头转为 `STALE`，再插入新 `VALID` 代；
9. 精确回读验证。

同证据并发写入由数据库唯一当前头约束兜底：一方提交，另一方只能作为精确 replay 返回。不同证据、坏旧头、时间倒退或槽被并发改动都不会被静默覆盖。

### 2.4 计划契约升级

`ChapterEditRebuildPlanRepository` 的 schema/policy 升为 v2：

- aggregate 不再使用“writer 未实现”的固定 blocker；
- 依赖未满足时保持等待/阻塞；
- 写入后只有 canonical payload、目标版本和完整 tracking provenance 全部匹配，才标为 `ALREADY_SATISFIED`；
- 畸形或占槽但不匹配的当前头标为 `DERIVED_VERSION_SLOT_OCCUPIED`，要求显式处理，不能偷偷替换。

旧 v1 计划不是持久执行许可；在新语义下必须重新规划。

## 3. DeepSeek 审计与 Sol 决策

- 成功运行：`20260806-002111-4d3f22a9`。
- 模式：只读设计/代码审计，`max` 推理，约 9 分 47 秒。
- 用量：总 Token 1,551,234；缓存输入 1,293,696；输出 57,333；推理 35,967。
- DeepSeek 没有修改工作树。
- 采纳：从权威表确定性重算、无需新增 schema、旧聚合只作为顺序来源而不复制内容。
- 未直接应用其大补丁：提案把摘要、事件、时间线和事实历史放得过宽，而且只按依赖解除 aggregate，没有识别写成后的 `ALREADY_SATISFIED`，会导致下一章永远无法解锁。
- Sol 补齐：紧凑 CURRENT_STATE、严格 tracking 代次 provenance、写后已满足判定、坏头阻塞、并发 replay 和时间下界测试。

## 4. 测试证据

### 4.1 双 API 定向与全量数据库

API 30 `emulator-5556` 与 API 35 `emulator-5558` 均通过：

- `AggregateStateWriterDatabaseTest + ChapterEditRebuildPlanDatabaseTest`：各 11/11；
- `core/database` 全量：各 166/166，0 失败、0 错误、0 跳过。

7 个 writer 场景覆盖：规范最新状态与正文/未来数据排除、未来伏笔失败关闭、旧版本头转 STALE、畸形当前头阻塞、同证据并发单代提交、tracking 换代拒绝旧聚合、生成时间倒退零写入与诊断脱敏。

### 4.2 统一离线门禁

`scripts/verify-build.ps1 -Offline` 通过：

- Gradle 797 actionable tasks；
- Debug、Release、Lint、R8 与 JVM 测试成功；
- 安全扫描脚本回归 4 项通过；
- 源码与 5 个 APK 安全扫描通过；
- Android 备份排除策略通过；
- `git diff --check` 无错误。

所有 Android 测试只写项目专用模拟器。App 内真实 Provider 调用 0，没有产生真实模型费用，也没有向物理设备写入。

## 5. 主要修改文件

- `core/database/src/main/kotlin/app/zhijuan/core/database/library/AggregateStateWriterRepository.kt`
- `core/database/src/main/kotlin/app/zhijuan/core/database/library/ChapterEditRebuildPlanRepository.kt`
- `core/database/src/main/kotlin/app/zhijuan/core/database/memory/MemoryDao.kt`
- `core/database/src/androidTest/kotlin/app/zhijuan/core/database/AggregateStateWriterDatabaseTest.kt`
- `core/database/src/androidTest/kotlin/app/zhijuan/core/database/ChapterEditRebuildPlanDatabaseTest.kt`
- `docs/ai/task-packets/TASK-061-PHASE-2B3A-AGGREGATE-WRITER-DESIGN-AUDIT.md`

同步更新生成系统、数据模型、状态机、测试计划、待办、追踪矩阵、工作状态、AI 开发协议和当前交接文档。

## 6. 尚未完成与下一阶段

下一阶段是 TASK-061 Phase 2B3B：

1. 把冻结计划转成可恢复、严格按章和依赖推进的执行流程；
2. 复用既有 memory/tracking/context 工厂、提交仓库、伏笔 rewind 和 aggregate writer；
3. 每个远程步骤继续遵守 Provider-open、费用、Attempt/Usage、lease 与崩溃恢复门禁；
4. 不删除后续正文，不伪造 Provider 输出，不跳过用户选择边界；
5. 完成 TEST-033 编辑后顺序重建的端到端证据。

TASK-061 尚未完成。当前 App 仍没有总 phase runner，也不能描述为已经可以自动完成整本小说生成。
