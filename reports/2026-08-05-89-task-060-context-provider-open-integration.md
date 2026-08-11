# 工作汇报 89：TASK-060 章前候选与发送前动态记忆门禁

> 日期：2026-08-05  
> 项目：织卷 Android App  
> 阶段：TASK-060 Phase 2C2 完成；TASK-060 整体仍在进行中

## 1. 本阶段结果

Phase 2C1 的“强制、最近、相关”权威记忆选择已经正式接入章前上下文。系统现在会自动完成以下流程，不增加用户逐章选择操作：

1. 读取当前 Story Bible、目标卷章、上一章和当前状态；
2. 选择 HARD_CANON、到期伏笔、最近 8 章摘要与 FTS 相关记忆；
3. 映射为现有 `ChapterContextCandidate`，按既有确定性预算整项保留或省略；
4. 把查询指纹、逐路命中、遗漏、拒绝和每项来源路线写入加密不可变 manifest；
5. 在 Provider 真正打开前重新执行完整选择、映射与预算；只有 payload hash 和完整 manifest 都与快照完全一致才允许继续。

普通 `STORY_CANON`、旧摘要、旧人物事件、时间线和未到期伏笔不再被全量塞入上下文。它们只有与目标章、用户补充或目标弧相关时才作为可裁剪项进入。当前人物身份与成年状态、每个实体属性的最新事件、硬事实、上一章摘要和到期伏笔仍保留安全优先级。

## 2. 关闭的严重缺口

- 旧组装器仍会把所有 `STORY_CANON` 当成必需硬事实，长篇运行后必然膨胀。现在只有 `HARD_CANON` 强制进入，普通故事事实只走相关路线。
- 旧组装器会批量读取最多 256 条时间线和 128 条开放伏笔，即使与目标章无关。现在历史时间线和未到期伏笔必须由 FTS 命中。
- 旧逻辑在快照完成后只复核 Bible、Outline 和上一章，没有复核事实、摘要、事件、时间线与伏笔。现在 Provider-open 会重建完整动态投影，任何来源、排序、遗漏、命中或预算结果变化都会拒绝旧快照。
- 索引指针损坏时过去只能返回“需要重建”证据。现在组装阶段会在同一事务内自动完整重建该书索引一次并重新选择；只有重建后仍异常才阻断。
- 强制记忆超过 512 项时，现在会用独立原因在联网前阻断，不建立部分 snapshot、不激活计划 Stage，也不创建 Attempt。

## 3. 关键实现边界

- 自动索引修复只发生在本地 `ASSEMBLE_CONTEXT` 阶段；Provider-open 只读复核，不在请求意图之后悄悄改写索引。
- manifest 的记忆证据包含选择状态、SHA-256 查询指纹、编译/执行/排序/选择/hydration 计数、逐项路线和目标章/用户补充/目标弧命中数；不新增明文日志。
- Provider-open 不逐条做 N+1 查询，而是复用有界路线选择和最多六类批量 hydration，再重跑候选与预算。
- payload 相同但路线、排序或遗漏证据改变也会拒绝，因为完整 manifest 必须一致。
- 本阶段没有调用织卷 App 内部真实 Provider，没有触碰物理设备，也没有增加用户确认步骤。

## 4. 修改文件

- `core/task/src/main/kotlin/app/zhijuan/core/task/ChapterContextBudgetPolicy.kt`
- `core/database/src/main/kotlin/app/zhijuan/core/database/search/MemorySearchBackfillRepository.kt`
- `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterContextAssemblyRepository.kt`
- `core/database/src/androidTest/kotlin/app/zhijuan/core/database/ChapterContextAssemblyDatabaseTest.kt`
- `docs/15-TEST-PLAN.md`
- `docs/19-IMPLEMENTATION-BACKLOG.md`
- `docs/20-TRACEABILITY-MATRIX.md`
- `docs/22-WORK-STATUS.md`
- `docs/ai/CURRENT-CONTEXT.md`
- 本报告

## 5. 验证证据

```text
Kotlin/AndroidTest 编译：通过
ChapterContextAssemblyDatabaseTest：API 30 5/5、API 35 5/5
core/database JVM：65/65
API 30 core/database 全量：139/139
API 35 core/database 全量：139/139
统一离线门禁：797 actionable tasks，BUILD SUCCESSFUL
Release/R8：通过
安全扫描：SECURITY_SCAN_TESTS_OK、SECURITY_SCAN_OK，源码 + 5 个 APK
备份排除：BACKUP_EXCLUSION_POLICY_OK
git diff --check：通过（仅既有换行符提示）
真实 Provider 调用：0
物理设备安装/写入/设置修改：0
Git remote 操作：0
```

新增测试覆盖：相关 `STORY_CANON` 进入而无关事实不进入、无关大时间线不进入、512 个硬事实加上一章摘要触发联网前阻断、坏索引指针自动重建一次、已选事实与索引同步失效后 Provider-open 拒绝且 Attempt 为 0。

## 6. Sol / DeepSeek 分工

本阶段由 Sol 直接完成，没有调用 DeepSeek。原因是改动跨越上下文状态、数据库事务、manifest 证据和 Provider-open 费用门禁，属于需要由 Sol 最终决策并逐项验收的高风险边界。

## 7. 未完成与下一步

TASK-060 仍不能标记完成。下一阶段建立固定中文召回集，至少覆盖：

1. 中文姓名、别名和同音/拼音边界；
2. 地点、物品、关系、身体状态与承诺；
3. 已到期/未到期伏笔和跨章状态；
4. 无关高频词误召回、其他书和未来章污染；
5. 相同输入的稳定顺序与真实模拟器耗时。

固定集和性能/质量门禁通过后，再进行 TASK-060 总收口。当前 App 仍没有按 phase 分发的总 runner，不能描述为已经能够自动跑完整生成流程。
