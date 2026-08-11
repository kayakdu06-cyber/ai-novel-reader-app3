# TASK-064 Phase 2C1：有限 executor registry 生产入口盘点

## 任务身份

- 任务 ID：`TASK-064 / Phase 2C1 executor registry inventory audit`
- 仓库根目录：`D:\gptuser\projects\ai-novel-reader-app2`
- 基准分支与 HEAD：`main @ 8ce774429da1c3f7139a221bc241c34d81a2efdd`
- 当前未提交改动：约 209 条既有/连续 WIP；不得 reset、clean、checkout、覆盖或整理无关改动
- 执行模型：DeepSeek（纯文本）

## 运行预算

- 推理等级：`max`
- 最长运行时间：30 分钟；理由：需要横向核对 10 个 route 与多个既有 executor/coordinator/repository，但本任务只读、输出结构固定
- 累计 Token 上限：无
- 预计读取文件数与明确清单：基础 8 个文件，加上通过限定 `rg` 找到的 `feature/generation` 与 `core/database/generation` 直接 executor/coordinator 文件，总数不得超过 50
- 预计执行命令/测试数：10～25 个只读 `rg` / `Get-Content`；不构建、不测试、不修改
- 提前停止条件：需要访问其他项目、需要读取密钥/正文/个人数据、需要修改架构或文件、限定目录内无法证明结论、预计读取超过 50 个文件

## 目标

为 Phase 2B 已绑定 current 双租约的 10 个 route，形成逐项可复核的生产入口清单。每个 route 必须明确：现有唯一 executor 是否存在、真实入口类/方法、远程或本地属性、所需输入与依赖、Attempt/Provider/commit/cursor 所有权、same-owner resume 能力，以及 registry 接线前仍缺什么。

只做代码事实审计，不修改任何文件。最后给出“最小可安全接线切片”建议，但不得宣布 TASK-064 或 Phase 2C 完成。

## 当前现场与已有 WIP

- 已存在的实现：
  - `GenerationRunnerStageRoute` 有 10 个有限枚举；
  - `GenerationRunnerStageRouteResolver` 已为 `:core:database` 内部权威 parser 路由；
  - `GenerationRunnerExecutionLeaseRepository.resolveCurrentStageRoute` 已在只读 Room 事务中绑定 current `RUNNING + PREPARING`、精确同 owner 双 token、未过期 heartbeat 和剩余 attempts；
  - `GenerationRunnerCurrentStageRouteSnapshot` 构造器为 `internal`，feature 层只能消费 repository 返回值；
  - heartbeat envelope 已存在，但没有 executor registry。
- 已存在的测试：
  - route resolver JVM 11 项；
  - current route binding Android 5 项；
  - database JVM 81/81，API 30/API 35 database 各 214/214。
- 已知失败或缺口：
  - 10 个 route 尚未建立 registry；
  - planning/context/普通 draft route 尚未纳入；
  - 不确定部分 route 是完整 executor、coordinator、repository，还是只存在测试装配。
- 必须延续、不得从零重写的部分：全部既有 executor、request audit、Stage lease、commit repository、candidate/final-commit 流程和 TASK-061 rebuild 流程。

## 必读资料

1. `AGENTS.md`
2. `docs/24-AI-DEVELOPMENT-PROTOCOL.md`
3. `docs/ai/CURRENT-CONTEXT.md`（重点 18～22 节）
4. `docs/06-AI-GENERATION-SYSTEM.md`（重点 33～35 节）
5. `docs/08-TECHNICAL-ARCHITECTURE.md`（重点 30～32 节）
6. `docs/10-STATE-MACHINES.md`（重点 36～38 节）
7. `docs/18-DECISION-LOG.md`（DEC-058～062）
8. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationRunnerStageRouteResolver.kt`
9. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationRunnerExecutionLeaseRepository.kt`
10. 通过下列限定搜索直接命中的源文件和对应测试：
    - `rg -n "class .*Executor|class .*Coordinator|execute\\(|run\\(" feature/generation/src core/database/src/main/kotlin/app/zhijuan/core/database/generation`
    - route 名称所对应的 Memory、Tracking、Candidate、Consistency、Revision、FinalCommit 文件

除上述清单和代码直接引用外，不得递归扫描整套文档、历史会话、reports、备份、缓存或无关模块；需要扩展读取范围时在回交中列出，不自行扩张。

## 范围

允许修改：

- 无；本任务使用只读/patch-proposal-only 模式，最终回交文本即交付物。

明确不在范围：

- 不新增 registry；
- 不改 Kotlin、Gradle、测试、文档或任务状态；
- 不运行构建和模拟器；
- 不接 planning/context/普通 draft；
- 不调用 App 内真实或 Fake Provider；
- 不读取/显示 API Key；
- 不访问 `D:\gptuser\projects\ai-novel-reader`。

## 不可破坏的约束

- 项目隔离：只读当前 app2。
- 多模态：无图片任务，不推测 UI。
- 安全与隐私：输出不得含正文、JSON payload、host、secret、完整用户输入或可识别 ID。
- 状态机与幂等：不得把测试 helper 当生产入口；不得把 phase 相同视为 executor 相同；不得建议 generic fallback。
- 数据库与事务：必须区分 Provider-open、Attempt、artifact、验证、commit/cursor 各自所有者；不假设跨 repository 自动原子。
- 联网与费用：0 Provider 调用、0 费用。
- 兼容性与构建基线：不改 schema/migration/Gradle。
- 需要保留的用户/未提交改动：全部 209 条 WIP。

## 实施要求

1. 对下列 10 个 route 逐项填写一行事实表：
   - `FORMAL_CHAPTER_MEMORY_V1`
   - `EDIT_REBUILD_CHAPTER_MEMORY_V2`
   - `FORMAL_CHAPTER_TRACKING_V1`
   - `EDIT_REBUILD_CHAPTER_TRACKING_V2`
   - `CANDIDATE_CHAPTER_DRAFT_V1`
   - `CANDIDATE_CHAPTER_MEMORY_V1`
   - `CANDIDATE_CHAPTER_TRACKING_V1`
   - `CANDIDATE_CHAPTER_CONSISTENCY_V1`
   - `CANDIDATE_CHAPTER_REVISION_V1`
   - `FINAL_CHAPTER_COMMIT_V3`
2. 每行必须给出：
   - 生产入口类与方法（带文件路径）；
   - “完整 executor / coordinator 组合 / 只有 repository / 只有测试装配 / 不存在”；
   - 远程 Provider 或纯本地；
   - 调用需要的公开输入类型与依赖；
   - 谁创建 Attempt/Usage、谁打开 Provider、谁验证输出、谁原子提交/推进 currentStage；
   - 是否已有 same-owner resume 或精确 replay；
   - registry 直接接线是否安全，若不安全写出最小缺口。
3. 单独列出任何“测试里能跑，但生产没有一个可调用入口”的 route。
4. 单独列出可能重复发送、重复 commit、错误 cursor 推进、绕过 final-commit executor 的 P0/P1 风险。
5. 推荐一个最小可安全接线切片；必须说明为什么不先接其余 route。
6. 所有结论引用文件路径与类/方法名，不复制长代码。

## 验收标准

- [ ] 10 个 route 无遗漏、无合并、无 generic fallback。
- [ ] 测试 helper 与生产入口明确区分。
- [ ] remote/local、Attempt、Provider、commit/cursor 所有权明确。
- [ ] same-owner resume/replay 结论有源码依据。
- [ ] 明确指出缺口，不把底层 repository 冒充完整 executor。
- [ ] 无文件改动、无 Provider 调用、无密钥或 payload 输出。

## 验证命令

```powershell
git status --short
git diff --name-only
```

仅用于确认任务前后没有新增修改；不运行 Gradle。未运行的构建必须写“按任务范围未运行”，不能写成通过。

## 回交格式

请严格按以下标题返回：

1. `完成内容`：先给 10 行 Markdown 事实表，再给风险与最小切片建议
2. `修改文件`：必须为“无”
3. `验证`
4. `未完成/风险`
5. `需要 Sol 处理`
6. `假设`

不要宣布整个 TASK 或 Phase 完成，不要更新正式状态。
