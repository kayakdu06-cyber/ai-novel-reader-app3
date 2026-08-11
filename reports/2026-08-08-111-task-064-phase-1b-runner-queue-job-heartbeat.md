# 工作汇报 111：TASK-064 Phase 1B runner queue 与 Job heartbeat

日期：2026-08-08  
项目：织卷 Android App  
唯一仓库：`D:\gptuser\projects\ai-novel-reader-app2`

## 1. 本阶段结论

TASK-064 Phase 1B 已完成并通过双 API 数据库回归。系统现在可以有界扫描可执行的 READY Job，让两个 runner 对同一候选只成功一个，并让成功 runner 持同一个 Job token 跨多个 Stage 续租和读取最新 current Stage。

这仍不是完整 total runner。当前没有自动取得 Stage lease、没有 Stage/Job 双层 heartbeat supervisor、没有 contract-aware dispatcher、没有自动调用任何 Provider，也没有从创建快照跑完正式第一章。

## 2. 落地内容

### 2.1 持久 READY queue

- DAO 只投影 Job/Stage identity、status、updatedAt，不读取 book、target、intent、input sources、payload 或 lease owner。
- 只选择 READY Job，其 current Stage 必须精确属于该 Job、处于 READY，且 Job/Stage 三项 lease 全空。
- `observedAt` 排除未来更新；按 Job updatedAt、jobId 稳定排序；limit+1 计算 hasMore。
- 异常 projection 失败关闭，不用 `mapNotNull` 静默吞掉坏行造成队列饥饿。

### 2.2 精确 claim

- claim 在单一 Room 事务重读并复验 Job/currentStage/status/updatedAt、Stage/status/updatedAt 和双方 lease。
- 复用现有 `acquireJobLease` CAS；两个 runner 并发只能一个成功。
- 成功后只有 Job 变为 RUNNING 并获得 lease；Stage 保持 READY，无 Stage lease，Attempt 与 attemptCount 不变。
- runner owner 限制为 1～128 位安全字符 `[A-Za-z0-9._:-]`，不持久化自由文本设备描述。

### 2.3 跨 Stage Job heartbeat

- runner 必须持有内存中的精确 `GenerationLeaseToken(ownerId, acquiredAt)`。
- heartbeat 成功后在同一事务读取最新 current Stage。
- 当业务提交把 current Stage 从 A 原子推进到 B 时，原 Job token 不变，runner 不重新领取 Job即可继续。
- 过期、错误 owner/acquiredAt 或旧进程 token 不能复活；重启后不能仅凭 owner 字符串收养旧 lease。

## 3. DeepSeek 与 Sol 分工

- DeepSeek 运行：`20260808-213707-7e4fe1b6`。
- 配置：DeepSeek V4 Flash、`max` 推理、30 分钟硬上限、无累计 Token 上限。
- 实际：约 23 分 21 秒正常结束；总 Token 4,412,800，其中缓存输入 3,899,008，输出 120,547，推理输出 84,499。
- DeepSeek 产出 DAO 查询、新 repository 和 7 个 Android 数据库测试，并完成编译/JVM 自测。
- Sol 审查后补强：READY Job lease-free 查询、异常 projection 失败关闭、残留 Job lease 不进入队列的回归；随后独立运行双 API 与全量数据库测试。

本次说明适当放宽思考时长有效：模型在约 13 分钟后开始产生可审查差异，最终没有因短超时只留下思考过程。但总 Token 较高，后续仍应继续按单一契约/文件级边界拆分。

## 4. 验证结果

- Kotlin/Room AndroidTest 编译：通过。
- `core:database` JVM：70/70。
- API 30 定向 `GenerationDatabaseTest`：64/64。
- API 35 定向 `GenerationDatabaseTest`：64/64。
- API 30 `core:database` Android 全量：204/204。
- API 35 `core:database` Android 全量：204/204。
- 全部 0 失败、0 错误、0 跳过。
- 测试设备仅 `emulator-5556`（API 30）和 `emulator-5558`（API 35）。

## 5. 仍未完成的边界

- Stage lease 获取与 Stage/Job 双层 heartbeat supervisor。
- frozen contract/schema identity 解析与 executor registry；`EXTRACT_MEMORY` 仍不能只按 phase 分发。
- CREATED 激活、RETRY_WAIT、暂停/停止安全点、UNKNOWN/RECOVERY 自动路由。
- 全 phase 时序自动发射、Fake 正式第一章闭环、重启/故障注入与统一 Release/R8 门禁。
- App 内真实 Provider 调用、物理设备测试和真实模型速度档案均未执行。

## 6. 下一步

先审计现有各 Stage executor 对 Stage lease 与 heartbeat 的实际所有权，形成最小双层 heartbeat 执行包络；再实现 frozen contract/schema-aware dispatcher。仍采用窄任务包、DeepSeek 实现、Sol 审查和双 API 验证的流程，完成下一阶段后继续生成新的工作汇报。
