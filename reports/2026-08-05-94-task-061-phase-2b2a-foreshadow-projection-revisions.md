# 工作汇报 94：TASK-061 Phase 2B2A 伏笔投影修订账本

> 日期：2026-08-05  
> 项目：织卷 Android App  
> 状态：Phase 2B2A 已完成；TASK-061 仍进行中

## 1. 本阶段结果

正式数据库已从 schema v11 升到 v12。现在每一次成功的伏笔 PLANT/DEVELOP/RESOLVE/ABANDON 都会原子保存一条完整、不可变的 after-state revision，后续 rewind 不再需要从信息不完整的 transition 反推可见实体、重要度、目标窗口或来源版本。

本阶段只建立“可证明的历史状态”和安全 replay 基础，没有实现真正的编辑点 rewind、跨章重建 runner、aggregate writer 或 TEST-033。

## 2. 主要实现

### 2.1 schema v12

- 新增 `foreshadow_projection_revision`。
- 每条 revision 绑定 book、item、source chapter version、generation stage、transition、chapter index 与 story order。
- `transition_id` 唯一，避免同一次转换拥有两份相互冲突的 after-state。
- v11→v12 只创建空账本，不伪造 legacy after-state；旧 transition 原样保留。
- Room schema 导出新增 `12.json`，迁移链加入 `MIGRATION_11_12`。

### 2.2 完整规范快照

`ForeshadowProjectionSnapshotCodecV1` 保存 `ForeshadowItemEntity` 全部字段：

- 身份与书；
- 描述、伏笔状态和记忆状态；
- 目标章范围；
- source/planted/resolved 章节版本；
- 可见实体、重要度和来源；
- 创建时间与更新时间。

快照采用固定字段顺序的规范 JSON，并计算 SHA-256。读取时要求字段集合精确、类型正确、枚举合法、ID/数量/范围受限、visible IDs 唯一有序，而且 decode 后重新编码必须逐字节相同。默认错误和 `toString()` 不展开描述、JSON、hash 或标识符。

### 2.3 共享 post-CAS writer

tracking 独立提交与最终候选原子发布均调用同一个 `ForeshadowProjectionRevisionWriterV1`：

1. 先完成新 item 插入或既有 item CAS；
2. 插入 transition；
3. 从数据库重新读取真实 post-CAS item；
4. 复核 operation/status/source/planted/resolved/时间；
5. 在同一个 Room 事务中写入规范 revision。

因此模型提供的 partial DTO 不负责拼出恢复快照，两条生产路径也不会各自维护一套字段映射。

### 2.4 历史保护与失效顺序

- revision 除内容不变的 `VALID → STALE` 外禁止更新；禁止 `STALE → VALID` 和 DELETE。
- 触发器复核 revision 与 book/version/stage/item/transition/chapter/story order/createdAt 的 provenance。
- 若某条 transition 仍有 VALID revision，数据库禁止先把 transition 置为 STALE。
- 编辑 stale 级联调整为先 revision、后 transition，并把 revision 计数加入内部与公开 stale cascade 结果。

### 2.5 replay 与 later-current 修复

DeepSeek 只读审计发现 final replay 仍强制要求当前可变 item 等于旧 Stage 的 after-state。该做法在同一伏笔已被更晚章节 DEVELOP/RESOLVE 后会误拒绝合法 replay。

Sol 修复该问题，并进一步发现旧 Stage 还可能把 later-current 搜索索引回写到旧章节序号。现在 replay：

- 只用不可变 revision 校验当时 after-state；
- 当前 item 已变化时不误判旧账本损坏；
- 只有当前 item 仍逐字段等于旧 revision 时才补写该伏笔索引；
- 否则只修复不可变时间线索引，不覆盖后来合法的 current 状态。

## 3. DeepSeek 协作记录

- 设计审计：`20260805-222425-48e5823c`，只读，`max`；帮助确认 transition 单独不足以恢复完整 current state。
- 代码审计：`20260805-225927-6ce8af71`，只读，`max`，约 5 分 18 秒。
- 代码审计用量：总 Token 302,353；缓存输入 205,568；输出 28,938。
- DeepSeek 没有写工作树，结论由 Sol 逐项复核。
- 采纳：final replay 不应强比 later-current。
- Sol 追加修复：防止旧 Stage 覆盖 later-current 的伏笔搜索索引。
- 未采纳为当前缺陷：两条提交仓库不是同一候选流程内连续写两代；final candidate 流在最终事务一次性发布 tracking，独立 tracking repository 是另一条完成路径。现有 Stage/transition 唯一性和端到端测试未显示双写。

## 4. 测试证据

### 4.1 双 API 模拟器

- API 35：
  - `ZhijuanMigrationTest + MemoryDatabaseTest`：20/20；
  - `ChapterFinalCandidateCommitDatabaseTest`：27/27；
  - `ChapterTrackingProjectionEndToEndTest`：3/3；
  - `core/database` 全量：155/155。
- API 30：
  - `ZhijuanMigrationTest + MemoryDatabaseTest`：20/20；
  - `ChapterFinalCandidateCommitDatabaseTest`：27/27；
  - `ChapterTrackingProjectionEndToEndTest`：3/3；
  - `core/database` 全量：155/155。

测试只使用项目专用 `emulator-5556` 与 `emulator-5558`，没有向物理设备写入。

### 4.2 统一离线门禁

`scripts/verify-build.ps1 -Offline` 通过：

- Gradle 797 actionable tasks；
- 496 项 JVM 测试基线；
- Debug、Release、Lint、R8 均成功；
- 源码与 5 个 APK 安全扫描通过；
- 4 项安全扫描脚本回归通过；
- Android 备份排除策略通过。

App 内真实 Provider 调用为 0，没有产生真实模型费用。

## 5. 修改范围

核心实现：

- `core/database/src/main/kotlin/app/zhijuan/core/database/memory/DerivedAuditEntities.kt`
- `core/database/src/main/kotlin/app/zhijuan/core/database/memory/MemoryDao.kt`
- `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ForeshadowProjectionRevisionWriter.kt`
- `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterTrackingProjectionCommitRepository.kt`
- `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterFinalCandidateCommitRepository.kt`
- `core/database/src/main/kotlin/app/zhijuan/core/database/LibraryDatabaseGuards.kt`
- `core/database/src/main/kotlin/app/zhijuan/core/database/ZhijuanDatabase.kt`
- `core/database/src/main/kotlin/app/zhijuan/core/database/ZhijuanMigrations.kt`
- `core/database/schemas/app.zhijuan.core.database.ZhijuanDatabase/12.json`

同步更新相关数据库测试、TASK-061 状态、数据模型、生成系统、测试计划、追踪矩阵和 AI 交接文档。

## 6. 尚未完成与下一阶段

Phase 2B2B 仍需实现：

1. 以编辑点之前最后一个可信 revision 为基线；
2. 在单事务、完整版本区间栅栏下执行受审计 rewind；
3. 失效编辑点及其后续依赖的 revision/transition，而不是只处理直接来源版本；
4. 对 v11 legacy 缺账明确失败关闭或选择更早可信边界；
5. 保持普通生成不能把 `RESOLVED/ABANDONED` 重新打开；
6. 之后再接逐章 tracking/context/consistency/aggregate 有序重建和 TEST-033。

TASK-061 未完成，当前 App 仍没有总 phase runner，也不能描述为已经可以自动完成整本小说生成。
