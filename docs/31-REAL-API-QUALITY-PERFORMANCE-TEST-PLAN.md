# 织卷 DeepSeek V4 Flash 真实 API 质量与性能测试计划

> 状态：执行前基线
> 日期：2026-08-11
> 首个真实测试模型：DeepSeek V4 Flash
> 本文不授权把密钥写入源码、文档、日志或 Git

## 1. 测试目的

离线测试能证明状态机、schema、预算和恢复逻辑，不能证明真实模型会：

- 按 schema 返回；
- 快速给出第一段；
- 写出可阅读的中文正文；
- 维持人物、关系、道具和特殊机制；
- 不重复剧情；
- 在连续章节中保留关键后果。

真实 API 测试必须在可亲手验证版本之前进行，而不是等 1.0 才进行。

## 2. 前置条件

全部满足后才允许 App 发出真实小说请求：

- TASK-129 的 3–5 章 Fake 闭环通过。
- TASK-130/131 的启动、书架和阅读入口完成。
- 当前 Git 根目录是 app3。
- 真实 API Key 只存在于用户配置或隔离 secret 环境。
- 目的地 canonical origin 和协议已由用户确认。
- 单次、每日、单书预算已设置。
- 请求时序和脱敏诊断已启用。
- secret 扫描在测试前通过。
- Provider 拒绝和限流路径可见。
- 测试数据中所有相关角色均明确为虚构成年人。

任何一项不满足，先修本地产品，不用真实 API 掩盖问题。

## 3. 密钥和数据规则

- 不读取、显示、复制或重复 API Key。
- 不在命令行参数中直接传明文 Key。
- 不在 Markdown、截图、异常 message 和 shell transcript 中保存 Key。
- 不记录完整 Prompt、正文和响应。
- 诊断只记录脱敏 ID、阶段、字节/token 计数、时间、标准错误和响应终态。
- 测试结束后运行源码、日志、构建产物和 APK secret 扫描。
- 真实小说正文仅保存在 App 加密存储和明确的脱敏评分产物中。

## 4. 测试环境记录

每次运行保存：

- app commit；
- APK SHA-256；
- Android 设备型号、系统版本和网络类型；
- Provider protocol；
- canonical origin 的不可逆脱敏指纹；
- model ID；
- temperature、topP、maxOutputTokens；
- PromptBundle、WritingPolicyPack、schema 和 capability manifest 版本；
- 目标章节字数；
- 开始时间、结束时间和中断事件；
- 请求次数、输入/输出 token 和价格来源；
- 是否流式；
- 结果：通过、拒绝、限流、协议失败、超时或用户停止。

## 5. 分级执行

### Gate R0：连接和模型能力

请求：

- 模型列表或零生成能力发现；
- 必要时用户主动触发一次最小 16-token 通用探针。

通过：

- model ID 可用；
- streaming 和 structured output 能力证据明确；
- 不发送小说正文；
- 无 Key 泄漏。

### Gate R1：结构化合同

请求：

- 小型、虚构、安全的 chapter-plan.v2 样例；
- 小型 chapter-post-analysis.v1 样例。

每类最多：

- 首次；
- 一次有界格式修复。

通过：

- 严格 JSON/schema 成功；
- 未知字段、重复键和错误版本为 0；
- 业务 validator 通过；
- 格式修复率可记录但不能长期依赖。

失败：

- 连续两次同类 schema 失败；
- 模型不支持所需输出；
- 需要关闭严格校验才能通过。

### Gate R2：最小流式正文

请求：

- 单章 800–1,500 中文字；
- 只验证增量 UTF-8、首段、终态、usage 和取消。

通过：

- 第一可读段 P95 ≤25 秒；
- 无乱码、重复 chunk 或丢尾；
- usage 和终态可结算；
- 取消后不提交正式 ChapterVersion。

### Gate R3：完整单章

请求：

- 3,000–5,000 中文字；
- chapter plan、正文、合并分析、校验和提交；
- 正常路径不触发修订。

通过：

- 正文 P95 ≤300 秒；
- 正式提交 P95 ≤360 秒；
- 正常章主要远程调用不超过规划值；
- 正文、状态和 Usage 原子一致；
- 本章有明确推进和后果；
- 无严重重复或状态矛盾。

### Gate R4：组合能力单章

样本：

- 修仙；
- 恋爱；
- progression-system；
- item-progression；
- 可选 intimacy-continuity；
- 本章只激活其中实际相关的子集。

通过：

- 未激活能力不出现在 Prompt manifest；
- 境界、系统、关系和道具分别按自身规则变化；
- 一项能力不覆盖其他能力事实；
- Prompt 大小和首段速度仍在上限内。

### Gate R5：连续 3–5 章

请求：

- 同一本书连续 3–5 章；
- 阅读第一章时后台继续；
- 中途至少一次暂停和 App 重启；
- 可选一次短暂断网。

通过：

- 已完成章节始终可读；
- 后续章节连续；
- 关键义务不丢；
- 核心剧情不重复；
- 无无证据状态回退；
- 恢复不重复收费事实；
- 10 分钟故障为 0。

## 6. 质量评分

每章由自动检查和人工评分共同决定。

### 6.1 自动指标

| 指标 | 计算 |
|---|---|
| obligationResolution | 到期义务有完成/推进/延期/有理由取消的比例 |
| evidenceCoverage | 权威状态变化具有正文证据的比例 |
| repeatedEventRate | 与最近章节核心事件高度重复且无新后果的比例 |
| stateViolationCount | 人物、关系、道具、系统、修炼和时间线非法转移数 |
| planCoverage | 计划场景和关键转折在正文出现的比例 |
| callbackIntegrity | 要求回收的伏笔/承诺是否处理 |
| promptSelectivity | 未激活策略片段进入 Prompt 的数量 |
| remoteCallsPerChapter | 每章实际远程请求次数 |

### 6.2 人工 1–5 分

- 可读性；
- 情节推进；
- 人物可信；
- 情绪和关系后果；
- 场景连贯；
- 钩子；
- 中文自然度；
- 与用户设想一致；
- 重复感；
- 继续阅读意愿。

P0 通过建议：

- 可读性、推进、人物可信均不低于 3；
- 任何 stateViolationCount P0 错误为 0；
- 连续样本没有整段换词复述；
- 继续阅读意愿平均不低于 3；
- 严重一致性错误为 0。

## 7. 速度指标

### 7.1 时间点

- T0：用户点击开始；
- T1：Job/Stage 持久化；
- T2：Provider open；
- T3：首个响应字节；
- T4：首个可读段落盘；
- T5：正文流结束；
- T6：章后分析结束；
- T7：正式 ChapterVersion 提交；
- T8：UI 可见正式完成。

### 7.2 阈值

| 指标 | 目标 | 发布阻断 |
|---|---:|---:|
| T2-T0 P95 | ≤2 秒 | >3 秒 |
| T4-T0 P50 | ≤10 秒 | >10 秒 |
| T4-T0 P95 | ≤20 秒 | >25 秒 |
| T5-T0 P95 | ≤180 秒 | >300 秒 |
| T7-T0 P95 | ≤240 秒 | >360 秒 |
| 任一正常章 | 无 10 分钟 | ≥10 分钟 |

“测试命令可无限等待直到返回数据”表示测试人员可以保留长尾证据，不表示 App 可以一直显示无反馈。5 分钟进入慢服务状态；10 分钟进入故障并阻断发布。

## 8. 费用指标

记录：

- plan 输入/输出 token；
- draft 输入/输出 token；
- post-analysis 输入/输出 token；
- revision 输入/输出 token；
- 每章总 token；
- 估算和实际费用差异；
- 被拒绝、取消、UNKNOWN 和重试的占用。

目标：

- 正常章不为每个启用能力增加独立请求；
- 正常章不触发 revision；
- Prompt token 随章节增长保持有界，不随全书字数线性增长；
- 3–5 章总费用必须在用户确认预算内；
- 价格未知时只展示 token，不伪造货币金额。

## 9. A/B 设计

比较对象：

- A：现有 PromptBundle v1；
- B：WritingPolicyPack 编译后的最小策略集合。

固定：

- 同一故事输入；
- 同一模型和参数；
- 同一章节任务和上下文；
- 同一目标字数；
- 每组至少 3 个非同质样本。

比较：

- 首段和完成时间；
- 输入/输出 token；
- schema 成功率；
- 质量评分；
- obligation/state/repetition 自动指标；
- 修订率；
- Provider 拒绝率。

采用 B 的条件：

- P0 质量不下降；
- 未激活能力零占用；
- 输入 token 或延迟有实质改善，或组合能力可靠性显著提高；
- 没有新增状态污染。

## 10. 失败归因

| 现象 | 首查 |
|---|---|
| 没有首段 | DNS/TLS、Provider open、模型排队、Prompt 大小、streaming |
| 有思考无正文 | 适配器事件映射、模型输出模式、max token、超时 |
| JSON 反复失败 | schema 复杂度、response format、示例、模型能力 |
| 章节慢 | Prompt token、计划调用、分析调用、续接、模型服务 |
| 重复剧情 | 义务/计划、召回、prohibitedRepetitions、上一章承接 |
| 系统/道具乱跳 | capability activation、state transition validator、证据映射 |
| 关系事件无后果 | obligation 和 relationship delta 是否进入下一章上下文 |
| 费用异常 | 重试、UNKNOWN、reservation、调用数和续接 |
| App 重启后重复请求 | lease、Attempt send evidence、recovery decision |

必须先用时序和权威状态定位，不靠主观猜测修改 Prompt。

## 11. 报告格式

每个 Gate 生成一个 Markdown 报告：

- commit 和 APK hash；
- 环境；
- 样本 ID；
- 脱敏模型/目的地；
- 请求次数；
- 延迟分位数；
- token 和费用；
- 自动质量指标；
- 人工评分；
- 失败和修复；
- 是否通过；
- 未完成风险。

完整正文不进报告。需要举证时只引用短小、去身份、无密钥的片段。

## 12. 停止条件

立即停止真实测试并回到本地修复：

- Key 或正文进入日志/仓库；
- 未确认目的地仍能发送；
- 预算失败仍打开 Provider；
- 同一 Attempt 可能重复发送；
- 正式章节出现部分提交；
- 连续两次 schema 失败且修复策略相同；
- 正常章超过 10 分钟；
- 明确的 P0 状态污染；
- Provider 明确拒绝而 App 伪装为成功。

## 13. 完成定义

真实 API 阶段完成必须同时满足：

1. R0～R5 全部有证据；
2. 至少一个 3–5 章连续样本；
3. 至少一个组合能力样本；
4. 性能、质量、费用和拒绝均有报告；
5. 用户在物理设备上实际阅读；
6. secret/API 数据扫描通过；
7. 修复已进入 Git 并同步；
8. 未达到的指标被明确列为阻断或已接受风险，不能省略。
