# TASK-059 第十阶段 B4：COMMIT_CHAPTER 专用 Stage 执行入口

## 任务身份

- 任务 ID：`TASK-059 / Phase 10B4 / final commit Stage executor`
- 仓库根目录：`D:\gptuser\projects\ai-novel-reader-app2`
- 基准分支与 HEAD：`main` / `8ce774429da1c3f7139a221bc241c34d81a2efdd`；保留全部未提交 WIP。
- 执行模型：DeepSeek V4 Flash，纯文本，允许在严格单文件范围内写代码。

## 运行预算

- 推理等级：`max`。
- 最长运行时间：15 分钟。
- 累计 Token 上限：不设置（用户明确要求；本任务完成即停）。
- 预计读取：强制入口、本任务包和 4 个明确生产文件；不要读取 Android 大测试、报告或整仓文档。
- 预计命令：一次 `git status --short`、必要的定点搜索、最多一次 `:feature:generation:compileDebugKotlin --offline`。
- 提前停止：需要修改第二个生产文件、需要 app 前台服务/总调度器/Room schema/DAO/状态机改动、遇到权限阻塞，或契约无法用现有公开 API 实现。

## 目标

新增 `COMMIT_CHAPTER` 专用的本地 Stage 执行入口。它负责观察当前 Stage 状态，在 READY 时以调用方给定 owner 安全领取 Stage lease，在 PREPARING/COMMITTING 时只允许同一持久 lease owner 恢复，然后调用上一阶段唯一的 `ChapterFinalCandidateCommitCoordinatorV1`。

当前 App 没有实际按 `GenerationPhase` 分发工作的总 runner；前台服务只做通知、控制和监看。本任务不得伪装接入不存在的总 runner，也不得修改前台服务。它只交付未来总 runner 可调用的严格单阶段入口。

## 当前现场与已有 WIP

- `GenerationStateRepository.findStage` 返回 `StoredGenerationStageState?`。
- `GenerationStateRepository.acquireStageLease(stageId, leaseOwnerId, now)` 只允许 READY→PREPARING，并返回带 `GenerationLeaseToken(ownerId, acquiredAt)` 的持久状态。
- `ChapterFinalCandidateCommitCoordinatorV1.commit(stageId, leaseToken, requestedAt)` 只接受 PREPARING/COMMITTING；它负责 v3 恢复、artifact、mapper、有限策略、PREPARING→COMMITTING 和最终事务。
- 协调器明确拒绝 READY/SUCCEEDED，并在 SUCCEEDED 时不读取 artifact。
- 维护流程负责租约过期分类；本执行器不得抢占、重领或猜测另一个 owner 的租约。

## 必读资料

1. `AGENTS.md`
2. `docs/24-AI-DEVELOPMENT-PROTOCOL.md`
3. `docs/ai/CURRENT-CONTEXT.md`
4. 本任务包
5. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationStateRepository.kt`
6. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationLease.kt`
7. `core/task/src/main/kotlin/app/zhijuan/core/task/GenerationStageStateMachine.kt`
8. `feature/generation/src/main/kotlin/app/zhijuan/feature/generation/ChapterFinalCandidateCommitCoordinator.kt`

除上述清单和直接引用类型外，不得递归读取日志、密钥、原项目、前台服务、Android 大测试或无关生成阶段。

## 范围

允许新增且只允许修改：

- `feature/generation/src/main/kotlin/app/zhijuan/feature/generation/ChapterFinalCandidateCommitStageExecutor.kt`

明确不在范围：

- 任何现有文件、测试、文档、app 前台服务、总 runner、Room schema/DAO、Provider、网络、UI、原项目。

## 不可破坏的约束

- 只操作 app2；保留全部已有未提交改动。
- 纯本地：不得联网、调用模型、建立 Attempt/Usage 或打开 Provider。
- 不复制 final coordinator 的恢复、映射、策略或事务逻辑。
- 不自动 reclaim、heartbeat、替换或偷取已有租约；租约过期仍由既有维护/最终门禁处理。
- 不从 PENDING/BLOCKED/NEEDS_ACTION/PAUSED/FAILED/CANCELLED 等状态猜测推进。
- SUCCEEDED 只返回“已完成”观察结果，不调用 coordinator、不读取 artifact。
- 错误和新增 `toString` 不回显 Stage ID、owner、hash、正文、JSON 或模型快照。
- 不捕获并吞掉 stale/lease/commit 异常。

## 实施要求

1. 新增内部可测试依赖对象，字段只需：
   - `findStage: suspend (String) -> StoredGenerationStageState?`
   - `acquireStageLease: suspend (String, String, Long) -> StoredGenerationStageState`
   - `commitFinalCandidate: suspend (String, GenerationLeaseToken, Long) -> ChapterFinalCandidateCommitResultV1`
2. 新增公开生产类 `ChapterFinalCandidateCommitStageExecutorV1`：
   - internal 构造函数接收上述依赖对象，便于 Sol 后续写 JVM 测试；
   - public 构造函数接收 `GenerationStateRepository` 和 `ChapterFinalCandidateCommitCoordinatorV1`，用 method reference/lambda 适配，不新增业务逻辑。
3. 暴露：

```kotlin
suspend fun execute(
    finalStageId: String,
    leaseOwnerId: String,
    requestedAt: Long,
): ChapterFinalCandidateCommitStageExecutionResultV1
```

4. 新增 sealed result：
   - `Committed(result: ChapterFinalCandidateCommitResultV1)`；覆盖 `toString`，只输出 `replayed` 与 revisionIndex 等非标识摘要；
   - `AlreadySucceeded` data object。
5. 输入必须使用 `[A-Za-z0-9._:-]{1,128}` 校验 finalStageId 和 leaseOwnerId，requestedAt 非负；错误不拼接输入。
6. 先 `findStage`，不存在失败关闭。按观察状态：
   - READY：调用 `acquireStageLease(finalStageId, leaseOwnerId, requestedAt)`；严格要求返回 stageId 相同、status=PREPARING、leaseToken 非空且 owner 相同、acquiredAt=requestedAt、leaseHeartbeatAt=requestedAt、updatedAt=requestedAt。
   - PREPARING 或 COMMITTING：不得 acquire；取持久 `leaseToken`，要求 owner 与 leaseOwnerId 相同，leaseHeartbeatAt 非空，且 requestedAt 不早于 updatedAt 和 heartbeatAt。
   - SUCCEEDED：立即返回 `AlreadySucceeded`。
   - 其他状态：失败关闭。
7. READY/PREPARING/COMMITTING 得到 token 后，只调用 `commitFinalCandidate(finalStageId, token, requestedAt)`，包装为 `Committed` 返回。
8. 不在执行器内检查 phase；final coordinator 的恢复仓库会以数据库证据验证确为 COMMIT_CHAPTER。不要为此增加新的 DAO 或扩大状态快照。

## 验收标准

- [ ] 只新增授权的一个 Kotlin 文件。
- [ ] READY 精确领取一次；PREPARING/COMMITTING 只恢复同 owner token；SUCCEEDED 零提交。
- [ ] 任何不一致状态、租约证据或倒退时间都不会调用 final coordinator。
- [ ] 不联网、不 heartbeat/reclaim、不复制业务逻辑、诊断脱敏。
- [ ] Kotlin 编译通过，或准确报告唯一编译阻塞。

## 验证命令

```powershell
.\gradlew.bat :feature:generation:compileDebugKotlin --offline --no-daemon --console=plain
```

不运行 JVM/Android 测试和统一门禁；由 Sol 在审查和补测试后执行。

## 回交格式

请严格按以下标题返回：

1. `完成内容`
2. `修改文件`
3. `验证`
4. `未完成/风险`
5. `需要 Sol 处理`
6. `假设`

不要宣布完整 TASK-059 完成，不要更新状态文档。
