# 工作汇报 88：TASK-060 强制、最近与相关记忆选择

> 日期：2026-08-05  
> 项目：织卷 Android App  
> 阶段：TASK-060 Phase 2C1 完成；TASK-060 整体仍在进行中

## 1. 本阶段结果

系统现在不再只靠搜索词决定“模型应该记住什么”。章前记忆被分为三条自动路线，并在同一个 Room 事务内合并：

1. 强制路线：当前有效 HARD_CANON 与已经到期的未解决伏笔；
2. 最近路线：当前版本的最近 8 个章节摘要，上一章自然排在最前；
3. 相关路线：Phase 2B1/2B2 产生并完成权威回填的 FTS 结果。

同一来源即使同时被多条路线选中也只出现一次，位置以强制→最近→FTS 为准，但会保留目标章、用户补充和目标弧的逐项命中次数，供下一阶段确定性排序和预算使用。

## 2. 关闭的严重缺口

- 旧上下文把 HARD_CANON 与每章产生的 STORY_CANON 都当成不可裁剪硬事实。长篇运行几百章后会让必需上下文持续膨胀。现在只有 HARD_CANON 强制进入，普通 STORY_CANON 走 FTS 相关路线。
- 旧逻辑先按重要度截取最多 128 个伏笔，之后才判断是否到期。低重要度但已经该回收的伏笔可能被挤掉。现在 SQL 先判断有效、未解决、来源 current、早于目标章和已经到期，再排序。
- 旧伏笔查询没有在这一入口复核来源章节仍为 current，也没有排除未来章。新强制查询同时关闭这两条边界。
- DeepSeek 建议各路线分开读取、不设外层事务。Sol 改为整个选择过程使用一个 Room 事务，避免事实、伏笔、摘要与 FTS 来自不同时间切片。
- DeepSeek 提案只保留“是否经 FTS”这一布尔路线。Sol 补齐每项三路命中次数，以及编译、执行、排序、选择和 hydration 的完整遗漏/拒绝计数，后续不会丢失相关度依据。

## 3. 边界和失败策略

- 默认最多返回 512 个权威来源。
- 强制与最近来源先装入；FTS 只能使用剩余名额。
- 强制+最近超过上限时，不返回任何不完整列表，不执行 FTS，返回 `MANDATORY_OVERFLOW` 与明确溢出计数，供上下文阶段联网前阻断。
- FTS 超出剩余名额时可以有界省略，并单独报告省略数；强制项不能被省略。
- FTS 指针失效时继续透传 `rejectedPointerCount` 与 `indexRebuildRequired`。
- 输出对象、错误和字符串表示不展开正文、JSON、人名、source/document ID、检索词、计划或用户补充。
- 本阶段只建立选择层，尚未修改现有上下文 manifest 或打开 Provider。

## 4. 修改文件

- `core/database/src/main/kotlin/app/zhijuan/core/database/memory/MemoryDao.kt`
- `core/database/src/main/kotlin/app/zhijuan/core/database/search/MemoryContextRouteSelectionRepository.kt`
- `core/database/src/androidTest/kotlin/app/zhijuan/core/database/MemoryContextRouteSelectionDatabaseTest.kt`
- `docs/15-TEST-PLAN.md`
- `docs/19-IMPLEMENTATION-BACKLOG.md`
- `docs/20-TRACEABILITY-MATRIX.md`
- `docs/22-WORK-STATUS.md`
- `docs/ai/CURRENT-CONTEXT.md`
- `docs/ai/task-packets/TASK-060-PHASE-2C1-MEMORY-ROUTE-SELECTION.md`
- 本报告

## 5. 验证证据

```text
生产与 AndroidTest Kotlin 编译：通过
MemoryContextRouteSelectionDatabaseTest：API 30 5/5、API 35 5/5
core/database JVM 全量：65/65
API 30 core/database 全量：136/136
API 35 core/database 全量：136/136
security-scan：通过（源码 + 5 个现存 APK）
git diff --check：通过（仅既有换行符提示）
真实 Provider 调用：0
物理设备安装/写入/设置修改：0
Git remote 操作：0
```

测试覆盖：强制/最近/FTS 合并与稳定顺序、同来源多路去重、逐项用户补充命中、STORY_CANON 只经 FTS 进入、旧章节/未来章/未到期/已解决伏笔排除、强制超界零部分结果与零 FTS、可选 FTS 有界省略、陈旧索引拒绝与重建标记透传、全部对象字符串脱敏。

## 6. Sol / DeepSeek 分工

DeepSeek V4 Flash 以只读补丁提案方式运行，ID `20260805-090459-a73dda4c`，使用 `max` 推理、20 分钟上限、无总 Token 上限，约 9 分 29 秒结束；没有写仓库、构建或调用 App Provider。

Sol 保留了它提出的 HARD_CANON/到期伏笔 SQL 与强制超界思路，但否决了分散事务和缺少逐项命中证据的部分，随后完成正式实现、五项 Room 测试与双 API 全量回归。

## 7. 未完成与下一步

TASK-060 仍不能标记完成。Phase 2C2 将：

1. 把本阶段权威选择映射为现有 `ChapterContextCandidate`；
2. 用逐项 FTS 命中、最近性、重要度和强制路由进入确定性预算；
3. 把路线/遗漏/查询指纹写入不可变上下文证据；
4. 在 Provider-open 前逐条重验已选择事实、事件、时间线和伏笔，任何来源变化都拒绝旧快照；
5. 保持默认自动运行，不增加每章人工选择。

本阶段没有调用织卷 App 内部真实生成 API，也没有对实体设备执行安装、写入或设置修改。
