# 工作汇报 95：TASK-061 Phase 2B2B 受审计伏笔回退

> 日期：2026-08-06  
> 项目：织卷 Android App  
> 状态：Phase 2B2B 已完成；TASK-061 仍进行中

## 1. 本阶段结果

正式数据库已从 schema v12 升到 v13。现在用户编辑旧章节后，系统可以在一个事务里把受影响的伏笔 current projection 安全回退到编辑点之前最后一个可证明的完整状态，同时失效编辑点及其后的旧 revision/transition、修复搜索索引，并保存不可篡改的审计证据。

这解决的是“伏笔投影如何可靠撤销”的底层问题，不等于编辑后的全部派生内容已自动重建。aggregate writer、跨章有序 Job/Stage 执行和 TEST-033 仍在后续阶段。

## 2. 实现内容

### 2.1 schema v13 审计账本

新增 `foreshadow_projection_rewind`，记录：

- rewind、书、编辑版本和被替换版本；
- 受影响章节首尾与冻结 `planHash`；
- 执行前 current projection、可信基线、执行后 projection 的集合 hash；
- 受影响、基线、编辑区间新生、失效 revision/transition 数量；
- 策略版本与执行时间。

`plan_hash` 唯一。数据库触发器核对编辑版本必须是 `USER_EDIT`、parent 必须是被替换版本、书/章/范围/hash/计数/策略必须一致，且审计时间不能早于编辑版本。审计行禁止更新和删除。

### 2.2 单事务 rewind

`ForeshadowProjectionRewindRepository` 的事务顺序固定为：

1. 重算完整 Phase 2A 计划并核对 `planHash`；
2. 验证当前编辑版本、parent 和受影响范围；
3. 读取编辑点至最新正式章的全部伏笔转换历史；
4. 为每个受影响 item 选择编辑点前、绑定当时 current 章节版本的最后一个 `VALID` 完整 revision；
5. 通过共享 revision verifier 校验规范 JSON、SHA-256 和 transition provenance；
6. 先失效区间 revision，再失效 transition，并断言两类 `VALID` 残留均为零；
7. 以全字段 CAS 恢复可信基线，或把区间首次 PLANT 的新生 item 保持/置为 `STALE`；
8. 删除全部受影响伏笔 FTS identity，只以可信基线章序重新索引恢复项；
9. 逐字段复核最终 current 集合，最后写入不可变审计。

任何一步失败都会回滚整笔事务。

### 2.3 legacy 失败关闭

v11 以前的 transition 没有完整 after-state。系统不会从描述不完整的历史猜测旧状态：

- 若区间第一条转换明确为 `PLANT(null → PLANTED)`，可以证明该 item 在编辑点前不存在；
- 若首条是 DEVELOP、RESOLVE、ABANDON 或其他需要旧 current 的操作，而编辑点前没有可信 revision，则整笔失败关闭。

### 2.4 replay、时间与索引

- 相同 rewind ID、plan、范围、时间、历史和结果的 replay 只核验证据，零写入返回。
- 同一个 plan 不能再用不同 rewind ID 执行。
- Phase 1 已经标为 `STALE` 的区间新生 item 保留原失效时间，不在 rewind 时伪造新时间。
- 恢复可信基线时允许恢复其真实历史 `updatedAt`，但必须使用全字段 CAS，不能覆盖并发变化。
- FTS 先删除受影响 item 的全部旧 identity，再只为可信基线重建，避免搜索仍指向被撤销的后续状态。

## 3. DeepSeek 审计

- 成功运行：`20260805-235105-afbf576a`。
- 模式：只读代码审计，`max` 推理，约 12 分 46 秒。
- 用量：总 Token 2,319,118；缓存输入 2,061,440；输出 72,473。
- DeepSeek 没有产生工作树代码写入，结论为无 P0/P1。
- Sol 采纳并修复两项 P2：已经 STALE 的区间新生 item 不应被 rewind 时间污染；审计 `createdAt` 不得早于编辑版本。
- 未采纳“只按 current-version join 统计区间 stale”的建议，因为这会遗漏异常遗留的旧版本 VALID 行，削弱清理和失败关闭保证。
- 第一次尝试被外层 60 秒命令超时提前中止；残留子进程已显式停止，该次不完整输出未参与结论。随后使用更合理的超时完成了上述成功审计。

## 4. 测试证据

### 4.1 双 API 模拟器

API 30 `emulator-5556` 与 API 35 `emulator-5558` 均通过：

- `ZhijuanMigrationTest + ForeshadowProjectionRewindDatabaseTest`：12/12；
- `core/database` 全量：159/159。

正向场景验证 A 在第 1 章 PLANT、第 2 章 DEVELOP、第 3 章 RESOLVE，B 在第 3 章首次 PLANT，编辑第 2 章后：A 精确恢复到第 1 章完整状态，B 为 STALE，历史未删除，失效顺序、FTS、审计、精确 replay 和 plan 唯一性均正确。

负向场景验证 legacy DEVELOP 缺少可信基线时全部回滚；另有回归专门验证 Phase 1 已 STALE item 的时间不被重写。

### 4.2 统一离线门禁

`scripts/verify-build.ps1 -Offline` 通过：

- Gradle 797 actionable tasks；
- Debug、Release、Lint 与 R8 成功；
- 安全扫描脚本回归 4 项通过；
- 源码与 5 个 APK 安全扫描通过；
- Android 备份排除策略通过。

所有 Android 测试只写项目专用模拟器。App 内真实 Provider 调用 0，没有产生真实模型费用，也没有向物理设备写入。

## 5. 主要修改文件

- `core/database/src/main/kotlin/app/zhijuan/core/database/memory/DerivedAuditEntities.kt`
- `core/database/src/main/kotlin/app/zhijuan/core/database/memory/MemoryDao.kt`
- `core/database/src/main/kotlin/app/zhijuan/core/database/library/ForeshadowProjectionRewindRepository.kt`
- `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ForeshadowProjectionRevisionWriter.kt`
- `core/database/src/main/kotlin/app/zhijuan/core/database/library/ChapterEditRebuildPlanRepository.kt`
- `core/database/src/main/kotlin/app/zhijuan/core/database/LibraryDatabaseGuards.kt`
- `core/database/src/main/kotlin/app/zhijuan/core/database/ZhijuanDatabase.kt`
- `core/database/src/main/kotlin/app/zhijuan/core/database/ZhijuanMigrations.kt`
- `core/database/schemas/app.zhijuan.core.database.ZhijuanDatabase/13.json`
- `core/database/src/androidTest/kotlin/app/zhijuan/core/database/ForeshadowProjectionRewindDatabaseTest.kt`
- `core/database/src/androidTest/kotlin/app/zhijuan/core/database/ZhijuanMigrationTest.kt`

同步更新生成系统、数据模型、状态机、测试计划、待办、追踪矩阵、工作状态、AI 开发协议和当前交接文档。

## 6. 尚未完成与下一阶段

下一阶段是 TASK-061 Phase 2B3：

1. 为 aggregate projection 建立与现有历史槽兼容的重建 writer；
2. 把冻结计划步骤接入严格按章节和依赖顺序执行的 Job/Stage 链；
3. 在每步执行前重验 current version、来源 hash 和上游派生头；
4. 保留后续正文，不伪造 Provider 输出，不绕过费用和用户选择边界；
5. 完成 TEST-033 的编辑后顺序重建端到端证据。

TASK-061 尚未完成。当前 App 仍没有总 phase runner，也不能描述为已经可以自动完成整本小说生成。
