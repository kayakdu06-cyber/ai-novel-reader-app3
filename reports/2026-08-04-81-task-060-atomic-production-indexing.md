# 工作汇报 81：TASK-060 正式记忆索引与原子接线

> 日期：2026-08-04  
> 项目：织卷 Android App  
> 唯一项目根目录：`D:\gptuser\projects\ai-novel-reader-app2`  
> 阶段：TASK-060 第 1B 阶段完成；整个 TASK-060 尚未完成

## 1. 本阶段结果

正式加密书库现在不再只有一个空的 FTS 容器。人物、章节摘要、人物事件、事实、时间线和伏笔会被转换成确定性的检索文档，并与权威数据在同一个 Room/SQLCipher 事务中写入、更新或删除。

已完成：

1. 六类正式记忆源的确定性索引文档构造器；
2. 稳定 document ID、source content hash、重要度、章节/故事顺序和来源身份；
3. 只保存 ASCII 中文单字/双字 token，不在索引表复制源中文、完整 JSON、提示词或模型输出；
4. JSON 严格解析、稳定键排序以及输入深度、叶子数、文本量、token 数和总 payload 上限；
5. 正式索引的稳定替换、来源冲突拒绝、批量删除和 FTS 同步；
6. 初始世界设定、章节记忆、故事追踪、最终候选发布和旧版兼容发布五条事务路径接线；
7. 章节重生成前捕获旧来源身份，旧记忆失效后删除旧索引，再写入新索引；
8. 精确 replay 只修复能够证明安全的索引，不用历史快照覆盖后来已变化的伏笔状态；
9. 安卓 11 上验证晚期发布失败会把正文、记忆、索引、Stage 和 Job 一起回滚。

本阶段没有接入生成上下文的多路召回，也没有完成旧数据库首次回填，因此不能把整个 TASK-060 标记完成。

## 2. 关键兼容性修复

原查询表达式使用显式大写 `AND` 连接中文双字 token。API 35 的 FTS4 能识别，但 API 30 的系统 SQLite 没有启用可选增强解析器，会把 `AND` 当成普通检索词，导致每个单独 token 都能命中、完整中文短语却返回空结果。

现改为 FTS3/4 全版本支持的空格隐式 AND。该问题由真实 API 30 测试发现并复现，修复后同一正式写入/替换/删除测试从失败变为 6/6 通过。没有降低检索约束：查询仍要求全部相邻双字 token 同时出现。

## 3. 原子写入与 replay 规则

- 初始世界设定：首次提交人物和硬事实后同步建索引；当前 replay 只验证 Bible revision，不能证明人物/事实 payload 与历史提交完全相同，因此不在 replay 中盲目重建。
- 章节记忆：首次提交同步索引摘要、事件和事实；精确 replay 已逐行验证全部不可变来源，可安全补回缺失索引。
- 故事追踪：首次提交同步时间线和数据库重新读取后的伏笔实际状态；精确 replay 只修复不可变时间线，不重放历史伏笔快照。
- 最终候选发布：旧章节身份在失效前捕获；旧索引删除、新正文及派生记忆、新索引、Stage/Job 状态均属于同一事务。精确 replay 只有在当前来源仍与冻结结果一致时才修复索引。
- 旧版兼容提交：继续维护同样的失效删除和新索引写入规则，防止未来误用绕过检索一致性；其 replay 缺少完整记忆验证，因此不做不安全修复。

## 4. DeepSeek 协作记录

本阶段使用两次只读补丁建议任务，均为 DeepSeek V4 Flash、`max` 推理、无总 Token 上限。两次实际代码差异均为 0，正式代码由 Sol 审查后使用 `apply_patch` 落地。

### 4.1 索引文档构造器审计

- 任务包：`docs/ai/task-packets/TASK-060-PHASE-1B1-SEARCH-DOCUMENT-FACTORY.md`
- 运行 ID：`20260804-185902-35f99a8f`
- 用时：约 6 分 42 秒
- Token：总计 303,543；缓存输入 178,176；输出 60,583；推理输出 48,893
- Sol 修正：DeepSeek 的隐私负例会被固定英文错误消息中的单字符误伤；提案缺少 JSON 深度限制；Kotlin 测试函数类型不兼容；严格 JSON 还需拒绝非标准裸标识符。上述问题均已修正并由测试覆盖。

### 4.2 原子事务接线审计

- 任务包：`docs/ai/task-packets/TASK-060-PHASE-1B2-ATOMIC-INDEX-WIRING.md`
- 运行 ID：`20260804-194055-c1731648`
- 用时：约 6 分 53 秒
- Token：总计 1,744,492；缓存输入 1,464,448；输出 38,901；推理输出 21,611
- Sol 审查：采纳事务顺序、不可变 replay 修复和可变伏笔隔离方案；没有把“列出的五条路径必然是所有入口”当成未经复核的事实，另行通过生产源代码搜索确认当前正式插入入口。

## 5. 修改文件

新增：

- `core/database/src/main/kotlin/app/zhijuan/core/database/search/MemorySearchDocumentFactory.kt`
- `core/database/src/main/kotlin/app/zhijuan/core/database/search/MemorySearchIndexWriter.kt`
- `core/database/src/test/kotlin/app/zhijuan/core/database/search/MemorySearchDocumentFactoryTest.kt`
- `docs/ai/task-packets/TASK-060-PHASE-1B1-SEARCH-DOCUMENT-FACTORY.md`
- `docs/ai/task-packets/TASK-060-PHASE-1B2-ATOMIC-INDEX-WIRING.md`
- 本报告

修改：

- `core/database/src/main/kotlin/app/zhijuan/core/database/search/MemorySearchDao.kt`
- `core/database/src/main/kotlin/app/zhijuan/core/database/search/SearchIndexText.kt`
- `core/database/src/main/kotlin/app/zhijuan/core/database/memory/MemoryDao.kt`
- `core/database/src/main/kotlin/app/zhijuan/core/database/generation/InitialPlanningCommitRepository.kt`
- `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterMemoryExtractionCommitRepository.kt`
- `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterTrackingProjectionCommitRepository.kt`
- `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterFinalCandidateCommitRepository.kt`
- `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterGenerationCommitRepository.kt`
- `core/database/src/test/kotlin/app/zhijuan/core/database/search/SearchIndexTextTest.kt`
- `core/database/src/androidTest/kotlin/app/zhijuan/core/database/MemoryDatabaseTest.kt`
- `core/database/src/androidTest/kotlin/app/zhijuan/core/database/ChapterFinalCandidateCommitDatabaseTest.kt`
- `feature/generation/src/androidTest/kotlin/app/zhijuan/feature/generation/InitialPlanningEndToEndTest.kt`
- `feature/generation/src/androidTest/kotlin/app/zhijuan/feature/generation/ChapterMemoryExtractionEndToEndTest.kt`
- `feature/generation/src/androidTest/kotlin/app/zhijuan/feature/generation/ChapterTrackingProjectionEndToEndTest.kt`

## 6. 验证证据

### 静态与 JVM

- `:core:database:compileDebugKotlin`：通过。
- core/database 与 feature/generation Android 测试代码编译：通过。
- 正式检索构造器与 token JVM 测试：18/18，通过。
- `git diff --check`：通过；仅保留工作区既有 LF/CRLF 提示，无空白错误。

### API 30 模拟器

设备：`emulator-5556`，Android 11 / SDK 30；只使用项目模拟器，没有向物理设备写入。

- 正式 writer/FTS 替换测试：6/6，通过。
- 最终候选发布、精确 replay、并发和晚期回滚：26/26，通过。
- 初始规划、章节记忆、故事追踪端到端：12/12，通过。
- 旧版兼容生成数据库入口：52/52，通过。
- core/database 全量：117/117，通过，0 失败、0 跳过。

### 外部调用

- 织卷 App 内真实 Provider API：0 次。
- 物理设备写入：0 次。
- Git remote、提交、清理或回退：0 次。

## 7. 下一阶段

自动进入 TASK-060 第 1C/2 阶段：

1. 为 v9 以前已有书籍建立可中断、可重放、按书隔离的首次索引回填；
2. 接入“计划关键词 + FTS + 最近章节窗口 + 强制硬事实 + 未解决伏笔”的多路候选召回；
3. 建立确定性去重、排序、预算裁剪和章节上界；
4. 用固定中文召回集验证正确率，并继续覆盖 API 30/API 35；
5. 最后再运行 Release/R8 与统一离线门禁。
