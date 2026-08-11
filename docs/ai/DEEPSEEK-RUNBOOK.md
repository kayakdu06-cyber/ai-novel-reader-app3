# app开发3 DeepSeek 受限运行手册

> 适用仓库：`D:\gptuser\projects\ai-novel-reader-app3`  
> 目标：让 DeepSeek 作为纯文本编码模型工作，同时控制项目隔离、写入范围、费用、日志和孤儿进程风险。

## 1. 固定边界

- 唯一仓库：`D:\gptuser\projects\ai-novel-reader-app3`。
- DeepSeek 只能修改该仓库，额外只允许写入 `D:\gptuser\cache\codex\ai-novel-reader-app3`、`D:\gptuser\cache\temp\ai-novel-reader-app3` 和 `D:\gptuser\cache\gradle`。
- 原生 Windows 使用 `windows.sandbox=unelevated` restricted-token 沙箱。没有这个设置时，当前 Codex CLI 会把 `workspace-write` 自动降级为 `read-only`。
- 不使用 `danger-full-access`，不绕过审批和沙箱。
- 不调用织卷 App 的真实生成 API，不访问其他项目副本，不读取或记录 API Key。
- 同一时间只运行一个 app开发3 DeepSeek 任务。
- 正式任务必须先形成符合 `TASK-PACKET-TEMPLATE.md` 的任务包。

## 2. 默认资源门禁

| 项目 | 默认值 | 作用 |
|---|---:|---|
| 沙箱 | `workspace-write` | 仓库可写，其余路径默认不可写 |
| Windows 执行隔离 | `unelevated` | restricted token，避免 workspace-write 被降级 |
| 额外可写目录 | app2 Codex、app2 临时目录、Gradle 缓存 | 不开放整个缓存根目录 |
| 推理等级 | `max` | 用户明确要求所有 DeepSeek 工作使用最高推理强度；仍由时间和累计 Token 门禁限制费用 |
| 最长运行时间 | 15 分钟 | 超时终止完整子进程树 |
| 累计 Token 上限 | 1,000,000 | 达到后终止完整子进程树 |
| 上下文窗口 | 131,072 | 防止百万级上下文持续回传 |
| 自动压缩阈值 | 100,000 | 在窗口耗尽前压缩上下文 |
| 任务包最大长度 | 60,000 字符 | 防止把历史对话或无关材料整体塞入 |

2026-08-08 用户持续授权覆盖上表的保守默认值：app开发3 正式任务保持 `max` 推理并可使用 `-NoTotalTokenLimit`。2026-08-09 用户进一步允许放宽思考时长：任务通常仍选择 15～30 分钟硬上限；只有此前同一窄任务在 30 分钟因正常推理超时、且没有权限、范围或重复失败阻塞时，才可在新任务包记录理由后提高到最多 45 分钟。启动器默认值仍保留，防止脱离任务包的误调用；所有运行仍受单任务锁和项目隔离约束。

累计 Token 门禁读取 `codex exec --json` 的结束用量，并在运行期间读取项目隔离 `CODEX_HOME` 中的会话用量事件。因此它既能形成最终汇总，也能在多轮工具调用过程中提前停止。单个已经发出的模型请求仍可能造成少量越线，门禁不能代替小任务包和有限文件清单。

## 3. 使用方法

只检查模型目录、启动参数和路径，不调用 DeepSeek：

```powershell
scripts/start-deepseek-codex.ps1 -ValidateOnly
```

该命令还会在 app2 临时目录内通过 Windows restricted-token 沙箱执行固定的写入、精确读回和删除探针；仍然不会请求 DeepSeek API。

检查某个任务包的最终限制，不调用 DeepSeek：

```powershell
scripts/start-deepseek-codex.ps1 `
    -TaskPacketPath docs/ai/task-packets/TASK-___-phase-_.md `
    -DryRun
```

执行正式任务：

```powershell
scripts/start-deepseek-codex.ps1 `
    -TaskPacketPath docs/ai/task-packets/TASK-___-phase-_.md
```

用户明确要求某次试运行不设置累计 Token 上限时，可使用：

```powershell
scripts/start-deepseek-codex.ps1 `
    -TaskPacketPath docs/ai/task-packets/TASK-___-phase-_.md `
    -NoTotalTokenLimit
```

该开关只取消累计 Token 终止条件，不取消运行超时、项目隔离、单任务锁和 Sol 的差异/测试复核。

### 只读补丁提案模式

Windows restricted-token 沙箱会隐式加入临时可写根，当前原生 `apply_patch` 无法同时处理它与项目目录；因此不允许 DeepSeek 用 Python、PowerShell 或其他命令绕过。需要代码修改时，优先使用只读补丁提案模式：

```powershell
scripts/start-deepseek-codex.ps1 `
    -TaskPacketPath docs/ai/task-packets/TASK-___-phase-_.md `
    -PatchProposalOnly `
    -MaxRunMinutes 20 `
    -NoTotalTokenLimit
```

该模式使用 `read-only` 沙箱、不传额外可写目录。DeepSeek 只能在最终回交中给出完整、最小、可供 `apply_patch` 使用的补丁提案；Sol 审查后应用补丁并运行测试。任务包必须明确禁止探针和 shell/Python/.NET 文件写入。普通模式仍可用于纯审计或已经存在安全写入入口的任务，但原生补丁失败时必须停止，不得绕过。

只有复杂且边界清晰的任务才能提高时间或 Token 预算，例如：

```powershell
scripts/start-deepseek-codex.ps1 `
    -TaskPacketPath docs/ai/task-packets/TASK-___-phase-_.md `
    -ReasoningEffort max `
    -MaxRunMinutes 20 `
    -MaxTotalTokens 1500000
```

推理等级保持 `max`。通常不得超过 30 分钟；只有已拆分的同一窄任务在 30 分钟正常推理超时后，才可提高到最多 45 分钟。用户已授权正式任务不设累计 Token 上限。提高时间预算不是允许模型扩大范围的授权。

## 4. 运行输出

- 实时事件：`D:\gptuser\logs\ai-novel-reader-app3\deepseek\<run-id>.events.jsonl`
- 标准错误：`D:\gptuser\logs\ai-novel-reader-app3\deepseek\<run-id>.stderr.log`
- 最终回交：`D:\gptuser\logs\ai-novel-reader-app3\deepseek\<run-id>.final.md`
- 安全汇总：`D:\gptuser\logs\ai-novel-reader-app3\deepseek\<run-id>.summary.json`
- Codex 会话：`D:\gptuser\cache\codex\ai-novel-reader-app3\sessions`

控制台每隔不超过 15 秒输出心跳；工具事件或用量变化时输出简短进度。提示临时文件只存在于 `D:\gptuser\cache\temp\ai-novel-reader-app3`，运行结束、中断或失败后删除。

## 5. 退出码

| 退出码 | 含义 | 处理 |
|---:|---|---|
| 0 | 正常结束 | Sol 检查差异、日志和测试 |
| 124 | 达到时间上限 | 缩小任务或明确提高时间预算 |
| 125 | 达到累计 Token 上限 | 拆分任务、减少必读文件，不直接放宽上限 |
| 其他 | Codex、Provider、脚本或子进程失败 | 查看同一 run-id 的 summary 与 stderr |

即使 DeepSeek 返回 0，也不能自行宣布 TASK 完成；Sol 仍需检查 Git 差异并运行任务相关测试。

## 6. 已验证场景

2026-08-04 已验证：

- `ValidateOnly` 不调用模型并通过；
- `ValidateOnly` 的 Windows restricted-token 写读删探针通过；
- `DryRun` 不调用模型并显示受限参数；
- 本地假模型正常结束，日志和用量汇总正确；
- 本地假模型达到 Token 上限后约 2 秒内被终止，返回 125，子进程和临时提示均清理；
- 未启用 Windows restricted-token 时，真实 DeepSeek 请求可以返回 0，但有效沙箱仍是 `read-only`，工具会被 `blocked by policy`；不能把退出码 0 单独作为成功证据；
- 启用 `windows.sandbox=unelevated` 后，有效沙箱为 `workspace-write`，真实 DeepSeek V4 Flash 在 app2 临时目录完成探针写入、精确读回和删除；
- 最终真实冒烟测试累计 41,270 Token，退出码 0；
- 运行日志和 Codex 会话均位于 D 盘，日志未发现密钥形态内容，结束后没有 DeepSeek 残留进程。
