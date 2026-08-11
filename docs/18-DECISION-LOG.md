# 织卷关键决策记录

## DEC-001 产品名为“织卷”

- 日期：2026-08-01
- 状态：已确认
- 原因：用户选择。

## DEC-002 当前 Logo 仅作临时占位

- 日期：2026-08-01
- 状态：已确认
- 资产：`branding/selected/zhijuan-logo-draft.png`
- 原因：用户明确“先用这个凑合”，当前先推进文档。
- 后续：正式图标仍需矢量、小尺寸、adaptive icon 和版权检查。

## DEC-003 原生 Android、本地优先、无业务后台

- 状态：已确认
- 原因：单人自用，不需要账号/会员/支付/社区；原生更适合阅读、后台任务、Keystore 和本地数据控制。
- 影响：跨设备云同步不在 1.0。

## DEC-004 默认路径以少参与为最高原则

- 状态：已确认
- 决策：一句设想即可创建；高级项折叠；系统自动补人物、世界、规划、检查和分类。
- 约束：自动推断必须可查看、可修改、可追踪来源。

## DEC-005 内容呈现采用隐蔽命名

- 状态：已确认；TASK-035 收束首版映射
- 决策：首版默认创建流程只使用 `留白/均衡/细写` 三档，不显示内部专业维度，也不增加“自定义”第四入口。
- 约束：内部仍拆分精确维度并记录 `presentationMappingSchemaVersion` 与 `contentControlSchemaVersion`，避免名称隐蔽导致实现不可测；未来开放自定义必须有独立需求证据且保持高级按需入口。

## DEC-006 分章流水线而非一次生成全书

- 状态：已确认
- 原因：上下文、输出长度、费用、失败恢复和一致性均要求阶段化。

## DEC-007 第一章快车道有限使用

- 状态：已确认
- 决策：最小规划可先产首章；第二章前必须完成完整故事圣经和规划。

## DEC-008 模板是不可变版本配置，不是旧书复制

- 状态：已确认
- 决策：模板新书默认保留设定/风格，重新生成标题/规划/正文；不复制运行记忆、日志和密钥。
- 原因：满足快速重开，同时防止旧内容污染。

## DEC-009 区分模板新书、书分支和章节重试

- 状态：已确认
- 原因：三者的数据复制范围、来源关系和用户预期完全不同。

## DEC-010 模板自动归类，来源不可篡改

- 状态：已确认
- 决策：用户不用手工整理；系统按题材、机制、氛围、节奏、篇幅、来源和时间分类。用户可改标签，不可改来源事实。

## DEC-011 模型连接使用统一适配层

- 状态：已确认
- 决策：首发支持 OpenAI Responses/Chat Compatible、Anthropic Messages、Gemini；Ollama 以 P1 进入。
- 原因：各家流式和能力不同，不能假设完全 OpenAI 兼容。

## DEC-012 不依赖提供方会话保存小说记忆

- 状态：已确认
- 决策：故事状态和上下文由本地数据库管理。
- 原因：兼容中转站和 Ollama 能力不一致；本地可恢复/可审计。

## DEC-013 费用硬上限为 P0

- 状态：已确认
- 决策：单次、单书、每日三层限制；价格未知仍以 token 限制。

## DEC-014 正式章节采用原子提交

- 状态：已确认
- 决策：未结束流仅存临时草稿；正文、派生记忆、任务状态和用量在一致性事务/补偿边界内提交。

## DEC-015 用户编辑触发派生失效链

- 状态：已确认
- 决策：旧摘要/状态/索引不可继续进入后续上下文；已生成后续正文保留，由用户决定是否重织。

## DEC-016 长任务不依赖永久前台服务

- 状态：已确认
- 原因：Android 新版本存在前台服务时限和 OEM 限制。
- 决策：持久状态机+短批次执行+安全检查点；FGS/WorkManager 都只是执行器。

## DEC-017 敏感数据排除 Android 自动备份

- 状态：已确认
- 决策：使用应用内加密备份包，默认不含 API secrets。

## DEC-018 正式迁移禁止破坏性清库

- 状态：已确认
- 决策：显式 Migration、升级前恢复点、失败只读恢复。

## DEC-019 正式签名从第一个可用版开始保护

- 状态：已确认
- 决策：固定包名和 keystore，离线双备份，覆盖安装必测。

## DEC-020 阅读参考组合

- 状态：已确认方向，未开始设计
- 决策：多看的沉浸阅读作为核心；起点的书架信息组织承载小说/生成状态；去除广告、会员、社区、商城等。

## DEC-021 内容和提供方边界

- 状态：已确认
- 决策：参与相关内容的角色必须明确成年人；不生成未成年人、年龄模糊者或真实人物性内容；服务商拒绝不规避、不无限重试。

## DEC-022 备份恢复采用验证后原子切换

- 状态：已确认架构边界，正式容器格式未冻结
- 决策：备份先写临时包并完整自检；恢复先进入活动库同文件系统 staging，校验并创建恢复点后原子切换。原子能力不可用时失败关闭，不先删除当前数据。
- 原因：任何错口令、损坏包、磁盘错误或中断都不能覆盖当前书库。

## DEC-023 网络客户端不自动重放付费请求

- 状态：已确认
- 决策：关闭 OkHttp 自动重定向与连接失败重试；只有同 origin HTTPS 的 GET/HEAD 可由安全执行器有限跟随。POST、跨 origin、降级和异常目标要求保存最终地址后重新测试。
- 原因：比“跨站剥离秘密后继续”更容易证明不会泄密，也避免生成请求被 HTTP 层隐式重放和重复计费。

## DEC-024 模型能力按来源保存并绑定端点

- 状态：已确认并在 TASK-027 落地
- 决策：能力证据按连接、端点指纹、协议、模型和来源分别保存，逐字段按用户覆盖、成功探测、官方元数据、内置基线、保守默认解析；不保存单条可覆盖所有来源的最终缓存。
- 原因：单条缓存无法在撤销用户覆盖后恢复自动值，也容易在中转地址变化、适配器升级或证据过期后继续误发高级字段。来源分存可以让默认流程自动化，同时保留可解释、可失效、可一键恢复的安全边界。

## DEC-025 自动重试由请求证据而非错误码单独决定

- 状态：已确认并在 TASK-028 落地
- 决策：标准错误必须与 `NOT_SENT / PROVIDER_REJECTED / RESPONSE_STARTED / RESULT_UNKNOWN` 送达证据、是否已有正文、预算及重试窗口共同决策。付费 POST 不由 HTTP 客户端重试；结果不明或已有正文时，用户确认前不重发。
- 原因：相同的“断网/超时/5xx”可能发生在请求发出前、服务端明确拒绝后或服务端已经生成后。只看错误码会造成重复章节和重复计费；显式证据可以在保留少操作体验的同时守住费用边界。

## DEC-026 创作宪法采用分层契约，不直接使用互动推演规则稿

- 状态：已确认；分层 Prompt/场景契约已在 TASK-050 落地，检查契约已在 TASK-058 落地，真实模型 A/B 待 TASK-059 完整流水线与联调门禁
- 决策：保留织卷现有的应用硬规则、阶段任务契约、故事圣经、运行记忆、呈现参数和一致性检查；从用户提供的互动推演规则稿中吸收视角感知、身体反应链、状态继承、人物驱动、因果与余波等有效规则，但不采用启动确认、每轮选项、特定继续口令、玩家调查门槛和声称覆盖服务商规则的条款。正文阶段新增可测试的“场景执行契约”，把避免淡出、动作—反应连续性、关系变化和场景余波落到结构化输入与质量检查中。
- 原因：原规则稿擅长约束单次互动片段，却会把自动长篇变成需要频繁操作的跑团流程；其中 `accept_all`、禁止遵守服务方规则等声明不能改变模型或 API 的上层策略，反而可能提高拒绝率。分层、版本化、可检查的契约更符合织卷的真实产品目标。

## DEC-027 默认零生成连接测试，完整验证显式且硬限额

- 状态：已确认并在 TASK-029 落地
- 决策：首次连接默认只读取模型列表；生成通路验证是可选高级动作，必须由用户明确确认可能产生极少费用，只发送一次固定通用探针，并以 16 token 和整条 60 秒 deadline 双重约束。模型列表不可用只在服务端明确证明端点不存在/不兼容时允许手填，其他失败不能绕过。
- 原因：傻瓜化不等于替用户静默消费额度。把零生成的基础验证作为默认，既减少操作又避免无感费用；严格的回退条件和有界探针同时防止错误密钥被误存、私密小说被送往未确认 host，以及慢列表叠加第二个 60 秒调用。

## DEC-028 连接改址和换密钥采用“验证后替换”

- 状态：已确认并在 TASK-032 落地
- 决策：普通编辑只允许修改显示名称和已发现模型；服务地址、协议或密钥变化时，用户先走一次新增连接向导，验证成功并设为当前后，再确认删除旧连接。向导临时引用提交与当前选择写入 schema v6 事务；进程中断后先查询数据库引用再决定是否撤销 secret。
- 原因：地址、协议和密钥共同决定数据发送目的地与可用性。允许原地覆盖会让一次输入错误破坏唯一可用连接，也会使数据发送确认绑定失效边界更难证明。验证后替换只多一次明确动作，却提供可恢复的旧连接和清晰的密钥生命周期。

## DEC-029 篇幅以章节下限和目标双字段版本化

- 状态：已确认并在 TASK-034 草稿、TASK-036 schema v7 与不可变快照落地
- 决策：短篇使用最低/初始目标 80 章，中篇使用最低/初始目标 300 章且作为默认选择；长篇由用户填写目标，允许 301–10,000。领域只保留 `SHORT/MEDIUM/LONG` 三种模式，长篇自定义值进入 `targetChapterCount`，不另造语义重复的 CUSTOM 模式。草稿和未来不可变快照同时记录下限、目标与 `lengthPolicySchemaVersion=1`。早期“建议 500”的界面预填已经由 DEC-031 取消。
- 原因：只保存“短/中/长”会让后续生成器、模板和规则升级各自重新解释篇幅，容易再次缩水。下限与目标分开既能保证用户要求的最低规模，也允许短/中篇为完整收束适度超出；10,000 是首版已做目录压力验证的防误输上限，不是不可修改的永久产品上限。

## DEC-030 创建标准化必须保留原始输入并使用可复现规则

- 状态：已确认并在 TASK-036 落地
- 决策：创建 UI 原样提交用户文本，统一标准化器保存原始 JSON 与 NFC 标准化 JSON；未选题材时按有序关键词规则推断，仍无命中则使用都市默认，每个结果记录来源和规则 ID。系统书名由故事设想首个分句推导并标记 `SYSTEM_INFERRED`。所有影响生成的载荷与 schema 计算固定顺序 SHA-256；随机 ID 和时间不参与哈希。Prompt Bundle 尚未建立时写明确未分配哨兵，禁止冒充真实提示版本。
- 原因：若 UI 先修剪，快照无法回答“用户实际输入了什么”；若空项直接交给模型推断，创建书会在确认前产生费用并失去可复现性；若只存结果不存来源，模板重开和规则升级无法解释差异。原始/标准化双层、确定性低成本推断和版本化哈希能同时满足少操作、可追踪和离线创建。

## DEC-031 长篇目标章数不使用系统预填值

- 状态：已确认并在创建页落地
- 决策：短篇不得少于 80 章，中篇不得少于 300 章；两者分别以 80、300 作为初始规划目标，完整收束需要时允许继续延长。长篇目标由用户明确填写，系统不再预填或暗中采用 500 章；首版合法范围仍为 301–10,000，未填写或越界都不能进入确认页。
- 原因：预填 500 会把系统建议伪装成用户选择，也可能导致用户没有意识到长篇规模已经确定。空值必填只在选择长篇时增加一次必要操作，短篇和中篇的一句话默认路径不受影响。

## DEC-032 开始前确认只信任已提交快照，未知价格不伪装

- 状态：已确认并在 TASK-037 占位页落地
- 决策：创建页先在本机原子保存 `Book + BookCreationSnapshot`，确认页只持久保存书 ID 并重新读取不可变快照。页面展示冻结书名、章节规模和模型；模型引用畸形或快照不可读时失败关闭。价格目录尚未接入时显示明确的未知状态，禁止显示 0 元、虚构 token 范围或按模型名猜价。确认动作只提交书/快照/内容哈希引用并锁定按钮，不创建生成任务，也不代表已授权向远程 host 发送小说。
- 原因：把上一页可变表单当确认依据，会在旋转、进程重建或连接变化后出现“用户看到的”和“即将生成的”不一致；把未知费用显示成 0 又会制造错误安全感。以提交快照作为单一事实来源并把占位确认与联网授权分开，可以在不增加默认操作的前提下保证可恢复、可审计和诚实反馈。

## DEC-033 状态只由带证据的专用事务推进

- 状态：已确认并在 TASK-040 落地
- 决策：Job/Stage 的领域矩阵是唯一转换白名单，持久层使用期望旧状态 CAS 和单调时间。领取租约、记录请求意图、确认发送、记录流结果、标记未知结果、提交输出和父任务停止不得通过通用状态更新模拟；Job 完成还必须由数据库验证所有 Stage 已成功。通用 Repository 只返回只读状态投影，不暴露 Room Entity 或底层 CAS。
- 原因：只验证“下一状态是否合法”仍可能产生没有租约的 PREPARING、没有 Attempt 的 STREAMING、没有章节版本的 SUCCEEDED，或阶段未完成但 Job 已完成。这些状态表面合法、业务事实却不存在，会在崩溃恢复时造成重复请求、丢章和重复费用。把跨表证据与状态放进同一专用事务，才能让数据库状态真正可解释。

## DEC-034 租约到期不等于远程请求没有发生

- 状态：已确认并在 TASK-041 落地
- 决策：默认以 15 秒心跳、60 秒到期管理生成租约；领取凭证由 owner 与领取时间共同组成，所有运行中转换必须携带匹配凭证且不得在到期后继续。只有尚未记录 RequestIntent 的 PREPARING 可在到期后原子回 READY；一旦存在 RequestIntent、流、校验或提交证据，过期只进入恢复审计判断，不自动清现场或创建新 attempt。
- 原因：租约只证明“本地执行器是否仍活跃”，不能证明请求是否已经离开设备、服务端是否完成或费用是否产生。把所有过期工作直接重跑会造成重复章节和重复计费；完全不回收请求前工作又会造成永久卡死。以 RequestIntent 为不可越过的恢复分界，可以同时保证自动恢复能力和费用安全。

## DEC-035 发送权是持久审计提交后的短生命周期能力

- 状态：已确认并在 TASK-042 落地
- 决策：普通 Provider 调用路径不能仅凭请求参数或 Stage ID 发起网络。执行器先原子提交 Attempt、未知 Usage 和 Stage，再获得内部构造的一次性 permit；claim 时重新核对持久证据并续租，随后才可交给 Provider open。进程重启不根据 `INTENT_RECORDED` 自动补发 permit。连接/模型/协议审计 JSON 必须是有界对象并递归拒绝可携带密钥的字段。
- 原因：若先构造网络请求再补数据库记录，崩溃会留下不可解释的费用；若 permit 可重复构造或不复查租约，两个执行器仍可能绕过 TASK-041；若把 API key 一并冻结到 RequestIntent，历史审计会变成长期密钥副本。提交后授权、claim 前复查和快照密钥白名单共同把这三类风险关在网络出口之前。

## DEC-036 流式增量先进入有界加密草稿，不直接进入正式章节

- 状态：已确认并在 TASK-043 落地
- 决策：每个 attempt 在 RequestIntent 前分配随机 UUID 的 `STREAM_DRAFT` artifact，并与 Attempt、未知 Usage 和 Stage 原子绑定。Provider 只能由消费一次性审计授权的 `AuditedStreamingProviderExecutor` 打开；独立心跳不依赖 Provider 发事件。草稿默认每 2 秒或待写 32 KiB 原子检查点，单阶段明文上限 4 MiB，expectedRevision 冲突永久栅栏旧 writer。正式成功、未成功、孤立草稿分别按 Stage 提交时间 24 小时、7 天、24 小时保留，重复引用或完整性冲突不自动删除。
- 原因：逐 token 写盘会放大 I/O 和换版竞争，纯内存又会在进程退出后失去已付费输出；若增量直接修改 `ChapterVersion`，半流会进入阅读目录且无法原子回退。受控入口、独立心跳、有界缓冲和延迟清理使流中断可审计，同时保留 TASK-045 作为唯一正式章节提交边界。

## DEC-037 结构化输出失败只能持久计数并修复一次

- 状态：已确认并在 TASK-044 落地
- 决策：结构化结果必须先通过严格 UTF-8、JSON 语法、重复键、资源上限、根对象、显式 schema 版本/迁移和契约校验。第一次且可修复的失败把 `FORMAT_INVALID` 写入完成 Attempt，并只发送一条不含原始创作提示的有界零温度修复请求；同一 Stage 的第二次格式失败、不可修复错误或无剩余 attempt 名额直接进入 `NEEDS_ACTION`。校验成功只到 `COMMITTING`，不提前创建正式章节。
- 原因：宽松 JSON、正则猜值或自动剥包装会把错误状态悄悄写进长篇记忆；只用内存布尔值会在进程重启后重新获得修复次数并循环花费；重放原始创作提示会把格式修复变成第二次创作。持久 Attempt 证据、单次独立修复和延迟正式提交共同限制错误传播与费用放大。

## DEC-038 正式章节只有一个许可化原子提交边界

- 状态：已确认并在 TASK-045 落地
- 决策：严格校验成功后签发与最新 Attempt、Stage、artifact 修订/hash 和当前租约绑定的内部提交 permit。只有 `ChapterGenerationCommitRepository` 能消费该 permit，并在一个 SQLCipher/Room 事务中提交正式章节版本、结构化记忆、章节指针、书籍进度、FINAL Usage、当前 Stage 和预冻结下一 Stage/Job 完成状态。成功 Stage 的完全相同载荷只回读既有结果；不同载荷、用户改稿、过期租约或任一约束失败均失败关闭。成功引用只存逐字段稳定 hash，不存正文，也不依赖语言对象的 `toString()`。
- 原因：把正文、记忆、用量和任务状态分成多个提交点会产生“能读但记忆缺失”“已计费但 Stage 未完成”或崩溃后重复生成；只用 Stage ID 做幂等键又无法证明重放内容相同；迟到写者若不比较章节父版本会覆盖用户修改。许可化证据、稳定载荷 hash、章节 CAS 和单事务推进共同关闭这些严重疏漏。

## DEC-039 生成控制必须由父 Job 意图与执行器安全点握手

- 状态：已确认并在 TASK-046 落地
- 决策：暂停、取消当前章、停止与继续只能通过 `GenerationControlRepository` 改变持久 Job；RequestIntent 已存在后不直接假定网络未发送，而先进入 `PAUSING/STOPPING`。执行器在打开 Provider 前和收流期间读取控制意图；命中后取消 Provider、强制刷新加密草稿，并在一个事务中取消 Attempt、最终结算 Usage、回队/取消 Stage 和完成 Job 控制状态。暂停发生在校验/正式提交时允许当前本地原子工作到安全点，停止可覆盖暂停。继续只回 READY，不签发发送权。心跳、发送和流开始使用同一持久写序列，避免并发时间倒序。
- 原因：按钮只改内存会在进程退出后丢失；RequestIntent 不等于“肯定未发送”，直接回队可能让已 claim 的执行器继续打开 Provider；只取消网络不结算数据库会留下活动 Attempt 和临时 Usage；立刻打断章节提交又会制造半章。父 Job 先持久、执行器后确认安全点、跨表原子结算和迟到回调栅栏共同保证少操作体验下仍不会误发、复活或丢失已付费草稿。

## DEC-040 未知结果只有“证明未执行”才能自动回队

- 状态：已确认并在 TASK-047 落地
- 决策：恢复先审计 Attempt、Usage、加密草稿、提交结果和租约；适配器若有可靠能力，只能按已存远端请求 ID 查询原请求。只有提供方明确证明未执行，同时存在发送证据、草稿可读且为空、没有已知 usage 时才自动回到 READY。查询不支持/无结论、仅有 RequestIntent、远端已完成但本地无输出或证据矛盾均进入用户确认；本地已有完整响应只恢复本地流水线。用户确认和恢复协调器均不得调用生成接口。
- 原因：发送许可被 claim 后到 Started 落库前存在真实崩溃窗口，仅凭 `INTENT_RECORDED` 无法判断请求是否离开设备；超时和租约只证明本地执行器失联，也不能证明服务端未执行。把“查询原请求”“解除门禁”“创建新请求”拆成三个边界，并让数据库原子保存最终 usage 和稳定原因，才能避免重复章节、重复付费和并发双重确认。

## DEC-041 前台服务只承载执行机会，系统限时转为可恢复暂停

- 状态：已确认并在 TASK-048 落地
- 决策：用户明确开始时才启动私有 `dataSync` FGS。服务立即发布不含小说隐私的通用通知，只用既有 Repository 观察和控制单个 Job，不创建任务或发送权。通知暂停/停止先落盘；Android 15 `onTimeout` 由独立应用级协调器写入 `SYSTEM_FGS_TIMEOUT`，服务在固定短截止内退出。服务销毁不修改任务，继续只回到 `READY`。
- 原因：把 Job 真状态放在 Service 内存会在进程回收后丢失；让超时回调等待网络或安全点可能触发系统 ANR；把系统限时混成用户暂停又无法解释为何停止；在 `onDestroy` 自动继续则可能重复付费。私密通知、数据库单一事实源、独立超时落盘和及时退场能同时满足少操作、系统合规与费用安全。

## DEC-042 WorkManager 只做无网络恢复维护，双租约是自动回收前提

- 状态：已确认并在 TASK-049 落地
- 决策：正式版使用唯一启动 Work 和唯一低频周期 Work 自动审计过期现场。Worker 不持有 Provider、连接读取或生成许可，只能执行本地扫描、请求前双租约原子回队、无 Provider 未知结果审计、到期控制结算和受保护草稿清理。只有 Stage 与父 Job 都精确过期、当前状态仍匹配时才可自动改变状态；RequestIntent 之后一律不把本地超时当作“服务端未执行”。
- 原因：只释放 Stage 会留下父 Job 旧租约并永久阻塞后续执行；把 WorkManager 当成长篇生成宿主会受到系统配额和长任务限制；给恢复 Worker 网络/生成能力则可能在用户不知情时重复付费。双租约事务、无网络能力接口和 SQLCipher 单一事实源同时保证少操作、可恢复和费用安全。
- 保留项：暂停/停止卡在本地 `VALIDATING/COMMITTING` 且没有足够提交证据时只延后。等实际阶段执行器接入后，必须复用 TASK-045/047 的本地恢复许可完成，不能扩大 Worker 权限。

## DEC-043 Prompt Bundle 以不可变快照确定性绑定，场景规则在远程准备前自动执行

- 状态：已确认并在 TASK-050 落地
- 决策：首版固定 `zhijuan.prompt-bundle.v1`/contract schema 1，以不可变 CreationSnapshot 为唯一来源，为全部 13 个 GenerationPhase 冻结模板、层、结构化输出标识和执行器类型。细写 + 避免淡出只在相关场景且成年人门禁通过时自动装配到章节计划、正文、检查、修订；用户未写年龄的新建虚构人物由种子/圣经阶段补明确成年事实，明确未成年、真实人物或年龄矛盾不得改写。绑定和 Provider 准备均为无网络、无 Job 写入的纯边界。
- 原因：把一大段自由提示词散落在 UI、数据库和各 Provider 会导致版本不可复现、阶段漏规则和适配器私自改写；逐场让用户确认又违背低参与目标。确定性哈希、13 阶段全覆盖、四阶段场景策略和 Provider 前阻断使规则既可测试又不增加操作，同时不把细写误实现为提高其他题材强度。
- 保留项：TASK-051 已冻结实际 Job 版本并实现故事种子/圣经/总纲结构化结果；TASK-058 已建立正文检查器。真实同模型 A/B 仍须等待 TASK-059 完整流水线和联调门禁，没有这些证据前不宣称模型成文效果已经验证。

## DEC-044 初始规划必须逐阶段提交，不能等待未来阶段组成“大包”

- 状态：已确认并在 TASK-051 落地
- 决策：初始规划固定为故事种子、故事圣经、全书总纲三个持久阶段。当前阶段只依赖已冻结输入和已成功提交的前序阶段，严格校验后立即提交；完整三件套校验仅作为最终一致性复核，不能反过来阻塞第一阶段。故事圣经和总纲进入不可变修订，人物和硬事实带来源；故事种子保存在加密 Attempt 产物中，并以稳定哈希衔接后续阶段，不新增一张可被随意修改的种子表。
- 原因：若提交故事种子前就要求同时拥有圣经和总纲，而后两阶段又只能在前序成功后激活，状态机会形成永远无法启动的循环依赖。逐阶段提交同时缩小崩溃重做范围，并让每次费用、结果和失败位置可审计。
- 后果：每个阶段都有独立 schema、解析器、提交草稿和事务门禁；后序结果必须携带并匹配前序内容哈希。任何跨书、跨阶段、过期租约或内容哈希不一致都失败关闭。TASK-052 只能消费已经成功的总纲，不能绕过本链路重新猜测全书结构。

## DEC-045 长篇逐章规划采用固定小窗口和增量修订，不建立全书章节清单

- 状态：已确认并在 TASK-052 落地
- 决策：`zhijuan.arc-window-policy.v1` 由本地把当前卷限制为最多 40 章、当前逐章窗口限制为最多 8 章；只在计划剩余 3 章时需要补下一窗。`arc-plan.v1` 在一个有界结果中保存卷级状态与当前窗口章节简要计划。每一窗作为独立 `CONTINUE_BOOK / BUILD_ARC_PLAN` Job 提交为新的不可变 outline revision，父链保留历史窗口。
- 原因：一次规划 300 或 10,000 个章节会超出上下文、成本和结构化输出上限，也会让远期计划在前几章变化后大面积失效；如果每次新修订复制所有旧章节节点，长期存储又会退化为平方增长。
- 后果：当前修订是“本窗口快照”，旧窗口通过父修订链追踪；不要求单一 revision 同时容纳全书所有章节节点。详细单章场景计划仍由后续 `BUILD_CHAPTER_PLAN` 生成，不能把本窗口摘要冒充正文计划。后续自动补窗必须复用这里的本地策略和当前 head 门禁。

## DEC-046 第一章快车道使用独立最小包，第二章以数据库事实硬阻断

- 状态：已确认并在 TASK-053 落地
- 决策：快车道第一章使用独立 `first-chapter-bootstrap.v1`，只包含种子人物、核心世界规则、相同结局方向与第 1–3 章粗计划；它不写伪 StoryBible/Outline revision。第一章提交后，完整圣经和总纲必须绑定第一章当前版本并适配它。第二章及后续只有在当前圣经、总纲、目标窗口、上一章和首章适配证据都由数据库复核后才能打开 Provider，并在正式章节提交时再复核。
- 原因：把最小包冒充完整规划会污染长期事实；只在 UI 排队时检查一次，又会在第一章改稿、窗口推进或并发恢复后留下旧许可，导致第二章绕过规划或覆盖新版本。独立契约、不可变来源绑定和发送/提交双复核既减少首章等待，又不牺牲长篇连续性。
- 后果：正常用户不需要新增操作；规划失败时第一章仍可阅读，但第二章不会联网。正文与 UI 接线仍由后续任务完成，TASK-053 只证明底层授权边界。

## DEC-047 上下文按整项确定性预算，必需事实超限即阻断，Provider 打开前重验来源

- 状态：已确认并在 TASK-054 落地
- 决策：`zhijuan.chapter-context-policy.v1` 先完整装入应用规则、成年/身份事实、世界硬事实、禁改项、目标卷章和上一章承接等必需项，再按稳定顺序整项选入可选记忆；不允许中途截断。容量未知默认阻断，明确确认时只采用 8192 保守回退。成功结果以不可变 snapshot/manifest 固定，并在 Provider 打开前重验所有当前来源。
- 原因：按字符尾部截断会留下半条事实，且最容易牺牲年龄、人物状态或上一章承接；从模型名称猜上下文容量也可能在真实请求时超限。发送前重验可阻断组装后改稿、补窗或并发推进产生的陈旧输入。
- 后果：普通用户无需管理 token 或记忆条数；可选历史可能被完整省略，必需事实装不下时则要求更大上下文模型或减少输出预留。阻断界面只给一个推荐主动作，不用静默降质换取“继续生成”。

## DEC-048 正文使用单字段流契约、精确尾锚点和独立 Attempt 有限续接

- 状态：已确认并在 TASK-055 落地
- 决策：正文固定使用严格 `chapter-draft.v1` 单字段 JSON 包络，本地增量解码后只保存 `body`；`LENGTH` 先与输出证据原子落库，再以 96 码点精确尾锚点创建新 Attempt、独立 Usage 和独立受保护 artifact。新草稿预置累计正文，模型返回的锚点只验证并剥离一次。最多自动续接 3 次；格式、锚点、attempt 或 4 MiB 上限失败时保存现场并停止。分类落库后崩溃只做本地结算恢复。
- 原因：让模型返回复杂章节对象会增加流式半 JSON 与错误字段污染正文的概率；模糊相似拼接可能静默删段或重段；在同一 Attempt 内续写会破坏请求、费用和恢复审计；若终态分类与输出证据分开保存，崩溃可能误把已完成请求当成可重发请求。
- 后果：正常用户无需手工续写或拼接，错误现场可解释且不会猜测性改正文；每次续接都会如实形成独立费用记录。代价是最多可能保留多份累计加密草稿，并在不能证明连续时宁可暂停。TASK-055 只完成正文流底层闭环，派生、检查、正式提交和阅读 UI 仍由后续任务完成。

## DEC-049 章节记忆必须绑定冻结最终版本，修订后重提取，重建路径双重校验当前版本

- 状态：已确认并在 TASK-056 落地
- 决策：`chapter-memory.v1` 必须回显预分配/既有的 `ChapterVersionId`、正文 SHA-256、章节 ID 和序号；人物事件与事实只允许已知实体及 `STORY_CANON/INFERRED`。正常生成链从绑定最终版本 ID 的冻结候选提取，正文修订后必须重提取，最终只在 TASK-059 的正文事务内一起生效。记忆重建只允许读取当前正式版本，并在 Provider-open 与提交前两次复核版本/hash。格式无效时原 Attempt Usage 与失败分类同事务最终结算。
- 原因：若从仍在续写的草稿提取，或修订正文后沿用旧记忆，会把不存在的伤势、关系、知识和持有物带入后续长篇；若先把候选记忆写成正式数据，一致性检查和修订会留下半套事实；若格式修复不结算前次用量，自动化会低估费用并积累临时预算。
- 后果：普通用户不增加操作；来源变化会自动重新准备或停止，不能静默复用。TASK-056 已完成通用契约和正式版本重建闭环，TASK-058 已完成正常候选检查；有限修订与最终原子提交属于 TASK-059，时间线和伏笔属于 TASK-057。

## DEC-050 伏笔使用“当前投影 + 追加转换台账”，且旧章节编辑按中间依赖失效

- 状态：已确认并在 TASK-057/schema v8 落地
- 决策：时间线/伏笔使用独立 `chapter-story-tracking.v1`，同时绑定最终章节版本、章节记忆、既有伏笔和故事实体快照。`foreshadow_item` 只承担当前快速读取；每次 PLANT/DEVELOP/RESOLVE/ABANDON 另写不可覆盖的 `foreshadow_transition`。旧章节版本被替换时，不只检查条目的当前来源，还检查整个转换历史：只要该条曾依赖旧版本，当前投影整体 STALE。重建必须按章节顺序，TASK-061 接入自动级联前不得跳章修补。
- 原因：仅覆盖 `foreshadow_item.status` 无法证明一条伏笔在哪章发展或为何回收；更危险的是，一条线索可能在第 2 章埋设、第 8 章发展、第 20 章回收，编辑第 8 章后当前记录的 `sourceChapterVersionId` 已是第 20 章，普通来源检查会漏掉第 8 章这一中间依赖。追加台账和历史依赖失效可以避免把不存在的发展继续当成事实。
- 后果：存储多两张小型结构表并需要 schema v8 迁移；换来的结果是可审计、可重建、可幂等。当前安全重建入口只处理没有后续已提交章节的位置，完整跨章重建的操作与成本提示由 TASK-061 完成。

## DEC-051 一致性检查采用“确定性前置 + 严格结构模型报告”，检查与改写分权

- 状态：已确认并在 TASK-058 落地
- 决策：正文先通过本地确定性规则；只有没有 blocker/major 且成年人场景门禁允许时，才请求零温度 `chapter-consistency-report.v1`。模型必须逐项返回固定检查矩阵和有界问题，不得改写正文、返回证据摘录、选择任意严重度或跳过严格过程节点。任何 blocker/major 只产生退修决定；实际改写与最终提交由 TASK-059 的有限循环执行。
- 原因：让同一个模型既检查又直接覆盖正文会失去原稿、混淆费用和来源，也容易把风格偏好升级成硬错误；只给总分又无法证明关键过程、位置、身体和余波连续性已经逐项检查。本地前置还能在显然无效或成年门禁不通过时避免额外付费。
- 后果：普通用户不新增操作；系统拥有可审计、可重放的检查结果，并能准确区分“允许进入提交候选”和“已经正式发布”。代价是 TASK-059 必须在每次修订后使旧记忆/追踪/检查全部失效并重新生成，不能复用旧 hash 的结论。固定负例集只能证明契约，真实中文识别效果仍需后续低成本 A/B。

## DEC-052 可靠性优先，章节速度成为独立 P0 闸门

- 状态：用户已确认实施路线；TASK-061 已完成，进入 TASK-062
- 决策：不以快速拼接页面为目标。先完成 TASK-061，再建立脱敏时序、固定延迟 Fake、全阶段 runner、生成中正文投影和慢服务 watchdog。普通参考章分别验收首段、正文结束和正式提交；5 分钟进入慢服务安全处置，10 分钟仍未结束阻断发布。
- 原因：当前候选章仍有 BODY、MEMORY、TRACKING、CONSISTENCY、可选修订等多段远程关键路径。只展示流式初稿不能证明整章完成够快；没有逐段测量就直接并行，又可能破坏来源、费用和恢复。
- 后果：总 runner 接线前增加 TASK-062～069；真实 Provider 速度校准晚于预算和数据目的地门禁。未通过固定参考负载的模型/连接组合不能成为默认推荐，但用户仍可在高级区选择并承担外部服务差异。

## DEC-053 取消应用锁与屏幕遮挡

- 状态：用户已确认
- 决策：不实现应用锁、生物识别、`FLAG_SECURE` 或最近任务缩略图遮挡；取消 FEAT-081、FEAT-082、TASK-097 和 TEST-089。
- 原因：用户明确表示这组功能不需要，不应继续占用设计、开发、测试和维护成本。
- 后果：数据库/草稿/备份/密钥加密、系统备份排除、脱敏日志、通用隐私通知和远程传输安全保持不变。设备已解锁后的截图、录屏和系统最近任务缩略图不由 App 额外保护。

## DEC-054 编辑重建 execution 保持不可变准备证据，完成性动态推导

- 状态：已确认并在 TASK-061 Phase 2B3B2E 落地
- 决策：`chapter_edit_rebuild_execution` 不新增可变 `COMPLETED` 状态。retained tracking 使用显式 ordinal 4/6/8…，每步证明直接前驱 tracking+aggregate、时间下界和连续 retirement evidence；全部冻结 memory/tracking/aggregate 是否完成由权威 planner 从业务表重新推导。自动选步、重启游标、双执行器和跨 phase 调度统一由 TASK-064 total runner 持久化。
- 原因：execution ledger 的职责是冻结编辑点、版本范围、rewind 和准备基线。把调度进度复制进同一不可变证据，或只为显示“完成”添加可变字段，会产生 planner 已回退但状态仍显示完成的双事实源；提前创建未来 Stage 又会迫使不可变输入引用尚不存在的前驱输出。
- 后果：TASK-061 可以独立证明失效、逐章重建、回滚和 replay，schema 保持 v15；10 章 TEST-033 以权威 planner 与上下文选择器关闭。产品仍需 TASK-064 才能自动执行整条链，文档不得把底层原语描述成可日用的 total runner。

## DEC-055 长篇性能采用 SQLCipher 追加时序账本与 boot-bound monotonic duration

- 状态：已确认并在 TASK-062 落地
- 决策：性能报告的权威来源是 schema v16 `generation_timing_event`，现有滚动诊断只保留故障窗口。事件只保存有限 phase/milestone/outcome、非负计数、epoch、elapsedRealtime、boot 指纹和域分离关联指纹；同 boot duration 才可计算，跨 boot 明确不可用。
- 原因：Job/Stage 的墙上时间不足以区分排队、首段、正文、派生和提交，系统调时会造成负耗时；把长篇全部事件塞进 512 条诊断环又会覆盖历史。独立加密追加表可长期汇总，同时避免保存正文、提示词、端点和密钥。
- 后果：TASK-063～068 必须复用同一时序契约；失败、拒绝、暂停、取消和未知结果写有限终态，不用自由文本。TASK-064 负责全阶段生产发射，TASK-062 本身不声称已有 total runner 或真实模型速度数据。

## DEC-056 Fake 延迟使用虚拟时钟，基准报告保留全部失败样本

- 状态：已确认并在 TASK-063 落地
- 决策：固定延迟、5 分钟慢流和断流测试使用独立 `provider:fake` 脚本与协程可取消虚拟时钟，不真实等待几分钟。P50/P95/最慢值由正式时序报告聚合，报告同时保留总样本、NotApplicable 和每种不可用原因；不能只对成功样本报快值后隐藏失败。
- 原因：真实长等待会让回归慢且容易受机器调度影响；只报成功样本又会把断流、跨 boot 或缺提交伪装成更快的性能。Fake adapter 必须可复用，但不能获得重试、预算、状态机或 watchdog 权力。
- 后果：TASK-063 能在秒级测试时间内证明 BODY 延迟和 UNKNOWN 语义。正式提交、第一章和自动慢服务处置仍需 TASK-064/066，不因 Fake BODY 达标而提前关闭。

## DEC-057 total runner 复用现有持久游标，先关闭双租约崩溃窗口

- 状态：已确认，TASK-064 Phase 1A 已落地
- 决策：不增加 runner table 或第二游标。`generation_job.current_stage_id`、Job/Stage 状态、租约与 Attempt 链继续是唯一恢复事实；runner 只领取和分发，业务仓库继续原子提交与推进。Job lease 已领取但 Stage lease 尚未领取的崩溃窗口，使用 current Stage READY+无 lease 的有界扫描和 exact CAS 恢复。
- 原因：现有提交仓库已经把 Stage 成功、动态后继、后继激活和 Job 游标推进放在单一事务中；新增第二游标只会增加漂移。相反，原维护器从有 lease 的 Stage 起扫，确实漏掉了 Job-only lease 崩溃窗口，可能永久停在 RUNNING。
- 后果：空闲 RUNNING Job 可在 60 秒默认租约到期后安全回到 READY，且不能抢走已领取 Stage 的执行。正常多阶段续跑、heartbeat、contract-aware dispatcher、RETRY_WAIT 和 UNKNOWN 仍需 TASK-064 后续子阶段，不能因本决策提前宣布 runner 完成。

## DEC-058 runner 持有一个 Job token 跨 Stage 续跑，不按 owner 收养旧租约

- 状态：已确认，TASK-064 Phase 1B 已落地
- 决策：READY queue 只领取 Job lease；业务提交推进 current Stage 后，同一运行实例继续用原 `GenerationLeaseToken` heartbeat 并读取新 Stage。进程重启不通过 owner 字符串扫描或收养旧 lease，等待 Phase 1A 的超时维护路径精确回收后重新领取。
- 原因：正常多阶段 Job 在 Stage 交接时保持 RUNNING；每 Stage 重领 Job 会违反状态机。反过来，持久 owner 不是足够的进程身份，仅凭相同字符串收养旧 lease 会让新旧进程同时自认拥有执行权。
- 后果：Job token 是运行实例内的连续执行凭证，Stage token 仍由后续 executor 层独立获取和 heartbeat。queue 不拥有业务提交、Attempt 或 Provider 权限；双层 heartbeat 和 contract-aware dispatcher 仍是 TASK-064 后续工作。

## DEC-059 current Stage 领取与双 lease heartbeat 必须单事务

- 状态：已确认，TASK-064 Phase 1C 已落地
- 决策：只有持精确 Job token 的 runner 可以领取该 Job 的 current READY Stage；领取事务先续 Job 再取得 Stage lease。活跃执行续租同样在一个 Room 事务内更新 Job/Stage，两 token 必须为同一 runner owner，任一失败整笔回滚。
- 原因：单独调用 Stage acquire 无法证明调用者仍拥有 Job 或 Stage 仍是 current；分别 heartbeat 又可能留下只有一层前进的部分状态，干扰维护和恢复判断。
- 后果：既有 executor 不需要复制或迁移业务事务，只在 runner 预领 Stage 后按 same-owner resume。Phase 1C 仍是原语，不等于已有定时 heartbeat 协程、dispatcher 或完整 total runner。

## DEC-060 heartbeat stale 必须区分 lease 丢失与业务 durable handoff

- 状态：已确认，TASK-064 Phase 1D 已落地
- 决策：heartbeat envelope 在 action 存活时定时调用原子双 heartbeat。失败后若权威 Job 证明原 token 下 cursor 已推进，或 Job 已进入清 lease 的完成/等待终态，则停止旧 heartbeat并等待 action；否则取消 action并传播 lease 失败。
- 原因：业务提交可能先原子推进 cursor/完成 Job，action 协程随后才返回。此窗口中旧 Stage heartbeat 必然 stale；若一律取消，会把已成功的提交报告为 lease 丢失并触发不必要恢复。反之，当前 Stage 未变而 token 丢失时继续 action 会造成双执行器风险。
- 后果：runner 可安全包裹本地提交或流式 executor，但 durable boundary 后长时间不返回仍需 watchdog。dispatcher 必须提供精确 Job/Stage token，不得绕过 envelope 直接启动长 action。

## DEC-061 dispatcher route 必须由冻结 source policy 的权威 parser 决定

- 状态：已确认，TASK-064 Phase 2A 已落地派生链 identity
- 决策：dispatcher 不按 `GenerationPhase` 单独选择 executor。它只用严格 `sourcePolicyVersion` 选择唯一权威 parser，完整 parser 成功后才返回有限 route；memory/tracking 重建还必须以正式 rebuild binding 区分，candidate 必须以完整 `artifactRole + phase` 穷举，未知或冲突输入不提供 fallback route。
- 原因：`EXTRACT_MEMORY` 同时承载 memory 与 tracking，phase-only 路由会把不同请求合同、校验器和提交事务接错；靠字段存在性猜测又可能在损坏 JSON 上误发付费请求。
- 后果：当前只覆盖已经有严格冻结来源合同的派生链和 final commit。planning/context/普通 draft 必须先补同等强度的 route identity；下一层还要把 route 与 current exact Job/Stage lease 绑定，不能让调用方用内存伪造 Stage 直接分发。

## DEC-062 executor route 必须绑定 current 双租约事实

- 状态：已确认，TASK-064 Phase 2B 已落地
- 决策：executor registry 不能接受裸 route 或调用方手工构造的 Stage。route 只能由数据库 repository 在同一事务验证 current Job/Stage、`RUNNING + PREPARING`、精确同 owner 双 token、未过期 heartbeat、单调时间和剩余 attempts 后返回；绑定快照构造器与原始 resolver 不对 feature 层开放。
- 原因：Phase 2A 只能证明 contract 自身合法，不能证明它仍是此刻有权执行的 current Stage。缺少租约绑定时，暂停、游标推进、token 被抢占或请求已经落库后仍可能重复分发。
- 后果：Phase 2B 入口是只读授权证明，不续租、不创建 Attempt、不联网。下一阶段有限 registry 必须消费该快照并沿用其中 exact tokens；UNKNOWN/恢复路径不得回到 PREPARING route 重新发送。

## DEC-063 total runner 的 final commit 只能消费 exact-token bound 入口

- 状态：已确认，TASK-064 Phase 2C2 已落地
- 决策：未来 registry 对 `FINAL_CHAPTER_COMMIT_V3` 只能调用 `ChapterFinalCandidateCommitStageExecutorV1.executeBound`，并传入 Phase 2B 快照中的 exact Stage token。不能调用只接 ownerId 的兼容入口，也不能绕过 Stage executor 直接调用 coordinator/repository。
- 原因：ownerId 可在不同 acquiredAt 的租约之间复用。重新读取“同 owner 当前 token”会把旧 route 授权漂移到新租约，破坏 exact lease identity。
- 后果：bound 入口拒绝 READY、错误 acquiredAt、超时和非持租约状态；SUCCEEDED 只读返回 replay。旧 owner-only 入口继续兼容非 total-runner 调用，但不构成 registry 权限。

## DEC-064 executor registry 采用显式白名单，已识别但未就绪的 route 一律失败关闭

- 状态：已确认，TASK-064 Phase 2C3 已落地
- 决策：registry 的公开入口只消费数据库绑定快照；当前白名单只包含 `FINAL_CHAPTER_COMMIT_V3`。其余九条已识别 remote route 必须在穷举分支中显式抛出有限未注册错误，不提供 `else`、通用 Provider executor、phase fallback 或“最接近”适配。
- 原因：route parser 只能证明冻结合同属于哪类工作，不能证明该类工作的生产输入装配、seal/commit、恢复、UNKNOWN 和防重复发送已经完整。提前注册会把“能识别”误当成“可安全执行”。
- 后果：最小 registry 可以安全形成 total-runner 的本地执行切片，同时保持所有远程请求关闭。新增 route 必须以独立测试证明 exact token 传递、零旁路和失败恢复后，才可修改白名单。

## DEC-065 initial draft 不得使用依赖尚未生成正文身份的 candidate source contract

- 状态：已确认，TASK-064 Phase 2D1 审计结论
- 决策：`CANDIDATE_CHAPTER_DRAFT_V1` 在现合同下继续不注册。initial DRAFT 的冻结身份必须只引用 Provider 请求前已经持久化并可重验的 planning/context/scene 证据；不得预填 candidate version/hash、伪造 predecessor 或放宽 revision-only guard。
- 原因：`ChapterCandidateStageBindingV1` 描述的是已有候选正文后的派生/修订链。初次正文输出尚不存在时无法构造真实 candidate hash；resolver、Provider-open、seal 和 recovery 对 BODY+DRAFT 的解释也不一致。
- 后果：下一步先建立独立 initial-draft source contract 和生产 Stage factory，再实现 exact-token streaming adapter。旧的候选派生/修订合同保持不变，避免为了初稿破坏已验证的 revision lineage。

## DEC-066 本地 context route 先建立严格身份，再单独建立 exact-token 执行权

- 状态：已确认，TASK-064 Phase 2D2 已落地 route identity
- 决策：`ASSEMBLE_CONTEXT` 使用独立 source policy 和唯一严格 parser，repository 与 resolver 共享解析结果；route identity 完成后仍保持 registry 未注册，直到 repository 有消费 Phase 2B exact 双租约快照的 bound 入口。
- 原因：现有 context assembly 是纯本地完整事务，但其旧入口只接 Stage token，不能证明调用方仍持有 route snapshot 对应的 exact Job token。把“可识别”和“可执行”分开，能避免 owner 相同但 token 已更新、cursor 已推进或 lease 已过期时误执行本地提交。
- 后果：Phase 2D2 不改变业务事务、Stage 数量、chapter-plan 激活或 Provider 行为。Phase 2D3 负责 exact-token adapter、registry 注册和双 API real Room 证据；未知或损坏合同继续失败关闭。

## DEC-067 本地 context 执行复核与业务提交必须同事务

- 状态：已确认，TASK-064 Phase 2D3 已落地
- 决策：total runner 只能通过 `ChapterContextAssemblyRepository.assembleBound` 执行 context；exact Job/Stage token、current cursor、状态、heartbeat、租约和 attempt 边界的复核必须与 context snapshot、Stage/Job 和后继 cursor 写入属于同一个 Room 事务。旧入口保留，但 registry 不调用它。
- 原因：如果 registry 先在只读事务验证绑定快照，再结束事务调用旧的 Stage-token 入口，Job token 或 cursor 可以在两次事务之间变化，造成陈旧 runner 仍提交本地业务状态。
- 后果：registry 可安全把 context 加入本地白名单；成功 replay 仍只读，九条 remote route保持关闭。后续 chapter-plan 和 initial draft 必须各自取得同等级别的冻结身份与 exact-token 执行证据，不能借用 context 注册权。

## DEC-068 普通 chapter-plan 的长期来源冻结进 initial DRAFT，而不是依赖成功 artifact

- 状态：已确认，TASK-064 Phase 2E1 审计结论
- 决策：普通 `chapter-plan.v1` 成功后，由 plan commit 严格解析并规范化有界 scene execution plan，在同一 SQLCipher 事务中动态创建 initial DRAFT Stage，并把规范计划、plan Stage/Attempt、raw/canonical hash 与 context/progression 证据冻结到 DRAFT 的不可变 `inputSourcesJson`。plan Stage output reference 保存核对 hash 与 DRAFT Stage ID。规范计划目标不超过 48 KiB，完整 DRAFT envelope 继续遵守 64 KiB 上限。
- 原因：成功远程输出使用的 `STREAM_DRAFT` artifact 默认 24 小时后可以清理，只保存 artifact/output reference 会使延后执行的初稿丢失计划。窗口级 `OutlineRevision` 的 CHAPTER 节点又不是章内场景合同；新增专表会带来不必要 migration 和第二套生命周期。
- 后果：artifact 保持提交时证据并可按策略清理；initial DRAFT 获得加密、持久、可重放的请求前来源，无需 schema migration。plan 提交、DRAFT 创建、Usage/Stage/cursor 推进必须原子完成；在严格输入 route、输出合同、目的地/预算、exact-token 执行和 replay 全部通过前不得注册 plan route。

## DEC-069 普通 chapter-plan route 只冻结最小请求前身份且保持未注册

- 状态：已确认，TASK-064 Phase 2E2 已落地
- 决策：`zhijuan.chapter-plan-source.v1` 在既有 plan root 上增加独立 source policy，并严格验证bundle/schema、唯一context Stage依赖、context input/policy/manifest和progression gate；不额外复制bookId、chapterId或可确定推导的contextSnapshotId。resolver新增`CHAPTER_PLAN_V1`，registry继续显式拒绝执行。
- 原因：Stage/Job已经保存target与book，progression gate保存自哈希chapterId/index，context snapshot由context Stage确定并在Provider-open重验。再复制这些值会产生可漂移的第二份事实，且route identity本身不应冒充数据库currentness或发送权限。
- 后果：普通plan可以被有限、严格地识别，同时仍保持零联网。后续exact-token executor必须重新读取Job/Stage/context权威事实，并在输出合同、目的地与三层预算门禁完成前保持registry关闭。

## DEC-070 chapter-plan 输出采用结构合同与动态业务 expectation 双层校验

- 状态：已确认，TASK-064 Phase 2E3 已落地
- 决策：`chapter-plan.v1` 先经过48 KiB严格结构合同，再用请求前权威 expectation核对章节/context、人物、成年人虚构门禁和场景执行策略。严格相关场景至少冻结3个有序过程节点，每节点保存动作、反应、空间、身体、衣着/物品与感官变化，相关余波必填；全章节点不超过64。规范 JSON 递归排序 object key并保持数组顺序。
- 原因：单靠 JSON schema 无法知道哪些人物已确认成年、当前章节是否应有相关场景，也无法防止模型用空节点或把字段顺序差异制造成不同 hash；单靠提示词又不能给后续 DRAFT/consistency 提供可核对的持久过程身份。
- 后果：结构正确但身份、门禁、相关性或严格连续性不符的输出仍失败关闭；非严格场景不得伪造过程节点。该合同不调用 Provider、不提交 Stage，也不自动扩大 registry；目的地/预算、exact-token executor 和 DEC-068 原子提交仍需后续阶段完成。

## DEC-071 远程 plan 接线必须先完成持久目的地确认与原子三层预算预留

- 状态：已确认，TASK-064 Phase 2E4A 审计结论
- 决策：`CHAPTER_PLAN_V1` 在目的地确认和 TASK-083 持久预算完成前继续不注册。`budgetSnapshotJson` 只是不变意图快照，不是余额或 reservation；真正 reservation 必须与 RequestIntent、Attempt 和初始 Usage 在同一事务中写入。目的地确认采用版本化 scheme/host/effective-port/protocol binding，动态校验而不是只依赖 UI 清空字段。
- 原因：当前内存 `BudgetEngine` 没有生产调用，不能抵御进程重启或并发 Job；disclosure 字段也没有生产写入/读取。若直接接远程 plan，会留下并发超支和 host 改变后沿用旧同意的 P0 缺口。
- 后果：实现顺序固定为目的地确认内核、持久预算 reservation、plan 请求绑定/exact-token executor、DEC-068 原子提交。正常章节不逐章打扰用户；确认按目的地复用，runner 每次无交互复核。任何前置证据缺失都在 Provider-open 前失败关闭且不创建请求事实。

## DEC-072 目的地确认绑定 canonical origin、disclosure 版本与 Provider protocol

- 状态：已确认，TASK-064 Phase 2E4B 已落地内核
- 决策：目的地身份为小写 `scheme://host:effectivePort`，忽略 request path 并规范默认端口、尾斜杠与 DNS 尾点；binding hash 同时覆盖 policy/disclosure version 和 protocol ID。新连接默认未确认，接受操作用数据库当前 endpoint 计算并 CAS 写入，每次使用前动态重算。
- 原因：path 变化没有改变数据接收方，不应反复打扰用户；host、端口、scheme、协议或说明的数据类别版本变化则会实质改变发送含义，不能沿用旧同意。仅由 UI 主动清空字段无法防止数据库竞态、迁移错误或损坏。
- 后果：同目的地只确认一次，真实变化失败关闭并要求一次新确认。返回的 evidence 不是跨事务发送许可；后续 TASK-083 必须在预算 reservation+RequestIntent 事务中再次复核它。现有 schema v16 不变，plan route 继续未注册。

## DEC-073 持久预算以不可删除 reservation 为事实源并在 Usage 唯一入口结算

- 状态：已确认，TASK-083 Phase 1 设计冻结
- 决策：schema v17 使用不可变 policy revision+CAS head 和每 Attempt 唯一 reservation；book/daily 占用聚合同范围全部非 RELEASED reservation，不增加可漂移余额计数器。候选 reservation 必须先在 RequestIntent 写事务中取得数据库写竞争权，再把自身计入三层检查，超限整笔回滚。所有 FINAL Usage 与迟到 Provider 升级统一在 `GenerationDao.recordUsage` 事务内按终值结算；UNKNOWN 保留估计，只有 Provider 明确证明未执行才 RELEASED。
- 原因：锁外“先查余额再插入”无法证明跨连接并发安全；把结算散落到二十多个仓库会造成永久占用或重复累计。reservation 明细同时承担并发竞争、重启恢复和审计事实，策略 head 变化不能删除或重置旧用量。
- 后果：实现必须有同库双 Room 实例竞争、跨日、重启、UNKNOWN、迟到 usage、超预留真实用量和 RELEASED 恢复测试。v16 历史 Attempt 标为 enforcement v0，只能继续本地恢复/结算，不能重新打开 Provider；因 v17 前真实 Provider 调用为0，不为旧 UNKNOWN 测试行伪造预算值。plan route 继续未注册。

## DEC-074 普通 chapter-plan 的 RequestIntent 必须消费 exact 双租约快照

- 状态：已确认，TASK-064 Phase 2E5A 已落地
- 决策：普通 `CHAPTER_PLAN_V1` 的首次请求和换日替代请求只能使用数据库签发的 route snapshot 进入 bound preparation；通用 Stage-token prepare 必须拒绝普通 plan。exact Job/Stage token、current cursor、route、heartbeat 与 attempt 边界必须和 reservation、Attempt、Usage、Stage 推进在同一 Room 事务内复核。
- 原因：route snapshot 已经证明调用方持有某一时刻的 Job+Stage 双租约。若随后只把 Stage token 交给通用 RequestIntent 入口，Job token、cursor 或 attempt 边界可以在两步之间漂移，形成 TOCTOU 和 total-runner 授权旁路。
- 后果：普通 plan 的加密草稿可在数据库事务前创建，但任何 bound 复核或预算失败都删除新工件且数据库零写入。首章 bootstrap 旧合同保持兼容；plan route 在请求工厂、expectation、Fake执行、严格解析与DEC-068提交完成前继续未注册。

## DEC-075 普通 chapter-plan 的逐章场景意图只由 arc-window v2 授权

- 状态：已确认，TASK-064 Phase 2E5B 设计冻结
- 决策：逐章 brief 显式冻结 `NOT_APPLICABLE|PLANNED`、精确相关场景数量和参与人物 ID；不可变 CHAPTER OutlineNode 是唯一权威来源。chapter-plan Stage 只消费从目标 node、当前 Story Bible、Prompt Bundle 和 context/progression 重算出的规范 expectation，并在 create/open/commit 三处比对冻结 hash。
- 原因：全书内容呈现档或存在成年人物不能证明每章相关；让 chapter-plan 自决则无法防止模型擅自加戏、删戏或在重启后漂移。新建独立意图表会制造双事实源。
- 后果：`arc-plan.v2`、窗口策略 v2 和 CHAPTER node schema 2 成为普通 plan 前置条件；旧 v1 数据不猜测、不静默解释为无相关场景，只能重建窗口。计划相关场景必须数量精确且覆盖全部计划参与者，未知、非人物、未确认成年、年龄缺失或未满18岁的参与者在联网前失败关闭。首章 fast-lane 保持独立。

## DEC-076 开发进度以可运行纵向切片而不是底层组件数量衡量

- 状态：已确认，2026-08-11 路线重排
- 决策：保留既有可靠性底层，但后续顺序改为统一语义、单章 Fake、3–5 章 Fake、真实 API、物理设备亲手验收，再进入模板、阅读完善和长篇压力。单独新增仓库、校验器或审计器不再构成产品里程碑。
- 原因：当前 10 模块和约 60,100 行 Kotlin 已覆盖大量底层，App 仍停在费用确认占位，书架、阅读和生成启动未接通。继续水平扩展不能验证文字质量和真实速度。
- 后果：27～31 号文档成为当前执行入口；25、26 号文档保留为历史输入。TASK-064 的 WIP 复用，但剩余部分拆入 TASK-125～128，不从零重写。

## DEC-077 外部写作 skill 必须编译为不可执行的内置创作策略包

- 状态：已确认，2026-08-11
- 决策：外部 SKILL.md、脚本和 Agent 工作流只作为研究来源。织卷运行时只消费带来源、许可证、版本、checksum、能力条件和 Prompt 预算的 WritingPolicyPack/PolicyFragment；首版不支持任意 skill 安装或执行。
- 原因：原始 skill 包含工具、私有路径、脚本和大段规则，直接装入 Android 会带来安全、许可证、提示冲突、token 和维护风险。
- 后果：现有 PromptBundleCatalogV1 保留为兼容桥和运行时编译结果；新规则在 TASK-121 起按最小片段选择，未激活能力不得进入本章 Prompt。

## DEC-078 组合小说使用书级能力清单、章级激活和统一状态变化外壳

- 状态：已确认，2026-08-11
- 决策：一本书可以同时启用修仙、恋爱、系统、道具、悬疑和亲密连续性等能力；每章只激活当前相关子集。各能力共享 NarrativeObligation 与 StoryStateDelta 外壳，并拥有自己的合法状态转移规则。
- 原因：用单一固定分类无法描述混合网文；把所有能力永久塞进 Prompt 会浪费上下文并污染无关章节。完全按题材复制子系统又会制造多套事实源。
- 后果：未启用能力零 Prompt/状态负担；能力间通过统一证据、义务和优先级协调。新增能力无需复制 runner，但必须提供版本化规则和测试。

## DEC-079 正常章节采用有限远程调用和合并章后分析

- 状态：已确认，2026-08-11
- 决策：正常路径以流式正文和一次 chapter-post-analysis 合并分析为核心；计划是否独立调用由性能基准决定，严重问题才触发修订。摘要、人物记忆、时间线、伏笔、义务、状态和一致性不按能力分别调用模型。
- 原因：逐阶段远程调用会显著增加首段等待、整章延迟、费用和失败点。现有各类仓库可以继续作为本地验证和持久化目标，不要求保留“一仓库一次模型请求”。
- 后果：正常章远程调用数进入硬指标；合并响应任一 P0 子区块失败时不能部分提交。修订后必须重新获得与最终正文匹配的分析。

## DEC-080 第一份交付是 3–5 章可亲手验证 APK

- 状态：已确认，2026-08-11
- 决策：不等待完整 80/300 章功能才交付。先用 Fake 通过全链，再用 DeepSeek V4 Flash 完成真实合同、单章和 3–5 章测试，并让用户在物理设备上完成创建、阅读、暂停和恢复。
- 原因：用户需要可靠可用，但最关键的文字质量、速度和交互只能通过真实闭环验证。过晚测试会放大返工。
- 后果：真实测试按 31 号文档分级，密钥和正文不得进日志/Git；10 分钟正常章为发布阻断。通过后才扩展模板、阅读精修和 20/80/300 章验证。

## DEC-081 VS-1 严格冻结现有十模块边界

- 状态：已确认，用户于 2026-08-11 明确要求
- 决策：VS-1 前不新增模块。每个任务必须声明主模块、允许配套模块和禁止模块；跨模块功能按模块批次提交。`:app` 只做导航/组装，业务编排属于对应 feature，存储属于 data，协议属于 provider，公共纯合同属于 core。feature 实现依赖不得增加，唯一既有例外仍是 template→creation。
- 原因：模块边界已经有脚本和构建证据；跨界“顺手实现”会重新形成大模块、隐藏依赖和难以定位的回归。
- 后果：`scripts/verify-module-boundaries.ps1` 是跨模块改动的固定检查。只有现有边界直接导致依赖环、崩溃、数据污染或严重 bug 无法修复时，才允许单独评审调整。

## DEC-082 无直接产品或稳定性证据的功能不得主动扩展

- 状态：已确认，用户于 2026-08-11 明确要求
- 决策：当前任务只实现验收必需最小路径。插件、通用扩展点、运行时 skill 管理、可选 Provider、额外设置/页面、抽象层、数据库表和兼容层默认禁止；只有缺失会直接造成不稳定、数据损坏、费用失控、安全问题或已确认严重 bug 时才允许扩展。
- 原因：早期开发已经出现底层宽度超过产品闭环的偏差，继续预建能力会延迟用户验证并增加维护面。
- 后果：候选改进只记录不实现。扩大范围必须写出“缺失能力→具体严重故障→现有结构无法处理”的证据链。

## DEC-083 测试按风险最小化，不以测试数量衡量质量

- 状态：已确认，用户于 2026-08-11 明确要求
- 决策：单模块改动只跑相关测试；跨模块合同跑边界检查、受影响模块和一个真实交接；数据库/安全/预算/Provider-open/runner 原子性跑专项回归；全量 test、Release/R8、双 Android API 和 APK 扫描只在里程碑、发布或对应风险变化时运行。真实 API 收敛为连接证据、一个结构 smoke、一个完整混合章和同书 3–5 章。
- 原因：重复的等价测试会消耗时间、API 费用和模型 token，却不增加独立风险覆盖。
- 后果：每个新增测试必须指向未覆盖的具体故障；已有证据能覆盖时复用，不重复运行。失败定位需要时再增加最小复测。

## ADR 执行状态

| ID | 问题 | 截止 |
|---|---|---|
| ADR-001 | 最低 Android API | Accepted：minSdk 29，compile/target 36 |
| ADR-002 | Hilt 或 Koin | Accepted：Hilt |
| ADR-003 | 数据库/字段加密方案与许可证 | Accepted：Room 2.8.4 + SQLCipher 4.17.0 + Keystore 包装；物理设备和最终 APK 体积仍为发布门禁 |
| ADR-004 | 加密存储下中文全文搜索方案 | Accepted：加密库内 FTS4 + 确定性汉字单/双字 token；真实语料、物理设备和失效链仍为发布门禁 |
| ADR-005 | 正式包名命名空间 | Accepted：`app.zhijuan.reader` |
| ADR-006 | Ollama 是否进入 1.0 | Pending：M4 结束前 |
| ADR-007 | 默认每日/单书 token 上限数值 | Pending：M2 用户实测前 |
