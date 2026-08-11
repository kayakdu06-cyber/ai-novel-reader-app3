# TASK-060 / Phase 2B1：有界 FTS 命中累计与排名

## 任务身份

- 仓库：`D:\gptuser\projects\ai-novel-reader-app2`
- 基线：工作汇报 85 之后的当前 dirty WIP，保留全部既有改动。
- 执行者：Sol；本阶段不调用 DeepSeek。

## 目标

在不接入权威行 hydration 和上下文候选的前提下，建立可单独验收的数据库召回层：有界选择 Phase 2A 探针、逐探针查询同一 SQLCipher/Room FTS、按文档身份累计三条路线命中，并输出确定性排名和脱敏证据。

## 必须同时修正的 Phase 2A 可用性问题

正常结构化章计划和弧计划可能产生超过 128 个唯一 token。超过检索预算属于“可选召回被裁剪”，不应让章节上下文整体失败。编译器需要提供编译结果对象，返回保留探针和被有界省略的唯一探针数量；仍必须限制内存、保留高优先/早出现项并可审计。畸形 JSON、大小、深度、叶子和单 token 上限继续失败关闭。

## 召回边界

- 每条路由最多执行固定数量探针：目标章 32、用户补充 16、目标弧 16；总查询最多 64。
- 每探针最多取 16 个文档；累计后最多返回 128 个文档。
- 查询必须复用 `MemorySearchDao.searchBeforeChapter`，只接受目标书且 `chapter_index IS NULL OR chapter_index < targetChapterIndex` 的结果。
- 同一 `document_id` 只保留一项；同一 `(book_id, source_type, source_id)` 不得对应多个文档。
- 排名依次比较：目标章命中数降序、用户补充命中数降序、目标弧命中数降序、总命中数降序、importance 降序、chapterIndex 降序、storyOrder 降序、documentId 升序。
- 空探针合法返回空命中；不存在的书失败关闭。
- 结果/异常/toString 不得输出源文本、searchTerms、MATCH token 或计划文本。
- 生成稳定 SHA-256 query fingerprint，覆盖实际执行探针的 route、routeOrdinal、matchExpression 和固定执行策略版本；只对外暴露 hash。

## 允许范围

- 调整 `MemoryRecallProbeCompiler.kt` 及其测试，加入有界省略证据。
- 新增 `MemorySearchRecallRepository.kt`。
- 在 core/database Android 测试中新增或扩展有界召回测试。
- 不修改 schema、迁移、权威记忆查询、上下文接线或预算策略。

## 最低测试

- 129+ 唯一 token 不再失败，保留 128 并报告准确遗漏数；畸形/大小等结构上限仍失败。
- 三路线固定探针配额和总查询上限。
- 同文档跨多个探针累计而不重复。
- 排名比较器所有关键级别有证据。
- 未来章节和其他书文档不能进入结果。
- 返回文档上限 128、每探针上限 16。
- query fingerprint 稳定且输入/策略变化会变化。
- 结果 `toString()` 脱敏。

## 验收

- 相关 JVM 测试通过。
- core/database 相关 Android 专项在 API 30 与 API 35 通过。
- `git diff --check` 和安全扫描通过。
- 不宣称 Phase 2B 或 TASK-060 完成。
