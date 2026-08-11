# app开发2 独立项目绑定

- 本仓库只对应 Codex 任务“app开发2”。
- 唯一允许修改的项目根目录是 `D:\gptuser\projects\ai-novel-reader-app2`。
- 开始任何修改、构建或测试前，必须确认 `git rev-parse --show-toplevel` 返回上述目录；不一致时立即停止。
- 不得修改 `D:\gptuser\projects\ai-novel-reader` 或其他同名、相似项目；除非用户明确要求，否则也不要从这些目录同步文件。
- 本副本从原项目提交 `48e897c0b1d6601daeaabc5351be559a02b39a3a` 独立复制，且没有 Git remote。未经用户明确要求，不要添加远程仓库或把原项目目录设置为 remote。
- 所有项目产出、缓存和临时文件必须保留在 `D:\gptuser` 下。

## AI 开发入口（强制）

- 每次开始实质性开发、审查、构建或测试前，必须完整读取 `docs/24-AI-DEVELOPMENT-PROTOCOL.md` 和 `docs/ai/CURRENT-CONTEXT.md`；不得只依赖对话历史。
- 接着按 `docs/24-AI-DEVELOPMENT-PROTOCOL.md` 的启动顺序读取当前任务对应的编号文档、源代码和测试。
- 交给 DeepSeek 的工作必须先形成符合 `docs/ai/TASK-PACKET-TEMPLATE.md` 的文字任务包。DeepSeek 是纯文本执行者，不得承担图片、截图、视觉验收或其他多模态判断。
- DeepSeek 的 API 仅用于驱动本仓库中的 Codex 编码代理，不等于把 DeepSeek 接入“织卷”App 的产品 Provider；除非用户另行明确要求，不得修改 App 的模型供应商实现。
- 用户已授权 Sol 在 app开发2 的开发过程中自行决定何时调用项目隔离的 DeepSeek V4 Flash 编码模型，无需逐次确认；每次交接应在进度更新中简短说明。此授权不包含调用“织卷”App 内部的真实生成接口、接入新的付费服务或扩大到其他项目。不得把密钥写入文档/日志/版本库，也不得由 DeepSeek 把任务状态改成“完成”。完成状态必须由 Sol 在审查差异并取得相应测试证据后确认。
