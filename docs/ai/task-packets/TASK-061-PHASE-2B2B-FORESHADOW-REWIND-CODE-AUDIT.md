# TASK-061 / Phase 2B2B：伏笔受审计 rewind 代码审计

## 任务身份

- 任务 ID：`TASK-061 / Phase 2B2B 伏笔受审计 rewind`
- 仓库根目录：`D:\gptuser\projects\ai-novel-reader-app2`
- 基准分支与 HEAD：`main` / `8ce7744`，继续大型 dirty WIP，禁止回退或清理。
- 当前未提交改动：Phase 1、2A、2B1、2B2A 与本阶段 schema v13/rewind WIP 混合存在；只审计实际文件，不从 HEAD 猜当前实现。
- 执行模型：DeepSeek V4 Flash（纯文本、只读审计）

## 运行预算

- 推理等级：`max`
- 最长运行时间：15 分钟
- 累计 Token 上限：无（用户持续指令）；仍受时间、文件和命令边界约束。
- 预计读取文件：本任务包列出的 11 个文件。
- 预计执行命令：最多 8 条只读搜索/差异命令；禁止构建、测试和写文件。
- 提前停止条件：需要修改工作树、需要扩大到 aggregate/runner/Provider、需要读取密钥/日志/其他项目，或发现无法在清单内证实的推测。

## 目标

独立审计 Sol 已实现并在 API 30/API 35 专项各 11/11 通过的 schema v13 伏笔 rewind。重点寻找会导致错误基线、错误回卷、历史丢失、非原子提交、重放误接受或越权恢复终态的 P0/P1 问题，并给出最小修复建议；只返回文字，不改代码。

## 当前现场与已有 WIP

- schema v12 已有每 transition 唯一、不可变的完整 post-CAS `foreshadow_projection_revision`。
- schema v13 新增不可变且每 plan 唯一的 `foreshadow_projection_rewind` 审计记录及来源触发器。
- 新 repository 在一个 Room 事务中重验完整 `ChapterEditRebuildPlan`，读取编辑区间全部 transition 历史，选择编辑点之前最后一个 VALID/current-version revision 作为可信基线。
- 无基线时，只有受影响区间最早操作是 `PLANT/from null` 才按“编辑点前不存在”处理；其他 legacy 数据失败关闭。
- 事务先 stale 区间 VALID revision，再 stale transition；然后使用完整字段 CAS 恢复基线 item，或把区间内新建 item 标为 STALE；同步删除/重建伏笔 FTS，最后写不可变审计。
- exact replay 重验 plan、区间历史、baseline set hash、零 VALID 区间历史和 current after-set hash，不重复写入。
- 新测试覆盖：A 在第 1 章种下、第 2 章发展、第 3 章解决；B 第 3 章新种下；编辑第 2 章后 A 恢复第 1 章完整状态、B 变 STALE、历史保留、索引修复、精确 replay；另有 legacy DEVELOP 无 pre-edit revision 整体回滚。
- 已有验证：Kotlin/Room 编译通过；API 30/API 35 的新 rewind + 完整 migration 测试各 11/11。

## 必读资料

1. `AGENTS.md`
2. `docs/24-AI-DEVELOPMENT-PROTOCOL.md`
3. `docs/ai/CURRENT-CONTEXT.md`
4. 本任务包
5. `core/database/src/main/kotlin/app/zhijuan/core/database/library/ForeshadowProjectionRewindRepository.kt`
6. `core/database/src/main/kotlin/app/zhijuan/core/database/memory/MemoryDao.kt`（只读 rewind/revision/transition 相关方法）
7. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ForeshadowProjectionRevisionWriter.kt`
8. `core/database/src/main/kotlin/app/zhijuan/core/database/LibraryDatabaseGuards.kt`（只读 foreshadow 相关触发器）
9. `core/database/src/main/kotlin/app/zhijuan/core/database/memory/DerivedAuditEntities.kt`
10. `core/database/src/main/kotlin/app/zhijuan/core/database/library/ChapterEditRebuildPlanRepository.kt`
11. `core/database/src/androidTest/kotlin/app/zhijuan/core/database/ForeshadowProjectionRewindDatabaseTest.kt`

除上述清单和直接类型定义外，不得递归扫描整套文档、日志、会话、备份、密钥或其他项目。

## 范围

允许：

- 只读检查实际 WIP 和限定文件的 `git diff`。
- 检查基线选择、区间边界、stale 顺序、完整 CAS、事务回滚、audit/replay、legacy 失败关闭、FTS 修复和隐私错误信息。
- 按 P0/P1/P2 列问题；每项必须有文件/代码证据、失败场景和最小修复。

明确不在范围：

- 修改任何文件、构建或测试。
- aggregate writer、跨章 runner、Provider/费用、UI、TEST-033 总实现。
- 放宽普通终态转换、删除历史、伪造 legacy checkpoint、访问 App 内真实生成 API。

## 不可破坏的约束

- 不得删除/覆盖 transition、revision 或派生历史；只允许 `VALID → STALE`。
- 普通生成不得把 `RESOLVED/ABANDONED` 重新打开；仅该专用 audited rewind 可以通过完整 CAS 恢复过去快照。
- rewind 必须绑定同书 USER_EDIT current version、其 parent、完整 plan/range/hash、单调提交时间，并在一个 SQLCipher/Room 事务完成。
- 旧数据无法证明编辑点之前状态时失败关闭，不从不完整 transition 猜字段。
- stale 顺序必须是 revision 在前、transition 在后；失败必须整体回滚。
- exact replay 不得因为 later-current 合法变化误接受，也不得重复写审计或索引。
- 默认错误与 `toString()` 不得展开描述、JSON、hash、正文、人物或 ID。
- 真实 Provider 0、网络 0、物理设备写入 0、Git remote 操作 0。

## 必须回答

1. `latestTrustedForeshadowProjectionRevisionsBeforeChapter` 是否真的为每个 item 唯一选择“编辑点之前、仍属于 current chapter version 的最后可信 revision”，是否有并列/旧版本/跨书漏洞？
2. 从区间全部历史求 affected item，再只 stale VALID 区间行，是否会漏掉应回卷的 item 或误伤编辑点前历史？
3. 无 baseline 且 earliest affected 为 PLANT 的“此前不存在”判断是否充分；给出可构造反例（如有）。
4. 完整字段 CAS 与事务内 plan 重验能否阻止并发/陈旧命令；恢复过去 `updated_at` 是否会破坏现有不变量？
5. exact replay 的验证集合是否足以区分真正重放、不同 rewind ID、后来重建以及 current item 被改写？
6. 搜索索引删除所有 affected identity 后仅重建 baseline item，是否与权威 hydration 的 chapter index/current-version 条件一致？
7. v13 trigger/unique plan hash 是否足以阻止伪造、更新、删除和同 plan 多审计？
8. 若没有 P0/P1，请明确说明；不要为了填充报告提出无证据问题。

## 验收标准

- [ ] 只读，无工作树改动。
- [ ] 结论基于当前实际代码，不只复述任务包。
- [ ] P0/P1 每项包含可复现路径和最小修复。
- [ ] 不把本阶段冒充 TASK-061 或整 App 完成。

## 回交格式

1. `结论`
2. `P0/P1 问题`
3. `P2/后续风险`
4. `已满足的不变量`
5. `最小修复建议`
6. `需要 Sol 决策`

只返回文字；不得修改文件、不得宣布任务完成。
