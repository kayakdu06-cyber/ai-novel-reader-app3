# 工作汇报 68：修订后派生阶段生产封存接线

> 日期：2026-08-04  
> 阶段：TASK-059 第五阶段  
> 结论：本阶段完成；完整 TASK-059 仍在进行中

## 1. 本阶段完成内容

本阶段把修订成功后的新候选从“数据库能够封存”推进到“生成模块有明确生产协调入口”：已通过严格校验的章节记忆结果会封存并建立 TRACKING Stage，追踪结果会封存并建立 CONSISTENCY Stage。调用方不再需要自行拼装 Usage、候选身份和下一阶段来源。

新增 `ChapterCandidateDerivedStagePersistenceCoordinatorV1`，负责：

- 核对结构化结果、冻结请求 expectation 与当前候选版本 ID/hash/章节序号一致；
- 从已审计执行结果取得最终 Usage，不接受上层另传费用结算；
- 为下一 Stage 生成唯一的候选来源 binding；
- 调用现有 `ChapterCandidateArtifactSealRepositoryV1` 完成原子封存与 Stage 推进；
- 返回类型化 MEMORY/TRACKING 结果和数据库封存结果，供下一阶段继续装配。

## 2. 二次审计发现并修复的严重缺口

修订正文被封为新候选时会生成 `revision-result binding`。此前该指纹虽然进入新 MEMORY Stage，但 MEMORY/TRACKING 的封存接口只要求“封存草稿与下一 Stage 相等”，没有同时要求它们与当前 Stage 的冻结来源相等；调用方若漏传或换传指纹，第二轮派生链仍可能继续。

现在的规则是：

1. 修订候选的 MEMORY、TRACKING 和 CONSISTENCY 来源必须保留同一个修订结果指纹；
2. MEMORY 和 TRACKING 封存时同时比较当前来源、封存草稿和下一 Stage 来源；
3. 丢失或替换指纹会在创建后继 Stage 前失败；
4. 到 CONSISTENCY 分流点后，才允许根据完整策略输入生成新的 route binding。

专项负例证明：故意把修订后 MEMORY 的指纹改成空值时，MEMORY 保持可恢复的 `COMMITTING`，TRACKING Stage 不会创建，正式章节也不会发布。

## 3. 最终证据不再由上层手工拼装

`ChapterCandidateArtifactSealResultV1` 现在直接携带仓库生成的 `ChapterFinalCandidateArtifactEvidenceV1`。证据来自已持久化 Attempt 与实际封存草稿，包括 Stage、Attempt、artifact 版本、原始/规范 hash 和请求来源 hash。

这为后续最终提交生产入口提供了可靠材料，也减少了调用方根据 opaque commit permit 重抄字段造成错配的机会。修订 BODY 专项测试已逐字段确认返回证据与实际响应一致。

## 4. 验证结果

### JVM 与编译

- `feature:generation` 新增规划器测试：4 项通过；
- `feature:generation:testDebugUnitTest`：通过；
- `core:database` Debug 与 AndroidTest Kotlin 编译：通过。

### API 35 模拟器专项

- 设备：`emulator-5554`，Android API 35；
- `ChapterFinalCandidateCommitDatabaseTest`：17/17 通过；
- 失败 0、错误 0、跳过 0；
- 新增覆盖：修订结果指纹丢失时拒绝、无后继 Stage、Stage 保持可恢复。

测试中首次专项运行失败：第一版把指纹相等规则错误扩大到 CONSISTENCY 分流点，导致合法的“修订结果指纹→新策略指纹”转换也被拒绝。随后将校验精确收窄到 MEMORY/TRACKING 派生传递边界，重跑 17/17 通过。该失败只发生在隔离模拟器数据库，没有真实 Provider 调用或正式数据写入。

### 统一离线门禁

- `scripts/verify-build.ps1 -Offline`：通过；
- Gradle：371 项任务，0 失败；
- 安全扫描：通过；
- Android 备份排除策略：通过；
- 真实织卷 Provider API：0 次；
- 物理设备写入：0 次。

## 5. DeepSeek 使用说明

本阶段没有调用 DeepSeek。原因是上一轮正式调用在约 1,000,000 Token 上限内没有产生可审查代码差异，不能把“进程成功”视为开发成果。本阶段由 Sol 直接完成审计、实现和测试。

后续 DeepSeek 仍保持最高推理强度，但只用于边界很小、要求明确 diff 与测试结果的任务；无差异交付按失败处理。

## 6. 剩余工作

完整 TASK-059 仍不能标记完成，下一阶段重点是：

1. 把已接受的一致性报告、本地报告、场景契约和候选历史装成唯一的 `ChapterRevisionPolicyInputV1`；
2. 只有策略确实要求修订时才创建精确绑定的 revision request，并把其 source binding 写入 REVISE Stage；
3. 接受路径直接进入最终 COMMIT Stage，额度耗尽路径原子进入 NEEDS_ACTION；
4. 最终 COMMIT Stage 在进程恢复后从持久 artifact/Attempt/Stage 证据重建最终草稿，而不依赖进程内对象；
5. 最终完成 API 30/API 35 全量、Release/R8、并发/恢复/精确 replay 验收。
