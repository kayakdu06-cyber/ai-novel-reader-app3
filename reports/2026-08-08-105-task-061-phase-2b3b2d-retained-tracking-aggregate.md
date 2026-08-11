# 工作汇报 105：TASK-061 Phase 2B3B2D 首个保留章节 tracking→aggregate

> 日期：2026-08-08  
> 项目：织卷 Android 单人 AI 小说创作与阅读 App  
> 状态：Phase 2B3B2D 完成；TASK-061 与 TEST-033 仍进行中

## 1. 本阶段完成内容

1. 让 schema v15 retirement 绑定的第一个保留章节 replacement Stage 通过正式 Provider-open 与 commit 双门禁。
2. 新增受保护的 bound source loader；每次构造请求前都重验 execution、stable fence、current 章节范围、直接前驱、retirement、确定性 Job/Stage 和真实来源。
3. planner 不再把任意 `VALID` tracking 当成本次重建结果，只接受 `generationStageId` 精确指向 retirement replacement Stage 且完整 provenance 可复核的 projection。
4. replacement tracking 的业务写入与既有 aggregate writer 保持同一个 Room 外层事务；成功后 Stage/Job/Usage 一起结算，失败时不留下半套新派生。
5. 用本地 Fake Provider 走完正向、精确 replay 和 aggregate 故障回滚；没有调用织卷 App 内部真实模型接口。

## 2. 关键可靠性结论

- 旧 tracking/timeline/search 的 retirement 是此前已经完成的不可变准备事实。后续 aggregate 失败时，它不会被撤销；新 tracking/timeline/aggregate 和 FINAL 结算会回滚，Stage 保持 `COMMITTING`，因此既不会恢复使用旧年代数据，也不会失去恢复入口。
- Provider-open 和 commit 都会重新验证 retirement 与当前来源，不能靠创建过一次 Stage 绕过后续变化。
- 普通 tracking 顺序保护没有删除或放宽，专用许可仅对同一 execution 的 ordinal 4 生效。
- 成功 replay 只复核权威 tracking 与 aggregate，不重复创建 projection、timeline、伏笔 revision、搜索索引或 aggregate 代次。

## 3. 主要修改文件

- `core/database/.../generation/ChapterEditRebuildStageRepository.kt`：retirement-bound Provider/commit 授权、bound source loader、planner identity、同章 aggregate 和 replay/rollback 门禁。
- `core/database/.../library/ChapterEditRebuildPlanRepository.kt`：只认 exact replacement Stage 生成的 retained tracking。
- `feature/generation/.../ChapterTrackingProjectionEndToEndTest.kt`：新增 Fake Provider 正向/replay和 aggregate 故障回滚。
- `docs/06、08、09、10、15、19、20、22、24` 与 `docs/ai/CURRENT-CONTEXT.md`：同步当前事实、证据和未完成边界。

## 4. 验证证据

| 验证 | API 30 | API 35 |
|---|---:|---:|
| `ChapterTrackingProjectionEndToEndTest` | 7/7 | 7/7 |
| `ChapterEditRebuildPlanDatabaseTest` | 19/19 | 19/19 |
| `:feature:generation` Android 全量 | 33/33 | 33/33 |
| `:core:database` Android 全量 | 183/183 | 183/183 |

- `:core:database` JVM：70/70。
- `:feature:generation` JVM：117/117。
- `scripts/security-scan.ps1 -SkipArtifacts`：`SECURITY_SCAN_OK`。
- `git diff --check`：退出码 0，仅有既有换行提示。
- App 内真实 Provider 调用：0。
- 物理设备写入：0。
- Git remote：无。

## 5. DeepSeek 使用说明

本阶段没有再次调用 DeepSeek。最终工作属于跨模块状态机、数据库事务与执行身份裁决，必须由 Sol 负责。用户已授权在后续任务中放宽 DeepSeek 思考时长；考虑到前两次宽泛审计分别在 15 分钟和 30 分钟内都没有形成最终回交，后续仅把文件、方法、测试边界明确的窄任务交给 DeepSeek，并在任务包中记录延长时限的理由。

## 6. 明确未完成

- 当前仍只允许 ordinal 4，即编辑章后的第一个保留章节。
- ordinal 6 及以后逐章 retirement→Provider→tracking→aggregate 通用迭代尚未实现。
- TEST-033 的 10 章编辑后全区间闭环、execution 完成状态、context/consistency 最终接线和总 runner 尚未完成。
- 本切片没有运行统一 Release/R8；留到 TASK-061 完成通用循环与 TEST-033 后执行。

## 7. 下一步

下一阶段采用“显式目标 step ordinal”的确定性命令，而不是在一次无目标 `next` 调用中猜测崩溃前处理到哪一章。每个后续 tracking 只有在其直接前一章 replacement tracking 与 aggregate 都以相同 execution 的完整证据完成后，才允许退役旧基线并创建新的不可变 Stage。完成通用迭代后，用 10 章编辑第 3 章的 Fake Provider 场景收口 TEST-033。
