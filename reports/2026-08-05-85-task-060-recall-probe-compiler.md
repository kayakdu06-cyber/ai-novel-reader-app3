# 工作汇报 85：TASK-060 确定性召回探针编译器

> 日期：2026-08-05  
> 项目：织卷 Android App  
> 阶段：TASK-060 Phase 2A 完成；多路数据库召回尚未完成

## 1. 阶段结果

已新增纯本地、无 IO 的召回探针编译器。它把目标章标题/计划、用户补充和目标弧标题/计划中的字符串，转换成 API 30/35 FTS4 均可使用的单个 ASCII token 探针，为下一阶段按多个命中累计相关性提供稳定输入。

固定优先顺序为：目标章 → 用户补充 → 目标弧。相同 token 跨路由只保留更高优先、最早出现的一项；每条路由的 ordinal 在去重后保持从 0 连续。

## 2. 安全与确定性边界

- JSON 严格解析；只读取字符串值，不把键名、数字、布尔或 null 当关键词。
- 对象键按字典序遍历，数组保持原顺序，因此同一对象只换键顺序不会改变结果。
- 复用 `SearchIndexText.matchExpression`，再拆成单 token，避免把整句所有词用隐式 AND 锁死。
- 单 JSON 64 KiB、总嵌套深度 32、整次编译合计字符串叶子 256、单字符串 4 KiB、唯一探针 128、单探针 128 字符；违反上限时使用静态错误失败关闭。
- `toString()` 不输出 MATCH token 或源文本。
- 空白、纯标点、数字/布尔/null 可以合法得到空探针列表。

## 3. DeepSeek 协作质量

- 任务包：`docs/ai/task-packets/TASK-060-PHASE-2A-RECALL-PROBE-COMPILER.md`
- 运行 ID：`20260805-074331-0e38cb8f`
- DeepSeek V4 Flash，`max` 推理，25 分钟硬上限，无总 Token 上限。
- 实际耗时约 15 分 54 秒；总 Token 2,018,458，输出 89,680，推理输出 70,142。
- 实际产出两个可编译、可测试的新文件，不是空交付；独立复跑通过。
- 边界遵守：业务范围只新增任务包允许的生产文件和测试文件。
- 需要 Sol 修正：DeepSeek 把 256 叶子限制实现为“每份 JSON 各 256”，实际会允许两份 JSON 合计 512；Sol 改为整次编译共享总配额并新增跨 JSON 回归。
- 工具流程问题：DeepSeek 的 `apply_patch` 在其 Windows 受限沙箱中被拒绝后改用 `WriteAllText`，不符合项目首选编辑流程；Sol 已用正式 `apply_patch` 继续审查和修正最终文件。后续任务包应继续限制范围，并由 Sol 逐项复核实际差异。

## 4. 修改文件

- `core/database/src/main/kotlin/app/zhijuan/core/database/search/MemoryRecallProbeCompiler.kt`
- `core/database/src/test/kotlin/app/zhijuan/core/database/search/MemoryRecallProbeCompilerTest.kt`
- `docs/ai/task-packets/TASK-060-PHASE-2A-RECALL-PROBE-COMPILER.md`
- `docs/ai/CURRENT-CONTEXT.md`
- 本报告

## 5. 测试证据

```text
:core:database:testDebugUnitTest
MemoryRecallProbeCompilerTest: 14/14
BUILD SUCCESSFUL
git diff --check: passed
```

覆盖中文 token 不含原文、固定路由顺序、对象键序无关、跨路由去重、连续 ordinal、长句拆分、空输入、数组顺序、32/33 层边界、256/257 叶子、两份 JSON 共享叶子上限、4 KiB/64 KiB/128 token/128 探针边界和脱敏错误。

## 6. 未完成与下一步

本阶段尚未执行数据库 MATCH、命中累计、权威行 hydration、跨路线候选合并或上下文接线，不能描述为多路召回已可用。

下一阶段 Phase 2B 将实现：逐探针有界查询、按源身份确定性累计、未来章节排除、六类权威来源批量 hydration、source hash 复核、最近摘要/硬事实/未解决伏笔强制路线，以及与现有上下文预算候选的去重接线。

本阶段织卷 App 内真实 Provider 调用 0、Android 设备写入 0、Git remote 操作 0。
