# TASK-125 任务包：普通章节规划闭环

> 状态：执行中；日期：2026-08-12

## 任务身份

- 仓库：`D:\gptuser\projects\ai-novel-reader-app3`
- 基准 HEAD：`b822321`
- 模块锁：先 `:data`，后 `:feature:generation`；不改其他模块
- 执行：Sol；本任务只使用 Fake Provider

## 目标

1. 在现有 TASK-064 exact-token、双租约、预算和目的地门禁上扩展 `chapter-plan.v2`，不重写旧链路。
2. 冻结 expectation、能力激活和策略编译清单，并在调用前、解析后、提交前核对同一组哈希。
3. Fake Provider 返回经过严格 schema 与业务校验的计划。
4. 计划提交与唯一 initial DRAFT 创建处于同一事务；重放不得重复创建。
5. 有限 registry 仅放行已完成的 route，其他 route 继续失败关闭。

## 批次

- [ ] A / `:data`：v2 冻结来源、route 扩展、原子提交和重放。
- [ ] B / `:feature:generation`：request factory、Fake 执行、严格解析和有限 registry。
- [ ] C / 验收：定向测试、模块边界、安全扫描、报告、提交并推送。

## 最小验收

- [ ] Provider-open 复用并重验 destination、budget、当前双租约和来源哈希。
- [ ] 一个参数化负例覆盖 schema、人物、activation、义务和 plan hash 错配时零提交。
- [ ] 成功与 replay 后都只有一个 initial DRAFT。
- [ ] registry 拒绝所有尚未实现的 route。

## 明确排除

- 不调用真实 Provider，不新增模型协议。
- 不修改 UI、创建页、阅读器、模板或 App 导航。
- 不增加题材白名单、运行时 skill、后台或隐私锁功能。
- 不做全量回归和同构字段穷举测试。
