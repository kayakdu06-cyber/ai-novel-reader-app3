# TASK-061 / Phase 2B2A：伏笔投影修订账本代码审计

## 任务身份

- 仓库根目录：`D:\gptuser\projects\ai-novel-reader-app2`
- 基准 HEAD：`8ce7744`；当前为大型 dirty WIP，禁止回退、清理或覆盖。
- 当前阶段：schema v12 与伏笔 after-state revision writer 已由 Sol 实现并通过 API 30/API 35 数据库全量各 154/154。
- 执行模型：DeepSeek V4 Flash，只读代码审计。

## 运行预算

- 推理等级：`max`
- 最长运行时间：10 分钟
- 不设置累计 Token 上限
- 最多读取 10 个文件，最多执行 8 条只读命令
- 禁止修改工作树、禁止构建、禁止测试
- 提前停止：需要扩张到实际 rewind/runner、需要读取日志/密钥/其他项目，或结论必须依赖未授权文件

## 目标

审计 Phase 2B2A 的实际差异，判断新 revision 账本是否真的保存了未来 rewind 所需的完整、可验证 after-state，并检查 schema、触发器、writer、两条提交接线、replay 与 stale 顺序中是否存在 P0/P1 原子性或一致性漏洞。

本次只返回问题和最小修复建议，不写代码，不宣布 Phase 2B2/TASK-061 完成。

## 当前现场与已有 WIP

- 新增 `foreshadow_projection_revision`，每个 transition 唯一一条完整 after-state revision。
- `ForeshadowProjectionSnapshotCodecV1` 规范编码完整 `ForeshadowItemEntity` 并做 SHA-256 校验。
- `ForeshadowProjectionRevisionWriterV1` 在真实 item CAS/insert 和 transition insert 后读取数据库 current item，再写 revision；两条生产提交路径共用 writer。
- replay 只验证不可变 revision 与 transition，不要求当前 item 仍等于当时快照。
- 编辑失效先 stale revision，再 stale transition；触发器禁止仍有 VALID revision 时直接 stale transition。
- v11→v12 不伪造旧 revision，旧 transition 保留但 revision 表为空。
- 实际 rewind、区间失效、重建 Job/Stage 和 TEST-033 仍未实现。

## 必读资料

1. `AGENTS.md`
2. `docs/24-AI-DEVELOPMENT-PROTOCOL.md`
3. `docs/ai/CURRENT-CONTEXT.md`
4. 本任务包
5. `core/database/src/main/kotlin/app/zhijuan/core/database/memory/DerivedAuditEntities.kt`
6. `core/database/src/main/kotlin/app/zhijuan/core/database/memory/MemoryDao.kt`
7. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ForeshadowProjectionRevisionWriter.kt`
8. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterTrackingProjectionCommitRepository.kt`
9. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterFinalCandidateCommitRepository.kt`
10. `core/database/src/main/kotlin/app/zhijuan/core/database/LibraryDatabaseGuards.kt`

不读取日志、密钥、其他项目、小说内容或与本阶段无关的模块。

## 范围

允许：

- 读取上述文件及其当前 `git diff`。
- 检查 v12 revision 的字段完整性、唯一性、外键、索引、触发器、writer 原子接线和 replay/stale 不变量。
- 指出一至三个有明确证据的 P0/P1 问题，或明确说明未发现。

不允许：

- 修改任何文件。
- 设计或实现完整 rewind、runner、Provider 调用或 TEST-033。
- 建议删除历史、伪造 v11 快照、放宽终态伏笔转换规则。

## 不可破坏的约束

- 所有 revision 必须由实际数据库 post-CAS item 派生，不得由模型 partial DTO 补全。
- snapshot hash、规范 JSON、transition/stage/source version/book/item/chapter/story order 必须能在消费时全部复核。
- 普通生成不得把 `RESOLVED/ABANDONED` 重新打开。
- 旧数据缺可信 after-state 时失败关闭。
- revision/transition 不得删除、篡改或 `STALE → VALID`。
- 两条生产提交路径必须在同一 Room 事务内共用一个 writer。
- replay 后可能已有更晚章节改变 current item，因此 replay 不得拿当前 item 与旧 after-state 强行相等。
- 默认错误和 `toString` 不展开描述、JSON、hash 或标识符。
- 真实 API 0，物理设备写入 0。

## 必须回答

1. 完整 after-state 是否覆盖 future rewind 所需全部 current item 字段？有无字段语义丢失？
2. 是否存在 transition 已成功但 revision 缺失、或 revision 有效但 transition 已 stale 的可提交状态？
3. replay 校验是否既能发现缺账/篡改，又不会因后续合法章节改变 current item 而误拒绝？
4. stale 顺序和触发器是否能在 Phase 1 编辑失效中保持事务一致？
5. v11→v12 空表策略是否明确失败关闭，还是存在暗中把 legacy transition 当可信基线的入口？
6. 请按 P0/P1/P2 分级；每项必须给出文件、代码证据、影响和最小修复，禁止泛泛建议。

## 验收标准

- [ ] 只读，无工作树改动。
- [ ] 结论以实际代码为依据。
- [ ] 不把尚未实现的 rewind/runner 写成已完成。
- [ ] 不要求模型执行 Android/视觉验收。

## 回交格式

1. `结论`
2. `P0/P1 问题`
3. `P2/后续风险`
4. `已满足的不变量`
5. `最小修复建议`
6. `需要 Sol 决策`

禁止宣布 Phase 2B2/TASK-061 完成。
