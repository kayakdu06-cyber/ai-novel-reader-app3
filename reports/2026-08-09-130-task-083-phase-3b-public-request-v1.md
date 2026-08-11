# 工作汇报 130：TASK-083 Phase 3B 公开 RequestIntent v1 接线

> 日期：2026-08-09  
> 项目：织卷 Android App  
> 唯一工作目录：`D:\gptuser\projects\ai-novel-reader-app2`  
> 分支 / HEAD：`main` / `8ce774429da1c3f7139a221bc241c34d81a2efdd`

## 1. 本阶段结论

Phase 3B 已完成：公开的流式请求准备与正文续接准备不再能建立 budget enforcement v0 请求，调用方必须显式提供每 Attempt 唯一的 `RequestBudgetReservationDraft`。数据库会在同一个事务中完成 reservation、Attempt、UNKNOWN/PROVISIONAL Usage 和 Stage 推进，只有完整 v1 持久证据提交后才签发发送许可。

Provider-open、标记已发送和标记流开始都会重新读取数据库，并把内存许可绑定到同一条精确 `RESERVED` reservation；旧 v0/null Attempt、错误 reservation ID、缺失或已 `RELEASED` 的 reservation、错误 Usage/Stage/Job/Book 身份均在联网前失败关闭。

这不等于 TASK-083 整体完成。Usage 终值结算、UNKNOWN/明确未执行释放、迟到 Provider usage 回补、跨午夜未发送请求重新预留，以及实际 Provider profile / adapter 与 connection/protocol/destination 的发送前匹配仍属于后续阶段。`CHAPTER_PLAN_V1` 继续未注册。

## 2. 生产代码改动

### 2.1 公开请求合同

- `RequestBudgetReservationDraft` 成为调用方可构造的公开脱敏合同，继续要求：非空 reservation/connection、正 token、金额与三字母大写币种成对出现。
- `RequestIntentDraft` 删除 caller `dailyPeriodKey`。公共层只保留一个不会落库的固定占位值；真正日键始终由当前 DAILY policy 的持久 IANA zone 与请求 epoch 在原子事务内派生并覆盖。
- `GenerationRequestAuditRepository.persistBeforeSend` 必须显式接收 budget draft，并只调用 `PersistentBudgetReservationRepository.recordBudgetedRequestIntent`；生产 `src/main` 中对低层 `GenerationDao.recordRequestIntent` 的唯一调用仍位于该原子 reservation repository 内。
- `GenerationStreamingDraftRepository.prepareBeforeSend`、内部 continuation prepare 和 `ChapterDraftContinuationRepository.prepareContinuationBeforeSend` 全部要求显式 budget；没有保留两参数 overload、nullable/default budget、测试开关或 v0 fallback。
- `PersistentBudgetPolicyRepository` 及其两个脱敏结果类型调整为跨模块可用的公开类型，结果构造器仍为 `internal`，策略激活/CAS/校验和持久化语义未改变。

### 2.2 精确发送许可

- `PersistedRequestSendPermit` 与 claimed request 在内部保存精确 reservation ID，字符串输出不展开 attempt、reservation、connection、金额、币种、目的地或 hash。
- claim 前要求 Attempt 为 enforcement v1，Attempt reservation ID 与 permit 完全相等；reservation 必须存在且为 `RESERVED`，并与 Attempt/Usage/Job/Stage/Book、daily key 全部一致。
- Usage 必须是该 Attempt 的 `UNKNOWN + PROVISIONAL` 初始行，total token 仍为空；缺 Attempt、Usage、Job 或 reservation 直接作为陈旧持久证据失败。
- `claimForProviderOpen` 只接受 Stage 仍为 `REQUEST_INTENT_RECORDED`；`markRequestSent` 再次要求相同状态；`markStreamStarted` 再次要求 Stage 已为 `REQUEST_SENT`。任何内存对象都不能代替持久状态重验。

## 3. 测试调用方迁移

新增两个仅测试使用的预算环境：

- core database AndroidTest 共享夹具；
- feature generation AndroidTest 共享夹具。

夹具只使用有限 token 上限和 1-token 估计，不使用 `Long.MAX_VALUE`，不伪造金额或价格。feature 测试连接固定为 `https://example.invalid:443` + `OPENAI_CHAT_COMPAT`，budget connection ID 与五组既有 Provider profile 分别精确一致。

迁移范围包括：

- core 的 Generation 与 final candidate 测试；
- feature 的流式执行、初始规划、章节记忆、故事追踪和一致性检查端到端测试；
- App 的生产维护恢复集成测试。

所有公开 prepare/continuation prepare 都显式携带按 attempt ID 确定性生成的唯一 reservation；旧 caller daily key 已从 feature/app 测试移除。App 维护测试使用随机书、随机 policy 和随机 `.invalid` 连接，只建立本地持久证据。

## 4. 新增和加固的回归

核心新增覆盖：

1. 公开 prepare 成功后 Attempt 为 v1，存在精确 reservation，Usage 日键来自 policy；
2. 公开请求超预算时 reservation/Attempt/Usage/Stage 四者零半状态；
3. 直接低层创建的 legacy v0 Attempt 即使人为构造内存 permit，也不能 claim Provider-open；
4. permit reservation ID 不匹配时不能 claim；
5. reservation 已 `RELEASED` 时不能 claim；
6. 缺失或不一致的 Attempt/Usage/Job/Stage/Book/reservation 持久证据不能推进发送状态；
7. secret-bearing snapshot 仍在任何 reservation 写入前拒绝，异常与对象字符串继续脱敏。

全量运行还暴露一个测试夹具竞态：API 30 在双 Room 同文件测试中，第二个实例若在第一个实例完成多轮 fixture DML 后才打开，两个 `onOpen` 重复安装保护 trigger 时可能遇到 `SQLITE_BUSY`。修复为先打开两个 Room 实例、再开始任何 fixture 写入；这保持了真实 reservation 写竞争，同时不再把数据库 callback 的 DDL 时序误当成预算并发结果。修复后专项在 API 30 连续 3 次、API 35 连续 2 次通过。

## 5. DeepSeek 使用与审查

### 5.1 Phase 3B 主体运行

- Run ID：`20260809-100215-c47df2ef`
- 模型 / 推理：DeepSeek V4 Flash / `max`
- 时限：40 分钟；无累计 Token 上限
- 结果：达到硬超时，无 final；但在允许范围内留下可审查的公开 API 与测试 WIP
- Usage：总 11,787,120；输入 11,632,677；缓存输入 10,668,288；输出 154,443；推理输出 84,753

Sol 没有把超时当成完成，独立修复编译、补三项安全回归并加固 permit 的持久状态重验。

### 5.2 feature 测试迁移窄任务

- Run ID：`20260809-110036-9b5c0f9b`
- 模型 / 推理：DeepSeek V4 Flash / `max`
- 时限：25 分钟；无累计 Token 上限
- 结果：约 11 分 9 秒正常完成，产生边界内可审查差异，无权限请求
- Usage：总 3,209,194；缓存输入 2,790,528；输出 51,202；推理输出 23,022

Sol 逐项复核连接身份、有限预算、seed 顺序和全部 prepare 调用，并独立完成编译、双模拟器及统一门禁。DeepSeek 未运行模拟器、未调用 App 内真实 Provider，也未宣布 TASK-083 完成。

## 6. 验证结果

### JVM / 编译

- `core:database` JVM：91/91
- `feature:generation` JVM：140/140
- 统一离线门禁全部 JVM：590/590，0 失败、0 错误、0 跳过
- core、feature、app Debug/AndroidTest Kotlin 编译通过

### Android API 30 / API 35

- `GenerationDatabaseTest`：各 77/77
- reservation 专项：各 11/11；修复后另做 API 30 连续 3 次、API 35 连续 2 次稳定性复验
- `core:database` 全量：各 240/240
- `feature:generation` 全量：各 42/42
- App 维护恢复专项：各 2/2
- 全部 0 失败、0 错误、0 跳过

### 统一门禁与安全

- `scripts/verify-build.ps1 -Offline`：801 actionable tasks 成功
- Debug、Release、Lint/Vital、R8、Release APK 成功
- `SECURITY_SCAN_TESTS_OK`
- `SECURITY_SCAN_OK`，源码与 5 个 APK 0 个疑似密钥命中
- `BACKUP_EXCLUSION_POLICY_OK`
- `git diff --check`：退出 0，仅既有 CRLF 提示
- Git remote：空
- App 内真实 Provider 调用：0
- 物理设备写入：0

## 7. 当前边界与下一步

TASK-083 下一阶段必须继续完成：

1. `GenerationDao.recordUsage` 唯一事务入口同步 settlement reservation accounted 终值；
2. UNKNOWN FINAL 保留 estimate，实际超预留仍保存；
3. 只有 Provider 证明未执行才允许 `RELEASED`，迟到高可信 usage 能 `RELEASED → SETTLED`；
4. 跨午夜且尚未发送的请求重新预留，只重置 DAILY，不重置 BOOK；
5. Provider-open 时核对实际 profile connection、adapter protocol 与 reservation 冻结的 canonical destination/disclosure；
6. 完成上述门禁后，TASK-064 才能把 `CHAPTER_PLAN_V1` 接入 exact-token 远程执行与 DEC-068 原子提交/initial DRAFT。

本阶段没有接通真实模型、total runner、完整 Fake 第一章或边生成边阅读，不能把当前 APK 描述成已可自动生成整本小说。
