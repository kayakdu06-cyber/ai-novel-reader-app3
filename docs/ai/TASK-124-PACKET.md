# TASK-124 任务包：规划合同 V2

> 状态：Sol 已完成并验收；日期：2026-08-12

## 任务身份

- 仓库：`D:\gptuser\projects\ai-novel-reader-app3`
- 基准 HEAD：`8c4efd0`
- 模块锁：仅 `:feature:generation`
- 执行：Sol；本任务未调用真实 Provider

## 目标与范围

- 复用现有 arc/chapter plan v1，新增最小 v2，保留 v1 只读解析。
- v2 绑定策略、激活、上下文证据、义务动作、预期状态变化和场景因果。
- 只增加严格输出合同、解析、业务校验、规范 JSON 和针对性单元测试。
- 不改数据库、UI、Provider、App、调度、网络或迁移。

## 验收

- [x] Arc 仅允许 1～8 章滑动窗口，并逐章绑定合同。
- [x] Chapter v2 拒绝义务消失、未激活状态、哈希错配和场景顺序错配。
- [x] v1 仍由旧解析器读取，不猜测 v2 新字段。
- [x] Provider schema 与本地严格校验均可初始化。
- [x] `:feature:generation:testDebugUnitTest`：17/17 通过。

## 明确排除

- 不生成全书逐章骨架。
- 不增加题材白名单、动态插件或运行时 skill。
- 不调用 DeepSeek 或 App 内真实 API。
