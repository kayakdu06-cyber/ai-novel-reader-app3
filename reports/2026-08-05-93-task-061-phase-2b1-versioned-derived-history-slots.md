# 工作汇报 93：TASK-061 Phase 2B1 可版本化派生历史槽

> 日期：2026-08-05  
> 项目：织卷 Android App  
> 唯一仓库：`D:\gptuser\projects\ai-novel-reader-app2`  
> 状态：Phase 2B1 完成；TASK-061 仍进行中，TEST-033 尚未完成

## 1. 本阶段解决的问题

用户编辑旧章节后，旧摘要、tracking、聚合和伏笔转换必须保留供审计，但原 schema 的唯一索引又不允许同一来源写入新一代。本阶段把正式 SQLCipher/Room 数据库升级到 schema v11，使旧代可以保留为 `STALE`，新代可以作为唯一 `VALID` 当前头。

完成后的数据库规则是：

- summary：每个 `chapter_version_id` 可保留多代，最多一个 VALID；
- tracking：每个 `chapter_version_id` 可保留多代，最多一个 VALID；`generation_stage_id` 仍唯一；
- aggregate：每个 `(book_id, through_chapter_index)` 可保留多代，最多一个 VALID；
- transition：每个 `(foreshadow_item_id, source_chapter_version_id)` 可保留多代，最多一个 VALID；
- summary/event/fact/timeline/tracking/aggregate/transition 七类历史均禁止删除、禁止内容或来源篡改、禁止 `STALE → VALID`；
- 唯一允许的历史行变化是内容不变的 `VALID → STALE`。

这关闭了 Phase 2A 中“旧派生占住唯一槽，只能覆盖或删除历史”的结构性风险。

## 2. 权威数据与历史数据不再混读

生产单行和列表查询现在显式只返回 VALID。Phase 2A 的批量 tracking 查询还会核对 projection 绑定的是章节 current version，避免旧版本或旧代被 `associateBy` 任意选中。

审计需要查看全部代时，必须调用名称明确的 history 查询；这些查询按创建时间和主键稳定排序。summary、event、fact、timeline、tracking、aggregate 和 transition 均有对应历史读取入口。

## 3. 迁移与并发保护

v10→v11 迁移只删除并重建四个索引为普通索引，然后安装与 fresh create 相同的数据库触发器。它不复制、不删除、不改写任何正文、JSON、模型快照或既有派生行。

单一 VALID 不是 repository 的“先查再写”，而是 SQLite 触发器在 INSERT/UPDATE 时执行，因此两个协程同时争抢空槽也只能成功一个。NULL 字段使用 `IS NOT` 比较，不能用 NULL 绕过不可变检查。

## 4. DeepSeek 审计与 Sol 修正

本阶段先后使用两个只读任务包：

1. `TASK-061-PHASE-2B1-DERIVED-HISTORY-SCHEMA-AUDIT.md`
   - 运行 ID：`20260805-140811-eb0471c5`
   - `max` 推理，25 分钟硬上限，无总 Token 上限
   - 累计 Token 4,825,918，其中缓存 4,131,968、输出 164,896
   - 因任务仍过大，在 25 分钟结束前只完成多轮代码审计，没有最终补丁，也没有工作树写入或残留进程。
2. `TASK-061-PHASE-2B1-SCHEMA-DIFF-REVIEW.md`
   - 运行 ID：`20260805-145447-3010979f`
   - `max` 推理，约 5 分 55 秒完成
   - 累计 Token 548,708，其中缓存 451,328、输出 38,624
   - 没有 P0；指出 Phase 2A batch tracking 会读取全历史并可能任意选择 projection。

Sol 将 batch 查询改为 VALID+current version，并在窄审计之后进一步补上七类派生历史的 DELETE 保护、event/fact/timeline 内容不可变和 `STALE → VALID` 负例。DeepSeek 没有调用 App Provider，也没有修改工作树。

## 5. 主要代码改动

- `ZhijuanDatabase.kt`：schema 版本 10→11。
- `ZhijuanMigrations.kt`：新增并注册无损 `MIGRATION_10_11`。
- `MemoryEntities.kt`、`DerivedAuditEntities.kt`：四个历史业务槽从唯一索引改为普通索引。
- `LibraryDatabaseGuards.kt`：单一 VALID、不可恢复、不可篡改、不可删除和时间单调触发器。
- `MemoryDao.kt`：authority/history 查询分离，tracking 批量 authority 查询绑定 current version。
- `ChapterEditRebuildPlanRepository.kt`：只使用权威 tracking 头。
- `ZhijuanMigrationTest.kt`、`MemoryDatabaseTest.kt`：迁移、两代历史、并发与失败关闭证据。
- Room 导出 schema：`core/database/schemas/app.zhijuan.core.database.ZhijuanDatabase/11.json`。

## 6. 验证证据

| 验证 | 结果 |
|---|---|
| 生产与 AndroidTest 编译 | 通过 |
| 迁移 + 历史槽定向，API 30 | 18/18 通过 |
| 迁移 + 历史槽定向，API 35 | 18/18 通过 |
| `core/database` 全量，API 30 | 152/152 通过 |
| `core/database` 全量，API 35 | 152/152 通过 |
| Gradle JVM XML | 496 项，0 失败、0 错误、0 跳过 |
| `scripts/verify-build.ps1 -Offline` | 797 actionable tasks，BUILD SUCCESSFUL |
| Release/R8 | 通过 |
| 安全扫描 | `SECURITY_SCAN_TESTS_OK`、`SECURITY_SCAN_OK`，源码与 5 个 APK |
| 备份排除 | `BACKUP_EXCLUSION_POLICY_OK` |
| `git diff --check` | 通过，仅现有 LF/CRLF 提示 |

首次 API 35 定向命令曾因 PowerShell 未给 `-P...` 参数加引号而被 Gradle误识别为任务名；修正命令引用后通过。这是命令行调用错误，不是产品或测试失败。

全部验证仅使用项目专用 API 30/API 35 模拟器和本地固定夹具。App 内真实生成 API 调用 0，物理设备安装、写入和设置变更 0。

## 7. 明确未完成项

- `foreshadow_item` 仍是可变 current projection；没有 checkpoint/head、从编辑点 rewind 或按转换台账 replay 的完整证据。
- aggregate 仍缺正式重建 writer；tracking 的跨章顺序保护不能删除。
- 尚未创建有序重建 Job/Stage/Attempt/Usage，也没有调用 Fake Provider 执行 10 章重建。
- TEST-033 尚未完成，TASK-061 不能标记完成。
- 当前 App 仍没有按 phase 分发的总 runner，不能描述为已经可以自动跑完整小说生成链。

## 8. 下一阶段

进入 Phase 2B2：先设计并实现 `foreshadow_item` 的持久 checkpoint/rewind/replay 基础，证明从编辑点恢复当前伏笔状态不会覆盖历史或重新打开终态；之后再接版本化 writer 和有序重建执行。
