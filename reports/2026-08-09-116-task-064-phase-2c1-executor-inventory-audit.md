# 工作汇报 116：TASK-064 Phase 2C1 executor 生产入口审计

日期：2026-08-09  
项目：织卷 Android（app开发2）  
唯一项目根目录：`D:\gptuser\projects\ai-novel-reader-app2`

## 本阶段目标

在写 registry 前，逐项确认 Phase 2A 的 10 个 route 是否已经存在真正可由生产 runner 调用的闭环，避免把 Android 测试里的人工拼装误当成正式 executor。

## DeepSeek 执行

- 任务包：`docs/ai/task-packets/TASK-064-PHASE-2C1-EXECUTOR-REGISTRY-INVENTORY-AUDIT.md`
- 运行 ID：`20260809-004534-fb703b2c`
- 模式：read-only / patch-proposal-only
- 推理等级：max
- 硬上限：30 分钟；累计 Token 上限：无
- 实际耗时：约 10 分 19 秒，正常结束
- Token：总计 1,511,631；cached input 1,166,464；output 47,523；reasoning 24,471
- 任务前后 Git status 均为 209 条；没有文件改动、权限请求、构建或 Provider 调用。

## 盘点结论

| Route 组 | 当前事实 | 是否可直接接 registry |
|---|---|---|
| formal memory / tracking v1 | 远程 coordinator、审计执行器和 commit repository 分别存在，但没有一个生产入口把验证结果交给正式 commit/cursor | 否 |
| edit-rebuild memory / tracking v2 | Stage repository、远程 coordinator、commit 底层和测试都有，缺 rebuild Stage→runner→提交的生产桥 | 否 |
| candidate draft v1 | 正文流 coordinator 可执行，BODY seal 没有生产调用者 | 否 |
| candidate memory / tracking v1 | coordinator 与候选派生 seal 组合存在，但 runner 输入装配、恢复与防重复发送尚未证明 | 否 |
| candidate consistency v1 | consistency coordinator 与三路 route 组合存在，但 REQUEST/UNKNOWN/重复 route 边界尚未接总 runner | 否 |
| candidate revision v1 | 修订 coordinator 可执行，`ChapterRevisionCandidateRepositoryV1.seal` 没有生产调用者 | 否 |
| final commit v3 | local executor、coordinator、artifact recovery、原子 commit 与 replay 均存在 | 最接近，但仍不可直接接 |

## Sol 复核修正

DeepSeek 把 `FINAL_CHAPTER_COMMIT_V3` 评为唯一可以直接接线的 route。Sol 打开 `ChapterFinalCandidateCommitStageExecutorV1` 后发现仍有一个授权缺口：

- Phase 2B 返回的是 exact Job token + exact Stage token 的绑定快照；
- 现 final executor 的 public `execute` 只接收 `finalStageId + leaseOwnerId + requestedAt`；
- PREPARING/COMMITTING resume 时，它重新读取当前 persisted Stage token，只比较 ownerId；
- 如果同一 owner 的 acquiredAt 已变化，executor 会使用新 token，而不是 Phase 2B 授权的 exact token。

最终 commit repository 会验证它收到的 Stage token，但这不能弥补“executor 已换用了另一个同 owner token”的身份漂移。因此本阶段不建立 registry，也不接受 DeepSeek 的“可直接接线”结论。

## 已识别的主要缺口

1. remote route 普遍缺完整的 production adapter：从绑定快照恢复冻结输入、调用唯一 coordinator、处理 Attempt/UNKNOWN、再进入唯一 seal/commit/cursor。
2. candidate DRAFT 与 REVISION 的 seal 仅在底层/测试存在，生产会停在“远程结果已有但后继 Stage 未创建”。
3. edit-rebuild 不可降级到 formal v1；必须保留 rebuild execution/fence/retirement 证明。
4. final commit 必须先新增 exact-token bound executor 入口，不能只凭相同 owner resume。

## 验证

- Sol 重新阅读 final executor、executor JVM 测试、final coordinator、final commit repository 的 token/status/commit 路径。
- 限定 `rg` 确认 memory/tracking formal commit、candidate draft/revision seal 没有 feature 生产调用点。
- Git remote 仍为空。
- 真实 Provider：0；Fake Provider：0；物理设备写入：0。
- 本阶段是只读审计，不运行 Gradle，不把此前测试数字冒充本阶段新证据。

## 下一步

为 `ChapterFinalCandidateCommitStageExecutorV1` 增加 exact-token bound 入口：只接受 PREPARING/COMMITTING、persisted token 与 Phase 2B snapshot 完全相等、时间单调，并把同一个 token交给唯一 final coordinator。新增错误 acquiredAt、同 owner 新 token、状态变化和零 commit 测试；通过后再建立只接受 `GenerationRunnerCurrentStageRouteSnapshot` 的最小 registry。
