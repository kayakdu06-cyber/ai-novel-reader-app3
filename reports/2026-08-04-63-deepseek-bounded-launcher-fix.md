# 工作汇报 63：DeepSeek 受限启动器修复

日期：2026-08-04

## 结论

DeepSeek API、密钥和模型本身正常。此前“长时间无响应且没有代码差异”的直接原因是 Codex 子任务被放入只读沙箱，同时外层没有实时事件输出；父进程中断后子进程还继续运行，造成额外消耗。

启动链路现已完成最小安全闭环，并通过本地模拟与真实 DeepSeek 基础设施冒烟测试。

## 原故障证据

- 原 DeepSeek 会话从 03:58 运行到 04:14，最终正常形成回交，不是 API 卡死。
- 会话沙箱为 `read-only`，Gradle 在创建缓存锁文件时被拒绝，DeepSeek 无法修改代码或完成构建。
- 启动器没有 `--json`，外层看不到过程事件，表现为“假死”。
- 父层被中断后，DeepSeek 子进程继续运行至完成。
- 原会话累计记录 8,093,229 Token，其中缓存输入 7,841,280、输出 95,214、推理输出 75,525。实际计费以 DeepSeek 后台为准。

## 已完成修改

- DeepSeek 只获得当前仓库 `workspace-write`，额外仅允许 app2 的 Codex 会话目录、临时目录和共享 Gradle 缓存，不开放整个缓存根目录。
- 原生 Windows 固定启用 `windows.sandbox=unelevated` restricted-token；解决 Windows 沙箱未启用时 `workspace-write` 被自动降级为 `read-only` 的问题。
- Codex 会话、Gradle 缓存、临时提示和运行日志全部迁到 `D:\gptuser`。
- 新增实时 JSONL 事件日志和 15 秒心跳。
- 新增 15 分钟默认超时和 1,000,000 累计 Token 默认硬上限。
- 达到任一上限后终止完整子进程树，分别返回 124/125。
- 新增单实例文件锁，阻止同一项目并发运行多个 DeepSeek 任务。
- 默认推理等级从 `high` 降为 `low`；仍可在任务包说明理由后显式提高。
- 模型上下文从 1,048,576 缩至 131,072，并在 100,000 Token 触发自动压缩。
- 任务包超过 60,000 字符时拒绝启动，并自动追加防重复读取、重复失败和越界访问规则。
- 临时提示通过标准输入交给 Codex，不直接放入启动命令；结束后自动删除。
- 增加独立子进程入口、运行手册、任务包预算字段和 AI 协作规程。

## 修改文件

- `.codex/config.toml`：默认低推理等级。
- `.codex/models.json`：缩小上下文并启用自动压缩。
- `scripts/start-deepseek-codex.ps1`：隔离、预算、日志、锁、进程树清理和汇总。
- `scripts/invoke-deepseek-codex-child.ps1`：从 D 盘临时文件安全读取参数和提示，通过标准输入启动 Codex。
- `docs/24-AI-DEVELOPMENT-PROTOCOL.md`：加入强制受限入口和费用门禁。
- `docs/ai/CURRENT-CONTEXT.md`：记录当前 DeepSeek 运行基线和验证证据。
- `docs/ai/TASK-PACKET-TEMPLATE.md`：加入运行预算、读取清单和提前停止条件。
- `docs/ai/DEEPSEEK-RUNBOOK.md`：新增操作、日志、退出码和故障处理手册。

## 验证

1. PowerShell 5.1 语法检查：两个启动脚本均为 0 个语法错误。
2. `scripts/start-deepseek-codex.ps1 -ValidateOnly`：通过，未调用模型。
3. `-DryRun`：通过，未调用模型，显示正确仓库、沙箱、缓存、日志和预算。
4. 本地假模型正常结束：退出码 0；识别 `turn.completed`；用量汇总 12,345 Token。
5. 本地假模型超限：识别隔离会话中的实时 Token；约 2 秒内终止；启动器返回 125；无残留子进程和临时提示。
6. Windows restricted-token 直接探针：不调用模型，写入、精确读回、删除均成功。
7. 前置真实诊断：模型请求和日志链路正常，但有效 sandbox 为 `read-only`，工具被 `blocked by policy`；证明“模型退出码 0”不能替代工具结果验收。
8. 最终真实 DeepSeek 基础设施冒烟：有效 sandbox 为 `workspace-write`；在 app2 临时目录写入固定探针、精确读回并删除；退出码 0；累计 41,270 Token。
9. 安全复核：探针不存在；日志和会话均在 D 盘；本次日志中的密钥形态匹配数为 0；未发生插件目录联网尝试；DeepSeek 残留进程数为 0。

本次启动器修复过程中共进行了 4 次受硬上限保护的真实 DeepSeek 小探针，运行记录累计 Token 合计 120,504。前三次用于定位 Windows 自动降级和验证日志链路，最终一次完成受限写入验收；实际计费以 DeepSeek 后台为准。

真实冒烟运行汇总：

`D:\gptuser\logs\ai-novel-reader-app2\deepseek\20260804-044810-c7ca9f2c.summary.json`

## 当前边界

- 本次没有继续 TASK-059 业务开发，也没有运行织卷 App 的真实生成 API。
- Token 门禁在每次 Codex 用量事件落盘后生效；一个已经发出的模型请求可能造成少量越线，因此仍需用小任务包和明确文件清单控制范围。
- 正式 DeepSeek 回交仍必须由 Sol 审查差异和测试，不能由模型自行更新任务完成状态。
