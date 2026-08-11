# TASK-060 / 第 1A 阶段：正式加密书库 FTS 基础结构

## 任务身份

- 任务 ID：`TASK-060 / Phase 1A / Production FTS schema`
- 仓库根目录：`D:\gptuser\projects\ai-novel-reader-app2`
- 基准分支与 HEAD：`main` / `8ce7744`（仅用于识别副本，不得回退；以当前工作区为准）
- 当前未提交改动：仓库存在大量 TASK-059 已审查 WIP、DeepSeek 隔离脚本和文档；必须完整保留，不得清理、回退、覆盖或格式化无关文件
- 执行模型：DeepSeek（纯文本）

## 运行预算

- 推理等级：`max`（用户持续指令，不得降低）
- 最长运行时间：20 分钟；理由：Room FTS 外部内容表的手写迁移、触发器和 schema 校验需要完整推理与一次编译反馈
- 累计 Token 上限：不设置；用户已明确允许，不得因旧模板默认值自行提前终止
- 预计读取文件数与明确清单：15 个，见“必读资料”；只可额外读取这些文件直接引用的数据库类型或构建配置
- 预计执行命令/测试数：最多 3 个，只允许 Git 根目录/状态检查、相关 Kotlin 编译；不要启动模拟器或运行 Android instrumentation
- 提前停止条件：首次明确权限阻塞；需要修改允许范围外的生产提交/上下文代码；同类补丁或编译失败重复两次；发现数据契约无法由当前范围安全表达；出现未声明的高风险改动

## 目标

把已经通过技术尖峰的“同一 SQLCipher 数据库内 Room FTS4 + 确定性中文 token”正式注册进 `ZhijuanDatabase` schema v9。交付只包含生产索引内容表、FTS4 外部内容表、最小 DAO、8→9 迁移及迁移测试，不接索引写入、不接生成上下文、不宣称完整多路召回完成。

## 当前现场与已有 WIP

- 已存在的实现：`SearchIndexText` 已实现确定性的 Han 单字/双字 ASCII token；`SearchDocumentEntity`、`SearchDocumentFtsEntity`、`SearchDocumentDao` 和 `ZhijuanSearchSpikeDatabase` 已在独立尖峰库验证 FTS4 与 rowid 子查询性能。
- 已存在的测试：`SearchIndexTextTest` 与 `EncryptedSearchDatabaseTest` 覆盖固定中文召回、10,000 文档、更新同步和加密；`ZhijuanMigrationTest` 覆盖生产 schema 1→当前版本的连续迁移与 SQLCipher。
- 已知缺口：正式 `ZhijuanDatabase` 仍是 schema v8，没有生产检索表；当前上下文组装仍用固定上限列表，没有 FTS 路由。
- 必须延续、不得从零重写：保留 `SearchIndexText` 和独立尖峰数据库；生产表使用新名称，避免把含完整 `content` 的尖峰实体直接混入正式书库。

## 必读资料

1. `AGENTS.md`
2. `docs/24-AI-DEVELOPMENT-PROTOCOL.md`
3. `docs/ai/CURRENT-CONTEXT.md`
4. `docs/adr/ADR-004-chinese-search.md`
5. `docs/09-DATA-MODEL.md`
6. `docs/10-STATE-MACHINES.md`
7. `core/database/src/main/kotlin/app/zhijuan/core/database/search/SearchIndexText.kt`
8. `core/database/src/main/kotlin/app/zhijuan/core/database/search/SearchDocumentEntity.kt`
9. `core/database/src/main/kotlin/app/zhijuan/core/database/search/SearchDocumentFtsEntity.kt`
10. `core/database/src/main/kotlin/app/zhijuan/core/database/search/SearchDocumentDao.kt`
11. `core/database/src/main/kotlin/app/zhijuan/core/database/ZhijuanSearchSpikeDatabase.kt`
12. `core/database/src/main/kotlin/app/zhijuan/core/database/ZhijuanDatabase.kt`
13. `core/database/src/main/kotlin/app/zhijuan/core/database/ZhijuanMigrations.kt`
14. `core/database/src/androidTest/kotlin/app/zhijuan/core/database/ZhijuanMigrationTest.kt`
15. `core/database/schemas/app.zhijuan.core.database.ZhijuanSearchSpikeDatabase/2.json`

除上述清单和代码直接引用外，不得递归扫描整套文档、历史会话、备份或无关模块；需要扩展读取范围时在回交中说明并停止扩张。

## 范围

允许修改：

- 新建 `core/database/src/main/kotlin/app/zhijuan/core/database/search/MemorySearchDocumentEntity.kt`
- 新建 `core/database/src/main/kotlin/app/zhijuan/core/database/search/MemorySearchDocumentFtsEntity.kt`
- 新建 `core/database/src/main/kotlin/app/zhijuan/core/database/search/MemorySearchDao.kt`
- 修改 `core/database/src/main/kotlin/app/zhijuan/core/database/ZhijuanDatabase.kt`
- 修改 `core/database/src/main/kotlin/app/zhijuan/core/database/ZhijuanMigrations.kt`
- 修改 `core/database/src/androidTest/kotlin/app/zhijuan/core/database/ZhijuanMigrationTest.kt`

明确不在范围：

- 不修改现有尖峰实体、DAO、数据库或测试
- 不修改任何 memory/generation 提交仓库、`ChapterContextAssemblyRepository`、App/UI、Provider、网络或费用代码
- 不更新正式任务完成状态、工作状态、追踪矩阵或工作汇报
- 不运行模拟器、不写物理设备、不调用任何真实 API

## 不可破坏的约束

- 项目隔离：Git 根目录必须精确为 `D:/gptuser/projects/ai-novel-reader-app2`；不得访问或修改其他项目副本。
- 编辑工具：只能用 `apply_patch` 修改源码/测试，不得使用 `Set-Content`、`WriteAllText`、重定向、Python 写文件或其他绕过方式。
- 安全与隐私：生产索引只能位于同一个加密 `ZhijuanDatabase`；新内容表不得保存完整小说正文、摘要 JSON、事实原文或提示词，只保存确定性 `search_terms`、来源身份、排序元数据和来源内容 hash。
- 状态与失效：本阶段把“行存在”定义为当前可召回派生项；不要新增一个与正式来源状态可能漂移的重复 status 字段。后续阶段会在来源提交/失效事务内插入、更新或删除这些行。
- 数据库与事务：`memory_search_document` 必须是 FTS 外部内容表，`memory_search_document_fts` 必须使用 FTS4；迁移必须建立 Room 期望的四个同步触发器。现有 1→8 迁移和数据不能改变。
- 查询计划：FTS 查询必须先通过 `memory_search_document_fts` 的 rowid 子查询命中，再回内容表，并以 `book_id` 隔离；禁止改回普通 JOIN。
- 数据契约：内容表至少包含自增 SQLite `rowid`、全局唯一 `document_id`、`book_id`、`source_type`、`source_id`、可空 `chapter_index`、可空 `story_order`、`importance`、`source_content_hash`、`search_terms`、`updated_at`。给 `book_id` 添加到 `book` 的 RESTRICT 外键；给 `(book_id, source_type, source_id)` 唯一索引，并提供检索所需索引。
- 最小 DAO：提供 ABORT 插入、按主键更新、按稳定 document ID 查找、按稳定 document ID 删除、rowid-first 的分书检索、计数。检索只返回 target chapter 之前或无章节边界的文档，排序必须稳定，limit 由后续仓库校验；不要在 DAO 内生成 token。
- 兼容性：生产 schema 升到 9，`ZhijuanMigrations.ALL` 增加连续 8→9；迁移后表、虚表、索引和四个触发器都必须存在，旧数据保持不变。
- 保留现场：不要改动或清理任何现有未提交文件，不要执行 Git reset/checkout/clean/commit，不添加 remote。

## 实施要求

1. 新建上述三份生产检索源文件；类与 DAO 保持 `internal`，除非 Room 代码生成明确要求更宽可见性。
2. 在 `ZhijuanDatabase` 注册两个实体和 DAO，并把 schema 版本从 8 升到 9。
3. 新增严格的 `MIGRATION_8_9`：内容表、外键、索引、FTS4 虚表、四个 Room 风格同步触发器；加入迁移注册表。
4. 更新 `ZhijuanMigrationTest.assertLatestSchemaAvailable`，至少验证两个表、关键索引和四个触发器存在；增加一项 8→9 专项，插入最小 book 后通过 SQL 插入/更新/删除正式检索文档，证明 FTS 触发器同步且分书检索依赖的结构可用。若适合可复用现有迁移循环，但不能只检查表名。
5. 可运行相关 Kotlin 编译来生成/校验 Room schema；不要手工编辑 JSON schema 文件。若编译自动生成 v9 schema，保留生成结果并在回交列出。

## 验收标准

- [ ] `ZhijuanDatabase` v9 能让 Room 生成生产内容表与 FTS4 外部内容表。
- [ ] 8→9 连续迁移不改旧表/旧行，完整建立预期索引和四个同步触发器。
- [ ] 正式表不保存完整源文本，只保存派生 token、来源元数据和 hash。
- [ ] DAO 查询保持 FTS rowid 子查询优先、分书隔离、章节上界与稳定排序。
- [ ] 新增迁移测试覆盖 INSERT/UPDATE/DELETE 后 FTS 同步，不把“仅创建表”当成通过。
- [ ] 不触碰生成链、真实 API、UI、旧尖峰或正式状态文档。

## 验证命令

先设置所有缓存/临时目录到 `D:\gptuser`，再运行：

```powershell
.\gradlew.bat :core:database:compileDebugKotlin --offline --no-daemon
```

instrumentation 迁移测试由 Sol 使用指定模拟器运行，DeepSeek 不运行。未运行的验证必须明确写“未运行”及原因，不能写成通过。

## 回交格式

请严格按以下标题返回：

1. `完成内容`
2. `修改文件`
3. `验证`
4. `未完成/风险`
5. `需要 Sol 处理`
6. `假设`

不要宣布整个 TASK-060 完成，也不要更新正式完成状态；由 Sol 根据差异和模拟器证据确认。
