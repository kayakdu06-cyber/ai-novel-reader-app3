# 织卷创作策略包与组合式小说规格

> 状态：V1 设计基线
> 生效日期：2026-08-11
> 目的：把外部写作 skill 的有效方法转成 App 内安全、精简、可组合、可版本化的创作能力

## 1. 结论

App 里“放 skill”有用，但不应把 Codex/Claude 使用的 SKILL.md、脚本或代理工作流原样安装进 Android App。

织卷需要的是最小内置 WritingPolicyPack（创作策略包）：

- 它是结构化规则和模板数据，不是可执行插件；
- 由 App 自己的代码解析、选择、合并和校验，不建设安装器、商店或运行时管理页；
- 每次生成冻结版本和来源；
- 只把本章相关片段编译进 Prompt；
- 不允许任意文件访问、命令执行或网络工具；
- 外部 skill 只是研究来源，必须经过提炼、许可证审查、测试和本地重写；来源记录保留在开发文档，不为它新增运行时数据库。

现有 PromptBundleCatalogV1 继续保留，但其长期职责改为“运行时编译结果和兼容桥”，不再承载所有创作规则的唯一作者源。

## 2. 为什么不能直接装第三方 skill

第三方 skill 常包含：

- 面向编码 Agent 的操作说明；
- 命令行脚本和目录假设；
- 私有路径、工具名或多代理工作流；
- 对单篇创作有帮助但对 Android 运行时无意义的步骤；
- 未明确许可证或来源；
- 大量规则全文，直接拼接会放大 token、延迟和相互冲突。

直接运行还会引入脚本执行、越权读取、提示注入和版本漂移。织卷是本地小说产品，不是通用 Agent 宿主，因此首版不提供“安装任意 skill”功能。

## 3. 外部方法如何进入产品

### 3.1 提炼流程

1. 记录来源仓库、版本、许可证和获取日期。
2. 删除 Agent 操作、脚本、私有路径和与小说无关的内容。
3. 将规则拆成可独立激活的小片段。
4. 把可确定性检查的部分转成本地校验器。
5. 把需要模型判断的部分转成结构化输入/输出合同。
6. 编写正向、冲突和“不应激活”测试。
7. 用 Fake Provider 验证 Prompt 组成。
8. 用 DeepSeek V4 Flash 做一个真实 smoke；只有出现质量或速度回归时才做 A/B。
9. 通过后作为随 App 发布的固定内置版本。

### 3.2 可提炼内容

| 方法来源 | 可吸收能力 | 不带入 App 的内容 |
|---|---|---|
| 连续性类写作 skill | 人物、物品、时间、关系、伤势和知识状态规则 | JS/Python CLI、Agent 调用命令 |
| 中文网文写作方法 | 故事引擎、章任务卡、钩子、节奏、信息边界 | 私有磁盘路径、固定题材成品提示 |
| 通用小说技法 | 场景目标、冲突、转折、对白、视角和节奏检查 | 多代理流水线、长篇教程全文 |
| 中文去模板化方法 | 语气指纹、句式变化、上下文适配 | 整包原文、不可追溯提示片段 |

## 4. 核心数据合同

### 4.1 WritingPolicyPack

运行时最小合同只包含：

| 字段 | 含义 |
|---|---|
| packId | 稳定 ID，例如 zhijuan.web-fiction-core |
| version | 不可变语义版本 |
| locale | 适用语言和地区 |
| fragments | 可独立选择的规则片段 |
| validators | 本地验证器 ID，不包含可执行脚本 |
| conflicts | 冲突规则和优先级 |
| promptBudget | 本包最多占用的 token/字符预算 |
| checksum | 规范化内容哈希 |

来源、许可证、审查人和排除内容写入仓库开发文档，不进入用户数据库、不做管理 UI，也不建立在线更新机制。

### 4.2 PolicyFragment

片段按用途分层：

- planning：故事种子、全书方向、分卷和章节任务；
- drafting：正文风格、视角、场景执行、对白和节奏；
- continuity：事实、时间、位置、身体、关系、道具和系统状态；
- analysis：章后摘要、义务结算、一致性和重复检查；
- revision：只在明确问题时使用的修订规则；
- presentation：留白、均衡、细写的表达映射。

片段必须声明：

- requiredCapabilities；
- forbiddenCapabilities；
- applicableStages；
- priority；
- maxPromptChars；
- hardRules 或 softGuidance；
- 是否需要结构化输出字段；
- 兼容的 schema 版本。

### 4.3 BookCapabilityManifest

一本书可以启用多个能力，不再用一个固定分类控制全书。这里必须区分两类信息：

- 用户创作意图：原始故事设想、自由题材/机制/关系/结构/文风描述和快捷标签，词汇开放，不能按内置 ID 校验或裁剪；
- 内部状态适配器：只为需要跨章确定性约束的状态提供版本化规则，集合有限且不可由用户文本直接执行。

BookCapabilityManifest 记录后者，不是 App 支持题材的白名单。没有对应专用适配器的自由题材仍使用 core-narrative、character-continuity、NarrativeObligation 和通用证据语义生成。原始输入必须保留并进入规划，不能被拒绝、丢弃或替换成某个预设。

首个纵向切片可能使用的内置状态适配器如下；这些是实现与测试示例，不是小说类型表：

| 能力 | 负责的状态 |
|---|---|
| core-narrative | 目标、冲突、转折、后果、钩子 |
| character-continuity | 身份、动机、知识、身体和情绪 |
| relationship-progression | 关系阶段、承诺、信任和边界 |
| cultivation | 境界、功法、资源、代价和突破条件 |
| progression-system | 等级、积分、任务、奖励、冷却和触发 |
| item-progression | 归属、位置、耐久、能力和成长 |
| mystery | 线索、嫌疑、误导、揭示和读者信息 |
| faction-politics | 阵营、利益、同盟、敌对和公开/秘密信息 |
| intimacy-continuity | 相关场景计划、过程连续性和剧情余波 |
| romance | 情感目标、误解、选择和关系推进 |

Manifest 只表示“这本书可能需要哪些专用状态规则”，不代表每章全部激活，更不决定用户可以写什么。

### 4.4 ChapterCapabilityActivation

每章计划生成前，本地路由器根据以下输入产生激活结果：

- BookCapabilityManifest；
- 当前卷/章节任务；
- 未完成 NarrativeObligation；
- 当前人物、关系、物品和系统状态；
- 用户呈现档；
- 上一章后果；
- 当前上下文预算。

输出包括：

- activeCapabilityIds；
- requiredPolicyFragmentIds；
- expectedStateNamespaces；
- forbiddenTransitions；
- promptBudgetByFragment；
- activationReasonCodes；
- activationHash。

相同权威输入必须产生相同结果。模型不得自行启用未授权的高影响能力。

### 4.5 NarrativeObligation

用“叙事义务”避免关键剧情丢失：

| 字段 | 含义 |
|---|---|
| obligationId | 稳定 ID |
| kind | 承诺、后果、线索、任务、关系、道具、系统或自定义 |
| sourceEvidence | 来源章节、事件或用户设定 |
| ownerEntityIds | 相关人物/组织/物品 |
| dueWindow | 计划处理窗口 |
| allowedActions | ADVANCE、FULFILL、DEFER、CANCEL_WITH_REASON |
| status | OPEN、ADVANCED、FULFILLED、DEFERRED、CANCELLED |
| latestEvidence | 最近一次有效变化 |
| revision | 单调版本 |

每章计划必须说明本章处理哪些义务；章后分析必须给出完成、推进、延期或有理由取消。没有证据时不能消失。

### 4.6 StoryStateDelta

当前纵向切片实际需要的状态变化共享一个变化外壳；以下 namespace 是首批验证集合，不是题材枚举：

- namespace：character、relationship、item、system、cultivation 或 world；
- subjectId；
- fieldKey；
- beforeValueHash；
- proposedAfterValue；
- transitionType；
- evidenceExcerptRef；
- authorityLevel；
- confidence；
- capabilityId；
- policyVersion。

具体能力只在当前书实际启用时定义自己的字段和合法转移，本地代码负责验证。例如：

- item.owner 不能在无转移事件时变更；
- system.level 必须满足升级条件和单调规则；
- relationship.trust 可以升降，但必须有事件证据；
- character.location 必须和时间线相容；
- cultivation.realm 不得跳过被设为必需的中间境界。

未启用某能力时，不生成其专用状态，也不把空字段送进 Prompt。

## 5. 章节计划 V2

chapter-plan.v2 在现有 v1 场景合同上增加：

- chapterObjective；
- activeCapabilityIds；
- obligationActions；
- expectedStateDeltas；
- prohibitedRepetitions；
- requiredCallbacks；
- sceneCauseEffect；
- endHook；
- contextEvidenceHash；
- policyCompilationHash。

现有 v1 的人物、地点、视角、开场、转折、收束、承接、相关性、过程节点和余波继续保留。

### 5.1 计划质量门

- 每个场景必须有目的、冲突或信息变化。
- 本章至少推进一个主目标或制造一个新后果。
- 不得把上一章完整事件换词复述为本章主事件。
- 关键状态变化必须先在计划中声明，正文后再以证据确认。
- 计划可以改变，但任何临时改变必须在章后分析中解释。

## 6. 章后合并分析 V1

为控制速度，正常章节不把摘要、人物记忆、时间线、伏笔和一致性拆成多次远程请求。模型一次返回 chapter-post-analysis.v1：

- summary；
- completedAndOpenObligations；
- entityEvents；
- canonFacts；
- timelineEvents；
- foreshadowTransitions；
- storyStateDeltas；
- repetitionFindings；
- consistencyFindings；
- presentationFindings；
- severeRevisionRequired；
- evidenceBindings。

本地把各部分校验后映射到现有表和仓库。正常接受路径只需一次分析请求。

### 6.1 原子性

- 原始分析结果先写受保护 artifact。
- 各子区块必须全部通过来源、schema 和状态转移验证。
- 任一 P0 子区块失败，不得部分推进权威 CURRENT_STATE。
- 可修复格式错误最多进行一次有界修复。
- 需要正文修订时，旧分析不提交；修订后重新分析。
- 最终 ChapterVersion、记忆、追踪、义务、状态和 Usage 在一个本地事务中提交。

## 7. 策略选择与冲突

### 7.1 优先级

从高到低：

1. 安全和角色成年硬门；
2. 用户明确设定与禁改项；
3. 已发生的权威事实；
4. 本章 NarrativeObligation；
5. 能力状态转移规则；
6. 呈现档和风格；
7. 通用写作建议。

低优先级规则不得覆盖高优先级事实。

### 7.2 组合示例（不定义支持范围）

一本修仙 + 恋爱 + 系统 + 亲密呈现小说：

- 书级 Manifest 启用四种能力；
- 普通战斗章只激活 cultivation、system 和 character；
- 情感选择章激活 romance、relationship 和 character；
- 相关场景章再激活 intimacy-continuity；
- 道具未出现时不加载 item-progression；
- 每章仍共享 core-narrative 和最小 continuity。

这个样本只用于验证选择性装配。其他题材、机制、关系结构或文风即使没有专用适配器，也必须通过开放创作意图和通用语义正常进入规划与正文。

## 8. Prompt 编译

PolicyCompiler 输入：

- 阶段；
- PromptBundle 版本；
- 书级能力；
- 章级激活；
- 当前上下文快照；
- 用户呈现档；
- Provider 能力和 token 上限。

输出：

- system instructions；
- developer/author rules；
- stage contract；
- selectedPolicyFragments；
- structured output schema；
- prompt manifest；
- canonical hash；
- 省略报告。

### 8.1 预算规则

- 硬规则和本章义务必须完整保留。
- 只选择激活能力的片段。
- 重复语义去重，不重复粘贴完整创作宪法。
- 风格示例只在确有需要且预算允许时加入。
- 优先压缩历史描述，不压缩年龄、禁改项、关键状态和到期义务。
- 超过预算时失败关闭或降低非关键风格片段，不能静默删除硬约束。

## 9. 来源与许可证

每个内置包必须保留：

- sourceName；
- sourceUrl；
- sourceCommitOrVersion；
- retrievedAt；
- licenseId；
- allowedUseSummary；
- transformedSections；
- excludedSections；
- reviewer；
- localPackVersion。

许可证不明的来源只能用于概念研究，不能复制原文进发布包。App 内不展示第三方 skill 安装入口，也不从网络自动更新策略。

## 10. 数据迁移

第一阶段禁止为了“结构更完整”新增专表：

1. 以版本化 JSON 合同和现有不可变快照保存策略选择；
2. 用现有 OutlineRevision/ContextSnapshot/Stage input 固定计划与 activation hash；
3. 只有现有字段无法保证崩溃恢复、原子提交或防止权威状态污染时，才新增最小 Room migration；
4. migration 若被触发，必须保留 v1 书籍；旧书缺 Manifest 时只启用 core-narrative、character-continuity 和由原创建快照能确定的能力；
5. 不从旧正文猜测敏感能力或人物成年事实。

## 11. 测试矩阵

### 11.1 本地确定性

- 相同输入产生相同 activation 和 compilation hash。
- 未激活能力的片段和状态字段完全缺席。
- 冲突按固定优先级解决。
- 已声明必须使用专用状态适配器但其 pack、版本、fragment、validator 或 capability ID 未知时失败关闭；未命中预设的自由题材文本不属于此类错误。
- Prompt 超预算时硬规则不丢失。
- 来源记录缺失或许可证 BLOCKED 时不能发布为 ACTIVE。

### 11.2 最小组合能力

- 一个未列入快捷预设、且不需要专用状态适配器的自由题材：原文保留并进入 Prompt，不被拒绝或改成默认题材，Prompt 和状态中不出现无关机制负担。
- 一个修仙 + 恋爱 + 系统 + 道具的混合样本：本章只激活相关子集，关键状态分别可信。
- 年龄不明，或任务明确要求一个已声明但未配置的专用适配器时，联网前失败关闭。

不为每种题材排列组合分别建测试；新测试必须保护一个尚未覆盖的独立故障。

### 11.3 章后分析

- 一次响应可映射到现有记忆、时间线、伏笔和一致性存储。
- 任一状态变化缺证据时整组不提交。
- 重复剧情被标记且严重时触发修订。
- 义务只能按白名单动作变化。
- 修订后旧分析不能提交。

## 12. 明确不做

- 不执行第三方 skill 附带的代码。
- 不允许用户导入任意提示包并获得系统权限。
- 不把 20 个写作 skill 全文塞入每次请求。
- 不为每个题材复制一整套数据库和 runner。
- 不让模型直接决定预算、重试、提交或状态真相。
- 不因书级启用某能力就让每章强行出现该内容。

## 13. 完成标准

本规格只有在以下证据齐全后才算实现：

1. 一个内置核心策略版本经过来源和许可证文档审查；
2. 组合能力路由纯本地测试通过；
3. chapter-plan.v2 与 chapter-post-analysis.v1 严格 parser 通过；
4. Prompt manifest 可证明每章实际选择了哪些片段；
5. 未激活能力零 Prompt 占用的测试通过；
6. Fake Provider 连续 3–5 章保持义务和状态；
7. DeepSeek V4 Flash smoke 通过；只有 smoke 显示质量或速度回归才要求 A/B；
8. 任意失败不会部分污染权威小说状态。
