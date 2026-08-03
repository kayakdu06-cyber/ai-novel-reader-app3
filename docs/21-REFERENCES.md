# 织卷参考资料与复用边界

> 核对日期：2026-08-02。API、Android 规则、模型和许可证会变化，开始实现具体模块时必须再次查看对应官方资料。

## 1. Android 官方资料

### 后台任务

- [Foreground service timeouts](https://developer.android.com/develop/background-work/services/fgs/timeout)：Android 15+ 部分 FGS 类型在后台有累计时限，需要实现 timeout 回调并安全停止。
- [Declare foreground services](https://developer.android.com/develop/background-work/services/fgs/declare)：正式服务必须在清单声明组件、权限与前台服务类型。
- [Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types)：`dataSync` 适用于网络数据传输与本地文件处理，且需要对应权限。
- [Android 15 foreground service changes](https://developer.android.com/about/versions/15/changes/foreground-service-types)：Android 15 为部分 FGS 引入累计后台时限，并限制从 `BOOT_COMPLETED` 启动 `dataSync`。
- [Notification runtime permission](https://developer.android.com/develop/ui/views/notifications/notification-permission)：拒绝通知权限不等于可以省略 FGS 通知；用户仍可从系统任务管理器看到活动服务。
- [Persistent work](https://developer.android.com/develop/background-work/background-tasks/persistent)：WorkManager 适合必须可靠完成、可延迟的短时后台工作，约束由系统在合适时间满足。
- [Manage work](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/manage-work)：唯一 Work 用于避免重复排队，并通过既有工作策略控制重复调度。
- [Define work requests](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work)：一次性/周期 Work、约束、退避和最小周期等正式规则。
- [Support for long-running workers](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running)：长 Worker 会使用前台执行；Android 16 起可能耗用 JobScheduler 配额，因此不能作为无限长篇生成宿主。

应用结论：生成状态必须持久化，长篇不能依赖永久前台服务或长 Worker；WorkManager 只负责有界、无网络的恢复/维护，并以唯一任务避免重复调度。

### 备份和隐私

- [Back up user data with Auto Backup](https://developer.android.com/identity/data/autobackup)：系统自动备份范围和 Android 12+ 数据提取规则。
- [Backup security best practices](https://developer.android.com/privacy-and-security/risks/backup-best-practices)：敏感数据的备份风险。

应用结论：明确排除数据库、密钥、草稿、日志和恢复点；用应用内加密备份。

### 数据库迁移

- [Migrate your Room database](https://developer.android.com/training/data-storage/room/migrating-db-versions)：显式迁移、测试和破坏性回退风险。
- [Android cryptography](https://developer.android.com/privacy-and-security/cryptography)：Android 推荐使用 AES-256 与 GCM 等成熟算法，不自行设计密码算法。
- [OWASP Password Storage Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html)：Argon2id 优先级、PBKDF2-HMAC-SHA256 600,000 次当前基线和工作因子升级要求。
- [NIST SP 800-132](https://csrc.nist.gov/pubs/sp/800/132/final)：基于口令的存储密钥派生、随机 salt 和可调工作因子基础。
- [Android Network Security Configuration](https://developer.android.com/privacy-and-security/security-config)：声明式禁用明文、系统信任锚和 debug-only override 边界。
- [Android cleartext communications risk](https://developer.android.com/privacy-and-security/risks/cleartext-communications)：明文网络的窃听和篡改风险以及关闭方式。
- [OkHttp](https://github.com/square/okhttp)：当前 5.3.0 依赖、TLS 平台行为与 MockWebServer 测试工具来源。
- [SQLCipher Community Edition License](https://www.zetetic.net/sqlcipher/license/)：Community Edition 的 BSD-style 条款及二进制分发时的许可证复现义务；核对副本存放于 `third-party/sqlcipher-community-license.txt`。

应用结论：正式版禁止破坏性迁移清库，覆盖所有支持版本的迁移路径。

### 网络安全

- [Network security configuration](https://developer.android.com/privacy-and-security/security-config)：按域配置明文流量、证书和调试覆盖。

应用结论：远程只用 HTTPS；本地 Ollama 的 HTTP 逐 host 放行；不全局明文、不信任所有证书。

### 签名、更新和开发者验证

- [Sign your app](https://developer.android.com/studio/publish/app-signing)
- [How app updates work](https://developer.android.com/google/play/app-updates)
- [Android developer verification FAQ](https://developer.android.com/developer-verification/guides/faq)
- [Limited distribution](https://developer.android.com/developer-verification/guides/limited-distribution)
- [Android Developer Console guide](https://developer.android.com/developer-verification/guides/android-developer-console)

应用结论：固定包名/签名并双备份；每个发布前复核 2026–2027 分阶段验证规则。

## 2. 模型 API 官方资料

### OpenAI

- [Create a model response](https://developers.openai.com/api/reference/resources/responses/methods/create)
- [Streaming API responses](https://developers.openai.com/api/docs/guides/streaming-responses)
- [Responses streaming events](https://developers.openai.com/api/reference/resources/responses/streaming-events)
- [Structured outputs](https://developers.openai.com/api/docs/guides/structured-outputs)
- [API overview and request IDs](https://developers.openai.com/api/reference/overview)
- [Create Chat Completion](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create)

关注：Responses 请求显式使用 `store:false`，结构化输出位于 `text.format`；流式正文来自 `response.output_text.delta`，以 `response.completed/incomplete/failed` 或 `error` 形成语义终态，不能套用 Chat 的 `[DONE]`。官方兼容规则允许新增响应字段和流事件，因此未知非终态要忽略，但未知终态不能假成功。Chat Completions 当前使用 `max_completion_tokens`，`max_tokens` 已弃用；流式 usage 可在 `[DONE]` 前的额外空 choices chunk 中出现。不能以旧博客字段作为事实。

### DeepSeek

- [Chat Completions API](https://api-docs.deepseek.com/api/create-chat-completion/)
- [Lists Models](https://api-docs.deepseek.com/api/list-models/)
- [Error Codes](https://api-docs.deepseek.com/quick_start/error_codes/)

关注：`POST /chat/completions` 使用 `max_tokens`；SSE 以 `data: [DONE]` 结束；`stream_options.include_usage` 会在终止前增加 `choices=[]` 的 usage chunk；JSON Output 必须同时在消息中明确要求 JSON；`reasoning_content` 与最终 `content` 分离；402 表示余额不足，429 表示速率限制，503 表示过载。当前模型名和推理强度支持范围会变化，不固化为长期产品承诺。

### Anthropic

- [Messages API](https://platform.claude.com/docs/en/api/messages/create)
- [Streaming Messages](https://platform.claude.com/docs/en/build-with-claude/streaming)
- [API versioning](https://platform.claude.com/docs/en/api/versioning)

关注：`anthropic-version`、message/content block 事件顺序、ping/错误、未知未来事件容忍。
当前实现结论：固定 `2023-06-01`；流以 `message_stop` 结束，不使用 `[DONE]`；`message_delta.usage` 是累计值；thinking/signature 与 text 分流；529/`overloaded_error` 映射服务过载。官方版本策略允许新增枚举值和事件类型，因此未知事件忽略，未知 stop reason 不假成功。

### Gemini

- [Gemini API reference](https://ai.google.dev/api)
- [generateContent](https://ai.google.dev/api/generate-content)
- [Models API](https://ai.google.dev/api/models)
- [Structured outputs](https://ai.google.dev/gemini-api/docs/generate-content/structured-output)
- [Safety settings](https://ai.google.dev/gemini-api/docs/safety-settings)
- [Troubleshooting](https://ai.google.dev/gemini-api/docs/troubleshooting)

关注：原生 contents/systemInstruction、候选 finish reason、promptFeedback、usageMetadata、当前 responseFormat 和模型列表。当前实现选择成熟、无状态的 GenerateContent，并显式 `store:false`；流式 finish reason 保留到 EOF 以接收尾部 usage。官方目前推荐 Interactions 作为最新方向，因此两者保持协议隔离，后续新增而不原地偷换。

### Ollama

- [OpenAI compatibility](https://docs.ollama.com/api/openai-compatibility)
- [Streaming responses](https://docs.ollama.com/api/streaming)
- [Chat API](https://docs.ollama.com/api/chat)

关注：OpenAI 兼容只覆盖部分字段；Responses 为无状态子集；原生 REST 流使用 NDJSON。

## 3. AI 小说开源参考

这些项目用于理解思路，不直接作为织卷依赖，也不代表其产品、安全、费用或内容策略可原样采用。

| 项目 | 可借鉴点 | 许可证/复用判断（核对日） |
|---|---|---|
| [GPTAuthor](https://github.com/dylanhogg/gptauthor) | 从简短描述生成 synopsis，再逐章生成；失败缓存/续跑；也明确暴露仅看前章导致连续性问题 | GitHub API 标识 MIT；复用代码仍需保留许可 |
| [LongWriter](https://github.com/THUDM/LongWriter) | 先规划再分段写的 AgentWrite 思路；长输出评估 | Apache-2.0；模型权重另看各自许可 |
| [NovelGenerator](https://github.com/KazKozDev/NovelGenerator) | 分阶段写作、角色/场景处理、版本和一致性检查 | README 声明 MIT，但 GitHub API 为 NOASSERTION；复制前必须核验仓库 LICENSE 全文 |
| [novel-creator-skill](https://github.com/leenbj/novel-creator-skill) | 文件级长期记忆、RAG、章节门禁、索引更新 | README 声明 MIT，但 GitHub License API 未找到文件；只借鉴思想，复制前重新核验 |
| [autonovel](https://github.com/NousResearch/autonovel) | seed→world/characters/outline/canon→逐章→评审循环 | GitHub License API 未找到许可证；无明确许可时不复制代码/文本 |
| [RikkaHub](https://github.com/rikkahub/rikkahub) | 原生 Android 多提供方连接、Room/DataStore/OkHttp 等工程组织 | AGPL-3.0；只研究架构概念，不复制实现，除非明确接受完整合规义务 |
| [Author](https://github.com/YuanShiJiLoong/author) | 本地优先写作、世界观、快照/回滚、多提供方和模型列表 | AGPL-3.0；只研究产品思路，不复制实现，除非明确接受完整合规义务 |

## 4. 从参考项目得到、但需重新实现的结论

- “种子 → 世界/人物/总纲 → 分章 → 记忆 → 检查”比一次生成全书可靠。
- 只提供总纲和上一章不足以维持长篇，需要结构化状态和相关检索。
- 多次润色会明显增加调用次数，必须和费用硬上限联动。
- 失败缓存/断点续跑需要数据库状态机和幂等，不能仅保存一个字符串状态。
- 提供方切换应做能力适配，不能只替换 base URL。
- 本地优先仍需加密、备份、迁移、签名和系统备份排除。

## 5. 禁止直接搬用的内容

- 未确认许可证的代码、提示词、文案、图标和插图；
- AGPL 项目的源码片段进入织卷闭源构建，除非用户之后明确接受并完成许可证义务评估；
- 项目示例中的具体小说文本、角色或风格指纹；
- 旧版 SDK 字段、模型名、价格和上下文数字；
- 参考 App 的广告、会员、账户、云端或社区模块。

## 6. 依赖引入清单要求

新增第三方库前记录：名称、版本、用途、许可证、主页、是否含 native code、维护状态、已知漏洞、数据出站行为和替代方案。Release 自动生成许可证页面；仅“个人使用”不等于可以忽略开源许可。
