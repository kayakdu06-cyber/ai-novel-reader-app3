# TASK-062 Phase 1：脱敏生成时序与基准时钟架构复核

## 任务身份

- 任务 ID：`TASK-062 / Phase 1 脱敏生成时序与基准时钟架构复核`
- 仓库根目录：`D:\gptuser\projects\ai-novel-reader-app2`
- 基准分支与 HEAD：`main / 8ce774429da1c3f7139a221bc241c34d81a2efdd`
- 当前未提交改动：仓库存在大量 TASK-059～061 的既有 WIP 与文档；必须全部保留。本任务严格只读，不得修改工作树。
- 执行模型：DeepSeek（纯文本）

## 运行预算

- 推理等级：`max`。
- 最长运行时间：25 分钟。理由：用户已明确允许适当放宽 DeepSeek 思考时长；本任务只裁决一个持久化与时钟架构问题，但必须同时核对隐私、重启、时钟回拨和现有状态证据。
- 累计 Token 上限：无。用户已明确不设置 Token 上限；仍受 25 分钟硬超时、单任务锁和只读沙箱约束。
- 预计读取文件数与明确清单：15 个，见“必读资料”。
- 预计执行命令/测试数：最多 8 个只读搜索/查看命令；不构建、不运行模拟器、不修改文件。
- 提前停止条件：需要访问其他项目或密钥、需要扩大到 total runner/TASK-063、无法在所列文件内证明结论、同一问题反复推理或发现任务前提矛盾。

## 目标

只读复核 TASK-062 的最小可靠架构：明确选择“扩展现有加密滚动诊断”“在 SQLCipher 正式库新增生成时序表”或一个边界清晰的组合方案；定义可跨重启、可抗墙上时钟回拨的基准时钟与事件契约；给出 TEST-093 能验证的报告器不变量和方法级实施清单。不要修改工作树。

## 当前现场与已有 WIP

- 已存在的实现：`generation_job`、`generation_stage`、`request_attempt`、`usage_ledger` 已保存部分墙上时间；`AuditedStreamingProviderExecutor` 有可替换的毫秒时钟；`ChapterDraftV1StreamPayloadDecoder` 能识别已解码正文增量；`core:diagnostics` 有结构化低敏事件、域分离关联哈希和 512 条/512 KiB 加密滚动存储。
- 已存在的测试：通用诊断已证明关联值只落哈希、异常正文不落盘；流式执行器已有 Fake Provider、断流、控制和恢复测试。
- 已知缺口：没有首字节、首个完整段落、正文结束、派生阶段开始/结束和最终正式提交的统一持久时序；现有诊断事件没有生成 phase/event/count/outcome 契约，滚动容量也不足以作为长篇基准账本；墙上时间会被用户调时影响。
- 必须延续、不得从零重写的部分：现有 Generation Job/Stage/Attempt/Usage 状态机、SQLCipher 主库、受保护流式草稿、通用诊断隐私边界和 `GenerationExecutionClock` 的可测试性。

## 必读资料

1. `AGENTS.md`
2. `docs/24-AI-DEVELOPMENT-PROTOCOL.md`
3. `docs/ai/CURRENT-CONTEXT.md`
4. `docs/25-RELIABILITY-AND-GENERATION-PERFORMANCE-ROADMAP.md` 第 5、7、11 节
5. `docs/08-TECHNICAL-ARCHITECTURE.md` 第 12.1 节
6. `docs/15-TEST-PLAN.md` TEST-093～099 与性能门槛段
7. `core/diagnostics/src/main/kotlin/app/zhijuan/core/diagnostics/DiagnosticEvent.kt`
8. `core/diagnostics/src/main/kotlin/app/zhijuan/core/diagnostics/DiagnosticEventCodec.kt`
9. `core/diagnostics/src/main/kotlin/app/zhijuan/core/diagnostics/EncryptedDiagnosticStore.kt`
10. `core/diagnostics/src/test/kotlin/app/zhijuan/core/diagnostics/DiagnosticEventTest.kt`
11. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationEntities.kt`
12. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationRequestAuditRepository.kt`
13. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationDao.kt` 中 Request/Stage 的时间写入和查询部分
14. `feature/generation/src/main/kotlin/app/zhijuan/feature/generation/AuditedStreamingProviderExecutor.kt`
15. `feature/generation/src/main/kotlin/app/zhijuan/feature/generation/ProviderStreamPayloadDecoder.kt`

除上述清单和代码直接引用外，不得递归扫描整套文档、历史会话、备份或无关模块。确需确认 Room 版本注册时，只允许额外查看 `ZhijuanDatabase.kt`、`ZhijuanMigrations.kt` 和 `LibraryDatabaseGuards.kt` 的版本/实体/触发器注册局部。

## 范围

允许修改：

- 无。本任务严格只读。

明确不在范围：

- 任何代码、schema、迁移、文档或测试修改；TASK-063 Fake 延迟夹具；TASK-064 total runner；TASK-065 阅读投影；TASK-066 watchdog；UI；真实 Provider；密钥；物理设备；其他项目。

## 不可破坏的约束

- 项目隔离：不得访问或修改其他项目副本。
- 多模态：无多模态输入。
- 安全与隐私：事件和报告只能包含时间、阶段、有限结果枚举、非负字符/token 计数，以及域分离的连接/模型等指纹；不得包含正文、人物名、提示词、端点、provider request id、secret、自由文本异常消息、原始书/Job/Stage/Attempt ID 或原始内容 hash。
- 状态机与幂等：重复写同一逻辑事件必须精确 replay 或失败关闭；不得因回调重复产生更早/冲突时间；终态后不得倒退。
- 时钟：必须区分可展示的 epoch 时间和持续时间依据；明确 Android 进程重启、设备重启、休眠和用户调整系统时间时如何处理，不允许用可能回拨的单一墙上时钟伪造负耗时。
- 数据库与事务：若建议正式表，必须说明 schema 版本、外键/索引/唯一性、不可变或单向更新规则、迁移以及哪些生命周期写入必须和现有事务原子；不得把网络跨度包进数据库事务。
- 联网与费用：不调用真实 API、不产生费用。
- 兼容性与构建基线：minSdk 29；保留现有诊断存储和 Provider transport 用法，不把 TASK-062 变成通用日志重写。
- 需要保留的用户/未提交改动：全部现有 WIP。

## 实施要求

1. 给出唯一主方案，并具体解释为什么淘汰另外两个方案。
2. 列出事件最小集合，至少覆盖：用户开始/上一章提交、Stage queued、local context ready、Provider open、first byte、first full paragraph、body stream end、MEMORY/TRACKING/CONSISTENCY/REVISION start/end、formal commit、next chapter start。
3. 定义事件字段、允许为空的关联层级、枚举结果、字符/token 计数、连接/模型指纹和严格禁止字段；说明首个完整段落如何在不保存段落正文的前提下判断。
4. 定义基准时钟接口和持久证据：epoch、monotonic、boot/session identity 的最小组合；说明同一 boot 与跨 boot 哪些耗时可计算、哪些必须明确标为 unavailable，不能猜值。
5. 定义幂等、顺序、重复回调、迟到回调、进程重启、设备重启、墙上时钟回拨、续写 Attempt 和修订慢路径的处理。
6. 定义报告器如何从事件算出 queue/provider/first-paragraph/body/derived/commit/total durations，以及数据不完整时的失败关闭行为。
7. 按模块和方法给出最小实施顺序与 TEST-093 测试矩阵；明确 TASK-062 到哪里结束、哪些接线留给 TASK-064。

## 验收标准

- [ ] 只有一个主方案，能够支撑长篇持久测量而不滥用 512 条滚动诊断。
- [ ] 时钟方案不会因墙上时间回拨产生负耗时或虚假达标。
- [ ] 事件可精确重放、顺序受约束，缺事件时报告明确不可用。
- [ ] TEST-093 覆盖正文、人物、提示词、端点、密钥和原始 ID/hash 的 canary 0 命中。
- [ ] 给出逐文件/逐方法建议和迁移/兼容影响，但不修改文件。
- [ ] 不提前实现或宣布 TASK-063～066、total runner 或真实模型测量完成。

## 验证命令

本任务只读，不运行 Gradle 或模拟器。可运行：

```powershell
git status --short
git diff --check
```

未运行的验证必须写明原因，不能写成通过。

## 回交格式

请严格按以下标题返回：

1. `完成内容`
2. `修改文件`
3. `验证`
4. `未完成/风险`
5. `需要 Sol 处理`
6. `假设`

在“完成内容”开头给出唯一主方案，再给事件表、时钟与跨重启规则、报告公式、方法级实施清单和 TEST-093 矩阵。不要宣布整个 TASK 完成，也不要更新正式完成状态；由 Sol 根据后续差异和测试证据确认。
