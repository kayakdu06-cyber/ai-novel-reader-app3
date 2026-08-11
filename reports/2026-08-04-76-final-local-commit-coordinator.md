# 工作汇报 76：最终候选纯本地提交协调器

> 日期：2026-08-04  
> 阶段：TASK-059 第十阶段 B3  
> 结论：本小阶段完成；完整 TASK-059 仍在进行中

## 1. 本阶段完成内容

新增 `ChapterFinalCandidateCommitCoordinatorV1`，把此前已经分别完成的恢复和提交零件接成一条唯一生产路径：

1. 从 final Stage v3 恢复数据库证据；
2. 严格解析一致性映射快照并复核外层来源；
3. 从受保护 artifact 恢复正文、记忆、追踪和一致性报告；
4. 使用既有三套 persistence mapper 重建派生行；
5. 重新执行有限修订策略，确认当前候选仍应被接受；
6. 使用既有最终 draft mapper 组装提交草稿；
7. 所有本地检查成功后，才把 PREPARING 推进到 COMMITTING；
8. 最后只调用既有最终 SQLCipher 原子事务发布正文和全部派生数据。

该路径不联网、不调用模型、不创建新的 Attempt/Usage，也不复制数据库事务。READY 尚未取得执行资格，SUCCEEDED 已由上层观察为完成；两者都会在读取 artifact 前拒绝。

## 2. 修订候选来源缺口

审计发现 final source 的 `routeBindingHash` 代表“接受当前候选的最终策略”，而重建 `ChapterCandidatePipelineIdentityV1` 需要的是“产生当前修订正文时的策略”。一次修订后这两个值通常不同，不能混用。

数据库恢复链本来已经读取到正确的候选 binding，本阶段将其作为 `candidateRouteBindingHash` 明确返回：

- 初始候选必须为 null；
- 修订候选必须非空，并来自连续 BODY→MEMORY→TRACKING→CONSISTENCY 链；
- 最终协调器只用它重建候选身份；
- final source route 仍通过重新计算的有限策略 hash 单独复核。

## 3. 崩溃恢复与状态顺序

- PREPARING：使用本次 `requestedAt` 生成全部派生草稿；只有解析、artifact、映射和策略全部通过后才执行 `LOCAL_OUTPUT_READY`。
- COMMITTING：使用持久 `finalStageUpdatedAt` 重建相同时间戳的派生行，不重复状态转换，然后进入最终事务。
- 状态转换返回的 Stage ID、状态、租约和时间必须与请求完全一致，否则不调用最终仓库。
- 最终事务仍会独立复核 artifact、Attempt、FINAL Usage、租约、当前章节版本、外键、并发和 replay；协调器不是绕过事务的新许可。

## 4. DeepSeek 执行与 Sol 审查

DeepSeek 运行：

- 运行 ID：`20260804-132119-3622fba5`；
- `DeepSeek-V4-Flash`，`max` 推理，`workspace-write`，20 分钟上限，无总 Token 上限；
- 实际约 8 分 55 秒，退出码 0；
- 总处理 2,386,514 Token，缓存输入 2,131,840，输出 47,968，推理输出 26,016；
- 只新增授权的协调器文件，并完成 Kotlin 编译；没有请求修改权限，没有触碰数据库事务、测试或其他项目。

运行日志记录了两次初始文件写入失败，之后同一任务成功落盘并正常结束。Sol 没有只依据退出码，而是独立检查真实文件、范围、最终回交和 stderr。

Sol 后续完成：

- 将四个生产依赖封装成内部可替换边界，公开生产构造函数仍注入原四个真实对象；
- 清零 UTF-8 计数字节数组；
- 加强 PREPARING→COMMITTING 返回证据复核；
- 新增 6 项协调器 JVM 测试；
- 为数据库恢复结果补候选 binding 字段和初始/修订断言；
- 更新状态、测试和追踪文档。

## 5. 验证证据

- 协调器 JVM：6/6；
- 相关 JVM 链：33/33，其中快照 7、artifact 恢复 5、最终 mapper 7、一致性接受/分流 8、协调器 6；
- API 35 `emulator-5554` 最终候选数据库专项：25/25；
- 统一离线门禁：371 个 Gradle task 成功；
- 当前 JVM 报告：456 项，0 失败、0 错误、0 跳过；
- 安全扫描：`SECURITY_SCAN_OK`；
- 备份排除：`BACKUP_EXCLUSION_POLICY_OK`。

设备列表只有 `emulator-5554`，没有实体设备安装或写入。App 内真实 Provider/API 调用为 0；DeepSeek 只作为项目隔离的编码模型使用。

## 6. 尚未完成

本小阶段已经完成 final Stage 的纯本地提交入口，但完整 TASK-059 仍不标记完成。下一阶段需要：

1. 审计并接入实际 Stage 执行调度，确保生产 runner 领取 `COMMIT_CHAPTER` lease 后只调用该协调器；
2. 补齐 API 30/API 35 全量证据；
3. 完成 Release/R8 与完整任务收口复核；
4. 确认没有仍由测试或上层直接手工拼装最终 draft 的生产旁路。
