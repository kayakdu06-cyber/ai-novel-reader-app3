# TASK-060 / Phase 2A：确定性召回探针编译器

## 任务身份

- 任务：`TASK-060 / Phase 2A / Deterministic recall-probe compiler`
- 仓库：`D:\gptuser\projects\ai-novel-reader-app2`
- 基线：工作汇报 84 之后的当前 dirty WIP；必须保留全部既有改动，不得从零重写。
- 模型：DeepSeek V4 Flash，纯文本编码模型。

## 运行预算

- 推理强度：`max`
- 最长运行：25 分钟
- 总 Token 上限：无
- 可执行目标 JVM 测试；不得运行 Android 模拟器、网络调用或织卷 App 内真实 Provider API。

## 目标

只实现多路召回的第一块纯函数基础：把当前目标章、目标弧和可选用户补充中的可读字符串确定性编译为有界、安全、可审计的 SQLite FTS4 MATCH 探针。数据库查询、权威行 hydration、上下文接线和固定召回集属于后续 Phase 2B/2C，本任务不得提前扩张。

## 开始前必读

1. 根目录 `AGENTS.md`
2. `docs/24-AI-DEVELOPMENT-PROTOCOL.md`
3. `docs/ai/CURRENT-CONTEXT.md`
4. `docs/adr/ADR-004-chinese-search.md`
5. `reports/2026-08-05-84-task-060-legacy-index-backfill.md`
6. `core/database/src/main/kotlin/app/zhijuan/core/database/search/SearchIndexText.kt`
7. `core/database/src/main/kotlin/app/zhijuan/core/database/search/MemorySearchDao.kt`
8. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterContextAssemblyRepository.kt`
9. `core/database/src/test/kotlin/app/zhijuan/core/database/search/SearchIndexTextTest.kt`

## 固定 API 与语义

在 `app.zhijuan.core.database.search` 新增内部 v1 类型，名称可小幅调整但语义不得变化：

```kotlin
internal enum class MemoryRecallProbeRouteV1 {
    TARGET_CHAPTER,
    USER_ADDITION,
    TARGET_ARC,
}

internal data class MemoryRecallProbeV1(
    val route: MemoryRecallProbeRouteV1,
    val routeOrdinal: Int,
    val matchExpression: String,
)

internal object MemoryRecallProbeCompilerV1 {
    fun compile(
        targetChapterTitle: String,
        targetChapterPlanJson: String,
        targetArcTitle: String,
        targetArcPlanJson: String,
        userAddition: String?,
    ): List<MemoryRecallProbeV1>
}
```

必须满足：

1. 路由固定优先级：目标章 → 用户补充 → 目标弧；同路由按输入出现顺序稳定。
2. 标题先于对应 JSON。JSON 必须严格解析；只提取字符串值，不把字段名、数字、布尔或 null 当作关键词。对象键按字典序遍历，数组保持原顺序。
3. 使用现有 `SearchIndexText.matchExpression` 产生兼容 API 30/35 的安全 ASCII token；再拆为单 token MATCH 探针，让多个命中可在后续阶段累计，而不是把整句全部用隐式 AND 锁死。
4. 输出必须去重：相同 `matchExpression` 只保留最高优先、最早出现的一项。
5. 固定上限：每个 JSON 最大 64 KiB、递归深度最大 32、字符串叶子总数最大 256、每个字符串最大 4 KiB、最终唯一探针最多 128、单探针最多 128 字符。超过上限或 JSON 畸形必须失败关闭，错误消息不得回显输入内容。
6. 空白、纯标点、数字/布尔/null 和无法形成 token 的内容不得产生探针；最终空列表是合法结果。
7. `routeOrdinal` 是该路由最终保留探针的零起点连续序号，不受被丢弃空值或跨路由去重项影响。
8. 数据类 `toString()` 不得显示 MATCH 表达式或源文本。
9. 不引入 ICU、分词库、网络依赖、向量库、明文文件或第二数据库。

## 允许修改范围

仅允许：

- 新增 `core/database/src/main/kotlin/app/zhijuan/core/database/search/MemoryRecallProbeCompiler.kt`
- 新增 `core/database/src/test/kotlin/app/zhijuan/core/database/search/MemoryRecallProbeCompilerTest.kt`

不得修改其他生产代码、schema、DAO、上下文策略、文档或报告。

## 最低测试矩阵

1. 中文人物别名/地点/物品短语产生单字或相邻双字 ASCII token，且不含原中文。
2. 目标章、用户补充、目标弧优先顺序固定；对象换键序得到同结果。
3. 同 token 跨路由去重保留高优先路由，routeOrdinal 连续。
4. 长句被拆为多个单 token 探针，不形成“整句全 AND”误召回门槛。
5. 空/标点/数值 JSON 可得到空列表。
6. 畸形 JSON、超长 JSON、超深嵌套、257 叶子、4 KiB+1 字符串和超长 token 均失败关闭且不泄露 canary。
7. 128 唯一探针上限行为必须明确测试：超过时失败关闭，不静默截断。
8. `toString()` 脱敏。

## 验收命令

```powershell
.\gradlew.bat :core:database:testDebugUnitTest --offline --tests app.zhijuan.core.database.search.MemoryRecallProbeCompilerTest
git diff --check
```

## 交付要求

- 直接在允许范围内实现并测试；不得只返回思路。
- 最终说明实际修改文件、测试结果、剩余风险和对 Phase 2B 的接口建议。
- 不得宣称 TASK-060 或多路召回已经完成。
