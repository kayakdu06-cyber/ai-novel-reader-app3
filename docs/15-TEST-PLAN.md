# 织卷测试计划

## 1. 测试目标

优先证明五件事：不会丢书、不会泄密、不会失控花费、不会重复生成/重复提交、不同 API 能以一致方式失败和恢复。视觉细节不能代替这些闸门。

## 2. 测试层次

| 层次 | 内容 | 频率 |
|---|---|---|
| 单元测试 | 状态转换、预算、合并、解析、脱敏、哈希 | 每次提交 |
| 组件测试 | Room DAO/事务、adapter 夹具、模板引擎 | 每次 PR/合并 |
| 集成测试 | 假服务端 + 真数据库 + 编排器 | 每次 PR/夜间 |
| UI 测试 | 创建、连接、阅读、模板、恢复 | 每个里程碑 |
| 实机系统测试 | 进程回收、后台时限、通知、备份、升级 | 每个候选版 |
| 手工探索 | 中转站差异、长篇阅读、错误文案 | 候选版 |
| 安全检查 | secret 扫描、重定向、备份排除、Release 配置 | 每次 Release |
| 性能基准 | 启动、长目录、长章、数据库迁移 | 每周/Release |

## 3. 测试环境

### 3.1 Android 矩阵

- 最低支持版本：编码前根据 Compose/安全库定案，建议 Android 10 或更高；
- Android 12：数据提取规则和通知/后台行为；
- Android 14：前台服务类型与权限；
- Android 15：前台服务超时；
- 当前最新稳定 Android：覆盖安装和存储访问；
- 至少一台低内存设备/模拟器、一台中档真机、一台大屏/高字体倍率环境。

### 3.2 API 测试矩阵

| 协议 | 假服务端 | 官方沙盒/低成本实测 | 兼容服务实测 |
|---|---:|---:|---:|
| OpenAI Responses | 必须 | 候选版 | 不假定 |
| OpenAI Chat Compatible | 必须 | 可选 | 至少 2 种不同实现 |
| Anthropic Messages | 必须 | 候选版 | 可选 |
| Gemini generateContent | 必须 | 候选版 | 可选 |
| Ollama native | 必须 | 本地模型 | 不适用 |

真实 API 测试使用专门低额度密钥，绝不使用用户主密钥进入自动 CI。

## 4. 测试数据

- 产品篇幅规则夹具：短篇 80/80（下限/初始目标）、中篇 300/300、长篇空值必填、301/888/10,000 合法边界以及 300/10,001 非法值；
- 三章微型流水线夹具：每章 2,000–4,000 汉字，只用于快速验证阶段编排，不代表产品“短篇”篇幅；
- 二十章一致性夹具：包含多人关系、物品转移和跨日时间线，只用于回归记忆链，不代表产品“中篇”篇幅；
- 长篇压力：1,000/10,000 章元数据 + 100 个真实长章，测试 UI/索引，不实际调用模型生成万章；
- 模板链：系统 → 用户派生 → 书提取 → 再派生，深度至少 10；
- 敏感标记数据：使用假的 `sk-test-...` 等 canary，验证不会进入模板/日志/备份；
- 中文检索集：人名别名、无空格短语、同音/相近实体、跨章伏笔。

禁止把真实私人小说或真实 API Key 提交进测试资源。

## 5. P0 测试用例

### 5.1 创建与连接

| ID | 用例 | 预期 |
|---|---|---|
| TEST-001 | 只输入一句设想创建 | 成功建立书、快照和首阶段 |
| TEST-002 | 所有高级字段留空 | 自动补齐且可查看来源 |
| TEST-003 | 正确官方连接 | 默认只拉模型列表；60 秒内完成并保存有证据的能力快照；可选完整验证只有一次 16-token 探针 |
| TEST-004 | 错误密钥 | 明确鉴权失败，0 次自动重试 |
| TEST-005 | 服务端明确不支持模型列表后手填 | 可保存，标记未验证；鉴权/网络/未知失败不可手填绕过 |
| TEST-006 | 跨 host 302 | 目标 host 收不到密钥头 |
| TEST-007 | 远程 HTTP | 默认拒绝 |
| TEST-008 | 明确局域网 Ollama HTTP | 仅该 host 获准 |

### 5.2 生成与状态

| ID | 用例 | 预期 |
|---|---|---|
| TEST-010 | 正常流式章节 | 未结束前不进正式目录，结束后原子提交 |
| TEST-011 | UTF-8 汉字跨分片 | 无乱码/丢字 |
| TEST-012 | 流中途断网 | 正式旧版本不变，草稿可恢复 |
| TEST-013 | 提交事务前崩溃 | 重启不重复提交，不丢上章 |
| TEST-014 | 提交成功后 UI 前崩溃 | 重启识别已成功，不重新付费请求 |
| TEST-015 | 未知结果 | 自动暂停，用户确认前不重发 |
| TEST-016 | 未知 SSE 事件 | 忽略并继续，不崩溃 |
| TEST-017 | 策略拒绝 | 标准错误，0 次自动规避/重试 |
| TEST-018 | 达 FGS timeout | 写检查点、停止服务、任务可继续 |
| TEST-019 | 输出截断 | 有限续接、去重拼接、计入费用 |
| TEST-020 | 结构 JSON 两次无效 | 停在 FORMAT_INVALID，不猜数据 |

### 5.3 长篇一致性和编辑

| ID | 用例 | 预期 |
|---|---|---|
| TEST-030 | 死亡角色无解释回归 | blocker 被检测 |
| TEST-031 | 物品同时属于两人 | 冲突被检测/修订 |
| TEST-032 | 编辑第 3 章且已有 10 章 | 3 章派生和 4–10 章上下文/报告 stale，正文保留 |
| TEST-033 | 重建失效数据 | 引用新章节版本，旧派生不再进入上下文 |
| TEST-034 | 硬事实与计划冲突 | 硬事实优先，计划可重规划 |
| TEST-035 | 中文别名召回 | 相关事实进入上下文来源清单 |
| TEST-035A | FTS 指针权威回填 | 六类来源必须重读权威行；旧 Bible、旧章节、归档/已解决来源和 hash 不匹配只剔除自身并要求索引重建，不把派生索引直接送给模型 |
| TEST-035B | 强制/最近/相关记忆合并 | HARD_CANON、到期伏笔和最近摘要不依赖关键词；普通 STORY_CANON 只在相关时进入；同一来源只出现一次并保留多路命中；强制超界必须联网前阻断且不做 FTS |
| TEST-035C | 章前候选接线与发送前复核 | 上下文只接收强制、最近、当前状态与 FTS 相关记忆；损坏索引在组装期自动重建一次；强制超界不建快照/Attempt；快照后任一动态记忆变化时 Provider-open 必须拒绝旧 payload |
| TEST-035D | 固定中文召回与 FTS4 token 质量/性能 | 正式加密库 10,000 文档固定集至少 95%（目标 20/20）；相邻双字不得误中隔开字符；旧 token 索引自动重建；双 API 热查询中位 < 100 ms、P95 < 200 ms、最慢 < 500 ms |
| TEST-036 | 年龄不明确且涉及相关内容 | 阻止进入该生成阶段并要求明确成人 |
| TEST-037 | 三个呈现预设映射 | 字段、范围和 schema 版本完全符合 PR-004；冲突、血腥、语言和压迫维度逐项保持题材基线，不被细写档位暗中提高 |
| TEST-038 | 细写+避免淡出的相关场景装配 | 所有人物明确成年时自动生成严格身体与感官连续性契约；未知或未确认时返回阻断而非静默降级；默认流程不增加逐场确认；契约标记的必写关键过程节点覆盖率为 100% |
| TEST-039 | 身体与感官连续性负例集 | TASK-058 已实现固定无正文夹具：淡出替代、动作无反应、空间/身体/感官无因跳变、关键过程缺失和余波缺失必须退修；语气轻微漂移/机械化细节不得误判为 blocker。该夹具验证结构契约和门禁，不代表真实模型识别质量已经实测 |

### 5.4 模板

`TEST-050` 至 `TEST-059` 见 `05-TEMPLATE-SYSTEM.md`，全部为 P0。额外：

| ID | 用例 | 预期 |
|---|---|---|
| TEST-060 | 重开确认页默认操作 | 3 次点击内创建新书，原书不变 |
| TEST-061 | 模型偏好已不存在 | 使用当前推荐值并提示一次 |
| TEST-062 | 来源链深度 10 | 正确显示且无循环 |
| TEST-063 | 模板内容哈希相同但来源不同 | 可提示重复，不自动合并来源 |

### 5.5 费用

| ID | 用例 | 预期 |
|---|---|---|
| TEST-070 | 单次预留超过剩余书预算 | 请求不发出 |
| TEST-071 | 两个并发任务争抢最后预算 | 只有一个获得预留 |
| TEST-072 | 价格未知 | token 硬限制仍生效，金额不显示 0 |
| TEST-073 | 失败请求返回 usage | 台账保留且计入上限 |
| TEST-074 | 跨午夜 | 每日预算重置，单书预算不重置 |
| TEST-075 | 重启后预算已耗尽 | 不自动继续 |
| TEST-076 | 10,000 次金额累加 | Decimal/最小单位结果准确 |

### 5.6 安全、备份和迁移

| ID | 用例 | 预期 |
|---|---|---|
| TEST-080 | 扫描数据库外普通文件 | 无明文密钥/正文泄露 |
| TEST-081 | 模板/诊断/备份 canary 搜索 | secret 命中为 0 |
| TEST-082 | 系统 Auto Backup 检查 | 敏感数据均排除 |
| TEST-083 | 错口令恢复 | 当前库不变 |
| TEST-084 | 损坏包恢复 | 当前库不变，临时文件清理 |
| TEST-085 | 空间不足 | 切换前终止，当前库不变 |
| TEST-086 | 最老版本升级到最新 | 计数、哈希、引用正确 |
| TEST-087 | 迁移中崩溃 | 旧库/恢复点可用 |
| TEST-088 | 旧 APK → 新 APK 覆盖安装 | 签名兼容、数据不丢 |
| TEST-089 | 最近任务页屏幕隐私 | **取消**：2026-08-06 用户明确不需要应用锁、生物识别、`FLAG_SECURE` 或最近任务遮挡 |
| TEST-090 | 新远程 host 未确认 | 连接测试可用，但小说请求不发出 |
| TEST-091 | 修改 host/port/protocol | 原数据发送确认失效并重新提示 |
| TEST-092 | TXT/Markdown 导出 | 明确提示为未加密文件，加密备份文案不混淆 |

### 5.7 生成速度与生成中正文

| ID | 用例 | 预期 |
|---|---|---|
| TEST-093 | 生成时序事件脱敏（已完成） | 能还原排队、Provider、首段、正文、派生和提交耗时；正文/人物/提示词/端点/密钥命中为 0 |
| TEST-094 | 普通参考章固定延迟 Fake（BODY 已完成） | 2,500–4,000 中文字符；首段 P95 ≤ 20 秒、正文结束 P95 ≤ 180 秒；正式提交 P95 待 TASK-064 全阶段 runner |
| TEST-095 | 第一章快车道固定延迟 Fake | 首段 P95 ≤ 90 秒、正式提交 P95 ≤ 300 秒；完整规划未成功前仍阻断第二章 |
| TEST-096 | 5 分钟慢服务 watchdog | 不再安排新远程 Stage；在途尽力取消并保存检查点；结果不明不自动重发；10 分钟不允许仍无解释运行 |
| TEST-097 | 生成中正文投影 | 只显示已持久化完整段落；尾段、失败、取消、重启和修订状态明确；不进入正式目录/索引/后续上下文 |
| TEST-098 | 连续 20 章抖动与故障注入 | 报告 P50/P95/最慢值；崩溃、断网、迟到回调和数据库失败不重复请求/提交，不产生无界内存增长 |
| TEST-099 | 受控真实模型档案 | 仅在预算、目的地和用户单独授权后运行；未达速度/质量门槛的组合不得成为推荐 |

### TASK-015 当前安全存储证据

- TEST-080 已覆盖正式正文：在真实 SQLCipher 生产库中提交唯一正文 canary，数据库保持打开时扫描主库及 WAL/SHM，关闭后再次扫描，命中均为 0；重开后正文可正确读取。
- TEST-080 已覆盖临时文件：流式草稿首次写入和换版后扫描 `.zjaf` 与可能存在的 AtomicFile 备份，旧/新正文 canary 均为 0。
- TEST-087 的底层原子文件条件已覆盖：草稿替换在输入中途抛错时回滚到上一完整修订；旧 expectedRevision 被拒绝。完整“迁移中崩溃→旧库/恢复点可用”仍需 TASK-100~103 编排测试。
- 恢复点使用 2 MiB 设备夹具验证 64 KiB 有界读、分块往返和文件明文 0 命中；真实多 GiB、磁盘写满和物理设备 I/O 仍是发布前门禁。

### TASK-016 当前自动备份证据

- TEST-082 在 API 35 设备检查安装包 `ApplicationInfo.FLAG_ALLOW_BACKUP=0`，并由 `bmgr backupnow` 返回 `Backup is not allowed`。
- 安装 APK 中的现代规则由设备测试解析：cloud backup 与 device transfer 均无 include，并各自完整排除九个 Android 备份域。
- 安装 APK 中的旧规则也由设备测试解析：`full-backup-content` 无 include，完整排除九域；构建脚本同时检查源码、Debug 和 Release 最终合并清单仍引用正确资源且 `allowBackup=false`。
- API 30（Android 11）空白项目专属 AVD 已完成旧规则运行时复验：3 项安装包规则测试通过，Backup Manager 明确拒绝 `app.zhijuan.reader.debug`，旧系统发布门禁已关闭。

### TASK-023 当前 OpenAI Chat Compatible 证据

- 9 项 JVM 固定夹具全部通过：OpenAI 非流严格 JSON schema、DeepSeek SSE/usage/推理字段、中转站最小字段与 JSON 降级、流式拒绝、缺失 `[DONE]`、429、402、模型列表、非法 schema/未知能力联网前拒绝。
- SSE 夹具按 2 字节传输片段覆盖中文 UTF-8 跨片；DeepSeek usage 位于 `choices=[]` 的最后 chunk 且早于 `[DONE]`，断言 usage 先于唯一终态。
- Android API 35 与 API 30 各运行 1 项本地 HTTPS 集成测试：真实 OkHttp/TLS、Bearer Secret Source、`/chat/completions` 路径、两字节节流交付、中文正文、usage 和单终态均通过。
- Android MockWebServer 的 `chunkedBody(..., 2)` 在当前设备测试环境会把 HTTP chunk framing 字节作为正文暴露，属于测试夹具异常；设备测试改为完整 body + `throttleBody(2, ...)` 验证读取碎片，不放宽生产 SSE 解析器。
- API 35 全项目设备回归共 64 项，0 失败、0 错误、0 跳过；全项目不重复 JVM 测试共 133 项，0 失败、0 错误、0 跳过。
- 安全扫描覆盖源码与 10 个 APK，0 个疑似密钥命中；真实 DeepSeek/OpenAI API 未调用，用户提供的密钥未进入测试进程。
- 小米 Android 16 物理测试机已识别，但系统拒绝 USB 安装；未绕过该限制。待设备侧允许安装后，重复同一组 3 项测试，作为厂商实机证据补强，不阻塞 API 30 规则结论。

### TASK-022 当前 OpenAI Responses 证据

- 9 项 JVM 固定夹具通过：流式正常完成、未知未来事件、结构化输出与隐私请求字段、非流长度截断、内容过滤拒绝、服务端失败、缺失语义终态、重复 sequence、HTTP 429、模型列表，以及不支持/模型未知字段与非法 schema 的联网前拒绝；
- 请求夹具断言路径为 `/v1/responses`，`store=false`，不含 `conversation/previous_response_id`，system/developer/user 层级保持，结构化输出为 `text.format.json_schema`；
- API 35 与 API 30 各 1 项真实 OkHttp/TLS 集成测试通过，中文正文按两字节节流交付仍能完整还原，usage 早于且全流只有一个终态；
- 两次设备执行均明确设置 `ANDROID_SERIAL` 为对应模拟器；已连接的小米实体机未安装、未写入、未变更；
- 所有测试使用本地假服务和假密钥。真实 API 调用为 0，聊天中提供的真实密钥未进入进程。
- 完成 TASK-022 后，全项目不重复 JVM 回归为 142 项，API 35 全项目设备回归为 65 项，均为 0 失败、0 错误、0 跳过；源码和 11 个 APK 的安全扫描为 0 命中。

### TASK-024 当前 Anthropic Messages 证据

- 9 项 JVM 固定夹具通过：完整 SSE 生命周期、thinking/signature 隔离、未知事件与 ping、累计 usage、请求头/版本/字段、非流长度结束、拒绝、上下文超限、pause turn、流中 overload、缺失 message_stop、非法 block 生命周期、HTTP 529、模型列表和联网前能力拒绝；
- 请求夹具断言 `/v1/messages`、`x-api-key`、`anthropic-version: 2023-06-01`、顶层 system、user message、`max_tokens` 与 `output_config`；
- API 35 与 API 30 各 1 项真实 OkHttp/TLS 集成通过，两字节节流下中文 UTF-8、两次累计 usage 和单终态完整；
- 全项目不重复 JVM 回归为 151 项，API 35 全项目设备回归为 66 项，均 0 失败、0 错误、0 跳过；源码和 12 个 APK 安全扫描为 0 命中；
- 设备目标始终显式锁定干净模拟器；实体机和真实 API 调用均为 0。

### TASK-025 当前 Gemini GenerateContent 证据

- 9 项 JVM 固定夹具通过：当前 responseFormat、system/contents、`store:false`、私密 header、SSE 多 chunk、thought 隔离、usage-only 尾块、非流长度结束、prompt/candidate 拒绝、语言/畸形工具终态、缺失 finish、finish 后迟到正文、HTTP 429、模型过滤/上限和联网前能力拒绝；
- 请求夹具断言 `/v1beta/models/{model}:streamGenerateContent?alt=sse`、`x-goog-api-key`、URL 中没有 key、当前 `responseFormat.text.schema`、thinking level 和 model path 校验；
- API 35 与 API 30 各 1 项真实 OkHttp/TLS 集成通过，两字节节流下中文 UTF-8、thought 隔离、usage 和单终态完整；
- 全项目不重复 JVM 回归为 160 项，API 35 全项目设备回归为 67 项，均 0 失败、0 错误、0 跳过；源码和 13 个 APK 安全扫描为 0 命中；
- 设备目标始终显式锁定干净模拟器；实体机和真实 API 调用均为 0。

### TASK-027 当前能力登记与探测证据

- 新增 16 项 JVM：9 项登记优先级/过期/版本/端点/覆盖/存储失败/协程取消测试，3 项探测证据约束测试，4 项首发适配器 registry-backed resolver 接线测试；全项目不重复 JVM 回归为 176 项；
- 解析优先级覆盖用户覆盖、探测、官方、内置和保守默认；高优先级 UNKNOWN 不擦除低优先级已知值；精确到期、adapterVersion 错配和地址变化均不复用旧值；
- 探测结果区分支持、明确字段拒绝和不确定；不确定不会自动变成“不支持”，有效期限制为 1 小时至 30 天；
- Room schema v5 的模型/端点/协议/来源复合键、最新证据单调写入、跨 registry 重建读取和一键恢复自动值，在 API 35/API 30 各 2/2 通过；
- v1、v2、v3、v4 到 v5 的显式迁移、真实 SQLCipher v1→v5、未来 v6 失败关闭继续在 API 35/API 30 各 4/4 通过；
- API 35 全项目设备回归为 69 项，0 失败、0 错误、0 跳过；`verify-build.ps1 -Offline`、备份排除检查和源码/APK 密钥扫描通过；
- 所有运行显式锁定 `emulator-5554` 或 `emulator-5556`；已连接实体机未安装、未写入，真实 API 调用为 0。

### TASK-028 当前标准错误与重试证据

- 新增 26 项 JVM 测试：18 项统一策略测试、4 项 `Retry-After` 解析、OpenAI Chat 2 项错误映射矩阵与 1 项 HTTP-date 集成、Gemini 1 项额度/限流保守分支；
- 13 类远程失败均有明确决策，额外覆盖格式修复、截断续写、预算耗尽、正文已出现、结果不明、次数上限和 15 分钟等待上限；
- 四个首发适配器的 HTTP 失败断言 `PROVIDER_REJECTED`，安全传输的本地凭据失败断言 `NOT_SENT`，流异常结束断言 `RESPONSE_STARTED`；
- 全项目去重 JVM 回归为 202 项，0 失败、0 错误、0 跳过；`verify-build.ps1 -Offline`、Debug APK、Release manifest、备份排除和源码/APK 密钥扫描通过；
- API 35 全项目回归为 69 项，0 失败、0 错误、0 跳过；API 30 对安全传输和四个提供方适配器做 5/5 专项回归；
- 所有设备命令显式锁定模拟器；小米实体机未安装、未写入，真实 API 调用为 0。

### TASK-029 当前连接测试与模型列表证据

- `:provider:common` 新增 16 项 JVM 测试，覆盖默认零生成请求、错误密钥 0 重试、手填回退边界、模型存在性、费用确认、硬输出上限、固定通用探针、usage 缺失、证据写入、缓存失败、终态和整条 60 秒 deadline；
- 四个首发适配器各新增 1 项真实编码路径测试：本地 MockWebServer 收到一次模型列表请求和一次有界生成请求，并精确断言对应协议的 `max_tokens/max_output_tokens/maxOutputTokens=16`；
- 连接探针不包含测试夹具中的私人小说 canary，不发送结构化输出、temperature、topP、seed、reasoning 或幂等字段；
- 手填模型只在 `MODEL_NOT_FOUND/PROTOCOL_MISMATCH + PROVIDER_REJECTED` 时放行；鉴权失败和本地/未知错误返回原失败；
- 模型列表与生成探针共享 deadline，列表消耗的时间会缩短探针的 `totalStageMillis`；
- 全项目不重复 JVM 回归为 222 项，0 失败、0 错误、0 跳过；API 35 全量设备回归 69/69，API 30 安全传输与四适配器专项 5/5；源码与 14 个 APK 扫描 0 个疑似密钥命中；真实 API 调用 0 次，实体机写入 0 次。

### TASK-030 当前首次启动说明页证据

- 新增 4 项 Compose 设备测试：三类数据边界文案、跳过与继续同路、重建后步骤保持、页面/系统返回、48dp 最小触摸目标；
- 同一 4 项套件在 API 35 普通竖屏、200% 系统字体、横屏和 1600×2560@320dpi（800dp 宽）模拟环境各通过一次；每次测试后字体、旋转、尺寸和密度均恢复；
- 浅色与深色均由实际安装 APK 截图检查；浅色橙色文字对比从 3.88:1 修正至 4.70:1，主/次文字均达到 WCAG AA；
- API 30 对同一套件 4/4 通过；API 35 全项目设备回归现为 73 项，0 失败、0 错误、0 跳过；
- `verify-build.ps1 -Offline`、Debug APK、Release manifest、备份排除和源码/14 个 APK 密钥扫描通过；物理设备写入 0 次。

### TASK-031 当前连接向导证据

- 4 项 Compose 流程：官方服务自动模型、中转站严格手填、鉴权失败只留尾四位、完整验证费用确认；
- 2 项生产安全接线：远程明文中转在保存 secret 前拒绝且清零输入缓冲；上次进程遗留的临时 secret 在复用向导前撤销；
- API 35 与 API 30 各 10 项首次启动+向导组合测试通过；API 35 的向导 4 项另在 200% 字体和横屏复跑通过；App 模块 API 35 全量 17/17；
- UI 测试只使用假 gateway，真实 API 调用 0；所有安装和运行显式锁定模拟器，已连接实体手机未安装、未写入、未变更；
- `verify-build.ps1 -Offline` 通过，唯一 JVM 测试 222 项，源码和 14 个 APK 的 secret 扫描 0 命中。

### TASK-032 当前连接管理证据

- 新增 2 项 Room 事务测试：插入并设为当前、切换、名称/模型编辑、删除当前后的确定性后备连接，以及删除唯一连接后清空当前选择；
- 新增 5 项 Compose 流程：当前连接与尾四位展示、一键切换、无需重填密钥的名称/已发现模型编辑、删除二次确认、保存连接后重启直达列表；
- 新增 1 项生产安全接线：进程边界遗留的临时引用如果已经进入加密数据库，不得被启动清理误撤销；确认删除后对应 SecretRecord 进入 `REVOKED`；
- schema v6 迁移注册表、明文夹具、SQLCipher v1→v6 与未来 v7 降级失败关闭在 API 35/API 30 各 4/4；连接 DAO 两套 AVD 各 2/2；
- API 35 与 API 30 的首次说明、向导、安全接线和连接列表组合各 16/16；API 35 连接列表另在 200% 字号和横屏各 5/5；App 模块 API 35 全量为 23/23；
- UI 使用假 gateway，不调用真实 API；所有设备操作显式锁定模拟器，实体手机安装/写入/设置变更为 0。

### TASK-033 当前极简创建证据

- 新增 6 项 Compose 测试：仅填写一句设想时产生题材为空、中篇、均衡和 schema v1 的结构化草稿；低频题材、长篇和细写组合；表单重建恢复；触摸目标与连接管理回调；浅色截图不含凭据；深色主题全流程可达；
- API 35 同一 6 项套件在标准竖屏、200% 系统字体、横屏和 984×2187@420dpi（约 375dp 宽）下分别 6/6 通过；字体、旋转和显示尺寸均在测试后恢复；
- API 30 的首次说明、向导、安全接线、连接列表与极简创建组合为 22/22；API 35 App 模块全量为 29/29；
- 已保存当前连接时的启动测试改为直达创作页；页面只显示连接名称和模型，不显示原始密钥、尾四位或 secret 引用；默认可见文案不含直白的成人尺度名称；
- TEST-001 已关闭“一句话输入→本地书记录和不可变创建快照”；首个生成阶段仍等待 TASK-040 的 App 接线。当前按钮只做本地加密事务，不发起网络请求；
- 所有 UI 测试使用假 gateway，真实 API 调用 0；所有设备操作显式锁定模拟器，实体手机安装、写入和设置变更均为 0。

### TASK-034 当前高级创建与篇幅规则证据

- 创建页 Compose 测试现为 8 项：默认一句话路径；短篇 80 章结构化提交；中篇默认 300 章；长篇默认空值且不能继续；长篇 300 非法、888 合法与 301 下限；五组高级输入折叠/展开并把原始文本交给统一标准化器；Activity 重建保留长篇 999 章与高级输入；触摸目标、浅色截图和深色可达；
- 临时进入连接管理再返回时，设想、选择和高级输入由目的地级保存状态恢复；创建页与连接列表组合测试共 13 项；
- API 35 创建页在标准竖屏、200% 字号、横屏和约 375dp 宽窄屏分别 7/7；App 模块最终全量 31/31；
- API 30 首次说明、向导、安全接线、连接列表与创建页组合最终 24/24；
- 全量连续运行发现旧向导测试依赖触摸坐标和完整 Activity 替换会偶发误报，现已改为隔离页面容器、语义点击与跨线程可见结果；创建页与向导连续 11/11，最终 App 全量通过；
- 所有设备命令显式锁定 `emulator-5554` 或 `emulator-5556`；真实 API 调用 0，实体设备安装、写入和设置变更 0。

### TASK-035 当前呈现映射证据

- `core:model` 新增 6 项 JVM 测试：三档精确映射和双 schema；四个题材维度对所有档位逐项保持；细写+确认成年启用严格连续性、100% 关键过程覆盖和禁止淡出替代；均衡保持非严格；未知/未确认成年人返回不同阻断原因；未知 schema 和越界强度失败关闭；
- 创建页默认路径额外断言均衡草稿为总体 3、亲密 2、ALLOW、映射 schema v1；原有留白/均衡/细写 UI 与低操作流程未增加字段；
- API 35 与 API 30 创建流程各 7/7；API 35 App 模块最终全量 31/31；
- 所有测试只解析本地数值策略，不生成正文、不装配最终 Prompt Bundle、不调用真实 API；实体设备写入 0。

### TASK-036 当前标准化与不可变快照证据

- App JVM 新增 7 项：原始/标准化文本分离、NFC 中文标点、确定性哈希、显式/关键词/默认题材来源、题材强度继承、成年人未知门禁、80/300/自定义长篇冻结、未知 schema 失败关闭、模型偏好不含地址或秘密；
- `core:model` 新增 4 项篇幅策略测试；当前新书只能使用 `lengthPolicySchemaVersion=1`，短/中/长目标分别不得低于 80/300/301；
- Room schema v7 迁移与约束专项在 API 35/API 30 各 14/14：v1~v6 连续迁移、SQLCipher v1→v7、未来版本失败关闭、旧 200 章长篇保留为兼容 schema0、新规则非法值原子回滚；
- 创建 UI 与 App 交接在 API 35/API 30 各 8/8：创建成功只提交一次，显示本地保存结果并锁定按钮，未进入下一产品步骤前不会重复建书；
- 真实 API 调用 0，实体设备安装/写入/设置变更 0。

### TASK-037 当前开始前确认占位证据

- App JVM 新增 2 项冻结映射测试：有效模型引用可从快照恢复；缺失、空白和畸形模型 JSON 失败关闭，不从当前连接猜测；
- Room `LibraryDatabaseTest` 在 API 35/API 30 各 11/11：创建事务提交后可按书 ID 回读书、快照、80/300/自定义章数、模型引用与内容哈希；
- 费用确认页 4 项 Compose 专项覆盖冻结规模/模型、价格未知不显示独立“0 元”、确认只发冻结引用并锁定、48/56dp 触摸目标、375dp 窄屏和深色可达；同套件在 API 35 的 200% 系统字体与横屏各 4/4；
- App 创建到确认的 3 项流程覆盖只创建一次、进程状态恢复后按书 ID 重读、快照不可读时失败关闭且可返回；确认前后均没有生成任务或 Provider 调用；
- App 模块全量在 API 35 与 API 30 各 39/39，0 失败、0 错误、0 跳过；真实 API 调用 0，实体设备安装/写入/设置变更 0。

### TASK-040 当前持久 Job/Stage 状态机证据

- `core:task` 新增 Job 与 Stage 完整状态/事件笛卡尔积断言：13 个 Job、21 个 Stage 白名单转换逐项校验，除此之外的组合全部必须抛出 `IllegalStateTransition`；补齐 `NEEDS_ACTION + ISSUE_RESOLVED → READY`；
- `GenerationDatabaseTest` 扩展为 API 35/API 30 各 13/13：CAS 过期写者、租约竞争、时钟倒退、等待态租约清理、全 Stage 成功后才能完成 Job、重试证据/时间、需处理恢复、公开状态快照 Repository；
- 专项证明普通接口无法绕过租约领取、RequestIntent/Attempt/Usage 原子创建、Attempt+Stage 发送/终态事务或输出提交事务；失败后相关状态、attempt 和计数保持不变；
- `core:database` 全量在 API 35/API 30 各 45/45，0 失败、0 错误、0 跳过；未创建真实生成任务，Provider 调用 0，实体设备写入 0。

### TASK-041 当前租约、心跳和回收证据

- Stage 全矩阵加入 `LEASE_EXPIRED_BEFORE_REQUEST`，白名单由 21 增至 22；其余状态/事件组合继续全部非法；
- JVM 3 项覆盖到期前 1 毫秒/精确到期边界、非法心跳/超时配置、空 owner/负时间/时钟倒退，以及只有 PREPARING 可自动回队；
- `GenerationDatabaseTest` 在 API 35/API 30 各 17/17：两协程并发领取只有一个成功；owner+acquiredAt 凭证、心跳续租、精确到期、旧执行器栅栏、请求前回队、请求后恢复审计和 Job 运行转换凭证均通过；
- `core:database` 全量在 API 35/API 30 各 49/49，0 失败、0 错误、0 跳过；真实 Provider 调用 0，实体设备写入 0。

### TASK-042 当前发送前审计证据

- JVM 3 项覆盖安全 `secretRefId`、根层/嵌套敏感字段、非法/非对象/过大 JSON、ID/SHA-256/时间边界，以及 permit 单次 claim 与默认字符串脱敏；
- `GenerationDatabaseTest` 在 API 35/API 30 各 21/21：三事实提交后才有 permit、claim 前租约二次校验、一次性 claim、发送原子推进、敏感快照写库前拒绝、Ledger 唯一冲突整体回滚和过期 permit 不获联网授权；
- `core:database` 全量在 API 35/API 30 各 53/53，0 失败、0 错误、0 跳过；所有测试使用本地 fixture，Provider 调用 0，实体设备写入 0。

### TASK-043 当前加密流草稿运行时证据

- `core:security` Android 全量在 API 35/API 30 各 18/18：覆盖 2 秒/32 KiB 检查点、4 MiB 上限、调用方与快照清零、旧 writer 修订栅栏、异常回滚，以及仅剩 `AtomicFile .bak` 时仍可发现、恢复和删除；
- `GenerationDatabaseTest` 在 API 35/API 30 各 26/26，数据库模块全量各 58/58：证明草稿先分配并与审计绑定；审计失败清孤立文件；中断流不修改既有 `ChapterVersion`；成功/未成功/孤立分别在精确 24 小时/7 天/24 小时边界清理；
- `feature:generation` 在 API 35/API 30 各 3/3：fake Provider 只能在持久审计、租约和 artifact 复核后打开；开始前 delta 被拒绝且草稿仍为 0 字节；Provider 停顿 250 ms 时独立心跳仍按 100 ms 测试间隔续租；
- 正常 `STOP` 现由 TASK-044 在停止独立心跳后固化草稿哈希，并把 Attempt 推进为 `SUCCEEDED`、Stage 推进为 `VALIDATING`；正式章节仍只留给 TASK-045；
- 所有设备命令显式锁定 `emulator-5554` 或 `emulator-5556`；测试只使用本地 fake adapter，真实 API 调用 0，实体设备写入 0。

### TASK-044 当前结构化校验与一次修复证据

- JVM 8/8：合法结果、显式版本迁移、未知/错误版本、转义等价重复键、包装/尾随/非 object、畸形 JSON、深度/节点/成员/数组/字符串/数字限制、空/过大/无效 UTF-8、修复资格、报告脱敏和 64 条问题上限；
- `core:database` 在 API 35/API 30 各 62/62：完整检查点的长度/修订/hash 证据、合法校验进入 `COMMITTING`、第一次格式失败进入 `RETRY_WAIT`、第二次进入 `NEEDS_ACTION`、无 attempt 名额直接暂停，以及并发校验 CAS；
- `feature:generation` 在 API 35/API 30 各 6/6：审计流完成进入 `VALIDATING`、心跳与完成时间有序、合法结构不创建正式章节、一次独立修复、第二次暂停，以及超出契约字节上限不会卡死在 `VALIDATING`；
- 修复请求只含 stage contract 与“schema/问题码/无效输出数据”两层提示，温度为 0；默认字符串不出现无效正文，修复父 attempt 必须持久标记 `FORMAT_INVALID`；
- `LENGTH`、工具终态、拒绝和未知结果不冒充正常结构成功；未知结果已由 TASK-047 关闭，正文长度/有限续接由 TASK-055 关闭，摘要/人物事件/事实与时间线/伏笔正式版本重建由 TASK-056/057 关闭，一致性检查由 TASK-058 关闭，有限修订和候选最终提交仍由 TASK-059 完成；主动暂停/停止/取消已由 TASK-046 关闭；真实 API 0，物理设备写入 0。

### TASK-045 当前章节原子提交证据

- `GenerationDatabaseTest` 新增 6 项：正常提交同时发布正文/五类派生数据/FINAL Usage/书进度并激活下一 Stage；草稿清理后的精确重放不重复写入；派生外键失败整笔回滚；双协程相同提交只有一次写入、另一次精确重放；用户在校验后改稿不能被覆盖；单 Stage 最终提交同事务完成 Job；
- `core:database` 全量在 API 35（Android 15）和 API 30（Android 11）各 68/68，0 失败、0 错误、0 跳过；
- 提交摘要按派生记录 ID 排序并逐字段编码；下一阶段激活通过状态机断言且拒绝时间倒退；编译与两套设备测试均在修改后重跑；
- 本阶段没有打开 Provider，没有使用聊天中的密钥，也没有向小米物理设备安装或写入；真实 API 调用 0、实体设备写入 0。

### TASK-046 当前暂停/继续/停止/取消证据

- `core:task` 对扩展后的 Job 21 个、Stage 35 个合法转换做完整“状态 × 事件”穷举；停止覆盖暂停、非终态 Stage 的父停止取消和网络安全点回队均在矩阵内；
- `GenerationDatabaseTest` 新增 7 项并在 API 35/API 30 全量各 75/75：发送前暂停/继续与旧租约栅栏、在途暂停的 Attempt/Usage 原子结算、停止覆盖暂停并取消全部未完成 Stage、停止幂等、精确租约到期代结算、本章提交后暂停下一章，以及校验失败保留证据后暂停；
- `feature:generation` 在 API 35/API 30 各 7/7：慢 fake Provider 流中持久停止会触发适配器取消、加密草稿 flush、Attempt/Usage/Stage/Job 结算后才返回；
- 新增并发回归证明 Stage 心跳、请求已发送和流开始写入按同一执行序列提交，不会因协程完成顺序与时间戳取得顺序不同而误杀合法回调；
- 两套设备命令均显式锁定模拟器 serial；没有打开真实 Provider，没有使用聊天中的密钥，真实 API 调用 0、实体设备写入 0。

### TASK-047 当前未知结果恢复证据

- `core:task` 新增 7 项恢复策略 JVM 测试：仅有 RequestIntent 必须视为不确定；提供方证明未执行的安全回队；正文/usage 矛盾时拒绝回队；运行中、查询无结论、远端完成但无本地输出，以及本地校验/提交恢复；连同状态机矩阵扩展，当前全项目不重复 JVM 测试共 268 项；
- `GenerationDatabaseTest` 在 API 35/API 30 全量各 81/81：覆盖 intent-only 到期未知、提供方未执行安全回队、正文矛盾、远端运行后完成、本地响应崩溃恢复、通用状态入口防旁路，以及两个并发用户确认只有一个成功且不创建 attempt；
- `feature:generation` 在 API 35/API 30 各 9/9：恢复协调器只查询一次既有远端请求，`generate()` 调用为 0；异常、畸形流和 UNKNOWN_RESULT 会先把 Attempt、FINAL Usage、Stage、Job 持久结算再返回；
- Job 合法转换当前为 22 个、Stage 为 42 个，状态 × 事件全矩阵继续覆盖所有非法组合；
- `scripts/verify-build.ps1 -Offline` 通过 371 个 Gradle task；源码与 15 个 APK 的疑似密钥扫描均为 0，备份排除策略与 Debug/Release 清单检查通过；
- 全部设备命令显式锁定 `emulator-5554` 或 `emulator-5556`。真实 API 调用 0，聊天中的密钥未进入任何产物，实体设备写入 0。

### TASK-017 当前数据库迁移证据

- 迁移注册表连续性：v1、v2、v3、v4、v5 均能从唯一生产注册表得到无缺口的 v6 路径；
- 明文 schema 夹具：每个受支持起点迁移到 v6，保留核心小说，并在适用版本保留生成审计、usage、故事圣经、大纲和章节摘要；
- 生产加密路径：用 v1 导出 schema 创建真实 SQLCipher 旧库，再由正式工厂执行 v1→v6；正文可读且数据库文件扫描不到正文 canary；
- 失败关闭路径：未来 v7 库没有降级迁移时拒绝打开，原书、schema 版本、完整性和外键仍保持；
- API 35 与 API 30 各 4/4 通过；API 35 全量 Android 回归为 59 项通过。

### TASK-018 当前脱敏诊断证据

- JVM 4 项：关联值只留域分离哈希；异常 message/suppressed message 不进入事件；安全字段编解码往返；畸形和尾随字节拒绝；
- Android 3 项：Keystore 加密文件与解密后的结构化记录均无敏感值/正文 canary；滚动上限只保留最新事件；密文损坏后失败关闭且不生成明文日志；
- Android 专项在 API 35 与 API 30 各执行一次，均为 3/3 通过；
- TASK-018 完成时的全量基线为 JVM 107 项、API 35 Android 62 项，全部 0 失败；源码和 8 个 APK 的 secret 扫描为 0。

### TASK-020 当前适配器契约证据

- 假 adapter 通过唯一统一接口完成连接测试、模型列表、能力查询、标准流生成和取消，证明上层可替换真实服务商实现；
- 请求、事件和连接配置的默认字符串表示不包含正文、schema、base URL、模型原值、远端请求 ID 或 secret 引用 canary；
- 未知能力不授权任何可选请求字段；能力快照跨协议或跨模型复用会立即失败；
- 事件门控忽略开始前的非终态、重复开始和终态后的迟到事件，一条流最多产生一个终态，无终态 EOF 映射为 `STREAM_INTERRUPTED`；
- 远程明文 HTTP、URL 内凭据、query 和 fragment 被拒绝；只有显式确认的本地字面地址可使用 HTTP；
- 敏感请求头引用视图不可修改；用量状态拒绝负数、未知状态夹带数值和不可能的总量；
- 本模块 8/8 JVM 契约测试通过。真实 HTTP、服务商 JSON、UTF-8/SSE/NDJSON 协议夹具和密钥发送边界留给 TASK-021~026。

### TASK-021 当前安全传输证据

- JVM 8 项：HTTPS 下主密钥 Bearer 与自定义敏感头按用途注入；请求正文发送后关闭；默认字符串不含主机、密钥、正文或异常 message；
- 公开 header、敏感 query、路径 traversal 和非凭据型主密钥 header 在联网前拒绝；测试最初发现 `api_key` 下划线写法漏拦，现已收紧并回归；
- 付费 POST 307 不重放；跨 origin GET 在目标收到请求前拒绝，两个密钥均未到达目标；
- 等待响应头期间可取消，重复取消不重复操作；重复 requestId 返回 `AlreadyActive` 且不替换原 Call；
- 已知 Content-Length 和未知长度 chunked response 都受字节硬上限约束；超限失败并移除活动请求；
- 本地 Secret Store 不可用映射为 `CREDENTIAL_UNAVAILABLE`，不与远端 `AUTH_FAILED` 混淆；
- Android 1 项：真实 Keystore 两类密钥 → 本地 HTTPS 服务 → 加密诊断完整走通；服务端收到正确 header/正文，诊断只保留 3 个关联哈希；API 35 与 API 30 各 1/1 通过；
- 当前全量基线为唯一 JVM 用例 124 项、API 35 Android 63 项，全部 0 失败；源码和 9 个 APK 的 secret 扫描为 0。

## 6. API 流夹具

每个流协议至少准备：

- 正常短流；
- 单个 JSON 被拆成多个网络片；
- 多个事件在一个网络片；
- 中文 UTF-8 边界拆分；
- heartbeat/ping；
- 未知未来事件；
- usage 只在最后、usage 缺失、usage 多次更新；
- 正常完成、长度结束、拒绝、错误；
- HTTP 200 但流中 error；
- `[DONE]` 缺失/重复；
- 半流断开和迟到事件。

## 7. UI 与无障碍

- 首次连接流程点击数和字段数；
- 一句话创建到首章；
- 字体 200% 时所有主按钮可见；
- TalkBack 标签、顺序、状态播报；
- 深色/暖色对比；
- 系统返回手势与目录抽屉冲突；
- 旋转/进程重建后阅读位置和表单状态；
- 生成中读旧章不跳动、不自动翻章。

## 8. 性能闸门

- 冷启动到书架可交互目标 < 1.5 秒（中档目标机，不含应用解锁）；
- 普通章节打开目标 < 300ms；
- 10,000 章目录首次可交互 < 1 秒，滚动无明显掉帧；
- 20 万汉字单章不崩溃，采用分段显示；
- 连续流 30 分钟不产生无界内存增长；
- 1GB 备份流式处理，不将整包读入内存。
- 普通后续参考章首段 P95 ≤ 20 秒、正文结束 P95 ≤ 180 秒、正式提交 P95 ≤ 240 秒；
- 第一章首段 P95 ≤ 90 秒、正式提交 P95 ≤ 300 秒；
- 正常章达到 5 分钟必须进入慢服务安全处置，10 分钟仍未结束为 P0 发布阻断；
- 速度报告必须同时给出 20 章的 P50、P95 和最慢值，不接受只报最好的一章。

阅读渲染目标需在目标机实测校准；生成目标先用固定延迟 Fake 证明 App 自身开销和 watchdog，再用用户授权的受控真实模型校准推荐档案。真实 Provider 波动不能成为放宽 10 分钟发布阻断的理由。

## 9. 发布回归

每个候选版：

1. 从上一个正式版覆盖安装；
2. 打开旧书并校验阅读位置；
3. 继续一个中断任务；
4. 从旧书模板重开；
5. 达成费用上限并确认没有额外请求；
6. 创建并恢复加密备份；
7. 锁屏/后台/重启/断网恢复；
8. 对所有首发协议跑最小真实调用；
9. 扫描 APK、日志和导出包的 canary；
10. 核对版本、签名、许可证、SHA-256。

## 10. 缺陷分级

- S0：丢书、密钥泄露、预算突破、错误恢复覆盖数据、无法升级。阻止任何发布。
- S1：核心创建/生成/阅读/模板重开不可用或数据错乱。阻止候选版。
- S2：有替代路径的主要体验问题。评审决定。
- S3：视觉/文案小问题，可进入后续版本。

## 11. TASK-048 验证证据

- JVM：前台命令处理新增 7 项；当前项目不重复 JVM 用例 275 项全部通过。
- App API 35/API 30：各 42/42，通过私有 `dataSync` 服务清单、通用私密通知、通知动作，以及真实生产服务暂停落库且 Attempt 仍为 0。
- Database API 35/API 30：各 82/82，通过 `SYSTEM_FGS_TIMEOUT` 独立原因、可恢复为 `READY` 且不新增 Attempt。
- Generation API 35/API 30：各 9/9，证明前台服务改动未破坏控制与未知结果恢复门。
- API 35 系统探针：配置 3,000ms 后约 4,648ms 观察到 Job `PAUSED`、原因 `SYSTEM_FGS_TIMEOUT`、服务消失；探针只允许显式 `emulator-N` 且会恢复系统配置。
- 正式 Release Manifest：生产服务 1、Debug 探针 receiver 0、`FOREGROUND_SERVICE_DATA_SYNC` 权限 1。
- `verify-build.ps1 -Offline` 通过 371 个 Gradle task；源码与 15 个 APK 疑似密钥命中 0；真实 API 调用 0，实体设备写入 0。

## 12. TASK-049 验证证据

- 纯策略 JVM 新增 4 项：请求前回队、运行中无 Provider 审计、暂停/停止网络控制结算、本地校验/提交不确定现场延后。
- App 协调器 JVM 新增 3 项：五类动作路由和清理、并发 stale 不做内层重试、候选/清理异常有界且报告脱敏。当前全项目不重复 JVM 用例为 297 项，0 失败、0 错误、0 跳过。
- Database API 35/API 30 全量各 84/84：双租约 60 秒精确到期、候选证据脱敏、请求前 Stage+Job 同事务回 `READY` 并清除双租约，以及每批上限和最旧心跳排序。
- App API 35/API 30 全量各 45/45：生产 Runner 在无 Attempt 时原子回队；RequestIntent 现场固定使用 `NOT_AVAILABLE`，进入未知结果且 Attempt 数仍为 1；重复调度只保留一个启动 Work 和一个周期 Work。
- Generation API 35/API 30 全量各 9/9：维护与 WorkManager 依赖未破坏既有流式、控制和未知结果恢复门。
- 调度约束：启动检查延迟 15 秒、唯一策略 `KEEP`；周期为 24 小时/6 小时弹性窗口、唯一策略 `UPDATE`，要求电量/存储不低且 `NetworkType.NOT_REQUIRED`。
- Release：`:app:assembleRelease` 和 Release Manifest 通过；R8 seeds 保留 WorkManager initializer、SystemJobService 和 Worker 两参数构造器；Release `AUTO_SCHEDULE_MAINTENANCE=true`，Debug/AndroidTest 为 `false`。
- 项目统一门禁：`verify-build.ps1 -Offline` 通过 371 个 Gradle task，`SECURITY_SCAN_OK`、15 个 APK 疑似密钥 0、备份排除规则通过；真实 API 调用 0、实体设备写入 0。

## 13. TASK-050 验证证据

- `core:task` 新增 11 项 JVM：13 阶段完整/稳定覆盖、版本与确定性哈希、细写严格身体/感官连续性、题材维度不连带提高、成年人门禁、四个场景感知阶段、未写年龄自动成年事实、均衡档差异、80/300/长篇边界、损坏/未来 schema 失败关闭和诊断脱敏。
- `feature:generation` 新增 4 项 JVM：Prompt 层逐项映射、细写正文准备包含连续性、未知成年事实在远程前阻断、本地阶段永不产生远程计划。当前全项目不重复 JVM 用例为 312 项，0 失败、0 错误、0 跳过。
- Database API 35/API 30 全量各 87/87：不可变细写快照绑定成功且题材维度不变、绑定不创建 Job/不回写快照、已解析配置不一致失败关闭、未来 Bundle 状态和损坏快照失败关闭。
- Generation API 35/API 30 全量各 9/9；App API 35/API 30 全量各 45/45。API 30 首次运行的 45 项测试全部通过，但测试卸载清理连接超时；确认模拟器在线后重跑得到完整 `BUILD SUCCESSFUL`。
- `:app:assembleRelease` 通过 460 个任务并完成 R8；`verify-build.ps1 -Offline` 通过 371 个任务，`SECURITY_SCAN_OK`、15 个 APK 疑似密钥 0、备份排除规则通过。
- 测试只绑定本地快照并生成脱敏计划；真实 API 调用 0、实体设备安装/写入/设置变更 0，聊天中的密钥未进入命令、源码、文档、报告或产物。

## 14. TASK-051 验证证据

- `feature:generation` 新增 11 项 JVM：三份 schema 的完整嵌套约束、严格解析、跨文档 hash/角色/事实/章节覆盖、`NOT_APPLICABLE` 成人状态拒绝和确定性持久化映射。
- `core:database` 新增 3 项 JVM：三阶段链、稳定输入/idempotency、损坏和重复阶段拒绝。当前 Gradle JVM 报告共 326 项通过；按模块+类+用例名去除 `core:backup` 的 15 项 test/testDebugUnitTest 重跑后为 311 个唯一用例，0 失败、0 错误、0 跳过。
- API 35/API 30 初始规划端到端各 2/2：本地假 Provider 经发送审计、加密流、严格校验、permit 和逐阶段事务完成；错误圣经 stage ID 整笔回滚且 Usage 保持 provisional。
- 两套 AVD 模块全量：Database 各 87/87、Generation 各 11/11、App 各 45/45，全部 0 失败、0 错误、0 跳过。
- `:app:assembleRelease` 通过 460 个任务并完成 R8；统一 `verify-build.ps1 -Offline` 通过 371 个任务，`SECURITY_SCAN_OK`、15 个 APK 疑似密钥 0、备份排除规则通过。
- 所有生成测试都使用本地假 Provider；真实 API 调用 0、实体设备安装/写入/设置变更 0，聊天中的测试密钥没有进入命令、源码、文档、报告或构建产物。

## 15. TASK-052 验证证据

- `core:task` 新增 5 项 JVM：10,000 章仍只选 8 章窗口、40 章卷上限、总纲节拍/全书结尾截断、活动卷复用、非法范围和剩 3 章补窗判断。
- `core:database` 新增 3 项 JVM：单 Stage 窗口 Job、冻结输入确定性/下一窗幂等变化、损坏 hash 与越界在建 Job 前失败。
- `feature:generation` 新增 5 项 JVM：完整 Provider schema、10,000 章有限解析、9 章/重复键拒绝、冻结证据交叉失败和确定性 BOOK/ARC/8 CHAPTER 映射。
- 初始规划 Android E2E 扩展为 4 项：第一窗 1–8、同卷第二窗 9–16、不可变父链/精确重放，以及错误父 hash 的 revision/head/Usage 整体回滚。
- JVM Gradle 报告 339 项通过；去除 `core:backup` 两个变体的 15 项重复执行后为 324 个唯一用例，0 失败/错误/跳过。
- API 35/API 30 全量均为 Database 87/87、Generation 13/13、App 45/45。Release/R8 460 tasks、统一门禁 371 tasks、15 APK 密钥扫描和备份排除全部通过。
- 真实 API 调用 0、实体设备安装/写入/设置变更 0；对话测试密钥未进入任何命令、源码、文档、报告或产物。

## 16. TASK-053 验证证据

- `core:task` 新增 4 项 JVM：快车道首章证据、完整模式、第二章缺规划阻断、快车道规划必须适配已提交第一章。
- `core:database` 新增 6 项 JVM：最小包 Job 的稳定输入/幂等与非法范围，以及第一章后 Bible → Master Job 对第一章版本变化的绑定。
- `feature:generation` 新增 4 项 JVM：`first-chapter-bootstrap.v1` 完整 schema、严格人物/成年/结局/三章序列交叉校验、专用 Provider 准备桥。全项目报告 353 项通过；去除 `core:backup` 15 项变体重复后为 338 个唯一用例，0 失败/错误/跳过。
- 初始规划 Android E2E 扩展为 6 项：篡改年龄提交整笔回滚、合法最小包/精确重放、第一章可开 Provider、第二章规划前阻断、伪造 gate hash 阻断、第一章后 Bible → Master → Window 完整链放行第二章，以及完整规划模式对照。
- API 35/API 30 全量均为 Database 87/87、Generation 15/15、App 45/45，0 失败/错误/跳过。
- Release/R8 成功；统一离线门禁 371 tasks、`SECURITY_SCAN_OK`、15 个 APK 密钥扫描和备份排除通过。真实 API 调用 0、实体设备安装/写入/设置变更 0。

## 17. TASK-054 验证证据

- `core:task` 新增 5 项 JVM：必需事实始终保留、可选项按整项稳定省略、未知容量失败关闭、显式确认后的 8192 保守回退、UTF-8 字节上界/10% 安全余量/128 封套预算边界。
- `core:database` 新增 3 项 JVM：本地 `ASSEMBLE_CONTEXT` → 远程 `BUILD_CHAPTER_PLAN` 两阶段结构、冻结来源/幂等性和非法输入前置拒绝。
- Android 数据库新增 2 项真实 Room/SQLCipher 路径：成功组装保留成年人/硬事实/上一章摘要并整项省略超大可选时间线；未知容量时 Stage/Job 阻断且无 snapshot/Attempt。成功用例还覆盖精确重放、下一 Stage 激活、Provider-open 放行和 Outline head 变化后的旧快照拒绝。
- 全项目 JVM Gradle 报告 362 项通过；按模块、类和用例名去除 `core:backup` 15 项变体重复后为 347 个唯一用例，0 失败、0 错误、0 跳过。
- API 35/API 30 全量均为 App 45/45、Database 89/89、Generation 15/15，共 149 项，0 失败/错误/跳过；TASK-054 专项各 2/2。
- 全部测试使用本地规则与项目专属模拟器；真实 API 调用 0、实体设备安装/写入/设置变更 0。Release/R8 460 tasks 成功；统一离线门禁 371 tasks、`SECURITY_SCAN_OK`、14 个现存 APK 扫描和备份排除通过。

## 18. TASK-055 验证证据

- 新增 15 项 JVM：续接策略 6 项、正文 JSON 增量解码 6 项、Provider 输出/续接提示契约 3 项；覆盖 Unicode 尾锚点、任意流分片、转义/代理项、累计大小、精确输入 hash、schema 完全匹配和提示来源绑定。
- Generation Android 新增 4 项本地假 Provider 场景：`LENGTH` 分类落库与崩溃恢复；首次截断后新 Attempt 预置正文并只剥离一次锚点；未绑定提示零调用、错误锚点不追加；初次加 3 次续接后第 4 次截断停止且每次 Usage 独立 FINAL。
- API 35/API 30 全量各通过 App 45/45、Database 89/89、Generation 19/19，共 153 项；0 失败、0 错误、0 跳过。
- 全项目 JVM Gradle 报告原始 377 项通过；按模块、类和用例名去除 `core:backup` 15 项变体重复后为 362 个唯一用例，0 失败、0 错误、0 跳过。
- Release/R8 460 actionable tasks 成功；统一离线门禁 371 actionable tasks、`SECURITY_SCAN_OK`、15 个现存 APK 和备份排除规则通过。
- 全部测试使用本地规则与项目专属 API 35/API 30 模拟器；真实 API 调用 0、实体设备安装/写入/设置变更 0。尚未验证真实模型的内容质量、单次输出行为或实际计费。

## 19. TASK-056 验证证据

- 新增 9 项 JVM：`chapter-memory.v1` 有效/非法结构、来源四字段、实体白名单、关系目标、重复项、Canon 限制、确定性映射、Job 来源绑定、请求 hash/schema 和脱敏。
- Generation Android 新增 3 项本地假 Provider E2E：当前正式版本完整提取、三类记录原子提交与精确 replay；RequestIntent 后切换版本，Provider 调用数为 0；未知实体进入一次修复、Usage FINAL 且派生表零写入。
- API 35/API 30 全量各通过 App 45/45、Database 89/89、Generation 22/22，共 156/156；0 失败、0 错误、0 跳过。
- 全项目 JVM 原始 386 项；去除 `core:backup` 的 15 项变体重复后 371 个唯一用例；0 失败、0 错误、0 跳过。
- Release/R8 460 actionable tasks 成功；统一离线门禁 371 actionable tasks、`SECURITY_SCAN_OK`、15 个现存 APK 与备份排除规则通过。
- 真实 API 调用 0、实体设备安装/写入/设置变更 0。真实模型的中文提取质量、拒绝行为与实际费用仍未验证。

## 20. TASK-057 验证证据

- 新增 10 项 JVM：`chapter-story-tracking.v1` 有效结构/映射、来源 hash、未知参与者/错误地点、既有伏笔身份与状态、ABANDON 满置信度、重复目标、请求来源绑定、防子契约误路由和诊断脱敏。
- Generation Android 新增 3 项本地假 Provider E2E：时间线 + DEVELOP + PLANT 原子提交与精确 replay；RequestIntent 后旧伏笔快照变化时 Provider 调用 0；未知伏笔进入一次修复且台账零写入、Usage FINAL。
- Memory/迁移 Android 测试覆盖 v7→v8 新表/索引/触发器，以及“当前条目来源已前移但历史中间转换来自旧版本”时仍会整体 STALE 的失效链。
- API 35/API 30 全量各通过 App 45/45、Database 89/89、Generation 25/25，共 159/159；0 失败、0 错误、0 跳过。
- 全项目 JVM 原始 396 项；去除 `core:backup` 15 项变体重复后为 381 个唯一用例；0 失败、0 错误、0 跳过。
- Release/R8 460 actionable tasks、统一离线门禁 371 actionable tasks、`SECURITY_SCAN_OK`、15 个 APK 和备份排除规则通过。真实 API 0、实体设备安装/写入/设置变更 0。

## 21. TASK-058 验证证据

- JVM：74 个报告文件，原始 426、按模块+类+方法去重 411，0 失败、0 错误、0 跳过。
- Android 15/API 35：App 45、Database 89、Generation 28，共 162 项全部通过。
- Android 11/API 30：App 45、Database 89、Generation 28，共 162 项全部通过。
- Generation 新增端到端 3 项：严格全通过并进入接受门；缺失标准只允许一次有界修复且不写报告；替换候选后旧 RequestIntent 不能打开 Provider。
- TEST-039 不保存成人题材示例正文，只用问题码、严重度、过程节点和门禁结果验证淡出/连续性/余波规则。
- Release/R8 460 actionable tasks 通过；统一构建门禁 371 tasks、源码/15 APK 安全扫描和备份排除通过；真实 API 调用 0、实体设备写入 0。

## 22. TASK-059 完整验证证据

- COMMIT_CHAPTER 专用 Stage 执行器 JVM 8/8：READY 精确领取一次，PREPARING/COMMITTING 只恢复同 owner token，SUCCEEDED 零提交；错误 owner、倒退时间、陈旧领取证据、其他状态和非法输入均在调用最终协调器前拒绝，结果摘要不泄露标识。
- 最终提交相关 JVM 联跑更新为 41/41：执行器 8、协调器 6、最小一致性快照 7、受保护 artifact 恢复 5、最终草稿 mapper 7、一致性接受/生产分流 8，0 失败、0 错误、0 跳过。
- 生产旁路只读审计：正式 `src/main` 未发现绕过 executor/coordinator 发布 AI 候选的实际调用点；新链、旧 `ChapterGenerationCommitRepository` 与 `LibraryDao.commitChapterVersion` 当前均无总 runner/生产调用方，后两者记录为潜在未来误用面而非当前旁路。
- 全量回归首次揭示旧 DRAFT Stage 使用合法 `inputSourcesJson = "[]"` 时被候选 binding 误判；`parseIfBound` 现先解析通用 JsonElement，合法非对象返回未绑定，畸形 JSON 继续失败，匹配当前候选 policy 的 object 继续走严格 `parseAndVerify`。新增 JVM 3/3 覆盖三条边界。
- App 连续全量曾暴露两类测试环境问题：固定 MediaStore 截图名在重复运行时冲突，以及输入法改变 LazyColumn 可视范围后回收最后一个高级输入框。三处截图名改为唯一名称；该高级字段测试改用不拉起 IME 的 SetText 语义动作，定点 1/1 与随后完整 App 45/45 均通过。产品字段和交互逻辑未改动。
- 最终本地提交协调器 JVM 6/6：PREPARING 全量验证后才转换并提交；COMMITTING 按持久时间确定性重建；READY/SUCCEEDED 在读取 artifact 前拒绝；final route 篡改、转换证据失败均不会调用最终仓库；一次修订候选保持修订来源 binding 与最终接受 binding 分离。
- 相关 JVM 链 33/33：协调器 6、最小一致性快照 7、受保护 artifact 恢复 5、最终草稿 mapper 7、一致性接受/生产分流 8，0 失败、0 错误、0 跳过。
- API 35 `emulator-5554` 最终候选数据库专项 25/25：初始与一次修订恢复、Stage v3 快照、原子发布、回滚、并发、精确 replay、缺快照和绑错来源继续通过。
- API 35：App 45/45、Database 114/114、Generation 28/28，共 187/187；API 30 同样为 187/187，全部 0 失败、0 错误、0 跳过。
- `:app:assembleRelease` 完成 R8 并生成 unsigned Release APK；统一离线门禁 371 actionable tasks 成功，JVM 报告 467 项，`SECURITY_SCAN_OK`、5 个现存 APK 扫描和备份排除规则通过；`git diff --check` 返回 0。
- 当前 App 尚无总 runner；本任务只交付未来 runner 可调用的专用 Stage 入口，不伪装为整 App 已接通。只使用本地规则和受保护 artifact，App 内真实 Provider 调用 0、实体设备安装/写入/设置变更 0。TASK-059 已在当前模块边界完成。

## 23. TASK-060 Phase 2C2 验证证据

- `ChapterContextAssemblyDatabaseTest` 扩展为 5 项：普通 STORY_CANON 只有命中目标章/用户补充/目标弧时才进入 payload；无关事实与时间线不再靠全量历史进入；强制记忆超过 512 时 Stage/Job 联网前阻断且 snapshot/Attempt 均为 0；索引指针损坏会在组装事务内自动完整重建一次；快照后选中事实及其索引同步失效时 Provider-open 重算结果不一致并拒绝旧 payload。
- 不可变 manifest 新增完整的记忆路线证据：选择状态、查询指纹、逐路执行/遗漏/拒绝计数、逐项路线与三路命中数；Provider-open 在同一 Room 事务内重新执行权威选择、候选映射与预算，要求 payload hash 和完整 manifest 都与快照一致。
- API 30/API 35 专项各 5/5；最终 `core/database` 全量各 139/139，0 失败、0 错误、0 跳过；`core/database` JVM 65/65。
- 全部测试仅使用本地数据库、固定夹具和项目专用模拟器；App 内真实 Provider 调用 0、物理设备安装/写入/设置变更 0。

## 24. TASK-060 固定中文召回与总收口证据

- 正式加密 `ZhijuanDatabase` 写入 10,000 条生产 `memory_search_document`，20 个固定中文人物/地点/物品/伏笔查询全部命中；无关查询为空，同查询 replay 的指纹和来源顺序一致。
- 生产多路召回一次执行 41 个有界探针并找回全部 20 个目标，没有突破总 64、目标章 32、用户补充 16、目标弧 16 的限制。
- API 30 热查询中位约 6.07 ms、P95 约 7.37 ms、最慢约 9.21 ms；API 35 中位约 4.35 ms、P95 约 5.43 ms、最慢约 6.87 ms，均远低于 TEST-035D 门槛。
- 固定集首次暴露 FTS4 会拆分下划线双字 token；v2 改用全字母数字 token 后，“甲乙”不再误中“甲丙乙”，旧 v1 回填标记会自动重建并升级为 v2。
- `core/database` JVM 65/65；API 30/API 35 数据库全量各 143/143，0 失败、0 错误、0 跳过。全部使用项目专用模拟器与本地固定夹具；App 内真实 Provider 调用 0，物理设备写入 0。

## 25. TASK-061 Phase 1 用户编辑原子失效证据

- `ChapterUserEditDatabaseTest` 以正式 Room schema 建立 10 个已提交章节，编辑第 3 章后断言旧版本仍保留、新 `USER_EDIT` 版本成为 current 且为 `EDITED/UNKNOWN`。
- 第 3 章旧摘要和第 3–10 章聚合投影进入 `STALE`；第 4–10 章上下文与一致性报告进入 `STALE`；第 4–10 章 current version 和正文保持原样，仅章节状态变为 `CONSISTENCY_UNKNOWN/UNKNOWN`。
- 旧摘要的正式 FTS/外部内容行在同一提交后消失，新版本在重建前没有伪造搜索文档。精确 replay 不增加版本；同 ID 不同正文、跨书、错章和过期 expected current 均失败且不改 current。
- 定向测试在 API 30/API 35 各 3/3；`core/database` 全量在两套模拟器各 146/146。统一离线门禁 797 actionable tasks 通过，包含 JVM、Lint、Debug/Release、R8、源码与 5 APK 安全扫描和备份排除检查。
- TEST-032 的原子编辑与失效部分已通过；TEST-033 的从编辑点向后顺序重建仍待 TASK-061 后续阶段。App 内真实 Provider 调用 0、物理设备写入 0。

## 26. TASK-061 Phase 2A 重建影响计划证据

- `ChapterEditRebuildPlanDatabaseTest` 在正式 Room/SQLCipher schema 上覆盖 4 项：10 章编辑第 3 章的完整计划、最新章派生状态变化后的版本栅栏、后续 current version 改变使整段冻结计划失效，以及非编辑 current/跨书/未实现策略/诊断脱敏的失败关闭。
- 10 章固定场景生成 32 个稳定排序步骤：1 个 `READY`、31 个 `BLOCKED`、17 个将来可能调用 Provider 的步骤，且后 7 章正文/current 保持不变。计划阶段 Job、Stage、Attempt、Usage 与业务表写入均为 0。
- 当前结构性阻塞被显式建模为：派生版本槽已占用、tracking 顺序保护、aggregate 缺少重建 writer 和依赖阻塞；禁止把影响分析或理论步骤数量报告成实际重建成功。
- 执行前版本栅栏会重读完整影响区间；编辑章派生状态或任一后续 current version 变化后，旧 `planHash` 都不能继续使用。
- 生产查询使用按书批量 current-version join 和按章节范围批量 tracking 读取，计划构建使用 O(1) 前驱引用；没有逐章 N+1 查询或 O(n²) 依赖扫描。
- 定向测试在 API 30/API 35 各 4/4；`core/database` 全量在两套模拟器各 150/150。统一离线门禁 797 actionable tasks 通过，包含 JVM、Lint、Debug/Release、R8、源码与 5 APK 安全扫描和备份排除检查。
- TEST-033 仍未完成；本阶段没有创建重建 Job、调用 App 内真实 Provider、产生费用或向物理设备写入。

## 27. TASK-061 Phase 2B1 派生历史槽证据

- v10→v11 迁移保留原摘要、tracking、聚合和伏笔转换，四个业务索引由 unique 改为普通索引；fresh create 与迁移库均安装单一 `VALID`、不可变更新和禁止删除触发器。
- 正式 Room 测试在同一业务槽保存一代 `STALE` 与一代 `VALID`，权威查询只返回新代，显式 history 查询稳定返回两代；event/fact/timeline 同样不会混入 `STALE`。
- 负例覆盖同槽第二个 `VALID`、`STALE → VALID`、内容/来源/NULL 字段篡改、时间倒退、七类派生历史 DELETE，以及两个协程争抢空 summary 槽；并发结果恰好一个成功。
- Phase 2A tracking 批量查询改为只读取 current version 的 `VALID` 头，另保留显式全历史查询，避免 `associateBy` 从混合历史中任意选行。
- `ZhijuanMigrationTest + MemoryDatabaseTest` 在 API 30/API 35 各 18/18；`core/database` 全量在两套模拟器各 152/152。统一离线门禁 797 actionable tasks、496 项 JVM、Release/R8、源码与 5 APK 安全扫描、备份排除和 diff 检查通过。
- TEST-033 仍未完成；本阶段没有伏笔 current projection rewind、重建 Job/Stage、App 内真实 Provider 调用、费用或物理设备写入。

## 28. TASK-061 Phase 2B2A 伏笔 after-state revision 证据

- v11→v12 迁移保留旧 item/transition，创建空 revision 账本、完整索引和保护触发器；旧数据不伪造 after-state。fresh create 与迁移库均通过 Room schema 校验和外键完整性检查。
- 正式 Room 测试将完整 `ForeshadowItemEntity` 规范编码、SHA-256 校验并逐字段解码回原对象；默认 `toString` 不出现描述或 snapshot。负例覆盖 snapshot 篡改、DELETE、`STALE → VALID`、transition 先失效，以及合法的 revision→transition 失效顺序。
- final candidate 与 tracking 两条提交链均在同一事务中写 transition 和 revision。精确 replay 校验 revision 缺账/hash/provenance；当前伏笔被后来合法推进后，旧 final Stage replay 仍成功且不覆盖 later-current 搜索索引。
- DeepSeek 只读审计运行 `20260805-225927-6ce8af71` 使用 `max` 推理，累计 302,353 Token；确认无 P0，并指出 final replay 的旧 current-item 强相等问题。Sol 同时补发现旧 Stage 可能回写最新索引的伴生风险，两者均已修复并加回归。
- `ZhijuanMigrationTest + MemoryDatabaseTest` 在 API 30/API 35 各 20/20；final commit 专项各 27/27；tracking E2E 各 3/3；`core/database` 全量在两套模拟器各 155/155。
- TEST-033 仍未完成；本阶段只建立可信历史证据，没有实际 rewind、区间 replay、aggregate writer、App 内真实 Provider 调用、费用或物理设备写入。

## 29. TASK-061 Phase 2B2B 受审计伏笔 rewind 证据

- v12→v13 迁移创建空的 `foreshadow_projection_rewind` 审计表、`plan_hash` 唯一索引、编辑前可信 revision 查询索引和保护触发器；有效审计可插入，早于编辑版本的时间、重复 plan、UPDATE 与 DELETE 均被数据库拒绝，外键完整性通过。
- 正向 Room 场景包含 A 在第 1 章 PLANT、第 2 章 DEVELOP、第 3 章 RESOLVE，B 在第 3 章首次 PLANT，再编辑第 2 章：rewind 将 A 逐字段恢复为第 1 章完整基线，将 B 标为 `STALE`，保留全部历史，严格按 revision→transition 顺序失效，删除 B 的 FTS 并以正确章序重建 A 的 FTS，最后写入不可变审计。
- 同一 rewind ID 的精确 replay 零写入成功；同一 plan 使用不同 rewind ID 被拒绝。编辑点前缺少可信 revision 的 legacy DEVELOP 整笔失败并保持全部表不变。
- 对已经在 Phase 1 被编辑 stale 级联标为 `STALE` 的区间新生 item，rewind 保留原失效时间，不把审计执行时间写进业务投影。
- `ZhijuanMigrationTest + ForeshadowProjectionRewindDatabaseTest` 在 API 30/API 35 各 12/12；`core/database` 全量在两套模拟器各 159/159。
- 统一离线门禁通过 797 actionable tasks，包含 Debug、Release、Lint、R8、JVM、安全扫描脚本回归、源码与 5 个 APK 安全扫描及备份排除策略。App 内真实 Provider 调用 0，物理设备写入 0。
- TEST-033 仍未完成；Phase 2B2B 当时没有 aggregate writer、跨章有序 Job/Stage 执行器、费用确认或总 phase runner。

## 30. TASK-061 Phase 2B3A 聚合状态 writer 证据

- `AggregateStateWriterDatabaseTest` 覆盖 7 项：最新实体属性选择与嵌套 JSON 规范化、未来伏笔失败关闭、旧版本聚合头转 STALE、畸形当前头阻塞、同证据并发只提交一代、tracking 换代拒绝复用、生成时间早于权威来源时零写入且诊断脱敏。
- `ChapterEditRebuildPlanDatabaseTest` 同步验证计划 v2：aggregate 在依赖未满足时等待，写入后严格识别为 `ALREADY_SATISFIED`；不再使用“writer 不支持”的假 blocker。
- API 30 `emulator-5556` 与 API 35 `emulator-5558` 的 writer+plan 定向套件各 11/11；`core/database` 全量各 166/166，0 失败、0 错误、0 跳过。
- `scripts/verify-build.ps1 -Offline` 通过 797 actionable tasks，包含 Debug、Release、Lint、R8、JVM、安全扫描脚本回归、源码与 5 个 APK 安全扫描及备份排除策略。
- App 内真实 Provider 调用 0、物理设备写入 0。TEST-033、跨章有序 Job/Stage 和总 runner 仍未完成。

## 31. TASK-061 Phase 2B3B1 不可变执行准备账本证据

- v13→v14 迁移测试验证两个新表为空创建、Room schema 校验、唯一索引、执行/步骤来源触发器、UPDATE/DELETE 拒绝和外键完整性；旧数据不自动生成执行。
- `ChapterEditRebuildPlanDatabaseTest` 验证三章编辑的准备结果固定为 5 个关键步骤，顺序是 edited memory→chapter 2 tracking/aggregate→chapter 3 tracking/aggregate；准备与精确 replay 最终只有一条 rewind、一条 execution、五条 step。
- 已有 VALID 编辑章摘要会写成 `SATISFIED` 并保存脱敏全字段指纹；没有证据的步骤为 `PENDING`。命令、结果、实体默认字符串不展开书、章节、执行、摘要身份或正文/hash。
- 另一 rewind 身份不能占用同一计划；计划过期在写前失败，rewind 已在外层事务内执行后再发生时间门禁失败时，rewind 和整个 ledger 仍一起回滚。
- API 30 `emulator-5556` 与 API 35 `emulator-5558` 的 migration+plan/ledger 定向各 9/9；`core/database` 全量各 171/171，0 失败、0 错误、0 跳过。
- `scripts/verify-build.ps1 -Offline` 通过 797 actionable tasks，包含 Debug/Release、Lint/R8、全部 JVM 测试、源码与 5 个 APK 安全扫描及备份排除策略。
- App 内真实 Provider 调用 0、物理设备写入 0。Phase 2B3B1 只完成 crash-safe 准备账本；动态 Stage 执行、TEST-033 和总 runner 仍未完成。

## 32. TASK-061 Phase 2B3B2A 动态 edited-memory Stage 证据

- `ChapterMemoryExtractionJobFactoryTest` 验证普通 memory Stage 继续使用 schema v1 且无 rebuild 字段；绑定 Stage 使用严格 schema v2，binding 任一无配套 hash 的篡改会失败，绑定内容确实改变 input hash 和 idempotency key。
- `ChapterEditRebuildPlanDatabaseTest` 新增 4 项：三章场景只创建一个确定性 Job/Stage 且 Attempt/Usage 为 0；同命令精确 replay；后续 current version 变化后零写入拒绝；已有 satisfied memory 时不跳入 tracking；双协程并发最终收敛为一份权威 Job/Stage。
- Provider-open 与 commit 的专用守卫在测试中分别回读同一 Stage 并成功复核 execution/fence/step/source；生产接线已编译进入 `GenerationRequestAuditRepository` 与 `ChapterMemoryExtractionCommitRepository`。真实 Provider 输出和提交结果未伪造。
- API 30 `emulator-5556` 与 API 35 `emulator-5558` 的 `ChapterEditRebuildPlanDatabaseTest` 各 12/12；`core/database` JVM 66/66，数据库模块全量在两套模拟器各 175/175，均为 0 失败、0 错误、0 跳过。源码 `SECURITY_SCAN_OK`，`git diff --check` 无格式错误。
- 本阶段未运行统一 797-task Release/R8 离线门禁，不能沿用 Phase 2B3B1 的门禁数字冒充本阶段证据。App 内真实 Provider 调用 0、物理设备写入 0；tracking/aggregate 逐章闭环与 TEST-033 仍未完成。

## 33. TASK-061 Phase 2B3B2B1 第一 tracking Stage 证据

- `ChapterTrackingProjectionJobFactoryTest` 新增严格 v2 rebuild binding、input hash 变化、旧 v1 兼容和无配套 hash 的篡改拒绝。
- `ChapterEditRebuildPlanDatabaseTest` 新增 3 项：普通 tracking 顺序守卫在存在后续提交章时仍拒绝，专用 execution 许可可创建 ordinal 2 tracking；精确 replay/预算冲突；current 范围变化零写入拒绝；双协程并发只保留一份确定性 Job/Stage。该套件在 API 30/API 35 各 15/15。
- `ChapterMemoryExtractionEndToEndTest` 新增真实 Fake 前驱链：绑定 memory Stage 经过请求审计、Fake 流式响应、严格解析、Attempt/FINAL Usage 和原子 commit 后才解锁 tracking；整类在双 API 各 4/4。
- `core/database` JVM 67/67、`feature/generation` JVM 117/117；数据库 Android 全量在 API 30/API 35 各 178/178，生成模块 Android 全量各 29/29；均为 0 失败、0 错误、0 跳过。`SECURITY_SCAN_OK`，`git diff --check` 无错误。
- 本子阶段没有调用 App 内真实 Provider、没有向物理设备写入，也没有运行统一 797-task Release/R8 门禁。tracking Fake 输出提交、同章 aggregate 原子推进、后续章节循环和 TEST-033 仍待后续。

## 34. TASK-061 Phase 2B3B2B2 tracking 与 aggregate 原子推进证据

- `ChapterTrackingProjectionEndToEndTest` 新增正向 rebuild Fake Provider 闭环：tracking 提交同事务生成一份 aggregate，当前计划两步均为 `ALREADY_SATISFIED`，精确 replay 不重复 tracking、transition、revision、FTS 或 aggregate。
- 同类故障注入构造未来章活动伏笔，使 aggregate writer 在 tracking 业务写入后失败；事务结束后 tracking/timeline/transition/aggregate 均为 0，Stage 仍为 `COMMITTING`、Job 为 `RUNNING`、Usage 为 `PROVISIONAL`。
- `ChapterEditRebuildPlanDatabaseTest` 新增 Stage 创建后 aggregate 槽变化负例：Provider-open 失败且 Attempt/tracking 为 0。计划套件在 API 30/API 35 各 16/16，tracking E2E 各 5/5。
- `core/database` JVM 67/67、`feature/generation` JVM 117/117；数据库 Android 全量在 API 30/API 35 各 179/179，生成模块 Android 全量各 31/31；均为 0 失败、0 错误、0 跳过。
- `scripts/security-scan.ps1 -SkipArtifacts` 返回 `SECURITY_SCAN_OK`；`git diff --check` 返回 0，仅有既有换行提示。App 内真实 Provider 调用 0、物理设备写入 0。
- 本子阶段没有运行统一 797-task Release/R8 门禁；该门禁留到 TASK-061 完成后续章节区间和 TEST-033 后执行。后续章节循环与总 runner 仍未完成。

## 35. TASK-061 Phase 2B3B2C 后续 tracking 退役准备证据

- `ChapterEditRebuildTrackingRetirementEvidenceTest` 3/3：timeline ID 严格排序/去重、内容指纹不受 `VALID→STALE` 状态变化影响、不可变内容篡改会改变指纹。
- `ZhijuanMigrationTest` 在 API 30/API 35 各新增 1 项 v14→v15：表、四个唯一索引、插入 provenance、不可变和禁止删除触发器全部存在。
- `ChapterEditRebuildPlanDatabaseTest` 从 16 增至 19 项：第二章旧 tracking/timeline/search 原子退役并创建 replacement Stage；同命令精确 replay；双 worker 收敛到一个 Stage；预占 replacement 身份时整笔回滚并保留旧 `VALID` 基线。
- `core/database` JVM 70/70；数据库 Android 全量在 API 30/API 35 各 183/183，均为 0 失败、0 错误、0 跳过。`scripts/security-scan.ps1 -SkipArtifacts` 为 `SECURITY_SCAN_OK`，`git diff --check` 返回 0。
- 本切片未运行统一 Release/R8；没有调用真实 Provider、没有写物理设备。Provider-open/commit、replacement tracking→aggregate、通用后续章节循环和 TEST-033 仍待后续。

## 36. TASK-061 Phase 2B3B2D 首个保留章节 tracking→aggregate 证据

- `ChapterTrackingProjectionEndToEndTest.retainedChapterReplacementRunsFakeProviderCommitsAggregateAndReplays` 真实走过本地 Fake Provider 请求审计、严格响应处理、Attempt/FINAL Usage、replacement tracking/timeline 和同章 aggregate；旧 tracking/timeline 保持 `STALE`，新 tracking/aggregate 各一份 `VALID`，Stage/Job 完成，精确 replay 零重复。
- `retainedChapterAggregateFailureRollsBackNewProjectionButKeepsRetirement` 在 aggregate 读取到非法未来来源时注入失败；retirement 与旧 `STALE` 历史保留，新 tracking/timeline/aggregate 整体回滚，Stage=`COMMITTING`、Job=`RUNNING`、Usage=`PROVISIONAL`，可从本地提交边界恢复。
- `ChapterTrackingProjectionEndToEndTest` API 30/API 35 各 7/7；`ChapterEditRebuildPlanDatabaseTest` 各 19/19；`feature:generation` Android 各 33/33；`core:database` Android 各 183/183。
- `core:database` JVM 70/70，`feature:generation` JVM 117/117；`SECURITY_SCAN_OK`，`git diff --check` 为 0。全部使用本地固定夹具和项目专用模拟器，App 内真实 Provider 调用 0、物理设备写入 0。
- 本切片未运行统一 Release/R8；ordinal 6 及以后通用循环、TEST-033、execution 收口与总 runner 仍待后续。

## 37. TASK-061 Phase 2B3B2E 通用循环与 TEST-033 完成证据

- `ChapterEditRebuildPlanDatabaseTest` 新增 ordinal 6 直接前驱、前驱未完成拒绝和前驱 aggregate 时间下界负例，计划套件在 API 30/API 35 各 22/22。
- `ChapterTrackingProjectionEndToEndTest.thirdRetainedChapterRunsFakeProviderAfterDirectPredecessorAggregateAndReplays` 证明 ordinal 6 Provider→tracking→aggregate 与独立 replay；整类随后在双 API 各 8/8。
- TEST-033 新增 10 章固定场景：先为第 2–10 章建立旧 tracking，编辑第 3 章，再按 ordinal 4–16 重建第 4–10 章。固定结果为 retirement 7、旧 `STALE` tracking 8、当前 `VALID` tracking 9、当前第 3–10 章 aggregate 8，且第 4–10 章 current body/version 不变；tracking E2E 整类最终在双 API 各 9/9。
- `MemoryContextRouteSelectionDatabaseTest.userEditedChapterContextSelectsOnlyTheReplacementSummary` 直接调用生产上下文选择器：旧摘要 `STALE`、旧 FTS 行为 0，只返回新 current version 的 replacement summary。TEST-033 因此以“有序重建 + 旧派生不进入权威上下文”完成。
- TEST-033 十章场景与上下文权威场景在 API 30/API 35 各 1/1；数据库 Android 全量各 187/187，生成 Android 全量各 35/35；数据库 JVM 70/70，生成 JVM 117/117，全部 0 失败、0 错误、0 跳过。
- `scripts/verify-build.ps1 -Offline` 通过 797 actionable tasks、Debug/Release、Lint/Vital、R8、JVM、安全扫描器自测、5 个产物扫描和备份排除检查；`SECURITY_SCAN_OK`，`git diff --check` 为 0。
- App 内真实 Provider 调用 0、物理设备写入 0。TASK-061 原语与 TEST-033 完成；total runner、自动选择下一步、重启续跑和 context/consistency 阶段调度归 TASK-064，不冒充本阶段已实现。

## 38. TASK-062 与 TEST-093 完成证据

- `GenerationTimingTest` 覆盖域分离指纹、确定性事件身份、完整公式、phase 隔离、缺事件、跨 boot、失败终态和有限首段探测；`core:diagnostics` JVM 10/10。
- `GenerationTimingDatabaseTest` 覆盖正式 Room 写入/replay、全字段 canary 扫描、错误前驱、时钟回退、错误 milestone-phase、不可变触发器和跨 boot 报告；v15→v16 迁移另验证表、索引、触发器和完整性。
- `AuditedStreamingProviderExecutorTest` 使用本地 Fake Provider 走通真实流式解码后的首字节、首段、正文结束、字符/token 计数，以及无响应失败不伪造首字节；`GenerationTimingRecordingTest` 覆盖成功、截断、失败和迟到结算。
- API 30/API 35 的 `core:database` 各 192/192、`feature:generation` 各 37/37；JVM 为 diagnostics 10/10、database 70/70、generation 120/120，均 0 失败、0 错误、0 跳过。
- `scripts/verify-build.ps1 -Offline` 通过 797 actionable tasks、Debug/Release、Lint/Vital、R8、扫描器自测、源码与 5 个 APK 扫描和备份排除；`git diff --check` 返回 0。
- 本任务只完成测量底座和 BODY Fake 接线；TASK-063 延迟/慢流/断流基准、TASK-064 total runner 全阶段接线、TASK-066 watchdog 与真实模型速度均未冒充完成。App 内真实 Provider 调用 0、物理设备写入 0。

## 39. TASK-063 确定性 Fake Provider 与 BODY 基准证据

- 新增独立 `provider:fake` JVM 模块：有限脚本、虚拟时钟、取消/显式 cancel 双观察和脱敏统计。JVM 24/24；5 分钟虚拟慢流不使用真实长等待。
- DeepSeek 运行 `20260808-201004-b5d24248` 使用 max 推理、30 分钟硬上限和无累计 Token 上限，约 19 分 38 秒正常结束，总 Token 2,202,934；实际写入新模块和 settings。Sol 修复共享时钟并发误算与 provider-reported total token 缺失后独立复测。
- `AuditedStreamingProviderExecutorTest` 以同一 Fake adapter 接入真实 RequestIntent、Room、加密草稿与 TASK-062 时序。虚拟 301 秒无终态 EOF 在墙钟 5 秒门禁内进入 `UNKNOWN_RESULT`，不自动再调用 Provider。
- 20 个实际 Fake BODY 负载为 2,500～3,450 字：首字节 P50/P95/最慢 10.9/11.8/11.9 秒，首段 18.35/19.70/19.85 秒，正文结束 147/174/177 秒；均满足 BODY 目标。
- 正式提交分布保持 20 个 `MISSING_EVENT`，未伪造 TASK-064；第一章完整链、watchdog、生成中投影和 20 章全链故障注入仍分别属于 TASK-064/066/065/068。
- API 30/API 35 `feature:generation` 各 39/39；统一离线门禁通过 801 actionable tasks，JVM 537/537、Debug/Release、Lint/Vital、R8、扫描器自测、源码与 5 APK 扫描和备份排除均通过。

## 40. TASK-064 Phase 1A 空闲 Job lease 恢复证据

- 新增五个 `GenerationDatabaseTest`：过期空闲 Job 正向恢复、timeout 临界点、扫描后 Stage 被领取的 stale-fail、双维护器一次成功收敛、稳定排序与 `hasMore`。
- 正向用例同时篡改候选 heartbeat，必须以 `StaleGenerationStateException` 失败且 Job 仍 RUNNING；只有原始精确候选可恢复。
- API 30/API 35 定向 `GenerationDatabaseTest` 各 57/57；`core:database` Android 全量各 197/197；数据库 JVM 70/70，全部 0 失败、0 错误、0 跳过。
- `scripts/security-scan.ps1 -SkipArtifacts` 返回 `SECURITY_SCAN_OK`，`git diff --check` 返回 0；真实 Provider 0、物理设备写入 0、Git remote 为空。
- 本阶段未运行统一 Release/R8 门禁，因为 TASK-064 仍在分阶段实现；完成 dispatcher/runner 可验证切片后再运行统一门禁。TEST-095 和完整第一章 Fake 链仍未完成。

## 41. TASK-064 Phase 1B runner queue 与 Job heartbeat 证据

- `GenerationDatabaseTest` 新增 7 个测试，覆盖稳定排序/limit/hasMore/observedAt、残留 Job lease 排除、双 runner 并发精确一次成功、scan 后 Job/currentStage/Stage lease/updatedAt 变化 stale-fail、领取零 Stage/Attempt 写入、跨 Stage handoff 使用同一 Job token、错误或过期 token 不可复活，以及 owner/时间/limit 边界。
- API 30/API 35 定向 `GenerationDatabaseTest` 各 64/64；`core:database` Android 全量各 204/204。数据库 JVM 70/70；AndroidTest Kotlin/Room 编译通过，全部 0 失败、0 错误、0 跳过。
- DeepSeek 写入运行 `20260808-213707-7e4fe1b6` 使用 max 推理、30 分钟硬上限、无累计 Token 上限，约 23 分 21 秒正常结束，总 Token 4,412,800。Sol 随后补强 Job lease-free 查询、异常 projection 失败关闭和残留 lease 回归。
- 本阶段只使用两个项目模拟器 `emulator-5556`（API 30）与 `emulator-5558`（API 35）；真实 Provider 0、物理设备写入 0。统一 Release/R8 留到 Stage 执行/dispatcher 可运行切片后执行。

## 42. TASK-064 Phase 1C 原子执行租约证据

- 新增 5 个 `GenerationDatabaseTest`：current Stage 正向领取、两协程并发精确一次成功、Stage acquire 第二步失败回滚 Job heartbeat、双 token heartbeat/混合 owner/错误 token，以及 Stage timeout 与 cursor 已推进时零部分写入。
- API 30/API 35 定向 `GenerationDatabaseTest` 各 69/69；`core:database` Android 全量各 209/209；数据库 JVM 70/70，AndroidTest Kotlin/Room 编译通过，最终 0 失败、0 错误、0 跳过。
- DeepSeek `20260808-221907-e4212150` 使用 max、30 分钟、无 Token 上限，在硬超时被终止；总 Token 3,654,893，无最终回交，只落了 repository 主体且没有新增测试。Sol 审查后补 same-owner/identifier 门禁和全部测试，并修正一次测试毫秒单位错误后完成双 API 验收。
- 真实/Fake Provider 调用 0、物理设备写入 0。Phase 1C 未运行统一 Release/R8；heartbeat 调度 envelope、dispatcher 与 Fake 第一章闭环仍未完成。

## 43. TASK-064 Phase 1D heartbeat envelope 证据

- 新增 5 个纯 JVM 测试：手动多次 tick、action 在首 tick 前完成零 heartbeat、lease 丢失取消 active action并传播失败、cursor durable handoff 不误取消 commit、终态 Job boundary 与 mixed-owner 提前拒绝。
- 测试 waiter 使用 `Channel + CompletableDeferred` 手动放行，不 `sleep`、不等待真实 15 秒；所有异步等待只有 2 秒失败保护。
- `feature:generation` JVM 全量 125/125；API 30/API 35 Android 全量各 39/39，最终 0 失败、0 错误、0 跳过。
- 首轮 JVM 有 1 个测试因 `assertSame` 错把协程堆栈恢复后的等价异常当成失败；改为校验异常类型与 `lease-lost` 原因后通过，生产 envelope 行为未放松。
- 本阶段由 Sol 直接实现，没有调用 DeepSeek；真实/Fake Provider 0、物理设备写入 0，统一 Release/R8 留给 dispatcher 可运行切片。

## 44. TASK-064 Phase 2A 派生 route identity 证据

- 新增 11 个 JVM 测试：memory/tracking 各 v1/v2、五种 candidate role+phase、final commit v3，以及 unknown/missing/malformed policy、错误 schema/root/hash、互换 memory/tracking 身份、candidate 角色冲突和 final phase/target/maxAttempts 负例。
- 正向 fixture 通过生产 factory/binding 创建，不用手写 JSON 冒充合法来源；解析器测试 11/11，`core:database` JVM 全量 81/81。
- API 30 `emulator-5556` 与 API 35 `emulator-5558` 的数据库 Android 全量各 209/209；两台均 0 失败、0 跳过。没有连接或写入物理设备。
- DeepSeek 写入运行 `20260808-234024-85842439` 使用 max、30 分钟硬上限、无 Token 上限，约 15 分 28 秒正常完成；总 Token 2,402,928。Sol 审查权威 parser 委托和错误路径后独立复测。
- `scripts/security-scan.ps1 -SkipArtifacts` 返回 `SECURITY_SCAN_OK`，`git diff --check` 返回 0，Git remote 为空；真实/Fake Provider 调用 0。统一 Release/R8 留到 route 与 executor 形成可运行切片后执行。

## 45. TASK-064 Phase 2B current-lease route binding 证据

- 新增 5 个 Android 数据库测试：合法 memory route 的只读精确绑定；错误 Job/Stage token、mixed owner 和非 current Stage；倒退时间、60 秒超时临界与 PAUSING；已进入 `REQUEST_INTENT_RECORDED`；损坏冻结来源。
- 每个失败用例都比较调用前后的 Job、Stage 与 Attempt；route binding 没有续 heartbeat、改变状态或创建台账。
- 首轮 API 35 全类 74 项只有 1 个测试预期错误：正式暂停在 PREPARING 安全点直接进入 PAUSED。随后改用故障注入隔离 PAUSING 拒绝分支，生产代码未放松；重跑 74/74。
- `core:database` JVM 81/81；API 30/API 35 数据库 Android 全量各 214/214，均 0 失败、0 跳过。
- 本阶段由 Sol 直接实现和审查，未调用 DeepSeek；真实/Fake Provider 0、物理设备写入 0。`SECURITY_SCAN_OK`、diff check 0、Git remote 为空。

## 46. TASK-064 Phase 2C2 final exact-token executor 证据

- 新增 4 个 JVM 测试：PREPARING/COMMITTING 使用调用方 exact token 且不 acquire；同 owner 但 acquiredAt 不同拒绝；60 秒超时临界与 READY 拒绝；SUCCEEDED 只读 replay。
- 定向 executor 测试 12/12；`feature:generation` JVM 全量 129/129。
- API 30/API 35 generation Android 全量各 39/39，0 失败、0 跳过；没有连接或写入物理设备。
- 本阶段只增加 local final executor 的授权门禁，没有 registry、Provider、Attempt、schema、migration 或 Gradle 变化。真实/Fake Provider 0。
- `SECURITY_SCAN_OK`、diff check 0、Git remote 为空；统一 Release/R8 留到最小 registry 可运行切片。

## 47. TASK-064 Phase 2C3 最小 registry 证据

- 新增 2 个 JVM 测试：注册集合严格只有 final commit；未注册异常只含有限 route，不带 Job/Stage/owner 标识。`feature:generation` JVM 全量 131/131。
- 新增 2 个真实 in-memory Room Android 集成测试：final route 将 Phase 2B exact Stage token 原样传给 bound executor且不 acquire READY；普通 memory route 在任何 executor 或状态写入前失败。
- 首轮定向 Android 测试出现 JUnit `initializationError`，原因是测试 `@Before` 使用表达式体并意外返回 `StoredBookCreationSummary`；改为显式返回 `Unit` 后 2/2，通过时没有放松生产代码。
- API 30/API 35 `feature:generation` Android 全量各 41/41，0 失败、0 错误、0 跳过。
- `scripts/verify-build.ps1 -Offline` 通过 801 actionable tasks、Debug/Release、Lint/Vital、R8、扫描器自测、源码与 5 个 APK 安全扫描、备份排除；`git diff --check` 为 0，Git remote 为空。
- 本阶段真实/Fake Provider 调用 0、物理设备写入 0。九条 remote route 仍未注册，完整 Fake 第一章与 TASK-064 总体完成门禁尚未满足。

## 48. TASK-064 Phase 2D1 candidate draft 只读审计证据

- DeepSeek 只读审计逐段追踪初始 BODY Stage、request audit、stream、validation、seal、recovery 与 continuation；任务前后 Git status 均为 215 条，没有代码写入、构建或 Provider 调用。
- Sol 独立复核三个关键源码边界：bound BODY 的 Provider-open 只允许 REVISE；initial DRAFT seal 不解析 candidate source；final recovery 要求 revisionIndex=0 的 body input source 为空。
- 生产 `ChapterCandidateStageBindingV1.stageSetup` 直接调用只有 3 处，分别创建 derived successor 或 revision successor；没有 initial DRAFT factory。`ReadyForValidation` 的生产消费者只有 revision coordinator。
- 本阶段不运行新测试，因为交付物是只读缺口审计；上一阶段 generation JVM 131/131、双 API 各41/41、801-task 统一门禁仍是当前基线，不能冒充 initial draft 合同已经通过。
- 后续必须增加 initial source contract JVM 测试、real Room factory/route 测试、Fake streaming+seal Android E2E、UNKNOWN/continuation/replay 负例后，才能注册 initial draft route。

## 49. TASK-064 Phase 2D2 context route identity 证据

- DeepSeek 写入运行 `20260809-020430-8f974ec6` 使用 max、30 分钟硬上限、无 Token 上限，约 20 分 37 秒正常完成；只改任务包授权的 factory、repository、resolver 和两份 JVM 测试。它发现 feature registry 的穷举分支需要新增 route，但按边界未越界修改。
- Sol 独立补 registry 的显式未注册分支，并加固 progression chapter index 交叉校验及解析结果预算脱敏；context route 未加入注册集合。
- `core:database` JVM 全量 86/86；Sol 加固后 factory+resolver 定向 19/19。`feature:generation` 正式 Kotlin 与 AndroidTest Kotlin 编译通过。
- API 30/API 35 数据库 Android 全量各214/214；Sol 加固后 `ChapterContextAssemblyDatabaseTest` 定向各5/5，均 0 失败、0 错误、0 跳过。
- 本阶段未调用真实/Fake Provider，未写物理设备，未改 schema/migration/DAO/Attempt/Usage。统一 Release/R8 沿用 Phase 2C3 基线，待 context exact-token registry 切片形成后再运行。

## 50. TASK-064 Phase 2D3 context exact-token registry 证据

- 新增 4 个 real Room context 测试：exact 双 token 正向提交与 durable replay；错误 Job/Stage token；Job status 或 cursor 变化；60 秒租约临界。定向 API 30/API 35 各9/9，包含原有5项回归。
- registry Android 测试新增真实 Room context route：原始 bound snapshot 与 requestedAt 原样交给 context executor，final executor 为0次，Job/Stage不被 registry 自身修改。registry 定向双 API 各3/3。
- `core:database` JVM 86/86、`feature:generation` JVM 131/131；API 30/API 35 数据库全量各218/218、生成全量各42/42，均0失败、0错误、0跳过。
- 一次把 database 与 generation 同时连接到同一模拟器的命令因 Windows 抢占 logcat 文件而非零退出，但两个 XML 已分别是218/218和42/42；随后按模块拆开重跑，四条全量命令均独立成功退出。
- `scripts/verify-build.ps1 -Offline` 通过801 actionable tasks、Debug/Release、Lint/Vital、R8、扫描器自测、源码与5个APK安全扫描和备份排除。
- DeepSeek `20260809-025115-1315827e` 在 max、30分钟、无Token上限下触发硬超时，无 final，留下部分代码与未完成测试；Sol 修复其括号结构错误、完成高风险复核和测试后才确认本阶段。真实/Fake Provider 0、物理设备写入0。

## 51. TASK-064 Phase 2E1 chapter-plan 合同审计证据

- DeepSeek 首次运行 `20260809-041913-9c111e91` 因网络断开并在五次 stream 重试后退出 1，无 final、无写入；网络恢复后的 `20260809-054854-76d0c42d` 约 9 分 54 秒正常完成只读审计。
- 第二次运行使用 max 推理、30 分钟上限、无 Token 上限；总 Token 3,132,957、缓存输入 2,745,344、输出 51,542、推理 28,392。默认 status 仍为 220 条且 SHA-256 与任务包完全一致。
- Sol 复核确认：默认 status 220 与 `-uall` 279 只是未跟踪目录是否展开的计数差异，不是文件新增或丢失；DeepSeek 将 status 与 diff path 一一对应的表述不作为证据。
- 源码审计确认没有普通 `chapter-plan.v1` parser/validator/commit/DRAFT successor；现有 registry 仍只注册 final+context，plan 在 Provider 前失败关闭。
- Sol 额外检查 artifact retention，确认成功 `STREAM_DRAFT` 默认 24 小时可清理，因此“artifact + output reference”不能单独承担长期计划来源；DEC-068 改为把有界规范计划原子冻结进 initial DRAFT 输入。
- 本阶段没有运行 Gradle 或模拟器。上一阶段基线仍为数据库 JVM86/86、生成JVM131/131，双 API数据库各218/218、生成各42/42和801-task统一门禁；不能把该基线冒充 plan route 已通过。

## 52. TASK-064 Phase 2E2 chapter-plan route identity 证据

- factory 测试现为8项：context/plan独立policy、plan正向解析与脱敏，以及phase/target/attempt/input hash/schema/policy/dependency/context ID/context hash/extra root/progression目标与章序负例。
- resolver 测试现为15项：普通plan唯一解析为`CHAPTER_PLAN_V1`；错误policy、phase、target或input hash均失败关闭。registry unit以该route证明未注册异常只含有限enum，registered set仍精确2项。
- JVM全量：`core:database` 90/90、`feature:generation` 131/131，0失败、0错误、0跳过。
- Android全量：API30/API35数据库各218/218、生成各42/42，0失败、0跳过；既有context事务接受新增plan policy且仍正确推进cursor。
- `scripts/verify-build.ps1 -Offline`通过801 actionable tasks、Debug/Release、Lint/Vital、R8、扫描器自测、源码与5个APK安全扫描和备份排除。
- 本阶段真实/Fake Provider 0、物理设备写入0、无schema/migration/DAO/Provider变化。下一阶段不得在输出合同、预算/目的地和exact-token执行完成前注册plan route。

## 53. TASK-064 Phase 2E3 chapter-plan 输出合同证据

- 新增 `ChapterPlanStructuredOutputTest` 9项：Provider schema/48 KiB/12场景/64节点边界；普通计划正向与脱敏；字段换序 canonical hash 稳定；严格成年人虚构场景3节点正向；未确认参与者、节点不足、余波缺失；相关场景规避；比例模式伪造严格节点；章节/人物/POV漂移；重复key、未知字段、乱序和超限输出；Blocked expectation前置拒绝。
- `feature:generation` JVM 全量由131项增至140/140，0失败、0错误、0跳过。
- API30/API35 `feature:generation` Android 全量仍各42/42，0失败、0错误、0跳过；本阶段没有新增设备专用逻辑，但双版本证明新正式源码没有破坏既有 Room/runner 集成。
- `scripts/verify-build.ps1 -Offline` 通过801 actionable tasks、Debug/Release、Lint/Vital、R8、扫描器自测、源码与5个APK安全扫描及备份排除。
- 断网期间没有启动 DeepSeek；本阶段由 Sol 按已冻结合同直接实现并独立审查。真实/Fake Provider 0、物理设备写入0、无schema/migration/DAO/registry变化，plan route继续未注册。

## 54. TASK-064 Phase 2E4A 目的地/预算审计证据

- 全仓生产调用搜索确认：`BudgetEngine` 只有测试调用；`budgetSnapshotJson` 只做 JSON 合法性和任务快照保存，没有余额竞争语义。
- `recordRequestIntent` 能原子建立 Attempt+UNKNOWN/PROVISIONAL Usage，但事务内没有 request/book/daily reservation。
- disclosure 四字段已有 schema，但连接保存始终写空确认，DAO/Repository 没有接受、验证或失效生产路径。
- `normalizedDestination` 当前等于保存的 base URL，未形成 scheme/host/effective-port/protocol 的版本化规范合同。
- 因上述缺口，`CHAPTER_PLAN_V1` 继续显式未注册；本审计不调用 Provider、不修改 schema、不运行迁移。
- 后续必须新增 TEST-090/091 的真实 Room 证据，以及 TEST-070～075 的并发、重启、跨日、价格未知和保守结算证据，才能进入 exact-token 远程执行。

## 55. TASK-064 Phase 2E4B 目的地确认内核证据

- `ExternalDataDestinationBindingV1Test` 新增6项，覆盖同 origin 大小写/path/默认端口/DNS尾点等价、scheme/非默认port/protocol区分、IPv6、userinfo/query/fragment、非法端口/协议、版本与stored hash失败关闭及toString脱敏；`core:model` JVM全量17/17。
- `ConnectionDatabaseTest` 由2项增至6项：未确认阻断、接受后canonical持久/replay、protocol变化、host变化、合法格式hash篡改、disclosure版本升级、失效后重新确认与entity字符串脱敏。
- API30/API35 `core:database` Android全量各222/222，0失败、0跳过。一次中间全量因表达式体测试返回`assertThrows`对象而产生JUnit initializationError，显式`Unit`后定向6/6和双API全量均通过，生产代码未放松。
- `scripts/verify-build.ps1 -Offline`通过801 actionable tasks、全部JVM、Debug/Release、Lint/Vital、R8、扫描器自测、源码与5个APK安全扫描及备份排除。
- DeepSeek只读审计运行`20260809-071823-4baf2bb8`使用max、15分钟上限、无Token上限，约5分36秒正常完成；确认无P1生产缺陷，提出测试覆盖与既有entity toString风险，由Sol补齐并复验。
- 本阶段无schema/migration/registry/Provider/Attempt/Usage变化。TEST-090/091的内核负例已建立，但用户确认UI与Provider-open原子接线尚未完成，因此产品级验收项仍不勾选。

## 56. TASK-083 Phase 1 设计审计与后续必测矩阵

- DeepSeek只读审计`20260809-075939-b1d748ce`使用max、25分钟上限、无Token上限，约10分39秒完成；总Token 1,367,455，任务前后status均233条，0写入。
- 实现验收必须覆盖：request/book/daily分别拒绝；价格未知token拒绝；单Room并发；两个Room实例同文件并发；失败方reservation/Attempt/Usage/Stage零写入；关闭重开后余额不丢；跨午夜只重置daily；UNKNOWN保留estimate；迟到Provider按终值幂等修正；实际超预留仍保存；Provider未执行唯一释放；RELEASED后迟到usage重新计入；v16→v17与v0 Provider-open拒绝。
- 目的地测试还必须证明实际`ProviderConnectionProfile`和adapter protocol与permit一致，不能确认连接A后把profile B交给执行器。
- 本设计阶段未运行Gradle/模拟器。上一阶段222/222与801-task门禁不能作为TASK-083实现通过证据。

## 57. TASK-083 Phase 2 schema/policy core 证据

- `BudgetDailyPeriodKeyV1Test`覆盖UTC与Asia/Shanghai午夜边界、确定性、显式zone输出、非法/过长zone和负epoch；API30首次设备失败暴露`LocalDate.ofInstant`不兼容，改为兼容调用后双API通过。
- `PersistentBudgetPolicyDatabaseTest`共3项，覆盖BOOK/DAILY首revision与current、直接子revision/head、daily换zone、book不存在、倒退时间、重复policy ID、parent fork、revision/head篡改与删除。
- reservation guard覆盖直接伪造SETTLED、错误Job–Book、非法币种、identity篡改/删除、v1 Attempt缺reservation、RESERVED→RELEASED、禁止倒退、RELEASED→SETTLED迟到回补及禁止SETTLED→RELEASED。
- v16→v17迁移保留Book/Chapter/Attempt/Usage，旧Attempt新增0/null，三张预算表为空且trigger/index齐全；跨schema helper按`database.version`检查，v10不再被错误要求存在v17表。
- JVM：core:model23/23、core:database90/90。API30/API35定向各4/4、数据库全量各226/226；补充fork/重复ID后策略类双API各3/3再通过。
- `SECURITY_SCAN_OK`、diff check 0、remote为空；真实/Fake Provider0、物理设备写入0。Phase3仍必须补单Room/双Room并发、失败四表零写入、重启/跨日/UNKNOWN/迟到Usage/v0 Provider-open与实际profile目的地矩阵。

## 58. TASK-083 Phase 3A 原子 reservation core 证据

- `PersistentBudgetReservationDatabaseTest`共11项：正常v1原子提交和派生日键；request/book/daily token拒绝四表零写入；金额缺失/币种不符保守拒绝；disclosure缺失与Attempt失败整笔回滚；policy换版仍累计旧reservation。
- 同Room两协程同时争抢150-token书级额度，各申请100，只能一个成功；失败必须是BOOK/LIMIT_EXCEEDED并且reservation/Attempt/Usage/Stage零写入。
- 两个Room实例指向同一WAL文件重复相同竞争，只能一个成功；关闭两实例、重新打开数据库后，第三个60-token请求仍因已有100-token reservation被150上限拒绝，聚合保持100。
- JVM `core:database` 90/90；API30/API35专项各11/11、数据库全量各237/237；`SECURITY_SCAN_OK`、diff check 0、remote为空。
- 本节不能替代后续公开入口、FINAL/UNKNOWN/RELEASED/迟到Usage、跨午夜、v0 Provider-open和实际profile/adapter destination矩阵；这些仍是TASK-083完成门禁。

## 59. TASK-083 Phase 3B 公开 RequestIntent v1 证据

- `GenerationDatabaseTest`新增/更新公开prepare正向、超预算四表零半状态、legacy v0联网前拒绝、permit reservation错配和`RELEASED`拒绝；API30/API35各77/77。
- core/feature/app全部公开streaming与continuation prepare调用已显式携带budget；feature五组`.invalid` profile的connection/protocol/canonical destination与预算fixture一致，caller daily key为0。
- `core:database`双API全量各240/240，`feature:generation`各42/42，App恢复维护专项各2/2；JVM统一590/590。
- 双Room测试曾在API30全量压力下暴露第二实例onOpen重复DDL的`SQLITE_BUSY`；夹具改为先打开两个Room实例再写fixture，不改变reservation竞争。修复后reservation专项API30连续3次、API35连续2次各11/11。
- 统一离线门禁801 actionable tasks、Debug/Release、Lint/Vital、R8、源码与5 APK安全扫描、备份排除、diff检查均通过；真实Provider0、物理设备写入0。
- 本节仍不能关闭TASK-083：FINAL/UNKNOWN/RELEASED/迟到Usage、跨午夜重预留和实际profile/adapter destination矩阵尚待完成。

## 60. TASK-083 Phase 4A Usage 原子结算证据

- `PersistentBudgetReservationDatabaseTest`增至23项，新增覆盖PROVISIONAL保持预留、FINAL UNKNOWN保留估计、ESTIMATED/PROVIDER终值替换、实际超预留、无可靠金额、FINAL replay、UNKNOWN/ESTIMATED迟到Provider升级、legacy v0，以及缺失/错态/错ID/错daily period失败关闭。
- Sol将迟到升级和首次结算的CAS加固为旧状态+旧更新时间+旧accounted全字段比较，并增加Usage/reservation写后身份与终值回读；任一冲突整笔回滚。
- API30/API35 reservation专项各23/23；`core:database`全量各252/252；`feature:generation`各42/42；App恢复维护专项各2/2。
- 统一JVM590/590；801-task离线门禁、Debug/Release、Lint/Vital、R8、源码与5 APK安全扫描和备份排除通过。真实Provider0、物理设备写入0。
- 本节仍不能关闭TASK-083：Provider明确未执行RELEASE、RELEASED后迟到Usage、跨午夜重预留和实际profile/adapter destination矩阵尚待完成。

## 61. TASK-083 Phase 4B 明确未执行 RELEASE 与迟到回补证据

- `PersistentBudgetReservationDatabaseTest` 增至30项；新增覆盖 Provider 明确未执行时 Usage/Attempt/Stage/Job/reservation 原子推进、book/daily聚合排除、错态五类回滚、已FINAL Usage拒绝、legacy v0、已知Usage不释放、`RELEASED→SETTLED`迟到Provider回补与精确replay，以及UNKNOWN/ESTIMATED不得复活。
- Sol审查收紧 DeepSeek 初稿：专用入口只接受UNKNOWN/PROVISIONAL，要求Attempt与审计时间一致；release/restore CAS补齐旧`releasedAt/settledAt`空值条件，并对v0/v1 UNKNOWN FINAL做写后精确回读。
- API30首次专项发现“无非RELEASED行时SQL SUM为NULL”的测试断言写成0；按DAO既有明确语义修正为NULL后，API30/API35专项各30/30。该失败没有修改生产聚合逻辑。
- API30/API35 `core:database`全量各259/259；`feature:generation`各42/42；App恢复维护专项各2/2。API30一次UTP/ADB本地通道瞬时超时启动0项，确认模拟器在线后原样重跑获得2/2，不计为业务测试结果。
- 统一JVM590/590；801-task离线门禁、Debug/Release、Lint/Vital、R8、源码与5 APK安全扫描和备份排除通过。真实Provider0、物理设备写入0。
- 本节仍不能关闭TASK-083：跨午夜未发送重预留和实际profile/adapter destination矩阵尚待完成。

## 62. TASK-083 Phase 5B Provider-open 换日核心证据

- `PersistentBudgetReservationDatabaseTest` 增至35项：上海时区午夜前1ms同日 claim、午夜到点换日、剩余次数 READY、次数耗尽 NEEDS_ACTION、旧 permit replay、两个并发 claim 仅一次释放、已发送 Attempt 零写入拒绝，以及 Attempt/Usage/reservation/Stage/Job、租约、时间、错误码和book/daily聚合精确断言。
- `AuditedStreamingProviderExecutorTest` 增至18项；新增换日用例证明异常发生在草稿 buffer 与 `adapter.generate` 之前，Provider调用计数为0，加密草稿 revision/updatedAt/0字节内容不变，五类持久状态已提交。
- API30/API35定向：reservation各35/35、executor各18/18；整模块：`core:database`各264/264、`feature:generation`各43/43，全部0失败。
- 统一JVM 592/592；`scripts/verify-build.ps1 -Offline`通过801 actionable tasks、Debug/Release、Lint/Vital、R8、5个构建产物安全扫描、扫描器自测和备份排除。
- DeepSeek生产实现运行达到45分钟护栏但留下可审查WIP；测试补充运行异常退出且无测试差异。Sol完成状态机穷举分支、精确租约/事务加固、测试与双API验收后才确认本阶段。
- 本节只证明旧日未发送请求的可靠终止与重新排队。Phase 5C的新日新Attempt/reservation与续写种子复制、以及实际profile/adapter destination匹配仍是TASK-083完成门禁。

## 63. TASK-083 Phase 5C 新日替代请求准备证据

- `PersistentBudgetReservationDatabaseTest` 增至40项；新增证明新Attempt序号/父链/新日键/新reservation、旧日释放保持、book跨日累计与daily换日、普通入口绕过拒绝、新日quota零半状态、错误Job token失败关闭，以及两个并发替代请求仅一个提交。
- `AuditedStreamingProviderExecutorTest` 增至21项；新增证明非空续写种子复制到不同的新受保护工件、空种子也分配不同工件、旧descriptor/内容保持、普通入口失败后新工件被清理、数据库证据变化时只删除新工件，且所有换日流程Provider调用为0。
- API30/API35定向：reservation各40/40、executor各21/21；整模块：`core:database`各269/269、`feature:generation`各46/46，全部0失败、0跳过。
- 统一JVM为592/592，0失败、0错误、0跳过。完整离线门禁结果记录在工作汇报135。
- DeepSeek只读设计审计`20260809-155744-bbeaf68d`使用max、无Token上限，约16分12秒正常完成；总Token4,180,592、0写入、0权限请求。Sol采用新工件优先/事务失败清理和父证据同事务复核，并加固为调用方必须提供真实双租约快照、普通入口不可旁路。
- 本节证明repository级专用准备边界，不表示total runner已注册该路线，也不表示实际profile/adapter destination已核对；后二者仍属于后续集成边界。

## 64. TASK-083 Phase 5D Provider-open 实际目的地匹配证据

- core:model JVM 覆盖等价 path/大小写/default port 规范化、不同非默认端口隔离，以及 evidence `toString` 对 connection、host、protocol 的脱敏。
- 数据库专项覆盖错误 destination 五类表零写入、同 permit 正确重试、错误 connection 与跨日同时发生时目的地错误优先、当前 disclosure 漂移失败关闭及恢复同 binding 后重试。
- executor 专项覆盖错误 profile destination、profile/adapter protocol 不一致、受保护草稿不打开、adapter 调用0、数据库零写入和正确配置重试成功。
- API30/API35：reservation 专项各43/43、executor专项各23/23；数据库模块各272/272、generation模块各48/48。
- 统一离线门禁通过801个Gradle task，Debug/Release、Lint/Vital、R8、源码与5个APK安全扫描及备份排除全部通过；App真实Provider调用0、物理设备写入0。

## 65. TASK-064 Phase 2E5A chapter-plan bound preparation 证据

- `PersistentBudgetReservationDatabaseTest` 增至47项，其中4项覆盖：exact runner snapshot成功、通用Stage-token旁路零半状态、错误Job token/attempt边界拒绝，以及公开streaming入口清理被拒绝工件并持久化bound工件。
- API30/API35 reservation专项各47/47；`core:database`模块全量各276/276；`feature:generation`模块全量各48/48。
- `:core:database:test`与`:feature:generation:test`通过；统一离线门禁通过801 actionable tasks、Debug/Release、Lint/Vital、R8、源码与5个APK安全扫描及备份排除。
- 本阶段真实/Fake Provider调用均为0，物理设备写入0；route仍未注册，因此这些证据只关闭RequestIntent准备旁路，不冒充plan端到端执行。
