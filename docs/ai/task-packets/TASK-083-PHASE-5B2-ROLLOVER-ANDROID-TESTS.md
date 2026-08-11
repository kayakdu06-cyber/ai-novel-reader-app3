# TASK-083 Phase 5B2：跨午夜 rollover Android 定向测试

## 任务身份

- 任务 ID：`TASK-083 / Phase 5B2 rollover Android tests`
- 仓库根目录：`D:\gptuser\projects\ai-novel-reader-app2`
- HEAD：`8ce774429da1c3f7139a221bc241c34d81a2efdd`
- 执行模型：DeepSeek V4 Flash（纯文本编码）
- 当前 WIP 很多且 Phase 5B 生产核心已由上一运行和 Sol 收口；禁止 reset/clean/checkout，禁止从零重写

## 运行预算

- 推理等级：`max`
- 最长运行时间：30 分钟；上一 45 分钟任务已完成生产 WIP但来不及写 Android 行为测试，本次只补两个现有测试文件中的有限用例
- 累计 Token 上限：无
- 不运行模拟器、不联网、不调用真实 Provider；最多运行 AndroidTest 编译
- 提前停止条件：测试必须修改生产代码、schema、fixture 公共模块或任务范围外文件

## 目标

只补 Phase 5B 已有生产实现的设备级测试，证明同日 claim 不变、跨午夜专用释放正确、attempt 上限失败关闭、Executor 在换日时零 Provider 调用。不得修改任何生产文件或正式文档。

## 已有生产行为（只读，不得修改）

- `GenerationRequestAuditRepository.claimForProviderOpen` 同日正常 claim；换日后在事务提交外抛 `DailyBudgetPeriodRolloverRequiredException(retryAllowed)`。
- `GenerationDao.releaseUnsentAttemptAfterDailyRollover` 要求精确 v1 Attempt/Usage/reservation、当前 Stage/Job/lease/latest Attempt，然后：
  - Attempt -> FAILED_RETRYABLE + `DAILY_BUDGET_PERIOD_EXPIRED_BEFORE_SEND`
  - Usage -> UNKNOWN/FINAL 无值
  - reservation -> RELEASED accounted=0
  - 有剩余次数 Stage/Job -> READY；否则 -> NEEDS_ACTION
- Provider adapter 尚未打开时发生上述行为。
- `Asia/Shanghai` 日界：`BudgetDailyPeriodKeyV1` 决定，不要手算字符串；fixture 的 intent createdAt 是 10ms，使用能明确落在下一本地日期的 epoch（例如 `86_400_010L`）触发换日。

## 允许修改（仅此两项）

1. `core/database/src/androidTest/kotlin/app/zhijuan/core/database/PersistentBudgetReservationDatabaseTest.kt`
2. `feature/generation/src/androidTest/kotlin/app/zhijuan/feature/generation/AuditedStreamingProviderExecutorTest.kt`

禁止修改所有生产文件、其他测试、Gradle、schema、migration、trigger、文档和报告。

## 必读最小范围

1. 根 `AGENTS.md`
2. `docs/24-AI-DEVELOPMENT-PROTOCOL.md`
3. `docs/ai/CURRENT-CONTEXT.md` 第39节
4. `reports/2026-08-09-133-task-083-phase-5a-cross-midnight-design-audit.md`
5. 上述两个允许修改测试文件的 setup、helper 与相邻现有测试
6. 只读生产入口：`GenerationRequestAuditRepository.claimForProviderOpen`、`GenerationDao.releaseUnsentAttemptAfterDailyRollover`

## 必须新增的 4 个测试

### A. 数据库：同日 claim

在 `PersistentBudgetReservationDatabaseTest`：

- 使用现有 `seedBudgetedV1Attempt`/fixture 或等价既有 helper 创建 v1 request；
- 通过最小可访问入口 claim，同日 `validatedAt`；
- 断言 claim 成功，Attempt 仍 INTENT_RECORDED、Usage PROVISIONAL、reservation RESERVED、Stage/Job RUNNING、占用不变。

如果该测试类无法访问 internal permit，允许改在 `GenerationDatabaseTest`，但只能二选一且回交说明；不要复制大段 fixture。

### B. 数据库：跨午夜有剩余次数

- 创建 v1 request，保存旧 artifact/占用证据；
- 用下一日 `validatedAt` claim，捕获 `DailyBudgetPeriodRolloverRequiredException`；
- `retryAllowed == true`；
- 精确断言 Attempt/Usage/reservation/Stage/Job、错误码、released/finalized/updatedAt、所有租约清空；
- book/daily 聚合排除 RELEASED（SQL 无行 SUM 可能为 null，不要错误断言 0）；
- 同一个旧 permit 再调用不能重复释放，Provider 为 0。

### C. 数据库：达到 maxAttempts

- 用现有 fixture 把 stage `max_attempts` 调成 1，或通过测试 fixture 参数安全创建；旧 intent 已使 attemptCount=1；
- 换日 claim 后 `retryAllowed == false`；Stage/Job NEEDS_ACTION，Job reason 是专用错误码；reservation 仍正确 RELEASED；queue/route 不可自动继续。

### D. Executor：跨午夜零 Provider

在 `AuditedStreamingProviderExecutorTest`：

- 现有 setup 使用 Asia/Shanghai daily policy、createdAt=3；用 `IncrementingClock` 从下一日时间开始；
- `FakeAdapter(onGenerate = { counter++ })`；执行 `AuditedStreamingProviderExecutor.execute`；
- 捕获专用异常且 `retryAllowed == true`；断言 counter 为 0；
- 断言 Attempt/Usage/reservation/Stage/Job 已按 rollover 提交，受保护草稿没有被 Provider 写入（artifact 仍存在且 0 plaintext bytes 或 revision 未推进）。

## 测试质量约束

- 不用 sleep；不依赖设备当前时区/当前时间；固定 epoch + policy zone。
- 不通过直接 SQL 手工制造 rollover 终态；状态必须由公开/现有 repository 入口产生。
- 可以直接 SQL 仅用于测试 fixture 的 `max_attempts=1`，必须在 intent 前完成。
- 异常、toString 与断言不得泄露 ID、zone、金额、destination、snapshot。
- 不改生产代码来迎合测试；发现生产缺陷即停止并回交。

## 验证命令

```powershell
.\gradlew.bat :core:database:compileDebugAndroidTestKotlin :feature:generation:compileDebugAndroidTestKotlin --offline --no-daemon
```

不运行模拟器；API30/35 定向执行由 Sol 完成。

## 回交格式

1. `完成内容`
2. `修改文件`
3. `验证`
4. `未完成/风险`
5. `需要 Sol 处理`
6. `假设`

不得宣布 Phase 5B/TASK-083 完成。
