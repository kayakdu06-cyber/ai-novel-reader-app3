# 织卷项目 AI 开发总交接（2026-08-11）

> 用途：让一个没有当前对话上下文的 AI，在不猜测、不重写既有系统、不丢失 WIP 的前提下继续开发。
>
> 当前动作边界：本次只整理、提交和备份。用户尚未要求在这次备份后立即继续写功能代码；接手者应先汇报理解，再等待明确的“开始开发/继续开发”指令。

## 0. 一分钟结论

- 产品名：**织卷**。
- 产品形态：只供用户本人使用的、本地优先 Android AI 小说生成与阅读 App。
- 核心体验：用户只填少量人物和偏好，App 自动完成规划、生成、检查、记忆更新和续写；已经生成的章节立刻可读，后续章节在后台继续生成。
- 当前仓库不是空壳：已形成大量本地数据库、状态机、加密工件、Provider 适配、预算预留、目的地确认、恢复和测试代码。
- 当前也不是可验收成品：统一总 runner 尚未闭环，`CHAPTER_PLAN_V1` 仍未注册，普通章节尚不能从计划一路自动生成、提交并进入阅读器。
- 最近的关键产品修正：小说不能被建模成单一固定题材。后续必须先引入“每本书按需组合能力”的架构，再继续 Phase 2E5C，避免系统、修仙、恋爱、成人场景、道具等能力互相污染或在无关章节浪费上下文。
- 最近一次完整验证基线是 TASK-064 Phase 2E5A：API 30/35 reservation 各 47/47、数据库各 276/276、generation 各 48/48，JVM 测试和 801-task 离线统一门禁通过。Phase 2E5B 只冻结设计，没有新增生产代码。
- 下一目标不是“马上做全功能正式版”，而是一个用户可以亲手安装验证的 3～5 章真实 API 纵向样机。

## 1. 唯一仓库与强制安全边界

唯一允许工作的项目根目录：

```text
D:\gptuser\projects\ai-novel-reader-app2
```

每次修改、构建或测试前必须执行等价校验：

```powershell
$expected = 'D:/gptuser/projects/ai-novel-reader-app2'
$actual = (git rev-parse --show-toplevel).Trim()
if ($actual -cne $expected) { throw "WRONG_ROOT:$actual" }
```

不可违反：

1. 不得修改或同步 `D:\gptuser\projects\ai-novel-reader` 及其他相似目录。
2. 所有产出、缓存、测试数据和临时文件只能放在 `D:\gptuser` 下。
3. 不得使用 `git reset --hard`、`git clean`、`git checkout --` 等方式清理当前现场。
4. `.codex/deepseek-key.local`、`local.properties`、数据库、安装包、签名材料和任何 API Key 不得提交或写进文档/日志。
5. DeepSeek 编码代理与织卷 App 内部的真实 Provider 是两条不同通道，不能混为一谈。
6. 接手 AI 必须完整读取根目录 `AGENTS.md`，并遵循它指定的开发规程。

## 2. 权威资料与冲突顺序

启动读取顺序：

1. `AGENTS.md`
2. `docs/24-AI-DEVELOPMENT-PROTOCOL.md`
3. 本文 `docs/ai/HANDOFF-2026-08-11.md`
4. `docs/ai/CURRENT-CONTEXT.md`，尤其最新章节
5. `docs/26-COMPOSABLE-NOVEL-PRODUCT-AND-DELIVERY-PLAN.md`
6. `reports/2026-08-09-137-current-system-checkpoint-and-next-work-plan.md`
7. `reports/2026-08-09-138-task-064-phase-2e5a-chapter-plan-bound-request-preparation.md`
8. `reports/2026-08-09-139-task-064-phase-2e5b-authoritative-chapter-scene-intent.md`
9. 当前子任务所对应的源代码和测试

发生冲突时按下列顺序判断：

1. 用户最新明确指令；
2. `AGENTS.md` 和项目隔离规则；
3. 本文与 `CURRENT-CONTEXT.md` 的最新记录；
4. 编号更高、日期更新的决策和工作汇报；
5. 早期 PRD、路线图和 `PROJECT_PLAN.md`。

不要把早期文档中的“固定类型管线”当成最终架构。组合式能力修正是当前最新产品结论。

## 3. 产品需求总表

### 3.1 必须实现

- 用户以尽量少的操作输入人物、题材倾向、文风、内容尺度和篇幅。
- 预设常见网文元素，但允许一本书任意组合多个元素，而不是被迫选一个互斥分类。
- 内容尺度的用户界面命名保持克制：`留白 / 均衡 / 细写`。内部策略字段必须明确、版本化、可测试。
- 短篇至少 80 章，中篇至少 300 章；长篇由用户自定义章节目标。这里的“短/中”是用户要求的产品档位，不按传统出版篇幅理解。
- App 自动生成世界观、人物、长线结构、窗口计划、逐章计划和正文，用户不需要逐章审批。
- 已生成章节立即进入可阅读状态，后续章节继续生成；生成失败不应破坏已完成章节。
- 支持主流模型 API 和 OpenAI 兼容中转站，配置必须尽量傻瓜化。
- API Key、本地小说、模板、设置、预算和生成状态都只保存在本地。
- 模板复制是一等功能：可以从旧书快速重开，但必须保留来源、分类、模板版本和派生链；不得复制旧正文、旧运行状态和密钥。
- 阅读器优先采用多看的沉浸式阅读逻辑，书架信息组织可参考起点；不引入广告、商城、签到、会员、社区或账户体系。

### 3.2 明确不做

- 账号、会员、支付、社区、云同步和管理后台。
- 广告、促销、签到、商城。
- 为了看起来“智能”而要求用户逐章填写复杂表单。
- 绕过模型服务商限制或隐瞒远程传输目的地。
- 把应用锁、生物识别、`FLAG_SECURE`、最近任务缩略图遮挡作为必做功能；用户已明确取消这部分。

### 3.3 内容与人物边界

- 成人题材应由“细写”档控制场景完整性、身体与感官连续性、动作与反应因果，以及场景对关系和剧情的后果，不能只依赖敏感词数量。
- 所有涉及成人场景的角色必须是明确的虚构成年人；年龄缺失、模糊或小于 18 岁时失败关闭。
- 模型服务商拒绝或限制时必须如实展示，不能把拒绝伪装成生成成功。
- 本地系统负责记录场景对关系、承诺、冲突、资源和后续剧情义务的影响，避免下一章“像没发生过”。

## 4. 最新产品架构：组合式小说能力

### 4.1 为什么必须改

一本小说可能同时包含修仙、恋爱、成人场景、系统升级、道具成长、权谋和悬疑。若把这些做成互斥题材：

- 同一事件会被多个模块重复记录；
- 无关章节也会携带系统/道具/关系上下文，浪费 API 理解能力；
- 不同规则会争抢剧情决定权；
- 后续新增题材容易继续复制状态表和提示词。

因此后续必须采用“统一故事核心 + 可组合能力”，详细设计以 `docs/26-COMPOSABLE-NOVEL-PRODUCT-AND-DELIVERY-PLAN.md` 为准。

### 4.2 必须落地的核心对象

建议先冻结语义和序列化合同，再决定 Room 表结构：

```text
BookCapabilityManifest
  enabledCapabilities[]       # 这本书允许使用的能力
  capabilityConfigs{}         # 每项能力的规则、强度和版本
  priorityPolicy              # 冲突时谁服从谁

ChapterCapabilityActivation
  activeCapabilities[]        # 本章真正需要的能力
  evidenceReasons[]           # 为什么激活
  contextBudget               # 本章给每项能力多少上下文

StoryEvent
  actors[]
  action
  targets[]
  location
  chronology
  stateDeltas[]
  evidence
  sourceChapter

PlotObligation
  promise
  dueWindow
  status
  evidence

EntityState / RelationshipState / MechanicState
  currentProjection
  lastVerifiedEvent
  confidence
```

### 4.3 不可破坏的原则

- **全书启用**与**本章激活**分离：能力在书中存在，不代表每章都注入其上下文。
- 模型是内容提案者，本地代码是事实裁决者。
- 事件只记录一次；人物、关系、道具、系统和剧情变化从事件投影，不各写一份相互漂移的“真相”。
- 事实、推断、计划严格分层。计划不能被误记为已经发生，推断不能覆盖权威事实。
- 新人物、新道具、新规则必须走受控新实体流程；模型不能通过正文悄悄创造永久事实。
- 能力冲突按稳定优先级解决：安全/年龄与硬设定 > 已提交事实 > 当前章节硬目标 > 能力规则 > 风格偏好。
- 无关能力不进入本章请求；这样可降低上下文重量，不会让“系统小说能力”拖累普通恋爱或现实题材。

### 4.4 当前道具与系统能力的真实状态

已有：

- `ITEM`、`POSSESSION` 等概念；
- 所有权和部分事实校验；
- 通用人物、关系、事件、记忆和衍生审计基础。

缺少：

- 类型化库存和当前状态投影；
- 消耗、转移、耐久、绑定、遗失、升级等统一 reducer；
- 新道具/新机制的受控创建与审批规则；
- 系统经验、等级、奖励、冷却和公式版本的可信本地演算；
- 与剧情义务、重复检测和章节激活的统一接线。

接手者不能写成“道具系统已经完成”。

## 5. 当前代码结构与规模

快照整理时的可见规模（构建缓存已排除）：

- Kotlin 文件：399 个，约 112,031 行。
- 生产 Kotlin：244 个，约 64,261 行。
- 测试 Kotlin：150 个，约 47,321 行。
- `docs/` 与 `reports/` Markdown：241 个，约 28,695 行；加入本文后数字会略增。
- 整理前 Git 现场：96 个已跟踪文件有修改，252 个未跟踪文件，共 348 个文件级条目。

Gradle 模块：

```text
:app
:core:model
:core:task
:core:security
:core:database
:core:backup
:core:network
:core:diagnostics
:provider:common
:provider:fake
:provider:capability-storage
:provider:stream
:provider:transport
:provider:openai-chat
:provider:openai-responses
:provider:anthropic
:provider:gemini
:feature:generation
```

模块意图：

- `app`：Compose 应用、导航、连接配置、书架和维护入口。
- `core:model`：跨模块领域模型和错误码。
- `core:task`：Job/Stage/Attempt 状态机、重试与预算策略。
- `core:database`：SQLCipher Room 权威事实、迁移、事务、生成和记忆仓储。
- `core:security`：密钥、受保护工件和安全基础。
- `core:backup`：备份与恢复校验。
- `provider:*`：统一 Provider 能力、流式传输和各协议适配。
- `feature:generation`：生成协调器、执行器、registry、解析与验收门禁。

## 6. 已实现的可靠基础

以下是“已有生产代码和测试基础”，不等于完整 App 已可用：

- SQLCipher Room 本地数据库、迁移和多类实体/DAO。
- Job、Stage、Attempt 的持久状态机、租约、心跳、暂停/停止/恢复和幂等边界。
- 受保护流式草稿、candidate 工件、seal、恢复和最终提交原语。
- OpenAI Chat/Responses、Anthropic、Gemini 等 Provider 适配模块和 fake provider。
- 请求意图、UNKNOWN 结果处理、用量台账、错误分类和重试约束。
- request/book/daily 三层持久预算 policy 与 reservation；实际 usage 结算、明确未执行释放、迟到 usage 回补和跨日替代请求。
- 外部数据目的地规范化、首次确认、配置变化失效，以及 Provider-open 时实际 profile/adapter/destination 精确匹配。
- chapter context 本地装配 route 和 final commit route 的 exact-token registry 接线。
- `CHAPTER_PLAN_V1` 的严格 route identity、48KiB 有界输出合同、业务 validator，以及 exact Job+Stage token 的请求前准备。
- 人物成年人门禁、部分场景执行合同、记忆/追踪/candidate/final 等大量局部执行器和测试原语。
- 备份排除、安全扫描、Release/R8/Lint/Vital 等统一构建门禁。

## 7. 当前未完成的关键链路

### 7.1 TASK-064 当前断点

最后完成的代码阶段：Phase 2E5A。

最后完成的设计阶段：Phase 2E5B。

当前事实：

- `CHAPTER_PLAN_V1` 仍在 registry 中显式未注册。
- Phase 2E5A 只完成 exact-token、预算和目的地保护下的请求准备，没有真正打开 Provider。
- Phase 2E5B 把逐章场景意图冻结为 arc-window v2 设计，但尚未实现。
- chapter-plan 的 Fake streaming、严格解析、DEC-068 原子提交和 initial DRAFT successor 未闭环。
- 普通 DRAFT、派生记忆、tracking、consistency、revision、final commit 尚未形成完整持久循环。
- App 没有一个可从 UI 触发并自动跑完普通第一章的总 runner。

### 7.2 为什么不能直接继续 Phase 2E5C

Phase 2E5B 的逐章 `relevantSceneIntent` 设计只处理一种场景意图。最新产品评审已经确认：同一本书需要任意组合多个能力。因此在写 2E5C 前，应把它提升为通用的章级能力子合同：

- arc-window 冻结本章剧情目标和可能激活的能力；
- 本地根据 Book Capability Manifest、已提交事实和本章目标计算激活集合；
- 每种能力只贡献有界子合同，不各自创建一套章节真相；
- chapter-plan 必须满足统一剧情目标、义务和状态约束，再满足具体能力规则。

否则现在完成 2E5C，后面会再次修改输入合同、hash、parser、测试 fixture 和兼容策略。

## 8. 下一阶段开发计划

### 阶段 A：组合能力合同冻结（下一项）

先写任务包和设计，不直接大改数据库：

1. 定义 `BookCapabilityManifest` 的有限能力标识、版本、配置和冲突优先级。
2. 定义章级 activation 的权威来源、可重算输入和 canonical hash。
3. 把 Phase 2E5B 的相关场景意图改成能力子合同，不让内容档自动等于本章激活。
4. 定义统一 `StoryEvent`、state delta、plot delta 和 obligation 的最小 schema。
5. 选择不引入大迁移的最小持久化方案；如确需迁移，先写迁移风险与兼容测试。
6. 为“普通章无系统上下文”“混合章同时激活关系+机制+道具”“能力冲突”写失败关闭测试。

完成标准：能证明无关能力不进入请求，同一事件不会被多份状态重复承诺，旧 arc-window v1 不会被静默猜测升级。

### 阶段 B：chapter-plan 真实纵向闭环

1. 实现 arc-window v2/能力合同输出和严格解析。
2. 建立唯一 request snapshot factory。
3. Fake-only exact-token streaming executor。
4. 严格解析和一次有限格式修复。
5. DEC-068 同事务提交规范计划并创建 initial DRAFT。
6. 把 `CHAPTER_PLAN_V1` 加入有限 registry；所有未完成 route 继续显式失败关闭。

### 阶段 C：普通章节总 runner

按最短闭环接入：context → chapter plan → initial draft → consistency → 必要 revision → memory/tracking → final commit。

必须覆盖：

- 进程被杀后恢复；
- pause/stop；
- UNKNOWN 不自动重发；
- exact-token 和 lease 过期；
- replay 不重复扣费、不重复提交；
- 已完成章节不因后续失败回滚。

### 阶段 D：可亲手验证 APK

目标范围：

- 用户能在 Android 测试机安装；
- 从 App 内配置 DeepSeek V4 Flash 连接；
- 通过极简创建流程生成一本 3～5 章验证书；
- 第 1 章完成后可立即阅读，第 2～5 章后台继续；
- 可看到生成状态、失败原因、重试/停止和用量；
- 可退出/重启后恢复；
- 可从该书保存并复制模板重开。

这个里程碑不要求 80/300 章全部生成完，也不要求最终视觉精修；它必须证明核心产品链路是真的，不是测试代码拼图。

### 阶段 E：可靠个人版与长篇验收

- 完成书架、目录、阅读器、模板来源/归类和连接向导体验。
- 做章节速度、长上下文、成本和内存优化。
- Fake 连续 20 章稳定性后，再做真实 API 多轮测试。
- 最终按 80 章、300 章和自定义长篇目标做分阶段压力与恢复验收，不要求一次性在开发阶段生成全部正文。

详细时间估算见 `docs/26-COMPOSABLE-NOVEL-PRODUCT-AND-DELIVERY-PLAN.md`。最新评估：可亲手验证样机约 18～30 个高质量开发日；可靠个人版约 35～60 个开发日；长篇可靠验收还需 20～40 个开发日。它们是工作量，不是日历承诺。

## 9. 测试与真实 API 规则

### 9.1 两类测试都必须保留

- Fake Provider：用于确定性故障、超时、断流、格式错误、UNKNOWN、重放、并发和恢复测试。
- 真实 DeepSeek V4 Flash App Provider：用于内容质量、结构服从、流式首段、整章速度、真实 usage、错误映射和端到端体验。

Fake 通过不能代替真实 API；真实 API 偶然成功也不能代替失败路径测试。

### 9.2 真实 API 验证要求

用户明确要求测试使用 DeepSeek V4 Flash。开始真实 API 测试前：

1. 用户需已经明确批准进入开发/真实测试阶段；本次备份本身不等于立即发起真实请求。
2. 只从本机安全存储读取 Key，不输出、不复制进任务包。
3. 先做最小连接探针，再做 1 章，再做 3～5 章；不要一开始生成 80/300 章。
4. 记录首段时间、正文结束时间、正式提交时间、输入/输出 token、重试、费用和失败原因。
5. 目标不是保证每章固定秒数；初始门槛是首段应尽快可见，完整普通章优先控制在约 1～3 分钟，5 分钟进入慢服务处置，10 分钟未结束不得作为可发布体验。

### 9.3 最近一次已知验证基线

TASK-064 Phase 2E5A 的记录：

- API 30/API 35 reservation：各 47/47。
- API 30/API 35 database：各 276/276。
- API 30/API 35 generation：各 48/48。
- core database 与 feature generation JVM：通过。
- `scripts/verify-build.ps1 -Offline`：801 actionable tasks 通过。
- Debug/Release、Lint/Vital、R8、源码与 5 个 APK 安全扫描、备份排除：通过。

本次整理只运行安全扫描和 Git 完整性检查，没有重新跑完整 Gradle/模拟器矩阵。后续 AI 不得把“最近已知基线”写成“当前提交刚刚重新验证”。

## 10. DeepSeek 编码代理协作规则

- Sol/主 AI 负责拆任务、设计裁决、审查差异、补测试和确认完成。
- DeepSeek 只执行边界明确的纯文本编码/审计任务，不做图片和视觉判断。
- 每次调用前必须按 `docs/ai/TASK-PACKET-TEMPLATE.md` 写独立任务包。
- 推理强度使用 `max`，不设总 Token 上限；窄任务通常 15～30 分钟。只有同一窄任务确因正常推理在 30 分钟超时时，才可说明理由放宽到最多 45 分钟。
- DeepSeek 没有产生可审查差异时，不得把思考过程当成实现结果。
- DeepSeek 不得自行宣布 TASK 完成；必须由主 AI 审查差异并取得测试证据。
- 数据库授权、租约、原子提交、安全和费用边界优先由主 AI 直接实现或逐行复核。

## 11. 建议的下一任务包

任务身份建议：

```text
TASK-064 / Phase 2E5B2 组合能力清单与章级激活合同冻结
```

边界：先审计和设计，允许新增领域合同与纯 JVM 测试；默认不改 schema/migration，不注册 `CHAPTER_PLAN_V1`，不调用 Provider，不写 UI。

必须回答：

1. 能力标识如何保持有限、版本化、可扩展？
2. 全书启用和本章激活的权威输入分别是什么？
3. 如何把“细写场景意图”作为子合同而不是全局题材开关？
4. 如何让道具、系统、关系和剧情义务共享同一事件，不互相覆盖？
5. 如何证明无关能力不消耗本章上下文？
6. 旧 arc-window v1/v2 WIP 如何失败关闭或重建，而不是静默猜测迁移？

主 AI 先冻结上述答案，再决定是否让 DeepSeek 实现窄范围代码。

## 12. 常见严重错误清单

接手者不得：

- 从零重写现有 runner、状态机、数据库或 Provider。
- 为赶进度把 route 按 phase 猜测分发。
- 用同 owner 的“最新 token”替代调用方 exact token。
- 在 Provider-open 之后才检查预算、目的地或年龄门禁。
- 把 UNKNOWN 当作失败后自动重发。
- 让模型生成的正文直接覆盖本地权威事实。
- 把每种题材做成一套独立人物/事件/道具账本。
- 因一本书启用了某能力，就在每章携带其全部上下文。
- 把计划写成事实，或用模型下一次输出纠正已提交事实。
- 为方便测试绕过加密工件、原子事务、租约和失败关闭。
- 把 fake provider 测试称为真实 API 验收。
- 把安全存储中的 Key、GitHub 凭据或用户小说内容写进报告。

## 13. Git 与备份恢复说明

本次整理后的私有远程目标：

```text
https://github.com/kayakdu06-cyber/ai-novel-reader-app2
```

包含本文的快照提交应是备份完成时 `main` 的 HEAD；不要在文档中硬编码自引用提交号，接手时运行：

```powershell
git rev-parse HEAD
git status --short
git remote -v
```

本地离线 Git bundle 放在：

```text
D:\gptuser\backups\ai-novel-reader-app2\2026-08-11
```

该目录中的 `BACKUP-MANIFEST.md` 记录最终提交、bundle 文件名、SHA-256 和验证结果。恢复示例：

```powershell
git clone D:\gptuser\backups\ai-novel-reader-app2\2026-08-11\<bundle-file>.bundle D:\gptuser\projects\ai-novel-reader-app2-restored
```

恢复后仍需自行创建 `local.properties` 和本机密钥文件；它们被故意排除，不能从 Git 恢复。

## 14. 接手 AI 首次汇报模板

接手后先向用户简短汇报：

```text
已确认唯一项目根目录和 Git 状态，并完整读取项目规程、总交接、当前上下文与组合式产品方案。当前断点是 TASK-064 Phase 2E5A 代码完成、2E5B 设计冻结，CHAPTER_PLAN_V1 尚未注册。下一步应先完成组合能力清单和章级激活合同，再继续真实纵向闭环；我不会从零重写，也不会调用真实 API，直到你明确让我开始开发/测试。
```

这份汇报之后才进入用户批准的下一项工作。
