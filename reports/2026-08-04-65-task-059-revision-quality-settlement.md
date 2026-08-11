# 工作汇报 65：TASK-059 修订质量失败原子结算

日期：2026-08-04

## 结论

TASK-059 第二阶段的第一块已完成：修订正文已经由 Provider 成功返回，但因“正文过短、正文未变化、回到旧候选形成循环”而不能继续时，系统会把 Stage、Job 和 Usage 在同一数据库事务中结算为可解释的 `NEEDS_ACTION` 现场，并保持正式章节未发布。

相同结果可以精确重放；如果重放时偷偷换成另一种失败原因，事务会拒绝，不能改写已经向用户暴露的状态解释。

## 已完成修改

- `ChapterRevisionOutcomeRepository` 只接受三种响应后质量失败：
  - `REVISED_BODY_BELOW_MINIMUM`
  - `REVISED_CANDIDATE_UNCHANGED`
  - `REVISED_CANDIDATE_CYCLE`
- 自动修订次数耗尽和 Stage attempt 耗尽属于请求前决策，不能借“成功响应结算”入口伪造。
- 结算前生成唯一稳定原因 `CHAPTER_REVISION:<reason>`，Job 与 Stage 同事务进入 `NEEDS_ACTION`，Usage 同事务转为 `FINAL`。
- 精确重放必须同时匹配 Stage/Job 状态、最终 Usage 和已持久化原因。
- 冲突原因重放失败关闭，不覆盖原原因。

## 测试证据

在现有真实 Room/受保护 artifact 候选链测试中新增完整修订响应场景：

1. 先完成 BODY → MEMORY → TRACKING → CONSISTENCY，并建立 REVISE Stage。
2. 修订正文响应完整落入受保护 artifact，Attempt 成功、Stage 进入 `VALIDATING`。
3. 用“自动修订次数耗尽”调用响应后结算，立即拒绝且数据库零变化。
4. 用“修订正文未变化”结算，Stage/Job 原子进入 `NEEDS_ACTION`，Usage=`FINAL`。
5. 相同原因再次结算得到 replay，不重复改变状态或用量。
6. 改成“候选循环”重放被拒绝，原持久化原因不变。
7. Chapter 的 `currentVersionId` 仍为空，证明失败候选没有成为正式可读章节。

## 验证结果

- 数据库 JVM 测试：通过。
- AndroidTest 编译：通过。
- API 35 `emulator-5554`，`ChapterFinalCandidateCommitDatabaseTest`：11/11 通过，0 失败、0 跳过。
- 统一离线门禁：371 个 Gradle task 通过，Debug APK、Release manifest、全项目 JVM 测试、安全扫描和备份排除策略通过。
- 初次统一扫描曾把文档路径中的 `task-059...` 子串误识别为 `sk-...` 形态；移除被扫描文档中的该字面路径后重跑通过。没有发现真实密钥。
- 真实织卷 Provider 调用：0。
- 物理设备写入：0。

## 修改文件

- `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterRevisionOutcomeRepository.kt`
- `core/database/src/androidTest/kotlin/app/zhijuan/core/database/ChapterFinalCandidateCommitDatabaseTest.kt`
- `docs/ai/CURRENT-CONTEXT.md`
- `docs/19-IMPLEMENTATION-BACKLOG.md`
- `docs/22-WORK-STATUS.md`

## 尚未完成

- 一致性报告得到 BLOCKER/MAJOR 后，尚需把请求前的“允许再修订 / 已耗尽”决策接到持久 Stage 分流。
- 修订成功的新正文仍需自动封存为新候选版本/hash，并重新建立 memory → tracking → consistency 链。
- 只有最终 ACCEPT 的候选才能调用现有最终事务一次性发布正文、派生数据、报告、Usage 和进度。
- 完整 TASK-059 仍不得标记完成。

## 下一步

实现一致性检查后的有限分流与新候选接续：标准场景最多自动修订 1 次，严格细写场景最多 2 次；MINOR/无问题走最终提交，BLOCKER/MAJOR 且有余额时建立 REVISE Stage，耗尽时进入持久 `NEEDS_ACTION`，全程不增加普通用户操作。
