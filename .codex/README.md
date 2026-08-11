# app开发2 的 Codex 模型隔离

此目录只控制 `D:\gptuser\projects\ai-novel-reader-app2`。它不会修改用户级
`C:\Users\du\.codex\config.toml`，也不会影响 APP开发1 或其他项目。

- 默认模型：`deepseek-v4-flash`
- 接口：DeepSeek 官方 Responses API
- 密钥来源：Windows 当前账户加密的 `.codex/deepseek-key.local`
- 多模态：关闭；图片和界面验收继续交给 Sol

密钥文件由 `scripts/set-deepseek-key.ps1` 创建，只能由当前 Windows 账户解密，
并且已经被 Git 忽略。`scripts/start-deepseek-codex.ps1` 会显式加载本目录的配置，
不依赖、不修改用户级 Codex 配置。离开本仓库时，Codex 继续使用用户级默认模型。

## 开发前必须读取

无论使用 Sol 还是 DeepSeek，均以根目录 `AGENTS.md` 为自动入口，并在实质工作前读取：

1. `docs/24-AI-DEVELOPMENT-PROTOCOL.md`
2. `docs/ai/CURRENT-CONTEXT.md`
3. 当前任务包指定的产品、架构、数据、状态机和测试文档

DeepSeek 只能接收符合 `docs/ai/TASK-PACKET-TEMPLATE.md` 的纯文字任务包。API Key 只保存在本机加密文件中，不得复制进任务包或项目文档。
