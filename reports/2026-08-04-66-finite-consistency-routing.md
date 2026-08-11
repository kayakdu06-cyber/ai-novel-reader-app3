# 工作汇报 66：一致性结果的有限持久分流

> 日期：2026-08-04  
> 项目：织卷 Android 单人版  
> 阶段：TASK-059 第三阶段  
> 结论：本阶段完成；完整 TASK-059 仍在进行中

## 1. 本阶段目标

把一致性检查完成后的三种结果收拢为一条可靠、可恢复的持久流程：

1. 没有 `BLOCKER/MAJOR` 时接受当前候选，只建立本地最终提交 Stage；
2. 存在 `BLOCKER/MAJOR` 且仍有额度时，只建立一次绑定当前候选的修订 Stage；
3. 自动修订次数或 Stage attempt 额度耗尽时，原子进入 `NEEDS_ACTION`，不创建后继、不发布候选章节。

## 2. 已完成内容

### 2.1 单一决策源

- 新增 `ChapterConsistencyOutcomeRepositoryV1`。
- 仓库内部直接调用 `ChapterRevisionPolicyV1`，调用方不能先算一个结果、再另外指定接受或退修。
- `MINOR/NONE`、`BLOCKER/MAJOR`、比例模式 1 次和细写模式 2 次的规则继续由原有策略统一负责。

### 2.2 三路持久结果

- 接受：一致性 artifact 封存后创建 `COMMIT_CHAPTER`，Job 指向新 Stage。
- 自动修订：创建 `REVISE_CHAPTER`，来源绑定候选版本 ID、正文 hash、章节、revision、直接一致性前驱和策略指纹。
- 额度耗尽：一致性 Stage 从 `COMMITTING` 原子进入 `NEEDS_ACTION`；Job 同步进入 `NEEDS_ACTION`，暂停中的 Job 则安全落为 `PAUSED`；本次 Usage 同事务变成 FINAL。

### 2.3 防止“同结果、不同依据”错误重放

- `ChapterRevisionPolicyV1` 新增稳定 route binding hash。
- 指纹覆盖候选 hash 历史、正文长度、次数、Stage 上限、场景契约、全部问题及最终决策。
- 问题顺序变化不会改变指纹；问题 ID、严重度、范围、修复动作、场景或次数变化都会改变指纹。
- 指纹写入一致性封存证据和 REVISE/COMMIT 后继来源。
- 即使两次都得出“自动修订”或“额度耗尽”，只要依据不同，也拒绝当作精确 replay。

### 2.4 来源与事务补强

- MEMORY、TRACKING、CONSISTENCY 封存时再次核对当前 Stage 的冻结 candidate binding，防止封存调用偷换版本、正文 hash、章节或 revision。
- 新增带 output evidence 的 `COMMITTING → NEEDS_ACTION` 条件更新；状态、证据、租约释放、Job 原因和 Usage 保持在同一数据库事务内。
- 额度耗尽路径不插入 `ChapterVersion`，也不创建未使用的后继 Stage。

## 3. 主要修改文件

- `core/task/.../ChapterRevisionPolicy.kt`
- `core/task/.../GenerationStageStateMachine.kt`
- `core/task/.../ChapterRevisionPolicyTest.kt`
- `core/task/.../GenerationStageStateMachineTest.kt`
- `core/database/.../ChapterConsistencyOutcomeRepository.kt`
- `core/database/.../ChapterCandidateArtifactSealRepository.kt`
- `core/database/.../GenerationDao.kt`
- `core/database/.../ChapterFinalCandidateCommitDatabaseTest.kt`
- `docs/19-IMPLEMENTATION-BACKLOG.md`
- `docs/20-TRACEABILITY-MATRIX.md`
- `docs/22-WORK-STATUS.md`
- `docs/ai/CURRENT-CONTEXT.md`

## 4. 验证结果

### 4.1 JVM

- `core:task` 全量单元测试通过。
- `ChapterRevisionPolicyTest`：7/7。
- `GenerationStageStateMachineTest`：5/5。
- 证明 MINOR 不升级、有限次数不改变、策略指纹对顺序稳定且对依据变化敏感。

### 4.2 Android 15 模拟器

- 设备：仅 `emulator-5554`，API 35。
- `ChapterFinalCandidateCommitDatabaseTest`：13/13，失败 0、错误 0、跳过 0。
- 覆盖接受→COMMIT、退修→REVISE、额度耗尽→NEEDS_ACTION、精确 replay、同结果不同依据冲突、候选来源篡改、最终原子提交和回滚。

### 4.3 安全边界

- 统一离线门禁通过：371 个 Gradle task，失败 0。
- Debug APK、Release 清单、安全扫描和备份排除检查全部通过。
- 真实小说生成 API：0 次。
- DeepSeek：本阶段未调用；沿用最高推理强度配置，但避免重复此前百万级 Token 无有效差异的运行。
- 物理设备写入：0。
- 未添加 Git remote，未修改原项目副本。

## 5. 尚未完成与下一阶段

本阶段没有把完整 TASK-059 标记为完成，剩余关键工作是：

1. 把持久 REVISE 路线接到实际修订请求准备和 Provider-open 入口；
2. 修订成功后生成新候选版本标识/hash，并自动建立新的 MEMORY→TRACKING→CONSISTENCY 链；
3. 在进程恢复、租约变化和并发执行中继续校验 route binding hash；
4. 最终只允许最新一轮候选及其派生证据进入现有原子提交事务；
5. 完整 TASK-059 收尾时补齐 API 30/API 35 全量、Release/R8 和统一安全门禁。
