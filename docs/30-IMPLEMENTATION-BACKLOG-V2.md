# 织卷实施任务清单 V2

> 状态：当前权威任务顺序
> 日期：2026-08-11
> 前置事实：TASK-001～063、083 的既有完成证据保留；TASK-064 已有 WIP 被拆入新的有界任务，不从零重写

## 1. 使用规则

- 本文决定“下一步做什么”；19-IMPLEMENTATION-BACKLOG.md 保留历史任务详情。
- 同一时间只允许一个主任务处于实现状态。
- 每个任务必须包含代码、最小风险测试、文档和工作报告；多模块任务的每个模块批次分别 commit 和 push。
- 每个任务先锁定主模块和允许配套模块；未列出的模块禁止修改。多模块任务必须按模块分批审查，`:app` 批次只允许导航和组装。
- 没有真实差异的 DeepSeek 输出不算完成。
- 不得在一个任务里同时设计新 schema、总 runner、UI 和真实 API。
- 不新增第十一个模块，不新增 feature 实现依赖，不顺手搬迁现有代码。
- 不实现“以后可能有用”的抽象、插件、配置或测试；只有稳定性、数据完整性、费用、安全或严重 bug 的直接证据才能扩大范围。
- 测试按风险最小化：相关模块测试优先；全量、双 API、Release/R8 和 APK 扫描只在里程碑或对应高风险变化时运行。
- P0 失败时停止进入下一任务；不靠 TODO、假数据或关闭门禁穿过。
- 时间为有效工时范围，不是自然日承诺。

## 2. 任务总览

| ID | P | 工时 | 主模块/允许配套模块 | 交付 | 依赖 |
|---|---:|---:|---|---|---|
| TASK-120 | P0 | 4–8h | docs only | 开发重规划、现状审计和权威文档 | 现有代码 |
| TASK-121 | P0 | 4–6h | `:core` only | 最小 WritingPolicyPack/fragment 纯 Kotlin 合同 | 120 |
| TASK-122 | P0 | 4–8h | `:feature:generation`；只读 `:core` | 章级能力激活和最小 Prompt 选择 | 121 |
| TASK-123 | P0 | 6–10h | `:data`；仅必要时配套 `:core` 合同 | 复用现有快照；仅稳定性证据不足时增加最小持久化 | 122 |
| TASK-124 | P0 | 6–10h | `:feature:generation` | 最小 arc/chapter plan v2 合同与 v1 兼容 | 121–123 |
| TASK-125 | P0 | 6–10h | `:data` + `:feature:generation`，分模块批次 | 收口普通 plan Fake 执行、提交和 initial DRAFT 交接 | 124、064 WIP |
| TASK-126 | P0 | 8–12h | `:feature:generation` + `:data`，不改 `:provider` | initial draft exact-token 流式执行与恢复 | 125 |
| TASK-127 | P0 | 8–14h | `:feature:generation` + `:data`，分模块批次 | 合并章后分析、现有仓库映射和原子提交 | 123、126 |
| TASK-128 | P0 | 8–14h | `:feature:generation` only | 单章 persistent total runner + Fake 闭环 | 125–127 |
| TASK-129 | P0 | 6–10h | `:feature:generation` only | 3–5 章自动队列、一次暂停/恢复验收 | 128 |
| TASK-130 | P0 | 4–8h | `:core`/`:feature:generation`/`:feature:creation`/`:app` 分批 | 开始合同、生成实现、确认事件和导航组装 | 128 |
| TASK-131 | P0 | 8–14h | `:feature:library`/`:feature:reader`/`:app` 分批 | 最小书架、目录、阅读器和生成中正文 | 129、130 |
| TASK-132 | P0 | 3–6h | 默认不改代码；协议 bug 才限 `:provider`/`:feature:generation` | DeepSeek 真实合同 + 单章 smoke | 129–131 |
| TASK-133 | P0 | 5–10h | 只改失败证据指向的模块 | 真实 3–5 章、物理设备验收和 APK | 132 |
| TASK-134 | P1 | 6–10h | `:feature:template`/`:feature:creation`/`:app` 分批 | 模板来源、分类、版本和三步重开 | 133 |
| TASK-135 | P1 | 8–14h | `:feature:reader`/`:feature:library`/`:app` 分批 | 必需阅读设置和目录体验 | 133 |
| TASK-136 | P0 | 10–18h | 默认测试/报告；只改失败模块 | 一个 20 章混合样本、一次编辑和一次恢复 | 133–135 |
| TASK-137 | P1 | 8–14h | `:data`/`:feature:library`/`:app` 分批 | 数据损失风险所需的最小手动备份/恢复 | 133 |
| TASK-138 | 条件 P0 | 另估 | 只改失败证据指向的模块 | 20 章证据不足或发布前才做 80/300 扩展验证 | 134–137 |

TASK-121～133 按精简后范围约 82–132 个有效工程小时。估算不包含未触发的 TASK-138，也不允许把 P1 完善或无证据扩展塞回 P0。

## 3. Wave A：统一创作语义

### TASK-120 开发重规划

状态：完成（2026-08-11，文档与审计基线已建立）。

交付：

- 27～31 号新文档；
- 现有功能接线表；
- 旧/新文档权威关系；
- CURRENT-CONTEXT 和 Git 同步。

验收：

- 文档不把存在类或测试误写成 UI 已接通；
- 明确 10 模块、约 60,100 行 Kotlin、普通 plan WIP 和真实 API 0 的事实；
- 后续 AI 能从本文直接选择唯一下一任务。

### TASK-121 WritingPolicyPack 基础

模块锁：只允许 `:core`。如果实现需要 `:data`、`:provider`、任何 feature 或 `:app`，说明任务边界设计错误，停止而不是扩展。

只做：

- 一个随 App 代码发布的不可变核心策略合同；
- policy fragment 的阶段、能力、优先级和预算元数据；
- 规范化 hash；
- 与 PromptBundle v1 对接所需的最小纯 Kotlin 适配合同。

不做：

- PolicyCompiler 业务实现（归 TASK-122）；
- 运行时来源/许可证数据库；
- 数据库 migration；
- 远程模型调用；
- UI；
- 第三方脚本执行；
- 任意 skill 导入。

必测：

- 相同输入相同 hash；
- 未知版本或片段失败；
- 一条优先级冲突测试；
- 一条 Prompt 正文不进入 toString 的泄漏测试。

验证只跑 `:core` 相关测试和编译，不运行 assembleDebug 或全量门禁。

### TASK-122 组合能力路由

模块锁：只允许 `:feature:generation`；消费 TASK-121 的 `:core` 合同，不反向修改 `:core`。

只做：

- BookCapabilityManifest；
- ChapterCapabilityActivation；
- 开放创作意图与最小内置状态适配器表之间的明确边界；适配器表不是题材白名单；
- 从现有创建快照保留原始自由描述，并只推导确实需要的初始 Manifest；
- 未启用能力零占用证明。

必测组合：

- 一个未列入快捷预设的自由题材：原文进入 Prompt，不被拒绝、丢弃或改成默认题材，且无关适配器零占用；
- 一个修仙 + 恋爱 + 系统 + 道具混合输入；
- 一个年龄不明或明确声明的专用适配器缺失负例；
- 相同输入确定性由上述用例顺带断言，不再单开排列组合。

不做动态插件 registry、用户自定义 capability 编辑器或新 UI；开放题材文本不得被误实现为开放可执行扩展。

### TASK-123 义务和状态变化

模块锁：主模块 `:data`。只有缺少公共纯 Kotlin 类型时才允许一个独立 `:core` 合同批次；不得同时修改 generation 或 UI。

先证明现有 OutlineRevision、ContextSnapshot、Stage input/output 和记忆表能否保存义务与状态证据：

1. 能满足崩溃恢复、来源重验和原子提交：复用现有结构，不做 migration；
2. 不能满足且会造成状态丢失/污染：只增加缺失的最小字段或表，并写出故障链。

初始只支持验证样本实际需要的 namespace：

- character；
- relationship；
- item；
- system；
- cultivation；
- world。

必测：

- 义务不能无证据消失；
- 系统等级不可信跳级；
- 道具不能无事件换主人；
- 关系变化允许升降但必须有事件；
- 未激活 namespace 的 delta 被拒绝。

只有实际产生 migration 时才增加一条旧库保留专项；没有 migration 就不跑迁移矩阵。

## 4. Wave B：章节合同和现有 WIP 收口

### TASK-124 规划合同 V2

模块锁：只允许 `:feature:generation`。不为了合同升级迁移数据库，不修改 UI、Provider 或 app。

交付：

- 在现有 ArcWindowPlanningStructuredOutput 和 ChapterPlanStructuredOutput 上做最小 v2；
- v1 只读兼容；
- activationHash、policyCompilationHash、obligationActions 和 expectedStateDeltas；
- 严格 schema、业务 validator 和 canonical JSON。

闸门：

- 只规划 1–8 章窗口；
- 剩 3 章补窗；
- 全书目标可为 80、300 或 10,000，不产生全书逐章请求；
- 旧 v1 书不静默猜测新增能力；
- 无 Provider 调用。

验证只覆盖一个 v1 兼容、一个 v2 正向和一个篡改负例；不为每个字段重复建立同构负例。

### TASK-125 普通 chapter-plan 收口

模块批次：

1. `:data`：只补当前 route/事务确实缺少的冻结和提交能力；
2. `:feature:generation`：request factory、parser/executor 和有限 registry；
3. 不修改其他模块。

基线：

- 复用 TASK-064 Phase 2E5A 的 exact-token RequestIntent；
- 复用 Phase 2E5B 的权威 expectation 思路；
- 不重写双租约、预算、目的地和 route resolver。

交付：

- v2 request factory；
- expectation/activation/policy manifest 冻结；
- Fake Provider 结构化返回；
- strict parse；
- plan commit；
- 规范计划原子冻结到 initial DRAFT input；
- CHAPTER_PLAN_V2 加入有限 registry。

最小验证：

- Provider-open 前重新校验 destination、budget、current lease 和 source hash；
- 用一个参数化负例覆盖错 schema/人物/activation/义务/plan hash 不提交；
- 一个成功 + replay 用例证明只创建一个 initial DRAFT；
- registry 仍拒绝所有未完成 route。

### TASK-126 initial draft

模块批次：先 `:data` 的 exact-token/持久边界，再 `:feature:generation` 的流式执行；现有 ProviderAdapter 能满足合同就禁止修改 `:provider`。

交付：

- 独立 initial-draft-source.v1；
- 只引用请求前已持久证据；
- exact-token bound prepare/open；
- 流式 artifact；
- 第一段只读投影；
- 截断续接；
- 结束后进入 post-analysis；
- 崩溃恢复。

最小验证：

- 一个成功流覆盖“不伪造 candidate、首段不是正式版本、结束进入分析”；
- 一个取消/UNKNOWN 恢复用例覆盖不正式提交和不自动重复收费；
- 一个参数化来源漂移用例覆盖 source/plan/context 变化时 Provider 0。

### TASK-127 合并章后分析与提交

交付：

- chapter-post-analysis.v1；
- summary、memory、tracking、foreshadow、obligation、state delta、consistency 合并响应；
- 本地确定性检查；
- 映射到现有仓库；
- 严重问题有限修订；
- 修订后重新分析；
- 复用现有最终章节原子提交。

性能约束：

- 正常正文之后只允许一次分析请求；
- 不因启用多个能力增加一组远程调用；
- 修订只对严重且可修问题触发；
- 远程调用数量进入时序报告。

最小验证：

- 某子区块失败不部分写状态；
- 一个混合正向用例同时覆盖义务、系统、道具、关系和正文证据；
- 一个严重重复负例覆盖不提交与修订 lineage；
- 时序报告断言正常路径 calls 不超过目标。

## 5. Wave C：总 runner 与 Fake 纵切

### TASK-128 单章 total runner

交付：

- 唯一生产 dispatcher；
- 有限 executor registry；
- Job 循环；
- current Stage 领取、heartbeat、执行、推进和终态；
- FGS/WorkManager 调用同一 runner；
- Fake 一章从创建到提交。

禁止：

- 第二个 runner；
- UI 直接调用 Stage repository；
- 兼容 owner-only 入口进入 total runner；
- 未注册 route 的通用 fallback。

最小验收：

- 用确定性状态夹具覆盖 PREPARING/STREAMING/ANALYZING/COMMITTING 恢复，不为每个状态重复做物理杀进程；
- 双执行器竞争只有一个写入；
- 一个单章端到端同时覆盖 pause/stop 安全点和 Job/Stage/Attempt/Usage/Chapter 一致；
- 物理杀进程留给 TASK-133 一次。

### TASK-129 3–5 章循环

交付：

- 自动创建下一章计划和 Stage；
- 有界队列；
- 前章正式提交后才允许后章进入需要权威事实的阶段；
- 一个 3–5 章 Fake 结果报告；
- 混合能力 fixture。

验收：

- 章节 ordinal 连续且唯一；
- 义务链不丢；
- 状态变化可从每章证据重放；
- 在同一连续场景中插入一次暂停/重启，顺带验证前章可读、无新 Provider-open 和恢复状态；不分开重复跑。

## 6. Wave D：产品接线

### TASK-130 生成启动入口

交付：

- GenerationController 增加受约束的 start 接口，或新增明确的 GenerationStarter；
- 费用确认页点击后创建唯一 Job；
- 导航到生成中书籍页；
- 重组/双击幂等；
- 首次目的地确认和预算提示。

验收：

- 移除“生成执行器尚未接入”占位；
- 点击开始不需要开发者介入；
- 错误以用户语言显示；
- 连接被删除或改变时失败关闭。

### TASK-131 书架和阅读器最小接线

交付顺序：

1. Library 状态流；
2. 书架 Compose；
3. 目录；
4. 正式正文阅读；
5. 生成中草稿只读投影；
6. 阅读时后台生成；
7. 状态/错误/暂停入口。

验收：

- ReaderSessionCoordinator、LibraryCatalog 有生产调用者；
- Compose UI 不依赖 data 实现；
- feature 间不新增非法实现依赖；
- 只要列表/正文渲染算法未变化，就复用已有长章/万章性能证据，不重跑；
- 对实际新增页面各做一次 200% 字号和横屏检查。

## 7. Wave E：真实 API 和手机验收

### TASK-132 DeepSeek 真实 smoke

只在 31 号文档前置条件通过后执行。

顺序：

1. 最小结构化合同；
2. 一个完整混合能力章节，同时覆盖流式正文、合并分析、提交、质量和速度。

不额外建立“最小流式正文”和“混合能力单章”重复请求。失败时先判断：

- Provider 协议；
- 模型 schema 遵从；
- Prompt 过大；
- 流式解析；
- App 状态机；
- 内容拒绝。

不得把 API Key、正文或 Prompt 写入 Git 和自由日志。

### TASK-133 亲手验证 APK

交付：

- 3–5 章真实样本；
- 质量/速度/用量报告；
- 物理设备恢复测试；
- APK、SHA-256 和安装说明；
- 已知问题；
- Git 同步。

只有用户在设备上完成一次端到端使用后，才把 VS-1 标为通过。

## 8. Wave F：个人可靠版

### TASK-134 模板重开

交付：

- 从书籍提取允许字段；
- 来源链、分类、标签、版本和使用快照；
- 书架“按这本重开”入口；
- 最多三次点击开始新书；
- 不复制旧正文、API Key、连接秘密、Attempt、Usage 和旧错误。

### TASK-135 阅读完善

- 多看式暖纸阅读；
- 目录抽屉；
- 字号、字体、背景、亮度和夜间模式；
- 起点式书架信息层级；
- 生成状态融入目录和正文，不堆 AI 按钮。

### TASK-136 20 章可靠性

- 只用一个经过用户认可的混合题材样本连续生成 20 章；
- 在选定章节安排一次编辑和一次“断网或杀进程”恢复，不把所有故障排列组合；
- 重复剧情、义务完成率、状态错误率、速度和费用报告；
- P0 数据污染为 0。

### TASK-137 手动备份恢复

- 加密导出；
- 明确包含和排除；
- 错口令、损坏、中断不覆盖当前库；
- 恢复后书、模板、进度和状态可读；
- API Key 默认不随备份。

## 9. Wave G：1.0 候选

### TASK-138 长篇与发布

TASK-138 默认不启动。只有以下任一证据存在才立项：

- 20 章后出现无法判断趋势的状态漂移或重复剧情；
- 目标 1.0 发布需要长篇声明证据；
- 窗口/召回算法发生了会随章节数放大的变化。

触发后只选择能回答具体风险的 80 章真实/分段样本或 300 章状态模拟，不默认两者全做。Release/R8、迁移、备份、安全扫描和签名 APK 属于发布闸门，不与每轮长篇生成重复执行。

## 10. 下一任务唯一入口

TASK-120 提交后，下一任务必须是 TASK-121。

不得直接跳回：

- 旧 TASK-064 的下一个超大 Phase；
- 阅读器视觉精修；
- 大规模删代码；
- 80 章真实长跑；
- 任意第三方 skill 安装。

TASK-121 只允许修改 `:core`，完成其相关纯 Kotlin 测试和编译后才进入 TASK-122；不得借此启动 data、UI、Provider 或新模块开发。
