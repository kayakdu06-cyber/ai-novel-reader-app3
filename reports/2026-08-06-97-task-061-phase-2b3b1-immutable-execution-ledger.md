# 工作汇报 97：TASK-061 Phase 2B3B1 不可变执行准备账本

> 日期：2026-08-06  
> 项目：织卷 Android App  
> 唯一工作目录：`D:\gptuser\projects\ai-novel-reader-app2`

## 1. 阶段结论

本阶段已完成 schema v14 的不可变“编辑后重建准备账本”。它解决的是：章节编辑后的伏笔 rewind 不能单独落库，必须同时留下可验证、可精确重放、不会因 `planHash` 后续变化而失去身份的执行凭据。

现在 `ChapterEditRebuildExecutionRepository.prepare` 会在一个外层 Room 事务中完成：

1. 执行或精确重放 schema v13 的受审计伏笔 rewind；
2. 再次核对编辑版本、被替换 parent、完整受影响 current 章节和时间；
3. 冻结准备时真实存在的摘要、tracking、aggregate 基线及全字段指纹；
4. 计算不依赖动态 `planHash` 的 stable fence；
5. 写入一条 execution 和固定顺序的关键 step；
6. 写后精确回读，任何不一致都回滚整个事务。

本阶段没有创建 GenerationJob、Stage、Attempt、Usage，没有调用 App 内真实 Provider，也没有向物理设备写入。

## 2. 为什么不能一次创建所有 Stage

后续章节的 tracking 输入依赖前一章真实产生的 memory、伏笔和 tracking 结果，而现有 Stage 的 `inputSourcesJson` 创建后不可变。如果在执行开始时把所有 Stage 一次建完，只能写入不存在的结果或符号占位，这会让来源审计失真。

因此采用以下边界：

- v14 只写不可变执行 fence 和真实准备基线；
- 后续阶段从第一个 `PENDING` 步骤开始；
- 只有直接前驱真实落库后，才动态创建下一 Stage；
- 普通 tracking 顺序保护继续保留，不能因重建而全局放宽。

## 3. 数据模型与保护

### `chapter_edit_rebuild_execution`

- 唯一绑定 edited version、replaced version、rewind、影响区间和 `KEEP_EXISTING`；
- edited version、rewind、stable fence 各自唯一；
- 保存准备时 `initialPlanHash`，但不把它用作长期执行身份；
- 状态当前仅允许不可变 `PREPARED`；
- UPDATE、DELETE 均由数据库触发器拒绝。

### `chapter_edit_rebuild_step`

- 主键为 `(execution_id, step_ordinal)`；
- 同一 execution 的 `(step_type, chapter_index)` 唯一；
- 固定关键链为：编辑章 memory，以及每个受影响章节的 tracking、aggregate；
- 准备状态仅为 `PENDING/SATISFIED`；
- 可绑定 VALID summary、tracking、aggregate 基线及全字段 SHA-256 指纹；
- 外键均为 `RESTRICT`，行禁止 UPDATE、DELETE；
- 插入触发器复核书、章、current version、正文 hash、范围、时间、类型、Provider 属性和基线槽。

## 4. 原子性、重放与隐私

- 同一命令精确 replay 不增加 rewind、execution 或 step；
- 另一 rewind identity 不能占用同一计划；
- 计划过期在写入前失败；
- 特意构造“rewind 已经执行，随后基线时间门禁失败”的场景，外层事务仍把 rewind 和 ledger 一起回滚；
- 稳定 fence 覆盖完整 current 章节、rewind before/baseline/after 证据和实际基线指纹；
- command/result/entity 的默认 `toString()` 不展开书、章节、版本、执行、摘要身份、正文或 hash。

## 5. DeepSeek 设计审计的使用情况

- 运行：`20260806-011339-0d8f70cf`
- 模式：只读、补丁提案关闭、`max` 推理；无工作树写入
- 结论中“需要 stable execution ledger、不能用动态 planHash 当身份、普通顺序保护不能全局放宽”被采纳。
- “为一次执行预建全部 Stage”的建议未采用，因为它与现有不可变 Stage 来源契约冲突。
- 没有新增 `GenerationPhase`；后续会复用现有 `EXTRACT_MEMORY` 和 tracking 语义，以显式重建 binding 区分。

## 6. 主要文件

- `core/database/src/main/kotlin/app/zhijuan/core/database/library/ChapterEditRebuildExecutionEntities.kt`
- `core/database/src/main/kotlin/app/zhijuan/core/database/library/ChapterEditRebuildExecutionDao.kt`
- `core/database/src/main/kotlin/app/zhijuan/core/database/library/ChapterEditRebuildExecutionRepository.kt`
- `core/database/src/main/kotlin/app/zhijuan/core/database/ZhijuanDatabase.kt`
- `core/database/src/main/kotlin/app/zhijuan/core/database/ZhijuanMigrations.kt`
- `core/database/src/main/kotlin/app/zhijuan/core/database/LibraryDatabaseGuards.kt`
- `core/database/schemas/app.zhijuan.core.database.ZhijuanDatabase/14.json`
- `core/database/src/androidTest/kotlin/app/zhijuan/core/database/ZhijuanMigrationTest.kt`
- `core/database/src/androidTest/kotlin/app/zhijuan/core/database/ChapterEditRebuildPlanDatabaseTest.kt`

## 7. 验证证据

### 编译与 schema

- `:core:database:compileDebugKotlin --offline`：通过；Room 导出 schema 14。
- `:core:database:compileDebugAndroidTestKotlin --offline`：通过。

### 双模拟器专项

- API 35 `emulator-5558`：migration + plan/ledger 9/9。
- API 30 `emulator-5556`：migration + plan/ledger 9/9。

### 双模拟器数据库全量

- API 35：171/171，0 失败、0 错误、0 跳过。
- API 30：171/171，0 失败、0 错误、0 跳过。

### 统一离线门禁

- `scripts/verify-build.ps1 -Offline`：通过。
- Gradle：797 actionable tasks；Debug、Release、Lint、R8、JVM 测试通过。
- 安全扫描脚本：4/4。
- 源码和 5 个 APK：`SECURITY_SCAN_OK`。
- Android 备份排除策略：通过。
- `git diff --check`：无空白错误；仅有既有 Windows 行尾提示。

首轮迁移测试曾因把 v14 表错误加入一个专门验收 v10 的旧辅助检查而失败；该测试范围错误已移除。v13→v14 专项继续单独验证新表、索引和触发器，随后完整迁移套件与双 API 全量均通过。

## 8. 尚未完成

- 还没有 `PREPARED → RUNNING/NEEDS_ACTION/COMPLETED` 的执行事件或状态推进；
- 还没有从 ledger 动态创建 memory/tracking Stage；
- 还没有把 tracking 的专门重建许可、提交后 aggregate 和下一章解锁接成完整链；
- context/consistency 的最终重建仍未接入；
- TEST-033 未完成；
- App 仍没有按 phase 分发的总 runner。

下一阶段是 Phase 2B3B2：以 stable fence 为边界，动态创建第一个真实 Stage，并完成 Provider-open 前和提交时的双重来源门禁。
