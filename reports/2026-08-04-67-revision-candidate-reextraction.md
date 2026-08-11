# 工作汇报 67：修订请求、新候选与重新提取交接

> 日期：2026-08-04  
> 项目：织卷 Android 单人版  
> 阶段：TASK-059 第四阶段  
> 结论：本阶段完成；完整 TASK-059 仍在进行中

## 1. 本阶段目标

把上一阶段已经持久化的 REVISE 路线继续接到真实执行边界：

1. REVISE Stage 必须冻结即将发送的精确请求输入，不能只冻结“需要修订”这个结论；
2. 修订成功后必须建立新的候选版本身份、正文 hash 和 revision；
3. 数据库必须独立确认正文长度和候选历史，不能盲信上层传值；
4. 新候选封存后自动创建并激活新的 MEMORY Stage，旧候选派生结果不得复用。

## 2. 已完成内容

### 2.1 冻结精确修订请求

- 候选 Stage 来源新增 `requestSourceBindingHash`。
- 一致性分流创建 REVISE Stage 时，除了 route binding hash，还必须写入 `BoundChapterRevisionRequestV1` 对应的精确 source binding。
- Provider-open 会把最新持久 Attempt 的 `inputHash` 与该值比较。
- 即使候选、章节和一致性前驱都没有变化，只要发送前换了另一份提示或修订计划，仍会在领取发送权之前失败。

### 2.2 新候选原子封存

- 新增 `ChapterRevisionCandidateRepositoryV1`。
- 它重新计算并核对：
  - 原一致性策略仍然授权本轮修订；
  - Stage 中的 route binding 与 request binding 未变化；
  - 新正文 hash 不等于原候选，也不回到历史候选；
  - 新候选版本 ID 不复用原候选 ID；
  - revision 恰好增加 1；
  - 候选 hash 历史与策略计划完全一致。
- 以上条件全部通过后，修订 BODY、最终 Usage、Stage 成功、Job 前移和新 MEMORY Stage 在同一事务内生效。

### 2.3 数据库二次复算正文长度

- 数据库不再只接受上层传来的 `revisedBodyCodePointCount`。
- 首次封存时重新读取加密 stream artifact，核对 artifact revision，使用严格 UTF-8 解码并复算 Unicode 码点数。
- 即使只伪造 1 个码点，也会拒绝封存；Stage 保持 `COMMITTING`，Usage 保持 PROVISIONAL，不创建 MEMORY 后继。

### 2.4 修订结果指纹

- 新增 `zhijuan.chapter-revision-result-binding.v1`。
- 指纹覆盖：原 route、精确请求、原候选 ID/hash、新候选 ID/hash、实际正文长度、完成修订次数和完整候选历史。
- 指纹同时写入修订 BODY 封存证据和新 MEMORY Stage 来源。
- artifact 已按生命周期清理后，完全相同的 replay 仍可完成；改策略、改问题或改正文长度均不能借用旧结果。

## 3. 主要修改文件

- `core/database/.../ChapterCandidateArtifactSealRepository.kt`
- `core/database/.../ChapterConsistencyOutcomeRepository.kt`
- `core/database/.../ChapterRevisionCandidateRepository.kt`
- `core/database/.../GenerationRequestAuditRepository.kt`
- `core/database/.../ChapterFinalCandidateCommitDatabaseTest.kt`
- `docs/19-IMPLEMENTATION-BACKLOG.md`
- `docs/20-TRACEABILITY-MATRIX.md`
- `docs/22-WORK-STATUS.md`
- `docs/ai/CURRENT-CONTEXT.md`

## 4. 验证结果

### 4.1 Android 15 模拟器

- 设备：仅 `emulator-5554`，API 35。
- `ChapterFinalCandidateCommitDatabaseTest`：16/16，失败 0、错误 0、跳过 0。
- 新增或扩展验证：
  - 精确请求 input 不一致时 Provider-open 拒绝；
  - 修订成功后新 BODY→新 MEMORY；
  - artifact 清理后精确 replay；
  - 同结果但策略变化拒绝；
  - replay 改正文长度拒绝；
  - 首次封存伪造正文长度拒绝且无后继。

### 4.2 JVM 与安全边界

- `core:task` 全量单元测试通过。
- 统一离线门禁通过：371 个 Gradle task，失败 0。
- Debug APK、Release 清单、安全扫描和备份排除检查全部通过。
- 真实小说生成 API：0 次。
- DeepSeek：本阶段未调用，最高推理强度配置保持不变。
- 物理设备写入：0。
- 未添加 Git remote，未修改原项目副本。

## 5. 尚未完成与下一阶段

1. 把新 MEMORY Stage 接入实际记忆提取协调器，而不是只证明数据库 Stage 链可创建；
2. 自动继续新 TRACKING 和新 CONSISTENCY，并将第二轮结果再次送入同一有限分流仓库；
3. 加入进程重启、租约恢复和并发 worker 下的完整自动链证据；
4. 最终只把最新 revision 的 BODY、MEMORY、TRACKING、CONSISTENCY 交给现有原子发布事务；
5. 完整 TASK-059 收尾时补齐 API 30/API 35 全量、Release/R8 和统一安全门禁。
