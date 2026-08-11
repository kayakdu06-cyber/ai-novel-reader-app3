# TASK-059 第六阶段 DeepSeek 试运行：修订请求 Job 绑定

## 任务身份

- 任务 ID：`TASK-059 / Phase 6 / DeepSeek trial / revision request Job binding`
- 仓库根目录：`D:\gptuser\projects\ai-novel-reader-app2`
- 基准分支与 HEAD：`main` / `8ce774429da1c3f7139a221bc241c34d81a2efdd`
- 当前未提交改动：仓库存在大量 Sol 延续中的 TASK-059 WIP；本任务目标实现文件为未跟踪新文件，测试文件已有未提交测试。必须在现有内容上做最小补丁，不得重写、清理或回退任何已有改动。
- 执行模型：DeepSeek V4 Flash（纯文本）

## 运行预算

- 推理等级：`max`
- 最长运行时间：15 分钟
- 累计 Token 上限：本次按用户明确要求不设置；仍须在目标完成后立即停止，不得扩大范围
- 预计读取文件数与明确清单：6 个，仅限本任务包、`AGENTS.md`、`docs/24-AI-DEVELOPMENT-PROTOCOL.md`、`docs/ai/CURRENT-CONTEXT.md`、下列 1 个实现文件和 1 个测试文件
- 预计执行命令/测试数：1 个最小单元测试命令，外加只读 `git diff/status`
- 提前停止条件：权限阻塞、需要修改第三个业务文件、同一测试重复失败 2 次、发现目标假设不成立或出现未声明高风险改动

## 目标

给现有一致性生产分流规划器补上一个精确的 Job 身份门禁：只有当修订请求 seed 的 `generationId` 与已冻结一致性请求的 `generationId` 完全相等时，才允许构造 `BoundChapterRevisionRequestV1`。增加一个最小负例测试，证明错误 Job ID 会在生成修订请求之前被拒绝。

## 当前现场与已有 WIP

- 已存在的实现：`ChapterCandidateConsistencyRoutingPlannerV1` 已把 gate、有限策略和修订请求装配为同一计划；只有 `ReviseAutomatically` 路径会读取 revision seed。
- 已存在的测试：同一测试类已有 MINOR 接受、MAJOR 修订、额度耗尽和候选正文错配测试，当前通过。
- 已知缺口：MAJOR 路径直接使用 `seed.generationId` 创建修订请求，尚未显式核对它与 `boundRequest.request.generationId` 相等。
- 必须延续、不得从零重写：现有 coordinator、planner、spec、四个新增测试和全部 TASK-059 WIP。

## 必读资料

1. `AGENTS.md`
2. `docs/24-AI-DEVELOPMENT-PROTOCOL.md`
3. `docs/ai/CURRENT-CONTEXT.md`
4. `feature/generation/src/main/kotlin/app/zhijuan/feature/generation/ChapterCandidateConsistencyRoutingCoordinator.kt`
5. `feature/generation/src/test/kotlin/app/zhijuan/feature/generation/ChapterConsistencyAcceptanceGateTest.kt`

除上述清单和代码直接引用外，不得递归扫描整套文档、历史会话、备份或无关模块；需要扩展读取范围时停止并在回交中说明。

## 范围

允许修改：

- `feature/generation/src/main/kotlin/app/zhijuan/feature/generation/ChapterCandidateConsistencyRoutingCoordinator.kt`
- `feature/generation/src/test/kotlin/app/zhijuan/feature/generation/ChapterConsistencyAcceptanceGateTest.kt`

明确不在范围：

- 数据库 schema、DAO、Repository、其他测试；
- 文档、报告、脚本、Gradle 配置；
- 现有策略、修订次数、成年人场景规则或产品行为；
- 原项目 `D:\gptuser\projects\ai-novel-reader` 和任何其他项目副本。

## 不可破坏的约束

- 项目隔离：任何修改前确认 Git 根目录精确为 `D:/gptuser/projects/ai-novel-reader-app2`。
- 安全与隐私：不读取、显示或记录密钥、正文 artifact 或私人内容。
- 状态机与幂等：只增加请求构造前的纯本地身份检查，不改变持久状态机或路由策略。
- 数据库与事务：不修改。
- 联网与费用：不得调用织卷 App 内部真实 Provider；测试必须离线。
- 兼容性与构建基线：保持 Kotlin/Android 现有风格，不引入依赖。
- 需要保留的用户/未提交改动：全部保留；禁止 reset、checkout、clean、format 全仓或覆盖文件。

## 实施要求

1. 在 `ReviseAutomatically` 分支、构造 `ChapterRevisionRequestSpecV1` 之前，验证 `seed.generationId == boundRequest.request.generationId`，失败信息应明确说明修订请求 seed 不属于当前冻结 Job，但不得包含敏感正文。
2. 在现有测试类新增一个测试：使用 MAJOR 报告和错误 `generationId` 的 seed，断言 `ChapterCandidateConsistencyRoutingPlannerV1.plan` 抛出 `IllegalArgumentException`。
3. 保持接受和额度耗尽路径不会构造 revision request，也不要要求这些路径必须提供 seed。
4. 不允许把检查放到 Provider 调用之后，不允许改变 `ChapterRevisionPolicyV1`，不允许重写 coordinator。

## 验收标准

- [ ] 正确 Job ID 的既有 MAJOR 修订测试继续通过。
- [ ] 错误 Job ID 在 request 构造前被拒绝。
- [ ] MINOR 接受和额度耗尽行为不变。
- [ ] 只修改允许的两个文件。
- [ ] 不产生真实 API 调用、密钥输出或其他项目写入。

## 验证命令

```powershell
.\gradlew.bat :feature:generation:testDebugUnitTest --tests "app.zhijuan.feature.generation.ChapterConsistencyAcceptanceGateTest" --offline
```

不要运行统一离线门禁；由 Sol 在审查差异后执行。

## 回交格式

请严格按以下标题返回：

1. `完成内容`
2. `修改文件`
3. `验证`
4. `未完成/风险`
5. `需要 Sol 处理`
6. `假设`

不要宣布整个 TASK-059 完成，不要修改正式状态文档；由 Sol 根据差异和测试证据确认。
