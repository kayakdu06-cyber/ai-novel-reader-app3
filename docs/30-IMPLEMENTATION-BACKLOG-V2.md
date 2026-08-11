# 织卷实施任务清单 V2

> 状态：当前权威任务顺序
> 日期：2026-08-11
> 前置事实：TASK-001～063、083 的既有完成证据保留；TASK-064 已有 WIP 被拆入新的有界任务，不从零重写

## 1. 使用规则

- 本文决定“下一步做什么”；19-IMPLEMENTATION-BACKLOG.md 保留历史任务详情。
- 同一时间只允许一个主任务处于实现状态。
- 每个任务必须包含代码、测试、文档、工作报告、Git commit 和 push。
- 没有真实差异的 DeepSeek 输出不算完成。
- 不得在一个任务里同时设计新 schema、总 runner、UI 和真实 API。
- P0 失败时停止进入下一任务；不靠 TODO、假数据或关闭门禁穿过。
- 时间为有效工时范围，不是自然日承诺。

## 2. 任务总览

| ID | P | 工时 | 交付 | 依赖 | 主要执行 |
|---|---:|---:|---|---|---|
| TASK-120 | P0 | 4–8h | 开发重规划、现状审计和权威文档 | 现有代码 | Sol |
| TASK-121 | P0 | 6–10h | WritingPolicyPack 领域合同、目录、来源和编译器骨架 | 120 | Sol 设计，DS 有界实现 |
| TASK-122 | P0 | 6–10h | BookCapabilityManifest、章级激活与冲突路由 | 121 | Sol + DS |
| TASK-123 | P0 | 8–14h | NarrativeObligation、StoryStateDelta 与迁移策略 | 122 | Sol 设计，DS 实现 |
| TASK-124 | P0 | 8–14h | arc-window v2、chapter-plan v2 合同和 v1 兼容 | 121–123 | Sol + DS |
| TASK-125 | P0 | 8–14h | 收口 TASK-064 的普通 plan Fake 执行、严格提交和 initial DRAFT 交接 | 124、064 WIP | Sol 主审，DS 分包 |
| TASK-126 | P0 | 8–14h | initial draft 冻结来源、exact-token 流式执行和恢复 | 125 | Sol + DS |
| TASK-127 | P0 | 10–16h | chapter-post-analysis.v1、现有记忆/追踪映射、有限修订和原子提交 | 123、126 | Sol 设计，DS 分包 |
| TASK-128 | P0 | 10–16h | 单章 persistent total runner + Fake 闭环 | 125–127 | Sol 主接线 |
| TASK-129 | P0 | 8–14h | 3–5 章自动队列、暂停/恢复和 Fake 验收 | 128 | Sol + DS 测试 |
| TASK-130 | P0 | 6–10h | StartGenerationUseCase、确认页真实开始和状态入口 | 128 | Sol + DS |
| TASK-131 | P0 | 10–18h | 最小书架、目录、阅读器和生成中正文投影 | 129、130 | Sol 主审，DS UI 分包 |
| TASK-132 | P0 | 6–12h | DeepSeek V4 Flash 真实合同、质量和速度 smoke | 129–131 | Sol 执行 |
| TASK-133 | P0 | 8–16h | 真实 3–5 章、物理设备验收、修复和可验证 APK | 132 | Sol |
| TASK-134 | P1 | 8–14h | 模板提取、来源/分类/版本和三步重开 UI | 133 | Sol + DS |
| TASK-135 | P1 | 10–18h | 阅读器主题、字号、目录和状态体验完善 | 133 | Sol + DS |
| TASK-136 | P0 | 16–30h | DeepSeek 20 章可靠性、编辑和恢复验收 | 133–135 | Sol |
| TASK-137 | P1 | 10–18h | 手动加密备份/恢复 UI 和演练 | 133 | Sol + DS |
| TASK-138 | P0 | 16–30h | 80 章样本、300 章分段模拟和 1.0 候选闸门 | 134–137 | Sol |

TASK-121～133 按表逐项相加为 102–178 个有效工程小时。边界清楚的实现和测试可由 DeepSeek 并行辅助，从而缩短自然时间，但工时估算不因此虚减；要守住上限，必须保持最小实现，不把 P1 完善提前塞入 P0。

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

只做：

- core 中的不可变领域合同；
- data 中的内置 pack 读取和版本记录；
- policy fragment 的阶段/能力/预算元数据；
- 规范化 hash；
- provenance/license 状态；
- PolicyCompiler 的纯本地骨架；
- PromptBundle v1 兼容适配。

不做：

- 数据库大迁移；
- 远程模型调用；
- UI；
- 第三方脚本执行；
- 任意 skill 导入。

必测：

- 相同输入相同 hash；
- 未知版本、片段或 BLOCKED 来源失败；
- maxPromptChars 生效；
- 硬规则不会被低优先级覆盖；
- 输出对象 toString 不含 Prompt 正文。

### TASK-122 组合能力路由

只做：

- BookCapabilityManifest；
- ChapterCapabilityActivation；
- 能力定义 registry；
- 冲突解析器；
- 从现有创建快照推导初始 Manifest；
- 未启用能力零占用证明。

必测组合：

- 无系统普通小说；
- 修仙 + 系统；
- 恋爱 + 悬疑；
- 修仙 + 恋爱 + 系统 + 道具 + 亲密连续性；
- 未成年人/年龄不明的相关能力失败关闭；
- 同输入激活结果确定。

### TASK-123 义务和状态变化

分两步：

1. 纯领域合同、转移校验和 in-memory 测试；
2. 最小 Room migration、DAO 和原子写入。

初始 namespace：

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
- 未激活 namespace 的 delta 被拒绝；
- migration 保留所有旧书和生成状态。

## 4. Wave B：章节合同和现有 WIP 收口

### TASK-124 规划合同 V2

交付：

- arc-plan.v2；
- chapter-plan.v2；
- v1 只读兼容；
- 窗口重建策略；
- activationHash、policyCompilationHash、obligationActions 和 expectedStateDeltas；
- 严格 schema、业务 validator 和 canonical JSON。

闸门：

- 只规划 1–8 章窗口；
- 剩 3 章补窗；
- 全书目标可为 80、300 或 10,000，不产生全书逐章请求；
- 旧 v1 书不静默猜测新增能力；
- 无 Provider 调用。

### TASK-125 普通 chapter-plan 收口

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

必测：

- Provider-open 前重新校验 destination、budget、current lease 和 source hash；
- 错 schema、错人物、错 activation、错义务、错 plan hash 全部不提交；
- 成功只创建一个 initial DRAFT；
- replay 不产生第二个 DRAFT；
- registry 仍拒绝所有未完成 route。

### TASK-126 initial draft

交付：

- 独立 initial-draft-source.v1；
- 只引用请求前已持久证据；
- exact-token bound prepare/open；
- 流式 artifact；
- 第一段只读投影；
- 截断续接；
- 结束后进入 post-analysis；
- 崩溃恢复。

必测：

- 不伪造尚未生成的 candidate hash；
- 第一段出现前正式 ChapterVersion 不存在；
- 取消后草稿安全落盘但不正式提交；
- UNKNOWN 不自动重新收费；
- source/plan/context 任一漂移时 Provider 0。

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

必测：

- 某子区块失败不部分写状态；
- 义务、系统、道具、关系状态和正文证据一致；
- 重复剧情严重时不提交；
- revision lineage 精确；
- 正常路径 calls 不超过目标。

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

验收：

- PREPARING、STREAMING、ANALYZING、COMMITTING 杀进程恢复；
- 双执行器竞争只有一个写入；
- pause/stop 行为和文档一致；
- 一章完成后 Job/Stage/Attempt/Usage/Chapter 状态一致。

### TASK-129 3–5 章循环

交付：

- 自动创建下一章计划和 Stage；
- 有界队列；
- 前章正式提交后才允许后章进入需要权威事实的阶段；
- 5 章 Fake 结果报告；
- 混合能力 fixture。

验收：

- 章节 ordinal 连续且唯一；
- 义务链不丢；
- 状态变化可从每章证据重放；
- 一章失败后前章可读；
- 暂停后没有新 Provider-open；
- 退出重启自动恢复或明确待处理。

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
- 长章、万章目录已有性能基线不退化；
- 200% 字号和横屏通过。

## 7. Wave E：真实 API 和手机验收

### TASK-132 DeepSeek 真实 smoke

只在 31 号文档前置条件通过后执行。

顺序：

1. 最小结构化合同；
2. 最小流式正文；
3. 一章完整链；
4. 混合能力一章。

每步通过才进入下一步。失败时先判断：

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

- 至少两种组合题材；
- 20 章连续生成；
- 至少一次断网、杀进程、暂停和编辑；
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

- 80 章真实或分段真实样本；
- 300 章状态/窗口/恢复模拟；
- 物理设备矩阵；
- Release/R8、迁移、备份和安全扫描；
- 质量退化曲线；
- 无 10 分钟正常章节；
- 签名 APK 和恢复材料。

## 10. 下一任务唯一入口

TASK-120 提交后，下一任务必须是 TASK-121。

不得直接跳回：

- 旧 TASK-064 的下一个超大 Phase；
- 阅读器视觉精修；
- 大规模删代码；
- 80 章真实长跑；
- 任意第三方 skill 安装。

TASK-121 完成并通过纯本地测试后，才进入 TASK-122。
