# 工作汇报 139：TASK-064 Phase 2E5B 权威逐章场景意图设计冻结

日期：2026-08-09  
项目：织卷 Android App  
唯一项目根目录：`D:\gptuser\projects\ai-novel-reader-app2`

## 结论

Phase 2E5B 完成。普通章节计划所需的“本章是否存在相关场景、涉及哪些人物、计划多少个相关场景”不能由 chapter-plan 模型临时决定，也不能从全书内容呈现档案推测；唯一权威来源冻结为上游 arc-window 逐章计划 v2。

采用的结构是：

1. arc-window v2 在每个章节 brief 中显式保存场景意图；
2. CHAPTER OutlineNode 的不可变 `planJson` 保存同一事实并由 `contentHash` 保护；
3. chapter-plan Job 创建时从目标 CHAPTER node、当前 Story Bible 和 Prompt Bundle 重算 `ChapterPlanExpectationV1`；
4. 规范 expectation JSON/hash 冻结进 Stage input；
5. Provider-open 与最终提交再次从同一批持久事实重算并逐字段比较。

## 为什么不能采用其他做法

### 让 chapter-plan 自己决定

这会让模型同时成为规则制定者和规则执行者。模型既可悄悄增加没有计划的相关场景，也可删掉原计划场景；重启或更换模型后结果无法重算，成年人门禁也会变成模型触发而不是计划触发。因此否决。

### 从人物或内容档案本地推导

“书里存在成年人物”或“全书允许更强呈现”都不能证明当前章节必然相关。把全书档案等同于逐章意图会错误强迫每章出现相关场景，因此否决。

### 新建独立意图表

如果由 arc-window 写入，会与 OutlineNode 形成两个可能漂移的事实源；如果由 chapter-plan 写入，仍然是模型自我授权。还会引入不必要的 Room migration 与生命周期，因此否决。

## 冻结合同

arc-window v2 每章新增：

- `relevantSceneIntent`：`NOT_APPLICABLE` 或 `PLANNED`；不用容易产生三态歧义的布尔值。
- `plannedRelevantSceneCount`：`NOT_APPLICABLE` 时必须为 0；`PLANNED` 时必须为 1..8。
- `participantCharacterIds`：`NOT_APPLICABLE` 时必须为空；`PLANNED` 时必须为 1..12 个不重复合法人物 ID。

选择 12 人上限，是为了覆盖群像章节，同时避免模型用超大参与者列表膨胀请求与验证成本。相关场景数量上限 8 与单窗口最多 8 章无关，它是单章可审计复杂度上限。

chapter-plan expectation 延续现有 v1 的身份和场景执行合同，并在后续实现中增加：

- 精确计划相关场景数量；
- 必须出现在至少一个相关场景中的人物 ID 集合。

validator 必须双向约束：

- `NOT_APPLICABLE`：输出中任何 `intimacyRelevant=true` 都失败；
- `PLANNED`：相关场景数量必须精确相等，不能少写也不能擅自增加；
- 每个计划参与者必须至少出现在一个相关场景；
- 相关场景不得出现未知、非人物、未确认成年、年龄缺失或小于 18 岁的人物；
- `Blocked` expectation 不得进入 Provider 请求。

## 版本和兼容策略

- 新远程输出合同：`arc-plan.v2`。
- 新窗口策略：`zhijuan.arc-window-policy.v2`。
- CHAPTER OutlineNode `planJson.schemaVersion=2`。
- chapter-plan Stage 来源根：由 `chapter-plan-source.v1` 升为 v2，并冻结 expectation canonical JSON/hash。
- 不修改 Room 表结构；不可变 JSON 和现有 hash 链足以承载新事实。
- 旧 `arc-plan.v1` / CHAPTER node schema v1 不做推测性回填，也不默认解释为 `NOT_APPLICABLE`；创建普通 chapter-plan Job 时失败关闭，要求用 v2 重建目标窗口。
- 首章 fast-lane 保持独立合同，不借用普通 chapter-plan 的 v2 窗口门禁。

## 权威人物门禁

参与者必须来自当前书、当前 Bible 的 active `StoryEntity`，且：

- `entityType == CHARACTER`；
- `adultStatus == CONFIRMED_ADULT`；
- `ageYears != null && ageYears >= 18`；
- ID 与计划中的参与者 ID 精确一致。

StoryEntity 当前没有独立的真人标记，因此本阶段不把“所有实体天然虚构”作为无条件事实。后续工厂实现必须沿用初始规划已经验证 `realIdentifiablePerson=false` 后才落库的来源链，并记录这是当前数据模型约束；若未来允许导入外部人物，需先扩充来源证明，不能沿用旧假设。

## 失败关闭和重算点

以下任一情况必须在 Provider-open 前以零请求失败：

- 目标窗口或 CHAPTER node 不是 v2；
- node JSON/hash、window revision、context/progression 或 bundle binding 变化；
- 场景意图字段缺失、额外、范围错误或相互矛盾；
- 参与人物未知、跨书、非 CHARACTER、未确认成年、年龄缺失或小于 18 岁；
- 冻结 expectation 与现场重算 canonical JSON/hash 不一致；
- Job/Stage exact-token、预算 reservation 或目的地证据失效。

## DeepSeek 使用情况

本阶段调用项目隔离的 DeepSeek V4 Flash 做只读架构审计，使用最高推理强度、无总 Token 上限，运行约 5 分 30 秒。运行前后 `git status --short` 均为 273 项，没有代码或文档差异，没有权限请求，没有真实 Provider 调用。Sol 采纳其“arc-window 权威意图 + Stage input 冻结绑定”主方向，否决让 chapter-plan 自决和新建影子状态；具体字段、上限、真人来源保留意见与实施边界由 Sol 复核后确定。

## 下一步

1. Phase 2E5C：实现 arc-window v2 parser、严格 schema、交叉 validator、持久化 mapper、Job/commit 版本门禁和 JVM/数据库回归。
2. Phase 2E5D：实现权威 expectation 工厂、规范 JSON/hash、Stage input v2 绑定与 create/open/commit 三次重算。
3. Phase 2E5E：完成 Fake-only exact-token streaming、严格解析、原子计划提交、initial DRAFT 和有限 registry 注册。

在 2E5E 完成前，`CHAPTER_PLAN_V1` 继续不注册，App 仍不能被描述为已完成端到端自动生书。
