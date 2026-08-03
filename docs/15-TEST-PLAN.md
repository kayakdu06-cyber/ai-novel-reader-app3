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
| TEST-089 | 最近任务页 | 锁定/后台不显示敏感正文 |
| TEST-090 | 新远程 host 未确认 | 连接测试可用，但小说请求不发出 |
| TEST-091 | 修改 host/port/protocol | 原数据发送确认失效并重新提示 |
| TEST-092 | TXT/Markdown 导出 | 明确提示为未加密文件，加密备份文案不混淆 |

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

性能目标需在技术尖峰后以目标机实测校准。

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
