# 织卷 AI 生成系统规格

## 1. 设计目标

长篇质量不能依赖“把整本书全塞进一次请求”。生成系统必须做到：

- 尽快交付可读第一章，并分别约束首段、正文结束和正式提交时间；
- 后续章节持续推进而不过度重复；
- 人物、关系、规则、时间线和伏笔可追踪；
- 任意阶段中断后可恢复；
- 用户编辑后不继续使用过期记忆；
- 每次调用都有预算、来源和结果记录；
- 模型拒绝、截断或格式错误时能够停在正确状态。

## 2. 全流水线

| 阶段 | 输入 | 结构化输出 | 可见结果 | 失败策略 |
|---|---|---|---|---|
| `NORMALIZE_INPUT` | 创建快照 | 标准化需求 | 无 | 本地校验后有限重试 |
| `BUILD_STORY_SEED` | 标准化需求 | 核心人物/欲望/阻力/卖点 | 简介草案 | 格式修复一次 |
| `BUILD_BIBLE` | 故事种子 | 稳定人物、世界规则、禁改事实 | 设定卡 | 阻塞第二章前进 |
| `BUILD_MASTER_OUTLINE` | 种子+圣经 | 开端/升级/转折/结局 | 总纲 | 保留上一有效版 |
| `BUILD_ARC_PLAN` | 总纲 | 卷/阶段计划 | 规划进度 | 可分批生成 |
| `BUILD_CHAPTER_PLAN` | 当前卷+记忆 | 章目标/场景/钩子 | 目录计划 | 只规划窗口内章节 |
| `ASSEMBLE_CONTEXT` | 记忆+预算 | 上下文包和来源清单 | 无 | 本地确定性执行 |
| `DRAFT_CHAPTER` | 章计划+上下文 | 正文草稿 | 仅临时状态 | 截断可续接或重写 |
| `EXTRACT_MEMORY` | 冻结且绑定最终版本 ID 的正文 | 摘要/人物事件/事实 | 无 | 格式修复，不改正文；时间线/伏笔后续投影 |
| `CHECK_CONSISTENCY` | 草稿+圣经+记忆 | 问题列表和等级 | 无 | 硬冲突触发修订 |
| `REVISE_CHAPTER` | 草稿+问题 | 修订草稿 | 无 | 最多固定次数 |
| `COMMIT_CHAPTER` | 草稿+派生数据+用量 | 原子提交 | 章节立即可读 | 本地事务重试 |
| `UPDATE_FUTURE_PLAN` | 新记忆+剩余目标 | 调整后的后续计划 | 规划变更提示 | 可延后，不阻塞阅读 |

### 2.1 生成关键路径与速度边界

正式候选当前按 `BODY → MEMORY → TRACKING → CONSISTENCY → 可选 REVISION → FINAL COMMIT` 绑定不可变来源。总 runner 第一版必须先保持这条可靠依赖并记录逐阶段时序；未经依赖、质量和预算证明，不得为了压缩时间把所有远程阶段直接并发或合并契约。

正文流的完整段落可以通过受保护草稿投影进入“生成中正文”只读视图，但它仍不是正式 `ChapterVersion`。是否能看到流式初稿与整章是否在速度门槛内正式提交是两项独立验收，完整定义见 `25-RELIABILITY-AND-GENERATION-PERFORMANCE-ROADMAP.md`。

受支持推荐模型档案下，普通后续章首个完整段落 P95 目标为 20 秒、正文结束 P95 为 180 秒、正式提交 P95 为 240 秒。5 分钟进入慢服务安全处置，10 分钟仍未结束属于 P0 发布阻断。任意用户手填模型不做虚假保证；未通过固定参考负载的组合不能成为默认推荐。

## 3. 第一章快车道

为了减少等待，第一章允许只依赖故事种子、最小人物表、核心世界规则、结局倾向和前 3 章粗计划生成。但必须满足：

1. 不跳过成年人年龄明确性和硬性内容规则检查；
2. 第一章提交后立即构建完整故事圣经和总纲；
3. 完整规划未成功前禁止开始第二章；
4. 如果完整规划与第一章硬冲突，优先调整规划适配已提交第一章，不静默重写用户已开始阅读的内容；
5. 用户可关闭快车道，先看规划再生成。

## 4. 提示上下文分层

从高到低：

1. **应用硬规则**：输出格式、安全和数据边界；
2. **阶段任务契约**：本次只做什么、必须返回哪些字段；
3. **故事圣经硬事实**：身份、年龄、世界规则、禁改事实；
4. **当前卷/章计划**：本章目的、场景、转折和结尾钩子；
5. **相关运行记忆**：人物状态、关系、时间线、物品、伏笔；
6. **最近章节摘要**：保证短程连续性；
7. **文风与呈现参数**；
8. **用户本次附加要求**。

用户文本必须作为数据段落包装，不能与系统阶段契约拼成不可区分的指令。模板和导入内容同样视为不可信数据。

### 4.1 章级场景执行契约

`DRAFT_CHAPTER` 不能只接收笼统的“写得详细”。章计划在进入正文生成前必须解析为版本化场景执行契约，至少包含：

- 场景目的和必须发生的状态/关系/风险变化；
- 当前视角、知识边界和感官可达范围；
- 参与人物及相关内容的成年人门禁结果；
- 呈现参数与 `fadePolicy`；
- 场景开始时的位置、衣着/物品、身体、情绪和关系状态；
- 需要连续呈现的动作—反应节点；
- 场景结束状态和必须进入运行记忆的余波；
- 禁止替代方式与质量检查项。

当 `intimacyDetailLevel=4` 且 `fadePolicy=AVOID` 时，自动启用严格身体与感官连续性：

1. 计划内关键过程必须在当前视角的可感知范围内展开，不能用转场、事后概述或一句话总结替代；
2. 重要动作必须有与人物和情境一致的感知或行为反馈，保持动作—反应因果链；
3. 人物位置、身体状态、衣着/物品、疲劳、伤势和情绪不能在段落或场景之间无因跳变；
4. 触觉、视觉、声音、呼吸/节奏、语言和心理只选当前时刻真正相关者，形成连续变化，不按固定清单机械轮播；
5. 场景结束必须留下与剧情相关的身体、情绪、关系或决策余波，并进入摘要/人物状态；
6. 具体度与粗俗度分离：词汇服从人物身份和文风，高细节不等于堆叠粗俗词或重复同一感官描述。

这套规则由系统按用户已经选择的呈现档位自动装配，不要求用户逐场确认。若成年人门禁不通过，场景契约不得进入相关正文生成；若服务商拒绝，按标准拒绝流程处理。

## 5. 上下文预算

模型能力登记表给出可靠上下文上限 `contextLimit` 和最大输出 `maxOutput`；未知时使用保守默认并让用户确认。

章前输入预算建议：

| 分区 | 预算占比 | 裁剪规则 |
|---|---:|---|
| 硬规则和阶段契约 | 8% | 不裁剪，只能压缩措辞 |
| 故事圣经硬事实 | 15% | 仅选与本章相关事实，但年龄/核心规则常驻 |
| 当前计划 | 12% | 不裁剪本章，远期计划只留摘要 |
| 最近连续性 | 20% | 最近 1–3 章摘要优先 |
| 相关长期记忆 | 25% | 关键词/FTS/可选向量检索排序 |
| 文风和用户要求 | 10% | 合并重复规则 |
| 安全余量 | 10% | 防中转站和 tokenizer 差异 |

输出预算与输入分开。上下文超限时先减少低相关长期记忆和远期计划，禁止删除硬事实后继续生成。

## 6. 结构化输出策略

- 规划、摘要、状态和检查结果使用应用定义 JSON schema。
- 提供方原生支持结构化输出时使用；否则要求 JSON 并本地严格解析。
- 本地解析先执行严格 UTF-8 和 JSON 语法扫描，拒绝重复键、Markdown/前后缀、多个根值、尾随内容、非法代理对和超出深度/节点/成员/数组/字符串/数字上限的输入；根必须是 object。
- 每个结果必须携带数值 `schemaVersion`；只接受契约声明的版本，旧版必须经显式迁移到当前版本，未知未来版本和迁移失败均关闭失败。
- 第一次可修复失败：把“无效输出作为数据”、有界问题码和当前 schema 交给独立的零温度格式修复请求，不重复原始创作提示，也不允许修复内容改变任务。
- 空输出、无效 UTF-8、超出字节或解析资源上限，以及没有剩余 attempt 名额的错误不自动修复；阶段直接进入 `NEEDS_ACTION`。
- `FORMAT_INVALID` 持久写入 Attempt，跨进程统计修复次数；同一 Stage 第二次格式失败可靠暂停，不靠内存计数，也不用正则猜测关键状态。
- 校验成功只推进到 `COMMITTING`；只有持有与最新成功 Attempt、草稿修订/hash 和当前租约绑定的内部提交许可，才可进入章节提交事务。
- TASK-045 在单一 SQLCipher 事务内插入正式 `ChapterVersion` 与结构化记忆、CAS 切换当前版本、结算 Usage、完成当前 Stage、激活预冻结下一 Stage 并更新书籍进度。任何一步失败都不留下半章或半套记忆。
- 同一成功 Stage 的完全相同提交只回读既有结果；内容、派生数据、下一 Stage 或目标章节版本不一致时失败关闭，不能用“重试”覆盖用户已修改的正文。
- 正文使用纯文本流，禁止把 Markdown 代码围栏写入正式章节。
- 每个 schema 有 `schemaVersion`；迁移器只能处理已知旧版本。

### 6.1 生成控制安全点

- 暂停、取消当前章和停止全书先写入父 Job，再由执行器读取持久控制意图；内存按钮状态不是事实来源。
- `PREPARING` 尚未形成 RequestIntent 时可立即回到 `READY` 并暂停；已经形成 RequestIntent 或开始流时，必须先取消 Provider、强制刷新加密草稿、取消 Attempt 并最终结算 Usage，之后才到 `PAUSED/STOPPED`。
- 继续只把用户暂停的 Job 恢复到 `READY`，不直接打开 Provider；调度器必须重新领取 Job/Stage 租约并再次经过发送审计门。
- 校验或正式提交已经开始时不丢弃本地原子工作：成功提交当前章后暂停下一 Stage；校验失败则保留 `FORMAT_INVALID` 与修复资格后进入暂停；停止请求可覆盖尚未完成的暂停。
- 心跳、请求发送标记和流开始标记在同一次执行中串行写库，避免并发提交造成时间戳倒序；控制轮询和网络收流仍保持并行。
- 迟到的“已发送/已开始/已完成”回调必须同时通过父 Job、当前 Stage、Attempt、租约和单调时间检查，任何控制或恢复已经改变现场时均失败关闭。

## 7. 运行记忆模型

### 7.1 章节摘要

包含：本章目标、关键事件、角色决策、关系变化、新事实、地点、时间推进、物品变化、未解决问题和结尾状态。摘要引用具体章节版本 ID。

### 7.2 人物状态

按人物和时间点记录：位置、身体状态、情绪、目标、掌握信息、关系、持有物、承诺与秘密。状态是事件增量与可重建投影，不仅是反复覆盖的一段文本。

### 7.3 事实和时间线

事实分为：

- `HARD_CANON`：用户或故事圣经明确，生成不能自行更改；
- `STORY_CANON`：已提交正文确立；
- `PLAN_ONLY`：尚未发生，可调整；
- `INFERRED`：模型推断，冲突时优先级最低。

时间线事件保存故事内相对/绝对时间、前后约束和来源，无法确定时保留“未知”而不是编造精确日期。

### 7.4 伏笔

状态：`PLANNED → PLANTED → DEVELOPING → RESOLVED`，也可进入 `ABANDONED`。每条记录期望回收窗口、可见角色、来源和更新章节。

## 8. 中文记忆检索

MVP 使用多路召回：

1. 当前章计划中的人物、地点、物品和关键词；
2. Room FTS 对摘要和事实检索；
3. 固定最近窗口；
4. 未解决伏笔和硬事实强制召回；
5. 按相关性、时间、重要度、未解决状态重新排序。

中文不能仅依赖英文空格分词。FTS 方案必须用真实中文小说样本做召回基准。向量检索作为 P2 可插拔模块，只有在基准证明 FTS 不足且设备开销可接受时加入。

TASK-060 已落实为同一 SQLCipher 主库内的 FTS4 派生索引：汉字使用确定性单字与相邻双字 ASCII token，双字 v2 必须保持全字母数字，避免 Android FTS4 把分隔符拆成两个词。旧 v1 回填标记会在下一次章前组装时自动整书重建。检索只返回派生指针，随后必须重读六类权威记忆行并复算文档；强制事实、到期伏笔、最近摘要和 FTS 相关项按固定路线合并。10,000 条正式加密库固定集在 API 30/API 35 均为 20/20，热查询中位分别约 6.07/4.35 ms。

## 9. 一致性检查

### 9.1 本地确定性检查

- 本章出现人物是否存在；
- 年龄和成人状态是否明确且未冲突；
- 死亡/离场人物是否无解释回归；
- 角色地点是否违反时间移动约束；
- 物品所有权是否冲突；
- 章节编号和时间顺序；
- 计划要求的关键事件是否覆盖；
- 输出为空、过短、重复段落或被代码围栏包裹。

### 9.2 模型语义检查

检测人物动机突变、关系跳变、语气漂移、设定软冲突、伏笔遗忘和场景因果断裂。对启用严格身体与感官连续性的场景，额外检测计划内关键过程被淡出/概述替代、动作没有反应、空间或身体状态无因跳变、余波缺失以及把细节误写成机械词汇堆叠。输出只允许问题码、严重度、Unicode 码点位置、已知对象 ID 和固定修订动作，不允许自由建议、正文摘录或直接覆盖正文。

### 9.3 修订门槛

- `BLOCKER`：违反硬事实、人物状态或章节不可读；必须修订/暂停。
- `MAJOR`：明显因果或连续性问题；默认自动修订一次。
- `MINOR`：风格、轻微重复；记录但不阻塞，可按质量档位修订。

自动修订总次数有固定上限，避免模型反复互改产生费用循环。

## 10. 编辑失效图

编辑源章 `N` 后：

```text
ChapterVersion(N)
  → ChapterSummary(N) stale
  → EntityEvents(N) stale
  → CanonFacts(N) stale
  → SearchIndex(N) stale
  → AggregateState(N..latest) stale
  → ContextSnapshots(N+1..latest) stale
  → ConsistencyReports(N+1..latest) stale
```

后续正式章节正文不自动失效或删除；它们被标记为 `CONSISTENCY_UNKNOWN`，由用户选择保留、检查或从下一章重织。

TASK-012 已在 schema v3 落地这条失效链的原子事务和来源约束。TASK-061 Phase 1 已提供用户编辑专用生产事务：调用方提交书、章节、预期旧版本、稳定的新版本 ID、正文和编辑时间；repository 在内部计算正文 SHA-256，先捕获旧版本关联的搜索来源，再执行 stale 级联、保存不可变 `USER_EDIT` 版本、以旧 current/status/consistency 做 CAS 切换为 `EDITED/UNKNOWN`，最后删除旧 FTS 来源。精确 replay 不重复版本，跨书、错章、过期 current、版本 ID 冲突和倒退时间均在写入前失败或整体回滚；后续章正文不删除。

TASK-061 Phase 2A 已新增只读的 `ChapterEditRebuildPlanRepository`。它会在一个 Room 事务内冻结编辑点至最新正式章的 current version、正文 hash、状态和一致性状态，生成带稳定 `planHash` 的有序影响计划；真正执行某一步之前，`requireCurrentMatches` 会重算完整计划并拒绝任何 current version、派生占位或依赖状态变化。默认策略仅支持“保留现有后续正文”，尚未实现的“从下一章重织”会直接失败关闭。

计划不会把“理论上需要重建”误报为“当前已经能安全执行”。在 10 章、编辑第 3 章的固定场景中，共得到 32 个步骤：编辑章记忆提取 1 项为 `READY`，其余 31 项因既有唯一版本槽、追踪顺序保护、聚合缺少重建 writer 或上游阻塞而明确 `BLOCKED`；其中 17 项是将来可能需要 Provider 的步骤，后 7 章正文继续保留。规划阶段不建 Job、不写 Attempt/Usage、不调用 Provider，也不改任何业务表。

schema v13 已完成派生历史槽、伏笔 after-state revision、受审计 current projection rewind 和受影响区间 revision/transition 失效。Phase 2B3A 又完成了纯本地 aggregate 重建 writer：它从 current-version-bound 人物属性事件、当前活动伏笔和同章有效 tracking 投影重新计算有界 CURRENT_STATE，并写入一代新的 `VALID` 聚合头。该切片当时尚未完成跨章顺序 Job/Stage 和 TEST-033，因此没有把单章 writer 描述成完整自动重建；最终通用循环见 27.10。“从下一章重织”的产品分支仍是独立后续能力。

Phase 2B3B2B2 已把第一章 rebuild tracking 提交与同章 aggregate writer 放入同一个 Room 外层事务。tracking、时间线、伏笔 transition/revision、FTS、aggregate、FINAL Usage 和 Stage/Job 完成要么一起成功，要么一起回滚；成功 Stage 的精确 replay 只验证当前 tracking/aggregate 已严格满足，不用合法进展后变化的 planHash 再写一代 aggregate。Stage 创建后 aggregate 槽变化会在 Provider-open 前阻断。该切片当时未完成后续保留章节与 TEST-033，最终结果见 27.10。

Phase 2B3B2C 已完成第一个保留后续章节的受控 tracking 退役与替换 Stage 创建。schema v15 新增不可变 `chapter_edit_rebuild_tracking_retirement`：它把准备时旧 tracking 指纹、精确排序的旧 timeline ID 集合与内容指纹、确定性 replacement Job/Stage 和退役时间绑定到同一 execution/step。旧 tracking/timeline 只从 `VALID` 转为 `STALE`，对应搜索文档删除、真实 current source 回读、replacement Job/Stage 和退役证据在一个 Room 事务内完成；冲突、并发或来源变化会整笔回滚。该安全切片只推进编辑章后的第一章；后续 Phase 2B3B2D/2E 才完成 Provider、aggregate、通用区间和 TEST-033。

## 11. 章节截断处理

TASK-055 已把“正常终态但达到输出上限”与网络断流分开处理：

1. 正文输出固定为 `chapter-draft.v1`：单一 JSON object 且只能含字符串字段 `body`。流式解码器只把完整 UTF-8 码点对应的 body 明文交给受保护草稿，不保存 JSON 外壳、Markdown 或解释。
2. `LENGTH` 先把当前 Attempt 记为 `SUCCEEDED + OUTPUT_TRUNCATED` 并绑定草稿 revision/hash；其 Usage 独立结算。崩溃发生在状态推进前时，重启可只凭落库分类恢复，不重新调用 Provider。
3. 自动续接沿用同一逻辑 Stage/generation，新建 Attempt 和 `retryParentAttemptId`；新草稿先预置已验证旧正文，输入 hash 同时绑定原 Stage 输入、父输出 hash、96 码点精确锚点 hash 和续接序号。
4. 续接提示携带最多 2,048 码点的保存尾窗和精确 `requiredBodyPrefix`。响应 body 必须从该锚点逐码点开始；本地验证后剥离锚点，只追加新正文。错误、缺失或被改写的锚点失败关闭，已保存正文不被 Provider 新文本污染。
5. 最多自动续接 3 次；第 4 次截断、Stage attempt 用尽、正文达到 4 MiB、空/过短片段或格式无效均保存现场并进入 `NEEDS_ACTION`。次数来自持久 Attempt 链，重启不会清零。

若网络断流且结束原因未知，不自动把片段当正式章。

## 12. 模型分工默认策略

默认所有阶段使用当前推荐模型，避免用户选择过多。高级模式可指定：

- 规划模型：长上下文和结构化输出优先；
- 写作模型：中文叙事质量和输出长度优先；
- 记忆模型：便宜、稳定结构化输出优先；
- 检查模型：事实比较和指令遵循优先。

切换只影响未开始阶段。已经提交的章节记录实际使用模型，不追求跨模型“完全一致”。

## 13. 质量档位

| 档位 | 规划 | 检查 | 修订 | 用途 |
|---|---|---|---|---|
| 快速 | 最小规划 | 本地检查 | 仅 blocker | 短篇试读 |
| 标准 | 完整规划 | 本地+模型 | major 一次 | 默认 |
| 细致 | 完整规划+更长窗口 | 双检查 | major/minor 最多两次 | 长篇重点书 |

质量档位影响调用数量，开始前计入费用预估。

## 14. 内容拒绝处理

- 识别 HTTP 状态、提供方错误类型和响应中的明确拒绝标记。
- 保存为标准错误 `POLICY_REFUSAL`，不记录不必要的完整敏感正文。
- 不自动改写为规避服务方限制的提示，不伪装请求，不无限重试。
- 用户可修改设定、降低呈现强度或选择其自行配置的其他连接。
- 无论用户选择何种连接，应用级成年人明确性和禁止真实人物规则仍为硬规则。

## 15. 可观测性

每阶段记录：`generationId`、`stageId`、`attemptId`、输入来源哈希、模板/故事版本、模型和协议版本、开始/结束时间、标准结束原因、usage、估价、重试关系、输出哈希、提交结果。普通日志不存完整提示和正文。

## 16. 未知结果恢复决策

恢复不等于重新生成。`UnknownResultRecoveryPolicy` 只接受持久证据，并输出以下互斥决定：

| 证据 | 决定 | 是否发新请求 |
|---|---|---:|
| Attempt 已成功，Stage 停在校验/提交/恢复态 | 恢复本地校验或提交 | 否 |
| 提供方查询显示原请求仍在运行或暂时无结论 | 保留原 Attempt，等待再次对账 | 否 |
| 提供方确认未执行；已有远端请求 ID 和发送时间；本地草稿可读且为空；无已知 usage | 回到 READY | 否，回队动作不联网 |
| 提供方已完成但本地无可恢复输出 | 标记结果未知并保留最终 usage | 否 |
| 查询不支持、超时、报错，仅有 RequestIntent，或草稿/usage 与“未执行”矛盾 | 等待用户明确确认 | 否 |

用户确认后只把 Stage/Job 原子恢复为可领取状态；下一次生成必须产生新 Attempt，并重新经过预算、外部目的地、租约和发送审计门。任何恢复函数和提供方状态查询函数均不得调用 `generate()`。

## 17. Android 执行宿主边界

- `GenerationForegroundService` 只负责把用户明确启动的活动 Job 保持为系统可感知工作、展示私密控制通知并观察数据库；它不自行创建 Job、Stage、Attempt 或发送许可。
- 启动、暂停、停止和重查均交给纯命令处理器，再通过公开 Repository 读取/写入持久状态。只有 `READY/RUNNING/PAUSING/STOPPING` 允许服务继续存活。
- 通知暂停/停止复用 `GenerationControlRepository`；若活动执行器尚未完成安全点，服务最多等待 5 秒后退出，但持久控制意图保留，后续恢复器继续结算。
- Android 15 `onTimeout` 使用应用生命周期协调器写入 `SYSTEM_FGS_TIMEOUT`，避免服务协程随 `onDestroy` 被取消；硬停止计时不等待数据库无限期完成。
- 系统限时恢复只回 `READY`，不产生 Attempt、不签发发送权、不调用 Provider。TASK-049 已把恢复/维护调度接入这条边界。

## 18. WorkManager 恢复/维护契约

1. `GenerationRecoveryMaintenanceWorker` 只能调用本地 `GenerationMaintenanceOperations`：扫描、请求前回队、无 Provider 审计、控制结算和草稿清理；接口中不存在 `generate`、模型连接、请求编码或发送许可。
2. 扫描要求当前 Stage 与父 Job 的租约都精确过期。只有 `RUNNING + PREPARING` 可在无 Attempt 时原子回到 `READY`；Stage 与父 Job 必须一起释放，避免父租约遗留导致任务永久阻塞。
3. `REQUEST_INTENT_RECORDED/STREAMING/VALIDATING/COMMITTING` 的运行中现场使用 `ProviderRecoveryEvidence.NOT_AVAILABLE` 进入既有恢复策略。RequestIntent 不能被当作“肯定未发送”，维护器不查询远端、不创建第二个 Attempt。
4. `PAUSING/STOPPING` 仅在仍属于网络活动阶段且存在 Attempt 时结算过期控制；本地校验/提交证据不足的现场保持延后，不猜测结果。
5. 每批默认 50、硬上限 100；候选错误互不覆盖，取消向上传播，临时失败最多重试两次。维护输出只有计数，`toString` 和 WorkManager Data 不包含书名、正文、连接、Job/Stage/Attempt ID。
6. WorkManager 的排队状态不是小说任务事实。重装、系统取消或 Worker 成功/失败都不能直接把 Job 标记为完成；SQLCipher 中的 Job/Stage/Attempt/Usage/提交证据仍是唯一依据。

## 19. Prompt Bundle v1 与阶段/场景契约

### 19.1 版本与绑定

- Bundle：`zhijuan.prompt-bundle.v1`；契约 schema：1；支持的创建快照 schema：1。
- 绑定输入只来自不可变创建快照，包含来源哈希、篇幅、已解析呈现档位、题材维度和冻结连接引用。绑定使用长度前缀的固定字段序列计算 SHA-256，随机 ID 和时间不参与。
- `unassigned-before-generation` 仍只表示创建时尚未分配。只读绑定不会回写快照，也不会创建 Job；后续真正创建 Job 时须把当前 Bundle 版本冻结到任务记录。

### 19.2 阶段契约

13 个 `GenerationPhase` 按枚举顺序全部覆盖。每个阶段固定：模板 ID、执行器类型、所需上下文层、输出 schema ID、是否流式、场景策略和阶段指令。`NORMALIZE_INPUT`、`ASSEMBLE_CONTEXT`、`COMMIT_CHAPTER` 为纯本地阶段；其余阶段只允许结构化远程结果，其中正文阶段允许结构化流。

Provider 前准备桥逐项把内部层映射为已有 `PromptLayer`，不存在自由拼接或未知层回退。计划对象的默认字符串只显示阶段、schema、层数和指令数，隐藏指令正文与绑定哈希。

### 19.3 场景执行

`BUILD_CHAPTER_PLAN`、`DRAFT_CHAPTER`、`CHECK_CONSISTENCY`、`REVISE_CHAPTER` 统一应用场景策略。相关场景只有在成年事实为 `CONFIRMED_ADULTS` 时可准备；否则返回阻断对象，不能生成降级远程请求。

严格档自动要求：计划内关键过程覆盖率 100%、淡出替代 0；禁止用黑屏、时间跳跃、突然转场、醒来后或事后概述替代；持续追踪位置、姿势、衣着/物品、接触、动作—反应因果、触觉/视觉/声音/呼吸与节奏/语言/心理、疲劳/疼痛/兴奋/伤势等相关身体状态及剧情余波。规则只在相关场景装配，不机械轮播感官，也不提高冲突、伤害、语言或压迫尺度。

用户没写年龄时，故事种子和圣经阶段可为新建虚构人物自动建立明确成年事实；明确未成年、真实人物或年龄矛盾必须失败关闭。该自动化不新增默认 UI 操作。

## 20. TASK-051 初始规划结构化结果

### 20.1 三份结果

1. `story-seed.v1`：核心承诺、主冲突、结局方向、目标章数和初始虚构人物；
2. `story-bible.v1`：引用种子 hash，固化人物、世界规则、硬事实及各自来源；
3. `master-outline.v1`：引用圣经 hash，以少量宏观节拍连续覆盖第 1 章到目标末章。

三者均执行“Provider schema 约束 + 本地严格验证”双层门禁。Provider 声称支持结构化输出并不替代本地校验。

### 20.2 逐阶段一致性

- 种子校验只依赖冻结篇幅；圣经校验依赖已成功种子；总纲校验依赖已成功圣经和冻结篇幅；最终可以再对三份结果做全链复核。
- 圣经中的种子人物不得被更名、换 ID、改成人状态或改年龄；涉及亲密关系的人物必须虚构、明确成年且事实一致。
- 世界规则与硬事实 ID 唯一，引用实体必须存在。总纲节拍按顺序相接，不允许缺章、重叠、从 0 开始或越过目标。
- 本阶段只解决宏观规划，不把 300 或 10,000 个章节明细一次交给模型。分卷和章节窗口由 TASK-052 负责。

## 21. TASK-052 分卷与有限逐章窗口

### 21.1 本地范围选择

`ArcPlanningWindowPolicyV1` 是 Provider 前硬边界：

- 当前卷最多 40 章，并被当前 master-outline beat 截断；
- 当前逐章窗口最多 8 章，并被当前卷结尾截断；
- `nextWindowStartChapter` 只能是当前窗口结束章 + 1，或在全书结束时为 `null`；
- 当 `plannedThrough - currentChapter <= 3` 且尚未到全书结尾时才需要补窗。

### 21.2 `arc-plan.v1`

结果包含总纲 hash、父修订 hash、目标章数、当前卷的范围/问题/进入与退出状态/里程碑/连续性约束，以及当前窗口每章的目标、冲突、转折、结果、钩子和状态承接。Provider schema 最多允许 8 个章节条目，本地再校验精确序列与冻结范围。

每个窗口形成一份新的不可变 outline revision：BOOK 根节点描述窗口，ARC 节点描述当前卷，CHAPTER 节点仅对应本窗口。父 revision 保留旧窗口，避免每次复制全书计划。详细章内场景和正文仍属于后续阶段。

## 22. TASK-053 第一章快车道与推进门禁

### 22.1 两条首章路径

- `FAST_LANE`：第 1 章依赖已提交故事种子、`first-chapter-bootstrap.v1` 与成年人/硬规则门禁。Bootstrap 固定为故事种子人物表、1–24 条核心世界规则、相同结局方向、第 1–3 章连续粗计划，以及首章 POV、开场、1–12 个场景序列、收束和钩子。
- `FULL_PLANNING`：第 1 章直接依赖故事圣经、全书总纲和覆盖第 1 章的窗口，不需要最小包。

Bootstrap schema 所有对象关闭未知字段，本地拒绝非法 UTF-8、重复键、人物集合/年龄/成年状态/虚构身份变化、结局改写、非连续三章和真实可识别亲密人物。提交时从加密种子 artifact 重新计算并交叉验证，不能信任调用方传入的“已校验”布尔值。

### 22.2 第二章及后续硬闸门

`ChapterProgressionGateRepository` 只从 SQLCipher 事实签发短生命周期 permit：

1. 当前故事圣经和总纲阶段已经成功，并能沿不可变 outline parent 链找到覆盖目标章的当前窗口；
2. 目标章紧邻的上一章存在当前已提交版本；
3. 若第一章走快车道，当前圣经和总纲的冻结输入都绑定第一章当前版本 ID 与内容 hash；
4. permit 中的规范证据 JSON 与 SHA-256 在 Provider 打开前重新计算；正式章节提交事务再复核一次。

第一章后规划由独立 Bible → Master Job 起步，再进入既有窗口 Job。第一章内容变化会改变两个完整规划阶段的输入 hash 和幂等键。旧第一章版本、伪造 gate hash、缺失窗口或尚未成功的规划阶段都不能打开第二章 Provider。

## 23. TASK-054 章前上下文预算组装

### 23.1 版本与预算算法

`zhijuan.chapter-context-policy.v1` 对 `BUILD_CHAPTER_PLAN` 的输入执行确定性预算。估算以最终规范 Provider payload 的 UTF-8 字节数作为保守 token 上界，再加 128 token 协议封套预留；安全余量为模型上下文上限的 10% 向上取整；输出预留取请求值与模型已知最低要求中的较大者。可用输入预算为：

`contextLimit - outputReserve - safetyReserve`

容量未知或未确认时失败关闭；用户明确确认未知模型后只采用 8192 的保守上下文上限，不能从模型名称猜测容量。

### 23.2 选择顺序

必需项包括应用硬规则、阶段契约、写作/呈现规则、人物成年及身份事实、世界规则、禁改事实、目标卷与章节计划、第二章起的上一章摘要；若存在，当前人物/世界状态、到期伏笔和冻结的本次用户补充也升级为必需项。任何必需项都不得裁剪。

可选项按稳定优先级依次考虑主题、较旧章节摘要、运行事件与时间线、未到期伏笔和远期计划。每项只能完整选入或完整省略。规范排序、内容哈希、选择原因和省略原因均写入 `chapter-context-manifest.v1`，同一冻结输入可精确重放。

### 23.3 提交与失效

本地 `ASSEMBLE_CONTEXT` Stage 使用 `PREPARING → COMMITTING → SUCCEEDED`，成功后原子写入不可变 `ContextSnapshot` 并激活对应 `BUILD_CHAPTER_PLAN`。本地阶段不创建 `RequestAttempt` 或 `UsageLedgerEntry`。容量未知或必需项超限时 Stage 进入 `BLOCKED`，Job 进入 `NEEDS_ACTION`；若此前正在暂停，则落为 `PAUSED`。

Provider 打开前从 snapshot 的 manifest 重建 payload 并复核其 hash，同时重验 Prompt Bundle、Story Bible head、Outline head、目标窗口/卷章、上一章当前版本和章节推进证据。任一来源变化都会拒绝旧上下文，不能拿陈旧快照联网。

## 24. TASK-056 章节记忆提取

### 24.1 两种合法来源

- 正常生成链：正文停止续接并冻结后，预分配最终 `ChapterVersionId`，以该 ID、章节 ID/序号和正文 SHA-256 绑定 `chapter-memory.v1`。提取结果仍是候选证据；若 TASK-059 修订正文，hash 改变后必须重新提取，旧结果不能进入最终事务。
- 记忆重建：来源必须是数据库中的当前正式 `ChapterVersion`。RequestIntent 落库后、Provider 真正打开前再次检查当前版本和内容 hash；提交前再检查一次。任一变化都拒绝旧请求。

TASK-056 已完成通用提取契约/请求/解析/映射，以及“当前正式版本重建”的完整提交闭环；TASK-058 已补齐候选检查。正常候选正文的有限修订、修订后重提取和正文+派生数据最终原子提交由 TASK-059 接线；在此之前不把候选提取冒充正式运行记忆。

### 24.2 `chapter-memory.v1`

输出只能是单个严格 JSON object，必须回显精确来源版本、正文 hash、章节 ID 和序号。包含：

- 章节摘要：目标结果、关键事件、决定、关系变化、结尾状态、未解决问题和重要度；
- 人物事件：位置、身体、情绪、目标、知识、关系、持有物、承诺、秘密；
- 事实：世界事件、人物状态、关系、发现、持有物、承诺、秘密和地点。

最多 128 条人物事件和 128 条事实，只能引用已知实体。关系事件必须引用另一已知实体；重复事件/事实拒绝。章节派生结果只能声明 `STORY_CANON` 或 `INFERRED`，不能把模型提取升级成 `HARD_CANON`。涉及高强度场景时必须保留会影响后文的身体、情绪、关系、知识及余波变化，不能用空泛概述替代，但不复制大段正文。

### 24.3 提交与费用

正式版本重建把摘要、人物事件、事实、FINAL Usage、Stage 和 Job 在一个 SQLCipher 事务中提交；稳定 ID 和完整 payload hash 支持精确重放。提交许可同时验证加密 artifact 修订、原始 hash 和规范 JSON hash，防止映射结果与已校验输出脱钩。

结构错误允许一次有界修复。格式失败进入修复或人工处理的同时，原 Attempt 的实际/未知 Usage 立即最终结算，避免临时预算长期占用或自动重试低估费用。时间线和伏笔不在本阶段生成，由 TASK-057 从同一来源版本继续投影。

## 25. TASK-057 时间线与伏笔投影

### 25.1 阶段子契约与来源

`EXTRACT_MEMORY` 是派生记忆的阶段总类。默认契约仍是 `chapter-memory.v1`；当 Stage 冻结的 `outputSchemaId=chapter-story-tracking.v1` 时，路由到专用请求、严格解析、Provider-open 来源门禁和提交仓库。路由不依赖提示文字猜测，也不能把两种 schema 的产物交叉提交。

投影来源固定为：最终章节版本 ID/正文 SHA-256/章节 ID/序号、同版本章节记忆快照 hash、此前有效伏笔快照 hash、当前故事实体快照 hash。四组快照在发送前与提交前重新计算。

### 25.2 时间线规则

- 每章最多 64 个事件；只记录已发生且影响故事顺序或后续约束的事件。
- 参与者只能引用已知实体；地点非空时必须引用 LOCATION 实体。
- 本章内顺序使用 `chapterIndex * 1,000,000 + localIndex` 的确定性键；约束与证据作为结构化 JSON 保存。
- 输出不得续写、评价或大段复制正文，高强度场景仍须保留真正发生的先后、地点、参与者及相关身体/关系余波。

### 25.3 伏笔规则

| 操作 | 允许旧状态 | 新状态 | 附加条件 |
|---|---|---|---|
| PLANT | 无 | PLANTED | 本章已出现的具体新线索；可选回收区间必须在后续章节 |
| DEVELOP | PLANTED/DEVELOPING | DEVELOPING | 必须有本章发展证据 |
| RESOLVE | PLANTED/DEVELOPING | RESOLVED | 必须有本章回收证据 |
| ABANDON | PLANNED/PLANTED/DEVELOPING | ABANDONED | 正文明确证明不可能且置信度必须 1,000,000 |

既有条目必须精确回显 ID、描述、重要度和旧状态，可见实体集合只能扩展。每条在一章最多转换一次；新线索不能与既有或本章新线索重复。

### 25.4 持久化与失效

`foreshadow_item` 是当前状态投影，`foreshadow_transition` 是不可覆盖的来源台账，`chapter_tracking_projection` 是整章投影头。三者和时间线、FINAL Usage、Stage/Job 在一个 SQLCipher 事务提交，payload hash 覆盖每一行；精确重放只回读相同结果。

替换章节版本会失效该版本的时间线、投影和转换，并把任何曾经依赖该转换的当前伏笔标为 STALE。为了防止中间状态依赖泄漏，当前重建入口只允许没有后续已提交章节的安全位置；TASK-061 再提供从修改点向后的自动顺序重建。

## 26. TASK-058 一致性与呈现检查

### 26.1 双层检查

`ChapterLocalConsistencyChecker` 先在本地完成可确定判断：精确正文 hash、正文边界、代码围栏/异常标题、长段精确重复、实体/成年人事实、死亡或离场回归、地点移动、物品归属、时间顺序和计划必需事件。这里不使用模糊相似度，也不调用 Provider。

本地通过后，`chapter-consistency-report.v1` 检查 23 个固定标准。每个标准必须按策略给出的顺序恰好出现一次；问题最多 128 条，且每个问题必须被一个标准准确引用。模型不得新增标准、改变严重度、自由提出改写稿或返回正文证据。

### 26.2 场景模式

- `NOT_APPLICABLE`：场景不涉及该呈现约束，不要求过程/淡出证明。
- `PROPORTIONAL`：检查相关身体/状态/余波连续性，但不错误要求关键过程覆盖率 100%。
- `STRICT`：仅在场景契约已冻结关键过程、要求避免淡出且所有相关人物均通过成年人/虚构身份门禁时启用；每个过程节点按原顺序返回 COVERED/MISSING，要求 100% 覆盖，并检查动作、空间、身体、感官和余波。

严格条件不完整时不会由调用方布尔值强行开启；成年人门禁失败直接在发送前阻断，不降级成留白模式。

### 26.3 接受门禁与边界

本地与模型结果合并后，只要存在 blocker 或 major 就产生 `REVISE_CANDIDATE`；仅 minor 或无问题才产生 `ACCEPT_CANDIDATE`。检查报告映射为绑定候选版本的 `ConsistencyReportEntity` 草稿，内容不含正文或证据原文。

TASK-058 不创建最终 `ChapterVersion`，因此也不单独插入带外键的报告。TASK-059 必须在有限修订完成、正文 hash 稳定、记忆/追踪重新提取和最终检查均有效后，把正文及所有派生数据原子提交。固定 TEST-039 只验证契约/门禁，不替代真实模型中文质量基准。

## 27. TASK-061 编辑后的派生历史与重建边界

### 27.1 schema v11 历史槽

同一个保留的章节版本允许存在多代摘要和 tracking，同一本书同一截止章节允许存在多代聚合投影，同一伏笔/来源章节允许存在多代转换。旧代必须先由 `VALID` 变为 `STALE`，再插入新代；数据库触发器并发保证每个业务槽最多一个 `VALID` 当前头。

摘要、人物事件、事实、时间线、tracking、聚合和伏笔转换均禁止删除历史、禁止篡改来源或内容、禁止 `STALE → VALID`。唯一允许的历史行变更是内容不变的 `VALID → STALE`；tracking 的 `generation_stage_id` 仍保持唯一，因此旧 Stage replay 没有被放宽。

### 27.2 权威读取与历史读取

生产上下文和重建影响计划只读取 `VALID` 且属于 current chapter version 的权威投影；审计接口使用名称明确的 `*History*` 查询读取稳定排序的全部代。不得用 `associateBy` 或无状态条件的单行查询从混合历史中随机选择一代。

### 27.3 schema v12 伏笔 after-state revision

Phase 2B2A 新增不可变 `foreshadow_projection_revision`：每次 PLANT/DEVELOP/RESOLVE/ABANDON 成功后，共享数据库 writer 都从真实 post-CAS `foreshadow_item` 读取完整状态，以规范 JSON 与 SHA-256 封存全部 current 字段，并绑定书、章节版本、Stage、transition、章节序号与 story order。tracking 独立提交和最终候选原子发布共用同一 writer；模型 partial DTO 不负责补全快照。

revision 只允许内容不变的 `VALID → STALE`，禁止恢复、篡改和删除；transition 失效前必须先失效其 VALID revision。Stage replay 以 revision hash/规范 JSON/完整 provenance 校验旧 after-state，不把后来已变化的 current item 强行当成旧状态，也不会用旧 Stage 覆盖后来合法更新的伏笔搜索索引。v11→v12 只创建空账本，不根据不完整 transition 猜测旧快照；legacy 缺账在 replay/未来 rewind 时失败关闭。

### 27.4 schema v13 受审计 rewind

Phase 2B2B 新增不可变 `foreshadow_projection_rewind` 审计行和 `ForeshadowProjectionRewindRepository`。执行前在同一 Room 事务中重算并核对完整 `planHash`，确认编辑版本为当前 `USER_EDIT`、其 parent 是被替换版本，并冻结编辑点至最新正式章的全部 transition 历史与当前伏笔集合。

每个受影响伏笔只允许使用编辑点之前、绑定当时 current chapter version 的最后一个 `VALID` 完整 revision 作为可信基线。执行顺序固定为先失效区间 revision、再失效 transition，断言区间内不再残留 `VALID` 历史，然后以全字段 CAS 恢复可信基线；区间内首次 PLANT 且不存在于编辑点前的伏笔改为 `STALE`。若 legacy 历史缺少可信 revision，只有区间第一条操作为从 null 开始的 PLANT 时才能证明“此前不存在”，其余情形整笔失败关闭。

受影响伏笔的 FTS 指针全部删除，只为成功恢复的可信基线重新索引。最终 current projection 集合、基线集合、受影响范围、计数和策略版本以 SHA-256 绑定到不可变审计行；同一 rewind ID 的精确 replay 零写入返回，另一个 rewind ID 不能占用同一 plan。已经由 Phase 1 标为 `STALE` 的区间新生伏笔保留原失效时间，不在 rewind 时伪造新的更新时间。

### 27.5 Phase 2B3A 聚合状态重建

`AggregateStateWriterRepository` 只执行计划中单个已就绪的 `REBUILD_AGGREGATE_STATE` 步骤。它在同一 Room 事务内重验冻结章节范围、目标 current version/正文 hash、同章有效 tracking 代次和全部权威来源；payload 只保存每个实体属性的最新状态与当前活动伏笔，不复制章节正文、摘要历史、时间线历史、Provider、Attempt、Usage、模型元数据或提示词。

`zhijuan.aggregate-state.v1` 采用严格 canonical JSON、128 KiB 总上限、256 个实体属性状态和 128 个活动伏笔上限。聚合来源同时绑定 tracking projection/stage、记忆快照、前序伏笔快照、tracking 输出和 payload hash，防止 tracking 已换代时把旧聚合误判为已满足。上一章聚合只作为执行顺序栅栏，当前章内容始终从权威表重算；旧槽先转为 `STALE` 再插入新 `VALID` 代，精确 crash replay 零写入返回。

### 27.6 Phase 2B3B1 不可变执行准备账本

schema v14 新增 `chapter_edit_rebuild_execution` 与 `chapter_edit_rebuild_step`。`ChapterEditRebuildExecutionRepository.prepare` 在一个外层 Room 事务中先执行或精确重放受审计 rewind，再次核对冻结计划和所有 current 章节，最后写入不可变执行 fence 与关键步骤基线。任何在 rewind 之后发现的时间倒退、来源变化、唯一身份冲突或写后回读不一致都会回滚 rewind 与账本，不留下“伏笔已回退但没有可恢复工作”的半成品。

执行身份来自 `zhijuan.chapter-edit-rebuild-execution.v1` 的稳定 fence，而不是会随合法步骤进展变化的 `planHash`。fence 覆盖编辑/被替换版本、完整受影响 current 章节身份与内容 hash、rewind 的 before/baseline/after 集合证据，以及准备时实际存在的摘要、tracking、aggregate 头和其全字段指纹；`initialPlanHash` 仅保留为准备时诊断证据。一个编辑版本、rewind 或稳定 fence 只能绑定一份账本，精确 replay 零写入。

准备账本只建立“编辑章 memory + 每章 tracking + 每章 aggregate”的关键链，已满足步骤记录精确基线，其余为 `PENDING`。它不会提前创建所有 Stage：后续章节的 tracking 输入依赖前一章真实输出，而 Stage 来源在创建后不可变，所以每个 Stage 必须在直接前驱真实落库后动态创建。本阶段 Job/Stage/Attempt/Usage/Provider 调用均为 0。

### 27.7 Phase 2B3B2A 首个 edited-memory Stage

`ChapterEditRebuildStageRepository.createEditedMemoryStage` 已能从 v14 ledger 的首个 `PENDING EDITED_MEMORY` 步骤原子创建一个确定性单步 Job/Stage。Job/Stage ID 只由 execution stable fence、step ordinal/type、目标章和来源版本/hash 派生；同一命令精确 replay 零新增，不同用户意图、预算、时间或既有身份占用失败关闭。创建事务重验 execution、完整 current 影响范围、初始 planHash、目标 summary 尚未出现和时间下界；它不创建 Attempt/Usage，也不打开 Provider。

绑定 memory Stage 使用严格 `schemaVersion=2` 输入并增加 `chapterEditRebuild`：policy、execution、stable fence、step ordinal/type、chapter index、source version/content hash 全部进入 `inputSourcesJson` 与 input hash。普通无绑定 `chapter-memory.v1` 仍保持原 v1 JSON 和哈希语义。`GenerationRequestAuditRepository` 在 Provider-open 前重验专用许可，`ChapterMemoryExtractionCommitRepository` 在写业务表前再次重验；任一来源、顺序或确定性身份变化都失败关闭。

### 27.8 Phase 2B3B2B1 第一章 tracking Stage

`ChapterEditRebuildStageRepository.createFirstTrackingStage` 只处理 ledger 中 ordinal 2 的编辑章 tracking。它先证明 ordinal 1 memory 已真实满足：准备时已有的 `SATISFIED` memory 必须保持完整全字段指纹；准备时为 `PENDING` 的 memory 则必须同时存在确定性 Job `COMPLETED`、Stage `SUCCEEDED`、最新 Attempt `SUCCEEDED`、Usage `FINAL`、严格 output reference，以及与该 output reference 匹配的权威 summary/event/fact 行。只看到一条 summary 不足以解锁 tracking。

tracking 的正文、memory、伏笔和实体来源在解锁时重新从权威表读取并冻结进严格 v2 Stage input。普通 `ChapterTrackingProjectionSourceRepository.loadCurrentVersion` 继续拒绝中间章之后存在已提交章节；只有 execution、stable fence、ordinal、版本范围和 memory 前驱全部匹配的 TASK-061 binding 才能调用 `loadForEditRebuild`。Provider-open 和 tracking commit 都先执行重建授权，再走同一专用来源复核；普通 tracking 路径没有被放宽。

Fake Provider 端到端测试已实际完成“绑定 memory Stage 请求审计→流式结果→严格解析→Attempt/FINAL Usage→memory 原子提交→第一 tracking Stage 创建”，不是用手工 summary 冒充远程成功。tracking Stage 本身仍只创建 Job/Stage，不创建 Attempt/Usage或调用 Provider。

### 27.9 Phase 2B3B2D 首个保留章节 tracking→aggregate 闭环

schema v15 retirement-bound replacement Stage 现在已能通过 Provider-open 与 commit 双门禁。门禁逐次回读 immutable execution/step、退役证据、完整 current 区间、前一章 rebuild tracking+aggregate、确定性 Job/Stage identity 和当前权威来源；只有全部一致时才允许构造或发送 tracking 请求。普通 tracking 路径和其顺序保护没有放宽。

planner 只把同时满足下列条件的新 tracking 认作 `ALREADY_SATISFIED`：projection 的 `generationStageId` 精确指向 retirement 绑定的 replacement Stage，Stage/Job 处于合法提交或完成状态，Stage binding 与 execution/step 完全相同，且旧 tracking/timeline/search 的退役证据仍可复核。仅有同一章节的一条任意 `VALID` tracking 不足以取得执行身份。

Fake Provider 已走通第一个保留章节的请求审计、流式响应、严格解析、Attempt/FINAL Usage、tracking/timeline 写入和同章 aggregate 原子提交；成功 replay 不重复写入。故障注入证明 aggregate 拒绝时，新 tracking、timeline、aggregate 与最终结算整体回滚，而已完成的旧基线 retirement 保留，Stage 停在可恢复的 `COMMITTING`。

### 27.10 Phase 2B3B2E 通用保留章节循环

`ChapterEditRebuildRetainedTrackingStageCommand` 要求显式提供偶数 `targetStepOrdinal`，最小为 4；目标章节、版本和步骤类型仍只从不可变 execution ledger 推导。ordinal 4 的旧入口保留为兼容包装，ordinal 6 及以后统一走显式目标入口。创建任一 replacement Stage 前必须证明直接前一章的确定性 tracking Stage/Job、权威 projection 与同章 aggregate 已完成，且其完成时间不晚于当前 Stage 的创建时间；不能跳章、猜测“下一步”或预建尚无真实来源的未来 Stage。

Provider-open、commit 与 planner 只接受从 ordinal 4 开始连续存在的 retirement evidence 前缀。任一缺口、章节错位、时间倒退、replacement identity 不一致或证据损坏都会停止授权；不同 ordinal 的精确 replay 独立收敛。10 章编辑第 3 章的 Fake Provider 固定场景已经按 ordinal 4–16 重建第 4–10 章，最终得到 7 条 retirement、8 条旧 `STALE` tracking、8 条新 aggregate，并保留第 4–10 章正文/current version。

TEST-033 同时由生产上下文选择器验证：用户编辑后旧摘要转为 `STALE`、旧 FTS 指针删除，只选择绑定新 current version 的 replacement summary。旧派生历史可以保留审计，但不会重新进入权威上下文。

### 27.11 执行收口与 runner 边界

`chapter_edit_rebuild_execution` 保持不可变准备证据，当前 `PREPARED` 不是遗漏的可变工作流状态。完成性由冻结步骤对应的权威 memory/tracking/aggregate 与 planner 重新推导，不新增可能和真实业务表漂移的“完成”字段。TASK-061 的失效、有序重建原语和 TEST-033 已完成；自动选择下一步、重启续跑、双执行器收敛、context/consistency 阶段调度及整 App phase dispatcher 归 TASK-064 total runner，不得描述为本阶段已实现。

## 28. TASK-062 脱敏生成时序

每次章节运行使用独立 `runId`，但正式库只保存域分离的 24 位十六进制指纹。有限阶段为 `CHAPTER/CONTEXT/BODY/MEMORY/TRACKING/CONSISTENCY/REVISION/COMMIT`，有限 milestone 覆盖章节请求、Stage 排队/开始、本地上下文完成、Provider 打开、首字节、首个完整段落、正文流结束、三类派生与可选修订开始/结束、正式提交和下一章启动。阶段是事件身份的一部分，BODY 与派生阶段的同名 Stage 事件不会混算。

持续时间只使用 `SystemClock.elapsedRealtime()`；epoch 只用于展示。事件同时保存由 Android boot count 派生的 boot 指纹，读取失败时退化为进程会话指纹。同一 boot 且单调值不回退时才计算 duration；跨 boot、缺事件、终态不成功或单调时间回退均返回明确 `Unavailable`，不得用墙上时间猜测或生成负数。

报告器分别输出排队、本地准备、Provider→首字节、Provider→首个完整段落、成功正文流、MEMORY/TRACKING/CONSISTENCY、可选 REVISION、派生总和、正式提交、全章与下一章间隔。Stage/Attempt 的起止事件必须按相同指纹配对；失败、拒绝、未知结果、暂停和取消写有限终态，不把失败误成“没有观测”。

首段探测器只保留“当前段是否已有非空白字符”、累计 Unicode code point 数和是否已观察完整段落；正文、人物、提示词、端点、Provider request id、异常自由文本、secret 和原始业务 ID/hash 均不进入时序事件。当前生产接线只覆盖正文流式执行器；全部 phase 的自动发射由 TASK-064 total runner 接入，固定延迟、慢流和断流基准由 TASK-063 提供。

## 29. TASK-063 确定性 Fake Provider 性能夹具

`provider:fake` 是独立 JVM 测试模块，不进入 App 或正式生成 feature 的 `implementation` 依赖图。脚本只允许有限 Wait/Started/Text/Structured/Usage/Heartbeat/Completed/Refused/Failed 步骤；构造时拒绝负时间、溢出、重复 Started、多终态和终态后事件。无终态脚本按意外 EOF 原样结束，adapter 不擅自补成功或失败。

虚拟时钟在协程调度点后单调推进，几分钟慢流不使用 `Thread.sleep` 或真实长 `delay`。每次调用只累计本调用已经完成的 Wait，避免并发调用从共享时钟误算彼此耗时；取消 collection 和显式 `cancel()` 分开统计。统计只保存有限事件数、字符/token 数和虚拟毫秒，不保存正文、prompt、endpoint、secret 或 request id。

20 个实际 Fake 参考正文负载为 2,500～3,450 Unicode 字符。BODY 报告固定得到首字节 P50/P95/最慢 10.9/11.8/11.9 秒，首个完整段落 18.35/19.70/19.85 秒，正文结束 147/174/177 秒。分位数采用 nearest-rank；失败、缺事件、跨 boot 与 NotApplicable 分开计数，不能从样本中静默剔除。当前 20 次正式提交均明确为缺事件，因为 TASK-064 total runner 尚未接通。

## 30. TASK-064 Phase 1A：空闲 RUNNING Job 崩溃恢复

total runner 不新增第二套持久游标。`generation_job.current_stage_id`、Job/Stage 状态、两层租约、Attempt 链和现有业务提交事务继续作为唯一恢复事实；Stage 成功、动态后继创建、后继激活和 Job 游标推进仍只能由既有业务仓库在同一 Room 事务内完成，runner 不得先推进游标再猜提交成功。

已关闭第一个确定的崩溃窗口：Job 已由 `READY` 领取为 `RUNNING`，但当前 Stage 仍为 `READY` 且尚无 Stage lease 时进程退出。维护扫描只选择 Job lease 已过期、current Stage 精确属于该 Job、Stage 仍 READY 且三项 lease 均为空的行；恢复事务重读并匹配 Job lease owner/acquired/heartbeat、current Stage 和 Stage 无租约事实，再用专用 CAS 把 Job 恢复为 READY。Stage、Attempt、重试计数、错误码和时间均不改写。

该子阶段不等于 total runner 已完成。正常 RUNNING Job 的同 owner 续跑、双层 heartbeat、contract-aware dispatcher、各 Stage executor、RETRY_WAIT、全 phase 时序和 Fake 第一章闭环仍由后续 TASK-064 子阶段实现。

## 31. TASK-064 Phase 1B：持久 runner queue 与 Job 续租

`GenerationRunnerQueueRepository` 只扫描 `READY Job + current READY Stage`。查询同时要求 Job 与 Stage 的三项 lease 字段全空，受 `observedAt` 快照、有界 limit 和 `job.updated_at/job_id` 稳定顺序约束；公开候选只含领取时精确复验所需的有限 identity/status/updatedAt，日志字符串隐藏业务标识。

领取在单一 Room 事务内重读候选并逐项复验 Job 状态、current Stage、双方 updatedAt 和无租约事实，再复用原有 Job lease CAS。两个 runner 对同一候选并发领取时只能一个成功；领取只把 Job 变为 RUNNING 并写 Job lease，Stage、Attempt、attempt count 和业务游标保持不变。异常 READY Job 若残留任一 lease 字段，不进入队列。

同一运行实例必须持有内存中的精确 `GenerationLeaseToken`，通过 `heartbeatAndLoadCurrentStage` 续 Job lease 后读取最新 current Stage。业务提交事务把游标从 Stage A 推进到 Stage B 后，原 Job token 继续有效，不需要也不允许重新领取 Job；进程重启不得仅凭 owner 字符串收养旧 lease，过期 lease 由 Phase 1A 维护路径回收。

Phase 1B 仍不获取 Stage lease、不创建 Attempt、不调用 Provider、不解析 frozen contract。Stage lease 与双层 heartbeat、contract-aware dispatcher、RETRY_WAIT/控制状态和 Fake 第一章闭环继续由 TASK-064 后续子阶段完成。

## 32. TASK-064 Phase 1C：current Stage 原子领取与双层 heartbeat

`GenerationRunnerExecutionLeaseRepository` 只服务已经持有精确 Job token 的当前 runner。领取前必须证明 Job 仍为 RUNNING、token owner 与 runner owner 相同、`current_stage_id` 精确指向目标 Stage、Stage 属于该 Job 且仍 READY/无 lease；在单一 Room 事务中先续 Job heartbeat，再复用既有 Stage lease CAS。若 Stage 时间、状态或竞争证据失败，Job heartbeat 随事务一起回滚。

活跃执行可用 `heartbeatCurrentExecutionLeases` 在同一事务续两层 lease。Job 可处于 RUNNING/PAUSING/STOPPING，Stage 必须仍是 current 且持同 owner 的精确 Stage token；任一 token 错误、timeout 临界、cursor 变化或第二次 heartbeat 失败都会整笔零写入。该原语不替代流式 executor 已有的 Stage heartbeat，也不获得 Attempt、Provider 或业务提交权限。

Phase 1C 只完成原子数据库原语，尚没有按 15 秒调度 heartbeat 的协程执行包络。后续先实现可取消、可确定性测试的 heartbeat envelope，再接 frozen contract/schema-aware dispatcher。

## 33. TASK-064 Phase 1D：heartbeat scheduling envelope

`GenerationRunnerHeartbeatEnvelope` 在一个 Stage action 存活期间按默认 15 秒间隔调用 Phase 1C 的原子双 lease heartbeat。action 是 Provider、Attempt、校验与业务提交的唯一所有者；envelope 只管理生命周期，action 一完成或抛错就不再发 heartbeat，父协程取消会通过结构化并发取消 action 和等待器。

heartbeat 失败时不能一律取消 action：如果权威 Job 仍持原 token 且 current Stage 已从 A 推进到 B，或 Job 已进入 COMPLETED/PAUSED/STOPPED/NEEDS_ACTION/BLOCKED 且 lease 已清除，说明业务事务已经越过旧 Stage 的持租约边界。此时 envelope 停止旧 heartbeat并等待 action 返回，避免把成功提交误杀。若 Job 仍是同一 Stage、token 已丢失/过期或检查失败，则取消 action并把原 heartbeat 失败交给 runner。

等待器与时钟可注入，JVM 测试用手动 tick，不真实等待 15 秒。Phase 1D 尚未选择 executor；下一步 dispatcher 必须先解析 frozen contract/schema identity，再把已获取 Stage lease 与 envelope 交给唯一 executor。

## 34. TASK-064 Phase 2A：派生链冻结 route identity

`GenerationRunnerStageRouteResolver` 不把 `GenerationPhase` 当作 executor 身份。它先从严格 JSON object 中只读取 `sourcePolicyVersion`，随后必须交给现有 memory、tracking、candidate 或 final-commit 权威 parser 做完整校验；schema、root keys、phase/target、targetId、inputVersionHash 和来源 binding 任一不一致都会失败关闭。

当前有限集合明确区分普通 memory、编辑重建 memory、普通 tracking、编辑重建 tracking、候选正文、候选 memory、候选 tracking、候选 consistency、候选 revision 和 final commit v3。尤其 `EXTRACT_MEMORY` 不再能同时猜成 memory 或 tracking；未知 policy、普通未绑定 draft、planning/context 和未来 phase 在本阶段都不会获得可执行 route。

本阶段只产生纯 route identity，不读取连接、不构造请求、不写数据库、不调用 Provider。下一层必须用精确 Job/Stage token 重新读取 current leased Stage，再把 route 交给有限 registry；调用方不能手工构造一个 Stage 对象绕过持久事实。

## 35. TASK-064 Phase 2B：current leased Stage route binding

`GenerationRunnerExecutionLeaseRepository.resolveCurrentStageRoute` 在同一个只读 Room 事务中重新读取 Job 和 Stage。只有 Job 仍为 `RUNNING`、Stage 仍为 current `PREPARING`、Stage 归属同一 Job、Job/Stage token 精确匹配且同 owner、两个 heartbeat 均未达到 60 秒超时临界、观察时间没有倒退、重试额度仍可用时，才会解析冻结来源并返回 route。

返回值 `GenerationRunnerCurrentStageRouteSnapshot` 同时携带 route 和授权它的精确双租约快照，构造器只对 `:core:database` 内部开放。原始 route resolver 也收紧为模块内部可见，feature 层不能用手工 Stage 或手工 route snapshot 绕过数据库事实。

该入口不续租、不写状态、不创建 Attempt、不读取连接、不打开 Provider。它只关闭“内存里看起来合法，数据库里已经失去 current/lease 身份”的竞态；下一阶段有限 executor registry 必须只接受这个绑定快照。

## 36. TASK-064 Phase 2C2：final commit exact-token bound executor

`ChapterFinalCandidateCommitStageExecutorV1.executeBound` 专门接收 total runner 已经取得的 exact Stage token。它不处理 READY、不重新 acquire，也不把调用方 token 替换成“owner 相同”的最新 persisted token；只有 PREPARING/COMMITTING 且完整 token 相等、heartbeat 未到 60 秒超时临界、时间单调时，才把同一个 token 交给唯一 final commit coordinator。

Stage 已 SUCCEEDED 时只返回 `AlreadySucceeded`，不读 artifact、不 commit。错误 acquiredAt、同 owner 新 token、READY/其他状态或过期 lease 全部在 coordinator 前失败。这样 final commit route 才能保留 Phase 2B 绑定的执行身份，而不是退化成 owner-only 授权。

旧 `execute(stageId, ownerId, at)` 保持兼容，供自身领取 READY Stage 的既有入口使用；未来 total runner registry 只能调用 `executeBound`。

## 37. TASK-064 Phase 2C3：最小有限 executor registry

`GenerationRunnerExecutorRegistryV1` 的公开执行入口只接受 Phase 2B 从 Room 返回的 `GenerationRunnerCurrentStageRouteSnapshot`，不接受裸 route、StageId 或由 feature 层手工拼装的租约。执行前再次检查 `RUNNING + PREPARING`、双租约同 owner、时间单调和 60 秒租约边界；陈旧快照不能直接触发 executor。

当前唯一注册路线是 `FINAL_CHAPTER_COMMIT_V3`。registry 将快照中的 exact Stage token 原样交给 `ChapterFinalCandidateCommitStageExecutorV1.executeBound`，不重新 acquire READY Stage，也不调用 owner-only 兼容入口。其余九条 remote route 在穷举 `when` 中逐项抛出有限的“未注册”错误，错误只含 route enum，不含 Job、Stage、owner、正文、提示词或连接信息。

本阶段建立的是失败关闭的最小接线，不是完整 total runner。memory、tracking、candidate draft/revision/derived/consistency 仍需各自补齐生产输入装配、seal/commit、恢复、防重复发送和 UNKNOWN 证明后才能注册；planning/context/普通 draft、多阶段循环和 Fake 第一章也尚未接通。

## 38. TASK-064 Phase 2D1：initial candidate draft 合同审计

`CANDIDATE_CHAPTER_DRAFT_V1` 当前不能注册。Phase 2A 使用 `ChapterCandidateStageBindingV1` 的 BODY+DRAFT 识别它，但该绑定包含尚未生成的 candidateChapterVersionId 和 candidateContentHash，并且 BODY binding 的 route/request hash 规则是为修订来源设计的。生产代码没有创建这种初始 DRAFT Stage 的工厂或调用点。

Provider-open 的 `ChapterCandidateStageSourceGuard` 明确拒绝非 `REVISE_CHAPTER` 的 bound BODY；seal 对初始 DRAFT 只接受 revisionIndex=0 而不解析 candidate source；final recovery 也要求初始 DRAFT body 的 `inputSource == null`。因此当前不是两种合法兼容格式，而是 route parser 先行、初始正文来源合同尚未落地的死路。

不能用放宽 guard、伪造候选正文哈希或沿用测试中的裸 Stage 解决。下一步必须先从已经完成的章前计划/上下文证据定义独立 initial-draft 冻结合同和生产 Stage factory；只有该合同能在请求前生成、Provider-open 与 seal/recovery 对称校验后，才设计 exact-token streaming adapter。

## 39. TASK-064 Phase 2D2：chapter-context assembly route identity

纯本地 `ASSEMBLE_CONTEXT` Stage 现在具有独立 `zhijuan.chapter-context-assembly-source.v1` 身份。policy 只写入 context Stage；后继 `BUILD_CHAPTER_PLAN` 仍保持原合同，不能因为共享同一 Job 而被误识别为本地 context route。

`ChapterContextAssemblyJobFactory.parseAndVerify` 是 repository 与 route resolver 共用的唯一冻结输入 parser。它严格验证 Stage phase/target/maxAttempts、root/context 精确字段集、固定 bundle/policy/schema、空依赖、预算边界、prompt hash、progression evidence 自哈希、章节 ID/序号一致性及完整 input hash。解析结果的字符串表示不展开 hash、预算或用户补充内容。

resolver 新增有限 `CHAPTER_CONTEXT_ASSEMBLY_V1`。Phase 2C3 registry 仍只注册 final commit；context route 在穷举分支中明确返回未注册，尚不执行 assembly。下一阶段必须增加 exact Job+Stage token 的 bound repository 入口并通过 registry/Room 测试后，才可把该纯本地 route 加入白名单。

## 40. TASK-064 Phase 2D3：chapter-context exact-token 执行与注册

`ChapterContextAssemblyRepository.assembleBound` 只接受 Phase 2B 从 Room 生成的 `GenerationRunnerCurrentStageRouteSnapshot`。它在组装和提交业务状态的同一个 Room 事务内重新读取 Job/Stage，并验证 `RUNNING + PREPARING`、current cursor、精确 Job/Stage token、同 owner、未回退 heartbeat、未过期租约以及未变化且仍有余量的 attempt 边界；任一事实变化都会在 context snapshot、Stage/Job 或后继激活写入前失败。

旧 `assemble(stageId, stageToken, at)` 保留兼容，两个入口共用同一内部 assembly/commit 路径，不复制预算、权威来源选择或状态推进。已成功 Stage 允许以原绑定快照进行只读 durable replay，返回现有 snapshot，不重复激活 chapter-plan。

`GenerationRunnerExecutorRegistryV1` 的白名单现为 `{FINAL_CHAPTER_COMMIT_V3, CHAPTER_CONTEXT_ASSEMBLY_V1}`。context 分支把原始绑定快照和 requestedAt 原样交给 bound executor；其余九条 remote route 继续显式关闭，因此本阶段仍是纯本地切片，没有 Provider、Attempt 或 Usage。

## 41. TASK-064 Phase 2E1：普通 chapter-plan 生产合同审计

context 成功后激活的普通 `BUILD_CHAPTER_PLAN` 目前只冻结 bundle、`chapter-plan.v1`、context Stage/input/policy/manifest 与 progression gate，没有独立 `sourcePolicyVersion`。resolver 因此按设计失败关闭；生产代码也没有普通 plan 专用 route、exact-token executor、严格输出 parser/业务交叉校验、commit repository 或 initial DRAFT successor。

现有 `ChapterContextAssemblyRepository` 能在 Provider-open 前重建并核对权威 context/progression，RequestIntent、Attempt、Usage、artifact、一次格式修复和 UNKNOWN 也有通用原语，但这些不能替代 plan 专用输入身份、三层预算/目的地预留、结构化输出合同和成功重放。普通 `chapter-plan.v1` 不能借用首章 `first-chapter-bootstrap.v1` 或窗口级 `arc-plan.v1` 的业务语义。

plan 成功 artifact 不是长期权威来源：`STREAM_DRAFT` 在成功 Stage 后默认 24 小时即可清理。后续 commit 必须把经过严格解析的有界规范计划，在同一 SQLCipher 事务内冻结进动态创建的 initial DRAFT `inputSourcesJson`；plan output reference 保存 Attempt、raw/canonical hash 与 DRAFT Stage ID。DRAFT envelope 继续受 64 KiB 上限约束，规范计划目标上限为 48 KiB。本阶段只完成审计与决策，route 仍未注册。

## 42. TASK-064 Phase 2E2：普通 chapter-plan 严格来源身份

普通 plan root 现新增 `sourcePolicyVersion=zhijuan.chapter-plan-source.v1`。`ChapterContextAssemblyJobFactory.parseAndVerifyChapterPlan` 要求 `BUILD_CHAPTER_PLAN + CHAPTER`、1～4 次尝试、精确 root keys、固定 bundle/`chapter-plan.v1`、唯一且匹配的 context dependency、合法 context input hash、固定 context policy/manifest，以及自哈希一致且目标章节匹配的 progression evidence；完整 Stage input hash 也必须相等。

新有限 route 为 `CHAPTER_PLAN_V1`。resolver 只有在命中该 policy 后才调用上述唯一 parser，不按 phase 猜测，也不复用 context/bootstrap/arc parser。解析结果只携带 context Stage/hash、目标章序和 progression hash，字符串表示全部隐藏 identity/hash。

registry 对 `CHAPTER_PLAN_V1` 仍显式 `notRegistered`，注册集合保持 final+context 两项。本阶段没有 RequestIntent、Attempt、Usage、Provider、费用或状态推进；下一步先实现严格、有界的 `chapter-plan.v1` 输出合同。

## 43. TASK-064 Phase 2E3：普通 chapter-plan 严格输出合同

`ChapterPlanOutputContractV1` 现在定义普通 `chapter-plan.v1` 的唯一 schema v1。根对象严格绑定输出 policy、章节 ID/序号、ContextSnapshot 内容 hash/来源 manifest hash、章首状态、章目标、章末状态、尾钩子、连续性约束和 1～12 个有序场景；未知字段、重复 key、错误类型、非连续序号或超过 48 KiB 的 UTF-8 输出均在业务提交前失败。

每个场景冻结目的、地点、视角人物、参与人物、开场/转折/收束状态、必须承接的连续性、是否属于亲密相关场景、关键过程节点和剧情余波。严格场景的每个过程节点还分别记录动作、可观察反应、空间状态、身体状态、衣着/物品状态与视角可达的感官变化，避免后续正文只收到一句含糊的“写详细”。全章最多 64 个过程节点；严格相关场景至少 3 个有序节点，形成起始推进、状态变化和结果承接的最低可执行骨架，但不要求机械罗列固定感官或粗俗词汇。

结构 parser 与动态业务 validator 分离。业务 expectation 由 Provider 请求前的权威事实提供已知人物、已确认成年且虚构的人物集合、章节/context identity 和 `SceneExecutionContract`：`NotApplicable` 禁止模型自行增加相关场景或过程节点；`Allowed` 要求至少一个相关场景，所有参与人物通过成年虚构门禁，并按契约要求余波；严格模式要求过程节点，非严格模式禁止伪造 100% 节点证明；`Blocked` 在 expectation 构造时直接拒绝，不能降档继续。

合法 JSON 会递归按 object key 排序后生成规范文本和 SHA-256，数组顺序保持业务语义；所有数据模型和错误报告的字符串表示均隐藏计划正文、人物 ID 集合与 hash。当前 parser/validator 仍是离线纯函数，`CHAPTER_PLAN_V1` 继续未注册；本阶段不创建请求、Attempt、Usage、artifact、数据库行或 Provider 调用。

## 44. TASK-064 Phase 2E4A：目的地与三层预算前置审计

普通 `CHAPTER_PLAN_V1` 在获得远程执行权前还缺两项独立的持久授权。`ConnectionProfileEntity` 虽已有目的地确认字段，但当前连接保存路径始终写入空确认，正式代码也没有接受、失效或 Provider-open 校验；`normalizedDestination` 当前只是原始 `baseUrl`，尚未定义为稳定的 scheme/host/effective-port/protocol 绑定。

现有 `BudgetEngine` 只是内存领域原型，`GenerationJobEntity.budgetSnapshotJson` 只是不可变意图快照，二者都不是可并发竞争的持久预留。`GenerationDao.recordRequestIntent` 会原子创建 Attempt 与 UNKNOWN/PROVISIONAL Usage，但尚未在同一事务竞争单次、单书和每日余额。若直接接 plan executor，并发 Job 可同时判断余额充足并突破硬上限。

后续顺序冻结为：先实现目的地规范化、确认与 host/protocol 变化失效；再实现数据库三层预算策略/预留/结算，并把预留与 RequestIntent 放入同一 Room 事务；最后才构造 plan 专用不可变请求绑定和 exact-token executor。目的地确认按连接目的地复用，预算上限按书/日持久复用，正常章节不要求用户逐章确认；runner 每次只做无交互的持久门禁复核。

## 45. TASK-064 Phase 2E4B：外部数据目的地确认内核

`ExternalDataDestinationBindingV1` 现把外部接收方规范为 `scheme://host:effectivePort`，并与 disclosure version、Provider protocol 一起进入版本化 SHA-256。host/scheme 大小写、默认端口、路径、尾斜杠和 DNS 尾点不会制造重复确认；userinfo、query、fragment、非法端口、非 HTTP(S) 和无 host 输入失败关闭。IPv6 使用括号化小写字面量。

新连接只保存规范 destination，disclosure 三字段仍为空。用户明确接受后，`ConnectionDao.acceptDataDisclosureForCurrentDestination` 在同一 Room 事务内读取当前 base URL/protocol、计算 binding、以 connection/base URL/protocol 作为 CAS 条件写入，并立即按当前事实回读验证。任何 host、port、scheme、protocol、version、normalized destination 或 hash 变化都会使证据失效；失效后可对新目的地重新确认。

`readAcceptedDataDisclosureEvidence` 只返回脱敏、可复核的持久证据，不是独立发送 permit。未来 plan 请求仍须在包含预算 reservation 和 RequestIntent 的发送前事务中再次使用同一 verifier，并同时满足 exact 双租约与一次性发送许可。`CHAPTER_PLAN_V1` 本阶段继续未注册。

## 46. TASK-064 Phase 2E5A：chapter-plan exact-token 请求准备

普通 `CHAPTER_PLAN_V1` 的首次远程请求现在必须通过 `GenerationStreamingDraftRepository.prepareBoundChapterPlanBeforeSend`。调用方只能提交 Phase 2B 从 Room 解析出的 `GenerationRunnerCurrentStageRouteSnapshot`；准备事务会重新读取 current Job/Stage，逐项核对 route、`RUNNING + PREPARING`、current cursor、同 owner 的 exact Job/Stage token、heartbeat、60 秒租约和 attempt 上下界，然后才创建 v1 reservation、Attempt、UNKNOWN/PROVISIONAL Usage并把Stage推进到`REQUEST_INTENT_RECORDED`。

通用 `prepareBeforeSend` 对普通 `BUILD_CHAPTER_PLAN` 失败关闭，不能拿 Stage token 绕过 Job token。既有 `firstChapterBootstrap` 合同保持兼容，不被误判为普通 plan。流式仓库仍先分配加密 `STREAM_DRAFT`；若 exact-token 或数据库门禁拒绝，新工件立即删除，数据库保持零半状态。

本阶段只完成“请求发送前的持久授权准备”，没有构造 Provider prompt、没有打开 Provider、没有解析计划输出，也没有执行 DEC-068 原子提交；`CHAPTER_PLAN_V1` 继续不进入 registry。下一阶段先冻结权威 expectation/请求快照，再接 Fake 流式执行和提交。
