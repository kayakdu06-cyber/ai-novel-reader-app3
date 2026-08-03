# 织卷需求追踪矩阵

## 1. 核心需求映射

| 需求 | 功能 | 核心数据 | 关键测试 | 开发任务 |
|---|---|---|---|---|
| PR-001 API 配置 | FEAT-001~009 | DATA-040~042 | TEST-003~008、TASK-020 契约测试 | TASK-020~032 |
| PR-002 极简创建 | FEAT-010,014,015,016 | DATA-001,002 | TEST-001,002、TASK-037 冻结确认测试 | TASK-010,033,034,036,037 |
| PR-003 题材预设 | FEAT-011,070 | DATA-020~023 | TEST-058 | TASK-013,070 |
| PR-004 呈现选项 | FEAT-013 | DATA-002,005,021 | TEST-036~039 | TASK-035,050,058 |
| PR-005 篇幅规划 | FEAT-012,031 | DATA-001,002,006 | TEST-001、篇幅策略边界 | TASK-010,033,034,036,052 |
| PR-006 分阶段生成 | FEAT-020~023,030~037 | DATA-030~035 | TEST-010~020、TASK-042 发送前审计门、TASK-043 受控流草稿 | TASK-011,040~059 |
| PR-007 边生成边阅读 | FEAT-024,025,028,060~066 | DATA-001,003,004,030 | TEST-010,012,018、TASK-043 中断隔离 | TASK-010,043~049,090~096 |
| PR-008 长篇记忆 | FEAT-030~039 | DATA-005~012,034,035 | TEST-030~035 | TASK-012,051~061 |
| PR-009 模板重开 | FEAT-050~054 | DATA-020~022 | TEST-050~060 | TASK-013,071,075~078 |
| PR-010 模板来源分类版本 | FEAT-055~059 | DATA-020~023 | TEST-052~059,062,063 | TASK-013,070~074,079 |
| PR-011 编辑失效 | FEAT-038,064,065 | DATA-004,007~012,035 | TEST-032,033 | TASK-012,061 |
| PR-012 恢复幂等 | FEAT-026,040 | DATA-030~033 | TEST-012~015,018、TASK-040 防旁路、TASK-041 租约栅栏、TASK-042 审计后授权、TASK-043 草稿修订栅栏 | TASK-011,040~049 |
| PR-013 模型切换 | FEAT-005,006,009 | DATA-040,042 | TEST-003,005 | TASK-027,029,031,032 |
| PR-014 费用保护 | FEAT-016,070~074 | DATA-002,033,043 | TEST-070~076、TASK-037 未知价格占位 | TASK-011,037,080~086 |
| PR-015 书架阅读器 | FEAT-060~067 | DATA-001,003,004 | UI/性能矩阵 | TASK-010,090~098 |
| PR-016 本地隐私 | FEAT-007,080~084,087 | DATA-004,032,041,044,051 | TEST-080~082,087,089 | TASK-014~016,021,043,097,100~103,108 |
| PR-017 备份恢复导出 | FEAT-085,086,090 | DATA-050,051 | TEST-083~085 | TASK-100~103 |
| PR-018 诊断 | FEAT-083,091 | 诊断事件/脱敏快照 | TEST-081 | TASK-018,104 |
| PR-019 无障碍中文排版 | FEAT-061~063 | 阅读偏好 | UI/性能矩阵 | TASK-091~094,098 |
| PR-020 迁移升级 | FEAT-088,089,092 | DATA-051 | TEST-086~088 | TASK-017,106,107 |
| PR-021 内容边界 | FEAT-013,027,037 | DATA-005,008,035 | TEST-017,036 | TASK-012,035,050,058,059 |
| PR-022 外部数据发送知情 | FEAT-094 | DATA-040、连接确认记录 | TEST-090~092 | TASK-021,031,036,110 |

## 2. 非功能需求映射

| 需求 | 设计落点 | 测试 | 发布闸门 |
|---|---|---|---|
| NFR-001 数据可靠性 | 08 §10、10 §7 | TEST-013~015、086~088 | 章节/迁移 P0 全过 |
| NFR-002 安全 | 07 §11、11 全文 | TEST-006~008、080~089 | Release 安全清单 |
| NFR-003 性能 | 08 §12、12 §7 | 性能闸门 | 中档机/大数据集达标 |
| NFR-004 可维护性 | 07、08 §11 | 适配夹具/版本测试 | 版本元数据齐全 |
| NFR-005 可测试性 | 15 全文 | CI + 故障注入 | P0 测试 100% |
| NFR-006 可用性 | 03、04、12 | 主流程用户走查 | 技术术语不阻塞 |
| NFR-007 可恢复 | 10、11 §7–10 | TEST-012~015、083~088、TASK-041 双执行器与到期回收 | 备份/恢复/升级演练 |

## 3. P0 功能覆盖检查

| P0 领域 | 规格 | 数据 | 状态/错误 | 测试 | Backlog |
|---|---:|---:|---:|---:|---:|
| 连接与密钥 | ✓ | ✓ | ✓ | ✓ | ✓ |
| 极简创建 | ✓ | ✓ | ✓ | ✓ | ✓ |
| 分阶段生成 | ✓ | ✓ | ✓ | ✓ | ✓ |
| 边读边生成 | ✓ | ✓ | ✓ | ✓ | ✓ |
| 长篇记忆 | ✓ | ✓ | ✓ | ✓ | ✓ |
| 模板重开 | ✓ | ✓ | ✓ | ✓ | ✓ |
| 费用上限 | ✓ | ✓ | ✓ | ✓ | ✓ |
| 崩溃恢复 | ✓ | ✓ | ✓ | ✓ | ✓ |
| 加密与备份 | ✓ | ✓ | ✓ | ✓ | ✓ |
| 数据迁移/签名 | ✓ | ✓ | ✓ | ✓ | ✓ |
| 内容边界 | ✓ | ✓ | ✓ | ✓ | ✓ |

## 4. 变更检查清单

新增或改变需求时逐项回答：

- 是否需要新/改 `PR` 或 `NFR`？
- 是否有对应 `FEAT` 和优先级？
- 是否改变模板复制排除清单？
- 是否改变数据实体、加密分类、备份格式或迁移？
- 是否增加生成状态/错误/重试/付费调用？
- 是否改变模型能力和协议字段？
- 是否新增至少一个正常和一个失败测试？
- 是否加入 Backlog、里程碑和发布闸门？
- 是否记录关键产品/技术决策？

任何一项为“是”却没有文档落点，该变更不得进入开发。

## 5. 当前第二次评审结论闭环

| 曾识别的严重疏漏 | 当前落点 | 状态 |
|---|---|---|
| 费用可能无限增长 | PR-014、14、TASK-080~086 | 已纳入 P0 |
| 只有数据库无可靠备份 | PR-017、11、TASK-100~102 | 已纳入 P0 |
| 敏感正文/密钥泄露 | PR-016、11、TASK-014~016/108 | 已纳入 P0 |
| 签名丢失无法升级 | 11 §13、TASK-106~109 | 已纳入 P0 |
| 崩溃重复请求/重复计费 | 10、TASK-040~049 | 已纳入 P0 |
| 编辑后继续用旧记忆 | PR-011、06 §10、TASK-061 | 已纳入 P0 |
| Android 后台服务被系统终止 | 08 §6、TEST-018 | 已纳入 P0 |
| 中转站重定向窃密 | 07 §11、11 §11、TEST-006 | 已纳入 P0 |
| 只看模型名猜能力 | 07 §8、TASK-027 | 已纳入 P0 |
| 内容名称隐蔽导致实现含糊 | PR-004、12 §3 | 已用内外双层命名解决 |
| 模板复制旧正文/密钥 | 05 §4、TEST-050/051 | 已纳入 P0 |
| 模板来源和归类失控 | PR-010、05 §7–9 | 已纳入 P0 |
| 加密备份只有规格、恢复失败可能覆盖当前书库 | PR-017、11 §7–10、TEST-083/084、TASK-008A/100/101 | 已补入 M0 可执行尖峰和 P0 正式实现 |
| 自定义中转站只测连通、不测重定向和证书边界 | PR-001/016/022、07 §11、TEST-006~008、TASK-008B/021 | 已补入 M0 可执行尖峰和 P0 网络实现 |
| 主流模型不保证接受目标内容尺度 | PR-013/021、07 §8、TASK-027/028 | 能力与拒绝必须实测并透明呈现；不承诺或规避服务商策略 |
| 用户提供的测试密钥进入源码或文档 | PR-016、11、TASK-009/014/108 | 禁止落盘到源码/文档/报告；正式使用前轮换，后续只经应用内安全输入联调 |
| “细写”只变成形容词/粗俗词，关键成人场景仍淡出或身体状态跳变 | PR-004、06 §4.1/9.2、TEST-038/039、TASK-050/058 | 细写+避免淡出自动装配身体与感官连续性；固定负例集检查转场替代、动作反应、空间/身体状态和余波 |

## 6. TASK-020 追踪闭环

| 需求风险 | 实现落点 | 验证 |
|---|---|---|
| 不同服务商污染业务层 | `ProviderAdapter`、统一结果与事件 | 假 adapter 通过注册表替换 |
| 中转站未知字段被乐观发送 | 三态能力快照、`maySend` 仅接受 `SUPPORTED` | 未知能力全拒绝测试 |
| 用错另一模型/协议的能力缓存 | 请求、连接和快照一致性约束 | 跨模型/协议立即失败测试 |
| 正文、schema、地址进入日志 | 防泄漏值对象和安全 `toString` | 多类 canary 断言 0 暴露 |
| 流产生两个终态或完成后继续写 | `ProviderEventGate` | 重复/乱序/EOF 测试 |
| 连接配置在运行期被修改 | 规范化只读配置和不可变敏感头引用表 | 强制 MutableMap 修改失败测试 |

## 7. TASK-021 追踪闭环

| 需求风险 | 实现落点 | 验证 |
|---|---|---|
| adapter 绕过统一安全客户端 | 独立 `provider:transport` 组装模块 | provider 只提交受限 request spec |
| 密钥进入公开 header/query/日志 | Secret Store header binding、敏感名称拒绝、安全字符串表示 | canary、`api_key`、异常 message 测试 |
| 付费 POST 被 3xx 或底层重试重复发送 | 自动重试关闭、POST 重定向拒绝 | 307 只收到 1 个请求 |
| 跨站重定向窃取两个鉴权头 | 同源校验先于第二次请求 | target requestCount 为 0 |
| 取消后 Call/流仍占用 | requestId 活动表、Call/response close 切换 | 响应头等待期间取消测试 |
| 巨大/无限响应拖垮设备 | Content-Length 预检 + 流式字节计数 | 已知/未知长度双夹具 |
| Keystore 失效误报为服务商拒绝 | `CREDENTIAL_UNAVAILABLE` | 联网前失败、server 0 请求 |
| 诊断保存正文、地址或异常消息 | 加密结构化 sink、关联哈希 | API 35/API 30 真实 Keystore 集成 |

## 8. TASK-023 追踪闭环

| 需求风险 | 实现落点 | 验证 |
|---|---|---|
| OpenAI、DeepSeek 和中转站同名字段语义不同 | 显式 `OPENAI/DEEPSEEK/RELAY_MINIMAL` 策略 | 三模式请求 body 精确夹具 |
| 中转站未知高级字段导致请求失败或意外行为 | 最小模式只发 `model/messages/stream`，未知能力失败关闭 | 高级字段在联网前拒绝，server 0 请求 |
| 中文被网络分片破坏 | SSE byte parser + UTF-8 增量解码 | JVM 两字节分片、API 35/API 30 两字节节流 |
| finish reason 早于最终 usage 导致漏账 | finish 暂存至 `[DONE]` | usage-before-DONE 顺序断言 |
| 流中断被误当成功并触发章节提交 | 缺失 `[DONE]` → `STREAM_INTERRUPTED` | 已有正文/finish 仍失败夹具 |
| 思考过程混入小说正文 | 忽略 `reasoning_content` | DeepSeek SSE 夹具正文仅含 content |
| 服务商错误正文泄漏 | 只映射 status/code/type，不传播 message | error canary 0 暴露 |
| 请求提示或密钥进入普通字符串/产物 | 可清零 UTF-8 request buffer + Secret Store 引用 | 源码与 10 APK 安全扫描 0 命中 |
| 测试意外消费真实额度 | 只用本地 HTTP/HTTPS 假服务器 | 真实 API 调用 0 次，连接测试仅 GET models |

## 9. TASK-022 追踪闭环

| 需求风险 | 实现落点 | 验证 |
|---|---|---|
| 服务端保存小说上下文，破坏本地唯一事实源 | 请求固定 `store:false`，不用 conversation/previous response | request body 精确断言 |
| system/data/user 层次被扁平化 | Responses 原生 system/developer/user input 消息 | 三角色顺序与内容夹具 |
| 把 Chat 的 `[DONE]` 错套到 Responses | 仅语义终态完成提交 | completed/incomplete/failed/error 夹具 |
| 半流断开后误提交章节 | EOF 无终态 → `STREAM_INTERRUPTED` | 已有正文仍失败测试 |
| 重复 SSE 导致正文重复 | sequence 严格递增，冲突失败关闭 | duplicate sequence 正文只出现一次 |
| 新增未来事件导致崩溃 | 未知非终态忽略 | unknown event 位于两段正文之间仍成功 |
| 截断与拒绝混为网络错误 | max_output_tokens → LENGTH；content_filter → Refused | 非流截断与流式拒绝夹具 |
| usage 晚到漏账 | terminal response 内 usage 先于终态发出 | 事件顺序断言与六类 token 映射 |
| 请求/错误正文泄漏 | 可清零请求 buffer；不传播 error message | canary 与产物安全扫描 |
| 测试误触实体机或真实额度 | `ANDROID_SERIAL` 锁定干净 AVD；本地假服务 | API 35/API 30 各 1/1，真实 API 0 次 |

## 10. TASK-024 追踪闭环

| 需求风险 | 实现落点 | 验证 |
|---|---|---|
| API 版本漂移导致事件语义变化 | 固定 `anthropic-version: 2023-06-01` | POST/GET header 精确断言 |
| API key 进入公开 header | `x-api-key` 只走 PrimarySecret RAW | 假服务验收与安全扫描 |
| 缺少必填 `max_tokens` 造成无效付费请求 | 联网前 require | server 0 请求夹具 |
| thinking/signature 混入正文 | block 类型状态机只输出 text_delta | thinking 流中正文断言 |
| block index 错序污染章节 | start/delta/stop 生命周期校验 | 无 start 的 delta 失败夹具 |
| message_delta 累计 usage 被当增量相加 | 每次输出 provider-reported 累计快照 | 初始与最终两次 usage 断言 |
| `message_stop` 缺失却提交半章 | EOF → STREAM_INTERRUPTED | partial text 保留但无 Completed |
| pause/refusal/context 被混成自然结束 | stop reason 独立映射 | 三类终态夹具 |
| 未来事件导致崩溃 | 未知事件忽略、未知 stop reason 失败保守 | unknown event + pause turn 测试 |
| 真实额度或实体机被误用 | 本地 HTTP/HTTPS 假服务、serial 锁定 | 双 AVD 1/1，真实调用 0 |

## 11. TASK-025 追踪闭环

| 需求风险 | 实现落点 | 验证 |
|---|---|---|
| API key 出现在 URL、代理日志或重试地址 | 只用 `x-goog-api-key` Secret header；query 仅有公开 `alt/pageSize` | recorded URL 无 key + header 精确断言 |
| 模型 ID 注入路径或 RPC 后缀 | 单段模型名校验后再拼接方法 | 含斜杠模型联网前拒绝 |
| 新旧结构化字段混用 | 当前 `generationConfig.responseFormat.text.schema` | 请求 JSON 精确断言 |
| thinking 混入小说正文 | thought part 过滤；includeThoughts=false | thought + text 混合 chunk 夹具 |
| finish 后 usage 尾块漏账 | finish reason 暂存至 EOF | usage-only 最终 chunk 仍先于终态 |
| 没有 done 事件却把断流当完成 | EOF 必须已有 finish reason | partial text + missing finish 失败夹具 |
| prompt 拦截被误报网络错误 | promptFeedback 独立拒绝映射 | SAFETY prompt + no candidates 夹具 |
| 未来 finish reason 被当自然结束 | 未知 reason → UNKNOWN_RESULT | 枚举矩阵与失败保守实现 |
| 模型列表混入 embedding-only 模型 | supportedGenerationMethods 过滤 | listModels 固定夹具 |
| 测试误触实体机或真实额度 | 本地 HTTP/HTTPS 假服务、serial 锁定 | 双 AVD 1/1，真实调用 0 |

## 12. TASK-027 追踪闭环

| 需求风险 | 实现落点 | 验证 |
|---|---|---|
| 只按模型名猜能力 | 来源分层登记 + 三态字段 | 未登记高级能力保持 UNKNOWN |
| 换中转站后误用旧能力 | base URL + 协议 SHA-256 端点指纹 | 两地址指纹不同且默认字符串不泄露 |
| 用户覆盖撤销后自动值丢失 | 每来源独立记录、只删 USER_OVERRIDE | 一键恢复重新显露 probe 测试 |
| 旧探测永久放行付费字段 | 自动证据强制 expiresAt、adapterVersion 匹配 | 精确到期与版本错配测试 |
| 探测失败被误判为不支持 | INCONCLUSIVE 与 EXPLICITLY_UNSUPPORTED 分离 | 不确定结果保持 UNKNOWN 测试 |
| 并发迟到刷新覆盖新值 | Room 事务按 verifiedAt 单调替换 | 旧刷新不能倒退测试 |
| 数据库损坏导致乐观放行 | 映射失败丢记录、读取异常保守回退 | store failure → conservative 测试 |
| 四协议生成与能力页使用不同结果 | 四个 registry-backed resolver | 四模块 resolver 专项测试 |
| schema 升级清空旧书 | 显式 MIGRATION_4_5 与连续迁移表 | API 35/API 30 迁移专项通过 |
| 测试误用实体机或真实密钥 | serial 锁定双 AVD、本地数据夹具 | 真实 API 0 次，实体机 0 写入 |

## 13. TASK-028 追踪闭环

| 需求风险 | 实现落点 | 验证 |
|---|---|---|
| 只凭错误码重发，造成重复计费 | `FailureRequestState` 四态证据 | 结果不明/响应已开始的决策测试 |
| 已收到半章又自动重放整章 | `contentObserved` 先于错误码判断 | 流中断有/无正文分支测试 |
| 无网等待消耗重试次数 | `WaitForCondition(NETWORK_AVAILABLE)` | `NOT_SENT` 无网矩阵测试 |
| 429/5xx 无限等待或高频重试 | 最多 3 次、总等待 15 分钟、指数退避与抖动 | 次数、窗口、Retry-After 测试 |
| `Retry-After` HTTP-date 被忽略 | 统一 RFC 1123/秒数解析器 | 4 项解析测试 + 适配器集成测试 |
| Gemini 日配额被当短期限流 | 无 Retry-After 的 RESOURCE_EXHAUSTED → QUOTA | 双分支适配器测试 |
| 预算耗尽后仍执行自动修复/重试 | 预算闸门先于所有动作 | EXHAUSTED 覆盖测试 |
| 错误修复形成无限循环 | context/format 各最多修复一次 | 修复上限测试 |
| 适配器丢失传输层送达证据 | 四适配器透传 requestState | HTTP 失败与本地失败断言 |
| 测试误触实体机或真实额度 | serial 锁定模拟器、本地 MockWebServer | API 35 69/69、API 30 适配器 5/5；真实调用 0、实体机 0 写入 |

## 14. TASK-029 追踪闭环

| 需求风险 | 实现落点 | 验证 |
|---|---|---|
| 默认连接测试静默产生费用 | 基础验证只调用 `listModels` | default 路径 `generateCalls=0` |
| 测试把私人小说送到新 host | 固定通用探针，不接收小说正文参数 | 私人 canary 0 命中 |
| 错误密钥被“手填模型”绕过 | 只允许明确列表端点拒绝回退 | AUTH/网络/未知失败均不可保存 |
| 低成本探针实际没有硬上限 | 能力先验必须允许上限字段；固定 16 token | 四协议请求 body 精确断言 |
| 模型列表慢 60 秒后再探针 60 秒 | 单一 deadline，探针使用剩余时间 | 剩余 `totalStageMillis` 单测 |
| 探针失败导致已验证列表丢失 | 报告分离列表和生成结果 | generation failure 保留 Available 列表 |
| usage 缺失却被假装支持 | 未观察则保持 `INCONCLUSIVE` | 成功无 usage 分支测试 |
| 缓存失败触发付费重放 | 证据写失败只降级缓存，不重发 | generate 精确一次测试 |
| 流断开被误判连接完整通过 | 必须观察 STOP/LENGTH 语义终态 | missing terminal 失败测试 |
| 测试误用实体机或真实额度 | 本地 MockWebServer；设备 serial 显式锁定 | 真实 API 0、实体机 0 写入 |

## 15. TASK-030 追踪闭环

| 需求风险 | 实现落点 | 验证 |
|---|---|---|
| “本地优先”被误解为完全不联网 | 三块文案明确远程生成会发送必要内容 | Compose 文案断言 |
| 用户被迫逐条确认隐私说明 | 跳过和继续进入同一连接步骤，无复选框 | 双动作流程测试 |
| 跳过说明被误记为连接完成 | TASK-030 不持久化完成状态 | 返回说明与重启边界评审 |
| 系统返回直接退出连接步骤 | `BackHandler` 只在连接入口启用 | 页面返回/系统返回测试 |
| 旋转后突然回到首屏 | 根步骤使用 `rememberSaveable` | `scenario.recreate()` 测试 |
| 大字体/横屏看不到主动作 | 全页纵向滚动 + safe drawing insets | 200% 字体、横屏专项 |
| 平板文字行过长 | 内容最大宽度 560dp、居中 | 800dp 宽模拟专项 |
| 状态只靠颜色或颜色对比不足 | 标题/序号文字并存，语义颜色与对比校验 | 六组对比值 + 浅深截图 |
| 小按钮难点或 TalkBack 层级混乱 | Material 按钮 ≥48dp、heading 语义 | 触摸高度和 heading 测试 |
| 测试误触物理设备 | 所有设备命令显式 `ANDROID_SERIAL`/`adb -s` | API 35 全量 73/73、API 30 专项 4/4；实体机 0 写入 |

## 16. TASK-031 追踪闭环

| 风险 | 实现控制 | 验证证据 |
|---|---|---|
| 用户被迫理解协议和模型能力 | 官方服务名选择、基础检查、自动推荐模型 | 官方默认流程 Compose 测试 |
| 任意失败都开放手填并绕过鉴权 | 只消费 `ConnectionModelList.Unavailable` 的严格回退 | 中转手填与鉴权失败对照测试 |
| 默认测试静默产生费用 | 默认仅 `listModels`；完整验证二次费用确认 | 完整验证确认前调用数为 0 |
| 密钥原文留在 UI/重建状态 | 非 saveable 密码字段，检查即清空字符缓冲 | 尾四位 UI 与缓冲清零测试 |
| 临时 secret 在退出/崩溃后孤立 | no-backup 临时引用、退出撤销、进程重启清理 | 遗留 secret 撤销设备测试 |
| 中转地址诱导明文传输 | 领域端点策略在创建 secret 前拒绝远程 HTTP | 远程明文拒绝设备测试 |
| 大字体/横屏无法完成 | 全页滚动、560dp 上限、48/56dp 动作 | API 35 200% 与横屏复跑 |

## 17. TASK-043 追踪闭环

| 需求风险 | 实现落点 | 验证 |
|---|---|---|
| Provider 在审计前被打开 | `GenerationStreamingDraftRepository` 收口底层审计入口，执行器只消费 claimed authorization | fake adapter 在审计/租约/artifact 均成立后才收到调用 |
| 半流覆盖正式章节 | 加密 `STREAM_DRAFT` 与 `ChapterVersion` 分离 | 中断后既有正式版本内容与指针不变 |
| 每个 token 都写盘导致 I/O 放大 | 2 秒或 32 KiB 节流检查点 | 时间阈值、字节阈值与强制 flush 测试 |
| 旧网络回调覆盖新恢复草稿 | expectedRevision CAS + writer 永久栅栏 | stale writer 后续 append/flush 均拒绝 |
| Provider 停顿导致租约误过期 | 独立心跳协程，不依赖流事件 | 250 ms 停顿期间 100 ms 测试心跳持续更新 |
| 崩溃只剩 AtomicFile 备份却漏恢复 | 枚举 `.zjaf/.zjaf.bak/.zjaf.new` 并按 ref 去重 | backup-only artifact 可发现、恢复、删除 |
| 清理线程误删刚绑定的孤立草稿 | 进程互斥 + 删除前数据库二次核对 | 孤立/绑定生命周期与精确边界测试 |
| 失败草稿过早消失或无限残留 | 成功 24h、未成功 7d、孤立 24h；重复/冲突不自动删 | API 35/API 30 数据库保留矩阵 |
| 测试触发真实额度或实体设备 | 本地 fake adapter、显式模拟器 serial | 双 AVD Security 18/18、Database 58/58、Generation 3/3；真实 API 0、实体机 0 写入 |

## 18. TASK-044 追踪闭环

| 需求风险 | 实现落点 | 验证 |
|---|---|---|
| 宽松解析把错误状态写入长篇记忆 | 严格 UTF-8/JSON 扫描、重复键拒绝、根 object 与契约校验 | JVM 畸形、包装、尾随、重复键矩阵 |
| 超大或深层 JSON 拖垮 App | 字节、深度、节点、成员、数组、字符串和数字硬上限 | JVM 全资源上限 + Android 超契约大小专项 |
| 未知 schema 被当成当前版本 | 数值 `schemaVersion` 白名单与显式迁移 | 旧版迁移、未来版/错误类型失败关闭 |
| App 重启后再次获得修复次数 | `FORMAT_INVALID` 持久写入 Attempt 链 | 双 AVD 第一次修复、第二次暂停 |
| 格式修复重演完整创作并放大费用 | 修复请求不带原始创作提示，只带 schema/问题码/无效输出数据；温度 0 | 提示层数、参数与默认字符串脱敏断言 |
| 超契约大小在读取层异常后卡住 | 全局 4 MiB 内读出完整证据，再由契约判不可修复 | 双 AVD `VALIDATING → NEEDS_ACTION` 专项 |
| 并发/迟到校验覆盖新状态 | 当前租约、最新 attempt、唯一引用、修订/hash 与 CAS | 双并发只有一次成功 |
| 校验成功提前污染正式章节 | 只推进 `COMMITTING`，TASK-045 前不创建 `ChapterVersion` | 双 AVD正式版本计数为 0 |
| 测试误触真实额度或实体设备 | 本地 fake adapter、显式模拟器 serial | Database 62/62、Generation 6/6 双 AVD；真实 API 0、实体机 0 写入 |

## 19. TASK-045 追踪闭环

| 需求风险 | 实现落点 | 验证 |
|---|---|---|
| 校验通过后被伪造/旧许可提交 | 内部 `ValidatedOutputCommitPermit` 绑定最新 Attempt、Stage、artifact 修订/hash、校验时间和租约 | 证据变化或租约过期提交失败 |
| 正文成功但摘要/记忆/用量/状态只成功一部分 | `ChapterGenerationCommitRepository` 单一 SQLCipher/Room 事务 | 派生外键故障后版本、记忆、Usage、Stage、书进度全部不变 |
| 崩溃恢复重复插入章节或累计章数 | 成功 Stage 输出引用 + generationStage 唯一版本 + 稳定 payload hash | 草稿清理后精确重放，所有计数和行数不增长 |
| 两执行器同时提交同一输出 | 章节 currentVersion CAS + Stage 租约 CAS + 数据库串行事务 | 双协程结果为一次新提交、一次 replay；只有一个版本 |
| 迟到 AI 覆盖用户刚编辑的章节 | `expectedCurrentVersionId` 前置检查与切换 CAS | 用户改稿后提交拒绝，用户版本保持 current |
| 派生列表顺序或代码升级破坏幂等证据 | 记录按 ID 排序、逐字段长度前缀 hash，不使用 data-class `toString()` | 修改后双 AVD 全量回归 68/68 |
| 错误下一阶段导致 Job 跳跃或假完成 | 同 Job、非自身、PENDING、单调时间和状态机校验；无 next 时查询所有 Stage | 正常下一 Stage 激活与单 Stage Job 完成专项 |
| 提交证据泄露正文/密钥 | `outputReferenceJson` 仅版本、ID/hash 和 nextStageId；默认对象字符串脱敏 | 源码/APK 安全扫描和提交字段检查 |
| 测试误触真实额度或实体设备 | 只用本地数据库/加密 artifact 夹具，显式 serial | API 35/API 30 各 68/68；真实 API 0、实体机 0 写入 |

## 20. TASK-046 追踪闭环

| 需求风险 | 实现落点 | 验证 |
|---|---|---|
| 用户点暂停后仍打开新 Provider 请求 | 父 Job 持久控制 + claim 事务内 RUNNING/currentStage 复核 + 执行器调用前复查 | 发送前暂停无 Attempt；RequestIntent 后暂停令迟到 send/start 失败 |
| 在途取消只停 UI、数据库仍显示运行 | `settleActiveAttempt` 同事务取消 Attempt、FINAL Usage、Stage 与 Job | API 35/API 30 在途暂停与停止事务测试 |
| 停止只取消当前章，后续阶段重启后复活 | STOP 安全点批量取消所有非终态 Stage，完成章保持 SUCCEEDED | 双 Stage 停止覆盖暂停测试 |
| 暂停时丢失已付费增量 | Provider cancel 后强制 flush Keystore 加密草稿，再结算控制 | 慢 fake Provider 执行器与 artifact 保留断言 |
| 迟到回调把已暂停/停止任务复活 | request send/start/outcome 同时核对父 Job、当前 Stage、Attempt、租约与时间 | 控制提交后的迟到 send/start 拒绝测试 |
| 心跳和发送回调并发造成时间倒序 | 单次执行的心跳/send/start 通过同一 Mutex 串行持久化 | 慢流 100 ms 心跳 + 发送/停止集成回归 |
| 暂停撕裂本地校验或正式章节提交 | 校验失败证据与 Job PAUSED 同事务；提交成功后下一 Stage READY+Job PAUSED | 格式失败暂停、两 Stage 章节提交暂停专项 |
| 执行器崩溃后控制永远卡住 | 持久 PAUSING/STOPPING + 精确租约到期代安全点 | 到期前拒绝、边界时刻成功测试 |
| 测试误触真实额度或实体设备 | 本地 fake adapter、显式模拟器 serial | Database 75/75、Generation 7/7 双 AVD；真实 API 0、实体机 0 写入 |

## 21. TASK-047 追踪闭环

| 需求风险 | 实现落点 | 验证 |
|---|---|---|
| RequestIntent 被误判为从未发送 | claim→Started 崩溃窗口按未知处理；仅 Provider 证明未执行可回队 | intent-only 到期进入 UNKNOWN_RESULT |
| 查询状态时偷偷重放生成 | 独立 `queryRequestRecovery` 能力与 `UnknownResultRecoveryCoordinator`；协调器不持有生成动作 | fake adapter 查询 1 次、generate 0 次 |
| Provider 说未执行但本地已有正文/费用 | 纯策略同时要求可读空草稿、无已知 usage、发送证据一致 | 正文与 usage 矛盾均要求用户确认 |
| 远端仍运行却创建第二个 Attempt | 保留活动 Attempt、PROVISIONAL Usage、RECOVERY_REQUIRED 和稳定等待原因 | 运行中→完成无本地输出两阶段对账测试 |
| 远端完成但本地丢结果后误重发 | Attempt/Stage 置 UNKNOWN，提供方 usage FINAL，等待用户确认 | completed-without-output 专项 |
| 本地已有完整结果却重复请求 | 保持 SUCCEEDED Attempt，只进入本地恢复路径 | received-response 崩溃恢复且 attempt 数不增 |
| 普通状态 API 绕过确认门 | 恢复事件仅专用 Repository 可写；稳定原因拒绝通用 ISSUE_RESOLVED | 直接通用确认被拒绝 |
| 用户双击确认产生两次重试 | UNKNOWN→READY 与 NEEDS_ACTION→READY 同事务 CAS；确认不创建 Attempt | 双协程仅一次成功、attempt 数不增 |
| 异常返回前数据库仍显示运行 | 执行器异常、缺失终态和 UNKNOWN_RESULT 先持久结算再返回 | Generation 9/9 双 AVD |
| 测试误触真实额度或实体设备 | 本地 fake adapter、显式模拟器 serial | Database 81/81、Generation 9/9 双 AVD；真实 API 0、实体机 0 写入 |

## 22. TASK-048 追踪闭环

| 需求风险 | 实现落点 | 验证 |
|---|---|---|
| Service 内存与数据库状态分叉 | `ForegroundGenerationContract` 只解释持久 Job；服务生灭不写任务状态 | 全状态 directive JVM 7 项 |
| 通知泄露小说隐私 | 通用私密通知，不含书名、人物、Job ID 和正文 | API 35/API 30 通知内容专项 |
| 通知暂停只停界面不落盘 | 生产服务 action 调用 `GenerationControlRepository` | 双 AVD 生产服务暂停落库且 Attempt 为 0 |
| Android 15 限时后服务不退出 | `onTimeout` 独立协调落盘，1.5 秒硬停止 | API 35 3 秒真实系统探针约 4.648 秒退出 |
| 系统限时被误当永久失败 | 独立 `SYSTEM_FGS_TIMEOUT` 原因，可继续回 `READY` | Database 双 AVD 82/82 |
| FGS 绕过发送审计 | TASK-048 不持有 Provider 发送能力，不推动 Stage 执行态 | 服务专项 Attempt 始终为 0，真实 API 0 |
| Debug 探针进入正式包 | receiver 仅在 debug manifest | Release manifest receiver 0、生产 service 1 |
| 测试误触实体设备 | 探针脚本只接受显式 `emulator-N` 且要求 API 35 | 实体设备写入 0，系统配置 finally 恢复 |

## 23. TASK-049 追踪闭环

| 需求风险 | 实现落点 | 验证 |
|---|---|---|
| WorkManager 在后台偷偷发起付费请求 | `GenerationMaintenanceOperations` 无 Provider/连接/生成接口；调度无网络约束 | RequestIntent 生产 Runner 测试 Attempt 数保持 1，真实 API 0 |
| 只回收 Stage、父 Job 旧租约导致任务永久卡住 | `requeueExpiredPreRequestExecution` 同一 Room 事务回队 Stage+Job并清除双租约 | Database 双 AVD 精确断言双状态 `READY`、双租约为空 |
| 活动执行器被维护器抢占 | 扫描和写入都复核 Stage/Job 双租约、心跳、当前状态、当前 Stage 与单调时间 | 60 秒边界、活动心跳、并发 stale 测试 |
| RequestIntent 被当作未发送而重复计费 | 固定 `ProviderRecoveryEvidence.NOT_AVAILABLE` 进入 TASK-047 保守审计 | Job `NEEDS_ACTION`、Stage `UNKNOWN_RESULT`、Attempt 仍为 1 |
| 暂停/停止崩溃后永远卡在控制中 | 网络活动阶段的过期控制复用 `settleExpiredControl` | 纯策略路由 + 协调器动作测试 |
| 本地校验/提交不完整时误删或假完成 | `PAUSING/STOPPING + VALIDATING/COMMITTING` 明确 `DEFER_UNSAFE_OR_INCOMPLETE` | 策略延后测试；验收保留项公开记录 |
| 重复打开 App 堆积维护任务 | 启动/周期各自唯一名称，`KEEP`/`UPDATE` | 调度两次仍各 1 个 Work |
| 周期维护消耗电量或空间 | 24h/6h 弹性窗口，battery-not-low + storage-not-low | WorkInfo 约束设备测试 |
| Worker/R8 在正式包中失效 | WorkManager initializer、SystemJobService、Worker 两参构造器进入 Release seeds | `assembleRelease`、Release manifest/mapping 检查 |
| 诊断泄露小说或连接标识 | candidate/report/Work Data 只给脱敏字符串和通用计数 | toString/错误路径测试 + 15 APK 安全扫描 0 |
| 测试误触真实额度或实体设备 | 全部本地 Room/artifact/WorkManager 夹具，显式 emulator serial | App 45/45、Database 84/84、Generation 9/9 双 AVD；真实 API 0、实体设备 0 写入 |

## 24. TASK-050 追踪闭环

| 需求风险 | 实现落点 | 验证 |
|---|---|---|
| Prompt 规则散落、版本不可复现 | `PromptBundleCatalogV1` 固定 Bundle/schema/阶段模板并计算确定性绑定哈希 | 相同输入同哈希、任一规则/来源变化改哈希、未来 schema 失败关闭 |
| 某阶段漏掉硬规则或呈现规则 | 13 个 `GenerationPhase` 按枚举顺序全覆盖；远程阶段固定所需层 | 全阶段覆盖/顺序/本地远程分类 JVM |
| “细写”仍用淡出或事后概述替代 | 四个场景阶段自动装配 100% 关键过程、0 淡出替代和身体/感官连续性 | TEST-038 对应严格规则与 Provider 准备 JVM |
| 为了成人细节误提高暴力等维度 | 题材 conflict/injury/language/emotionalPressure 从冻结基线原样绑定 | detailed 与 reserved 绑定对比 + Database 持久快照测试 |
| 为少操作而放过年龄不明 | 新建虚构角色可自动补明确成年事实；显式未成年/真实人物/矛盾年龄 Provider 前阻断 | 自动成年事实与 blocked 准备测试 |
| 数据损坏后按默认值继续付费 | Room 绑定交叉检查 resolved profile、schema、所有权和 hash | 双 AVD Database 各 87/87 失败关闭路径 |
| 本地阶段或阻断阶段仍进入网络 | `LocalOnly/Blocked/Remote` 三分准备对象；bridge 无网络/secret/permit | bridge JVM + 真实 API 0 |
| 日志泄露 Prompt 或小说设定 | Bundle/stage/instruction/plan 默认字符串脱敏 | redaction JVM + 15 APK 安全扫描 0 |
| 测试误触实体设备 | 所有设备测试显式绑定两套项目 AVD | App 45/45、Database 87/87、Generation 9/9；实体设备 0 写入 |

## 25. TASK-051 追踪闭环

| 需求风险 | 实现落点 | 验证 |
|---|---|---|
| 一次自由文本同时生成种子/圣经/总纲，失败无法定位 | 冻结三阶段 Job 与精确依赖 | JobFactory 结构/确定性 JVM + 双 AVD E2E |
| 第一阶段等待未来阶段结果造成死锁 | seed/bible/outline 分离增量验证和提交 | E2E 从空库逐阶段激活至完成 |
| Provider schema 看似严格但本地宽松 | 完整 JSON Schema + 独立严格 UTF-8/JSON/字段/业务校验 | generation 11 项 JVM |
| 成人状态“不适用”或年龄矛盾混入角色事实 | character contract 仅允许确认成年/未知/非成年并交叉年龄 | NOT_APPLICABLE、亲密角色与年龄负例 |
| 300/10,000 章总纲被模型缩短或漏章 | 节拍连续覆盖 1..冻结目标章 | 80 章 E2E + 边界/缺口/重叠 JVM |
| 半套圣经或错误阶段被当成功 | permit + artifact/Attempt/Stage/Book/租约/hash + 单事务 | 错 stage ID 回滚 E2E，无实体/事实/修订副作用 |
| 重试重复写人物/事实或重复结算费用 | 稳定 ID、精确重放和 FINAL Usage 幂等 | 三阶段成功后完整重放 E2E |
| 测试调用付费接口或写入实体设备 | 无网络假适配器 + 明确 AVD serial | 两套项目 AVD 全量，真实 API 0、实体设备写入 0 |

## 26. TASK-052 追踪闭环

| 需求风险 | 实现落点 | 验证 |
|---|---|---|
| 300/10,000 章一次规划导致超上下文和高费用 | 本地最多 40 章卷、8 章窗口 | 10,000 章策略/解析 JVM 仍仅 8 条 |
| 模型擅自扩大窗口或缩短全书 | Provider maxItems=8 + 本地精确范围/next 校验 | 9 条、缺章/乱序、错误指针负例 |
| 下一窗漏章或重叠 | next 固定为 windowEnd+1 | 连续 1–8、9–16 双 AVD E2E |
| 每窗复制全部历史导致平方存储 | 当前 revision 只存本窗，旧窗走 parent 链 | 第二窗后 3 revisions、16 个 CHAPTER 节点 |
| 并发窗口覆盖较新计划 | 提交时 parent 必须是 current head | 错父 hash/head 回滚 E2E |
| 精确重放在 head 前移后失效 | 已完成 Stage 按 output reference/revision/nodes 回读 | 第一窗 replay 后继续第二窗 |
| 自动化导致重复付费 | 剩余 3 章的纯本地判断；每窗独立 idempotency | 阈值与相同/下一窗 key JVM |

## 27. TASK-053 追踪闭环

| 需求风险 | 实现落点 | 验证 |
|---|---|---|
| 为快出首章跳过成年人或应用硬规则 | `FirstChapterProgressionPolicyV1` + Bootstrap 严格人物事实 | 未成年/未知/真实亲密人物和篡改年龄负例 |
| 最小包被当作完整圣经/总纲污染长期记忆 | 独立 `first-chapter-bootstrap.v1` 加密 artifact，不建伪 revision | 提交后 Bible/Outline revision 数保持不变 |
| 第一章后规划与已读正文冲突 | Post-first Bible/Master 输入冻结第一章版本 ID/hash | 修改首章内容会改变两阶段 idempotency JVM |
| 第二章在完整规划前偷跑 | 数据库 permit 要求 Bible、Master、目标 Window 与上一章当前版本 | 快车道 E2E 第二章规划前 Provider-open 被拒 |
| 调用方伪造“闸门已通过” | 规范 evidence hash + Provider-open 事务内重算 | fake gate hash 无法 claim |
| 排队后第一章改稿或窗口变化仍提交旧正文 | Provider-open 与 Chapter commit 两次复核 | 旧版本/缺失适配证据失败关闭 |
| current head 已前移导致误判没有总纲 | 有界遍历 outline parent 链定位 master 与目标窗口 | 完整 post-first 链在后续窗口 head 下放行 |
| 测试触发真实费用或实体设备 | 本地假 Provider、显式项目 AVD serial | 双 AVD App 45/45、Database 87/87、Generation 15/15；真实 API 0、实体设备 0 写入 |

## 28. TASK-054 追踪闭环

| 需求风险 | 实现落点 | 验证 |
|---|---|---|
| 上下文裁剪丢失人物成年/身份或应用硬规则 | 必需候选先完整装入，禁止截断 | 策略 JVM + 双 AVD 成功路径核对成年/硬事实 |
| 上一章承接或目标卷章被旧历史挤掉 | 目标计划与上一章摘要为必需项，可选历史后装 | 超大时间线被整项省略而上一章仍保留 |
| 模型容量未知却乐观发送 | 未确认失败关闭；明确确认仅用 8192 保守回退 | 未知容量 Stage BLOCKED/Job NEEDS_ACTION，无快照/Attempt |
| 必需事实本身超预算时偷偷删减 | `REQUIRED_ITEMS_EXCEED_BUDGET` 阻断并建议更大模型/减少输出 | 策略边界负例，不产生远程 Stage 激活 |
| 组装后改稿、换窗口仍发送旧上下文 | snapshot 来源证据 + Provider-open 事务重验 | 改变 Outline head 后旧上下文被拒绝 |
| 重启/重放生成另一份上下文或重复写入 | 冻结 stage 绑定、规范 manifest/payload hash、精确重放 | 同一 succeeded Stage 回读相同 snapshot/payload |
| 本地准备被误计费或形成远程审计 | 本地状态事件与原子提交路径，不建 Attempt/Usage | 成功/阻断数据库测试均断言 Attempt 为 0 |
| 测试触发真实费用或实体设备 | 纯本地策略/假 Provider、显式项目 AVD serial | API 35/API 30 各 149 项全量；真实 API 0、实体设备 0 写入 |

## 29. TASK-055 追踪闭环

| 风险/需求 | 实现证据 | 验证证据 |
|---|---|---|
| 流式 JSON 外壳或解释污染正文 | `chapter-draft.v1` 单 `body` schema + 本地增量解码器 | JVM 任意分片、转义、Unicode、未知字段测试 |
| 截断后重段、漏段或误删 | 96 码点精确锚点，验证后只剥离一次，禁止模糊匹配 | 双 AVD 正常续接与错误锚点不追加测试 |
| 续接覆盖父草稿或费用混账 | 每次新 Attempt、Usage、artifact，预置累计正文 | 四次截断链及每 Attempt Usage FINAL 测试 |
| 重启清零次数导致无限续费 | 从持久 `retryParentAttemptId` 链计算，最多 3 次自动续接 | 第 4 次截断进入 `NEEDS_ACTION` 双 AVD 测试 |
| 终态落库与结算之间崩溃后误重发 | 输出 hash、分类、成功响应证据同事务；本地恢复结算无 Provider | 租约过期恢复和重复恢复幂等测试 |
| 错误提示/父输出被篡改后仍联网 | 续接提示绑定父输出、尾窗、锚点、序号与输入 hash | 未绑定提示 Provider 调用 0；hash/anchor 失败关闭 |
| 超大章节耗尽内存/存储 | 累计 UTF-8 4 MiB 上限和 2,048 码点尾窗 | JVM 边界测试与双 AVD 安全门禁 |
| 把流完成冒充正式章节 | `STOP` 只到 `VALIDATING`；TASK-056/057 提供记忆/追踪契约，TASK-058 提供检查门禁，仍等待 TASK-059 最终提交 | 状态机/验收文档和后续任务依赖 |

## 30. TASK-056 追踪闭环

| 风险/需求 | 实现证据 | 验证证据 |
|---|---|---|
| 摘要/状态串到错误章节版本 | 输出回显版本 ID、正文 hash、章节 ID/序号并做交叉校验 | JVM 来源四字段负例；双 AVD 完整 E2E |
| RequestIntent 后用户换了当前版本仍产生费用 | Provider-open 事务重验当前 `ChapterVersion` 与 hash | 换版本后 Provider 调用数 0，Attempt 保持 intent |
| 提取器发明人物或关系对象 | `allowedEntityIds` 白名单和关系目标规则 | 未知实体/错误关系进入一次修复，派生表 0 写入 |
| 章节派生结果篡改硬设定 | 只接受 `STORY_CANON/INFERRED` | `HARD_CANON/PLAN_ONLY` 严格拒绝测试 |
| 高强度场景后状态被含糊略过 | endingState + 身体/情绪/关系/知识/持有物等事件契约 | 身体伤势和物品连续性解析/落库测试 |
| 已校验原文与落库映射脱钩 | permit 校验 artifact revision/raw hash/canonical hash，提交 payload v1 完整散列 | 正常提交、精确重放和错误来源失败关闭 |
| 格式失败进入修复但费用仍临时 | 失败分类、Stage 转移和 FINAL Usage 同事务 | 未知实体修复路径断言 Usage FINAL |
| 重试重复写摘要/事件/事实 | 稳定 ID、版本唯一摘要、完整 output reference | 重放后仍为 1 摘要/2 事件/2 事实 |
| 修订后沿用旧候选记忆 | 候选输出绑定最终版本 ID/hash；hash 改变必须重提取 | TASK-056 契约/映射；TASK-059 最终编排门禁 |
| 测试触发真实费用或实体设备 | 本地假 Provider、显式项目 AVD | 双 AVD 各 156/156；真实 API 0、实体设备 0 写入 |

## 31. TASK-057 追踪闭环

| 风险/需求 | 实现证据 | 验证证据 |
|---|---|---|
| 把计划、猜测或隐喻写成已发生时间线 | 专用提示契约 + 实际事件字段/证据/已知实体校验 | 有效投影 JVM/E2E；未知参与者和非 LOCATION 负例 |
| 每个悬念都膨胀为伏笔 | PLANT 明确新线索语义、描述去重和每章 64 条上限 | 重复新线索/重复目标严格拒绝 |
| 模型覆盖既有伏笔身份或历史 | 既有 ID/描述/重要度/fromStatus 精确回显 | 四字段偏差负例均进入无效结果 |
| 非法跳转或随意丢弃伏笔 | 四操作转换白名单；ABANDON 需明确不可能 + 100% 置信度 | 非满置信度 ABANDON 拒绝；DEVELOP/RESOLVE 状态边界测试 |
| RequestIntent 后依赖变化仍付费 | 正文/记忆/伏笔/实体四快照 Provider-open 重验 | 修改旧伏笔后 Provider 调用数 0、派生写入 0 |
| 并发任务覆盖伏笔状态 | 提交前快照重验 + 期望旧状态 CAS + 单事务 | 错误状态导致整笔回滚；正常 E2E 原子生效 |
| 编辑中间发展章节却遗漏依赖 | `foreshadow_transition` 追加历史；stale 查询检查任一旧版本转换 | 条目当前来源已前移仍被旧中间转换正确标 STALE |
| 重试重复写时间线/转换 | 稳定 ID、projection/Stage 唯一证据、完整 payload hash | exact replay 后行数不增加 |
| 新 schema 损坏旧书 | v7→v8 只增表/索引/触发器，Room 导出 schema 8 | API 35/API 30 全迁移与 Database 89/89 |
| 测试触发真实费用或实体设备 | 本地假 Provider、显式项目 AVD serial | 双 AVD 各 159/159；真实 API 0、实体设备 0 写入 |

## 32. TASK-058 追踪闭环

| 风险/需求 | 实现证据 | 验证证据 |
|---|---|---|
| 明显无效正文仍花费模型检查费用 | `ChapterLocalConsistencyChecker` 先于请求准备 | 本地 major 返回 `LocalRevisionRequired`，Provider 调用 0 |
| 人物、年龄、离场、地点、物品或时间冲突漏进模型自由判断 | 结构化确定性事实和专属问题码 | 8 项本地规则单元测试 |
| 调用方伪造“严格场景已通过” | 模式只从冻结 `SceneExecutionContract` 导出，独立成年人/真实人物守卫 | 5 项策略 + 门禁阻断请求测试 |
| 模型漏查项目或只给总分 | 23 项标准按策略顺序恰好一次，每个 issue 恰好被引用一次 | 缺失/乱序/重复引用严格拒绝 |
| 细写场景淡出或漏关键过程 | 每个冻结 process node 按序 COVERED/MISSING；缺失绑定固定 issue | TEST-039 淡出/过程/动作/空间/身体/感官/余波固定负例 |
| 模型把普通文风差异升级成硬阻断 | 问题码固定精确严重度和修订动作 | voice minor、mechanical detail 不可升级 blocker 测试 |
| 检查另一候选后复用旧结果 | 候选/本地/场景/实体/证据/过程来源绑定 + 持久 inputHash | 换 candidate 后 Provider 调用 0，Attempt 保持 intent |
| 报告复制正文造成额外泄露 | 只保存码点范围和白名单 ID，无 evidence/suggestion 自由文本 | mapper 断言 `issuesJson` 不含候选正文；安全扫描 0 命中 |
| “允许提交”被冒充“已经发布” | mapper 只生成候选报告草稿，不单独插入外键行 | E2E 接受后 Stage 仅到 COMMITTING；TASK-059 显式依赖 |
| 测试触发真实费用或实体设备 | 本地规则/假 Provider、显式项目 AVD serial | 双 AVD 各 162/162；真实 API 0、实体设备 0 写入 |
