# 工作汇报 72：最终 COMMIT Stage 完整来源绑定

> 日期：2026-08-04  
> 阶段：TASK-059 第九阶段  
> 结论：本阶段完成；完整 TASK-059 仍在进行中

## 1. 本阶段完成内容

最终 COMMIT Stage 不再只记住“当前候选是谁”，而是持久冻结恢复和重放必需的完整来源：

- 候选版本 ID、正文 hash、章节 ID/序号和 revision index；
- 直接 CONSISTENCY 前驱 Stage；
- 完整 route binding；
- 生成开始时预期的当前章节版本 ID，可为真正的 JSON null；
- 同一策略给出的自动修订上限 1 或 2；
- 从初始正文到当前候选的完整、无重复 SHA-256 历史。

新增 `ChapterFinalCommitStageBindingV1` 作为唯一构造/解析入口。它使用 exact keys、identifier/hash/范围、history 长度/末项/去重、Stage phase/target/maxAttempts 和 input hash 复算门禁。封套形状发生不兼容扩展，因此 Sol 将来源策略标识从 v1 升级为 v2，避免新旧格式使用同一版本号。

最终提交仓库在任何正式正文、摘要、事实、时间线、伏笔或报告写入前，重新解析该封套并与：

- 最终提交草稿；
- CONSISTENCY artifact evidence；
- CONSISTENCY 封存输出；
- 章节真实序号；

逐项核对。修改修订上限、候选历史、预期父版本、前驱或 route binding 都不能进入正式事务写入。

`ChapterRevisionPolicyDecisionV1.AcceptCandidate` 同时携带策略计算出的自动修订上限，数据库模块没有复制“比例 1 次、严格 2 次”的模式判断。

## 2. DeepSeek 执行情况

- 运行 ID：`20260804-110628-a2c2087b`；
- 模式：只读补丁提案，DeepSeek 没有项目修改权限，也没有请求修改权限；
- 推理强度：`max`；
- 最长时间：20 分钟；总 Token 上限不设置；
- 实际耗时：约 9 分 49 秒；
- 总处理 Token：2,011,965；缓存输入 1,736,832；输出 66,976；推理输出 49,529；
- 交付：严格限制在 5 个允许文件的完整补丁提案；
- 真实 Provider API、网络费用和物理设备写入：0。

与上一阶段约 20 分钟相比，本次把职责缩为“来源封套 + 最终事务复核”后耗时约减半，说明继续按单一安全边界拆分是有效的。

## 3. Sol 审查与修正

Sol 应用提案前确认：

- REVISE 和 NEEDS_ACTION 路线未被修改；
- nullable expected current 使用 `JsonNull`，不是字符串 `"null"`；
- history 不出现在错误或默认字符串中；
- 最终仓库保留全部既有 artifact、Attempt、Usage、lineage、lease 和事务门禁；
- 新核对发生在所有正式行写入前，也覆盖 SUCCEEDED 精确 replay。

Sol 做出的唯一语义修正是把来源策略版本升级为 `zhijuan.chapter-final-commit-stage-source.v2`。DeepSeek 提案沿用了 v1，但字段集合已不兼容；继续叫 v1 会让以后恢复器无法区分旧封套和新封套。

第一次模拟器命令因 PowerShell 参数拆分而在 2 秒内失败，测试没有启动、模拟器没有执行用例。改为明确参数数组后正常运行；这不是代码失败，也没有被计入通过证据。

## 4. 测试证据

- `ChapterRevisionPolicyTest`：7/7；接受路线同时验证修订上限；
- `ChapterConsistencyAcceptanceGateTest`：8/8；路由规格显式传递 expected current；
- API 35 模拟器 `emulator-5554` 最终候选数据库专项：19/19；
- 新负例把合法草稿上限从 1 改成 2，或把 null expected current 换成其他版本 ID，均在事务写入前失败；版本、summary、report 不产生，final Stage 保持 COMMITTING，Job 保持 RUNNING；
- 统一离线门禁：Gradle 371 项任务通过；
- 安全扫描与 Android 备份排除检查通过；
- `git diff --check` 无新增格式错误。

只连接并写入项目模拟器 `emulator-5554`。没有实体设备连接、安装或写入。

## 5. 尚未完成

final Stage 现在已经保存足够的候选 lineage 元数据，但仍缺少把全部持久证据自动重建成最终草稿并执行提交的生产入口。下一阶段需要：

1. 从 final Stage、Job、Chapter、四段 Stage/Attempt 和冻结请求中恢复 book ID、模型快照、Stage ID、场景/检查输入与提交时间；
2. 调用 Phase 8 的 artifact 恢复器得到正文和三类严格模型；
3. 只调用现有 memory/tracking/consistency persistence mapper 和最终草稿映射器；
4. 在有效 COMMIT lease 下调用最终数据库仓库；
5. 补齐进程重启、lease 过期、事务失败、并发和 SUCCEEDED replay 的端到端恢复证据。

完整 TASK-059 收口前仍需 API 30/API 35 全量、Release/R8 和发布级门禁。
