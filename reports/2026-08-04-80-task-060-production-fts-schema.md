# 工作汇报 80：TASK-060 正式加密书库 FTS 基础结构

> 日期：2026-08-04  
> 项目：织卷 Android App  
> 项目根目录：`D:\gptuser\projects\ai-novel-reader-app2`  
> 阶段：TASK-060 第 1A 阶段完成；整个 TASK-060 尚未完成

## 1. 本阶段结果

此前通过 10,000 条中文固定集验证的 FTS 方案只存在于独立技术试验数据库，正式书库不能调用。本阶段已经把同一方案的生产基础层接入正式 SQLCipher 书库：

1. 正式数据库 schema 从 v8 升到 v9；
2. 新增正式检索内容表 `memory_search_document`；
3. 新增外部内容 FTS4 表 `memory_search_document_fts`；
4. 新增 Room DAO 的插入、更新、删除、稳定身份查找和分书检索接口；
5. 新增连续的 8→9 迁移、四个 FTS 自动同步触发器和生产 Room schema 9；
6. API 30 模拟器上完成实际迁移与整个数据库模块回归。

本阶段只完成“正式加密书库具备可靠索引容器”。索引内容尚未接入摘要、事实、实体、时间线和伏笔提交事务；章前上下文也尚未调用多路召回，因此没有把整个 TASK-060 标记完成。

## 2. 数据与隐私边界

正式索引内容表只保存：

- 确定性的 ASCII 中文单字/双字 token；
- 书籍、来源类型和来源行 ID；
- 章节序号、故事顺序、重要度等排序元数据；
- 来源内容 hash 与更新时间。

正式索引表不保存完整小说正文、摘要 JSON、事实原文、提示词或模型输出副本。索引与源数据位于同一个 SQLCipher 加密数据库，不建立未加密的第二份索引文件。

## 3. 查询与迁移设计

- 查询继续使用已经在技术尖峰中证明有效的 FTS rowid 子查询，再回正式内容表取来源指针；没有退回会导致 10,000 条场景出现秒级延迟的普通 JOIN。
- 查询强制按 `book_id` 隔离，并排除目标章节及其后的章节记忆，防止把“未来内容”带入当前章节。
- 结果以重要度、章节、故事顺序和稳定 document ID 排序，为后续多路重排提供确定性候选集。
- 外部内容 FTS4 表使用 Room 期望的四个同步触发器，内容表 INSERT、UPDATE、DELETE 都会同步 FTS。
- `memory_search_document` 对 `book` 使用 RESTRICT 外键；稳定 document ID 和 `(book, source type, source id)` 均有唯一约束。

## 4. DeepSeek 协作记录

- 任务包：`docs/ai/task-packets/TASK-060-PHASE-1A-PRODUCTION-FTS-SCHEMA.md`
- 运行 ID：`20260804-183553-77e987f6`
- 模型：DeepSeek V4 Flash
- 推理强度：`max`
- 沙箱：`workspace-write` / Windows `unelevated`
- 用时：约 7 分 18 秒
- Token：总计 1,031,381；缓存输入 794,112；输出 45,847；推理输出 34,092

DeepSeek 完成了资料审计和实现设计，但它的 `apply_patch` 因 Windows 受限沙箱无法处理分离的多个可写根而失败，实际代码差异为 0。它遵守项目规则，没有改用 `WriteAllText`、重定向或其他绕过手段。Sol 核对其设计后，使用主环境的正规 `apply_patch` 独立落地，并以 Room 生成的真实 schema 9 校正迁移 DDL。

这次协作的有效部分是设计复核；代码作者和最终验证者都是 Sol，不能把它描述成 DeepSeek 已成功写入代码。

## 5. 修改文件

新增：

- `core/database/src/main/kotlin/app/zhijuan/core/database/search/MemorySearchDocumentEntity.kt`
- `core/database/src/main/kotlin/app/zhijuan/core/database/search/MemorySearchDocumentFtsEntity.kt`
- `core/database/src/main/kotlin/app/zhijuan/core/database/search/MemorySearchDao.kt`
- `core/database/schemas/app.zhijuan.core.database.ZhijuanDatabase/9.json`
- `docs/ai/task-packets/TASK-060-PHASE-1A-PRODUCTION-FTS-SCHEMA.md`
- 本报告

修改：

- `core/database/src/main/kotlin/app/zhijuan/core/database/ZhijuanDatabase.kt`
- `core/database/src/main/kotlin/app/zhijuan/core/database/ZhijuanMigrations.kt`
- `core/database/src/androidTest/kotlin/app/zhijuan/core/database/ZhijuanMigrationTest.kt`

没有修改现有技术尖峰数据库、生成流程、App UI、Provider、网络、费用或 TASK-059 WIP。

## 6. 验证证据

### 静态与 JVM

- `:core:database:compileDebugKotlin`：通过；Room 成功生成生产 schema 9。
- `:core:database:compileDebugAndroidTestKotlin`：通过。
- `SearchIndexTextTest`：5/5，通过。
- 本阶段文件 `git diff --check`：通过；仅有工作区既有 LF/CRLF 提示，无格式错误。

### API 30 模拟器

设备：`emulator-5556`，SDK 30，`ro.kernel.qemu=1`；测试时只有该项目模拟器连接，没有向物理设备写入。

- `ZhijuanMigrationTest`：5/5，通过。
  - schema 1–8 到 v9 的连续迁移路径；
  - 旧书、章节、生成审计和叙事记忆保留；
  - SQLCipher v1 到 v9；
  - 不支持的降级失败关闭；
  - v8→v9 正式 FTS INSERT/UPDATE/DELETE 同步、分书隔离和章节上界。
- `core:database` 全量：115/115，通过，0 失败、0 跳过。

### 外部调用

- 织卷 App 内真实 Provider API：0 次。
- 物理设备写入：0 次。
- Git remote/提交/清理：0 次。

## 7. 尚未完成与下一阶段

当前生产 FTS 表是空容器。TASK-060 后续必须继续完成：

1. 定义可验证的来源类型与稳定 document ID；
2. 从人物别名、摘要、硬事实、人物事件、时间线和伏笔生成确定性索引文本；
3. 把索引插入/更新/删除放进对应正式数据提交和失效事务，不能出现源数据成功而索引漂移；
4. 增加旧库首次回填和精确 replay；
5. 接入“计划关键词 + FTS + 最近窗口 + 强制硬事实/未解决伏笔”的多路召回与重排；
6. 在 API 35/API 30 上跑固定中文召回集、全量回归、Release/R8 和统一离线门禁。

下一步直接进入 TASK-060 第 1B 阶段：生产索引文档构造、原子写入与失效边界。
