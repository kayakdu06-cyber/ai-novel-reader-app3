# 工作汇报 118：TASK-064 Phase 2C3 最小有限 executor registry

日期：2026-08-09  
项目：织卷 Android（app开发2）  
唯一项目根目录：`D:\gptuser\projects\ai-novel-reader-app2`

## 本阶段目标

把 Phase 2B 的数据库绑定快照和 Phase 2C2 的 final exact-token executor 接成第一个可验证 registry，同时确保尚未具备完整生产闭环的九条远程路线不会被误启动。

## 完成内容

新增 `GenerationRunnerExecutorRegistryV1`：

- 公开入口只接受 `GenerationRunnerCurrentStageRouteSnapshot`，不接受裸 route、StageId 或手工租约；
- 分发前再次检查 Job/Stage 状态、same-owner、时间单调和双 lease 时效；
- 注册集合严格只有 `FINAL_CHAPTER_COMMIT_V3`；
- final 分支把 Phase 2B 快照中的 exact Stage token 原样传给 `executeBound`，不重新领取 Stage、不调用 owner-only 入口；
- 其余九条 remote route 在穷举 `when` 中逐项抛出 `GenerationRunnerRouteNotRegisteredException`；
- 未注册异常只含有限 route enum，不含 Job、Stage、owner、正文、prompt、连接或 secret。

新增有限返回类型 `GenerationRunnerRegisteredExecutionResultV1`。其日志字符串沿用 final executor 的脱敏结果，不暴露 Stage 或 owner fixture。

## 审查结论

本阶段没有把“route 能识别”误当成“route 可安全执行”。只有已经具备 exact-token、恢复、replay 和原子提交闭环的本地 final commit 被注册。memory、tracking、candidate draft/revision/derived/consistency 都继续失败关闭。

registry 使用的 Job 快照可能在返回后变旧，因此它先检查快照时效；final bound executor随后重新读取权威 Stage 并复核 exact token，final repository再检查 Job/currentStage 与提交条件。现有状态机不允许在保留同一 exact Stage token 的同时合法替换 Job token，因此本地 final 切片没有出现 Job 授权漂移旁路。

## 测试与门禁

- JVM 新增 2 项，`feature:generation` 全量 131/131。
- Android real Room 新增 2 项：
  1. final route 原样传递 exact token，且 READY acquire 零调用；
  2. 普通 memory route 在 executor 和状态写入前失败。
- 首轮定向 Android 出现 JUnit `initializationError`：测试 `@Before` 使用表达式体，意外把 `BookCreationRepository.create` 的返回值暴露成非 Unit。改成显式 block/Unit 后 2/2；生产代码没有修改或放松。
- API 35 generation 全量：41/41，0 失败、0 错误、0 跳过。
- API 30 generation 全量：41/41，0 失败、0 错误、0 跳过。
- `scripts/verify-build.ps1 -Offline`：801 actionable tasks，Debug/Release、Lint/Vital、R8 全通过。
- 安全扫描器自测通过；源码和 5 个 APK 为 0 疑似密钥命中；备份排除通过。
- `git diff --check`：0；新增文件尾随空白检查：0；Git remote：为空。
- App 内真实 Provider：0；Fake Provider：0；物理设备写入：0。

## DeepSeek 使用

本阶段没有调用 DeepSeek。Phase 2C1 的 DeepSeek 只读审计已经完成入口盘点；本阶段改动集中在租约授权和 registry 白名单，Sol 直接实现并完成双 API 与统一门禁验收。用户允许延长 DeepSeek 思考时长的授权继续保留，后续边界明确的 candidate adapter 子任务可使用 max 推理与 15～30 分钟硬上限。

## 未完成

- TASK-064 整体仍为进行中。
- 九条 remote route 尚未注册；这不是缺陷，而是当前安全边界。
- candidate draft 尚缺唯一生产 adapter、请求输入装配、artifact seal、exact-token 恢复和防重复发送证明。
- planning/context/普通 draft route、多阶段循环、全 phase timing 和完整 Fake 第一章尚未完成。

## 下一步

先审计并实现 candidate draft 的最小生产 adapter：只使用 Fake Provider，保留 Phase 2B exact token，复用现有请求审计、流式 executor 和 artifact seal，并证明崩溃恢复时不会重复发送。通过独立测试后再决定是否把 `CANDIDATE_CHAPTER_DRAFT_V1` 加入 registry。
