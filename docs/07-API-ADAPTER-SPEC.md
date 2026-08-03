# 织卷模型 API 适配规格

## 1. 目标

业务生成引擎只依赖统一接口，不直接依赖厂商 SDK。适配层负责请求格式、鉴权、流式事件、usage、结束原因、错误和能力差异。中转站“声称兼容”不等于所有字段兼容，必须通过能力探测和保守降级处理。

## 2. 首发协议族

| 协议 ID | 服务 | 首发 | 流式 | 备注 |
|---|---|---:|---|---|
| `OPENAI_RESPONSES` | OpenAI Responses | P0 | SSE | 支持事件型流和结构化输出能力探测 |
| `OPENAI_CHAT_COMPAT` | OpenAI Chat Completions 兼容 | P0 | SSE/非流 | 大多数中转站首选兼容面 |
| `ANTHROPIC_MESSAGES` | Anthropic Messages | P0 | SSE | 必须带 `anthropic-version` |
| `GEMINI_GENERATE_CONTENT` | Gemini generateContent | P0 | 流式/非流 | 保留未来 Interactions 适配空间 |
| `OLLAMA_NATIVE` | Ollama `/api/chat` | P1 | NDJSON | 原生流不是 SSE |
| `OLLAMA_OPENAI_COMPAT` | Ollama OpenAI 兼容 | P1 | 依实际能力 | Responses 只按无状态方式使用 |

## 3. 统一领域接口

逻辑接口：

```text
ProviderAdapter
  testConnection(profile) -> ConnectionTestResult
  listModels(profile) -> ModelListResult
  getCapabilities(profile, modelId) -> CapabilitySnapshot
  generate(request) -> Flow<NormalizedEvent>
  cancel(requestId)
```

统一请求 `GenerationRequest` 至少包含：

- `requestId`、`generationId`、`stageId`、`attemptId`；
- system/data/user 消息的已分层表示；
- 模型 ID、温度、topP、maxOutputTokens 等可选参数；
- 结构化输出 schema（可选）；
- stream 标记；
- 超时策略；
- 幂等键（仅提供方支持时发送，不能假设有效）；
- 不含可日志化的原始密钥。

## 4. 统一流事件

```text
Started(providerRequestId?)
TextDelta(text)
StructuredDelta(fragment)
UsageUpdate(inputTokens?, outputTokens?, cachedTokens?, raw)
Finish(reason, providerStatus)
Refusal(category?, message)
Error(standardError)
Heartbeat
```

解析器要求：

- 未知事件必须忽略并记低敏诊断，不能让整个 App 崩溃；
- 一个网络响应只能产生一个终态 `Finish/Refusal/Error`；
- 收到终态后忽略迟到增量；
- UTF-8 多字节和 JSON 必须支持跨网络分片；
- SSE 以事件边界解析，不能按单行随意拆 JSON；
- NDJSON 允许最后一行无换行符，但必须是完整 JSON；
- 流式回调写入缓冲并节流刷新 UI。

## 5. 提供方差异

### 5.1 OpenAI Responses

- 按官方 Responses 流事件解析文本增量、内容块、完成状态、错误和 usage。
- 不能假设所有中转站都支持 Responses；探测失败后仅在用户同意或预设允许时改用 Chat Completions 兼容协议。
- 结构化输出能力由探测/登记表决定。
- 不依赖服务端保存的 conversation/previous response 构建小说记忆；织卷使用自己的本地状态。

### 5.2 OpenAI Chat 兼容

- 最小兼容字段：`model`、`messages`、`stream`。
- `response_format`、`stream_options.include_usage`、`seed`、`reasoning_effort` 等为可选能力；未经验证不发送。
- 兼容站返回非标准 `data: [DONE]`、usage 位于不同位置等情况通过夹具覆盖，但不使用宽松解析吞掉真实错误。

### 5.3 Anthropic Messages

- 保存并发送明确的 `anthropic-version`，升级版本必须经过适配回归。
- 解析 `message_start → content_block_start/delta/stop → message_delta → message_stop`，容忍 ping 和未来未知事件。
- API Key 使用该协议要求的请求头；不得在跨主机重定向中转发。
- 对明确 overload、rate limit、authentication、invalid request、policy 等做标准化映射。

### 5.4 Gemini generateContent

- 原生协议单独适配内容、system instruction、候选、finish reason、安全反馈和 usage metadata。
- 不把 Gemini 原生地址伪装成 OpenAI 兼容，除非用户明确选择兼容端点。
- Google 当前还提供新的 Interactions API 方向；首发仍可实现成熟的 generateContent，但协议版本必须隔离，方便后续新增适配器。

### 5.5 Ollama

- 原生 `/api/chat` 的流为 NDJSON，单独解析。
- OpenAI 兼容 Responses 只按无状态方式使用，不依赖 `previous_response_id` 或 `conversation`。
- 局域网 HTTP 需要用户明确添加目标主机，仅对该地址放行，不开启全局明文流量。
- 本地模型能力未知较多，必须允许手填上下文和最大输出的保守值。

## 6. 连接配置

`ConnectionProfile` 包含：

- 用户名称；
- 协议 ID；
- 规范化基础地址；
- 密钥引用 ID，而不是密钥本身；
- 自定义非敏感头与敏感头的独立加密引用；
- 模型列表缓存及获取时间；
- 当前模型；
- 连接测试结果和时间；
- 网络安全策略；
- 能力快照版本。

基础地址处理：

- 去除多余末尾 `/`；
- 禁止把用户输入直接与任意相对路径拼接后跨域；
- URL 必须解析为明确 scheme/host/port/path；
- 远程地址默认必须为 HTTPS；
- 地址变更后旧能力缓存失效；
- 不在地址 query 中接受 API 密钥。

## 7. “自动测试”的步骤

1. 本地校验 URL、协议和必填字段；
2. DNS/TLS/连接测试（不把详细底层信息暴露在主界面）；
3. 默认只请求一次模型列表，验证鉴权并取得可选模型，不发送生成请求；
4. 已选模型必须确实存在于成功返回的列表；只有列表端点被服务端明确拒绝为不存在/不兼容时，才允许手填并标记“尚未验证”；
5. 可选“完整验证”必须先明确提示可能产生极少费用，再发送一次固定通用生成探针，输出硬上限为 16 token；
6. 只有实际观察到的流式、usage 和输出上限字段才能形成成功探测证据；未观察到的能力保持 `UNKNOWN`；本步骤不试探结构化输出、采样、推理或幂等字段；
7. 模型列表和可选探针共享 60 秒总时限，保存带时间和来源的能力证据。

默认测试不产生生成费用。完整验证不得使用任何长篇内容、人物、设定、模板或正文；错误密钥和明确远端失败原样返回，0 次自动重试。

## 8. 能力登记表

每个 `(connectionId, modelId, protocolId)` 保存：

- `supportsStreaming`；
- `streamFormat`：SSE/NDJSON/none；
- `supportsStructuredOutput`；
- `supportsUsageInStream`；
- `supportsSystemInstruction`；
- `supportsSeed`；
- `contextLimit`、`maxOutputTokens`及来源；
- `tokenizerFamily`（可未知）；
- `requestFieldsAllowed/blocked`；
- `capabilitySource`：内置、官方元数据、自动探测、用户覆盖；
- `verifiedAt`、`expiresAt`、`adapterVersion`。

优先级：用户显式覆盖 > 最近成功探测 > 随 App 发布的已验证表 > 保守未知默认。用户覆盖要显示风险，可一键恢复自动值。

## 9. 用量标准化

统一：

- 输入 token；
- 输出 token；
- 缓存读取/写入 token（若有）；
- reasoning token（若有且提供）；
- 原始 usage JSON 的受限、脱敏副本；
- `usageQuality`：`EXACT / PROVIDER_REPORTED / ESTIMATED / UNKNOWN`。

只有提供方明确返回的用量才称“服务方报告”；客户端 tokenizer 推算均为估计。最终账单始终以服务商为准。

## 10. 超时、取消和重试

- 连接超时、首字节超时、流空闲超时和总阶段超时分别配置。
- 用户取消先把父 Job 控制持久为 `PAUSING/STOPPING`，再调用适配器取消并停止读取流；完成加密草稿检查点后，Attempt 进入 `CANCELLED`、Usage 进入 `FINAL`，Stage/Job 才到安全状态。是否已产生服务费无法撤销，未知用量保持 UNKNOWN 而不是 0。
- 仅重试：短时网络错误、明确 429/可重试 5xx、流在任何正文前中断。
- 不自动重试：鉴权、无余额、无模型、格式配置错误、策略拒绝、预算上限、结果未知且可能已完成。
- 重试采用指数退避和随机抖动，受最大次数、总时长和预算三重限制。
- 提供方返回 `Retry-After` 时在安全范围内优先遵循。

## 11. 重定向与密钥

- 默认禁止自动跨 host 重定向。
- 同 host 的 HTTPS 重定向可接受；scheme 降级到 HTTP 一律拒绝。
- 任何跨 host 重定向必须去掉 Authorization、API key、自定义敏感头；若业务确实需要，要求用户把最终地址保存为新连接并重新测试。
- 日志中 URL 默认去除 query 和 fragment，并对主机名提供可选隐藏。

### M0.9 可执行结论

- `:core:network` 使用 OkHttp 5.3.0，关闭自动重定向、HTTPS 重定向跟随和连接失败自动重试；付费生成请求不交给 HTTP 客户端隐式重发。
- 远程 base URL 必须为 HTTPS，且禁止 userinfo、query 和 fragment；query 只允许由经过审查的适配器在具体请求上添加，密钥不放 query。
- 同 origin（scheme + host + port）的 HTTPS `GET/HEAD` 最多有限跟随；`POST` 等可能创建付费工作的请求不自动跟随任何 3xx。
- 跨 origin、HTTPS→HTTP、带嵌入凭据的目标、循环和超限重定向全部在第二次请求前拒绝。跨 origin 不采用“剥离后继续”作为默认行为，而是要求用户保存最终地址并重新测试，因此目标端不会收到请求或秘密。
- TLS 分类遍历主因和 suppressed 异常，避免 OkHttp 多路连接把证书失败包在 `ConnectException` 时被误报为普通断网。
- 当前 App 的 Android Network Security Config 全局禁止明文，API 35 设备测试通过。局域网 Ollama HTTP 尚未启用；不得为了它把全局 cleartext 打开，留待 ADR-006 决定窄范围方案。

## 12. 标准错误

| 标准码 | 例子 | 自动重试 |
|---|---|---:|
| `NETWORK_OFFLINE` | 无网络 | 等待网络，不计重试次数 |
| `DNS_FAILED` | 域名解析失败 | 有限 |
| `TLS_FAILED` | 证书/握手错误 | 否 |
| `AUTH_FAILED` | 密钥错误 | 否 |
| `MODEL_NOT_FOUND` | 模型 ID 不存在 | 否 |
| `PROTOCOL_MISMATCH` | 返回格式不兼容 | 否 |
| `RATE_LIMITED` | 429 | 有限 |
| `QUOTA_EXHAUSTED` | 余额/配额不足 | 否 |
| `SERVER_OVERLOADED` | 可重试 5xx | 有限 |
| `POLICY_REFUSAL` | 内容/安全拒绝 | 否 |
| `CONTEXT_TOO_LARGE` | 输入超上下文 | 缩减后一次 |
| `OUTPUT_TRUNCATED` | 输出达到限制 | 走续接策略 |
| `FORMAT_INVALID` | 结构化输出无效 | 格式修复一次 |
| `STREAM_INTERRUPTED` | 流中断 | 取决于是否已有结果和费用风险 |
| `BUDGET_EXCEEDED` | 应用硬上限 | 否 |
| `UNKNOWN_RESULT` | 服务端结果无法确认 | 必须用户确认 |
| `CREDENTIAL_UNAVAILABLE` | 本地 Keystore 锁定、失效或记录损坏 | 否，请用户解锁或重新保存 |

## 13. 适配器验收

- 每个协议具有成功、分片、UTF-8、未知事件、usage 缺失、拒绝、429、5xx、截断和断流夹具。
- 同一业务请求在不同协议产出等价标准事件。
- 未识别字段不导致崩溃；未识别终态不得假装成功。
- 在所有导出、日志和崩溃信息中检索不到测试密钥。
- 跨 host 302 测试确认密钥未被发送。

## 14. TASK-020 可执行领域契约

`:provider:common` 已把本规格的供应商无关部分实现为可编译契约：

- `ProviderConnectionProfile` 只保存 Keystore secret 引用，不保存 API 密钥；远程端点只允许 HTTPS，显式确认的本机/局域网字面地址才允许 HTTP，userinfo、query 和 fragment 一律拒绝；
- `ProviderCapabilitySnapshot` 对每个可选能力使用 `SUPPORTED / UNSUPPORTED / UNKNOWN` 三态；只有明确 `SUPPORTED` 才允许发送对应字段，未知不会被乐观放行；
- 能力快照必须同时匹配当前协议和模型，禁止把另一连接或模型的探测结果误用于当前请求；
- `GenerationRequest` 固化 request/generation/stage/attempt 标识、分层提示、可选参数、结构化 schema、超时与幂等键，但不含原始密钥；
- 提示正文、结构化 schema、模型 ID、远端请求 ID 和 base URL 使用防泄漏值对象，默认 `toString()` 不输出原值；
- 标准事件为 `Started / TextDelta / StructuredDelta / UsageUpdate / Completed / Refused / Failed / Heartbeat`；`ProviderEventGate` 保证最多一个终态，忽略终态后的迟到增量，并把无终态 EOF 转成 `STREAM_INTERRUPTED`；
- 用量区分服务方报告、估算和未知；未知状态不得夹带数字，明显不可能的总量会被拒绝；
- `ProviderAdapterRegistry` 每种协议只允许注册一个版本有效的实现，假 adapter 可以经同一接口完成连接测试、模型列表、能力、流式生成和取消。

本轮 8 项 JVM 契约测试通过。这里尚未实现任何真实服务商 JSON/HTTP 映射；官方协议夹具、Secret Store 取用、安全 OkHttp 接线和真实连接测试分别由 TASK-021~029 完成。

## 15. TASK-021 唯一安全传输出口

新增 `:provider:transport` 作为协议适配器到网络/密钥/诊断底座之间的唯一组装层：

- 适配器只能提供逐段路径和已声明为公开的 query/header；不能传入任意绝对请求 URL，不能用 `..`、斜杠或编码技巧逃离连接 base URL；
- `key / api_key / api-key / token / secret / credential / auth` 等敏感 query 名称在请求创建前拒绝；`Authorization`、API key、cookie 和连接声明的敏感头不能从公开 header 通道写入；
- 主密钥只允许注入 `Authorization / x-api-key / api-key / x-goog-api-key`，并明确选择 raw 或 Bearer；自定义敏感头按连接中的 Secret Store 引用注入；
- Android 生产绑定在发请求时才读取 `AndroidSecretStore` lease，用完即关闭并清零；请求正文由可关闭的 byte buffer 承载，字符/字节输入和发送后的内部 buffer 都会清零；
- OkHttp 仍关闭自动重定向和连接重试；所有付费 POST 的 3xx 均拒绝，同源 GET 才能有限跳转，跨 origin 目标不会收到密钥；
- 连接、首字节、流空闲和总阶段时限分别落地；跨多次 GET 重定向时总时限按剩余时间递减，不会每跳重新获得完整预算；
- 活动 requestId 唯一；重复打开返回 `AlreadyActive`，不会替换原 Call；取消为幂等状态，并可在等待响应头或读取流时关闭网络；
- 响应体既检查声明长度，也用流式计数器执行 256 MiB 以内的调用方硬上限；超限为 `PROTOCOL_MISMATCH`，不能继续吞入内存；
- 返回给适配器的 response lease 不暴露 request URL、请求密钥或任意响应头；只允许读取协议适配所需的受限响应头，`Set-Cookie` 等不提供；
- 网络诊断只记录开始、响应打开、失败和取消，连接/endpoint/requestId 进入加密诊断前先哈希；异常 message、请求体、响应体和 header 值不进入记录。

本轮 8 项 JVM 假服务器测试通过；真实 Android Keystore + HTTPS 假服务器 + 加密诊断集成在 API 35 和 API 30 各 1/1 通过。尚未映射任何服务商 JSON，也未消耗真实 API。

## 16. TASK-023 OpenAI Chat Compatible 可执行实现

新增 `:provider:openai-chat`，通过 `OpenAiChatCompatibilityResolver` 为每个连接明确选择兼容模式，不根据域名猜测：

| 模式 | 必发字段 | 经验证的可选字段 | 结构化输出 |
|---|---|---|---|
| `OPENAI` | `model/messages/stream` | `stream_options.include_usage`、`temperature`、`top_p`、`max_completion_tokens`、`seed`、`reasoning_effort` | `json_schema` + `strict=true` |
| `DEEPSEEK` | `model/messages/stream` | `stream_options.include_usage`、`temperature`、`top_p`、`max_tokens`、`reasoning_effort` | `json_object`，并在 system 消息中加入 JSON schema 约束 |
| `RELAY_MINIMAL` | `model/messages/stream` | 无；所有未知高级字段失败关闭 | 不发送 `response_format` |

实现边界：

- 请求只通过 `SecureProviderHttpTransport` 发送到逐段构造的 `/chat/completions` 或 `/models`；鉴权只引用 Secret Store 中的 Bearer secret，适配器不接收原始密钥；
- OpenAI 使用当前 `max_completion_tokens`，DeepSeek 使用 `max_tokens`；DeepSeek `MEDIUM` 按其官方兼容规则映射为 `high`；
- 通用中转模式把固定优先级提示合并为单个 user 消息，只发送最小兼容字段，避免“兼容 OpenAI”却拒绝高级字段；
- 请求 JSON 由可清零 UTF-8 byte buffer 增量编码，正确处理引号、换行、中文和代理对；结构化 schema 在联网前验证为 JSON object；
- 流按 SSE 事件边界解析，容忍任意 UTF-8 字节分片；忽略 DeepSeek 的 `reasoning_content` 和 OpenAI 的 `obfuscation`，不把推理过程写入小说正文；
- `finish_reason` 在流中只暂存，直到 `[DONE]` 才产生单一终态，以接收可能位于终止前最后一个空 `choices` chunk 中的 usage；缺失 `[DONE]` 一律视为 `STREAM_INTERRUPTED`；
- 请求流式但中转站返回标准 JSON 时允许安全降级解析；其他 Content-Type/协议不匹配不使用宽松猜测；
- 拒绝文本最多保留 32,768 字符；远端错误 message 不进入标准事件、日志或测试失败输出；
- `GET /models` 仅证明鉴权与列表可读，`testConnection` 明确返回“尚未验证最小生成、尚未观察 usage”。会产生极少费用的完整连接探测现由 TASK-029 的统一验证器执行；
- 生成响应上限 64 MiB，模型列表上限 2 MiB；本地取消继续复用 TASK-021 的幂等 requestId 取消通道。

固定夹具覆盖 OpenAI、DeepSeek 和最小中转三种请求/响应，包含非流 JSON、SSE、usage-before-DONE、推理字段、拒绝、未知字段、缺失终止、429、402、非法 schema 和模型列表。9 项 JVM 测试、API 35 与 API 30 各 1 项 HTTPS/两字节传输集成测试通过；未调用真实服务、未使用真实密钥。

## 17. TASK-022 OpenAI Responses 可执行实现

新增 `:provider:openai-responses`，实现官方 `POST /responses` 与事件型 SSE，不借用 Chat Completions 的 `[DONE]` 语义：

- 分层提示映射为 Responses `input` 消息：应用硬规则为 `system`，阶段契约、故事状态和写作资料为 `developer`，用户本次要求为 `user`；层次不扁平化；
- 每个生成请求显式发送 `store:false`，且不发送 `conversation` 或 `previous_response_id`。服务端状态不是织卷的小说记忆来源；
- 结构化输出使用 `text.format={type:json_schema,name,strict,schema}`；`max_output_tokens` 是协议基线，`reasoning.effort`、`temperature`、`top_p` 和结构化输出默认标记为模型能力未知，只有后续登记/探测明确支持才发送；Responses 创建协议未定义的 `seed` 与幂等键在联网前拒绝；
- 正文只接收 `response.output_text.delta/done`，拒绝只接收 `response.refusal.delta/done`；新增的未知事件类型可忽略，但未知终态不能冒充成功；
- `response.completed` 是正常提交证据；`response.incomplete` 的 `max_output_tokens` 映射为 `Completed(LENGTH)`，`content_filter` 映射为 `Refused(SAFETY)`；`response.failed` 和 `error` 映射为标准失败；
- EOF 前未收到语义终态一律为 `STREAM_INTERRUPTED`，即使已有正文；存在的 `sequence_number` 必须严格递增，重复或倒序事件失败关闭，避免正文重复；
- usage 映射输入、输出、缓存读取、缓存写入、推理和总 token，并保证 usage 早于唯一终态；服务商 error message 不跨过适配器边界；
- 流式请求遇到标准 JSON 成功响应可安全解析；其他 Content-Type 冲突、非法 schema、畸形 usage 和事件名/type 冲突均失败关闭；
- `GET /models` 仅用于无付费的基础鉴权/列表测试，完整低成本生成探测由 TASK-029 的统一验证器执行。

9 项 JVM 固定夹具、API 35 与 API 30 各 1 项本地 HTTPS/两字节 UTF-8 传输集成测试通过；模型未知高级字段的联网前拒绝也在夹具内；未调用真实 API，未读取聊天中提供的密钥。

## 18. TASK-024 Anthropic Messages 可执行实现

新增 `:provider:anthropic`，直连 `POST /messages` 与 `GET /models`：

- 每个请求固定发送 `anthropic-version: 2023-06-01`；主密钥通过 Secret Store 以原始 `x-api-key` 注入，不能从公开 header 通道传入；
- Anthropic 要求 `max_tokens`，织卷不静默猜默认值：请求缺少 `maxOutputTokens` 时联网前失败；
- 非用户提示进入顶层 `system`，用户要求进入 `messages[user]`；如果请求没有显式用户要求，则把必有的阶段契约作为 user turn，避免发送空 messages；
- 流状态机严格按 `message_start → content_block_start/delta/stop → message_delta → message_stop`；`ping` 映射为心跳，未知未来事件忽略；
- 只有 text block 的 `text_delta` 进入正文；thinking、redacted thinking、signature 和 tool input 不进入小说正文；
- `message_delta.usage` 按官方累计语义输出更新，映射输入、输出、缓存创建、缓存读取和 thinking token；
- `end_turn/stop_sequence`、`max_tokens`、`tool_use`、`refusal`、`model_context_window_exceeded` 分别映射为 STOP、LENGTH、TOOL_CALL、Refused、CONTEXT_TOO_LARGE；`pause_turn` 和未知新增 stop reason 失败保守，不当作成功章节；
- `message_stop` 是流式提交证据；缺失它、内容块未关闭、事件名/type 冲突或 block 生命周期非法均失败关闭；
- 结构化输出使用 `output_config.format.json_schema`，推理强度使用 `output_config.effort`；两者和采样参数均默认模型能力未知，只在能力登记明确支持时发送；
- 非流 JSON 与流式请求收到 JSON 的安全降级均受同一终态、usage 和错误规则约束。

9 项 JVM 固定夹具、API 35 与 API 30 各 1 项本地 HTTPS/两字节 UTF-8 集成测试通过；没有调用真实 Anthropic API。

## 19. TASK-025 Gemini GenerateContent 可执行实现

新增 `:provider:gemini`，实现当前 `v1beta` 原生 `generateContent` 与 `streamGenerateContent?alt=sse`，并与未来 Interactions API 保持模块隔离：

- 模型 ID 只进入经过分段校验的 `models/{model}:generateContent` 路径；流式端点使用公开的 `alt=sse`，API key 只从 Secret Store 注入 `x-goog-api-key`，永不进入 query；
- 每次生成显式发送 `store:false`；应用硬规则、阶段契约和长期小说资料进入 `systemInstruction`，本次用户要求进入 `contents[user]`；没有独立用户要求时，以必有的阶段契约构造 user turn；
- generation config 支持 `maxOutputTokens`，模型级确认后可发送 temperature、topP、seed、`thinkingConfig.thinkingLevel` 和当前 `responseFormat.text.schema`；旧的 `responseSchema` 不再作为新实现默认字段；
- 结构化输出与采样/推理能力默认 `UNKNOWN`，不根据 Gemini 模型名猜测；`maxOutputTokens`、streaming、system instruction 和流式 usage 作为协议基线放行；
- SSE 的每个 data 都按完整 `GenerateContentResponse` chunk 解析；finish reason 暂存到 EOF，再输出唯一终态，以接收可能位于最后的 usage-only chunk；缺少 finish reason 的 EOF 为 `STREAM_INTERRUPTED`；
- 只输出候选 0 中非 thought 的 text part；thought、thought signature、未知多模态 part 不进入小说正文；function call 只映射为工具终态，不伪装成普通正文；
- `promptFeedback.blockReason` 和 candidate `finishReason` 分开映射安全、政策、语言、长度、工具、畸形响应与未知结果；未知 finish reason 失败保守；
- usage 映射 prompt、candidate、cache、thought 和 total token；明显不可能的负数或总量关系按协议错误关闭；
- `GET /models?pageSize=1000` 只保留声明支持 `generateContent` 的模型，并读取输入/输出 token 上限提示；连接测试仍是无生成费用的鉴权/列表检查。

9 项 JVM 固定夹具、API 35 与 API 30 各 1 项本地 HTTPS/两字节 UTF-8 集成测试通过；没有调用真实 Gemini API。

## 20. TASK-027 模型能力登记与探测证据

能力登记已从“单个最终快照”改为按 `(connectionId, endpointFingerprint, protocolId, modelId, source)` 保存各来源证据，再逐字段合并：

- 地址与协议生成 SHA-256 端点指纹；连接改地址后旧证据无法命中，原始 base URL 不进入登记表键或默认字符串；
- 解析顺序固定为保守未知 → 内置协议基线 → 官方元数据 → 未过期成功探测 → 已确认风险的用户覆盖；高优先级中的 `UNKNOWN` 不会擦除低优先级的已知值；
- 官方元数据和自动探测必须带有限过期时间；到达 `expiresAt` 的瞬间即失效，适配器版本变化和未来时间证据同样不采用；
- 自动探测只允许把“明确成功”记为支持、把“服务端明确拒绝该字段”记为不支持；超时、鉴权失败、余额不足、网络错误等不确定结果保持 `UNKNOWN`；
- 探测证据默认 7 天过期，允许范围 1 小时至 30 天，避免一次偶然结果永久控制后续付费请求；
- 用户覆盖必须携带本地风险确认时间，可单次删除覆盖记录；删除后自动显露仍有效的探测/官方/内置值，不需要重新配置连接；
- 每个来源只保留该键最新的 `verifiedAt`，迟到的旧刷新结果不能覆盖较新的证据；
- 存储读取失败、损坏枚举、协议/模型/适配器版本错配均失败保守，不会因此放行未知高级字段；
- OpenAI Chat、OpenAI Responses、Anthropic 与 Gemini 四个适配器均提供 registry-backed resolver，生成与能力查询走同一份模型级解析结果。

Room schema v5 在加密主库中新增 `provider_capability`；领域合并位于 `:provider:common`，Room 映射位于独立 `:provider:capability-storage`，数据库模块只负责原始实体、DAO 和迁移。完整低成本联网探测由 TASK-029 调用本证据接口；本任务提供严格证据模型、有效期、持久化和四适配器接线，没有调用真实 API。

## 21. TASK-028 跨协议失败证据契约

- `ProviderCallFailure` 与 `ProviderStreamEvent.Failed` 必须同时表达标准错误码和 `FailureRequestState`，避免业务层只凭 429/5xx 猜测是否可重发；
- 安全传输层在密钥不可读、端点校验、DNS 或 TLS 建连失败时标记 `NOT_SENT`；Socket/超时等不能证明送达结果的故障保持 `RESULT_UNKNOWN`；
- 四个适配器收到非 2xx 响应时标记 `PROVIDER_REJECTED`，传输层失败则原样透传请求状态；成功响应开始后的 I/O 中断标记 `RESPONSE_STARTED`；
- HTTP-date 与秒数形式的 `Retry-After` 统一转换为毫秒，最大 24 小时；非法或带控制字符的值忽略，不进入日志或用户正文；
- Gemini 的 `RESOURCE_EXHAUSTED` 只有存在合法 `Retry-After` 才视为短期限流，否则按额度耗尽停止；
- 适配器只负责提供事实证据，是否等待、修复、续写、重试或要求用户确认由 `:core:task` 的统一策略决定。

统一策略覆盖 13 类远程失败、格式修复和输出截断；最多自动重试 3 次、累计等待不超过 15 分钟，并把“已经收到正文”和“结果可能已产生费用”置于错误码之前判断。此任务没有进行真实 API 调用。

## 22. TASK-029 连接验证与模型发现

`ProviderConnectionVerifier` 位于 `:provider:common`，只编排统一适配器、能力登记和明确状态，不解析任何厂商 JSON：

- 基础验证只调用一次 `listModels`，不调用 `generate`；成功列表中的模型可标为 `LISTED`，列表给出的上下文/输出上限以 `OFFICIAL_METADATA` 保存，其他可选能力仍为 `UNKNOWN`；
- 只有服务端明确返回列表端点不存在或协议不兼容，且请求状态为 `PROVIDER_REJECTED` 时，才允许把手填模型保存为 `UNVERIFIED_MANUAL`；鉴权、额度、网络、TLS、超时和结果不明直接失败；
- 完整验证需要已选模型和显式费用确认，只发送一次固定英文通用词，`maxOutputTokens=16`；能力表不能明确允许输出上限字段时不发送，避免“低成本测试”变成无上限请求；
- 若协议明确支持流式则使用流式，否则使用非流；只有 `STOP` 或 `LENGTH` 终态算生成通路成功，拒绝、工具调用、未知终态和缺失终态都失败关闭；
- usage 只有实际收到才标记已观察；缓存写入失败不触发第二次网络请求，也不抹去已经完成的连接结果；
- 外层总时限最大 60 秒。模型列表已花费的时间从探针预算中扣除，不能形成“列表 60 秒 + 探针再 60 秒”；
- OpenAI Chat、OpenAI Responses、Anthropic Messages 与 Gemini 均通过本地 MockWebServer 验证“列表一次 + 有界探针一次”的真实编码路径，没有调用真实 API。

## 23. TASK-047 既有请求恢复查询契约

`ProviderAdapter` 额外公开与生成严格分离的恢复能力：

- `requestRecoveryCapability` 只能声明 `NOT_SUPPORTED` 或 `STATUS_QUERY`；未知能力按不支持处理；
- `queryRequestRecovery` 只接受已经持久化并脱敏的远端请求引用，返回不支持、无结论、运行中、明确未执行、已完成但本地无输出五类标准结果；
- 查询调用不得创建生成请求、改变提示词或静默调用 `generate()`；协调器对单次查询使用 1–60 秒有界超时，默认 15 秒；
- 超时、网络异常和未知响应统一变成“无结论”，不得据此回队；外部取消仍向上传播；
- 只有 `ConfirmedNotExecuted` 可作为自动回队的远端必要证据，但还必须与本地空草稿、无已知 usage、已发送证据一致；
- `CompletedWithoutLocalOutput` 会保留提供方返回的最终 usage，并进入用户确认，不把远端完成误当成可重发；
- 当前 OpenAI Responses、OpenAI Chat Compatible、Anthropic Messages、Gemini 四个内置适配器均声明 `NOT_SUPPORTED`。未接入可靠官方查询端点前不伪造支持。

## 24. TASK-050 Prompt 层准备契约

- Prompt Bundle 内部层名必须与 `PromptLayer` 枚举逐项同名映射，顺序由阶段契约固定；适配器不得自行重排、合并或猜测未知层。
- 远程准备结果包含阶段、模板、输出 schema ID、是否流式、所需层和已装配指令；默认字符串只暴露计数，指令正文和绑定哈希均脱敏。
- 本地阶段返回 `LocalOnly`，成年人门禁不通过返回 `Blocked`；两者都不能进入适配器编码。只有 `Remote` 计划才具备后续请求编码资格。
- `Remote` 仍不是发送许可。调用方必须继续经过目的地确认、预算预留、双租约、RequestIntent 和一次性发送 permit；TASK-050 没有改变四个 Provider 的网络行为。

## 25. TASK-051 初始规划输出契约

- `story-seed.v1`、`story-bible.v1`、`master-outline.v1` 的完整 JSON Schema 由生成模块随请求提供；所有对象关闭未知字段，嵌套人物、规则、事实和节拍也必须声明 ID、范围和数量限制。
- 适配器只负责协议编码和流传输，不得把无效 JSON 修补成“看起来合理”的业务对象。输出仍须经过统一严格校验、加密 artifact hash、最新 Attempt 和提交 permit。
- 三阶段本地端到端夹具使用不会打开网络的假适配器；它证明协议边界和持久链路，而不证明任何外部模型的文字质量或策略接受度。
- 接入真实连接时不得新增快捷通道；目的地确认、预算/token 预留、双租约、RequestIntent 和一次性发送许可仍全部必需。

## 26. TASK-052 `arc-plan.v1` 适配边界

- Provider 请求携带完整 `arc-plan.v1` schema，其中章节数组 `maxItems=8`；适配器不能删除该上限、把输出改成自由文本或在同一请求追加后续窗口。
- 卷/窗口范围、总纲 hash、父修订 hash 和下一窗口指针由本地冻结并在输出后复核。模型只填写计划内容，不能决定扩大到 300/10,000 章。
- 当前端到端仍使用不会打开网络的假适配器。真实连接必须继续经过目的地、预算/token、双租约、RequestIntent、加密流和提交许可。

## 27. TASK-053 首章最小包与 Provider 打开边界

- `first-chapter-bootstrap.v1` 是独立输出 schema，不把减少后的输入伪装成普通 `chapter-plan.v1`。适配器只能传输完整 schema 和冻结指令，不能自由补字段或把三章粗计划扩成后续正文。
- 第一章快车道的远程准备继续继承应用硬规则、呈现规则和成年人场景门禁；最小包并不获得跳过年龄/虚构身份检查的特权。
- 支持 Prompt Bundle v1 的章节请求在 Provider 打开前必须携带持久 `firstChapterBootstrap` 或 `chapterProgressionGate` 证据。旧的不受支持测试契约不会被追溯误判，但正式 v1 请求不能缺省放行。
- Provider-open claim 在同一持久事务中重查种子、圣经、总纲、窗口和上一章证据；调用方伪造 evidence hash、使用旧第一章版本或在规划尚未成功时均不能建立网络连接。
- 本阶段端到端只使用本地假 Provider；没有改变四个真实适配器的网络、预算、目的地或发送许可边界。

## 28. TASK-054 上下文快照与 Provider-open 契约

- `ASSEMBLE_CONTEXT` 是纯本地阶段。适配器不得收到候选材料、参与裁剪，也不得为该阶段创建 RequestIntent、Attempt 或 Usage。
- `BUILD_CHAPTER_PLAN` 只能消费成功 Stage 输出引用绑定的不可变 `ContextSnapshot`；发送内容必须由 `chapter-context-manifest.v1` 的已选完整项按固定顺序重建，规范 payload hash 必须与快照一致。
- 在任何套接字或 HTTP 请求打开前，审计仓储重验 Prompt Bundle、Story Bible head、Outline head/父链、目标 ARC/CHAPTER、上一章当前版本和章节推进证据。失效时拒绝发送，不能依赖先前内存中的“已通过”。
- 模型上下文容量来自已确认能力登记或用户明确的保守回退，不从模型 ID 推断。未知且未确认、输出预留越界或必需事实超限都不能进入协议编码。
- 适配器仍须在该快照门禁之后经过目的地确认、预算预留、双租约、RequestIntent 和一次性发送许可；上下文已组装不代表已经授权联网。

## 29. TASK-055 正文流协议

- `DRAFT_CHAPTER` 必须使用完全匹配的 `chapter-draft.v1` JSON Schema：根对象只允许必填字符串 `body`，不得增加标题、状态、解释或第二候选字段。
- 请求必须启用 streaming。正文协调器在打开 Provider 前检查 schema；续接请求还必须在 `USER_REQUEST` 层精确绑定持久续接序号、保存尾窗和 `requiredBodyPrefix`，缺少任一项时 0 次联网。
- 适配器继续输出通用 `StructuredDelta`；增量正文解码属于 `feature:generation`，不会让四个协议适配器各自猜 JSON 或拼接正文。
- `STOP` 只有在 JSON 完整闭合、锚点完整且存在新增正文时才可进入校验；`LENGTH` 只接受已经解码的完整码点前缀。不完整转义/代理项不会写入草稿。
- `LENGTH` 与解码格式异常的分类和响应 hash 同一持久事务落库，关闭“响应已保存但重启后不知道该校验还是续写”的窗口。网络 EOF 仍是 `STREAM_INTERRUPTED/UNKNOWN_RESULT`，不能冒充截断。

## 30. TASK-056 `chapter-memory.v1` 适配边界

- 请求固定 `temperature=0`、streaming、严格 `chapter-memory.v1` JSON Schema 和 256–16,384 输出 token；四种 Provider 适配器只负责传输通用结构化分片，不各自解释章节记忆。
- Prompt 输入包含冻结正文、最终版本 ID、正文 SHA-256、章节 ID/序号，以及最多 256 个已知实体及其类型/成年人状态；正文上限 4 MiB，全部属于敏感 Provider 内容。
- 输出最多 128 条人物事件和 128 条事实，根对象及所有子对象关闭额外字段。Provider 自报 schema 成功仍必须经过本地严格 UTF-8/JSON/重复键/资源/来源/实体校验。
- 格式无效最多形成一次零温度修复；未知实体、错误来源、非法关系目标和越权 Canon 都按结构结果无效处理，不降级为自由文本。
- 记忆重建 RequestIntent 落库后，打开 Provider 前由数据库重验当前正式版本；失败不会调用适配器。正常候选检查已由 TASK-058 绑定最终候选 hash，有限修订后的重新提取和最终提交由 TASK-059 编排。

## 31. TASK-057 `chapter-story-tracking.v1` 适配边界

- 适配器接收完整 JSON Schema，但领域层仍执行独立严格解析、来源快照交叉校验和伏笔状态转换校验；服务端声称 schema 合法不能代替本地验证。
- 请求 temperature 固定为 0，并限制最多 64 个时间线事件、64 个伏笔操作、512 KiB 原始输出和一次格式修复。
- `EXTRACT_MEMORY` 的专用子契约由冻结 `outputSchemaId` 路由。普通 `chapter-memory.v1` 守卫会显式跳过 tracking 子契约，tracking 守卫也不会处理普通章节记忆。
- RequestIntent 后正文、章节记忆、伏笔或实体快照变化时，在打开网络连接前拒绝，适配器调用数为 0。格式已返回但无效时，原 Attempt Usage 仍最终结算。

## 32. TASK-058 `chapter-consistency-report.v1` 适配边界

- 请求固定 temperature 0、streaming、512 KiB 输出上限和完整 JSON Schema；适配器只传输通用结构化分片，不解释问题码或改写章节。
- 输入精确绑定候选版本/正文 hash/章节/序号、本地检查快照、场景契约、已知实体/证据及关键过程节点。持久 RequestIntent 的 `inputHash` 必须等于规范来源绑定 hash，Provider 打开前再次验证。
- 输出必须精确回显来源、检查快照和场景契约 hash，并按固定顺序返回全部 23 个检查项。问题只允许白名单码、固定严重度/修订动作、Unicode 码点范围和请求白名单中的实体/伏笔/过程 ID。
- 本地专属问题不能由模型伪造；严格场景的每个过程节点必须恰好返回一次 COVERED/MISSING，非严格场景必须返回空过程结果。
- 首次结构无效最多一次格式修复并如实结算原 Attempt 用量；来源变化、成年人门禁阻断或本地 blocker/major 时调用数为 0。
