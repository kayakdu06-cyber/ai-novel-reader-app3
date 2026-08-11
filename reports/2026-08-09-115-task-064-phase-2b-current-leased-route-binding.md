# 工作汇报 115：TASK-064 Phase 2B current leased route binding

日期：2026-08-09  
项目：织卷 Android（app开发2）  
唯一项目根目录：`D:\gptuser\projects\ai-novel-reader-app2`

## 本阶段目标

把 Phase 2A 的“合同解析结果”变成真正可用于 dispatcher 的授权事实。即使一个 Stage 的 JSON 合法，只要它已经不是 current Stage、token 已变化、租约已过期、Job 正在暂停，或请求意图已经落库，就不能再次取得 executor route。

## 完成内容

1. 在 `GenerationRunnerExecutionLeaseRepository` 新增 `resolveCurrentStageRoute`。
2. 同一只读 Room 事务内验证：
   - Job 仍是 `RUNNING`；
   - Stage 仍是 current `PREPARING` 且属于该 Job；
   - Job/Stage token 都精确匹配并属于同一个 runner owner；
   - 观察时间不早于两行的 updatedAt 和 heartbeat；
   - 两个租约都未到 `now - heartbeat >= 60s` 的失效临界；
   - Stage attempt 仍小于 maxAttempts；
   - frozen source contract 仍能通过 Phase 2A 的权威 parser。
3. 新增 `GenerationRunnerCurrentStageRouteSnapshot`，把有限 route 与授权它的精确双租约快照、attempt 上下界放在同一返回值。
4. 绑定快照构造器改为 `internal`，原始 route resolver 也改为 `internal`；feature 层不能直接伪造一个可分发快照。
5. 入口保持纯只读：不 heartbeat、不改状态、不创建 Attempt、不读连接、不调用 Provider。

## 为什么本阶段没有调用 DeepSeek

本阶段决定“谁有权启动 executor”，属于数据库原子性、租约和状态机的最终安全边界。按项目 AI 协作规程，这类架构取舍由 Sol 直接实现和审查更合适。用户允许放宽 DeepSeek 思考时长的授权仍保留；后续边界清晰的 executor 接线或测试任务可以继续使用 max 推理与较长硬上限。

## 代码变化

- 修改 `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationRunnerExecutionLeaseRepository.kt`
  - 新增 current route 只读绑定入口和不可跨模块构造的快照；
  - 新增 current/status/token/owner/time/expiry/attempt 验证。
- 修改 `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationRunnerStageRouteResolver.kt`
  - resolver 从 public 收紧为 `internal`。
- 修改 `core/database/src/androidTest/kotlin/app/zhijuan/core/database/GenerationDatabaseTest.kt`
  - 新增 5 个数据库边界测试和生产 factory fixture。

## 测试与修正

- 数据库 JVM 全量：81/81。
- Android 测试编译：通过。
- API 35 首轮 `GenerationDatabaseTest`：73/74；唯一失败是测试预期错误，正式暂停入口会在 PREPARING 安全点直接进入 PAUSED。
- 改为数据库故障注入，单独构造“Job PAUSING、Stage 仍持原租约”的 dispatcher 拒绝边界；生产代码未放松。
- API 35 `GenerationDatabaseTest` 重跑：74/74。
- API 35 数据库 Android 全量：214/214，0 失败、0 跳过。
- API 30 数据库 Android 全量：214/214，0 失败、0 跳过。
- 源码安全扫描：`SECURITY_SCAN_OK`。
- `git diff --check`：0。
- Git remote：为空。
- App 内真实 Provider：0；Fake Provider：0；物理设备写入：0。

## 已关闭风险

- 合法但陈旧的 Stage 不能仅凭 JSON 再次取得 route。
- 错 Job token、错 Stage token、mixed owner、非 current Stage 或超时临界全部失败。
- PAUSING/STOPPING/已暂停状态不会启动新的 executor action。
- `REQUEST_INTENT_RECORDED` 之后不能回到 PREPARING route 再发一次请求。
- feature 层不能直接调用 parser 或构造绑定快照绕过 repository。

## 仍未完成

- 10 个 route 还没有全部映射到唯一 executor。
- planning、context 和普通 draft 的 frozen route identity 尚未补齐。
- runner 还没有执行多阶段循环、全 phase timing、暂停/恢复总编排和完整 Fake 第一章闭环。
- 统一 Release/R8 留到 route registry 形成可运行切片后执行。
- TASK-064 整体保持进行中。

## 下一步

建立有限 executor registry。registry 只能接受本阶段的绑定快照，对 10 个 route 做显式穷举映射；先审计每个已有 executor 的真实输入、Provider/本地属性、Attempt 所有权、commit 推进方式和是否支持 same-owner resume，再选择最小可验证 route 接线，禁止 generic fallback。
