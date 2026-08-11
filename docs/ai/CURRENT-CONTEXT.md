# app开发3 当前 AI 交接现场

> 更新时间：2026-08-11
> 唯一项目根目录：`D:\gptuser\projects\ai-novel-reader-app3`

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
- Phase 3–8 尚未完成；最终事实以 `APP3-MODIFICATIONS-2026-08-11.md` 更新为准。

## Git 同步规则

每个 Phase 只有在相关编译/测试通过并更新阶段记录后才算完成；完成后单独提交并立即推送 app3 远程。失败的中间状态不得写成完成。
