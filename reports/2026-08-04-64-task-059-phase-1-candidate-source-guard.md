# 工作汇报 64：TASK-059 第一阶段候选来源门禁

日期：2026-08-04

## 结论

TASK-059 第一阶段已经完成并通过独立验收：候选 BODY → MEMORY → TRACKING → CONSISTENCY → REVISE 链在每次打开 Provider 前都会重新核对当前 Stage、同 Job/章节、直接前驱、封存输出、候选版本/hash、章节序号、修订序号和 `nextStageId`。无效证据会在取得发送权之前失败，不会把 Attempt 标成已打开 Provider，也不会把候选正文提前发布为正式章节。

本结论只覆盖“候选 Stage Provider-open 来源门禁”。完整 TASK-059 仍未完成；实际有限修订编排、每轮派生重跑、最终提交接线和 NEEDS_ACTION 端到端恢复仍是后续阶段。

## 已完成修改

- 新增候选来源单一守卫，并接入统一 `GenerationRequestAuditRepository` Provider-open 入口。
- 合法候选链通过守卫后直接结束候选校验，避免被正式版本 memory/tracking 守卫或普通 `chapterProgressionGate` 误杀。
- 删除正式 memory/tracking 守卫中“看见候选 binding 就无验证跳过”的旧捷径。
- 候选 binding 与前驱 output reference 使用严格 JSON 字段集合、字段类型、schema/pipeline、role/phase、标识符、hash、章节和修订范围校验。
- 非候选 binding 与“声明为候选但证据损坏”分开处理；后者统一转为 `StaleGenerationStateException`，失败关闭。
- 候选 lineage 逐级回溯到已成功封存的初始 DRAFT BODY，并复核初始正文的章节推进与上下文来源门禁。
- 删除已无调用且会吞掉候选解析错误的 `runCatching(...).getOrDefault(false)` 判定。

## 测试覆盖

`ChapterFinalCandidateCommitDatabaseTest` 现有 10 项真实 Room/受保护 artifact 测试，新增或强化以下证据：

1. BODY → MEMORY → TRACKING → CONSISTENCY → COMMIT 合法链可原子发布。
2. CONSISTENCY → REVISE BODY 合法修订链可以通过 Provider-open，并为新候选创建下一 memory Stage。
3. 前驱 `nextStageId` 被篡改时，在发送权领取前拒绝。
4. 前驱 output reference 为损坏 JSON 时，在发送权领取前拒绝。
5. 当前 binding 使用陈旧候选 hash 时拒绝。
6. 当前 binding 使用陈旧修订序号时拒绝。
7. 当前 binding 指向另一章节而前驱仍属于原章节时拒绝。
8. 上述拒绝均保持 Attempt=`INTENT_RECORDED`、Usage=`PROVISIONAL`、Stage=`REQUEST_INTENT_RECORDED`。
9. 原有外键故障全事务回滚、精确 replay 和并发最终提交测试继续通过。

## 验证结果

- DeepSeek 隔离自检：通过；`workspace-write`、Windows restricted-token、15 分钟、1,000,000 Token 和 `max` 推理均生效。
- 数据库 JVM 测试与 AndroidTest 编译：通过。
- API 35 `emulator-5554`，只运行 `ChapterFinalCandidateCommitDatabaseTest`：10/10 通过，0 失败、0 跳过。
- `scripts/verify-build.ps1 -Offline`：通过；371 个 Gradle task 成功，Debug APK、Release manifest、全项目 JVM 测试、安全扫描和备份排除策略均通过。
- `git diff --check`：通过。
- 真实织卷 Provider 调用：0。
- 物理设备写入：0。

## DeepSeek 运行记录

- 按用户指令，项目配置、启动器、协议、运行手册和任务包已统一使用最高推理等级 `max`；时间与 Token 硬上限保持不变。
- 一次初始启动被外层 5 秒命令时限误中断，遗留的受限 Codex 子进程随后按精确 PID 树终止；记录用量 203,188 Token，无业务代码差异。
- 正式受限运行 `20260804-052729-b3d03f2e` 在约 6 分钟触发 1,000,000 Token 门禁，实际记录 1,002,947 Token，启动器返回 125 并清理完整进程树；模型完成了部分只读审计，但没有形成可采纳代码差异或最终回交。
- 本阶段两次 DeepSeek 运行合计记录 1,206,135 Token；实际计费以 DeepSeek 后台为准。
- Sol 随后独立审查并完成代码、测试和验收，没有采信模型未完成的“完成”状态。

## 修改文件

- `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterCandidateArtifactSealRepository.kt`
- `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationRequestAuditRepository.kt`
- `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterMemoryExtractionJobFactory.kt`
- `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterTrackingProjectionJobFactory.kt`
- `core/database/src/androidTest/kotlin/app/zhijuan/core/database/ChapterFinalCandidateCommitDatabaseTest.kt`
- `.codex/config.toml`
- `.codex/models.json`
- `scripts/start-deepseek-codex.ps1`
- `docs/24-AI-DEVELOPMENT-PROTOCOL.md`
- `docs/ai/CURRENT-CONTEXT.md`
- `docs/ai/TASK-PACKET-TEMPLATE.md`
- `docs/ai/DEEPSEEK-RUNBOOK.md`
- `docs/ai/task-packets/TASK-059-PHASE-1-CANDIDATE-SOURCE-GUARD.md`

## 下一阶段

继续审计并接通正式整章协调器：根据一致性结论决定 ACCEPT/REVISE/NEEDS_ACTION；每次修订必须生成新候选版本/hash，重新运行 memory、tracking、consistency；达到上限、循环、正文不变或长度失败时原子进入 NEEDS_ACTION；最终再用现有事务仓库一次性发布正文、全部派生数据、报告、Usage 和 Job/Stage/书籍进度。
