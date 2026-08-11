# 工作汇报 84：TASK-060 旧书检索索引首次回填

> 日期：2026-08-05  
> 项目：织卷 Android App  
> 唯一项目根目录：`D:\gptuser\projects\ai-novel-reader-app2`  
> 阶段：TASK-060 Phase 1C 完成；整个 TASK-060 尚未完成

## 1. 阶段结果

正式 SQLCipher 书库已从 schema v9 升级到 v10。旧书第一次组装章节上下文时，会先按书检查回填标记；没有当前版本标记时，在一个 Room 事务内重建六类正式记忆源的检索索引，最后才写入完成标记。完成过的书直接跳过全量扫描，后续继续由 Phase 1B 的增量 writer 维护。

这关闭了此前最严重的兼容缺口：v9 以前已有的书即使迁移出 FTS 表，也不会因为索引表为空而永远召回不到既有记忆。

## 2. 实现内容

1. 新增 `memory_search_backfill_state`：以 `book_id` 为主键和外键，只保存索引 schema 版本与完成时间，不复制正文、中文、JSON、提示词或模型输出。
2. 新增 v9→v10 显式迁移；迁移只建完成标记表，不在 SQL 中尝试中文分词，旧检索文档与 FTS 同步触发器保持不变。
3. 新增按书回填仓库：
   - 先验证书存在；
   - 当前标记存在时精确跳过；
   - 仅删除目标书的旧检索文档；
   - 六类来源均使用稳定主键的 keyset 分页，默认每页 250、硬上限 500，不使用 OFFSET 或无界列表；
   - 章节派生数据通过联表一次取得真实 `chapter_index`，没有逐行 N+1 查询；
   - 只纳入未归档人物、当前章节版本的 VALID 摘要/人物事件/事实/时间线，以及 VALID 且未解决/未放弃的伏笔；
   - 无章节来源的全局事实和用户伏笔仍可进入检索；
   - 完成标记最后写入。
4. 章节上下文组装在书、Job、章节和 Prompt Bundle 绑定通过后、读取召回来源前执行 ensure-ready。
5. 任一 JSON、工厂、SQL、取消或进程故障都会让外层事务整体回滚，不留下“索引只有一半但已标完成”的状态。

## 3. 全量回归发现并修复的问题

第一次 API 30 数据库全量回归出现 2 个失败。原因不是事务或迁移错误，而是旧书中一个合法的 40,000 字符时间线 JSON 叶子超过了检索构造器原来的 8 KiB 单叶硬限制，导致首次上下文组装被拒绝。

修复后，8 KiB 改为受控的“叶片计费单位”，长叶子按多个片段占用 512 片段配额；同时仍保留：

- 单个 JSON 最大 64 KiB；
- 检索输入总量最大 256 KiB；
- 检索词总量最大 256 KiB；
- 最大 JSON 深度与固定片段总数；
- 完整可读内容参与 source hash，尾部变化也会使哈希变化；
- 索引表仍只保存派生 ASCII token，不复制原始 JSON。

因此合法旧数据不会造成整本书不可用，超大或畸形输入仍会有界、失败关闭。

## 4. 测试证据

### 新增/扩展专项

- 六类来源完整回填，并强制 `pageSize = 1` 验证跨页 keyset 推进；
- 旧章节版本、归档人物与 stale 伏笔不进入索引；
- 全局事实、无章节来源伏笔和真实章节索引映射正确；
- 第二本书的检索文档完全不变；
- 当前完成标记使第二次调用精确跳过；
- 中途遇到坏 JSON 时，旧索引、FTS 与缺失标记全部回滚；
- v9→v10 迁移保留既有检索文档和 FTS，标记表初始为空；
- 40,000 字符合法 JSON 叶子可索引且尾部变化会旋转 source hash。

### Android 模拟器

- Android 11 / API 30，项目专用 `emulator-5556`：core/database 全量 `121/121`，0 失败、0 跳过。
- Android 15 / API 35，项目专用 `zhijuan_api35_clean`：core/database 全量 `121/121`，0 失败、0 跳过。
- 两套设备均确认 `ro.kernel.qemu=1`；物理设备写入 0。

### 统一离线门禁

```text
BUILD SUCCESSFUL
797 actionable tasks
SECURITY_SCAN_TESTS_OK
CASES=4
SECURITY_SCAN_OK
ARTIFACT_COUNT=5
BACKUP_EXCLUSION_POLICY_OK
```

门禁实际执行全模块 JVM、Debug、Release/R8、扫描器回归、源码/报告/APK 密钥扫描和备份排除验证。

## 5. DeepSeek 协作与 Sol 审查

- 任务包：`docs/ai/task-packets/TASK-060-PHASE-1C-LEGACY-INDEX-BACKFILL.md`
- 运行 ID：`20260805-070905-1a079c5d`
- 模型：DeepSeek V4 Flash，`max` 推理，无总 Token 上限，约 5 分 18 秒。
- DeepSeek 实际仓库写入：0；交付的是审计与补丁建议。
- Sol 修正三点后落地：标记 DAO 必须支持未来版本升级；无来源章节的有效伏笔不能被 INNER JOIN 丢弃；完成时间必须使用调用方已验证时间，不能在仓库内部读取系统时钟。
- 全量回归暴露的长 JSON 兼容问题由 Sol 继续定位、修复并补测。

## 6. 主要修改文件

新增：

- `core/database/src/main/kotlin/app/zhijuan/core/database/memory/MemorySearchBackfillRows.kt`
- `core/database/src/main/kotlin/app/zhijuan/core/database/search/MemorySearchBackfillRepository.kt`
- `core/database/src/main/kotlin/app/zhijuan/core/database/search/MemorySearchBackfillStateDao.kt`
- `core/database/src/main/kotlin/app/zhijuan/core/database/search/MemorySearchBackfillStateEntity.kt`
- `core/database/schemas/app.zhijuan.core.database.ZhijuanDatabase/10.json`
- 本报告

修改：

- `ZhijuanDatabase.kt`、`ZhijuanMigrations.kt`
- `MemoryDao.kt`、`MemorySearchDao.kt`、`MemorySearchDocumentFactory.kt`
- `ChapterContextAssemblyRepository.kt`
- `MemoryDatabaseTest.kt`、`ZhijuanMigrationTest.kt`、`MemorySearchDocumentFactoryTest.kt`
- 项目状态与交接文档

## 7. 未完成与下一步

Phase 1C 已完成，但 TASK-060 仍未完成。下一阶段是 Phase 2：把“计划关键词 + FTS + 最近章节窗口 + 强制硬事实 + 未解决伏笔”装配成确定性的多路候选召回，完成跨路去重、排序、章节上界和预算裁剪。随后才是固定中文召回集、API 30/API 35 验收与 TASK-060 总收口。

本阶段织卷 App 内真实 Provider 调用 0、Git remote 操作 0、提交/回退/清理 0。
