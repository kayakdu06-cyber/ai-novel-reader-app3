# 工作汇报 86：TASK-060 有界 FTS 命中累计

> 日期：2026-08-05  
> 项目：织卷 Android App  
> 阶段：TASK-060 Phase 2B1 完成；TASK-060 整体仍在进行中

## 1. 本阶段结果

已把 Phase 2A 的文本探针接到正式 SQLCipher/Room FTS 查询层。现在系统可以用目标章、用户补充和目标弧三条路线搜索同一本书的既有记忆索引，跨关键词累计同一文档的命中次数，再用固定规则产生可重放的候选顺序。

本阶段只返回派生检索指针，不把索引词当作权威小说记忆，也没有把结果发送给模型。下一阶段必须按指针重读六类权威数据并复核来源 hash。

## 2. 修正的可用性缺陷

Phase 2A 原实现把“超过 128 个唯一探针”视为整次失败。详细章节计划很容易自然超过这个数量，这会让一章在联网前无故卡住。

现在的处理是：

- 最多保留 128 个探针，额外唯一探针只增加遗漏计数，不让整章失败；
- 三路先各保留实际执行额度：目标章 32、用户补充 16、目标弧 16；
- 剩余 64 个编译名额再按目标章、用户补充、目标弧的优先级分配；
- 因此很长的章计划不会把用户明确补充和目标弧完全挤掉；
- 畸形 JSON、超大输入、超深嵌套、字符串叶子过多和单 token 过长仍然失败关闭。

## 3. 正式召回边界

- 在单个 Room 事务中先确认书存在，再执行 FTS 查询和累计。
- 固定最多执行 64 个探针：目标章 32、用户补充 16、目标弧 16。
- 每个探针最多读取 16 个索引文档；累计排序后最多返回 128 个文档。
- 复用已有 `MemorySearchDao.searchBeforeChapter`，只允许同书且 `chapterIndex` 为空或严格小于目标章。
- 同一 `documentId` 只返回一次；同一 `(bookId, sourceType, sourceId)` 不允许映射到多个文档。
- 每个文档分别记录目标章、用户补充和目标弧命中数。
- 固定排序依次为：目标章命中、用户补充命中、目标弧命中、总命中、重要度、章节序号、故事顺序、文档 ID。
- 查询指纹使用 SHA-256，覆盖策略版本、书、目标章和实际执行探针；外部只看到 hash 与计数。
- `toString()` 与静态异常不输出计划文本、用户补充、MATCH token、`searchTerms` 或来源正文。

## 4. 修改文件

- `core/database/src/main/kotlin/app/zhijuan/core/database/search/MemoryRecallProbeCompiler.kt`
- `core/database/src/main/kotlin/app/zhijuan/core/database/search/MemorySearchRecallRepository.kt`
- `core/database/src/test/kotlin/app/zhijuan/core/database/search/MemoryRecallProbeCompilerTest.kt`
- `core/database/src/androidTest/kotlin/app/zhijuan/core/database/MemorySearchRecallDatabaseTest.kt`
- `docs/19-IMPLEMENTATION-BACKLOG.md`
- `docs/20-TRACEABILITY-MATRIX.md`
- `docs/22-WORK-STATUS.md`
- `docs/ai/CURRENT-CONTEXT.md`
- `docs/ai/task-packets/TASK-060-PHASE-2B1-FTS-HIT-ACCUMULATION.md`
- 本报告

## 5. 验证证据

```text
core/database JVM 全量：65/65
MemoryRecallProbeCompilerTest：15/15
API 30 core/database 全量：126/126
API 35 core/database 全量：126/126
MemorySearchRecallDatabaseTest：双 API 各 5/5
git diff --check：通过（仅既有换行符提示）
security-scan：通过
```

新增测试覆盖：跨探针去重和命中累计、三路排序优先级、重要度/章节/故事顺序/文档 ID 比较、其他书与未来章节排除、32/16/16 路由配额、单探针 16 与最终 128 文档上限、空探针、书不存在失败关闭、查询指纹稳定/变化和字符串脱敏。

## 6. Sol / DeepSeek 分工

本阶段由 Sol 直接实现和审查，没有调用 DeepSeek。原因是工作集中在数据库事务边界、召回排序、安全计数和双模拟器验收，属于需要同一执行者连续核对的高风险小阶段。

## 7. 未完成与下一步

TASK-060 不能标记完成，当前还缺：

1. 按六类 `sourceType` 批量重读权威表；
2. 复核 source ID、书、有效状态、当前章节版本和 `sourceContentHash`；
3. 合并最近章节、硬事实和未解决伏笔强制路线；
4. 接入现有章前上下文候选与确定性预算裁剪；
5. 建立固定中文召回集并完成 TASK-060 总回归。

本阶段织卷 App 内真实 Provider 调用 0、物理设备写入 0、Git remote 操作 0。
