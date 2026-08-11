# 织卷费用与用量控制

## 1. 目标

用户自带 API Key 意味着每个自动步骤都可能产生真实费用。费用控制是 P0 数据安全同等级功能，不是一个事后统计页。

## 2. 三层硬上限

| 上限 | 作用 | 默认行为 |
|---|---|---|
| 单次请求 | 防模型或参数异常导致超长输出 | 由阶段和模型能力给推荐 token 上限 |
| 单本书 | 防长篇循环或规划膨胀 | 创建中长篇时必须设置或接受推荐值 |
| 每日总量 | 防后台连续运行失控 | 首次生成前必须接受默认值 |

每层同时支持：

- token 上限：价格未知时仍可硬停止；
- 金额上限：价格表可用时启用；
- 预警阈值：默认 70%、90%；
- 硬停止：100%，必须用户明确提高或等待周期重置。

## 3. 发请求前预算预留

1. 估计输入 token；
2. 取阶段最大输出 token；
3. 根据模型和价格表计算最坏合理金额；
4. 同时检查单次、单书、每日剩余额度；
5. 预留预算并写 `RequestIntent`；
6. 任一层不足则不发请求，任务进入 `BUDGET_EXCEEDED`；
7. 返回后按服务方 usage 或估算结算，多余预留释放。

并发请求必须在同一事务中竞争预算，防止两个任务同时认为余额充足。

## 4. 价格表

每条价格记录包含：提供方、模型匹配、输入/输出/缓存/推理价格、计价单位、币种、生效时间、来源 URL、更新时间和可信度。

- 官方服务可随 App 更新内置表，但价格会变化，必须显示“更新时间”。
- 中转站价格不能从 OpenAI 兼容协议推断；除非用户手填或中转站提供可信元数据，否则标为未知。
- 模型别名可能指向不同实际模型，别名不自动套价格。
- 历史台账引用当时价格版本，更新价格不重算旧记录。
- 金额为估算，最终账单以服务商为准。

## 5. 价格未知模式

价格未知时：

- 仍记录输入/输出 token 或估计字符/token；
- 必须使用 token 硬上限；
- 费用区域显示“此连接无法可靠估价”；
- 不显示误导性的 0 元；
- 不因为无法估价而允许无限自动生成。

## 6. 创建前预估

### 6.1 TASK-037 占位确认的诚实边界

- 当前价格目录和真实预算估算尚未接入，页面只能显示 `PRICE_CATALOG_UNAVAILABLE`，不得构造金额或 token 范围；
- 页面从不可变创建快照读取目标章数和模型，不使用可变表单或根据模型名称猜价格；
- 用户确认只锁定书/快照/内容哈希引用，不代表已经同意向某个 host 发送小说，也不启动生成；
- TASK-080~085 接入后，必须把章节规模、检查、修订、续接和重试余量纳入估算，并在 token/金额硬限制真实生效后才能替换占位状态；
- 即使未来价格仍未知，也必须先配置 token 硬上限和目的地确认，才能创建首个外部请求意图。

向用户展示范围而非假精确：

```text
预计调用：规划 3–6 次，正文约 20 次，检查约 20 次
预计用量：输入 0.8M–1.5M，输出 0.3M–0.6M token
预计费用：¥X–¥Y（按 2026-08-01 的价格表估算）
本书硬上限：¥Z / N token
```

估算考虑：篇幅、章节数、质量档位、上下文增长、摘要、检查、修订和可能续接。默认不把重试费用假装为 0，加入合理风险余量。

## 7. 用量台账

每次 attempt 记录：

- 书、章节和阶段；
- 连接、提供方、模型、协议；
- 输入/输出/缓存/推理 token；
- 服务方报告或估计；
- 价格表版本、币种、金额；
- 成功、失败、取消、拒绝或结果未知；
- 是否重试及父 attempt；
- 计入哪个日周期和书预算。

取消、拒绝和错误也可能有用量，不能只统计成功章节。

## 8. 展示层

- 创建前：范围 + 硬上限确认。
- 书详情：本书已用、剩余、最近一章用量。
- 设置：今日总量、按书/模型/阶段查看。
- 达 70%：书详情轻提示；90%：生成通知；100%：自动暂停。
- 普通用户默认看金额/大致用量；技术详情可展开 token。

## 9. 自动策略

- 不自动切换到更贵模型；
- 不在预算不足时降低质量后悄悄继续，除非用户预先开启明确规则；
- 自动重试同样先预留预算；
- 任务从后台恢复前重新检查当日周期和书上限；
- 用户降低上限后，已经发生的费用保留，后续立即暂停；
- 价格表更新若使预计剩余费用超限，暂停并重新确认。

## 10. 估算算法验收

- 输入/输出分开计价；
- 缓存与推理 token 按提供方规则单独处理；
- 跨币种默认不自动换算，除非有带时间的汇率来源；
- 小数使用 Decimal/整数最小货币单位，不能用 Float 累积；
- 时区按用户本地日界线定义每日预算，但记录 UTC；
- 无 usage 时标估算，不混入“精确”统计；
- 服务方迟到的最终 usage 能幂等修正台账。

实现基线（TASK-011）：请求意图事务会先创建 `UNKNOWN/PROVISIONAL` 台账，所有 token/金额字段为 `NULL` 而不是 0。台账可从未知→估算→服务方报告单向升级；已封账的估算值仍允许被一次迟到的 `PROVIDER_REPORTED` 最终值纠正，服务方最终值之后不可再改。三层预算的并发原子预留与结算由 TASK-083 完成；TASK-084 只负责创建前调用/费用范围估算。

TASK-046 已把主动暂停、取消和停止接入同一台账边界：若 Provider 已报告总量则按报告值 FINAL；只有可推导的部分量时按 ESTIMATED FINAL；完全没有可靠用量时按 UNKNOWN FINAL 且数值保持空。后续迟到的 Provider 报告仍可按既有单向升级规则纠正，取消动作不会抹掉可能已经产生的费用。

TASK-047 对未知结果采用同样的保守封账原则：进入用户确认时把本次 Usage 置为 FINAL，但保留已有的最高可信 token/金额；完全未知仍为 `NULL`，不得写 0。远端仍运行/查询无结论时 Usage 保持 PROVISIONAL，避免过早封死后续对账；提供方确认已完成时可用其 usage 单向升级为 PROVIDER_REPORTED FINAL。提供方明确证明未执行并自动回队时，也保留一条 UNKNOWN FINAL 审计记录，不删除旧 attempt。用户确认、恢复查询和回队本身均不预留或消耗新请求预算；真正重试仍必须重新预留。

## 11. P0 测试

- 两个并发请求不能突破共享每日上限；
- 达限后数据库重启、应用重启和网络恢复都不能自动发新请求；
- 价格未知仍被 token 上限拦截；
- 429 重试和输出续接计入预算；
- 取消请求仍保存已报告 usage；
- 金额精度在 10,000 次小额请求累加后正确；
- 日期跨午夜只重置每日层，不重置单书层；
- 提高上限必须产生新预算快照和审计记录。

## 12. 章前上下文容量预算（TASK-054）

- 本预算负责“一个请求能否完整装下必要故事事实”，与金额/每日额度并列而不互相替代。
- v1 使用最终规范 payload 的 UTF-8 字节数作为保守 token 上界，加 128 token Provider 封套预留；再保留上下文上限 10% 的安全余量和章节计划输出额度。
- 有效输入预算为 `上下文上限 - 输出预留 - 10% 安全余量`。任何中间值为负、输出预留不合法、容量未知且未确认时都在请求前阻断。
- 必需事实先全量计入；只有可选记忆可以按稳定优先级整项省略。系统不能为了“省 token”删除成年人事实、硬规则、目标章或上一章承接。
- 本地 `ASSEMBLE_CONTEXT` 不预留费用、不新增 Usage。后续 `BUILD_CHAPTER_PLAN` 真正发送时仍按常规单次/单书/每日硬上限重新预留并审计。

## 13. 截断续接费用边界（TASK-055）

- 初次正文和每次自动续接都是独立 Attempt，分别建立、结算自己的 `UsageLedger`；不得把四次请求合并成一笔而掩盖真实消耗。
- 最多 3 次自动续接只是连续性上限，不是费用豁免。每次打开 Provider 前仍要通过单次、单书、每日 token/费用硬上限，以及用户已确认的数据目的地门禁。
- `LENGTH` 响应只要已经成功返回就结算该 Attempt 的可得 usage；Provider 未给 usage 时保持 `UNKNOWN/ESTIMATED` 语义，不填造精确值。
- 进程在终态落库后退出，恢复器不会再请求 Provider。若已收响应但仅存在内存的 usage 丢失，则以 `UNKNOWN` 完成该 Attempt，而不是重发来“补账单”。
- 第 4 次截断、Stage attempt 用尽、4 MiB 正文上限或其他硬预算触发后进入 `NEEDS_ACTION`，重启不能重置计数，也不能自动建立第 5 次付费请求。
- TASK-055 使用本地假 Provider，真实调用和实际费用均为 0；正式费用预留事务仍由 TASK-083 后续接入整章执行循环。

## 14. 章节记忆提取费用边界（TASK-056）

- 每次提取是独立 Attempt/Usage；默认零温度，输出上限由请求在 256–16,384 token 内冻结。
- 结构失败最多自动修复一次。第一次无效响应的 Usage 在进入 `RETRY_WAIT` 的同一事务中最终结算，第二次失败停止，不能形成无限“修格式”费用循环。
- 来源版本在 Provider-open 前变化时 0 次调用；提交前变化时不重复发送，由调度器基于新版本重新建立独立任务。
- 候选正文修订导致 hash 变化时必须重提取，这笔成本应纳入 TASK-084 的整章估算；不能为省一次调用沿用错误记忆。
- TASK-056 全部使用本地假 Provider，真实费用 0；三层原子预算预留仍由 TASK-083 接入完整流水线。

## 15. 时间线与伏笔投影费用边界（TASK-057）

- story-tracking 是独立 Attempt/Usage，temperature 固定 0，输出限制为 512 KiB、最多 64 个事件和 64 个伏笔操作；不能用超长自由文本替代结构化台账。
- 结构失败最多修复一次，第一次无效响应的实际或未知 Usage 在同一失败事务中转为 FINAL；第二次失败停止。
- RequestIntent 后任一来源快照变化时在 Provider-open 前拒绝，因此调用数和新增费用为 0；提交前变化不自动重发，由新任务重新预算。
- 编辑旧章节可能需要从修改点向后顺序重建，属于可显著放大的成本。TASK-061 必须在启动前计算受影响章节数并受单书/每日硬上限约束，不能把逐章重建藏在一次“免费修复”里。
- TASK-057 验收只使用本地假 Provider，真实费用 0；三层预算原子预留仍由 TASK-083 接入完整执行器。

## 16. 一致性检查费用边界（TASK-058）

- 本地确定性检查永远先运行；已经存在 blocker/major 或成年人门禁阻断时不建立模型检查请求，Provider 调用和新增费用均为 0。
- 模型检查是独立 Attempt/Usage，temperature 0、512 KiB 上限、最多 128 个问题；结构格式失败最多修复一次，不能无限请求“重新评审”。
- 检查结果要求退修时，TASK-058 只返回决策，不自行发起改写。TASK-059 必须把修订次数、再次提取和复检成本纳入整章预算硬上限。
- RequestIntent 后候选或来源变化时在 Provider-open 前拒绝；旧 Attempt 审计保留，新的候选需要新的预算和请求，不能复用已付费结果。
- TASK-058 验收仅使用本地规则和假 Provider，真实费用 0；固定负例集不等于已测真实模型的中文检查性价比。

## 17. 章节速度优化的费用边界（TASK-062～069）

- 速度优化不能靠无条件并发多个 Provider 请求。任何并行或推测执行都必须先证明依赖独立，并为每个 Attempt 分别预留最坏合理预算；默认关闭可能因后续修订而整批作废的推测调用。
- 正文/修订模型与规划/记忆/追踪/检查辅助模型可以按角色分工，但只能在同一已确认 host 或用户明确确认的新 host 内，从能力和速度均已验证的模型中选择；不得自动切换到更贵模型。
- 5 分钟 slow watchdog 只停止新远程 Stage并尽力取消在途请求，不把取消当成“没有费用”。已发送请求的 Usage 继续按精确、估算或未知结算，结果不明时不自动重发。
- 推荐模型档案必须同时展示性能样本范围和用量范围；不能为了更快只报告耗时而隐藏多次派生调用、修订或续接成本。
- TASK-069 的真实模型校准必须在 TASK-080～085、TASK-110 和用户单独授权后运行，使用固定小样本和明确硬上限。

## 18. TASK-064 Phase 2E4A 持久预算审计结论

- `BudgetEngine` 当前是纯内存规则原型，只能证明三层计算逻辑，不能证明进程重启或两个并发请求不会突破上限。
- `GenerationJobEntity.budgetSnapshotJson` 是任务创建时接受的不可变上限快照，不是共享余额、预留记录或结算计数器。
- `UsageLedgerEntity` 能记录实际/估算/未知用量，但没有 reservation 身份和状态，不能单独阻止并发超支。
- 正式实现必须让三层 reservation 与 RequestIntent、Attempt、UNKNOWN/PROVISIONAL Usage 在同一数据库事务中创建；失败或冲突必须零写入。
- 价格未知时仍按 token 最坏上界预留；未知结果按保守占用结算，不能释放成 0。重试、续接和格式修复分别重新预留。

## 19. TASK-083 Phase 1 持久预算设计冻结

- 策略采用不可变 revision+head，request 级限制冻结在 reservation；book/daily 聚合包含同范围全部非 RELEASED 明细，调整上限不会删除旧用量。
- RequestIntent 写事务先建立候选 reservation 并把自身纳入聚合检查，失败整笔回滚；不能在事务外先读余额。
- FINAL 与迟到 Provider usage 只通过 `GenerationDao.recordUsage` 的唯一结算入口同步更新 reservation。UNKNOWN FINAL 保留最坏估计；只有 Provider 明确证明未执行可释放为0。
- daily period 由持久 IANA zone 和 epoch 时间规范生成；跨午夜未发送请求重新预留，只重置 daily、不重置 book。
- v16 旧 Attempt 保留为 enforcement v0 且不得新开 Provider；v17 前真实 Provider调用为0，不为旧 UNKNOWN 测试行伪造费用或 token。

## 20. TASK-083 Phase 3B 公开预留边界

- 每个公开远程 Attempt 必须在 prepare 时显式提供 request token/可选金额上限、估计值、连接和唯一reservation ID；系统不猜默认预算，也不允许测试或旧调用绕回v0发送。
- 日键不再由调用方填写，必须在同一原子事务内从当前 DAILY policy 的持久IANA zone与请求时间派生，避免伪造日界线绕过额度。
- 发送许可只在reservation、Attempt、UNKNOWN/PROVISIONAL Usage和Stage完整提交后产生，并在Provider-open、SENT和STREAMING三处重复核对同一条`RESERVED` reservation。
- legacy v0/null Attempt仍可用于旧本地迁移/恢复测试，但不能领取Provider发送权；错误、缺失或已释放的reservation同样在联网前拒绝。
- 本阶段尚未完成终值结算和释放：UNKNOWN仍按estimate占用；只有后续唯一结算事务才能根据高可信Provider证据调整accounted值或证明未执行后释放。

## 21. TASK-083 Phase 4A 唯一结算边界

- 所有现有提交、失败、取消和恢复路径只要调用 `GenerationDao.recordUsage`，就会在同一事务同步结算 enforcement v1 reservation，不允许上层遗漏或重复记账。
- PROVISIONAL 不减少占用；FINAL UNKNOWN 保留最坏合理估计；已知终值直接替换 accounted，实际高于预留也必须保存并阻断后续请求。
- 迟到 Provider 报告会替换 UNKNOWN/ESTIMATED 终值而不是追加差额；精确 replay 只返回相同结果，单书/每日聚合不会重复增加。
- token、金额和币种成对使用持久终值；Provider 没有可靠金额时不伪造价格，配置金额上限的后续聚合因此继续保守拒绝。
- 普通 FINAL 不能释放 reservation。只有 Provider 明确证明未执行的专用恢复事务可以进入 `RELEASED`，该入口与跨日重预留仍待下一阶段完成。

## 22. TASK-083 Phase 4B 明确未执行的唯一释放边界

- 断网、超时、空响应、Provider 查询无结论或本地无草稿都不能单独证明“请求未执行”；只有既有恢复策略同时取得 Provider `CONFIRMED_NOT_EXECUTED`、空草稿和未知用量，才会进入专用释放分支。
- v1 release 与 UNKNOWN/FINAL Usage、Attempt/Stage/Job 回队共享一个外层 Room 事务；释放后 accounted 清零并从 book/daily 聚合排除。任何旧状态、身份、时间或 accounted CAS 冲突都会失败关闭，不留下半状态。
- 本地已有正文、已知 Usage 或含糊 Provider 证据继续走等待/用户确认/保守结算，不能释放。普通 `recordUsage` 不暴露通用 release 开关。
- 若已释放请求后来收到 FINAL `PROVIDER_REPORTED`，系统恢复为 `SETTLED` 并按实际终值重新占用；相同报告 replay 不重复累计，UNKNOWN/ESTIMATED 不能恢复占用。
- 下一阶段仍需解决 Provider-open 跨午夜时旧日 reservation 的原子释放与新日重竞争；单书累计不得借换日重置。

## 23. TASK-083 Phase 5B 跨日旧预留释放边界

- 每次 Provider-open 都从当前持久 DAILY policy 的 IANA zone 和当次 `validatedAt` 重算日键；同日不重新扣费，跨日时旧日 permit 永久失效。
- 跨日释放只接受完全未发送、无用量、仍按 estimate 占用的 v1 reservation。成功后旧 reservation 为 `RELEASED/accounted=0`，因此旧日 daily 与单书聚合都排除该请求；这不等于重置单书历史，新的请求仍会与同书其他非 RELEASED 明细重新竞争。
- 换日释放与 Provider“明确未执行”使用不同事务入口和不同错误语义；普通断网、超时、UNKNOWN、取消或含糊恢复证据不能借此清零占用。
- 旧 Attempt 已消耗一次 attempt。新日替代请求必须使用新的 Attempt 序号和新的 reservation，不能复用旧行或篡改 `attemptCount`；次数耗尽时停止自动重试。
- Phase 5B 尚未创建替代请求。Phase 5C 将在 runner 重新取得精确租约后完成新日原子预留，并在旧草稿非空时以新的受保护工件复制有限续写种子。

## 24. TASK-083 Phase 5C 新日重竞争边界

- 替代请求不是“恢复旧 reservation”，而是创建新 Attempt 和新 `RESERVED` reservation；request 上限、estimate、币种与来源版本必须逐字段沿用父请求，不能借换日放宽本次请求成本。
- 旧 `RELEASED` reservation 保持 accounted=0。新 reservation 重新计入同书所有非 RELEASED 明细，因此单书预算连续；它只进入当前新日键的 DAILY 聚合，因此每日额度按新周期重新竞争。
- 当前 BOOK/DAILY policy revision 会在新事务中重新读取。新日策略变严时可以拒绝替代请求，拒绝必须使 candidate、Attempt 和 Usage 零落盘，不能因为旧请求已释放就强行发送。
- 两个 worker 同时准备同一个父请求时，最新 Attempt、Stage 状态、attemptCount 与双租约门禁只允许一个成功，避免双预留和双扣占用。
- 草稿复制本身不改变 token/金额 estimate，不产生 Provider Usage，也不打开 Provider；实际网络请求仍要经过后续 Provider-open 目的地匹配和同日预算许可。

## 25. TASK-083 Phase 5D 实际发送目的地费用边界

- reservation 预留的是某个 connection/canonical destination/protocol/disclosure binding 下的一次外部请求，不是对任意 Provider 的通用余额凭证。
- 只有实际 profile、adapter protocol、当前 accepted disclosure 与 reservation 冻结事实全部匹配，才允许打开受保护草稿并调用 Provider。
- 不匹配不会消耗预留、刷新发送 heartbeat、释放旧日余额或生成新的 Usage；纠正配置后仍可安全重试同一 permit。
- 该门阻止“按低风险连接完成确认和预算预留，实际改向另一 endpoint 发送”的费用与隐私旁路。

## 26. TASK-064 Phase 2E5A plan exact-token 预留边界

- 普通 plan 的预算预留只能由 bound preparation 创建；裸 Stage token 无法进入 request/book/daily 三层竞争。
- exact 双租约复核与 v1 reservation、Attempt、UNKNOWN/PROVISIONAL Usage、Stage 推进属于同一 Room 事务；任一授权或额度失败时四类数据库状态零写入。
- streaming repository 在事务前创建的加密草稿不是费用事实；事务拒绝后必须删除，不能因为工件存在就计费或占用预算。
- 本阶段没有 Provider-open、SENT 或 FINAL Usage。下一阶段即使增加请求工厂，也必须继续沿用 TASK-083 的目的地、日界和最终结算门禁。
