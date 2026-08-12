# TASK-130 工作汇报：普通生成启动入口

> 日期：2026-08-13
>
> 结论：完成
> 项目：`D:\gptuser\projects\ai-novel-reader-app3`

## 完成内容

- `:core`：冻结 `GenerationStarter` 请求、幂等键、预算上限和可映射失败合同。
- `:data`：同一快照只创建一个入口 Job；补齐初始规划和首个 8 章窗口的 route、来源、提交与连续准备；无 schema 变化。
- `:feature:generation`：复用唯一持久 runner，按“初始规划 → 首窗 → 第一章”有界推进；v2 窗口逐章保存目标、能力提示、义务和禁止重复项，并冻结章计划权威来源。
- `:feature:creation`：确认页显示当前连接、模型、规范化目的地、章节规模、未知价格和三层 token 上限；失败后允许直接重试。
- `:app`：确认一次后调用合同并进入最小生成中页；连接、模型、快照、目的地或预算不一致时留在确认页显示中文错误。

## 重要修复

- 修复初始规划请求误落到章节正文审计路径的问题。
- 修复 arc-window v2 被按 v1 canonical payload/hash 提交的问题。
- v1 只读兼容保持原节点形状，不被 v2 字段污染。
- 连接快照增加纯字符串协议标识，避免 `:app` 反向依赖 Provider 实现。
- 失败提示不再永久锁死确认按钮。

## 验证证据

- `:app:assembleDebug test --offline`：通过。
- JVM：197/197，0 失败、0 错误、0 跳过。
- API 35：入口 Room 交接 2/2；App 创建链路 4/4；确认页交互 4/4。
- 模块边界：10 模块、依赖无环、App 生产依赖仅 feature；唯一既有例外仍为 `template → creation`。
- 安全扫描：`SECURITY_SCAN_OK`，5 个构建产物。
- 真实 Provider 调用：0。
- GitNexus：改动风险为 `critical`，涉及 DAO、runner 和启动链；上述全量 JVM、定向 Android、边界及安全验证均通过。

## APK

- 文件：`app/build/outputs/apk/debug/app-debug.apk`
- 大小：51,071,882 bytes
- SHA-256：`68589CDD73462883084C0EBA54FC3DBCD4394383F32068BABF1E488B7224AEBB`

## Git 与遗留风险

- TASK-130 已按 data、generation、creation、app 分批提交。
- GitHub 推送暂因本机到 `github.com:443` 连接重置而失败；本地提交完整，恢复网络后只需 `git push origin main`。
- 未纳入用户/其他工具的 `AGENTS.md`、`.claude/`、`CLAUDE.md` 变化。
- 当前生成中页仍是最小状态页；书架、目录、正文和生成中投影由 TASK-131 完成。
