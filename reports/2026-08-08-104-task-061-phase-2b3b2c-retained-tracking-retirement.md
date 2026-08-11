# 工作汇报 104：TASK-061 Phase 2B3B2C 保留章节 tracking 原子退役

> 日期：2026-08-08  
> 项目：织卷 Android App  
> 唯一仓库：`D:\gptuser\projects\ai-novel-reader-app2`

## 1. 本阶段完成内容

本阶段完成的是“编辑章之后第一章”的安全准备切片，不是整个后续章节循环：

1. 正式 Room schema 从 v14 升到 v15。
2. 新增不可变 `chapter_edit_rebuild_tracking_retirement`，记录准备时旧 tracking、精确 timeline 集合、退役后指纹、replacement Job/Stage 与操作时间。
3. 旧 tracking/timeline 的 `VALID→STALE`、对应搜索文档删除、真实 current source 读取、确定性 replacement Job/Stage 创建和 retirement evidence 插入都在一个 Room 事务内完成。
4. 相同命令精确 replay 不重复退役或创建；两个 worker 并发收敛为同一个 Stage；replacement identity 已被占用时整笔回滚，旧 tracking/timeline/search 仍保持有效。
5. 新增 v14→v15 迁移、不可变/删除/provenance 触发器和 API 30/API 35 回归。

## 2. 为什么需要 schema v15

现有 Stage binding 能证明 replacement Stage 属于哪个 execution/step，却不能长期证明“本次执行究竟退役了哪一个旧 tracking，以及哪些历史 timeline 已精确转为 STALE”。只依赖当前表状态会把其他执行留下的 STALE 历史误当成自己的证据，也无法在崩溃后核对搜索清理集合。

因此采用 append-only retirement evidence，把准备时 baseline 和精确历史集合固定下来。它表示“旧基线已经安全退役，并且存在可恢复的 replacement Stage”，不表示新 tracking 或 aggregate 已经成功。

## 3. DeepSeek 执行情况

- 第一轮宽泛只读审计在 15 分钟上限结束，没有最终回交和代码差异。
- 用户允许放宽思考时长后，第二轮 `20260808-151727-476950e8` 使用 `max` 推理、30 分钟、无 Token 上限，累计约 2,001,338 Token，仍没有最终回交或代码差异。
- 两轮均没有向 Sol 请求修改权限。问题不是权限不足，而是跨模块审计范围过宽，模型持续分析但未在时限内收敛成答案。
- Sol 没有第三次重复该宽泛审计，独立完成 schema 决策、代码、测试和复核。后续如使用 DeepSeek，将拆为单文件或单测试的窄任务，并可按用户授权适当放宽时间。

## 4. 主要修改

- schema v15、迁移与 Room schema JSON。
- retirement entity/DAO、唯一索引、外键和数据库触发器。
- tracking/timeline 精确 CAS 退役与 timeline 搜索 identity 支持。
- `ChapterEditRebuildStageRepository.createNextRetainedTrackingStage`。
- retirement evidence 严格 codec/指纹及 JVM 测试。
- 第二章正向、replay、并发和 identity collision 回滚的 Android 数据库测试。

## 5. 验证证据

| 验证项 | API 30 | API 35 | 结果 |
|---|---:|---:|---|
| `ChapterEditRebuildPlanDatabaseTest` | 19/19 | 19/19 | 通过 |
| v14→v15 migration 定向 | 1/1 | 1/1 | 通过 |
| `core/database` Android 全量 | 183/183 | 183/183 | 通过 |
| `core/database` JVM | 70/70 | 不区分 API | 通过 |
| 源码安全扫描 | 不区分 API | 不区分 API | `SECURITY_SCAN_OK` |
| `git diff --check` | 不区分 API | 不区分 API | 返回 0 |

全部测试均为 0 失败、0 错误、0 跳过。没有调用织卷 App 内真实 Provider，没有产生付费请求，没有向物理设备写入，Git remote 仍为空。

## 6. 明确未完成

以下内容不属于本阶段完成项，不能误报：

- 新 replacement Stage 的 Provider-open 授权仍被第一章硬编码门禁挡住。
- tracking commit 尚未基于 retirement evidence 泛化。
- planner 尚未把 `projection.generationStageId == replacementStageId` 的新 tracking 认定为本次 execution 的 `ALREADY_SATISFIED`。
- 第一个保留章节的 tracking→aggregate 原子完成尚未接通。
- 第三章及更后章节的通用迭代、TEST-033、总 runner 和统一 Release/R8 门禁均未完成。

## 7. 下一阶段

下一阶段先只打通第一个保留章节：

1. Provider-open 与 commit 通过 retirement evidence 识别目标 Stage；
2. planner 严格核对 replacement tracking 的 generation Stage 身份；
3. tracking commit 与同章 aggregate 保持一个 Room 原子事务；
4. 补 Fake Provider 正向、失败回滚、replay 和并发测试；
5. 验证稳定后再把同一模式扩展到任意后续章节。

TASK-061 与 TEST-033 继续保持“进行中”。
