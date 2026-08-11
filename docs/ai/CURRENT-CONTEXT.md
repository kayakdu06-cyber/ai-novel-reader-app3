# app开发3 当前 AI 交接现场

> 更新时间：2026-08-11
> 唯一项目根目录：`D:\gptuser\projects\ai-novel-reader-app3`

## 2026-08-11 开发路线重排

- 当前权威总规划：`docs/27-DEVELOPMENT-MASTER-PLAN-V3.md`。
- 创作策略与组合能力：`docs/28-WRITING-POLICY-PACK-SPEC.md`。
- 第一份可亲手验证 APK：`docs/29-HANDS-ON-VERTICAL-SLICE-SPEC.md`。
- 当前权威任务顺序：`docs/30-IMPLEMENTATION-BACKLOG-V2.md`。
- 真实 API 测试：`docs/31-REAL-API-QUALITY-PERFORMANCE-TEST-PLAN.md`。
- `docs/25-*`、`docs/26-*` 和旧 TASK-064 任务包保留为历史输入，不再单独决定下一步。
- TASK-064 已有 plan、预算、目的地、route 和 exact-token WIP 必须复用；剩余收口分配到 TASK-125～128，禁止从零重写。
- 唯一下一任务是 TASK-121：只在 `:core` 增加最小 WritingPolicyPack/fragment 纯 Kotlin 合同。
- TASK-121 已进一步收窄：只允许修改 `:core`，不做 data、Provider、UI、migration、运行时来源管理或第三方 skill 安装。
- 后续每个任务必须锁定主模块和允许配套模块；VS-1 不新增第十一个模块，feature 不新增实现依赖，`:app` 不写业务逻辑。
- 不主动实现候选功能、通用扩展点或“以后可能有用”的抽象；只有不修会直接造成不稳定、数据损坏、费用失控、安全问题或严重 bug 时才扩大范围。
- 测试按具体风险最小化；全量、双 API、Release/R8 和 APK 扫描只在里程碑、发布或对应高风险变化时运行。
- 真实 API 测试收敛为连接证据、一个结构 smoke、一个“细写”档虚构成年人最高敏感度完整章和同书连续 3–5 章；A/B 仅在小说逻辑、敏感场景合同或速度出现回归时触发。
- 真实试写不评价文笔、辞藻、文风美感、文学感染力或继续阅读意愿，只验计划兑现、因果、重复、人物/关系/道具/机制/时间线状态、敏感场景合同、返回完整性和性能。
- 外部小说写作 skill 只能经过提炼后成为不可执行、可版本化的内置创作策略包；App 首版不安装或执行任意第三方 skill。
- 用户题材、背景、关系、机制、叙事结构和文风输入不设封闭清单；文档中的修仙、恋爱、系统等都只是示例。预设仅为快捷入口，未知自由题材必须原样保存并进入规划，不能拒绝、丢弃或覆盖成默认类型。
- BookCapabilityManifest 只记录需要确定性长期状态校验的内部适配器，不是题材白名单；没有专用适配器的内容继续使用通用叙事、人物连续性、叙事义务和证据语义。开放输入不引入动态插件或运行时 skill。
- 在 TASK-129 Fake 3–5 章、TASK-130 启动接线、TASK-131 最小书架/阅读器通过前，不调用 App 内真实 Provider。
- TASK-132 起按 31 号文档使用 DeepSeek V4 Flash 进行真实合同、小说逻辑、最高敏感度场景合同和速度测试。
- 当前工作树还有用户/其他工具产生的 `AGENTS.md`、`.claude/`、`CLAUDE.md` 变化；后续提交不得误纳入或覆盖，除非用户另行明确要求。

## 当前任务

- 以 app开发2 提交 `89ec64da146b27d6186ef71c32c5b1a565e2f52c` 为 Git 历史基线。
- 已把只读输入 `C:\Users\du\Downloads\ai-novel-reader-slim (2).zip` 的 `ai-novel-reader-slim` 项目内容同步为 app3 工作树。
- 按 `docs/ai/inputs/MODULARIZATION-PLAN.md` 的 Phase 1–8，把输入的 `app / engine / data` 三模块重构为明确列出的十模块结构。
- 不修改 app2，不调用织卷 App 内真实 Provider，不接入付费服务。

## 模块数量裁决

方案文字声称目标为 11 模块，但代码块和职责章节实际只列出十个：

1. `:core`
2. `:data`
3. `:provider`
4. `:feature:connection`
5. `:feature:creation`
6. `:feature:generation`
7. `:feature:reader`
8. `:feature:library`
9. `:feature:template`
10. `:app`

本任务按这十个明确模块执行，不额外制造第十一个空模块。`template -> creation` 是方案唯一允许的 feature 依赖例外。

## 启动规则

每次修改、构建或测试前确认：

```powershell
$actual = (git rev-parse --show-toplevel).Trim()
if ($actual -cne 'D:/gptuser/projects/ai-novel-reader-app3') { throw "WRONG_ROOT:$actual" }
```

然后完整读取：

1. `AGENTS.md`
2. `docs/24-AI-DEVELOPMENT-PROTOCOL.md`
3. 本文件
4. `docs/ai/APP3-MODIFICATIONS-2026-08-11.md`
5. `docs/ai/inputs/MODULARIZATION-PLAN.md`

## 当前状态

- app3 独立 Git 根目录已创建，app2 remote 已移除。
- 精简包 CHANGELOG 已审查：Step 5 没有删除安全层；Step 6 声称删除七个未引用文件，后续以实际编译和引用审计为准。
- app2 的旧上下文和交接已分别归档为 `CURRENT-CONTEXT-APP2-HISTORY.md`、`HANDOFF-APP2-2026-08-11.md`，只能作为历史，不是 app3 操作指令。
- Phase 0 已完成：修复精简包遗漏后，三模块基线 `assembleDebug test` 成功，JVM 123/123。
- Phase 1 已完成：`:core` 是无 Android/无项目依赖的纯 Kotlin 模块，model 与 task/状态机不再编译在 `:data` 内。
- Phase 2 已完成：Provider common、OpenAI Chat、transport/stream、capability storage 与 Fake 全部集中到 `:provider`，旧 app/data/engine 不再包含 provider 源文件。
- Phase 3 已完成：连接网关、连接列表和首次引导已迁入 `:feature:connection`；跨功能导航测试仍由 app 壳验证。
- Phase 4 已完成：创建/标准化/费用确认已迁入 `:feature:creation`，该模块不依赖 connection 实现，app 负责最小输入映射。
- Phase 5 已完成：旧 `engine` 与 app 内生成 Service/Worker/控制边界合并为 `:feature:generation`，旧 `engine` 目录已消失。
- Phase 6 已完成：core 内已有实际使用的连接/生成契约与未来书架契约，连接和生成模块通过 Hilt 绑定实现。
- Phase 7 已完成：reader/library/template 均为有生产实现和独立测试的模块；模板到 creation 是唯一 feature 依赖例外，书架通过 data 只读门面实现 core 契约。
- Phase 8 已完成：app 生产依赖只指向 6 个 feature，Activity 注入接口而非具体网关；调试探针所需 data/core 被限制在 debug configuration。
- 十模块边界校验通过：无空 feature、无跨目录 sourceSet、core 无 Android import、依赖无环；唯一 feature 例外是 template→creation。
- 最终强制重跑 `assembleDebug test --rerun-tasks` 成功：361/361 tasks，JVM 130/130；源码与最终 APK 安全扫描通过。
- 最终 APK：`app/build/outputs/apk/debug/app-debug.apk`，SHA-256 `692bb864c4aab2c83706ea322c06d142329fb14963c9a22770a054524793332b`。
- 公开 GitHub 远端已创建并推送：`https://github.com/kayakdu06-cyber/ai-novel-reader-app3`，本地 `main` 跟踪 `origin/main`。
- 可恢复 ZIP 已完成并校验：`D:\gptuser\backups\ai-novel-reader-app3\2026-08-11\ai-novel-reader-app3-2026-08-11-verified.zip`，SHA-256 `7e003321a6c8c165335c813a5a88b824e0dbbc87bda6ee95986c1c72b8a9c8f3`。
- ZIP 含完整 `.git` 历史、源码、验证文档、Debug APK 和三份最终日志；恢复基线为 `ace038d`。ZIP 自身哈希以 sidecar 和本工作树最终元数据提交记录。
- Phase 0–8、最终验证、公开远端和备份交付均已完成；后续开发以 `APP3-MODIFICATIONS-2026-08-11.md` 为事实入口。

## Git 同步规则

每个 Phase 只有在相关编译/测试通过并更新阶段记录后才算完成；完成后单独提交并立即推送 app3 远程。失败的中间状态不得写成完成。
