# 工作汇报 87：TASK-060 六类权威记忆回填

> 日期：2026-08-05  
> 项目：织卷 Android App  
> 阶段：TASK-060 Phase 2B2 完成；TASK-060 整体仍在进行中

## 1. 本阶段结果

已把 Phase 2B1 返回的 FTS 派生指针接到权威记忆读取层。系统不会把搜索索引里的词、hash 或元数据直接当作小说事实发送给模型，而是按指针重新读取正式 SQLCipher/Room 数据库中的原始记忆行，确认它仍然有效，再用唯一索引工厂重新计算完整指针并逐字段核对。

简单说：FTS 现在只负责“找到可能相关的卡片编号”，真正装入上下文前还必须回到保险柜里取原卡核验。

## 2. 权威性与可用性边界

- 最多 128 个召回指针按六类来源去重分组，空组跳过，单个 Room 事务最多执行六次批量查询，没有逐条 N+1 查询。
- Story entity 必须仍属于同书且没有归档。
- Chapter summary、entity event、timeline event 必须为 `VALID`，来源章节版本仍是该章当前版本，并严格早于目标章。
- Canon fact 必须为 `VALID`，且来源是当前 Bible，或来源章节仍为当前版本并严格早于目标章。
- Foreshadow 必须为 `VALID`，不能是 `RESOLVED/ABANDONED`；有章节来源时同样要求当前版本和章界，全局来源可保留。
- 每条权威行重新调用 `MemorySearchDocumentFactoryV1`；除 SQLite rowid 外，document ID、source、章节、故事顺序、重要度、hash、检索词和更新时间都必须与指针完全一致。
- 失效、缺失或不匹配只剔除该条，返回 `rejectedPointerCount` 并设置 `indexRebuildRequired`，不会让整章失败。
- 指针结构损坏、跨书、重复 document/source 身份或超过 128 条仍然失败关闭。
- 有效结果保持 Phase 2B1 原顺序，不在 hydration 阶段重新排序。
- 六类来源 wrapper、命中和结果均使用脱敏 `toString()`，不展开正文、JSON、人物名、source ID、document ID 或检索词。

## 3. 修改文件

- `core/database/src/main/kotlin/app/zhijuan/core/database/memory/MemorySearchBackfillRows.kt`
- `core/database/src/main/kotlin/app/zhijuan/core/database/memory/MemoryDao.kt`
- `core/database/src/main/kotlin/app/zhijuan/core/database/search/MemorySearchHydrationRepository.kt`
- `core/database/src/androidTest/kotlin/app/zhijuan/core/database/MemorySearchHydrationDatabaseTest.kt`
- `docs/15-TEST-PLAN.md`
- `docs/19-IMPLEMENTATION-BACKLOG.md`
- `docs/20-TRACEABILITY-MATRIX.md`
- `docs/22-WORK-STATUS.md`
- `docs/ai/CURRENT-CONTEXT.md`
- `docs/ai/task-packets/TASK-060-PHASE-2B2A-AUTHORITATIVE-HYDRATION.md`
- 本报告

## 4. 验证证据

```text
生产与 AndroidTest Kotlin 编译：通过
MemorySearchHydrationDatabaseTest：API 30 5/5、API 35 5/5
core/database JVM 全量：65/65
API 30 core/database 全量：131/131
API 35 core/database 全量：131/131
security-scan：通过（源码 + 5 个现存 APK）
git diff --check：通过（仅既有换行符提示）
真实 Provider 调用：0
物理设备安装/写入/设置修改：0
Git remote 操作：0
```

五项真实 Room 测试覆盖：六类来源共八个命中、输入顺序保持、权威实体可供后续上下文层使用且字符串表示脱敏；Bible head 前移、章节 current version 前移、人物归档和伏笔解决后八个旧指针全部被剔除；单条 source hash 被篡改时其他结果仍正常保留；空召回正常返回空结果；重复和跨书指针静态失败且不回显私密 canary。

## 5. Sol / DeepSeek 分工与审查

DeepSeek V4 Flash 以只读补丁提案方式参与生产代码草拟，运行 ID 为 `20260805-082829-13d92ef0`，使用 `max` 推理、20 分钟上限、无总 Token 上限，约 14 分 12 秒正常结束；没有写入仓库，也没有接触 App 内真实 Provider。

Sol 没有原样采用提案，审查时修正了两个会影响产品的缺陷：

1. DeepSeek 把“没有召回结果”误判成错误；现已改为合法空记忆结果，避免无相关历史时整章卡住。
2. DeepSeek 提议先查一次 memory head 再查六类来源，最坏会达到七次；现把当前 Bible 判断合并进 canon fact 批量查询，保证最多六次。

Sol 随后用正式编辑工具落地、补五项数据库测试，并在 Android 11/15 两套项目模拟器完成全量回归。

## 6. 未完成与下一步

TASK-060 不能标记完成。下一阶段是 Phase 2C：

1. 合并不能依赖 FTS 偶然命中的强制硬事实；
2. 加入最近章节承接路线；
3. 加入到期和未解决伏笔路线；
4. 统一来源去重、优先级和遗漏证据；
5. 再接到现有章前上下文候选与确定性预算裁剪。

本阶段没有调用织卷 App 内部真实生成 API，也没有在实体设备上安装、写入或修改设置。
