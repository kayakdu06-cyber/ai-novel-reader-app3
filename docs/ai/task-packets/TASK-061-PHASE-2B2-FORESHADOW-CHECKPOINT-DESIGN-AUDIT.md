# TASK-061 / Phase 2B2：伏笔 current projection checkpoint/rewind 设计审计

## 任务身份

- 仓库根目录：`D:\gptuser\projects\ai-novel-reader-app2`
- HEAD：`8ce7744`；继续当前 dirty WIP，禁止回退、清理或覆盖。
- 已完成：Phase 1 原子编辑失效、Phase 2A 影响计划、Phase 2B1 schema v11 派生历史槽。
- 执行模型：DeepSeek V4 Flash，只读架构复核。

## 运行预算

- 推理等级：`max`
- 最长运行时间：12 分钟
- 无累计 Token 上限
- 只读文件最多 10 个，命令最多 7 个
- 不生成大补丁；只返回数据契约、风险和最小实施切片
- 提前停止：需要删除历史、放宽 tracking 顺序保护、调用 Provider、修改章节正文或无法对旧数据失败关闭

## 目标

为下一小阶段选择最小可靠的伏笔 current projection 历史方案，使未来编辑早期章节时能够证明“编辑点之前的伏笔状态”、安全执行特殊 rewind，并按新章节顺序 replay；不得把终态 RESOLVED/ABANDONED 的普通前向转换规则放宽成可随意重开。

本次只做设计审计，不写工作树、不实现实际 rewind、不建 Job、不联网。

## 当前事实

1. `foreshadow_item` 是唯一且可变的 current projection，保存状态、来源/种下/解决版本、可见实体、重要度与更新时间。
2. `foreshadow_transition` 是追加台账，schema v11 已允许同一 item/source version 多代 STALE，但 transition 不保存更新后的 `visibleEntityIdsJson`、`importance`、target 范围或完整 after-state。
3. `compareAndTransitionForeshadow` 会 CAS 更新 current item；两个生产提交入口都会先改 item，再插 transition，且都在 Room 事务内。
4. 编辑旧章节会把与该版本相关的 item `memory_status` 标成 STALE，并 stale 该来源版本的 transition；后续章节 transition 当前仍可能保持 VALID，但其前驱链已经不可信。
5. tracking source 会冻结最多 256 个 active foreshadow 的完整 hash，并禁止在存在后续 committed chapter 时单独重建中间章。
6. Phase 2B1 只解决 transition 历史槽，明确没有解决 current projection rewind。

## 必读资料

1. `AGENTS.md`
2. `docs/24-AI-DEVELOPMENT-PROTOCOL.md`
3. `docs/ai/CURRENT-CONTEXT.md`
4. `docs/09-DATA-MODEL.md` 第 20、22 节
5. `docs/10-STATE-MACHINES.md` 第 22 节
6. `core/database/src/main/kotlin/app/zhijuan/core/database/memory/MemoryEntities.kt` 的 foreshadow 两实体
7. `core/database/src/main/kotlin/app/zhijuan/core/database/memory/MemoryDao.kt` 的 foreshadow 查询/更新/失效
8. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterTrackingPayloadHasher.kt`
9. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterTrackingProjectionCommitRepository.kt` 的提交事务
10. `feature/generation/src/main/kotlin/app/zhijuan/feature/generation/ChapterTrackingProjectionPersistenceMapper.kt`

不得读取日志、密钥、其他项目、历史报告或 C 盘。

## 必须比较的方案

A. 每个 PLANT/DEVELOP/RESOLVE/ABANDON 结果额外保存一条不可变的完整 after-state revision，并以 transition/stage/source version 绑定。

B. 每个成功 tracking chapter 保存一本书当时全部 current foreshadow 的不可变 checkpoint（规范 JSON/hash），rewind 时回读编辑点之前最近 checkpoint。

可以提出第三种方案，但必须解释为什么比 A/B 更小、更可迁移和更适合长篇。

## 必须回答

1. 哪种方案能完整恢复 status、source/planted/resolved version、visible entities、importance、target 和 source，而不依赖当前可变行？
2. 怎样把普通“终态不可重新打开”与受审计 rewind 区分；rewind 的 CAS 输入和持久证据必须包含什么？
3. 编辑第 3 章且已有 10 章时，哪些 transition/revision 必须 stale，哪些可作为第 3 章之前的可信基线？
4. v11 旧数据缺少 after-state 历史时，v12 迁移能安全回填什么，必须对什么场景失败关闭或从更早点重建？
5. 怎样同时接入 `ChapterTrackingProjectionCommitRepository` 与 final candidate 原子提交，避免两套 writer 漂移？
6. 建议的 entity/index/foreign key/guard/DAO 最小字段清单是什么？需说明每个字段的作用。
7. 本阶段最小可编译切片应做到哪里，哪些内容必须留给下一切片？

## 不可破坏的约束

- 不删除/覆盖 item、transition 或派生历史。
- 不以 transitions 当前状态列表猜测缺失的 visible entities/importance。
- 不允许普通生成把 RESOLVED/ABANDONED 改回活动状态。
- rewind 必须绑定书、编辑点、完整 current-version 区间、可信基线 hash 和单调时间，并在单事务 CAS。
- 旧数据无法证明时失败关闭，不伪造 checkpoint。
- 不放松 Provider-open、费用、Stage、租约或 tracking 顺序保护。
- 默认字符串和错误不展开伏笔描述、可见实体 JSON、模型输出或标识符。
- 真实 API 0，物理设备写入 0。

## 输出格式

1. `结论`
2. `方案比较`
3. `推荐数据契约`
4. `迁移与旧数据边界`
5. `rewind/replay 算法不变量`
6. `最小实施切片`
7. `P0/P1 风险`
8. `需要 Sol 决策`

禁止写工作树，禁止宣布 Phase 2B2/TASK-061 完成。
