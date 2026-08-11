# 工作汇报 90：TASK-060 固定中文召回与总收口

> 日期：2026-08-05  
> 项目：织卷 Android App  
> 阶段：TASK-060 已完成；下一任务为 TASK-061

## 1. 本阶段结果

TASK-060 的最后一段质量与性能门禁已经完成。测试不再使用早期独立尖峰库冒充生产结果，而是在正式 SQLCipher `ZhijuanDatabase` 中写入 10,000 条生产 `memory_search_document`，通过完整 `MemorySearchRecallRepositoryV1` 验证：

1. 20 个固定中文人物、地点、物品与伏笔查询全部命中，召回率 20/20；
2. 完全无关查询不返回候选；
3. 相同查询的指纹和来源顺序可确定性重放；
4. 目标章、用户补充、目标弧三路合计执行 41 个探针，未超过 64 总上限，并取回全部 20 个目标；
5. API 30/API 35 均满足热查询中位、P95、最慢查询和多路查询耗时门槛。

至此，TASK-060 从“索引存在”推进到“正式生成章前上下文确实使用权威、多路、有界、可重验的中文记忆召回”，可以正式标记完成。

## 2. 固定集发现并关闭的检索漏洞

原 CJK 双字 token 使用 `g7384_94c1` 这类带下划线格式。真实 API 30 FTS4 会把下划线当成分隔符，使本应表示“相邻双字”的一个 token 退化为两个片段；例如查询“甲乙”存在误中“甲丙乙”的风险。

本阶段没有放宽“无关查询为空”的断言，而是修复根因：

- 双字 token v2 改用只含字母和数字的 `g7384x94c1` 格式，FTS4 将其视为单个词；
- 搜索回填 schema 从 1 升到 2；已有 v1 标记会在下一次章前组装时自动整书重建，不需要用户操作；
- 新增设备回归，证明“甲乙”只命中相邻文档，不命中“甲丙乙”；
- 新增 v1→v2 自动重建回归，验证旧 token 被替换、标记版本与完成时间同步更新。

这项修复不修改 SQLCipher 主库 schema，不创建明文索引，也不增加用户交互。

## 3. 性能结果

| 环境 | 文档数 | 固定查询 | 热查询中位 | P95 | 最慢 | 20 词三路查询 | 实际探针 |
|---|---:|---:|---:|---:|---:|---:|---:|
| API 30 / Android 11 | 10,000 | 20/20 | 6.07 ms | 7.37 ms | 9.21 ms | 67.30 ms | 41 |
| API 35 / Android 15 | 10,000 | 20/20 | 4.35 ms | 5.43 ms | 6.87 ms | 45.01 ms | 41 |

门槛为热查询中位不超过 100 ms、P95 不超过 200 ms、单次最慢不超过 500 ms、多路查询不超过 1,000 ms；两套系统均保留充足余量。插入 10,000 条生产索引的耗时不计入查询门槛，API 30 约 6.49 秒、API 35 约 1.58 秒。

## 4. 修改文件

- `core/database/src/main/kotlin/app/zhijuan/core/database/search/SearchIndexText.kt`
- `core/database/src/main/kotlin/app/zhijuan/core/database/search/MemorySearchBackfillRepository.kt`
- `core/database/src/test/kotlin/app/zhijuan/core/database/search/SearchIndexTextTest.kt`
- `core/database/src/test/kotlin/app/zhijuan/core/database/search/MemorySearchDocumentFactoryTest.kt`
- `core/database/src/test/kotlin/app/zhijuan/core/database/search/MemoryRecallProbeCompilerTest.kt`
- `core/database/src/androidTest/kotlin/app/zhijuan/core/database/MemoryDatabaseTest.kt`
- `core/database/src/androidTest/kotlin/app/zhijuan/core/database/MemorySearchRecallDatabaseTest.kt`
- `core/database/src/androidTest/kotlin/app/zhijuan/core/database/MemoryContextRouteSelectionDatabaseTest.kt`
- `core/database/src/androidTest/kotlin/app/zhijuan/core/database/MemorySearchProductionBenchmarkDatabaseTest.kt`
- `docs/06-AI-GENERATION-SYSTEM.md`
- `docs/15-TEST-PLAN.md`
- `docs/19-IMPLEMENTATION-BACKLOG.md`
- `docs/20-TRACEABILITY-MATRIX.md`
- `docs/22-WORK-STATUS.md`
- `docs/24-AI-DEVELOPMENT-PROTOCOL.md`
- `docs/ai/CURRENT-CONTEXT.md`
- 本报告

## 5. 验证证据

```text
core/database JVM：65/65
固定中文召回 + 三路选择 + 邻接回归专项：API 30 13/13、API 35 13/13
v1→v2 回填专项所在 MemoryDatabaseTest：API 30 10/10、API 35 10/10
API 30 core/database 全量：143/143
API 35 core/database 全量：143/143
统一离线门禁：797 actionable tasks，BUILD SUCCESSFUL
Release/R8：通过
安全扫描：SECURITY_SCAN_TESTS_OK、SECURITY_SCAN_OK，源码 + 5 个 APK
备份排除：BACKUP_EXCLUSION_POLICY_OK
git diff --check：通过（仅既有换行符提示）
真实 Provider 调用：0
物理设备安装/写入/设置修改：0
Git remote 操作：0
```

最初的基准测试文件曾因写入阶段的中文编码损坏无法编译，已经完整重建为 UTF-8；首次 API 30 运行随后暴露上述真实 FTS4 token 漏洞。两处都在本阶段修正并由双 API 全量回归覆盖，没有通过删测试或放宽召回断言绕过。

## 6. Sol / DeepSeek 分工

本阶段由 Sol 直接完成，没有调用 DeepSeek。固定集的价值来自真实 Room/SQLCipher/FTS4 执行、双 Android 版本差异和耗时数据；发现误召回后还需要联动 token 兼容、回填版本与设备回归，属于必须由 Sol 现场审查和验证的数据库边界。

## 7. 下一步

正式进入 TASK-061“编辑后派生失效和重建”：重点检查旧章节被用户编辑后，摘要、人物事件、事实、时间线、伏笔历史依赖、搜索索引和后续章节上下文如何按顺序失效与重建。

当前 App 仍没有按 phase 分发的总 runner，因此不能描述为已经可以自动跑完整生成流程。后续仍默认使用本地规则、Fake Provider 和项目专用模拟器；织卷 App 内真实生成 API 调用保持为 0。
