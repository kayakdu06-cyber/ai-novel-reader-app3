# 工作汇报：模块、范围和精简测试复审

> 日期：2026-08-11
> 项目：D:\gptuser\projects\ai-novel-reader-app3
> 工作类型：文档复审与收敛；未开发功能、未构建 App、未调用真实 Provider

## 1. 用户新增硬要求

1. 严格按照模块开发。
2. 不主动扩展无用功能；只有稳定性或严重 bug 需要时才开发。
3. 不做过多无意义测试，避免浪费时间和 token。

三项要求已写入 AI 开发规程、总规划、任务清单、决策日志、追踪矩阵和当前交接上下文，不再只依赖对话记忆。

## 2. 复审发现的问题

上一版文档存在三类偏重：

- TASK-121 同时涉及 core、data、来源记录和编译器，模块边界不够严格。
- WritingPolicyPack 设计包含运行时来源/许可证状态和未来扩展倾向，对第一份可验证 APK 没有直接价值。
- 真实 API 被拆成结构、流式、完整章、组合章和连续章多组测试，并把 A/B 写成固定要求，存在重复付费和重复证据。

这些内容已收窄。

## 3. 模块裁决

现有十模块保持冻结：

- :core：纯 Kotlin 公共模型和契约；
- :data：存储、安全、诊断和已有持久事务；
- :provider：Provider 协议、transport、能力和 Fake；
- :feature:connection：连接；
- :feature:creation：创建；
- :feature:generation：生成业务编排；
- :feature:reader：阅读；
- :feature:library：书架/目录；
- :feature:template：模板；
- :app：导航和 Hilt 组装。

实际 Gradle 依赖与 scripts/verify-module-boundaries.ps1 完全一致，依赖图无环；唯一 feature 例外是 template→creation。

后续每个任务必须声明：

- 主模块；
- 允许配套模块；
- 禁止模块；
- 依赖方向；
- 多模块时的分批顺序。

VS-1 不新增第十一个模块，不顺手迁移现有代码。

## 4. 范围收敛

- TASK-121 只允许修改 :core。
- 运行时 skill 管理、来源数据库、安装器、插件、动态 capability 编辑器全部删除出 VS-1。
- 来源和许可证只保存在开发文档。
- TASK-123 先复用现有 Outline/Context/Stage JSON；只有缺失会造成恢复或状态污染时才允许 migration。
- TASK-138 改为条件任务：20 章证据不足或发布声明确实需要时才做 80/300 扩展。
- 候选改进没有严重故障链时只记录，不实现。

精简后到可亲手验证版本的估算由 102–178 小时调整为 82–132 个有效工程小时。

## 5. 测试收敛

离线测试改为风险分级：

- 文档：格式、引用、差异和一致性；
- 单模块纯逻辑：相关模块测试 + 编译；
- 跨模块合同：模块边界 + 受影响模块 + 一个真实交接；
- 数据库/安全/预算/Provider-open/runner：专项回归；
- 全量 test、Release/R8、双 Android API、APK 扫描：里程碑或对应风险变化才运行。

真实 API 只保留：

1. 连接能力证据；
2. 一个 chapter-plan 结构 smoke；
3. 一个完整混合能力章节；
4. 复用同一本书连续 3–5 章并暂停/重启一次。

A/B 只有出现质量、速度或 token 回归时才触发。

## 6. GitNexus 与源码证据

- GitNexus 索引显示 stale，原因是最近提交只有 Markdown 变化；当前代码 commit 之后没有生产代码变更。
- 模块依赖结论未依赖陈旧图谱，而是直接读取所有 build.gradle.kts 和 scripts/verify-module-boundaries.ps1 复核。
- 本轮只修改文档，不涉及函数、类或调用链，不需要代码 symbol impact。

## 7. 修改文件

- docs/24-AI-DEVELOPMENT-PROTOCOL.md
- docs/27-DEVELOPMENT-MASTER-PLAN-V3.md
- docs/28-WRITING-POLICY-PACK-SPEC.md
- docs/29-HANDS-ON-VERTICAL-SLICE-SPEC.md
- docs/30-IMPLEMENTATION-BACKLOG-V2.md
- docs/31-REAL-API-QUALITY-PERFORMANCE-TEST-PLAN.md
- docs/18-DECISION-LOG.md
- docs/20-TRACEABILITY-MATRIX.md
- docs/ai/CURRENT-CONTEXT.md
- 本工作汇报

## 8. 验证边界

本轮根据新的精简测试规则不运行 Gradle、模拟器或真实 API。只运行：

- Markdown/Git whitespace 检查；
- 关键术语和旧范围残留搜索；
- 实际 Gradle 依赖读取；
- 模块边界脚本；
- GitNexus 文档差异范围检查。

## 9. 下一任务

唯一下一任务仍为 TASK-121，但边界已收窄为：

- 只修改 :core；
- 只建立最小 WritingPolicyPack/fragment 纯 Kotlin 合同；
- 只跑 :core 相关测试和编译；
- 不碰 data、Provider、UI、App、migration 或新模块。
