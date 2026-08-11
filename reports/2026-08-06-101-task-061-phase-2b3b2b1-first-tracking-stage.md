# 工作汇报 101：TASK-061 Phase 2B3B2B1 第一 tracking Stage

> 日期：2026-08-06  
> 项目：织卷 Android App  
> 唯一仓库：`D:\gptuser\projects\ai-novel-reader-app2`  
> 状态：本子阶段完成；TASK-061 仍进行中

## 1. 本阶段结果

已经把 schema v14 不可变重建账本从“首个 edited-memory Stage”推进到“第一章 tracking Stage”：

1. tracking 只有在 memory 前驱可证明成功后才会创建；
2. 普通 tracking 的章节顺序保护没有删除或全局放宽；
3. 只有绑定同一 execution、stable fence、ordinal 2 和完整 current 范围的 TASK-061 专用许可，才可在保留后续已提交正文时读取 tracking 来源；
4. tracking Stage 使用确定性 Job/Stage ID、严格 v2 input binding 和 input hash，支持精确 crash replay；
5. Provider-open 与 tracking commit 都会重新验证专用许可和权威来源；
6. Fake Provider 端到端已经实际证明：绑定 memory Stage 完成请求审计、流式结果、严格解析、Attempt、FINAL Usage 和原子提交后，tracking 才能解锁。

本阶段没有把 tracking 输出提交、aggregate 推进、后续章节循环或 TEST-033 冒充为已完成。

## 2. 关键安全与可靠性决策

### 2.1 memory 前驱不能只看 summary 是否存在

准备时已有且记录为 `SATISFIED` 的 memory，必须保持准备时完整全字段指纹。

准备时为 `PENDING` 的 memory，必须同时满足：

- 确定性 memory Job 为 `COMPLETED`；
- 确定性 memory Stage 为 `SUCCEEDED`；
- 最新 Attempt 为 `SUCCEEDED` 且 output hash 对应；
- Usage 为 `FINAL` 且已结算；
- Stage output reference 是严格 schema；
- output reference 的版本、正文 hash、summary ID、event/fact 数量与权威表一致。

因此，其他代码直接插入一条 summary 不能偷偷跳过 Provider 审计。

### 2.2 普通顺序保护继续有效

普通 tracking 仍会拒绝“当前章之后已经存在已提交章节”的情况。TASK-061 专用 loader 只在 rebuild binding、stable fence、memory 前驱、完整 current 影响范围和当前计划 blocker 全部通过后使用。

这避免了为解决编辑重建而把全 App 的章节顺序安全规则拆掉。

### 2.3 没有新增并行 parser 或 schema

普通与重建 tracking 共用现有 factory、严格 parser、source repository、Provider 审计和 commit repository。重建只增加 v2 binding 与专用授权分支；Room schema 继续为 v14。

## 3. 代码变更

- `core/database/.../ChapterEditRebuildStageRepository.kt`
  - 新增 `createFirstTrackingStage`；
  - 验证 prepared-SATISFIED 或真实 bound-memory 成功证据；
  - 创建确定性 ordinal 2 tracking Job/Stage；
  - 支持 tracking Provider-open/commit 重建授权。
- `core/database/.../ChapterTrackingProjectionJobFactory.kt`
  - 普通 tracking 保持 schema v1；
  - rebuild tracking 使用严格 schema v2；
  - 新增专用来源读取与来源复核；
  - source guard 根据经过授权的 binding 选择普通或重建路径。
- `core/database/.../ChapterTrackingProjectionCommitRepository.kt`
  - 业务写入前调用 TASK-061 commit 授权；
  - rebuild Stage 使用专用来源复核，普通 Stage 继续使用原顺序保护。
- `core/database/.../ChapterEditRebuildExecutionRepository.kt`
  - 准备基线 fingerprint helper 供 Stage 授权复用，避免复制哈希算法。
- 测试：
  - `ChapterTrackingProjectionJobFactoryTest`；
  - `ChapterEditRebuildPlanDatabaseTest`；
  - `ChapterMemoryExtractionEndToEndTest`。

## 4. 验证证据

### 4.1 JVM

- `core/database`：67/67；
- `feature/generation`：117/117；
- 0 失败、0 错误、0 跳过。

### 4.2 Android API 30 / API 35

- `ChapterEditRebuildPlanDatabaseTest`：每套 15/15；
- `ChapterMemoryExtractionEndToEndTest`：每套 4/4；
- `core/database` 全量：每套 178/178；
- `feature/generation` 全量：每套 29/29；
- 0 失败、0 错误、0 跳过。

测试只使用项目模拟器：

- API 30：`emulator-5556`；
- API 35：`emulator-5558`。

没有向物理设备安装或写入。

### 4.3 静态与安全检查

- `scripts/security-scan.ps1 -SkipArtifacts`：`SECURITY_SCAN_OK`；
- `git diff --check`：通过，仅有仓库既有 CRLF 提示；
- App 内真实 Provider 调用：0；
- 新付费请求：0；
- Git remote：仍为空。

本子阶段没有运行统一 797-task Release/R8 离线门禁，不能沿用前一阶段数字冒充本阶段证据。统一门禁留在 TASK-061 更完整闭环后执行。

## 5. DeepSeek 使用说明

本子阶段没有再次调用 DeepSeek。上一子阶段运行 `20260806-054907-37278d3b` 已在约 100 万 Token 后停止且没有代码差异；当前工作又涉及跨 memory/tracking/commit 的事务与安全边界。为了避免重复消耗而没有可审查产出，本段由 Sol 直接实现、审查并在双 API 验证。

## 6. 尚未完成与下一阶段

下一阶段是 Phase 2B3B2B2：

1. 用 Fake Provider 实际产生并提交第一 tracking 输出；
2. 在 tracking 成功的同一事务边界调用既有 `AggregateStateWriterRepository`；
3. 证明 tracking 成功但 aggregate 失败时不会留下半完成状态；
4. 证明精确 replay 不重复 tracking、伏笔 transition、revision、FTS 或 aggregate；
5. 再处理后续保留章节旧 tracking 基线的受控失效和逐章循环。

TASK-061 只有在上述链、TEST-033、双 API 全量和统一 Release/R8 门禁全部通过后才能收口。
