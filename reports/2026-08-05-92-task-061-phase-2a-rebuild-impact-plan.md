# 工作汇报 92：TASK-061 Phase 2A 重建影响计划与版本栅栏

> 日期：2026-08-05  
> 项目：织卷 Android App  
> 唯一仓库：`D:\gptuser\projects\ai-novel-reader-app2`  
> 状态：TASK-061 进行中；Phase 1 与 Phase 2A 已完成，真正的跨章重建仍待后续阶段

## 1. 本阶段结果

本阶段没有假装“重建已经完成”，而是先把一次编辑到底会影响什么、哪些步骤现在能安全执行、哪些步骤为什么不能执行，变成数据库可验证的确定性计划。

新增的 `ChapterEditRebuildPlanRepository` 会在一个 Room 事务内：

1. 验证目标是当前未解决的 `USER_EDIT` 版本，并核对书、章节与 parent；
2. 从编辑点到最新正式章批量冻结 current version、正文 hash、章节状态和一致性状态；
3. 为每章建立有序的 memory、tracking、context、consistency 与 aggregate 影响步骤；
4. 给每一步标记 `READY`、`WAITING_FOR_DEPENDENCY`、`ALREADY_SATISFIED` 或 `BLOCKED`，同时记录精确 blocker；
5. 计算不含正文的稳定 `planHash`；真正执行前通过 `requireCurrentMatches` 重建完整计划，拒绝陈旧计划。

10 章、编辑第 3 章的固定场景得到：

- 32 个影响步骤；
- 1 个 READY：编辑章记忆提取；
- 31 个 BLOCKED；
- 17 个将来可能需要 Provider 的步骤；
- 第 4–10 章共 7 章正文和 current version 继续保留。

规划过程不创建 Job、Stage、Attempt 或 Usage，不调用 Provider，也不修改业务数据。

## 2. 为什么其余 31 步现在不能直接执行

审计确认，现有 schema 和提交入口不支持简单地“把旧流程再跑一遍”：

- `chapter_summary.chapter_version_id` 等派生表使用唯一版本槽，旧历史不能被安全覆盖；
- `chapter_tracking_projection.chapter_version_id` 也只有一个槽；
- `aggregate_state_projection(book_id, through_chapter_index)` 唯一，且当前没有正式重建 writer；
- `foreshadow_transition(foreshadow_item_id, source_chapter_version_id)` 唯一，历史状态需要明确 replay 语义；
- tracking 来源仓库会在存在后续已提交章节时拒绝较早章节的单章重建，这是防乱序保护，不能为了“跑通”而删除。

因此 Phase 2A 将这些情况显式标成 `DERIVED_VERSION_SLOT_OCCUPIED`、`TRACKING_ORDER_GUARD`、`AGGREGATE_REBUILD_UNSUPPORTED` 或 `DEPENDENCY_BLOCKED`。这比覆盖旧派生或跳过保护可靠，也为下一阶段的 schema/writer 设计提供了准确边界。

## 3. DeepSeek 审计与 Sol 修正

本阶段按任务包使用项目隔离的 DeepSeek V4 Flash 做只读架构与补丁提案：

- 任务包：`docs/ai/task-packets/TASK-061-PHASE-2A-REBUILD-IMPACT-PLAN.md`
- 运行 ID：`20260805-133041-8930ec46`
- 有效沙箱：`read-only`
- 推理等级：`max`
- 耗时：约 10 分钟
- 累计 Token：820,370；其中缓存输入 673,024，输出 68,212
- 结果：正常返回架构分析和 diff；没有写工作树，没有调用 App Provider。

DeepSeek 正确识别了唯一槽、tracking 顺序门禁和 aggregate writer 缺失。但其提案只规划了编辑章 tracking，遗漏了后续正式章节也必须顺序 replay。Sol 补齐了编辑点至最新章的完整 tracking/context/consistency/aggregate 步骤，并做了两项长篇性能加固：

- 将逐章读取 current version 和 tracking 改为两个批量 DAO 查询；
- 将反复扫描前驱步骤的 O(n²) 逻辑改为按 ordinal O(1) 引用。

## 4. 代码与测试改动

### 生产代码

- `core/database/src/main/kotlin/app/zhijuan/core/database/library/ChapterEditRebuildPlanRepository.kt`
  - 新增保留后续正文策略、步骤类型/状态/blocker、冻结章节、完整计划与脱敏诊断；
  - 新增只读计划事务和执行前完整版本栅栏；
  - 未实现的 `REGENERATE_FROM_NEXT` 策略明确失败关闭。
- `core/database/src/main/kotlin/app/zhijuan/core/database/library/LibraryDao.kt`
  - 新增按书一次读取章节与 current version 的批量 join。
- `core/database/src/main/kotlin/app/zhijuan/core/database/memory/MemoryDao.kt`
  - 新增从指定章节起批量读取 tracking projection 的只读查询。

### 测试

- `core/database/src/androidTest/kotlin/app/zhijuan/core/database/ChapterEditRebuildPlanDatabaseTest.kt`
  - 10 章编辑第 3 章的稳定 32 步计划及零写入；
  - 最新章从 memory READY 到 ALREADY、tracking READY 的状态前移；
  - 编辑章派生状态变化和后续 current version 变化都会使旧 plan 失效；
  - 非编辑 current、跨书和未实现策略失败关闭；
  - request/plan/step 默认字符串不泄露正文和标识符。

## 5. 验证证据

| 验证 | 结果 |
|---|---|
| 生产与 AndroidTest 编译 | 通过 |
| Phase 2A 定向测试，API 30 | 4/4 通过 |
| Phase 2A 定向测试，API 35 | 4/4 通过 |
| `core/database` 全量，API 30 | 150/150 通过 |
| `core/database` 全量，API 35 | 150/150 通过 |
| `scripts/verify-build.ps1 -Offline` | 797 actionable tasks，BUILD SUCCESSFUL |
| Release/R8 | 通过 |
| 安全扫描 | `SECURITY_SCAN_TESTS_OK`、`SECURITY_SCAN_OK`，源码与 5 个 APK |
| 备份排除策略 | `BACKUP_EXCLUSION_POLICY_OK` |
| `git diff --check` | 通过；仅现有 LF/CRLF 提示 |

所有验证均使用本地固定夹具和项目专用 API 30/API 35 模拟器。App 内真实生成 API 调用 0，物理设备安装、写入和设置修改 0。

## 6. 明确未完成项

- TEST-033 仍未完成；本阶段只证明影响范围和执行前版本一致性，没有执行跨章重建。
- 现有派生表还没有可版本化历史槽，不能安全覆盖旧摘要、tracking、aggregate 或伏笔转换。
- 当前只支持 `KEEP_EXISTING`；“从下一章重织”仍需用户决策、费用边界和后续生成编排。
- 当前 App 仍没有按 phase 分发的总 runner，不能描述为已经能够自动完成整本书生成。

## 7. 下一阶段

继续 TASK-061 Phase 2B：先为摘要/追踪/聚合及伏笔转换确定可审计的历史版本化与 replay 语义，再实现从编辑点向后的有序本地/Provider 步骤执行。完成后用 Fake Provider 建立 TEST-033 的 10 章端到端证据，并继续保持真实 API 与物理设备写入为 0。
