# 工作汇报 113：TASK-064 Phase 1D heartbeat scheduling envelope

日期：2026-08-08  
项目：织卷 Android App  
唯一仓库：`D:\gptuser\projects\ai-novel-reader-app2`

## 1. 本阶段结论

Phase 1D 已完成。系统现在不仅有双 lease heartbeat 的数据库原语，还具备实际的可取消协程 envelope：Stage action 存活时按间隔续租，action 结束立即停止；真正丢 lease 会取消 action，而业务已经原子推进 cursor 或完成 Job 的正常 stale 不会误杀成功提交。

本阶段没有接 dispatcher 或 Provider，因此 TASK-064 整体仍是进行中。

## 2. 核心行为

- 默认每 15 秒调用 Phase 1C 的原子 Job+Stage heartbeat。
- action 和 heartbeat waiter 使用结构化并发；父协程取消会同时清理。
- action 先完成：取消等待器，不发送迟到 heartbeat。
- heartbeat 失败且权威 Job 仍是同 current Stage：取消 action，向 runner 传播 lease 失败。
- heartbeat 失败但原 Job token 已把 cursor 从 A 推进到 B：停止旧 heartbeat，等待 action 正常返回。
- Job 已 COMPLETED/PAUSED/STOPPED/NEEDS_ACTION/BLOCKED 且 lease 清除：视为 durable boundary，不把成功安全点当成抢占。

## 3. 为什么由 Sol 直接实现

上一阶段 DeepSeek 在 30 分钟上限超时，只完成 repository 主体、没有测试。Phase 1D 的主要难点是协程取消和“提交已成功但 action 尚未返回”的竞态，继续交给同一模型预计仍会大量消耗且难以收敛。因此本阶段由 Sol 直接设计、实现和验证，没有调用 DeepSeek。

## 4. 测试

新增 5 个纯 JVM 测试，使用 `Channel + CompletableDeferred` 手动触发 heartbeat，不 sleep、不真实等待 15 秒：

- action 活跃时多次 heartbeat，完成后停止。
- action 在首 tick 前完成，heartbeat 为 0。
- lease 丢失取消 `awaitCancellation` action，并传播 `lease-lost`。
- cursor durable handoff 后不取消已提交 action。
- Job 完成边界正常收口，mixed-owner identity 在 action 前拒绝。

首轮 JVM 125 个测试中 1 个失败，原因是测试要求异常必须为同一对象；协程堆栈恢复会生成类型/消息相同的等价异常实例。改为校验类型与消息后通过，生产逻辑未放松。

最终结果：

- `feature:generation` JVM：125/125。
- API 30 `feature:generation` Android：39/39。
- API 35 `feature:generation` Android：39/39。
- 最终 0 失败、0 错误、0 跳过。
- 真实/Fake Provider 调用 0，物理设备写入 0。

## 5. 仍未完成

- frozen contract/schema identity 解析与 executor registry。
- `EXTRACT_MEMORY` 下 memory 与 tracking 的可靠区分。
- dispatcher 驱动 Stage acquire → envelope → executor → 下一 current Stage 的循环。
- RETRY_WAIT、UNKNOWN/RECOVERY、全 phase timing 和完整 Fake 第一章。
- 统一 Release/R8、真实模型速度档案和物理设备验收。

## 6. 下一步

进入 contract-aware dispatcher。先审计每种 Stage 的冻结 `input_sources_json`/schema identity 和现有 executor 入口，定义有限 route enum 与失败关闭解析器；第一切片优先接不调用 Provider的本地 final commit executor，再逐步接 BODY/MEMORY/TRACKING/CONSISTENCY。
