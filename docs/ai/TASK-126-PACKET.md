# TASK-126 任务包：initial draft exact-token 流式执行与恢复

> 状态：已完成（2026-08-12）

## 任务身份

- 仓库：`D:\gptuser\projects\ai-novel-reader-app3`
- 主模块：先 `:data`，再 `:feature:generation`
- Provider：禁止修改；只用 Fake 验收

## 目标

接通由 TASK-125 创建的唯一 initial DRAFT：冻结来源、exact-token prepare/open、流式受保护 artifact、生成中只读投影、截断续接、结束后进入 post-analysis、UNKNOWN/取消/崩溃恢复。

## 不可破坏约束

- 不创建正式 `ChapterVersion`；初稿只能进入候选章后分析链。
- 请求只引用发送前已持久化的 plan/context/policy 证据。
- 来源、计划或上下文漂移必须在 Provider-open 前失败，Provider 调用数为 0。
- UNKNOWN/取消不得自动再发或重复计费。
- 不增加模块，不修改 `:provider`，不调用真实 API。

## 最小验收

- 来源合同及三类漂移参数化负例。
- Fake 正常正文合同；结果明确为非正式并转入 memory Stage。
- 复用既有流式生命周期对截断、UNKNOWN、取消和崩溃恢复的持久化保证。
- `:data:testDebugUnitTest`、`:feature:generation:testDebugUnitTest`、边界和安全扫描。

## 完成证据

- `:data` 10/10、`:feature:generation` 28/28。
- API 35 的 UNKNOWN 恢复用例 1/1；同一 Stage 始终只有一个 Attempt，不自动再次收费。
- 来源、计划、上下文漂移在 route/Provider-open 门禁前失败，因此 Provider 调用数为 0。
- 10 模块无环；安全扫描通过；真实 Provider 调用 0。
