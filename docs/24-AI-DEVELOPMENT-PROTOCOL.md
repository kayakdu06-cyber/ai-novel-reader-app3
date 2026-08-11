# app开发2 AI 协作开发规程

> 版本：v1.2  
> 更新日期：2026-08-09  
> 适用范围：`D:\gptuser\projects\ai-novel-reader-app2`

## 1. 目的

本文件是 Sol 与 DeepSeek 在 app开发2 中协作的统一入口。它解决四件事：

1. 新任务开始时先读取哪些事实，避免只凭对话记忆开发；
2. Sol 在交出纯文字任务前必须完成哪些前置工作；
3. DeepSeek 可以做什么、不能做什么；
4. DeepSeek 回交后，Sol 如何审查、集成和确认完成。

DeepSeek 在这里是 Codex 的纯文本编码模型，不是“织卷”App 内部的模型 Provider。两者不得混为同一项改动。

## 2. 指令与事实优先级

发生冲突时按以下顺序处理：

1. 当前系统、开发者和用户指令；
2. 仓库根目录 `AGENTS.md`；
3. 本规程；
4. `docs/ai/CURRENT-CONTEXT.md` 中的当前现场；
5. 与任务直接相关的编号文档、ADR、测试和当前代码。

`PROJECT_PLAN.md` 是早期讨论记录。它与 `docs/` 下更具体、更新的文档冲突时，不作为当前实现依据。代码存在不代表任务已经完成；完成状态以文档声明和可复核测试证据共同决定。

## 3. 每次任务的强制启动顺序

执行者在修改代码前必须完成以下读取：

1. 用 `git rev-parse --show-toplevel` 确认仓库根目录；
2. 完整读取根目录 `AGENTS.md`；
3. 完整读取本文件；
4. 完整读取 `docs/ai/CURRENT-CONTEXT.md`；
5. 读取 `docs/22-WORK-STATUS.md` 与 `docs/19-IMPLEMENTATION-BACKLOG.md` 中当前任务对应部分；
6. 按任务类型读取下面的最低资料集；
7. 搜索并阅读现有实现、测试、未提交改动和最近相关提交，确认不是从零重写已有 WIP。

最低资料集：

| 任务类型 | 必读资料 |
|---|---|
| 产品行为、流程 | `01-PRD.md`、`03-USER-FLOWS.md`、`17-ACCEPTANCE-CRITERIA.md` |
| AI 生成链路 | `06-AI-GENERATION-SYSTEM.md`、`07-API-ADAPTER-SPEC.md` |
| 架构与模块 | `08-TECHNICAL-ARCHITECTURE.md`、相关 ADR |
| 数据库与原子提交 | `09-DATA-MODEL.md`、`10-STATE-MACHINES.md` |
| 安全、密钥、联网 | `11-SECURITY-PRIVACY-BACKUP.md`、`13-ERROR-HANDLING.md`、`14-COST-CONTROL.md` |
| UI、阅读和视觉 | `04-INFORMATION-ARCHITECTURE.md`、`12-UX-READING-SPEC.md`、`design-system/default/` 中对应页面 |
| 测试与发布 | `15-TEST-PLAN.md`、`16-RELEASE-PLAN.md`、`scripts/verify-build.ps1` |

## 4. Sol 必须先完成的前置工作

在把任务交给 DeepSeek 前，Sol 必须：

1. 确认任务 ID、目标、依赖和当前完成度；
2. 把截图、图片、录屏、视觉差异等信息转成明确的文字约束；无法可靠转写的部分保留给 Sol；
3. 标出允许修改的文件或模块、禁止触碰的区域和已有未提交改动；
4. 找出已有 WIP、测试和接口，说明应延续、补齐还是重构；
5. 把安全、隐私、原子性、状态机、费用和离线限制写成不可破坏的约束；
6. 给出可执行的验收命令，以及哪些检查必须由 Sol 或 Android 模拟器完成；
7. 使用 `docs/ai/TASK-PACKET-TEMPLATE.md` 形成独立、完整的纯文字任务包。

任务包应包含完成工作所需的最小上下文，不要把整段历史对话、API Key、小说正文、个人数据或无关文件直接塞给模型。
任务包还必须声明运行预算、预计读取文件和停止条件。DeepSeek 始终使用 `max` 推理等级。用户在 2026-08-08 持续授权不设置累计 Token 上限，并在 2026-08-09 允许进一步放宽思考时长；Sol 应先拆成边界明确的子任务，通常选择 15～30 分钟硬上限，只有此前同一窄任务在 30 分钟因正常推理超时且没有权限、范围或重复失败阻塞时，才可提高到最多 45 分钟，并在新任务包中写明理由。不得为了节省 Token 私自降低推理等级，也不得用更长预算扩大任务范围。

## 5. 分工边界

| 工作 | DeepSeek | Sol |
|---|---|---|
| 已明确契约下的局部 Kotlin/Gradle/测试实现 | 可执行 | 拆分、审查、集成 |
| 搜索代码、解释调用链、补单元测试、静态审查 | 可执行 | 复核高风险结论 |
| 文档同步、任务包草拟、错误信息整理 | 可执行 | 确认事实与状态 |
| 架构取舍、跨模块状态机、数据库原子性、安全边界 | 可提出方案 | 最终决策与验收 |
| 图片、截图、录屏、视觉比较、像素级 UI 验收 | 不可执行 | 必须执行 |
| 真实设备操作、交互式 UI 检查、无障碍视觉验证 | 不可执行 | 必须执行 |
| DeepSeek 作为 app开发2 编码模型的调用 | 可执行 | 已获持续授权，负责调度和审查 |
| 密钥录入、密钥查看、App 内真实生成 API 或其他付费请求 | 不可执行 | 仅在用户另行明确授权后执行 |
| 宣布任务完成、更新正式完成状态 | 不可执行 | 依据证据确认 |

只要任务包含 DeepSeek 无法读取的输入，DeepSeek 必须在回交中列出“需要 Sol 处理的多模态事项”，不得猜测图片或界面内容。

## 6. DeepSeek 执行规则

- 只在 `D:\gptuser\projects\ai-novel-reader-app2` 内工作；不得访问或同步原项目副本。
- 开始前查看 `git status --short`，保留所有无关和用户已有改动。
- 先检查已有实现和测试，再决定修改范围；不得因状态文档较旧就删除现有 WIP。
- 只完成任务包明确授权的改动；发现需要扩展范围时停止扩展，并在回交中说明。
- 不读取、输出、提交或记录 API Key。不得把 `.codex/deepseek-key.local` 加入版本库。
- 用户已持续授权使用项目隔离的 DeepSeek V4 Flash 作为编码模型，无需逐次确认。除此之外，不调用“织卷”App 内部真实生成 API、不接入新付费服务，也不把本授权扩大到其他项目。
- 不用伪造通过记录替代实际验证；未运行的测试必须写“未运行”并说明原因。
- 不把 DeepSeek 接入 Codex 的基础设施改动，误当成 App Provider 功能实现。
- 只能通过 `scripts/start-deepseek-codex.ps1` 启动。不得直接执行未受限的 `codex exec`。
- 原生 Windows 必须启用 `windows.sandbox=unelevated` restricted-token 沙箱，否则当前 Codex CLI 会把 `workspace-write` 自动降级为 `read-only`。
- 默认沙箱为仓库 `workspace-write`，额外只允许写入 app2 的 Codex 会话目录、临时目录和 `D:\gptuser\cache\gradle`；不得把整个缓存根目录开放为可写，也不得使用 `danger-full-access` 或绕过审批与沙箱的参数。
- 同一时间只允许一个 app开发2 DeepSeek 任务运行。启动器应拒绝第二个并发任务。
- 运行日志、最终回交、会话记录、临时提示和构建缓存必须位于 `D:\gptuser`；临时提示在运行结束或中断后删除。
- 达到运行时限或累计 Token 上限时必须终止完整子进程树并保留汇总日志，不得让孤儿进程继续消耗 API。
- 看到首次明确的只读、权限或构建阻塞后停止重复证明；回交准确的单次证据，由 Sol 修复环境。

## 7. 回交格式

DeepSeek 的最终回交必须按以下顺序给出：

1. `完成内容`：实际做了什么；
2. `修改文件`：逐项列出文件和作用；
3. `验证`：命令、结果、测试数量或失败点；
4. `未完成/风险`：明确剩余工作，不使用模糊的“基本完成”；
5. `需要 Sol 处理`：架构、安全、模拟器、真实设备、图片或视觉事项；
6. `假设`：所有可能影响结果的假设。

不得只回复“已完成”，也不得用思考过程代替可核查的结果。

## 8. Sol 的回收与完成门禁

Sol 收到回交后必须：

1. 独立检查差异和未提交文件，确认没有越界或覆盖已有工作；
2. 对状态机、数据库事务、联网、密钥、日志、费用路径进行高风险复核；
3. 运行任务相关的最小测试，再按风险决定是否运行统一离线门禁；
4. UI 变更必须由 Sol 实际渲染并检查截图、横竖屏、大字体和相应可访问性要求；
5. 只有证据齐全后，才能更新 `22-WORK-STATUS.md`、`19-IMPLEMENTATION-BACKLOG.md` 和追踪矩阵；
6. 若当前现场发生变化，同步更新 `docs/ai/CURRENT-CONTEXT.md`，避免下一次任务从旧状态开始。

统一离线门禁入口：

```powershell
scripts/verify-build.ps1 -Offline
```

DeepSeek 隔离配置的无联网检查：

```powershell
scripts/start-deepseek-codex.ps1 -ValidateOnly
```

DeepSeek 的受限执行入口：

```powershell
scripts/start-deepseek-codex.ps1 `
    -TaskPacketPath docs/ai/task-packets/TASK-___-phase-_.md
```

参数、日志和退出码说明见 `docs/ai/DEEPSEEK-RUNBOOK.md`。

## 9. 当前开发方向

`TASK-061 编辑后派生失效和重建` 已于 2026-08-08 完成。Phase 1～2B3B2D 建立了原子用户编辑、多代 STALE 历史、受审计 rewind、aggregate writer、immutable execution ledger、动态 memory/tracking Stage、retirement evidence、Provider-open/commit 双门禁和首个保留章节闭环。Phase 2B3B2E 将其推广到显式 ordinal 4/6/8…：直接前驱 tracking+aggregate 与时间下界必须满足，retirement evidence 必须形成连续前缀，exact replay 不猜测下一章。

TEST-033 已以 10 章编辑第 3 章和生产上下文权威选择器关闭；双 API 数据库各 187/187、生成各 35/35，JVM 70/70 + 117/117，统一离线门禁 797 tasks、Release/R8、安全扫描与备份排除通过，详见工作汇报 106。execution 保持不可变 `PREPARED` 证据，完成性由 planner 从权威表重算；不得一次预建带假来源的后续 Stage、覆盖历史派生、删除普通顺序保护、另造并行解析器、伪造 Provider 输出或跳过 Provider 前门禁。

TASK-063 已于 2026-08-08 完成：独立 Fake Provider、虚拟分钟级慢流、断流/UNKNOWN/取消、失败可见分位数和 20 个参考 BODY 负载均有双 API 证据，详见工作汇报 109。正式下一阶段是 TASK-064 全阶段 dispatcher 与持久 total runner（Fake only）。当前 App 仍无按 phase 分发的总 runner。

当前 App 仍没有按 phase 分发的总 runner；TASK-059 的完成不等于整 App 已能自动跑完整生成流程。未来 runner 的 COMMIT_CHAPTER 分支必须只调用 `ChapterFinalCandidateCommitStageExecutorV1`。

详细现场、文件清单和未验证风险见 `docs/ai/CURRENT-CONTEXT.md`。
