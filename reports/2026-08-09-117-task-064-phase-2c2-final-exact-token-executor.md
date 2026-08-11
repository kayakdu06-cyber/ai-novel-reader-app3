# 工作汇报 117：TASK-064 Phase 2C2 final exact-token executor

日期：2026-08-09  
项目：织卷 Android（app开发2）  
唯一项目根目录：`D:\gptuser\projects\ai-novel-reader-app2`

## 本阶段目标

修复 Phase 2C1 发现的最后一个 final commit 接线缺口：total runner 已经从数据库取得 exact Stage token，final executor 不能只凭相同 owner 重新选择另一个 token。

## 完成内容

在 `ChapterFinalCandidateCommitStageExecutorV1` 新增 `executeBound`：

- 输入为 finalStageId、Phase 2B 绑定的 exact Stage token 和 requestedAt；
- 只接受 PREPARING 或 COMMITTING；
- persisted token 必须与传入 token 完整相等，包括 ownerId 和 acquiredAt；
- 不接收 READY，不 acquire 新租约；
- requestedAt 不得早于 Stage updatedAt/heartbeat；
- `requestedAt - heartbeat >= 60 秒` 时已过期，在 coordinator 前失败；
- SUCCEEDED 只返回 `AlreadySucceeded`，不读取 artifact、不 commit；
- 成功时把原样 exact token 交给唯一 `ChapterFinalCandidateCommitCoordinatorV1`。

旧 `execute(stageId, ownerId, at)` 保持兼容，但未来 total runner registry 不允许调用它。

## 测试

- 新增 4 个 JVM 用例：
  1. PREPARING/COMMITTING 使用调用方 exact token，trace 只有 find+commit；
  2. owner 相同但 acquiredAt 从 40 变 41，零 commit；
  3. 60,000ms 超时临界和 READY，零 commit；
  4. SUCCEEDED 只读 replay，零 acquire/commit。
- executor 定向：12/12。
- `feature:generation` JVM 全量：129/129，0 失败、0 跳过。
- API 35 generation Android：39/39。
- API 30 generation Android：39/39。
- `SECURITY_SCAN_OK`。
- `git diff --check`：0。
- Git remote：为空。
- 真实 Provider：0；Fake Provider：0；物理设备写入：0。

## 设计结论

“同 owner”只能证明名称相同，不能证明还是原租约。租约身份是 `ownerId + acquiredAt`。total runner 后续所有 executor adapter 都必须保留 Phase 2B 快照里的 exact token，不能重新获取或换用当前 token。

## 未完成

- 最小 executor registry 尚未创建。
- 其余 9 个 remote route 仍未注册，也没有被意外启用。
- 完整 Fake 第一章、多阶段循环、全 phase timing、统一 Release/R8 尚未完成。
- TASK-064 保持进行中。

## 下一步

建立最小有限 registry：public 入口只接受 `GenerationRunnerCurrentStageRouteSnapshot`；`FINAL_CHAPTER_COMMIT_V3` 映射到 `executeBound` 并传递快照中的 exact Stage token，其余 9 个枚举逐项显式返回“未注册”失败，不提供 default/generic fallback。
