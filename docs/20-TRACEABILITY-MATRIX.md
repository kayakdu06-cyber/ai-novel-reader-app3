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
| PR-007 边生成边阅读 | FEAT-024,025,028,060~066,121~123 | DATA-001,003,004,030、受保护草稿投影 | TEST-010,012,018,093~098、TASK-043 中断隔离 | TASK-010,043~049,062~069,090~096 |
| PR-008 长篇记忆 | FEAT-030~039 | DATA-005~012,034,035 | TEST-030~035 | TASK-012,051~061 |
| PR-009 模板重开 | FEAT-050~054 | DATA-020~022 | TEST-050~060 | TASK-013,071,075~078 |
| PR-010 模板来源分类版本 | FEAT-055~059 | DATA-020~023 | TEST-052~059,062,063 | TASK-013,070~074,079 |
| PR-011 编辑失效 | FEAT-038,064,065 | DATA-004,007~012,035 | TEST-032,033 | TASK-012,061 |
| PR-012 恢复幂等 | FEAT-026,040 | DATA-030~033 | TEST-012~015,018、TASK-040 防旁路、TASK-041 租约栅栏、TASK-042 审计后授权、TASK-043 草稿修订栅栏 | TASK-011,040~049 |
| PR-013 模型切换 | FEAT-005,006,009 | DATA-040,042 | TEST-003,005 | TASK-027,029,031,032 |
| PR-014 费用保护 | FEAT-016,070~074 | DATA-002,033,043 | TEST-070~076、TASK-037 未知价格占位 | TASK-011,037,080~086 |
| PR-015 书架阅读器 | FEAT-060~067 | DATA-001,003,004 | UI/性能矩阵 | TASK-010,090~098 |
| PR-016 本地隐私 | FEAT-007,080,083,084,087；FEAT-081/082 已取消 | DATA-004,032,041,044,051 | TEST-080~082,087；TEST-089 已取消 | TASK-014~016,021,043,100~103,108；TASK-097 已取消 |
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
| NFR-008 章节生成响应 | 06 §2.1、08 §12.1~12.3、25 全文 | TEST-093~099 | 首段/正文/提交 P95 达标；5 分钟安全处置；10 分钟发布阻断 |

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
| 生成速度与慢服务处置 | ✓ | ✓ | ✓ | ✓ | ✓ |

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
| 把流完成冒充正式章节 | `STOP` 只到 `VALIDATING`；TASK-056/057 提供记忆/追踪契约，TASK-058 提供检查门禁，TASK-059 提供唯一最终协调器与原子提交 | 双 API 最终候选/全量回归与状态机证据 |

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
| “允许提交”被冒充“已经发布” | mapper 只生成候选报告草稿；TASK-059 只有严格恢复、复核和原子提交后才发布 | E2E 接受后只创建本地 COMMIT Stage；最终候选事务负正例通过 |
| 测试触发真实费用或实体设备 | 本地规则/假 Provider、显式项目 AVD serial | 双 AVD 各 162/162；真实 API 0、实体设备 0 写入 |

## 33. TASK-059 完整追踪闭环

| 风险/需求 | 实现证据 | 验证证据 |
|---|---|---|
| MINOR 被错误升级成自动改写 | 三路仓库只调用 `ChapterRevisionPolicyV1`，调用方不能另选路线 | MINOR 策略单测；接受路径进入本地 COMMIT Stage |
| BLOCKER/MAJOR 无限互改 | 比例模式 1 次、细写模式 2 次；次数与候选 hash 历史来自冻结输入 | 额度耗尽后 Stage/Job/Usage 原子 `NEEDS_ACTION`，无后继 Stage |
| 进程恢复后用新问题集合重放旧修订 Stage | 完整策略输入与结果生成 route binding hash，同时写入一致性封存与后继来源 | 同为 MAJOR、仅问题 ID 改变的重放被拒绝，原 Stage/后继不变 |
| 额度耗尽仍提前发布候选章节 | 一致性结果可在 COMMITTING 安全点直接结算 NEEDS_ACTION，不创建 COMMIT/REVISE | API 35 专项断言 `currentVersionId` 仍为空、后继 Stage 不存在 |
| 候选封存时偷换版本/hash/revision | MEMORY/TRACKING/CONSISTENCY 封存重新核对当前 Stage 冻结 binding | 既有来源篡改负例继续通过，候选专项合计 13/13 |
| REVISE Stage 保留但发送前换了另一份提示 | Stage 来源冻结 request source binding；Provider-open 对照最新 Attempt input hash | 不同 input hash 保持 INTENT_RECORDED/PROVISIONAL，Provider 未领取发送权 |
| 上层伪造修订正文长度绕过过短门禁 | 数据库重新读取加密 artifact，严格 UTF-8 解码并复算码点数 | 长度差 1 即拒绝，Stage 留在 COMMITTING，无 MEMORY 后继 |
| 修订成功后复用旧候选身份或派生数据 | 新版本 ID、新正文 hash、revision+1、完整历史和结果 binding 同时核对 | 新 BODY 封存后只激活绑定新候选的 MEMORY Stage |
| artifact 清理后重放时改长度或改策略 | 修订结果 binding 覆盖来源 route/request、长度、hash 和候选历史 | 精确 replay 成功；改策略或改长度均拒绝，行数与后继不变 |
| 修订结果指纹在重新提取中途丢失或被替换 | MEMORY/TRACKING 封存同时核对当前 Stage 来源指纹、封存草稿和下一 Stage 来源；CONSISTENCY 分流点才允许生成新的策略指纹 | 丢失指纹时 MEMORY 留在 COMMITTING、TRACKING 不创建；完整修订派生链仍可到额度耗尽分流 |
| 上层从 opaque permit 手工伪造最终 artifact 证据 | 候选封存仓库在成功/精确 replay 时直接返回由持久 Attempt 和封存草稿生成的 `ChapterFinalCandidateArtifactEvidenceV1` | BODY 修订封存返回证据与持久响应逐字段一致；API 35 专项 17/17 |
| 实际协调器只验证模型结构但没有推进持久 Stage | `ChapterCandidateDerivedStagePersistenceCoordinatorV1` 把已审计 MEMORY/TRACKING 结果、冻结请求、最终 Usage 与候选身份装配后调用唯一封存仓库 | 4 项规划器 JVM 测试 + 371 项统一离线门禁；真实 API 0、物理设备写入 0 |
| 一致性 gate、有限策略和数据库路线由不同调用方分别选择 | `ChapterCandidateConsistencyRoutingCoordinatorV1` 从同一冻结候选与报告一次生成 gate、策略输入、精确 revision request 和持久路线 | MINOR/MAJOR/额度耗尽/候选错配/跨 Job seed 共 5 项 JVM 负正例；独立重跑 8/8 |
| 最终提交前由上层手工拼装正文、派生数据和四类证据 | `ChapterFinalCandidateCommitDraftMapperV1` 只接受同一候选 lineage、ACCEPT gate、唯一 BODY/MEMORY/TRACKING/CONSISTENCY evidence 和来源一致的派生草稿 | 4 项映射器 JVM 测试 + 371 项统一离线门禁；缺失/重复/错 hash/非接受均拒绝 |
| 进程重启后把未经验证的 artifact 明文或宽松 JSON 当成最终候选 | `ChapterFinalCandidateArtifactRecoveryCoordinator` 只经 `AndroidProtectedArtifactStore` lease 按固定角色顺序读取，核对 descriptor/ref/revision/type、raw/canonical hash，并复用三套严格 Parser | 5 项 JVM 覆盖乱序、缺失/重复、descriptor、revision、类型、payload、schema、canonical hash 与脱敏；371 项统一离线门禁、安全扫描通过 |
| 最终 COMMIT 重启后由调用方猜测修订上限、候选历史或预期父版本 | `ChapterFinalCommitStageBindingV1` v2 在 ACCEPT 路线冻结完整 history、上限、expected current、CONSISTENCY 前驱和 route binding；最终仓库在任何正式行写入前与草稿/封存链逐项复核 | API 35 最终候选专项 19/19；改上限或 expected current 均原子失败，正式版本/summary/report 为 0，Stage/Job 保持可恢复 |
| 重启后按 phase 猜测候选 Stage，或把旧 Attempt/未结算 Usage/损坏模型快照用于最终映射 | `ChapterFinalCandidateRecoveryRepository` 在单一只读事务中从 final v2 前驱反向恢复唯一 CONSISTENCY→TRACKING→MEMORY→BODY 链，核对连续 next Stage、冻结输入、最后成功 Attempt、FINAL Usage、同书章节和严格模型 JSON；恢复快照不是提交许可 | API 35 最终候选专项 23/23；初始与一次修订完整链成功，断链和损坏模型快照失败且无正式行/状态前进；371 项统一门禁与安全扫描通过 |
| 重启后缺少本地报告、expectation 或场景契约而猜测最终一致性映射输入 | `ChapterFinalConsistencyMappingSnapshotCodecV1` 只从已绑定一致性请求和同一 routing spec 冻结最小输入，严格校验 exact keys、类型、集合顺序、跨对象 hash/正文计数/检查标准/过程节点关系；不保存正文、名称、证据 payload、提示词或 API 信息 | JVM 7/7 覆盖确定性往返、非 canonical 根键序同 hash、未知字段、字符串伪 null/数字、集合乱序/重复、跨对象篡改及诊断脱敏；371 项统一离线门禁、安全扫描和备份排除通过 |
| 快照只存在内存，重启后 final Stage 仍无法证明映射来源 | ACCEPT 路线把 canonical 快照作为嵌套 object 写入 final Stage v3，并同时冻结原请求 source binding、快照 hash 与完整外层 input hash；REVISE/NEEDS_ACTION 明确拒绝夹带；恢复仓库和最终提交仓库复核 CONSISTENCY seal 的 source binding | API 35 最终候选专项 25/25；缺快照和绑错请求均在创建 final Stage 前失败且不发布章节；JVM 相关 15 项、371 项统一门禁、安全扫描和备份排除通过 |
| 修订正文来源 binding 与最终接受 route binding 被当成同一个值 | 恢复仓库从 CONSISTENCY 输入链单独返回候选修订 binding；最终协调器只用它构造候选身份，并用重新计算的有限策略 hash 独立复核 final source route | 初始候选要求 null、修订候选要求非空且两种 binding 可不同；协调器修订恢复正例和数据库修订链断言通过 |
| final Stage 有恢复数据但仍由上层手工拼装或提前进入 COMMITTING | `ChapterFinalCandidateCommitCoordinatorV1` 按固定顺序执行 v3 快照解析、artifact 恢复、三套 mapper、有限策略、最终 draft；全部成功后才走 `LOCAL_OUTPUT_READY`，COMMITTING 重启复用持久 mapping time | 协调器 JVM 6/6 覆盖 PREPARING、COMMITTING、READY/SUCCEEDED、策略篡改、转换失败和修订 binding；相关链 33/33、API 35 数据库 25/25、371 项统一门禁通过 |
| runner 领取 final Stage 后绕过唯一协调器，或恢复时偷取其他 worker 租约 | `ChapterFinalCandidateCommitStageExecutorV1` 只在 READY 精确领取一次，PREPARING/COMMITTING 只恢复同 owner 的持久 token，SUCCEEDED 零提交，随后只调用唯一最终协调器 | 执行器 JVM 8/8；最终提交相关链 41/41；真实 DAO 的领取 SQL 将 acquired/heartbeat/updated 同时写为请求时间；生产 `src/main` 旁路审计未发现实际绕过调用；371 项统一门禁通过 |
| 旧 DRAFT Stage 的合法数组来源被误识别成损坏候选 binding | `parseIfBound` 对合法非 JsonObject 返回未绑定；畸形 JSON 仍失败，当前候选 policy object 仍严格验签 | JVM 3/3；API 35/API 30 Database 114/114、Generation 28/28 全量通过 |
| 局部专项通过但旧链或正式包回归 | 同一 WIP 运行双 API 三模块全量、Release/R8、统一 JVM/安全/备份门禁 | API 35 与 API 30 各 187/187；JVM 467；Release/R8、371 tasks、`SECURITY_SCAN_OK`、备份排除和 `git diff --check` 通过 |
| 把任务完成误写成整 App 已可自动生成 | TASK-059 的完成边界是有限修订与 COMMIT_CHAPTER 专用执行入口；总 runner 仍单独接线 | 状态、待办和交接文档均保留“无总 runner”限制；真实 API 0、实体设备写入 0 |

## 34. TASK-060 阶段追踪（已完成）

| 风险/需求 | 实现证据 | 验证证据 |
|---|---|---|
| 详细计划产生太多关键词导致整章生成失败 | 编译结果保留最多 128 个探针并报告遗漏数；畸形 JSON、大小、深度、叶子和单 token 上限仍失败关闭 | JVM 覆盖 128/130 唯一探针、256/257 叶子及大小/深度边界 |
| 章计划占满名额，用户补充和目标弧完全不参与召回 | 三路先保留与执行配额一致的 32/16/16 名额，再把剩余容量按路由优先级分配 | 190 个跨路由唯一 token 仍保留目标章 96、用户 16、目标弧 16 |
| FTS 查询或候选数量拖垮移动设备 | 总查询 64、每探针 16、累计后最终文档 128；所有查询复用 `searchBeforeChapter` | 双 AVD 覆盖 153 条命中文档时逐探针与最终双重上限 |
| 同一记忆跨多个关键词重复进入上下文 | 以 `documentId` 聚合，另验 `(bookId, sourceType, sourceId)` 唯一映射，分别累计三路命中 | 中文双字命中累计为 2 但结果文档只出现一次 |
| 其他书或未来章节污染当前章 | 单事务先验证目标书存在，SQL 和返回行双重核对书 ID 与 `chapterIndex < target` | API 35/API 30 专项包含其他书和目标章同序号文档，均不进入结果 |
| 排序重放漂移或调试输出泄露计划/检索词 | 固定九级排序；指纹覆盖实际执行探针、书、目标章与策略版本；结果/命中/探针字符串表示均脱敏 | 稳定 replay、输入变化、各排序层与 canary 断言；core/database 双 API 各 126/126 |
| 把检索指针误当权威记忆直接发送给模型 | 2B1 只返回派生指针；2B2 重读六类权威行并复核 hash；2C1/2C2 才允许映射为章前候选并在 Provider-open 重验 | 六类旧/损坏指针均被剔除或自动重建；章前接线与双 API 全量通过后才把 TASK-060 标记完成 |
| 索引仍指向旧 Bible、被替换章节、归档人物或已解决伏笔 | 单 Room 事务按六类 source ID 最多执行六个批量查询；SQL 与 Kotlin 双重要求同书、有效状态、当前 Bible/章节版本及 `< targetChapterIndex` | 双 AVD 专项把 Bible head、章节 current version、人物归档和伏笔状态分别前移，8 个旧指针全部被拒绝且整批不抛错 |
| 派生索引的 hash、章节或重要度被篡改后进入模型 | 权威行重新调用唯一 `MemorySearchDocumentFactoryV1`，除 SQLite rowid 外与召回指针逐字段精确比较 | 篡改 `sourceContentHash` 只拒绝该命中，其他命中保持原有顺序；返回 `rejectedPointerCount` 与 `indexRebuildRequired` |
| hydration 逐条查库造成 128 次 N+1，或错误信息展开私密实体 | 按六类去重 ID 分组，空组跳过，最多六查询；六类 wrapper、hit、result 均自定义脱敏字符串 | 编译/Room 全量通过；六类真实行 8 个命中保持输入顺序，canary 不出现在结果/命中字符串；API 35/API 30 core/database 各 131/131 |
| 所有章节级 STORY_CANON 都被当成不可裁剪硬事实，长篇上下文随章节数必然膨胀 | Phase 2C1 只把当前有效 `HARD_CANON` 放入强制路线；普通 `STORY_CANON` 仅在 FTS 相关时进入 | 双 AVD 真实 Room 测试先断言无关键词时章节事实不进入，再以匹配词证明它只带 FTS 路由 |
| 旧逻辑先截断 128 伏笔再判断到期，低重要度到期伏笔被静默挤掉 | SQL 先过滤 `VALID`、未解决、来源 current/早于目标章和到期条件，再按 importance/update/id 排序；查询 `limit+1` 探测强制溢出 | 双 AVD 排除旧章节版本、未来章、未到期和已解决来源，仅全局到期伏笔进入 |
| 强制事实/伏笔超过选择上限时静默裁剪，模型在缺事实状态下继续 | 强制+最近先合并；超过 hardLimit 返回 `MANDATORY_OVERFLOW`、空 items、明确 overflow count，并在运行任何 FTS 前结束 | hardLimit=3、4 个硬事实返回空选择、overflow=1、执行探针/FTS 命中均为 0 |
| 强制、最近和 FTS 跨事务读取形成时间切片竞态，或合并后丢掉相关度证据 | 整个 Phase 2C1 在一个 Room 事务；按强制→最近→FTS 的插入顺序按 source identity 去重，逐项保留目标章/用户补充/目标弧命中数 | 三类 core item 与 FTS 合并后不移动，FTS 新项后置且有逐路命中；API 35/API 30 core/database 各 136/136 |
| Phase 2C1 已选好记忆但旧上下文仍全量加载 STORY_CANON、时间线和开放伏笔 | `ChapterContextAssemblyRepository` 以权威路线结果作为普通事实/摘要/历史/时间线/伏笔的唯一候选入口；仅当前人物和每个属性最新事件保留独立安全路线 | 真实 Room 用例断言命中 STORY_CANON 进入、无关 STORY_CANON 与大时间线不进入；双 API 专项各 5/5 |
| 强制记忆超界后仍生成部分 snapshot 或创建 Provider Attempt | `MANDATORY_OVERFLOW` 映射为独立上下文阻断原因，Stage/Job 在组装事务内结束，不进入预算、不建 snapshot、不激活计划 Stage | 512 个硬事实加上一章摘要触发阻断；snapshot 为空、上下文/计划 Attempt 均为 0 |
| 搜索指针损坏后只能要求用户手工修复 | 组装阶段发现 hydration 拒绝时强制完整重建该书索引一次并重新选择；第二次仍异常才失败关闭 | 篡改事实指针 hash 后仍自动恢复并成功组装，重建后指针回到权威 hash |
| snapshot 完成后动态事实/事件/时间线/伏笔变化，旧 payload 仍被发送 | manifest 冻结逐项路线和三路命中证据；Provider-open 在同一 Room 事务重跑权威选择、候选映射与预算，要求 payload hash 和完整 manifest 与 snapshot 完全一致 | 同步使已选 STORY_CANON 与索引失效后，Provider-open 报动态记忆变化且计划 Stage Attempt 为 0；API 35/API 30 core/database 各 139/139 |
| CJK 双字 token 中的下划线被 Android FTS4 拆开，导致不相邻汉字误命中 | 双字 token v2 改为全字母数字编码；回填 schema 升为 2，已有 v1 标记在首次组装时自动整书重建 | “甲乙”只命中相邻文档、不命中“甲丙乙”；v1 文档/标记自动更新为 v2，双 API 通过 |
| 尖峰库通过但正式 SQLCipher schema、召回仓库或多路配额性能退化 | 在正式加密 `ZhijuanDatabase` 插入 10,000 条生产索引文档，以 20 个固定中文人物/地点/物品/伏笔词走完整 `MemorySearchRecallRepositoryV1` | 20/20 命中、无关查询为空、replay 一致、三路 41 个实际探针仍取回 20 个目标；API 30/35 热查询中位约 6.07/4.35 ms，双 API 全量各 143/143 |

## 35. TASK-061 阶段追踪（已完成）

| 风险/需求 | 实现证据 | 验证证据 |
|---|---|---|
| 生产用户编辑只切 current，继续使用旧摘要、伏笔和搜索索引 | `ChapterUserEditRepository` 在一个 Room 事务中捕获旧搜索 identity、调用既有 stale 级联、插入不可变 `USER_EDIT` 版本、CAS 为 `EDITED/UNKNOWN` 并删除旧索引 | TEST-032 的 10 章场景：第 3 章旧摘要、第 3–10 章聚合、第 4–10 章上下文/报告按规则 stale，旧 FTS 行为 0 |
| 用户编辑覆盖并发生成或另一设备刚保存的新版本 | 命令必须携带预期 current；编辑专用 SQL 同时比较旧 current、旧 status、旧 consistency 和单调时间，任何一项变化都 CAS 失败并回滚 | 过期 expected current 不产生 v3，current 仍为已提交 v2；跨书/错章在写入前失败 |
| 崩溃重放重复增加版本号或同 ID 悄悄替换正文 | 新版本 ID、parent、source、null Stage/model、正文 hash 与正文必须全部一致才算 replay；同 ID 不同正文失败关闭 | 精确 replay 保持 2 个版本且 `replayed=true`；冲突正文不覆盖已保存版本 |
| 调用方伪造正文 hash，或日志展开用户正文 | repository 内部以 UTF-8 计算 SHA-256，命令不接收 hash；命令/结果 `toString()` 脱敏，正文限制 4 MiB | 测试复算新版本 hash；canary 正文和章节/版本 ID 不出现在默认字符串表示 |
| 编辑早期章节时自动删除后续正式正文 | stale 级联只把后续章节标为 `CONSISTENCY_UNKNOWN/UNKNOWN`，不改 current version 或 `chapter_version` 行 | 第 4–10 章 current 与正文逐章保持原样；API 30/API 35 定向各 3/3、数据库全量各 146/146 |
| 把原子失效误报为完整重建 | Phase 1 明确不建 Provider job、不伪造新摘要/索引；只有 Phase 2B3B2E 通用逐章链和 TEST-033 通过后才关闭任务 | Phase 1 报告保持未完成；最终 10 章 ordinal 4–16 Fake Provider 与生产上下文权威排除通过，报告 106 才标记完成；真实 API 0、物理设备写入 0 |
| 把“需要重建的清单”误报为“已能重建” | `ChapterEditRebuildPlanRepository` 为每一步给出 `READY/WAITING/ALREADY_SATISFIED/BLOCKED` 和精确 blocker；未实现策略失败关闭，计划本身绝不写 Job/Attempt/业务表 | 10 章编辑第 3 章得到 32 步，其中仅 1 步 READY、31 步 BLOCKED；写入计数均为 0，TEST-033 仍明确待办 |
| 规划后章节或派生状态变化，旧计划仍继续执行 | `planHash` 覆盖请求身份、整个受影响 current-version 集合、状态、步骤和依赖；执行前 `requireCurrentMatches` 在单事务内重建完整计划 | 编辑章补入摘要或后续章切换 current version 后，旧计划均失败；重新规划后才可匹配 |
| 长篇按章节逐条查 current/tracking 或反复线性找前驱，造成 N+1/O(n²) | DAO 按书批量 join current chapter/version，并按范围批量读取 tracking；内存构建以 ordinal O(1) 引用前驱 | 10 章计划双 API 确定性通过；实现审查确认无逐章 DAO 调用和 `drop/any/single` 依赖扫描 |
| 只规划编辑章 tracking，遗漏后续正式章的顺序 replay | 影响计划为编辑点至最新 current 章逐章建立 tracking、context、consistency 和 aggregate 步骤；当前不能安全覆盖的步骤显式阻塞而非省略 | 10 章编辑第 3 章保留后 7 章正文并生成完整 32 步；双 API 定向各 4/4、数据库全量各 150/150 |
| 旧派生占住唯一槽，只能覆盖或删除历史才能重建 | schema v11 将 summary/tracking/aggregate/transition 业务槽改为普通索引；旧头先 stale，再插入新头 | v10→v11 无损迁移和新库测试均保留两代；四类槽各 1 VALID + 1 STALE |
| 并发重建在同一业务槽产生两个当前头 | fresh/open/migration 共用数据库触发器，在 INSERT/UPDATE 时检查同槽 `VALID` | 两协程争抢空 summary 槽恰好一个成功；第二个 VALID 在四类槽均失败 |
| 旧历史被恢复为 VALID、篡改或删除后失去审计链 | 七类派生历史只允许内容不变的 `VALID → STALE`；`IS NOT` 保护 NULL 字段；DELETE 全部拒绝 | STALE→VALID、内容/来源/NULL、时间倒退与七类 DELETE 负例通过 |
| 生产单行/批量查询混入 stale，或 `associateBy` 任意选择历史代 | authority 查询显式限定 VALID；tracking 还绑定 current chapter version；全历史使用独立 `*History*` API | summary/event/fact/timeline/tracking authority/history 双视图通过，Phase 2A 计划只使用有效 tracking 头 |
| 放开 transition 历史槽后误报伏笔 replay 已完成 | 继续保留 tracking 顺序保护；`foreshadow_item` checkpoint/rewind 和 aggregate writer 仍显式阻塞 | 文档与计划均保持 TEST-033 未完成；真实 Provider 0、物理设备写入 0 |
| transition 不含可见实体、重要度、目标窗口等完整状态，无法可靠 rewind | schema v12 为每条 transition 写唯一 `foreshadow_projection_revision`；共享 writer 从真实 post-CAS item 规范封存全部字段与 SHA-256 | snapshot 逐字段 round-trip；迁移/新库触发器、篡改/删除/恢复负例通过，双 API 定向各 20/20 |
| 两条提交路径各自补字段而漂移，或 transition 已提交但 revision 缺失 | tracking 与 final candidate 在同一 Room 事务内共用 `ForeshadowProjectionRevisionWriterV1`；任一读取、校验或插入失败整笔回滚 | tracking E2E 双 API 各 3/3，final commit 专项各 27/27，数据库全量各 155/155 |
| 旧 Stage replay 把 later-current 伏笔当旧 after-state，误失败或覆盖最新索引 | replay 以不可变 revision 校验旧 after-state；只有 current 仍逐字段等于旧 revision 时才补写对应伏笔索引，否则只修复不可变时间线 | current importance/索引章序后来改变后旧 final Stage replay 仍成功，并保留最新 importance 与章序 |
| v11 旧 transition 被迁移时猜测成完整快照 | v11→v12 只建空 revision 表，不从不完整 transition 反推 after-state；缺账在消费时失败关闭 | 迁移后旧 transition=1、revision=0；没有 legacy backfill 或静默可信入口 |
| 可变 `foreshadow_item` 无法证明编辑点前状态，直接改字段可能生成混合年代数据 | schema v13 rewind 只采用编辑点前、绑定当时 current 章节版本的最后一个 VALID 完整 revision；共享 verifier 验证 snapshot/hash/transition provenance，再以全字段 CAS 恢复 | A 经 PLANT→DEVELOP→RESOLVE 后编辑中间章，逐字段恢复为 PLANT 后基线；迁移与正式 Room 测试双 API 通过 |
| legacy transition 缺少完整 checkpoint 时凭当前状态猜测旧值 | 仅允许把区间第一条 `PLANT(null → PLANTED)` 解释为编辑前不存在；其他缺可信 revision 的操作整笔失败关闭 | legacy DEVELOP 场景抛错，item、revision、transition、FTS 和审计均保持原状 |
| 先失效 transition 会绕过 revision 依赖，或区间仍残留可被误读的 VALID 历史 | rewind 固定先 revision 后 transition，并在恢复 current 前断言受影响章节范围两类 VALID 计数均为 0；数据库触发器同时阻止反序 | 正向场景记录的 stale 计数准确，反序写入负例被拒绝，双 API 全量各 159/159 |
| 同一编辑计划重复执行改变状态或形成两份冲突审计 | rewind ID 与 plan/hash/range/time 全量绑定；相同 ID 精确 replay 零写入，`plan_hash` 唯一禁止另一 ID 抢占 | exact replay 返回 replayed；different ID/same plan 失败且审计仍只有一条 |
| current 投影已恢复但 FTS 仍指向区间内旧状态或错误章序 | 先删除所有受影响 item identity，只对可信基线使用其基线章节序号重新索引；区间新生 STALE item 不重建索引 | A 的搜索指针恢复为第 1 章，B 的指针为 0；authority lookup 与投影一致 |
| Phase 1 已将区间新生伏笔置为 STALE，rewind 又改写 `updatedAt` 造成伪历史 | 特殊 CAS 只在 item 仍需从有效状态转为 STALE 时写入；已经 STALE 的新生 item 保留原状态与时间 | 独立回归断言 Phase 1 stale 时间在 rewind 后逐值不变 |
| aggregate 直接复制上一代 JSON，把旧版本、坏数据或未来状态继续传播 | 上一章 aggregate 只作为顺序栅栏；每章从 current-version-bound 最新实体属性、活动伏笔和有效 tracking 权威重算有界 CURRENT_STATE | 规范写入测试排除旧/未来事件与正文；未来伏笔整笔失败且写入数为 0 |
| tracking 已换代但旧 aggregate 仍被计划当作完成 | aggregate provenance 绑定 projection/stage、memory/prior-foreshadow/output/payload 全套 hash；计划 v2 严格解码比对当前 tracking | 替换 tracking 代次后旧 aggregate 不再满足计划，旧证据不能 replay 成新头 |
| 同章重建覆盖历史或并发写出两个 VALID 聚合头 | 事务先把精确业务槽旧 VALID 头转 STALE，再插入确定性新代；数据库唯一 VALID 触发器与 constraint race 的 replay-only 重试共同保护 | 旧版本头保留为 STALE；两协程同证据恰好一个新写、一个 replay，最终只有一个 VALID |
| 畸形当前聚合被静默覆盖，掩盖数据损坏 | 计划严格解码 canonical JSON/hash/provenance；槽已占用但不匹配时显式 `DERIVED_VERSION_SLOT_OCCUPIED`，writer 只接受 READY | 畸形 VALID 头使计划 BLOCKED，writer 拒绝且原行不变 |
| 聚合无限收集全历史导致长篇 payload 膨胀或泄露正文/模型信息 | schema v1 只保存 256 个最新实体属性和 128 个活动伏笔，128 KiB 硬上限；明确排除正文、历史、Provider、Attempt、Usage 和提示词 | 双 API 规范 payload/上限/正文 canary 回归；统一源码与 APK 安全扫描通过 |
| 用会随合法进展变化的 `planHash` 作为持久执行 ID，崩溃后无法识别同一次重建 | schema v14 以实际 current 章节、rewind after-state 和 summary/tracking/aggregate 基线指纹计算 stable fence；`initialPlanHash` 只作诊断 | 精确 replay 只保留一条 execution；edited version、rewind 和 fence 三重唯一，冲突身份失败关闭 |
| rewind 已提交但后续准备失败，留下“投影已回退却无可恢复工作” | `ChapterEditRebuildExecutionRepository.prepare` 用一个外层 Room 事务包住 rewind、计划重验、基线冻结和 ledger 插入，并做写后精确回读 | 人为制造 rewind 后时间门禁失败，rewind/execution/step 均为 0，既有 summary 保留 |
| 为了省事提前创建所有后续 Stage，导致不可变 `inputSourcesJson` 只能写假引用 | v14 只持久化关键步骤和真实基线，不创建 Job/Stage；后续 Stage 必须在直接前驱真实结果落库后动态创建 | migration+ledger 定向双 API 各 9/9；Job/Stage/Attempt/Usage 计数全部为 0 |
| 重建 Stage 与 ledger 仅靠调用约定关联，崩溃或篡改后可能消费错误 execution | memory/tracking 使用严格 v2 `chapterEditRebuild` binding，并把 execution/fence/ordinal/type/章/来源版本与 hash 纳入 Stage input hash 和确定性 ID | factory 篡改、身份占用、精确 replay、current 范围变化和并发创建回归通过；普通 v1 Stage 保持兼容 |
| 只看到编辑章 summary 就跳过真实 memory 请求审计，直接创建 tracking | pending memory 必须具备绑定 Job COMPLETED、Stage SUCCEEDED、最新 Attempt SUCCEEDED、Usage FINAL、严格 output reference 和权威 memory 行；prepared-SATISFIED 则按全字段 fingerprint 复核 | Fake Provider E2E 实际完成绑定 memory 请求、解析、Attempt/Usage、commit 后才创建 tracking；双 API 各 4/4 |
| 为了重建中间章而全局删除 tracking 顺序保护，普通生成可越过后续已提交章 | 普通 source loader 继续失败；只有 stable-fence 重建授权通过后才调用专用 loader，并在 Provider-open 与 commit 重新复核 | 两章夹具中普通 guard 被拒绝、专用 ordinal 2 Stage 成功；范围变化零写入、并发只保留一份；数据库双 API 各 178/178 |
| tracking 已成功但 aggregate 失败，留下后续章节可消费的混合年代状态 | rebuild tracking 的业务写入与 aggregate writer 位于同一 Room 外层事务；FINAL Usage 和 Stage/Job 完成在 aggregate 成功之后 | 未来章活动伏笔使 aggregate 失败时，tracking/timeline/transition/aggregate 全部为 0，Stage=COMMITTING、Job=RUNNING、Usage=PROVISIONAL |
| 成功 replay 用变化后的 planHash 再写 aggregate，产生重复代次 | 首次提交调用 writer；SUCCEEDED replay 只要求当前 tracking/aggregate 均为 `ALREADY_SATISFIED`，不再次写入 | Fake Provider 正向闭环提交后精确 replay，tracking/transition/revision/FTS/aggregate 数量均不增加；双 API 各 5/5 |
| tracking Stage 创建后 aggregate 槽被其他执行占用，仍打开 Provider 浪费费用 | Stage 创建、Provider-open 和 commit 均复核 prepared aggregate step 与同章槽；只有成功 replay 允许严格匹配的 aggregate 已存在 | Stage 创建后插入意外 aggregate，Provider-open 失败且 Attempt/tracking 为 0；计划套件双 API 各 16/16 |
| 后续章节旧 tracking/timeline 先 stale，崩溃后没有可恢复 Stage，或 replay 无法证明精确退役集合 | schema v15 `chapter_edit_rebuild_tracking_retirement` 把 prepared tracking 指纹、精确 timeline ID/内容指纹、deterministic replacement Job/Stage 与时间绑定；退役、搜索删除、Stage 创建和 evidence 插入同事务 | 第二章正向+replay、双 worker 收敛、replacement 身份碰撞整笔回滚；计划套件双 API 各 19/19，数据库全量各 183/183 |
| 只靠当前 STALE 状态推断本次 execution，误把其他历史退役当成可继续依据 | retirement 主外键绑定 immutable execution/step 和准备时 baseline；唯一索引禁止 baseline/Job/Stage/章节被多次认领，写后复核 stale 指纹与 exact timeline set | v14→v15 双 API 迁移验证表、唯一索引、provenance/immutable/delete triggers；JVM evidence codec 3/3 |
| 把“第二章 replacement Stage 已创建”误报为后续区间已完成 | Phase 2B3B2D 只闭合 ordinal 4；Phase 2B3B2E 必须使用显式目标 ordinal 和直接前驱证据逐章推进，未通过 10 章场景前不关闭任务 | ordinal 6 正向/负例、10 章编辑第 3 章 ordinal 4–16 Fake Provider 双 API 通过；报告 105/106 分别记录中间与完成边界 |
| planner 把任意新 VALID tracking 误认成本次 execution 的重建结果 | 只授权 retirement 指向的 deterministic replacement Stage；projection 必须以 `generationStageId` 绑定该 Stage，并复核 Job/Stage/binding/source/旧退役集合 | 正向提交后 tracking/aggregate 均为 ALREADY；无 retirement 或 identity 不匹配仍保持槽占用阻塞 |
| 保留章节 aggregate 失败后同时丢失 retirement，导致旧 tracking 已 stale 但无法恢复 | retirement 是先前事务的不可变准备事实；Provider 提交事务只原子处理新 tracking/aggregate/Usage/Stage | 故障注入后 retirement=1、旧 tracking=STALE、新 tracking/timeline/aggregate=0、Stage=COMMITTING |
| 跳过中间保留章节或提前创建未来 Stage，使用并不存在的来源继续重建 | 显式偶数 target ordinal；ledger 推导目标；直接前驱 tracking+aggregate 必须确定性完成且时间不晚于当前创建；禁止自动猜 next | ordinal 6 前驱未完成和时间倒退均零写入拒绝；三章正向与 10 章 ordinal 4–16 顺序闭环双 API 通过 |
| 较后的 retirement evidence 掩盖较早缺口，使旧派生重新获得执行许可 | Provider-open/commit/planner 仅授权从 ordinal 4 开始的连续、章节递增、时间单调 evidence 前缀 | 通用 repository 前缀校验；旧 projection 精确 `STALE`、新 projection 精确 Stage identity，10 章 retirement=7 |
| 旧摘要或搜索指针在用户编辑后仍进入后续章节上下文 | 编辑事务原子 `VALID→STALE` 并删除对应 FTS；生产上下文选择器只 hydration current+VALID 来源 | `userEditedChapterContextSelectsOnlyTheReplacementSummary` 双 API：只返回新版本摘要，旧摘要 STALE、旧搜索行 0；TEST-033 完成 |
| 为了给 TASK-061 标记完成而给不可变 execution 增加可能漂移的 mutable 完成字段 | execution 只保存 PREPARED fence；完成性由权威 planner 重算，自动游标和恢复状态归 TASK-064 total runner | 10 章所有 tracking/aggregate 为 ALREADY；schema 保持 v15；797-task Release/R8 门禁通过 |

## 36. TASK-062 脱敏时序追踪（已完成）

| 风险/需求 | 实现证据 | 验证证据 |
|---|---|---|
| 只看 Job/Stage 墙上时间，无法定位十分钟慢章卡在哪一段 | phase+milestone 追加账本与报告器分别计算 queue/local/provider/首段/body/memory/tracking/consistency/revision/commit/total | 完整固定时间线逐项公式通过；phase 同名事件隔离回归通过 |
| 用户调系统时间或设备重启产生负耗时/虚假达标 | duration 只用 elapsedRealtime；每事件绑定 boot 指纹，epoch 只展示 | 跨 boot 与单调回退均返回明确 Unavailable，不输出猜测值 |
| 性能事件泄漏正文、人物、提示词、端点、密钥或原始 ID/hash | 表结构没有自由文本字段；关联、连接、模型、boot 全部域分离指纹；首段只保留有限状态与码点计数 | JVM/正式 Room/Fake 流三层 canary 0 命中；源码安全扫描门禁保留 |
| BODY/MEMORY/TRACKING 等 Stage 事件同名造成错误配对 | phase 进入 event ID、索引、触发器和 reporter group；Stage/Attempt 起止按同指纹配对 | 错 milestone-phase 直接 SQL 插入被拒；跨 phase 同事件 ID 不相等且报告仍精确 |
| 拒绝、断流、暂停或取消没有结束事件，被误报成缺数据 | BODY 终态覆盖 FAILED_CLOSED/UNKNOWN/NEEDS_ACTION/CANCELLED/TRUNCATED；无响应失败不伪造 FIRST_BYTE | Fake NOT_SENT 仅写 PROVIDER_OPENED+失败 BODY_STREAM_ENDED；迟到结算幂等 |
| 把测量底座误报成完整自动生成 | TASK-062 只接 BODY 执行器；total runner 负责其他 phase 发射，Fake 性能分布另归 TASK-063 | 文档、Backlog 和测试明确 TASK-063 已完成而 064/066 未完成；真实 Provider 0、物理设备写入 0 |

## 37. TASK-063 Fake 性能夹具追踪（已完成）

| 风险/需求 | 实现证据 | 验证证据 |
|---|---|---|
| 5 分钟场景真实等待，回归过慢且不稳定 | `VirtualFakeStreamClock` 只在可取消调度点后推进虚拟毫秒，不 sleep/忙等 | 301 秒虚拟慢流的 JVM 与双 API 集成均在秒级完成 |
| Fake adapter 偷补成功终态或未知结果后自动重发 | 无终态脚本按 EOF 原样结束；UNKNOWN/STREAM_INTERRUPTED 保留有限 request state | Room+加密草稿执行器进入 UNKNOWN，generateCalls=1 |
| 共享虚拟时钟让并发调用互相污染耗时 | stats 只累计本 collection 已完成的 Wait，不用全局开始/结束差 | 并发统计与确定性重放 JVM 回归；Sol 审查修复 DeepSeek 初稿 |
| 只报成功样本使 P95 虚假变快 | benchmark 同时保存 total/available/NotApplicable/各 unavailable reason | 失败、缺事件和跨 boot 不被丢弃；正式提交明确 20 个 MISSING_EVENT |
| 用短文本冒充普通参考章 | 双 API 测试实际生成 20 个 2,500～3,450 字 BODY 流 | 首段 P95 19.70 秒、正文 P95 174 秒；最慢 19.85/177 秒 |
| Fake 测试代码误进 Release | 新模块不被 app/feature implementation 引用；generation 仅 androidTestImplementation | Release 依赖边界审查与统一门禁 |

## 38. TASK-064 Phase 1A 持久恢复追踪（进行中）

| 风险/需求 | 实现证据 | 验证证据 |
|---|---|---|
| Job lease 领取后、Stage lease 领取前崩溃，原维护器永远扫不到而永久 RUNNING | Job→current Stage 有界 JOIN 只选过期 Job lease、Stage READY+无 lease；单事务 exact CAS 恢复 Job READY | timeout 临界、正向恢复与双 API 数据库全量各 197/197 |
| 维护器用旧扫描结果抢走已经开始的 Stage | re-read + SQL `EXISTS` 再证明 Stage READY 且三 lease 为空；匹配 Job owner/acquired/heartbeat/currentStage | 扫描后另一 executor 领取 Stage，恢复 stale-fail，Job/Stage 活跃租约保留 |
| 宽 status CAS 或伪造候选误恢复别人的 Job | 专用 CAS 匹配完整 lease token 和 heartbeat；候选 heartbeat 必须等于重读事实 | 篡改 heartbeat 回归零写入；双维护器仅一份成功 |
| 修恢复时改写 Stage/Attempt 造成重复请求 | Phase 1A 只清 Job lease并保留 currentStage；Stage、Attempt、attemptCount、错误和 retryAt 不变 | 正向/并发用例逐字段断言；真实 Provider 调用 0 |

## 39. TASK-064 Phase 1B runner queue 追踪（进行中）

| 风险/需求 | 实现证据 | 验证证据 |
|---|---|---|
| 两个 runner 同时领取同一 READY Job，产生重复请求 | 有界候选在单 Room 事务精确重读，复用 Job 状态 CAS；候选绑定 Job/currentStage/status/updatedAt | 双 runner 并发无 sleep，精确一个 claim 成功；双 API 定向各 64/64 |
| Stage 交接后重新领取 Job 或丢失 RUNNING 任务 | 原 Job token heartbeat 后读取最新 currentStage；业务 cursor 仍由 commit 事务推进 | Stage A→B 后 acquiredAt/owner 不变、heartbeat 前进且读到 B |
| 新进程仅凭相同 owner 收养旧租约 | API 必须传入精确 owner+acquiredAt token；没有按 owner 扫描 RUNNING Job 的入口 | 旧 token 在超时回收并由新 runner 领取后 stale-fail，新 token 正常续跑 |
| READY 坏行残留租约被队列覆盖 | DAO 要求 Job/Stage 三 lease 均空；claim 再复验；异常 projection 不静默跳过 | 人工残留完整 Job lease 的 READY 行不进入 scan；Stage lease/updatedAt 竞争零写入 |
| queue 泄露业务 payload 或获得提交权限 | projection 不读 book/target/input/intent/source/owner；结果字符串脱敏；仅改 Job lease | 日志 canary 断言无 Job/Stage/owner；Stage/Attempt/attemptCount 全字段不变 |

## 40. TASK-064 Phase 1C 原子执行租约追踪（进行中）

| 风险/需求 | 实现证据 | 验证证据 |
|---|---|---|
| 只凭 StageId 领取已非 current 或不属于 Job 的 READY Stage | acquire 先验证精确 Job token、RUNNING、currentStage、归属和 same-owner，再取得 Stage lease | 正向/错误 owner/currentStage 与双 API 69/69；不创建 Attempt |
| 先续 Job 后 Stage acquire 失败，留下部分 heartbeat | 两个 DAO 调用位于一个 `withTransaction`；第二步失败回滚第一步 | Stage updatedAt 超前使 acquire 第二步拒绝，Job heartbeat 保持领取值 |
| 两个执行器同时领取 current Stage | 共享 Job token 仍需 Stage READY CAS，Room 事务串行裁决 | 两协程无 sleep，精确一个成功；Stage 只有一个 token |
| Job/Stage heartbeat 分别成功导致维护事实不一致 | 双 heartbeat 共用外层事务并要求 same-owner/currentStage | 错 Stage token 时 Job heartbeat 回滚；错/混合 token 零写入 |
| 过期 Stage 或已推进 cursor 被旧执行器续活 | 正式 lease policy 与 currentStage 重验在每次 heartbeat 前执行 | 60,000ms 临界 Stage 过期、cursor A→B 后旧 S heartbeat 均 stale-fail |

## 41. TASK-064 Phase 1D heartbeat envelope 追踪（进行中）

| 风险/需求 | 实现证据 | 验证证据 |
|---|---|---|
| action 完成后迟到 heartbeat 把已提交结果误报失败 | select 等待 action/tick，action 先完成即取消 waiter | 首 tick 前完成零 heartbeat；多 tick 后完成不再调用 |
| 真正丢 lease 后 Provider/action 仍继续 | heartbeat 失败且权威 Job 仍是同 current Stage时取消 action | awaitCancellation action 的 finally 被执行，runner 收到 lease-lost |
| commit 已推进 cursor，旧 heartbeat stale 导致成功 action 被误取消 | 同 Job token + currentStage 已改变视为 durable handoff | A→B 检查后 action 未取消，随后返回 committed |
| Job 已完成/暂停/停止/需操作，旧 lease 清除被误判为抢占 | 有限终态/等待态 + lease null 视为 durable boundary | COMPLETED fixture 停止 beats并返回 action；mixed owner 在 action 前拒绝 |
| 测试真实等待 15 秒造成慢回归 | waiter/clock 依赖注入，手动 Channel tick | 新增 5 JVM 测试秒级完成，模块 125/125 |

## 42. TASK-064 Phase 2A 派生 route identity 追踪（进行中）

| 风险/需求 | 实现证据 | 验证证据 |
|---|---|---|
| `EXTRACT_MEMORY` 只按 phase 把 memory 当 tracking 或反向 | 先读有限 `sourcePolicyVersion`，再委托各自权威 parser；没有 phase-only fallback | 双向 policy/schema 互换、错误 phase/target/hash 均失败；正向 v1/v2 分流 |
| 伪造 schemaVersion=2 冒充编辑重建 | 完整 parse 后再读取正式 `chapterEditRebuild` binding | memory/tracking 合法 v2 各命中独立 route；未知 schema 与额外 root 拒绝 |
| candidate role 与 executor phase 接错 | 完整 candidate binding 后按 BODY/MEMORY/TRACKING/CONSISTENCY + phase 穷举 | 五种合法组合唯一命中；不兼容 role/phase 失败 |
| 损坏或未来 policy 被 generic route 继续执行 | 未知、缺失、非字符串、非 object 或畸形 JSON 直接抛错，不降级 | 11 个 resolver JVM 测试全部通过，异常断言不打印 payload |
| route 解析触发状态或联网副作用 | 纯 enum resolver，只读取 Stage entity；无 DAO/Provider/文件写入 | database JVM 81/81、双 API Android 各 209/209、安全扫描通过、Provider 0 |

## 43. TASK-064 Phase 2B current-lease route binding 追踪（进行中）

| 风险/需求 | 实现证据 | 验证证据 |
|---|---|---|
| 调用方用陈旧/伪造 Stage 取得合法 route | repository 在同一 Room 事务重读 current Job/Stage 后才解析；resolver 为 `internal` | 合法 memory route 与精确 lease snapshot 绑定；非 current Stage 失败 |
| 错 token、mixed owner 或租约超时后继续分发 | exact Job/Stage token、same owner、双 heartbeat 与 60 秒临界共同验证 | 错双 token、mixed owner、倒退时间与 60,000ms 临界全部拒绝 |
| PAUSING/STOPPING 或请求已记录后又开新 executor | 只允许 `RUNNING + PREPARING`，attempt 必须仍有额度 | PAUSING 故障注入与 `REQUEST_INTENT_RECORDED` fixture 均失败且状态不变 |
| 裸 route snapshot 被 feature 层手工构造 | 绑定快照是普通 class 且构造器 `internal`；route parser 也只在数据库模块可见 | Android/JVM 编译通过；跨模块只能消费 repository 返回值 |
| route 授权检查意外续租或创建 Attempt | 整个入口只读，无 DAO update/Provider 调用 | 前后 Job/Stage/Attempt 相等；JVM 81/81、双 API各214/214、安全扫描通过 |

## 44. TASK-064 Phase 2C2 final exact-token executor 追踪（进行中）

| 风险/需求 | 实现证据 | 验证证据 |
|---|---|---|
| owner 相同的新租约继承旧 route 授权 | `executeBound` 要求 persisted token 与调用方 token 完整相等 | same owner、acquiredAt 40→41 在 coordinator 前拒绝 |
| total runner 再 acquire READY Stage 覆盖 Phase 2B 身份 | bound 入口不调用 acquire，且 READY 失败关闭 | PREPARING/COMMITTING trace 只有 find+commit；READY 零 commit |
| 过期 token 进入 artifact recovery/commit | executor 在 coordinator 前使用正式 lease policy 检查 heartbeat | 60,000ms 临界失败，trace 无 commit |
| durable commit race 被误报失败并重复工作 | SUCCEEDED 直接返回 `AlreadySucceeded` | 无 lease、无 acquire、无 commit 的只读用例通过 |
| registry 绕过唯一 final executor | DEC-063 固定 registry 只能调用 `executeBound` | executor 定向12/12，generation JVM129/129、双 API各39/39 |

## 45. TASK-064 Phase 2C3 最小 registry 追踪（进行中）

| 风险/需求 | 实现证据 | 验证证据 |
|---|---|---|
| feature 层用裸 route 或伪造 Stage 绕过数据库事实 | registry 公开入口只接受构造器受限的 `GenerationRunnerCurrentStageRouteSnapshot` | real Room 创建绑定快照后才可进入 Android 集成用例 |
| final route 换用同 owner 新 token 或重新 acquire | 唯一分支把快照 exact Stage token 原样传给 `executeBound` | trace 只有 bound commit；token 完整相等；acquire 回调零调用 |
| 已识别 remote route 被 generic executor 意外发送 | 注册集合只有 final；九条 route 在 exhaustive `when` 中逐项 `notRegistered` | memory v1 集成用例在 executor/状态写入前失败；编译器保证枚举穷举 |
| 未注册错误泄露业务标识或正文 | 异常只保存有限 route enum | JVM 断言不含 Job/Stage/owner；结果字符串不含 Stage/owner fixture |
| 最小接线破坏全项目 Release/R8 或安全边界 | 不改 schema/DAO/Provider/Gradle；统一门禁复核 | generation JVM 131/131、双 API各41/41；801 tasks、5 APK 扫描和备份排除通过 |

## 46. TASK-064 Phase 2D1 candidate draft 合同审计（进行中）

| 风险/需求 | 实现证据 | 验证证据 |
|---|---|---|
| 请求前伪造尚未生成的 candidate version/hash | candidate binding 强制这两项，但 initial DRAFT 没有生产 factory | 生产 stageSetup 调用仅 3 处且都是 derived/revision successor |
| resolver 识别的 BODY+DRAFT 在 Provider-open 被另一层拒绝 | resolver 映射 BODY+DRAFT；source guard 明确 bound BODY 必须 REVISE | Sol 源码复核；registry 仍将该 route 显式 notRegistered |
| seal 与 final recovery 对初始根节点解释不一致 | seal DRAFT 分支不解析 source；recovery 要求 revisionIndex=0 inputSource null | 对应分支逐段复核，无生产 adapter 可跨越该缺口 |
| 测试手工闭环被误当生产链 | Android fixture 直接造 phase-only BODY Stage并手工 seal | `ReadyForValidation` 生产消费者只有 revision coordinator |
| 为接线放宽 guard造成错发/重复付费 | DEC-065 要求另建 request 前可得的 initial source contract | route 保持未注册；本审计 Provider 0、Git 差异 0 |

## 47. TASK-064 Phase 2D2 context route identity 追踪（进行中）

| 风险/需求 | 实现证据 | 验证证据 |
|---|---|---|
| context Stage 没有独立 route，dispatcher 无法识别本地前置步骤 | factory 只给 `ASSEMBLE_CONTEXT` 写独立 source policy；resolver 新增有限 route | factory/resolver JVM 正向与错误 policy/schema/phase/target/hash 负例 |
| repository 与 resolver 各自解析造成合同漂移 | 两者共用 `ChapterContextAssemblyJobFactory.parseAndVerify` | 数据库 JVM 86/86；双 API database 各214/214 |
| 损坏 progression evidence 或跨章节输入被识别为合法 route | parser 复算 evidence hash，并交叉验证 chapterId、chapterIndex 与 Stage/context | Sol 加固后定向 JVM 19/19、双 API context 各5/5 |
| 新 route 被枚举后意外获得执行权 | registry 白名单仍只有 final；context 在穷举分支显式未注册 | feature 正式/AndroidTest Kotlin 编译通过，0 Provider/Attempt/状态写入 |
| 错误字符串泄露冻结输入 | source `toString` 对 prompt/progression hash、预算和用户补充脱敏 | JVM 断言不包含 tokenizer 与 token 数值 |

## 48. TASK-064 Phase 2D3 context exact-token registry 追踪（进行中）

| 风险/需求 | 实现证据 | 验证证据 |
|---|---|---|
| route 解析后 Job token/cursor 变化仍提交 context | `assembleBound` 在业务提交同一 Room 事务内重读 Job/Stage 并复核 exact 双 token/current cursor | 错 Job/Stage token、PAUSING、cursor 改变均零 snapshot/Attempt 写入 |
| context adapter 复制旧业务逻辑并产生漂移 | 旧入口与 bound 入口共用唯一 `assembleInternal` | 原有5项 context 回归与4项 bound 用例合计双 API 各9/9 |
| 成功竞态重复插 snapshot 或再次推进 plan | SUCCEEDED 分支使用既有严格 durable replay，不执行 shared write path | replay 前后 snapshot、Job、context Stage、plan Stage完全相等 |
| registry 把 context 快照拆成 owner/stage 参数 | context executor 接口直接接收原始 `GenerationRunnerCurrentStageRouteSnapshot` | real Room registry 用例断言对象同一、时间原样、final executor 0次 |
| 本地 route 注册意外放开远程调用 | 白名单只增加 context；其余九条逐项 `notRegistered` | JVM注册集合严格2项；remote memory Android 用例零 executor/状态写入 |
| 新切片破坏发布和安全基线 | 无 schema/DAO/Provider/Attempt/Usage 变更 | JVM 86+131；双 API 218+42；801-task Release/R8与安全门禁通过 |

## 49. TASK-064 Phase 2E1 chapter-plan 合同审计（进行中）

| 风险/需求 | 实现/决策证据 | 验证证据 |
|---|---|---|
| plan Stage 只有 phase/schema 字符串，被 runner 当成可执行身份 | 工厂当前缺 source policy，resolver 因缺身份失败关闭；2E2 单独增加严格 source/parser/route | 限定源码追踪无 chapter-plan route；Provider/Attempt/Usage 0 |
| 普通 plan 误用 bootstrap 或 arc-window 合同 | 三者按请求前持久身份分离；普通 plan 不写 ChapterVersion/OutlineRevision | DeepSeek 与 Sol 逐段比较 factory、parser、commit 目标 |
| 没有严格输出合同却提交错误 schema | 当前无 `chapter-plan.v1` parser/业务 validator/commit，registry 保持关闭 | 全仓限定符号搜索只见常量和 context 引用 |
| 只保存成功 artifact，隔天继续时计划被清理 | DEC-068 将规范计划原子冻结进 initial DRAFT 输入；artifact 只作提交证据 | Sol 复核 `STREAM_DRAFT` 成功默认保留 24 小时及 64 KiB Stage 输入上限 |
| 新增表或误写 OutlineRevision 造成权威模型漂移 | 采用无 migration 的 DRAFT immutable input；窗口 outline 与章内 scene contract 分离 | 数据模型/现有 commit 模式复核；规范计划目标上限 48 KiB |
| 网络失败被误认为审计结论或代码贡献 | 首次运行记录为网络中断；仅采用第二次正常 final，并由 Sol 独立复核 | 两次 summary、stderr 与 Git status 指纹；工作树无新增改动 |

## 50. TASK-064 Phase 2E2 chapter-plan route identity（进行中）

| 风险/需求 | 实现证据 | 验证证据 |
|---|---|---|
| `BUILD_CHAPTER_PLAN`只凭phase误分发到bootstrap/arc/context | 独立`zhijuan.chapter-plan-source.v1`，resolver先policy后唯一严格parser | resolver plan正向与错误policy/phase负例；JVM全量90+131 |
| context依赖被替换或依赖数组夹带多项 | exact root；dependency恰好一项且等于contextAssemblyStageId；context input hash为64位hex | factory依赖空数组、不同context ID、坏hash负例 |
| progression被改写后仍获得route | 复算去掉evidenceHash后的规范对象hash，并核对targetId与chapterIndex>=1 | 重哈希错chapterId/0章序及坏evidence hash均拒绝 |
| route identity泄露持久ID/hash | 有限source `toString`隐藏context Stage/input/progression hash；route enum无payload | JVM断言不含实际Stage ID和hash |
| 新route一出现就被registry发送 | registry新增穷举`CHAPTER_PLAN_V1 → notRegistered`；registered set仍final+context | registry unit、双API generation各42/42、Provider0 |
| plan字段新增破坏context提交或发布构建 | repository继续按已知字段读取，不依赖旧exact root；无schema/DAO变化 | 双API database各218/218；801-task Release/R8/安全门禁通过 |

## 51. TASK-064 Phase 2E3 chapter-plan 输出合同（进行中）

| 风险/需求 | 实现证据 | 验证证据 |
|---|---|---|
| 模型输出超大、深层或夹带未知字段进入内存/提交 | `StructuredOutputLimits` 48 KiB、8层、4096节点、64数组项；exact schema/reader | 超限、重复key、未知字段、乱序负例；JVM 140/140 |
| 相同计划只因JSON字段顺序不同产生新hash | object key递归排序，scene/process数组保留顺序 | root字段反序与原输入 canonical JSON/content hash完全相同 |
| 模型改章节或使用陈旧ContextSnapshot | expectation核对chapterId/index与context内容/manifest两个hash | chapter identity漂移返回固定cross issue，零业务写入 |
| 输出引用未知人物或POV不在场 | knownCharacterIds白名单与POV∈participants交叉门禁 | unknown character + POV membership同次失败关闭 |
| Allowed相关章节被模型标成无相关场景以规避尺度 | Allowed要求至少一个`intimacyRelevant`场景；NotApplicable反向禁止自行增加 | 缺相关场景与意外相关场景均有固定issue |
| 严格场景用一个含糊节点、淡出或无余波冒充完整计划 | 每相关场景至少3个有序节点，逐节点六类状态，aftermath必填，全章≤64 | strict正向及节点不足/成年人门禁/余波缺失负例 |
| 比例模式伪造严格过程证明或Blocked静默降档 | 非严格相关场景禁止process nodes；Blocked expectation直接拒绝 | proportional forged nodes与Blocked构造负例 |
| 计划/人物/hash通过日志或异常泄露 | 领域对象和Invalid结果字符串只给计数/issue code并标记redacted | 正向/错误toString不含计划文本；安全扫描通过 |
| 输出合同一落地就触发真实生成 | registry未改，`CHAPTER_PLAN_V1`仍notRegistered | 双API generation各42/42；真实/Fake Provider 0 |

## 52. TASK-064 Phase 2E4A 目的地/预算前置审计（进行中）

| 风险/需求 | 实现/决策证据 | 验证证据 |
|---|---|---|
| 把 Job 预算 JSON 当成可扣余额，并发请求突破上限 | DEC-071 明确 snapshot 只读；TASK-083 建独立持久 reservation | `BudgetEngine` 生产调用为0；`recordRequestIntent` 当前无三层竞争 |
| 先扣预算后写 RequestIntent 或反向分两次提交 | reservation+RequestIntent+Attempt+Usage 必须同一 Room 事务 | 后续 TEST-071 需双协程只成功一方且失败方零写入 |
| 更换中转站 host/port/protocol 后沿用旧确认 | disclosure 绑定版本化 canonical destination+protocol，并在发送前动态核对 | 后续 TEST-090/091 覆盖未确认与变更失效 |
| base URL 大小写、默认端口和尾斜杠造成同目的地漂移 | 单独定义规范化 origin，不直接保存用户输入字符串作为授权身份 | 后续纯 JVM 规范化向量+Room round-trip |
| 可靠门禁导致每章弹确认 | 用户确认按目的地/书预算持久复用；runner 逐请求无交互复核 | 产品流保持首次或变更时一次确认 |
| 审计结论被误写成已接线 | registry仍只有final+context，plan显式未注册 | 本阶段0 Provider、0 schema/migration、0执行器变化 |

## 53. TASK-064 Phase 2E4B 目的地确认内核（进行中）

| 风险/需求 | 实现证据 | 验证证据 |
|---|---|---|
| 大小写/path/默认端口变化导致重复操作 | canonical origin忽略path并显式effective port，host/scheme小写且移除DNS尾点 | JVM同origin、HTTP/HTTPS默认端口、DNS尾点向量 |
| host/port/scheme/protocol改变仍沿用旧同意 | binding覆盖origin+protocol+version；读取按当前endpoint动态重算 | Room protocol/host变化、version bump均失败关闭 |
| 写确认期间endpoint变化导致旧同意落到新地址 | 接受事务UPDATE以connection/base URL/protocol为CAS条件并立即回读验证 | DAO写入计数必须为1；篡改后零放行 |
| 格式正确但伪造hash被当成合法 | stored hash先验格式，再与重算值常量时间比较 | 64个`0`合法hex篡改负例 |
| 新连接或连接测试静默接受小说发送 | App保存canonical destination但disclosure三字段恒null；连接测试无接受调用 | 未确认read失败；接受前后持久字段对比 |
| 对象字符串泄露endpoint/密钥尾号/hash | binding、evidence和`ConnectionProfileEntity`覆写脱敏toString | JVM/Android断言不包含host/hash/connection/secret tail |
| evidence被当作独立Provider permit | API/文档明确只读证据；registry未增加plan | 双API数据库222/222；真实/Fake Provider0 |

## 54. TASK-083 Phase 5B Provider-open 换日旧请求释放（进行中）

| 风险/需求 | 实现证据 | 验证证据 |
|---|---|---|
| 旧日 reservation 在新日继续发送而绕过 daily 上限 | claim 内从当前 DAILY head/revision zone 重算日键；不同则不签发 permit | 上海午夜前1ms同日、到点换日；双API专项35/35 |
| 跨日释放和 Provider-proof 混用，普通网络失败也清零 | 独立错误码、事件和 `releaseUnsentAttemptAfterDailyRollover`，没有通用 release boolean | 普通Phase4B回归保留；整库双API264/264 |
| 释放一半留下 Attempt/Usage/reservation/Stage/Job 分裂 | 单一外层Room事务、逐行CAS和五类写后回读 | 状态/时间/租约/聚合精确断言；并发最多一次提交 |
| attempt 上限被换日重置形成无限收费重试 | 旧Attempt计数不变；`attemptCount < maxAttempts`才READY，否则Stage/Job NEEDS_ACTION | maxAttempts=1负例，`retryAllowed=false` |
| 已发送请求被误判为未发送并释放 | permit evidence和专用事务都要求INTENT_RECORDED、无发送字段、UNKNOWN/PROVISIONAL Usage、RESERVED reservation | SENT负例保持reservation RESERVED且零换日写入 |
| 换日检查发生在草稿/Provider之后 | Executor先claim，成功后才open buffer和adapter | adapter调用0；受保护草稿revision/time/0字节不变 |
| 把“旧请求已结束”误写成“新日已重新预留” | 文档明确Phase5B只回READY；新Attempt/reservation/种子复制归Phase5C | registry仍未注册plan；TASK-083保持进行中 |

## 55. TASK-083 Phase 5C 新日替代请求准备（进行中）

| 风险/需求 | 实现证据 | 验证证据 |
|---|---|---|
| 调用方只凭父ID创建替代请求 | 专用API要求真实`GenerationRunnerExecutionLeaseSnapshot`，事务内重读最新父Attempt/Usage/reservation与当前Job/Stage | 错Job token负例零写入；双APIreservation各40/40 |
| 普通prepare绕过种子复制与双租约 | 最新Attempt为换日错误时普通reservation入口直接stale失败 | feature集成测试证明临时新工件已删除、数据库无新Attempt |
| 复用旧Attempt、reservation、attemptNo或草稿引用 | 新身份全部唯一；`attemptNo=parent+1`、`retryParent=old`、新artifact不可与父相同 | 正向逐字段断言；空/非空种子均产生不同引用 |
| 跨日把单书预算一并清零 | 新reservation重新进入book聚合，只进入新daily key | 旧日50+新日100时book=150、旧daily=50、新daily=100 |
| 新日策略不足却留下半请求 | candidate与Attempt/Usage/Stage在同一Room事务；上层删除新artifact | DAILY拒绝后新三行不存在，Stage仍PREPARING且旧release保持 |
| 明文种子落盘或复制后旧草稿被改写 | 有界ByteArray在lease内复制并清零；只创建新受保护artifact，旧descriptor复核 | 非空内容逐字节相同；旧descriptor/content不变；无明文临时文件路径 |
| 并发worker双重预留 | 最新Attempt、attemptCount、Stage状态与精确双lease同事务重验 | 两个并发专用prepare只有一个成功，只有一个新RESERVED行 |
| 把repository能力误报为总runner已接通 | 文档明确未注册Phase5C总路由，Provider-open不在本阶段执行 | adapter调用0；plan registry仍未注册；TASK-083保持进行中 |

## 56. TASK-083 Phase 5D Provider-open 实际目的地匹配（完成）

- 需求：FR-011、FR-012、FR-013、NFR-003、NFR-006、DEC-071、DEC-073。
- 代码：`ProviderOpenDestinationEvidence`、`GenerationRequestAuditRepository`、`GenerationStreamingDraftRepository`、`AuditedStreamingProviderExecutor`。
- 数据边界：不新增schema；实际connection/canonical origin/protocol/current disclosure与reservation冻结证据在Provider-open事务内动态比较，claimed send继续携带同一脱敏证据。
- 失败边界：错误目的地或协议在heartbeat、跨日release、受保护草稿打开和adapter调用前失败，Attempt/Usage/reservation/Stage/Job零写入，permit可在修复后重试。
- 证据：双API reservation各43/43、executor各23/23；数据库模块各272/272、generation各48/48；801-task统一离线门禁、Release/R8、安全扫描与备份排除通过。
- 结论：TASK-083在持久预算与实际发送目的地门禁边界完成；total runner、plan执行与App可用闭环继续由TASK-064承担。

## 57. TASK-064 Phase 2E5A chapter-plan exact-token 请求准备（进行中）

- 需求：FR-011、FR-012、NFR-003、NFR-005、NFR-006、DEC-071、DEC-073、DEC-074。
- 代码：`GenerationRequestAuditRepository.persistBoundChapterPlanBeforeSend`、`GenerationStreamingDraftRepository.prepareBoundChapterPlanBeforeSend`及bound换日入口。
- 授权边界：只接受Room签发的`CHAPTER_PLAN_V1` snapshot；同事务复核exact Job+Stage lease、current cursor、heartbeat、route和attempt范围后才创建v1预算请求事实。
- 旁路边界：普通`BUILD_CHAPTER_PLAN`使用generic prepare会失败；损坏plan来源也不能借解析失败回落generic；首章bootstrap保持兼容。
- 工件边界：公开streaming负例证明被拒绝的新加密草稿已删除；正例证明成功Attempt唯一引用bound工件。
- 证据：双API reservation各47/47、数据库各276/276、generation各48/48；801-task统一离线门禁、Release/R8、安全扫描和备份排除通过。
- 未完成：request factory、权威expectation冻结、Fake streaming、严格响应提交、initial DRAFT与registry仍属后续Phase 2E5。

## 58. 2026-08-11 开发路线重排追踪

| 新需求/风险 | 设计与任务 | 验证 |
|---|---|---|
| 现有底层多但用户无法开始生成 | 27 号总规划；TASK-128 total runner；TASK-130 启动入口 | VS-TEST-001、003、004 |
| 固定分类不能支持混合小说 | 28 号 BookCapabilityManifest、ChapterCapabilityActivation；TASK-122 | 组合能力本地矩阵、VS-B |
| 不写系统类时仍携带系统负担 | 未激活能力零 Prompt/状态；TASK-121/122 | promptSelectivity=0、确定性编译测试 |
| 关键剧情、关系、道具或升级丢失 | NarrativeObligation、StoryStateDelta；TASK-123/127 | VS-TEST-005、R4/R5 |
| 每章多个分析调用导致十分钟一章 | chapter-post-analysis.v1；DEC-079；TASK-127 | remoteCallsPerChapter、R3/R5 时序 |
| 全书骨架过大导致模型难产 | arc-window v2 只规划 1–8 章并滚动补窗；TASK-124 | 80/300/10,000 章目标不产生全书逐章请求 |
| 第三方 skill 直接进入 App 的安全与维护风险 | WritingPolicyPack；DEC-077；TASK-121 | 来源/许可证、未知 pack 失败、无脚本执行 |
| 真实 API 拖到最后才暴露问题 | 31 号分级 R0～R5；TASK-132/133 | DeepSeek V4 Flash 脱敏报告 |
| 代码存在但书架/阅读/模板无生产调用 | TASK-131、134 | VS-TEST-008、模板三步重开验收 |
| 旧计划与新顺序冲突 | 27/30 号文档权威；DEC-076 | CURRENT-CONTEXT、README、Git 提交 |
