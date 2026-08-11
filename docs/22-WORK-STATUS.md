# 织卷开发状态

> 更新时间：2026-08-09

## 当前里程碑

- M0 技术风险验证：完成（模拟器/桌面证据；物理设备项保留为发布门禁）
- M1 本地骨架与连接：进行中
- M2 单章闭环：进行中（正文流、有限续接、记忆/追踪/检查和纯本地最终提交底层已接通，实际 Stage 执行调度与 UI 待接通）

## 已完成

| 任务 | 状态 | 证据 |
|---|---|---|
| TASK-001 Android 工程和构建基线 | 完成 | Gradle Wrapper 9.4.1；Debug APK 构建成功 |
| TASK-002 模块/package 边界 | 完成 | `app`、`core:model`、`core:task`、`core:database`、`core:security`、`core:backup`、`core:network`、`core:diagnostics`、`provider:common`、`provider:stream` 可独立编译 |
| TASK-008 ADR-001~005 收束 | 完成 | ADR-001~005 全部 Accepted；每项剩余物理设备/发布跟进已明确，不伪装成已验证 |
| TASK-040 Job/Stage 状态机 | 完成 | Job 13/Stage 21 合法转换穷举；Room CAS、单调时间、全阶段完成门禁、租约释放和跨表事务防旁路；API 35/API 30 Generation 各 13/13、数据库全量各 45/45 |
| TASK-041 阶段租约、心跳和回收 | 完成 | owner+acquiredAt 凭证；15 秒心跳/60 秒精确到期；双执行器 CAS 防重和旧执行器栅栏；请求前回队、请求后恢复审计；双 AVD Generation 各 17/17、数据库全量各 49/49 |
| TASK-042 RequestIntent 和 Attempt 审计 | 完成 | Attempt+UNKNOWN Usage+Stage 原子提交后才签发一次性发送 permit；claim 前复查证据并续租；敏感快照拒绝、冲突回滚、过期授权失败；双 AVD Generation 各 21/21、数据库全量各 53/53 |
| TASK-043 加密流式草稿运行时 | 完成 | 审计前随机 artifact、2 秒/32 KiB 节流、4 MiB 上限、修订栅栏、崩溃识别及 24h/7d/24h 清理；Provider 仅从一次性授权打开并独立心跳；双 AVD Security 各 18/18、Database 各 58/58、Generation 各 3/3 |
| TASK-044 结构化输出解析与一次修复 | 完成 | 严格 UTF-8/JSON/重复键/资源上限/schema 版本与显式迁移；一次持久化有界修复、第二次/不可修复暂停；合法结果只到 `COMMITTING`；双 AVD Database 各 62/62、Generation 各 6/6 |
| TASK-045 章节原子提交事务 | 完成 | 内部校验 permit、artifact/hash/租约复核；正文版本、摘要/人物事件/事实/时间线/伏笔、FINAL Usage、Stage、下一 Stage/Job 与书进度同一事务；精确重放、并发去重、用户改稿防覆盖、故障全回滚；API 35/API 30 Database 各 68/68 |
| TASK-046 暂停/继续/停止/取消 | 完成 | 持久父 Job 控制意图；发送前暂停、在途 Provider 取消/加密草稿 flush/Attempt+FINAL Usage+Stage+Job 原子安全点、停止覆盖暂停、继续不直发、迟到回调栅栏、本地校验/提交安全点；API 35/API 30 Database 各 75/75、Generation 各 7/7 |
| TASK-047 未知结果恢复审计 | 完成 | 持久 Attempt/Usage/草稿/提交/租约裁决；Provider 原请求查询与生成分离；仅明确未执行且本地证据一致才自动回队，其余等待远端或用户确认；确认不联网且并发 CAS 去重；API 35/API 30 Database 各 81/81、Generation 各 9/9 |
| TASK-048 前台生成服务与通知控制 | 完成 | 私有 `dataSync` FGS、通用私密持续通知、暂停/停止持久控制、5 秒安全点退场、Android 15 `SYSTEM_FGS_TIMEOUT` 独立落盘与 1.5 秒硬停止；API 35 真实系统限时探针通过，双 AVD App 各 42/42、Database 各 82/82、Generation 各 9/9 |
| TASK-049 WorkManager 恢复与维护 | 完成 | Release 唯一启动/周期调度、无网络能力的有界扫描、Stage+Job 双租约原子回队、未知结果保守审计、控制结算和受保护草稿清理；双 AVD App 各 45/45、Database 各 84/84、Generation 各 9/9，Release/R8 与统一安全门禁通过 |
| TASK-050 Prompt Bundle 与阶段/场景契约 | 完成 | `zhijuan.prompt-bundle.v1`/schema 1、13 阶段全覆盖、不可变快照确定性绑定、Provider 前准备；细写+避免淡出+成年门禁自动装配严格身体/感官连续性且不提高其他题材维度；双 AVD App 各 45/45、Database 各 87/87、Generation 各 9/9，真实 API 0 |
| TASK-051 故事种子、故事圣经与全书总纲 | 完成 | 冻结三阶段 Job；三份完整 schema、严格解析与跨文档校验；逐阶段不可变提交、加密证据、精确重放和事务回滚；双 AVD App 各 45/45、Database 各 87/87、Generation 各 11/11，Release/R8 与安全门禁通过，真实 API 0 |
| TASK-052 分卷与窗口式分章规划 | 完成 | `zhijuan.arc-window-policy.v1`：卷最多 40 章、逐章窗口最多 8 章、剩 3 章补窗；`arc-plan.v1` 严格 schema、本地范围冻结、单 Stage Job、不可变窗口 revision；连续 1–8/9–16 两窗和父 hash 回滚双 AVD 通过，真实 API 0 |
| TASK-053 第一章快车道 | 完成 | 独立 Bootstrap v1 固定前三章粗计划并严格复核种子/成年/硬规则；第一章后完整规划绑定当前首章版本；第二章 Provider-open 与正文提交由数据库圣经/总纲/窗口/上一章/适配证据硬阻断；双 AVD App 45/45、Database 87/87、Generation 15/15，真实 API 0 |
| TASK-054 章前上下文预算组装 | 完成 | v1 确定性整项预算完整保留成年人/身份、应用/世界硬规则、禁改项、目标卷章、上一章承接及存在的当前状态/到期伏笔/用户补充；可选记忆整项省略；未知/必需项超限联网前阻断；不可变 snapshot/manifest 精确重放并在 Provider-open 前重验来源；双 AVD 各 149/149，Release/R8、371 项统一门禁与安全扫描通过，真实 API 0 |
| TASK-055 章节正文与截断续接 | 完成 | `chapter-draft.v1` 单字段增量解码；新 Attempt/Usage/加密 artifact；96 码点精确锚点只校验不重写；最多 3 次自动续接、4 MiB/attempt/格式失败关闭与无网络崩溃结算恢复；双 AVD 各 153/153，JVM 唯一 362，Release/R8、371 项统一门禁与 15 APK 安全扫描通过，真实 API 0 |
| TASK-056 摘要、人物事件与事实提取 | 完成 | `chapter-memory.v1` 严格结构、来源版本/hash/章节/实体交叉校验；只允许 `STORY_CANON/INFERRED`；身体、情绪、关系、知识、持有物、承诺和秘密的结尾状态不含糊省略；正式版本重建双门禁、原子提交/重放及格式失败用量结算；双 AVD 各 156/156，JVM 唯一 371，Release/R8 与统一门禁通过，真实 API 0 |
| TASK-057 时间线与伏笔投影 | 完成 | `chapter-story-tracking.v1` 严格结构；正文/记忆/旧伏笔/实体四快照双门禁；实际事件与实体地点约束；PLANT/DEVELOP/RESOLVE/ABANDON 白名单；追加转换台账和中间依赖 stale；schema v8、原子提交与精确 replay；双 AVD 各 159/159，JVM 唯一 381，Release/R8 与安全门禁通过，真实 API 0 |
| TASK-058 本地+模型一致性与呈现检查 | 完成 | 本地确定性前置；`chapter-consistency-report.v1` 23 项严格矩阵；候选/本地/场景/实体/证据/过程来源绑定；成年人失败关闭；严格过程逐节点证明；固定严重度/修订动作；TEST-039 负例；双 AVD 各 162/162，JVM 唯一 411，Release/R8 与安全门禁通过，真实 API 0 |
| TASK-059 有限修订与提交门禁 | 完成 | 有限策略、候选来源/修订/派生重建、final Stage v3、受保护 artifact 与数据库恢复、唯一最终协调器、原子提交和 COMMIT_CHAPTER 专用执行入口已完成；生产旁路审计无实际绕过；API 35/API 30 各 187/187，Release/R8、467 项 JVM、371 项统一门禁、安全扫描与备份排除通过。当前没有总 runner，留给后续整 App 调度接线 |
| TASK-060 中文 FTS 多路召回 | 完成 | v9/v10 加密 FTS、六类原子索引/回填、三路有界召回、权威 hydration、强制/最近/相关选择及章前预算/manifest/Provider-open 全部接通；FTS4 安全的全字母数字 CJK 双字 token v2 会让旧 v1 索引自动重建。10,000 文档固定中文集 20/20，误召回与确定性回归通过；API 30/API 35 热查询中位约 6.07/4.35 ms，数据库全量各 143/143，真实 API 0 |
| TASK-061 编辑后派生失效和重建 | 完成 | 显式 ordinal 4/6/8… 通用逐章 Stage、直接前驱/时间证明、连续 retirement 前缀、Provider-open/commit、tracking+aggregate 原子推进与 replay 已完成。TEST-033 10 章编辑第 3 章和生产上下文旧派生排除通过；双 API 数据库各 187/187、生成各 35/35，JVM 70/70 + 117/117，797-task Release/R8 与安全门禁通过 |
| TASK-062 脱敏生成时序、基准时钟和报告器 | 完成 | schema v16 SQLCipher 追加事件、phase 隔离、boot-bound monotonic 时钟、失败关闭报告器和 BODY Fake 流接线；双 API 数据库各 192/192、生成各 37/37；统一门禁通过 |
| TASK-063 固定延迟/慢流/断流 Fake Provider 性能夹具 | 完成 | 独立 Fake 模块、虚拟时钟、取消/断流/UNKNOWN、失败可见分位数；20 个参考 BODY 首段 P95 19.70 秒、正文 P95 174 秒，正式提交保持缺事件；全 phase 归 TASK-064 |
| TASK-064 全阶段 dispatcher 与持久 total runner | 进行中 | Phase 1A～2E5A与TASK-083已完成；恢复、双lease、route binding、final/context本地执行、chapter-plan严格合同、三层预算/目的地门禁及plan exact-token RequestIntent准备已通过双API验证。generic Stage-token旁路已关闭，plan仍未注册；下一步冻结权威expectation/请求快照并接Fake执行、严格解析和原子提交/initial DRAFT |
| TASK-083 三层预算和原子预留 | 完成 | schema v17策略/三层原子预留、RequestIntent v1、Usage结算、明确未执行释放、迟到Provider回补、跨日替代和实际profile/adapter/current disclosure/frozen reservation匹配已闭环；双API reservation各43/43、executor各23/23、数据库各272/272、生成各48/48，801-task门禁通过。total runner接线归TASK-064 |
| TASK-005 SSE/NDJSON 流分帧 | 核心完成 | 任意字节分片、中文 UTF-8、CR/LF/CRLF、EOF 和安全边界测试通过；协议 JSON 映射待适配器阶段 |
| TASK-003 Room + 加密候选实机尖峰 | 模拟器通过/ADR Accepted | 加密读写、20 万字、迁移、口令生命周期、Keystore 丢失失败关闭通过；ABI 压缩体积已盘点；物理设备待验证 |
| TASK-004 加密中文检索尖峰 | 模拟器通过/ADR Accepted | 10,000 文档、20 个查询召回 100%；热查询中位约 1.45 ms；真实语料/物理设备待验证 |
| TASK-006 Android 15 FGS/恢复实验 | 模拟器通过 | 3 秒限额约 3.64 秒触发 onTimeout 并自停；force-stop 后重启识别 RECOVERY_REQUIRED；物理设备待验证 |
| TASK-007 Compose 长章/万章目录性能实验 | 模拟器通过 | 20 万字正文仅组合 4 个可见段落；1 万章目录仅组合 13 行；跳到末尾和点击均通过；横竖窄屏夹具通过 |
| TASK-008A 加密备份/原子恢复尖峰 | 模拟器通过 | 15 项 JVM 故障测试 + 2 项 Android 测试通过；错口令/损坏/校验失败/提交前中断不覆盖活动库；64 MiB 有界流通过 |
| TASK-008B 自定义端点网络安全尖峰 | 通过 | 16 项 JVM 测试 + 1 项 Android 策略测试；远程 HTTP、跨 origin/降级/POST 重定向、错误证书和日志脱敏边界通过 |
| TASK-009 canary/secret 扫描 | 完成 | 假密钥能让联合构建失败；当前源码与 7 个 APK 扫描为 0 |
| TASK-010 Book/Chapter/Version 正式 schema | 完成 | 加密正式库 schema v1 已导出；书/快照/章节/不可变版本、复合外键、分支来源、CAS 提交和幂等提交共 9 项 Android 事务/约束测试通过 |
| TASK-011 Job/Stage/Attempt/Usage 正式 schema | 完成 | schema v2 + 显式 v1→v2 迁移；请求意图先落库、状态 CAS、租约、结果未知、重试父链和迟到 usage 单向升级共 8 项 Android 测试 + 2 项 JVM 状态测试通过 |
| TASK-012 Bible/Outline/Memory 正式 schema | 完成 | schema v3 + 显式 v2→v3 迁移；不可变圣经/大纲修订、人物年龄事实、章节版本来源约束及编辑后 stale 失效链共 5 项专项 Android 测试 + 1 项迁移测试通过 |
| TASK-013 Template/Revision/Snapshot/Tag 正式 schema | 完成 | schema v4 + 显式 v3→v4 迁移；允许字段白名单、来源链、幂等书源提取、不可变使用快照、分类标签和不复制正文/运行数据共 6 项专项 Android 测试 + 1 项迁移测试通过 |
| TASK-014 Android Keystore Secret Store | 完成 | 每记录独立 Keystore 别名与 AAD、原子轮换、无密文撤销墓碑、锁定清零、用途隔离和失败关闭；4 项 JVM codec 测试 + 6 项 Android 专项测试通过 |
| TASK-015 小说、草稿和恢复点加密存储 | 完成 | 正式正文由 SQLCipher 主库/WAL/SHM 统一保护；草稿/恢复点采用每文件独立 Keystore key、64 KiB 分块认证、认证尾记录、修订 CAS 与原子换版；4 项 JVM 格式测试 + 6 项 Android 专项测试通过，正文/文件 canary 0 命中 |
| TASK-016 Android 自动备份排除 | 完成 | `allowBackup=false`；Android 12+ cloud/device transfer 与 Android 11- 九域全排除；源码/Debug/Release 清单校验，以及 API 35、API 30 各 3 项设备测试和 `bmgr` 拒绝均通过；小米 Android 16 厂商实机因设备拒绝 USB 安装待补测，不阻塞旧系统门禁 |
| TASK-017 完整数据库迁移测试框架 | 完成 | 唯一生产迁移注册表现覆盖 v1/v2/v3/v4→v5；全路径保留核心小说及已有生成/记忆/模板数据；真实 SQLCipher v1→v5、计数/哈希/完整性/外键、未来 v6 缺失迁移失败关闭且不清库共 4 项专项测试，在 API 35 与 API 30 均通过 |
| TASK-018 脱敏日志和诊断事件基础 | 完成 | 新增 `core:diagnostics`；无自由文本入口，外部关联值只留域分离哈希，异常只留类型；512 条/512 KiB 加密滚动存储，密文损坏不回退明文；4 项 JVM + API 35/API 30 各 3 项专项测试通过 |
| TASK-020 ProviderAdapter 统一领域接口 | 完成 | 统一连接、能力、分层请求、用量、取消和标准流事件；未知能力失败保守，协议/模型错配拒绝，事件单终态，敏感值默认字符串不泄漏；8 项 JVM 契约测试通过 |
| TASK-021 Provider 安全传输出口 | 完成 | 新增 `provider:transport`；安全路径/query/header、Secret Store 短租借、正文清零、POST/跨域重定向拒绝、幂等取消、响应上限和加密诊断；8 项 JVM + API 35/API 30 各 1 项通过 |
| TASK-023 OpenAI Chat Compatible 适配器 | 完成 | 新增 `provider:openai-chat`；OpenAI/DeepSeek/最小中转三模式、可清零请求 JSON、SSE/JSON/usage/拒绝/错误映射；9 项 JVM + API 35/API 30 各 1 项通过 |
| TASK-022 OpenAI Responses 适配器 | 完成 | 新增 `provider:openai-responses`；`store:false`、原生角色层级、结构化输出、JSON/SSE/usage/拒绝/语义终态映射；9 项 JVM + API 35/API 30 各 1 项通过 |
| TASK-024 Anthropic Messages 适配器 | 完成 | 新增 `provider:anthropic`；固定版本/x-api-key、system/messages、JSON/SSE/累计 usage/拒绝/错误/终态映射；9 项 JVM + API 35/API 30 各 1 项通过 |
| TASK-025 Gemini GenerateContent 适配器 | 完成 | 新增 `provider:gemini`；x-goog-api-key、store:false、system/contents、当前 responseFormat、JSON/SSE/finish/safety/usage/error 映射；9 项 JVM + API 35/API 30 各 1 项通过 |
| TASK-027 模型能力登记表与探测证据 | 完成 | schema v5 + `provider:capability-storage`；来源分层、端点指纹、过期/版本失效、明确探测证据、风险确认覆盖/一键恢复、四适配器接线；16 项新增 JVM + 双 AVD 持久化/迁移专项通过 |
| TASK-028 标准错误与统一重试分类 | 完成 | 13 类失败矩阵、四态送达证据、正文/费用风险闸门、3 次/15 分钟上限、秒数/HTTP-date Retry-After、Gemini 配额保守分流；26 项新增 JVM + API 35 全量/API 30 适配器专项通过 |
| TASK-029 连接测试与模型列表 | 完成 | 默认零生成模型发现、严格手填未验证回退、可选单次 16-token 通用探针、共享 60 秒 deadline、能力证据写入；16 项编排 + 四适配器编码路径 JVM 测试通过 |
| TASK-030 首次启动本地保存说明 | 完成 | 暖纸/深色说明页、本地/远程/密钥三类边界、跳过与继续同路、重建和系统返回；4 项 Compose 测试在普通/200% 字体/横屏/800dp/API 30 场景通过 |
| TASK-031 连接向导 UI | 完成 | 四类官方/兼容中转、零生成模型列表、自动推荐、严格手填回退、可选 16-token 完整验证、失败尾四位重试与临时 secret 清理；6 项新增测试在 API 35/API 30 通过，API 35 另过 200%/横屏 |
| TASK-032 连接列表、当前连接和编辑 | 完成 | schema v6 加密持久连接、向导原子提交、重启直达列表、当前置顶/一键切换、名称与已发现模型编辑、确认删除和密钥撤销；8 项新增测试，双 AVD + 200% 字号/横屏通过 |
| TASK-033 极简创建页面 | 完成 | 已有连接时直达创作；一句设想即可准备结构化草稿，题材可空、中篇 300 章/均衡默认、留白/均衡/细写隐蔽命名；当前连接不泄密；6 项新增 Compose 测试通过浅深主题、重建、200% 字号、横屏、375dp 窄屏和双 AVD |
| TASK-034 高级创建折叠区与篇幅规则 | 完成 | 五组自然语言补充默认折叠且全可空；折叠、重建、连接管理往返状态保留；短篇最低/初始 80、中篇最低/初始 300、长篇不预填且由用户必填 301–10,000，自定目标提交 schema v1；篇幅更正后创建页专项在 API 35/API 30 均为 8/8 |
| TASK-035 内容呈现外部/内部映射 | 完成 | 首版留白 2/1/PREFER、均衡 3/2/ALLOW、细写 4/4/AVOID；四个题材维度不随档位提高；双 schema 失败关闭；成年人门禁和严格身体/感官连续性策略有 6 项 JVM 测试，双 AVD 创建 7/7、App 31/31 |
| TASK-036 创建标准化和不可变快照 | 完成 | 原始/标准化输入、确定性题材与标题来源、篇幅/呈现版本、当前模型引用和 SHA-256 哈希原子写入 SQLCipher；schema v7 兼容旧书，API 35/API 30 迁移约束各 14/14、创建交接各 8/8 |
| TASK-037 开始前确认占位/接口 | 完成 | 按书 ID 回读冻结快照，显示章节规模/模型与诚实未知价格；确认只传冻结引用并锁定，加载失败不绕过；API 35/API 30 App 全量各 39/39，200% 字号/横屏专项通过，模型调用 0 |

## 下一步

- 2026-08-09 TASK-064 Phase 2E3 已完成48KiB `chapter-plan.v1`严格输出schema/parser、稳定canonical hash、人物/成年人虚构/场景策略业务交叉校验与严格过程节点/余波门禁；generation JVM 140/140、双API各42/42、801-task门禁通过。registry仍只有final+context；下一步Phase 2E4处理目的地/三层预算和请求绑定，在exact-token执行与DEC-068提交完成前不注册、不启用App内真实付费生成。
- 2026-08-09 TASK-064 Phase 2E4A 已完成发送前缺口审计：现有disclosure字段尚无生产确认/校验，`normalizedDestination`未形成规范origin，`BudgetEngine`无生产调用且`budgetSnapshotJson`不是持久余额。DEC-071冻结顺序为目的地确认→TASK-083原子三层reservation+RequestIntent→plan exact-token执行；审计阶段保持Provider 0和plan未注册。
- 2026-08-09 TASK-064 Phase 2E4B 已完成外部数据目的地确认内核：origin/protocol/version稳定binding、新连接默认未确认、Room事务CAS接受与动态失效、连接实体字符串脱敏；DeepSeek只读审计后补齐host/hash/version/IPv6/端口负例。core:model 17/17，双API数据库各222/222，801-task门禁通过；UI/Provider-open尚未接线，plan继续未注册。
- 2026-08-09 TASK-083 Phase 1 已冻结持久预算设计：不可变policy revision/head、每Attempt唯一reservation、候选先写后聚合、FINAL/迟到Usage唯一DAO结算、跨日重预留和legacy v0发送阻断。DeepSeek只读审计`20260809-075939-b1d748ce`确认双连接并发与单一结算入口为高风险门禁；本阶段0代码/schema/Provider变化，详见工作汇报127。
- 2026-08-09 TASK-083 Phase 2 已完成schema v17与policy core：三张预算表、Attempt v0/null迁移、显式IANA日键、原子policy revision/head和有限reservation guards落地。DeepSeek`20260809-081637-6c382bb4`在30分钟超时前留下允许范围实现，Sol修复API30兼容、状态/币种/Job–Book保护与测试分层；JVM 23+90、双API数据库各226/226、`SECURITY_SCAN_OK`，详见工作汇报128。
- 2026-08-09 TASK-083 Phase 3A 已完成内部原子reservation核心：候选先写后聚合、三层token/金额失败关闭、动态disclosure与派生日键、v1 Attempt/Usage/Stage同事务提交；同Room、双Room同WAL文件及关闭重开竞争都只允许一个胜者。DeepSeek`20260809-091858-713b52af`正常回交，Sol审查并补并发证据；JVM90/90、双API专项各11/11、数据库各237/237、`SECURITY_SCAN_OK`。公开RequestIntent/Provider-open尚未切换，详见工作汇报129。
- 2026-08-09 TASK-083 Phase 3B 已完成公开请求入口切换：调用方不再传入日周期键，流式生成和续写均必须显式提交预算草稿；Provider-open许可会重新核验持久化Attempt/Usage/Job/Stage/reservation证据，旧版v0、错绑或已释放reservation均在联网前失败关闭。DeepSeek主实现超时后由Sol完成审查和收口，窄任务迁移正常回交；统一JVM 590/590、双API数据库240/240、生成模块42/42、App恢复专项2/2、完整离线门禁与安全扫描通过，详见工作汇报130。
- 2026-08-09 TASK-083 Phase 4A 已把v1 Usage与reservation收敛进唯一`recordUsage`事务：PROVISIONAL保留预留、FINAL UNKNOWN保留估计、已知终值确定性替换、迟到Provider同步修正且replay不重复累计。DeepSeek`20260809-114953-91e6dcc2`正常回交，Sol加固全字段CAS、写后回读和Book/daily身份并修正一项旧断言；双API专项23/23、数据库252/252、生成42/42、App恢复2/2及完整离线门禁通过，详见工作汇报131。
- 2026-08-09 TASK-083 Phase 4B 已把Provider明确未执行的UNKNOWN/FINAL Usage、reservation RELEASED和Attempt/Stage/Job回队收进单一恢复事务；普通失败/断网/含糊Provider/本地正文/已知Usage均不能释放。RELEASED后只有迟到FINAL PROVIDER_REPORTED可恢复SETTLED并按终值重计。DeepSeek`20260809-123220-9e8bc700`正常回交，Sol收紧PROVISIONAL前置、时间/CAS和写后回读；双API专项30/30、数据库259/259、生成42/42、App恢复2/2及完整离线门禁通过，详见工作汇报132。
- 2026-08-09 TASK-083 Phase 5A/5B 已完成跨午夜设计审计和Provider-open换日旧请求释放：当前DAILY日键在发送许可前重算，日键变化时旧未发送v1 Attempt、UNKNOWN Usage、reservation、Stage、Job在一个事务中原子结束；有剩余次数回READY，耗尽进入NEEDS_ACTION，旧permit/replay/并发/已发送状态均失败关闭。DeepSeek设计审计正常完成，生产实现运行到45分钟护栏留下WIP，测试运行异常且无差异；Sol收口并双API验证专项35/35+18/18、数据库264/264、生成43/43及592/592 JVM和801-task门禁。新日替代请求仍属Phase5C，详见工作汇报133/134。
- 2026-08-09 TASK-083 Phase 5C 已完成repository级新日替代请求准备：持久队列重新领取Job和当前Stage精确双lease后，专用入口重验Phase5B父Attempt/Usage/RELEASED reservation，创建attemptNo+1、新日reservation和不同的新受保护artifact；非空种子有界复制，空种子也不共享引用。普通prepare旁路、错误token、新日quota、证据变化和并发均失败关闭并清理新工件。DeepSeek只读审计`20260809-155744-bbeaf68d`正常完成，Sol实现/加固并验证双API专项40/40+21/21、数据库269/269、生成46/46及592/592 JVM；total runner路线和实际profile/adapter目的地匹配仍未完成，详见工作汇报135。
- 2026-08-09 TASK-064 Phase 2E5A 已关闭plan RequestIntent授权旁路：普通plan必须携带Room签发的exact Job+Stage route snapshot，双租约/current cursor/heartbeat/attempt边界与v1 reservation、Attempt、Usage、Stage推进在同一事务重验；generic Stage-token prepare和损坏来源回落均拒绝，公开streaming负例会清理新加密工件。双API专项47/47、数据库276/276、generation48/48及801-task门禁通过；plan仍未注册，详见工作汇报138。
- 应用锁、生物识别、`FLAG_SECURE` 和最近任务遮挡已取消，TASK-097 不再开发。数据库/密钥/备份/通知和远程传输安全要求保持不变。

## M0 二次复评结论

- 当前工程底座、状态机、预算、流分帧、加密检索、Android 15 恢复和 Compose 长内容方向均有代码与测试证据。
- 二次复评发现的“恢复失败不覆盖现有书库”和“自定义中转站不泄露密钥”已由 TASK-008A/008B 关闭。
- ADR-003/004 已 Accepted；物理设备、真实中文语料、最终 APK 体积、App 内许可证页和 OEM 差异仍是发布前硬门禁，未被描述为已通过。
- 实际提供方是否接受某种内容尺度只能通过能力与拒绝实测得出；App 不把服务商拒绝伪装成网络错误，也不承诺规避其策略。

## 当前构建基线

- applicationId：`app.zhijuan.reader`；Debug 为 `app.zhijuan.reader.debug`。
- minSdk 29，target/compileSdk 36。
- AGP 9.2.1，Gradle 9.4.1，Kotlin 2.3.21/built-in Kotlin。
- Hilt 2.59.2，Compose BOM 2026.06.00，Lifecycle 2.10.0。
- 加密尖峰：Room 2.8.4、SQLCipher for Android 4.17.0、AndroidX SQLite 2.6.2。
- 构建命令：`scripts/verify-build.ps1 -Offline`。
- 当前 Debug APK：`app/build/outputs/apk/debug/app-debug.apk`。
- 当前统一门禁生成的 Gradle JVM 报告：592 个用例，0 失败、0 错误、0 跳过。
- TASK-042 新增发送前审计证据：RequestIntent、Attempt、UNKNOWN Usage 与 Stage 在同一事务中提交，提交完成后才签发一次性 permit；真正打开 Provider 连接前再次核对持久化证据并刷新当前租约。Generation 数据库专项在 API 35/API 30 各 21/21，数据库模块全量各 53/53。
- TASK-043 新增加密流运行时证据：Security 全量在 API 35/API 30 各 18/18，Database 全量各 58/58，Generation feature 各 3/3；草稿节流、旧 writer 栅栏、backup-only 恢复、审计后 Provider open、独立心跳和正式章节隔离均通过，真实 API 调用 0。
- TASK-044 新增结构校验证据：8 项严格解析 JVM 测试；Database 全量在 API 35/API 30 各 62/62，Generation feature 各 6/6；一次持久修复、第二次暂停、并发 CAS、超契约大小暂停和正式章节隔离均通过，真实 API 调用 0。
- TASK-045 新增章节提交证据：Database 全量在 API 35/API 30 各 68/68；正常原子发布、草稿清理后幂等重放、派生故障全回滚、双协程去重、用户改稿防覆盖和末 Stage 完成 Job 均通过，真实 API 调用 0、实体设备写入 0。
- TASK-046 新增生成控制证据：Job 合法转换增至 21 个、Stage 合法转换增至 35 个；Database 全量在 API 35/API 30 各 75/75，Generation feature 各 7/7；发送前暂停、在途取消、停止全阶段、租约到期代结算、校验/提交安全点、适配器取消和并发时间顺序均通过，真实 API 调用 0、实体设备写入 0。
- TASK-047 新增未知结果恢复证据：Job 合法转换增至 22 个、Stage 合法转换增至 42 个；Database 全量在 API 35/API 30 各 81/81，Generation feature 各 9/9；intent-only 保守门、远端查询分流、证据矛盾、远端完成、本地结果恢复和并发确认均通过，真实 API 调用 0、实体设备写入 0。
- TASK-048 新增前台执行宿主证据：App 全量在 API 35/API 30 各 42/42，Database 各 82/82，Generation feature 各 9/9；API 35 将 `data_sync_fgs_timeout_duration` 配置为 3,000ms 后，约 4,648ms 观察到生产服务退出且 Job 为 `PAUSED(SYSTEM_FGS_TIMEOUT)`；Release 不含 Debug 探针，真实 API 调用 0、实体设备写入 0。
- TASK-049 新增恢复维护证据：App 全量在 API 35/API 30 各 45/45，Database 各 84/84，Generation feature 各 9/9；请求前 Stage+Job 双租约原子回队，RequestIntent 无 Provider 证据时进入未知结果且 Attempt 仍为 1；启动/周期调度去重、无网络约束和电量/存储条件通过，Release/R8 保留 Worker，真实 API 调用 0、实体设备写入 0。
- TASK-050 新增 Prompt 绑定证据：11 项 core contract + 4 项 Provider bridge JVM；13 阶段稳定覆盖、确定性版本/哈希、80/300/长篇边界、题材维度隔离、成年人自动事实/阻断、细写身体与感官连续性和脱敏均通过。App 全量在 API 35/API 30 各 45/45，Database 各 87/87，Generation feature 各 9/9；Release/R8 和统一安全门禁通过，真实 API 调用 0、实体设备写入 0。
- TASK-051 新增初始规划证据：11 项结构契约/映射 JVM + 3 项 JobFactory JVM；本地假 Provider 三阶段端到端和错误 stage 回滚在 API 35/API 30 各 2/2。App 全量各 45/45、Database 各 87/87、Generation feature 各 11/11；Release 460 tasks、统一门禁 371 tasks、15 APK 安全扫描与备份排除均通过，真实 API 调用 0、实体设备写入 0。
- TASK-052 新增窗口规划证据：5 项策略 + 3 项 JobFactory + 5 项结构/映射 JVM；本地假 Provider E2E 扩展为 4 项，连续 1–8/9–16 两窗、精确重放和错误父 hash 全回滚。App 全量各 45/45、Database 各 87/87、Generation feature 各 13/13；Release/R8 460 tasks、统一门禁 371 tasks 通过，真实 API 调用 0、实体设备写入 0。
- TASK-053 新增快车道证据：4 项推进策略 + 6 项 JobFactory + 4 项 Bootstrap/Provider bridge JVM；本地假 Provider E2E 扩展为 6 项，覆盖篡改年龄回滚、首章放行、第二章规划前阻断、伪造 gate hash 阻断、首章后 Bible→Master→Window 放行与完整模式对照。App 全量各 45/45、Database 各 87/87、Generation feature 各 15/15；Release/R8、统一门禁 371 tasks、15 APK 安全扫描与备份排除通过，真实 API 调用 0、实体设备写入 0。
- TASK-054 新增上下文证据：5 项预算策略 + 3 项双阶段 JobFactory JVM；真实 Room/SQLCipher Android 专项 2 项覆盖必需事实保留、超大可选时间线整项省略、精确重放、Provider-open 放行/来源变化拒绝及未知容量无副作用阻断。App 全量各 45/45、Database 各 89/89、Generation feature 各 15/15；Release/R8 460 tasks、统一门禁 371 tasks、14 APK 安全扫描与备份排除通过，真实 API 调用 0、实体设备写入 0。
- TASK-055 新增正文续接证据：15 项 JVM 覆盖 Unicode 锚点、任意 JSON 分片、严格 schema 和续接提示绑定；Generation Android 新增 4 项覆盖终态分类/崩溃恢复、正常续接、错误锚点和 3 次上限。App 全量各 45/45、Database 各 89/89、Generation feature 各 19/19，共 153 项；Release/R8 460 tasks、统一门禁 371 tasks、15 APK 安全扫描与备份排除通过，真实 API 调用 0、实体设备写入 0。
- TASK-056 新增章节记忆证据：9 项 JVM 覆盖严格 schema、来源/实体交叉校验、重复与 Canon 拒绝、确定性映射、请求绑定和脱敏；Generation Android 新增 3 项覆盖正式版本完整提取/精确重放、RequestIntent 后换版本联网前拒绝、未知实体一次修复且零派生写入。App 全量各 45/45、Database 各 89/89、Generation feature 各 22/22，共 156 项；Release/R8 460 tasks、统一门禁 371 tasks、15 APK 安全扫描与备份排除通过，真实 API 调用 0、实体设备写入 0。
- TASK-057 新增故事追踪证据：10 项 JVM 覆盖严格 schema/来源/实体/地点/状态转换/ABANDON/去重、Job 子契约绑定、确定性映射和脱敏；Generation Android 新增 3 项覆盖 timeline + DEVELOP + PLANT 原子提交/精确 replay、旧伏笔快照变化 Provider 调用 0、未知伏笔一次修复且台账零写入；Memory/迁移覆盖 schema v8 与中间转换依赖 stale。App 全量各 45/45、Database 各 89/89、Generation feature 各 25/25，共 159 项；JVM 原始 396/唯一 381；Release/R8 460 tasks、统一门禁 371 tasks、15 APK 安全扫描与备份排除通过，真实 API 调用 0、实体设备写入 0。
- TASK-058 新增一致性检查证据：5 项场景策略、8 项本地确定性检查、严格结构/请求/接受门禁等 JVM 测试；Generation Android 新增 3 项覆盖严格全通过、缺失检查项一次修复零报告和换候选联网前阻断。App 全量各 45/45、Database 各 89/89、Generation feature 各 28/28，共 162 项；JVM 原始 426/唯一 411；Release/R8 460 tasks、统一门禁 371 tasks、15 APK 安全扫描与备份排除通过，真实 API 调用 0、实体设备写入 0。
- TASK-059 B3 新增最终本地协调器证据：协调器 JVM 6/6，相关快照/恢复/映射/接受链 33/33；API 35 最终候选数据库专项 25/25；统一门禁 371 tasks、`SECURITY_SCAN_OK` 和备份排除通过。DeepSeek 仅作为隔离编码模型参与单文件实现，App 内真实 Provider 调用 0、实体设备写入 0。
- TASK-059 B4 新增最终 Stage 执行入口证据：执行器 JVM 8/8，最终提交相关链 41/41；READY 精确领取、PREPARING/COMMITTING 同 owner 恢复、SUCCEEDED 零提交及诊断脱敏通过；统一门禁 371 tasks、`SECURITY_SCAN_OK` 和备份排除通过。App 内真实 Provider 调用 0、实体设备写入 0。
- TASK-059 B5 生产旁路审计：新 executor→coordinator→final repository 链内部接线正确，但当前无总 runner 调用；旧 `ChapterGenerationCommitRepository` 和 `LibraryDao.commitChapterVersion` 也无生产调用方，未发现当前实际旁路。DeepSeek 只读复核未产生文件差异。
- TASK-059 B6 与最终收口：修复旧 DRAFT Stage 的合法非对象来源 `[]` 被候选 binding 误判的问题；非对象合法 JSON 继续作为未绑定处理，畸形 JSON 和当前候选 policy object 仍严格失败关闭。新增 3 项 JVM 回归；Compose 高级字段测试改为不拉起 IME 的语义写入，三处截图名增加唯一时间戳，消除连续回归的测试环境碰撞。API 35/API 30 均为 App 45/45、Database 114/114、Generation 28/28，共 187/187；Release/R8 成功；统一门禁 371 tasks、JVM 467 项、`SECURITY_SCAN_OK` 和备份排除通过。TASK-059 在当前任务边界正式完成，真实 API 0、实体设备写入 0。
- TASK-060 总收口：正式加密库 10,000 文档固定中文集 20/20；FTS4 下划线拆词造成的不相邻字符误命中已由全字母数字 token v2 关闭，旧 v1 标记自动重建。API 30/API 35 热查询中位约 6.07/4.35 ms，Database 全量各 143/143；统一门禁 797 tasks、Release/R8、源码与 5 APK 安全扫描、备份排除及 diff 检查通过，真实 API 0、实体设备写入 0。
- TASK-061 Phase 1：新增用户编辑专用原子事务和编辑 CAS；10 章 TEST-032 场景、重放/冲突/跨书/过期 current、正式 FTS 删除均通过。API 30/API 35 Database 各 146/146；统一门禁 797 tasks、Release/R8、安全扫描和备份排除通过，真实 API 0、实体设备写入 0。TASK-061 保持进行中。
- TASK-061 Phase 2A：新增只读重建影响计划和完整区间版本栅栏；10 章编辑第 3 章得到 32 个稳定步骤、1 READY/31 BLOCKED、17 个潜在 Provider 步骤，后 7 章正文保留。DAO 使用批量 current/tracking 查询，依赖构建为 O(1) 前驱引用。API 30/API 35 定向各 4/4、Database 全量各 150/150；统一门禁 797 tasks、Release/R8、安全扫描与备份排除通过。没有业务写入、真实 API 或实体设备写入，TEST-033 仍待完成。
- TASK-061 Phase 2B1：schema v11 允许 summary/tracking/aggregate/transition 多代 STALE 历史，数据库保证每槽最多一个 VALID；七类派生历史禁止恢复、篡改和删除，权威/历史查询分离。API 30/API 35 定向各 18/18、Database 全量各 152/152；统一门禁 797 tasks、JVM 496、Release/R8、安全扫描、备份排除及 diff 检查通过。真实 API 与实体设备写入 0，TEST-033 仍待完成。
- TASK-061 Phase 2B2A：schema v12 新增每 transition 唯一的完整伏笔 after-state revision、严格规范 codec、共享 post-CAS writer、append-only/stale 顺序触发器和两条生产提交接线。DeepSeek 只读审计指出的 final replay later-current 误拒绝已修复，Sol 同时阻止旧 Stage 回写最新伏笔索引。API 30/API 35 定向 20/20、27/27、3/3，Database 全量各 155/155；真实 API 与实体设备写入 0，实际 rewind/TEST-033 仍待完成。
- TASK-061 Phase 2B2B：schema v13 新增不可变 rewind 审计；单 Room 事务重验 plan，选择编辑点前 current-version 可信 revision，按 revision→transition 失效区间，以全字段 CAS 恢复基线、将区间新生 item 保持/置为 STALE，并修复 FTS。legacy DEVELOP 缺基线整笔失败，精确 replay 零写入。DeepSeek 只读审计 `20260805-235105-afbf576a` 无 P0/P1；Sol 采纳并修复 stale item 时间污染与审计时间下界。双 API 定向各 12/12、Database 全量各 159/159；统一离线门禁 797 tasks、源码与 5 APK 安全扫描及备份排除通过，真实 API 与实体设备写入 0。aggregate、有序执行和 TEST-033 仍待完成。
- TASK-061 Phase 2B3A：新增严格有界 `zhijuan.aggregate-state.v1` 与单事务 writer，只从当前权威实体状态、活动伏笔和同章 tracking 重算，不传播旧 aggregate。计划 v2 识别严格匹配的当前头；坏头阻塞，旧版本头转 STALE，并发同证据只提交一代。DeepSeek 只读设计审计 `20260806-002111-4d3f22a9` 的权威重算方向被采纳，但其遗漏写后 `ALREADY_SATISFIED` 且 payload 过宽，未直接应用；Sol 补齐 tracking 代次绑定和回归。双 API 定向各 11/11、Database 全量各 166/166；统一门禁 797 tasks、安全扫描与备份排除通过，真实 API 与实体设备写入 0。跨章有序执行和 TEST-033 仍待完成。
- TASK-061 Phase 2B3B1：schema v14 新增不可变 execution/step 准备账本；stable fence 覆盖 current 章节、rewind after-state 和 summary/tracking/aggregate 基线，不用动态 planHash 充当执行 ID。prepare 用同一 Room 事务包住 rewind、重验和账本插入；精确 replay 零写入，冲突身份失败，rewind 后故障整体回滚。DeepSeek 设计审计中“稳定账本/专门许可”方向被采纳，但“一次预建全部 Stage”因不可变来源冲突被拒绝。双 API 定向各 9/9、数据库全量各 171/171；统一门禁 797 tasks、安全扫描与备份排除通过。动态 Stage、TEST-033、总 runner、真实 API 和实体设备写入均未完成/未发生。
- TASK-061 Phase 2B3B2A：新增无 schema 迁移的严格 v2 rebuild Stage binding 与 `ChapterEditRebuildStageRepository`，从首个 PENDING edited-memory step 原子创建确定性单步 Job/Stage；普通 v1 memory 兼容。Provider-open/commit 双重回读 execution/fence/current 范围，来源变化和身份冲突零写入，并发创建收敛为一份。DeepSeek `20260806-054907-37278d3b` 在 100 万 Token 上限停止且无代码差异，Sol 接管实现。JVM 66/66，API 30/API 35 数据库全量各 175/175，源码安全扫描通过；未运行本阶段统一 Release/R8 门禁。tracking、aggregate、TEST-033、总 runner、真实 API 和物理设备写入仍未完成/未发生。
- TASK-061 Phase 2B3B2B1：第一章 tracking Stage 只在 prepared-SATISFIED memory 全字段指纹仍有效，或绑定 memory Job/Stage/Attempt/FINAL Usage/output reference/权威行完整成功时解锁；普通 tracking 顺序守卫保持，专用 source loader、Provider-open 和 commit 双门禁只接受同 execution stable fence。Fake Provider 端到端实际完成 memory 提交后创建 tracking。数据库 JVM 67/67、生成 JVM 117/117；API 30/API 35 数据库各 178/178、生成各 29/29，安全扫描与 diff 检查通过。未运行统一 Release/R8；tracking 输出提交、aggregate、后续章节、TEST-033 和总 runner 仍未完成，真实 API 与物理设备写入为 0。
- TASK-061 Phase 2B3B2B2 暂停检查点：第一章 rebuild tracking 与 aggregate 已在同一 Room 事务推进；aggregate 失败会回滚 tracking/时间线/伏笔/FTS/FINAL Usage/Stage 完成，replay 只验证现有 tracking 与 aggregate，Stage 创建后的 aggregate 槽变化会在 Provider-open 前拒绝。计划定向双 API 各 16/16、tracking E2E 各 5/5、数据库 JVM 67/67、生成 JVM 117/117。API 30 数据库全量在 153/179、0 failed 时按用户要求中止，双 API 模块全量与安全门禁待恢复后补齐，因此本阶段和 TASK-061 均未标记完成。详见工作汇报 102。
- TASK-061 Phase 2B3B2B2 完成：恢复后不改代码重跑 API 30/API 35，数据库模块各 179/179、生成模块各 31/31，数据库 JVM 67/67、生成 JVM 117/117；`SECURITY_SCAN_OK` 与 diff 检查通过。第一 rebuild tracking、aggregate、Usage 和 Stage/Job 形成同事务原子边界，失败整笔回滚，成功 replay 零重复。未运行本阶段统一 Release/R8；后续章节循环和 TEST-033 仍待完成。详见工作汇报 103。
- TASK-061 Phase 2B3B2C 完成：schema v15 新增不可变 tracking retirement evidence；第一个保留章节的旧 tracking/timeline/search 退役、真实来源 replacement Job/Stage 和证据同事务完成。正向/replay、双 worker 收敛、identity collision 整笔回滚及 v14→v15 迁移在双 API 通过；数据库 JVM 70/70、API 30/API 35 数据库各 183/183，`SECURITY_SCAN_OK` 与 diff 检查通过。DeepSeek 两次宽泛只读审计均因未收敛而没有最终回交和代码差异。未运行统一 Release/R8；Provider-open/commit、同章 aggregate、通用后续循环与 TEST-033 仍待完成。详见工作汇报 104。
- TASK-061 Phase 2B3B2E 完成：显式 ordinal 通用循环要求直接前驱 tracking+aggregate 和时间单调证明，retirement 只授权连续前缀。10 章编辑第 3 章重建第 3–10 章，7 条 retirement、8 条旧 STALE tracking、8 条新 aggregate，后 7 章正文保留；生产上下文只选新版本摘要。双 API 数据库各 187/187、生成各 35/35，JVM 70/70 + 117/117；统一离线门禁 797 tasks、Release/R8、安全扫描和备份排除通过。TASK-061 与 TEST-033 关闭，详见工作汇报 106。
- TASK-063 完成：独立 `provider:fake` JVM 24/24，diagnostics JVM 13/13；20 个参考 BODY 首段 P95 19.70 秒、正文 P95 174 秒。虚拟 301 秒 EOF 经真实执行器进入 UNKNOWN 且不重发；双 API 生成各 39/39。统一门禁 801 tasks、JVM 537/537、Release/R8、安全扫描和备份排除通过，详见工作汇报 109。
- 2026-08-05 安全门禁修复：`sk-` 密钥规则不再误报 `task-059/task-060` 文件名，源码扫描纳入 `reports/**`；新增 4 项脚本回归，统一门禁从只处理 Release 清单改为真正执行 `assembleRelease`。最新离线门禁 797 tasks、`SECURITY_SCAN_OK`（源码与 5 个 APK）和备份排除通过，真实小说生成 API 0、实体设备写入 0，详见工作汇报 83。
- TASK-041 新增租约证据：Stage 合法转换增至 22 个；Generation 数据库专项在 API 35/API 30 各 17/17，数据库模块全量各 49/49；并发领取、精确到期、旧执行器栅栏和请求前/后分流均通过。
- TASK-040 新增持久状态边界证据：Job 13 个、Stage 21 个合法转换均做全矩阵穷举；Generation 数据库专项在 API 35/API 30 各 13/13，数据库模块全量各 45/45。
- TASK-036 新增证据：API 35/API 30 的 schema v1~v6→v7 迁移与书库约束各 14/14，创建页与 App 交接各 8/8；App 模块在两套 AVD 全量均为 32/32。
- TASK-037 新增证据：冻结模型映射 JVM 2/2，书与快照回读在 API 35/API 30 各 11/11；App 模块两套 AVD 全量各 39/39，费用确认页另过 200% 字体、横屏、375dp 窄屏、深色和真实截图检查。
- 当前安全扫描：源码和 15 个现存 APK 均为 0 个疑似密钥命中；TASK-022/023/024/025/027/028/029/036/043/044/045/046/047/048/049/050/051/052/053/054/055/056/057/058 均未调用真实 API，聊天中提供的测试密钥未进入代码、命令、文档、报告或构建产物。
- M0.7 设备夹具：200,000 字/2,000 段首屏约 212.11 ms、末段定位约 95.84 ms、PSS 增量约 7,745 KB；10,000 章目录首屏约 400.28 ms、末章定位约 146.90 ms。该数据是 API 35 x86_64 Debug 模拟器观测值，不等同于正式性能承诺。
- TASK-083 完成：schema v17 的 request/book/daily 三层预算预留、RequestIntent v1、Usage终值结算、明确未执行释放、迟到usage回补、跨日替代请求和实际profile/adapter/current disclosure/frozen reservation匹配已经闭环。API30/API35 reservation专项各43/43、executor各23/23，数据库模块各272/272、generation各48/48；801-task统一离线门禁、Debug/Release、Lint/Vital、R8、安全扫描与备份排除通过。错误目的地在任何Provider或工件打开前失败且五类状态零写入。total runner和章节规划执行仍属于TASK-064，不能据此把App描述成端到端可用。
- TASK-064 Phase 2E5B 完成：普通章节的相关场景意图权威来源冻结为 arc-window v2 的不可变逐章计划；每章显式记录是否计划、精确场景数和最多12个参与人物。Stage input 将冻结由 CHAPTER node、Story Bible、Prompt Bundle 和 context/progression 重算的 expectation；create/open/commit 三次复验。旧 v1 不猜测兼容，需重建窗口；`CHAPTER_PLAN_V1`仍未注册。详见工作汇报139。

## 尚未具备

当前 APK 已有可操作的首次启动说明、连接向导、持久连接列表、带可选高级区和正式篇幅规则的创建页，以及从不可变快照回读的开始前确认占位页。Prompt Bundle v1、故事种子/圣经/总纲、分卷/8 章窗口、首章最小包、第二章硬闸门、章前上下文预算、章节正文/有限续接、章节记忆、时间线/伏笔投影、一致性检查、有限修订、候选最终提交和编辑后跨章顺序重建底层原语已经通过本地规则/假 Provider 验证，但确认页尚未把用户动作接到真实预算、目的地和 Provider 门禁。total runner、自动补窗调度、编辑后自动续跑、边生成边阅读和模板界面尚未接通，不应当作可用产品分发。
