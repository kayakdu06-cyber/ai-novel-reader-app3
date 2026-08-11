# 工作汇报 138：TASK-064 Phase 2E5A chapter-plan exact-token 请求准备

日期：2026-08-09  
项目：织卷 Android App  
唯一项目根目录：`D:\gptuser\projects\ai-novel-reader-app2`  
结论：Phase 2E5A 完成；普通 chapter-plan 的 RequestIntent 已关闭 Stage-token 旁路，但 route 仍未注册，尚未调用 Provider。

## 1. 本阶段为什么必须先做

Phase 2B 已经能从数据库生成同时绑定 Job 和 Stage 的 route snapshot，但旧的通用请求准备入口只接收 Stage token。如果直接把 `CHAPTER_PLAN_V1` 注册到 total runner，可能出现以下竞态：

1. runner 解析 route 时持有合法 Job+Stage 双租约；
2. Job cursor、Job token 或 attempt 边界随后变化；
3. 旧调用方仍凭 Stage token 创建预算预留和 RequestIntent。

这会把“曾经有权执行”错误延长为“现在仍有权创建外部请求”。本阶段先关闭这条 TOCTOU 授权旁路，再继续请求工厂和 Fake 执行。

## 2. 已完成的代码

### 2.1 bound chapter-plan preparation

`GenerationRequestAuditRepository` 新增普通 plan 专用入口：

- 只接受 `GenerationRunnerCurrentStageRouteSnapshot`；
- 要求 route 精确为 `CHAPTER_PLAN_V1`；
- 在创建请求事实的同一 Room 事务内重读 Job 和 Stage；
- 核对 current cursor、`RUNNING + PREPARING`、exact Job token、exact Stage token、同 owner、heartbeat、60 秒租约、attemptCount 和 maxAttempts；
- 重新使用唯一 route resolver 验证持久 Stage 仍是普通 chapter-plan；
- 全部通过后，才原子创建 v1 reservation、Attempt、UNKNOWN/PROVISIONAL Usage，并推进 Stage。

### 2.2 通用旁路失败关闭

通用 `persistBeforeSend` 和普通 streaming prepare 现在会读取持久 Stage：

- 普通 `BUILD_CHAPTER_PLAN` 必须使用 bound 路径；
- 来源 JSON 损坏时也按 bound-required 处理，不能靠解析失败回落到 generic；
- 既有 `firstChapterBootstrap` 不是普通 plan，保持兼容。

### 2.3 受保护草稿生命周期

`GenerationStreamingDraftRepository` 新增 bound 首次准备和 bound 换日替代入口。公开首次准备的行为已经验证：

- repository 先创建新的加密 `STREAM_DRAFT`；
- generic 旁路被数据库拒绝后，新工件立即删除；
- bound 准备成功后，Attempt 唯一引用该工件；
- 不产生明文临时文件。

## 3. 主要修改文件

生产代码：

- `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationRequestAuditRepository.kt`
- `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationStreamingDraftRepository.kt`

测试：

- `core/database/src/androidTest/kotlin/app/zhijuan/core/database/PersistentBudgetReservationDatabaseTest.kt`

正式文档已同步：

- `docs/06-AI-GENERATION-SYSTEM.md`
- `docs/08-TECHNICAL-ARCHITECTURE.md`
- `docs/10-STATE-MACHINES.md`
- `docs/13-ERROR-HANDLING.md`
- `docs/14-COST-CONTROL.md`
- `docs/15-TEST-PLAN.md`
- `docs/18-DECISION-LOG.md`（DEC-074）
- `docs/19-IMPLEMENTATION-BACKLOG.md`
- `docs/20-TRACEABILITY-MATRIX.md`
- `docs/22-WORK-STATUS.md`
- `docs/ai/CURRENT-CONTEXT.md`

## 4. 验证证据

### 专项与模块测试

- `PersistentBudgetReservationDatabaseTest`：API 30 为 47/47，API 35 为 47/47。
- `core:database` Android 全量：API 30 为 276/276，API 35 为 276/276。
- `feature:generation` Android 全量：API 30 为 48/48，API 35 为 48/48。
- `:core:database:test`：通过。
- `:feature:generation:test`：通过。

### 统一离线门禁

`scripts/verify-build.ps1 -Offline`：通过。

- 801 个 actionable Gradle task；
- Debug 与 Release 构建通过；
- Lint/Vital 与 R8 通过；
- 安全扫描脚本自测 4/4；
- 源码与 5 个 APK 扫描通过；
- `allowBackup=false`；
- cloud backup/device transfer 排除策略通过。

测试过程中：真实 Provider 0 次，Fake Provider 0 次，物理设备写入 0，Git remote 仍为空。

## 5. DeepSeek 使用说明

本阶段由 Sol 直接完成，没有调用 DeepSeek。原因不是权限或网络问题，而是本阶段集中在数据库授权、事务原子性和已有 WIP 的窄收口；这些高风险边界需要由 Sol 做最终裁决，且任务规模没有必要再交接一次。

## 6. 当前系统真实状态

已经具备：

- total runner 的 Job/Stage 双租约、heartbeat 和 route snapshot；
- final commit 与 context 两条本地注册路线；
- 普通 chapter-plan 严格输入身份、48 KiB 输出合同和业务 validator；
- request/book/daily 三层持久预算、跨日替代、Usage 结算；
- 实际 profile/adapter/current disclosure/reservation 目的地匹配；
- plan RequestIntent 的 exact-token 准备边界。

尚未具备：

- `CHAPTER_PLAN_V1` 仍未注册；
- plan 请求工厂和不可变请求快照；
- plan 的 Fake streaming 执行与响应恢复；
- 严格计划提交、DEC-068 原子创建 initial DRAFT；
- initial DRAFT 的生产来源合同和远程执行；
- 其余 remote route、多阶段自动循环、Fake 首章闭环；
- 边生成边阅读的正式产品流程。

因此当前 APK 仍不能被描述为“已经能自动生成一本小说”。

## 7. 后续工作计划

### Phase 2E5B：冻结权威 expectation 与请求快照

先解决一个不能猜的问题：`ChapterPlanExpectationV1` 需要知道当前章节是否真的存在相关场景、允许哪些已确认成年虚构人物参与，以及采用哪份 `SceneExecutionContract`。现有 context payload 有人物事实，但窗口章节合同尚未提供足够明确的“本章场景相关性”来源。

本阶段要完成：

1. 找到或补充唯一、持久、可重验的逐章场景意图来源；
2. 区分 `NotApplicable`、`Allowed` 和 `Blocked`，不能因为全书使用成人呈现档就强迫每一章出现相关场景；
3. 构造确定性的 `ChapterPlanExpectationV1`；
4. 冻结 prompt bundle、context、progression、expectation、model/connection和预算来源的不可变请求快照；
5. 在 RequestIntent 前后复算并核对同一 input hash。

### Phase 2E5C：Fake-only exact-token 远程执行

1. 从同一 bound snapshot 调用 2E5A preparation；
2. 复用 TASK-083 目的地、日界、permit 和 Usage 门禁；
3. 使用 Fake Provider 流式返回 `chapter-plan.v1`；
4. 覆盖成功、格式失败、断流、UNKNOWN、取消、错目的地和重启；
5. 对 raw/canonical hash、artifact 和最终 Usage 建立恢复证据。

### Phase 2E6：DEC-068 原子提交与 initial DRAFT

1. 严格 parser 和业务 validator 同时通过；
2. 在单一 SQLCipher 事务中提交 plan 输出引用；
3. 把规范化计划、context/progression/expectation身份冻结到新的 initial DRAFT Stage；
4. 原子推进 plan Stage、Job cursor 和 Usage；
5. 证明 replay、并发、artifact 丢失和事务故障均不会创建两个 DRAFT 或半提交计划。

### 随后阶段

- plan route 加入有限 registry；
- 完成 initial DRAFT 独立来源合同与 exact-token executor；
- 逐条接入 memory、tracking、consistency、revision；
- 形成 Fake-only 第一章与多章持久循环；
- 再进入生成中正文投影、watchdog、速度优化和 20 章 E2E。

## 8. 后续恢复入口

下次继续时依次读取：

1. 根目录 `AGENTS.md`；
2. `docs/24-AI-DEVELOPMENT-PROTOCOL.md`；
3. `docs/ai/CURRENT-CONTEXT.md` 第 43 节；
4. 本工作汇报；
5. `docs/06-AI-GENERATION-SYSTEM.md` 第 46 节；
6. DEC-068、DEC-070、DEC-071、DEC-073、DEC-074；
7. 上述两个生产文件和 `PersistentBudgetReservationDatabaseTest` 的4项plan准备测试。

恢复后的第一个动作不是注册 route，而是审计逐章场景意图的权威来源并形成 Phase 2E5B 的窄任务包。
