# 工作汇报 125：TASK-064 Phase 2E4A 目的地与三层预算前置审计

> 日期：2026-08-09  
> 项目：织卷 Android App  
> 唯一仓库：`D:\gptuser\projects\ai-novel-reader-app2`  
> HEAD：`8ce774429da1c3f7139a221bc241c34d81a2efdd`  
> 结论：Phase 2E4A 审计完成；发现两项 P0 前置缺口，chapter-plan 继续失败关闭

## 1. 本阶段回答的问题

Phase 2E3 已经能判断模型返回的单章计划是否结构正确、人物合法且满足场景连续性，但它还不能回答两个更早的问题：

1. 小说资料究竟允许发往哪个外部地址？
2. 这次请求是否真的还有可用的单次、单书和每日余额？

审计结论是：两项都预留了部分数据结构，但尚未形成可依赖的生产门禁。现在接远程 plan executor 会留下严重漏洞，因此 route 必须继续未注册。

## 2. 严重缺口

### 2.1 目的地确认只是空字段

`ConnectionProfileEntity` 已有：

- `normalizedDestination`；
- `dataDisclosureVersion`；
- `dataDisclosureAcceptedAt`；
- `dataDisclosureBindingHash`。

但当前连接保存代码把 `normalizedDestination` 直接写成用户输入的 base URL，并把后三项全部写成 `null`。DAO、Repository 和 Provider-open 路径都没有正式的“接受确认、验证绑定、地址变化后失效”实现。

现有 `ProviderEndpointFingerprint` 会对 base URL 和 protocol 做 SHA-256，适合能力缓存身份，但 base URL 规范化目前只做 trim/去尾斜杠，未明确默认端口、host 大小写等目的地确认语义，不能直接冒充用户授权。

### 2.2 三层预算仍是内存原型

`BudgetEngine` 已能在内存中计算单次、单书和每日限制，测试也覆盖部分预留/结算规则，但全仓没有生产调用。App 重启后该状态不会成为权威事实，两个并发 Job 也没有同一个数据库余额可竞争。

`GenerationJobEntity.budgetSnapshotJson` 只说明任务创建时接受了哪些上限；它不是共享余额。`UsageLedgerEntity` 保存发生后的用量事实，但没有 reservation 身份或状态，也不能在发送前锁住最坏用量。

`GenerationDao.recordRequestIntent` 已能在同一事务创建 RequestIntent、Attempt 和 UNKNOWN/PROVISIONAL Usage，这是正确底座；缺的是在这笔事务里同时竞争并写入三层 reservation。

## 3. 决策

新增 DEC-071，冻结以下顺序：

1. 定义版本化 canonical destination：scheme、host、effective port、protocol；
2. 实现目的地确认、持久验证和 host/port/protocol 变化失效；
3. 设计持久 budget policy/reservation，并通过数据库并发、重启、跨日与未知价格测试；
4. 把 reservation 与 RequestIntent、Attempt、初始 Usage 放入同一 SQLCipher 事务；
5. 最后实现 plan 专用请求绑定、exact-token executor、UNKNOWN/恢复和 DEC-068 原子提交。

`budgetSnapshotJson` 永久保持“意图快照”语义，不升级成隐式余额字段。这样可避免后续 TASK-083 又建立第二套互相冲突的预算事实。

## 4. 用户操作原则

可靠性不会变成逐章点确认：

- 第一次向一个新目的地发送小说资料时确认一次；
- host、port 或 protocol 变化后重新确认一次；
- 单书/每日上限首次设置或主动调整时确认；
- 普通章节由 runner 自动复核持久记录，不弹出逐章确认。

## 5. 本阶段改动

本阶段只同步架构、状态机、费用、测试、决策、追踪、进度和 AI 交接文档，没有修改 Kotlin、Room schema、migration、DAO、registry 或 Provider。

同时修正一处文档任务编号：三层预算并发原子预留属于 TASK-083；TASK-084 是创建前调用/费用范围估算。

## 6. 安全与联网状态

- Phase 2E4A 开始时沿用了此前断网现场，因此未调用 DeepSeek；用户随后已明确网络恢复，后续 Phase 2E4B 正常使用了只读 DeepSeek 审计；
- 织卷 App 内真实 Provider 调用 0；
- Fake Provider 调用 0；
- 物理设备写入 0；
- Git remote 仍为空；
- `CHAPTER_PLAN_V1` 仍在 registry 中显式未注册。

## 7. 下一阶段

Phase 2E4B 先实现目的地确认内核：规范化 origin、版本化 binding、持久接受/校验/失效和纯 JVM/Room 负例。该阶段不会接 plan 网络执行；目的地内核验收后再单独设计 TASK-083 的 schema 与原子 reservation。
