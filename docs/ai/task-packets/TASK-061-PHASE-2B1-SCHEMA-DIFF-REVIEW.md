# TASK-061 / Phase 2B1：v11 派生历史槽小差异复核

## 任务身份

- 仓库：`D:\gptuser\projects\ai-novel-reader-app2`
- HEAD：`8ce774429da1c3f7139a221bc241c34d81a2efdd`，当前 dirty WIP 不得清理。
- 模型：DeepSeek V4 Flash，只读复核。

## 运行预算

- 推理等级：`max`
- 最长时间：12 分钟
- 无累计 Token 上限
- 只读文件最多 10 个、命令最多 6 个
- 只返回审查结论；禁止重新设计整个 TASK、禁止生成大补丁、禁止写工作树

## 已实现且已验证

Sol 已完成 Phase 2B1 的第一小块：

1. Room schema v10→v11；
2. summary、tracking、aggregate、transition 四个业务槽的 unique index 改为普通 index；
3. `LibraryDatabaseGuards` 触发器保证同一业务槽最多一个 `VALID`，只允许内容不变的同状态更新或 `VALID→STALE`；
4. 生产 DAO 对 summary/event/fact/timeline/tracking/transition 显式只读 `VALID`，另有稳定排序的 history 查询；
5. v10→v11 迁移测试和新库 history-slot 测试；
6. API 30/API 35 的 `ZhijuanMigrationTest + MemoryDatabaseTest` 各 18/18 通过。

## 只读清单

1. `AGENTS.md`
2. `docs/ai/task-packets/TASK-061-PHASE-2B1-DERIVED-HISTORY-SCHEMA-AUDIT.md`（只读目标/约束，不再递归）
3. `core/database/src/main/kotlin/app/zhijuan/core/database/ZhijuanDatabase.kt`
4. `core/database/src/main/kotlin/app/zhijuan/core/database/ZhijuanMigrations.kt`（只看 `MIGRATION_10_11` 与 `ALL`）
5. `core/database/src/main/kotlin/app/zhijuan/core/database/LibraryDatabaseGuards.kt`（只看 derived history 新增段）
6. `core/database/src/main/kotlin/app/zhijuan/core/database/memory/MemoryEntities.kt`（只看四个 index）
7. `core/database/src/main/kotlin/app/zhijuan/core/database/memory/DerivedAuditEntities.kt`（只看 aggregate index）
8. `core/database/src/main/kotlin/app/zhijuan/core/database/memory/MemoryDao.kt`（只看约 418–515 行新增/修改查询）
9. `core/database/src/androidTest/kotlin/app/zhijuan/core/database/ZhijuanMigrationTest.kt`（只看 v10→v11 新测试）
10. `core/database/src/androidTest/kotlin/app/zhijuan/core/database/MemoryDatabaseTest.kt`（只看 `derivedHistorySlots...`）

可以运行针对上述文件的 `git diff`、`rg` 和只读查看。不要读日志、报告、其他模块、C 盘或其他项目。

## 必须回答

1. 是否存在 P0/P1 正确性问题：迁移丢数据、fresh/migrated schema 漂移、双 VALID 并发、STALE→VALID、内容篡改、权威 DAO 混入历史、旧 Stage replay 被意外放宽？
2. 触发器 `IS NOT`、状态转换和 `updated_at` 规则是否覆盖 NULL 与 SQLite 语义？
3. 哪个现有查询仍可能在多代历史后返回多行或随机一行？
4. Phase 2A 的 batch tracking 查询现在读取全历史并 `associateBy`；它应当只读取 VALID 头还是冻结全部历史？给出单一建议。
5. transition 历史槽放开后，为什么 foreshadow current projection rewind 仍必须保持阻塞？

## 输出格式与限制

只返回：

1. `结论`
2. `P0/P1 问题`
3. `P2 建议`
4. `最小修正`（最多 120 行 unified diff；无问题则写“无”）
5. `仍未解决边界`

不得宣布 Phase 2B1/TASK-061 完成，不得写仓库，不得运行设备测试或调用 App Provider。
