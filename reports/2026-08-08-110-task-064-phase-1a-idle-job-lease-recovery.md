# 工作汇报 110：TASK-064 Phase 1A 空闲 RUNNING Job 租约恢复

> 日期：2026-08-08  
> 项目：织卷 Android App  
> 唯一仓库：`D:\gptuser\projects\ai-novel-reader-app2`

## 1. 阶段结果

TASK-064 已进入实现，Phase 1A 完成。本阶段关闭了一个会让小说生成永久卡死的崩溃窗口：runner 已把 Job 从 READY 领取为 RUNNING，但还没来得及领取当前 Stage lease 时进程退出。旧维护器只从“有 lease 的 Stage”开始扫描，因此永远看不到这个只有 Job lease 的状态。

现在，Job lease 到期后，维护器能在不改写 Stage、Attempt 或重试事实的前提下，把它安全恢复到 READY。整个 TASK-064 仍未完成。

## 2. 架构裁决

- 不增加 schema v17 或 runner shadow table。
- `generation_job.current_stage_id`、Job/Stage 状态、两层租约、Attempt 链继续是唯一恢复事实。
- runner 以后只负责领取、重读和分发；Stage 成功、动态后继创建、后继激活与 Job 游标推进继续由现有业务仓库的单一 Room 事务完成。
- dispatcher 不能只看 `GenerationPhase`：`EXTRACT_MEMORY` 同时承载 memory 与 tracking，后续必须根据冻结的有限 contract/schema identity 路由。

## 3. 代码实现

- `GenerationDao.expiredIdleRunningJobsForMaintenance`：有界 JOIN 查询，只选 RUNNING、Job lease 已过期、current Stage READY 且 Stage 三项 lease 全空的 Job；按 heartbeat/job id 稳定排序。
- `GenerationDao.compareAndRequeueExpiredIdleJobLease`：专用精确 CAS，同时匹配 Job status、current Stage、lease owner/acquired/heartbeat，并用 SQL `EXISTS` 再次证明 Stage READY+无 lease。
- `GenerationMaintenanceRepository.scanExpiredIdleJobLeases`：limit+1、准确 `hasMore`、timeout 前快速空返回，候选 `toString` 脱敏。
- `GenerationMaintenanceRepository.requeueExpiredIdleJobLease`：单事务重读、状态机证明、时间与 exact candidate 校验后恢复 Job READY；Stage 零写入。
- 五个 Android 数据库测试覆盖正向、边界、竞争、并发和有界排序；Sol 另补候选 heartbeat 篡改失败回归。

## 4. DeepSeek 与 Sol 分工

### 只读审计

- Run：`20260808-210256-fbe80952`
- max 推理，30 分钟硬上限，无 Token 上限
- 实际约 5 分 14 秒，总 Token 1,312,501
- 有效贡献：发现 Job-only lease 崩溃窗口；确认无需新 runner 表
- Sol 修正：正常多阶段 Job 不能只扫描 READY；dispatcher 不能只凭 phase

### 代码实现

- Run：`20260808-211241-a473f0d6`
- max 推理，25 分钟硬上限，无 Token 上限
- 实际约 8 分钟，总 Token 1,793,201
- 只修改授权的 3 个文件，产生可审查差异并通过 AndroidTest 编译
- Sol 修正：恢复必须匹配扫描候选中的 heartbeat，不能只采用事务重读后的 heartbeat

## 5. 验证

- API 30 定向 `GenerationDatabaseTest`：57/57。
- API 35 定向 `GenerationDatabaseTest`：57/57。
- API 30 `core:database` Android 全量：197/197。
- API 35 `core:database` Android 全量：197/197。
- `core:database` JVM：70/70。
- 源码安全扫描：`SECURITY_SCAN_OK`。
- `git diff --check`：0。
- Git remote：无。

本子阶段未运行统一 Release/R8 门禁；在形成可运行的 dispatcher/runner 切片后统一执行。

## 6. 安全与边界

- 真实 Provider 调用：0。
- 物理设备写入：0；只使用 API 30/API 35 模拟器。
- schema/migration：无变化。
- Stage/Attempt/attemptCount/retryAt/error：恢复路径零变化。
- 原项目及其他相似目录：未修改。
- API Key：未读取、未输出、未写入文件。

## 7. 尚未完成

- READY Job 的有界领取与双 runner CAS。
- 同 owner RUNNING Job 的多阶段续跑。
- Job/Stage 双层 heartbeat 与进程重启恢复编排。
- contract-aware phase dispatcher 与全部 Stage executor 接线。
- RETRY_WAIT 到期、UNKNOWN/RECOVERY 调度、全 phase 时序。
- Fake 创建快照到正式第一章闭环与 TEST-095。

## 8. 下一步

进入 TASK-064 Phase 1B：先建立 runner queue/claim/resume/heartbeat 的最小持久接口，证明 READY 竞争领取和同 owner RUNNING 跨 Stage 续跑；再单独实现 contract-aware dispatcher，避免把两类高风险改动混在同一个子任务中。
