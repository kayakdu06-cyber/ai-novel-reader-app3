# TASK-127 任务包：合并章后分析与原子提交

## 任务身份

- 任务 ID：TASK-127
- 仓库根目录：`D:\gptuser\projects\ai-novel-reader-app3`
- 基准分支与 HEAD：`main` / `bc301ce`
- 当前未提交改动：用户持有的 `AGENTS.md`、`.claude/`、`CLAUDE.md`，不得覆盖或提交
- 执行者：Sol；本任务暂不委派 DeepSeek

## 目标

把既有摘要、记忆、时间线、伏笔、义务、状态和一致性分析合并为一次 `chapter-post-analysis.v1` 远程响应；全部本地校验通过后，映射到现有仓库并复用现有章节原子提交。严重且可修问题最多触发有限修订，修订后必须重新分析。

## 当前现场与已有 WIP

- TASK-126 已把初稿接入 `DRAFT_CHAPTER`，并保留受保护输入来源。
- 已有 memory、tracking、consistency 三套严格输出合同及本地交叉校验。
- 已有义务和通用叙事状态本地合同，可覆盖人物、关系、道具、系统、修炼和世界状态。
- 已有候选正文严重问题路由、有限修订、修订谱系和最终章节 Room 原子提交。
- 缺口：三个章后能力仍是独立远程请求；不存在合并合同、单次调用计数和直接提交映射。

## 必读资料

1. `AGENTS.md`
2. `docs/24-AI-DEVELOPMENT-PROTOCOL.md`
3. `docs/ai/CURRENT-CONTEXT.md`
4. `docs/30-IMPLEMENTATION-BACKLOG-V2.md` TASK-127
5. `docs/18-DECISION-LOG.md` DEC-079
6. `docs/28-WRITING-POLICY-PACK-SPEC.md` 章后分析
7. 现有 memory、tracking、consistency、narrative state、revision、final commit 源码及测试

## 范围

允许修改：

- `feature/generation`：合并合同、请求、解析、本地校验、严重问题路由、计时/调用计数、测试。
- `data`：映射现有写库模型、复用原子提交、失败不部分落库、测试。
- `docs`：任务状态、工作报告、目录状态。

明确不在范围：

- UI、阅读器、真实 Provider、API 密钥、付费服务。
- 新数据库或并行状态系统。
- 与 TASK-127 无关的功能扩展和大范围重构。
- app2 或任何其他相似项目。

## 不可破坏约束

- 正常正文后只允许一次远程章后分析调用。
- 任一子块失败，当前状态不得部分写入。
- 修订只处理 BLOCKER/MAJOR 且可修问题；修订后旧分析作废并重新分析。
- 继续使用既有仓库与 Room 原子事务。
- 功能模块不得产生新直接依赖；仅保留既有 `template -> creation` 例外。
- 全程 Fake Provider；真实 Provider 调用数为零。

## 实施批次

1. `:feature:generation`：建立合并合同和严格 parser，覆盖混合正例与任一子块失败。
2. `:feature:generation`：建立单次请求、执行、严重问题路由、修订后重分析与远程调用计数。
3. `:data`：把合并结果映射到既有记忆、追踪、义务、状态、一致性和最终原子提交。
4. 集成：注册路由，验证正常调用数、无部分写入、修订谱系、边界和构建。

## 验收标准

- [x] `chapter-post-analysis.v1` 严格合同与 parser 完成。
- [x] 混合正例覆盖义务、系统、道具、关系及正文证据。
- [x] 一个子块非法时整体拒绝且没有部分状态写入。
- [x] 严重重复不提交，生成有限修订谱系，修订后重新分析。
- [x] 正常路径章后远程调用数不超过 1，并写入计时报告。
- [x] 现有最终章节原子提交复用成功。
- [x] 相关 JVM 测试、边界检查、安全扫描、`assembleDebug`、`test` 通过；本任务没有新增 schema，未重复恢复已删除的 600+ 行旧 Android fixture。
- [x] 每个完成点单独提交并推送 Git。

## 验证命令

```powershell
gradle :feature:generation:testDebugUnitTest
gradle :data:testDebugUnitTest
gradle assembleDebug test
powershell -ExecutionPolicy Bypass -File scripts/verify-module-boundaries.ps1
powershell -ExecutionPolicy Bypass -File scripts/verify-no-secrets.ps1
```

## 回交要求

每个批次记录：完成内容、修改文件、测试结果、风险与下一步。不得在缺少差异审查和验证证据时宣告 TASK-127 完成。
