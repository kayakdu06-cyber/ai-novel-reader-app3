# TASK-061 Phase 2B3A：aggregate 重建 writer 只读设计审计

## 任务身份

- 任务 ID：`TASK-061 / Phase 2B3A aggregate 重建 writer 只读设计审计`
- 仓库根目录：`D:\gptuser\projects\ai-novel-reader-app2`
- 基准分支与 HEAD：`main` / `8ce7744`；HEAD 仅用于识别独立副本，不得回退工作树
- 当前未提交改动：存在大量 TASK-059～061、DeepSeek 隔离、测试、文档和报告 WIP，全部必须保留
- 执行模型：DeepSeek（纯文本）

## 运行预算

- 推理等级：`max`（用户持续要求最高推理强度）
- 最长运行时间：20 分钟
- 累计 Token 上限：不设置（用户已明确允许；任务仍受 20 分钟硬上限约束）
- 提高时限理由：这是已有 schema、历史不可变触发器、上下文消费者、重建计划和未来执行器之间的跨文件契约审计；此前 60 秒外层超时曾截断有效审计，因此给出一次有界 20 分钟窗口
- 预计读取文件数：不超过 18 个，限于下方明确清单及这些文件直接引用的类型定义
- 预计执行命令/测试数：只读搜索与阅读；不构建、不测试、不写文件
- 提前停止条件：需要修改 schema、任务边界必须扩大到 Provider/总 runner、发现未声明高风险、权限阻塞或同一读取失败重复两次

## 目标

审计当前 `aggregate_state_projection` 的实际字段、保护规则、唯一消费者和 TASK-061 计划依赖，给出一个最小、确定、可版本化、可精确 replay、可在编辑后逐章重建的生产 writer 方案。重点回答“state JSON 应保存什么权威信息、如何证明只累计到指定 current 章节、如何原子替换同槽 VALID 头”，并列出下一步 Sol 可以直接实现的文件级改动和测试矩阵。

本次只读，不允许修改任何代码或文档，也不要宣布 Phase 2B3/TASK-061 完成。

## 当前现场与已有 WIP

- schema v11 已允许同一 `(book_id, through_chapter_index)` 保留多代 STALE aggregate，数据库触发器保证同槽最多一个 VALID，历史只允许 `VALID → STALE` 且禁止 DELETE。
- schema v13 和 `ForeshadowProjectionRewindRepository` 已完成受审计伏笔回退及区间 revision→transition 失效。
- `ChapterEditRebuildPlanRepository` 目前为每个受影响章生成 `REBUILD_AGGREGATE_STATE`，但统一标记 `AGGREGATE_REBUILD_UNSUPPORTED`；上一章 aggregate 和本章 tracking 是依赖。
- `ChapterContextAssemblyRepository` 只消费目标章之前、来源版本仍为 current 的最新 VALID aggregate，并把 `stateJson` 作为上下文候选。
- 当前没有生产 `insertAggregateState` 调用者，只有数据库测试夹具；`state_json` 没有冻结的正式 schema/codec/writer。
- 当前 App 没有总 phase runner；跨章有序 Job/Stage 执行、费用/用户选择和 TEST-033 仍未完成。
- 必须继续已有 WIP，不能从零重写计划仓库、memory/tracking/context 或状态机。

## 必读资料

1. `AGENTS.md`
2. `docs/24-AI-DEVELOPMENT-PROTOCOL.md`
3. `docs/ai/CURRENT-CONTEXT.md`
4. `docs/06-AI-GENERATION-SYSTEM.md` 第 10、27 节
5. `docs/09-DATA-MODEL.md` 第 22～24 节
6. `docs/10-STATE-MACHINES.md` 第 22 节
7. `docs/15-TEST-PLAN.md` TEST-032/033 与第 25～29 节
8. `docs/19-IMPLEMENTATION-BACKLOG.md` TASK-061 行
9. `core/database/src/main/kotlin/app/zhijuan/core/database/library/ChapterEditRebuildPlanRepository.kt`
10. `core/database/src/main/kotlin/app/zhijuan/core/database/library/ForeshadowProjectionRewindRepository.kt`
11. `core/database/src/main/kotlin/app/zhijuan/core/database/memory/DerivedAuditEntities.kt`
12. `core/database/src/main/kotlin/app/zhijuan/core/database/memory/MemoryEntities.kt`
13. `core/database/src/main/kotlin/app/zhijuan/core/database/memory/MemoryDao.kt`
14. `core/database/src/main/kotlin/app/zhijuan/core/database/LibraryDatabaseGuards.kt`
15. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterContextAssemblyRepository.kt`
16. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterTrackingProjectionCommitRepository.kt`
17. `core/database/src/androidTest/kotlin/app/zhijuan/core/database/ChapterEditRebuildPlanDatabaseTest.kt`
18. `core/database/src/androidTest/kotlin/app/zhijuan/core/database/MemoryDatabaseTest.kt`

除上述清单和代码直接引用外，不得递归扫描整套仓库、历史会话、日志、备份或其他项目。

## 范围

允许：

- 只读检查上述文件和直接引用的 enum/entity/DAO 类型。
- 给出建议的 aggregate JSON v1 字段、排序、上限、来源快照/hash、ID 策略、事务顺序、replay/CAS/并发/legacy 语义。
- 判断当前 schema v13 是否足够；若不够，只说明最小迁移需求和理由，不实施。
- 给出 Phase 2B3A 的最小文件级实施清单和 API 30/API 35 Room 测试矩阵。

明确不在范围：

- 修改文件、运行写入探针、构建或测试。
- 实现跨章总 runner、调用 Provider、创建真实费用、改 UI、改模板系统。
- 读取或输出 API Key、小说正文、artifact 明文或个人数据。
- 更新正式任务状态或工作报告。

## 不可破坏的约束

- 只能访问 app2 独立副本，绝不访问 `D:\gptuser\projects\ai-novel-reader`。
- 后续正式章节正文必须保留；aggregate 只能作为派生历史。
- 旧 aggregate 不删除、不覆盖、不从 STALE 恢复；同一业务槽最多一个 VALID。
- 所有来源必须绑定 book、through chapter current version、chapter index 和明确内容 hash；不能只相信调用方传入 JSON。
- writer 必须在单个 Room 事务内重验来源、处理同槽旧 VALID、插入新代并支持精确 replay；并发只能恰好一个结果成功或同证据 replay。
- `state_json` 不能保存章节正文、API Key、连接信息、自由模型解释或未受限大文本；默认错误和 `toString()` 必须脱敏。
- aggregate 是本地确定性派生，不得伪造 Provider 输出、Attempt 或 Usage。
- 不得把 aggregate writer 完成误报为跨章有序重建或总 runner 完成。

## 必答问题

1. aggregate 的真实产品用途是什么？在唯一消费者 `ChapterContextAssemblyRepository` 中，最小但有用的 `state_json` 应由哪些权威表/字段组成，哪些内容必须排除？
2. 如何保证第 N 章 aggregate 只反映 current 版本且故事序不晚于 N 的摘要、事件、事实、时间线、伏笔状态，而不会混入未来章、STALE、旧版本或跨书数据？
3. 前一章 aggregate 应作为真实数据输入累积，还是只作为顺序/来源栅栏？请比较重放确定性、数据膨胀、损坏传播与 legacy 情形。
4. 现有 `AggregateStateProjectionEntity` 字段是否足够实现可信 writer？若足够，给出每字段语义；若不足，指出必须新增而非“最好新增”的字段。
5. writer 的命令、结果、事务步骤、稳定 ID/hash、精确 replay、同槽竞争、时间单调、异常回滚应该怎样定义？
6. `ChapterEditRebuildPlanRepository` 在 writer 完成后应如何解除 `AGGREGATE_REBUILD_UNSUPPORTED`，同时避免尚未实现的 tracking 顺序执行被虚假标为 READY？
7. 给出最小正向、篡改、旧版本、跨书、未来数据、同槽历史、并发、replay、legacy 和诊断脱敏测试矩阵。

## 验收标准

- [ ] 明确区分“权威输入”“规范持久结果”“仅用于排序的依赖”。
- [ ] 方案可在现有历史不可变和单 VALID 头规则下实现。
- [ ] 所有集合有稳定排序和硬上限，hash 覆盖边界明确。
- [ ] replay、并发、legacy 和失败回滚语义具体可测。
- [ ] 不需要真实 Provider，不创建 Attempt/Usage，不写正文。
- [ ] 结论按 P0/P1/P2 风险排序，并给出 Sol 的推荐取舍，不只罗列可能性。

## 验证命令

本次禁止运行构建和测试。只允许只读命令，例如：

```powershell
git status --short
rg -n "aggregate_state_projection|AggregateStateProjection" <明确文件>
Get-Content -Encoding UTF8 <明确文件>
```

## 回交格式

请严格按以下标题返回：

1. `当前事实`
2. `P0/P1/P2 风险`
3. `推荐 aggregate v1 契约`
4. `推荐 writer 事务与 replay`
5. `计划仓库的最小改动`
6. `测试矩阵`
7. `Sol 实施清单`
8. `未决问题与假设`

不要输出思考过程，不要宣布整个 TASK 完成，不要更新任何文件。
