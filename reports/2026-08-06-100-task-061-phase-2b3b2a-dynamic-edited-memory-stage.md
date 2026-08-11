# 工作汇报 100：TASK-061 Phase 2B3B2A 动态 edited-memory Stage

> 日期：2026-08-06  
> 项目：织卷 Android App  
> 唯一工作目录：`D:\gptuser\projects\ai-novel-reader-app2`  
> 阶段结论：Phase 2B3B2A 完成；TASK-061、TEST-033 与总 runner 未完成

## 1. 本阶段交付

本阶段把 schema v14 的不可变编辑重建账本接出了第一个可恢复远程工作，但只覆盖编辑章 memory：

1. 从 execution ledger 的首个 `PENDING EDITED_MEMORY` 步骤创建一个确定性单步 Job/Stage。
2. execution/step、完整 current 影响范围、初始 plan、目标 summary 和时间下界在同一 Room 外层事务中重验。
3. Job/Stage ID 由 stable fence、step ordinal/type、chapter 和 source version/hash 确定性派生；不使用随机数、当前时间或动态 planHash 作为身份。
4. 同一 immutable setup 重试或并发创建只保留一份 Job/Stage；部分身份、不同预算/意图或来源冲突失败关闭。
5. Stage 输入新增严格 `schemaVersion=2` 的 `chapterEditRebuild` binding，并进入 input hash/idempotency key。普通无 binding 的 memory Stage 继续保持原 v1 JSON 与哈希行为。
6. Provider-open 前和 memory commit 写业务表前分别回读 v14 execution/step、stable fence、完整 current 范围和确定性 Job/Stage 身份。
7. 创建阶段不产生 Attempt、Usage 或 Provider 请求，不伪造 memory 输出。

## 2. 为什么不升 schema v15

v14 已提供不可变 execution/step 权威账本；`generation_stage.input_sources_json` 本身又是 Stage 的冻结输入，并由 `input_version_hash`、唯一 idempotency key 和 factory 严格解析保护。因此本阶段把 rebuild authorization 内嵌到 Stage 输入即可形成稳定映射，不需要再建一个容易与 Stage 状态重复的旁路表。

这项决定只适用于“远程 Stage 与 immutable step 的绑定”。后续跨章统一进度若确实需要新的追加式证据，必须在 tracking/aggregate 实现时按真实恢复需求重新审计，不能为了省迁移而藏进内存状态。

## 3. 主要代码

- 新增 `ChapterEditRebuildStageRepository.kt`：binding、确定性身份、原子创建、exact replay、Provider-open/commit 授权复核。
- 更新 `ChapterMemoryExtractionJobFactory.kt`：普通 v1 与 rebuild v2 双严格解析；binding 参与 input hash。
- 更新 `GenerationRequestAuditRepository.kt`：Provider-open claim 前加入 rebuild authorization。
- 更新 `ChapterMemoryExtractionCommitRepository.kt`：正式 memory 写入前再次复核 rebuild authorization。
- 更新 factory JVM 测试与 `ChapterEditRebuildPlanDatabaseTest`。

## 4. 已验证行为

### 4.1 JVM 与编译

- `:core:database:testDebugUnitTest --tests "*ChapterMemoryExtractionJobFactoryTest"`：通过。
- `:core:database:compileDebugAndroidTestKotlin`：通过。
- `:core:database:testDebugUnitTest` 全量：66/66，0 失败、0 错误、0 跳过。
- 普通 v1 memory 输入不出现 `chapterEditRebuild`，schema 仍为 1。
- rebuild v2 binding 可严格回读，字段篡改在 hash/解析门禁失败。

### 4.2 双 API 数据库定向测试

- API 30 `emulator-5556`：`ChapterEditRebuildPlanDatabaseTest` 12/12。
- API 35 `emulator-5558`：`ChapterEditRebuildPlanDatabaseTest` 12/12。
- API 30 `emulator-5556`：数据库模块全量 175/175。
- API 35 `emulator-5558`：数据库模块全量 175/175。
- 覆盖确定性创建、顺序 replay、双协程并发、不同预算冲突、current 范围变化、satisfied memory 不偷跑 tracking、Provider-open/commit 授权回读和字符串脱敏。

### 4.3 安全与格式

- `scripts/security-scan.ps1 -SkipArtifacts`：`SECURITY_SCAN_OK`。
- `git diff --check`：无格式错误；仅有工作树既存 CRLF 提示。
- App 内真实 Provider 调用：0。
- 物理设备写入：0；测试只使用项目专用 API 30/API 35 模拟器。

## 5. DeepSeek 本轮结果

任务包：`docs/ai/task-packets/TASK-061-PHASE-2B3B2A-DYNAMIC-EDITED-MEMORY-STAGE.md`。

- 运行 ID：`20260806-054907-37278d3b`。
- 模型与推理：DeepSeek V4 Flash / `max`。
- 约 5 分 58 秒触发 1,000,000 累计 Token 守卫；摘要记录实际 1,008,159 Token。
- 没有最终回交、没有新文件、没有可归因的新代码差异。
- Sol 检查工作树确认未被写坏，随后接管设计、实现、修复、测试与文档。

结论：本轮 DeepSeek 的代码产出质量不合格，不能把高 Token 消耗算作开发进度。后续若继续使用，应进一步缩小读取清单和交付面，优先让它处理单文件测试或局部审计，不再把“实现 + 双门禁 + 多测试”放进同一个子任务。

## 6. 明确未完成

- 尚未由 TASK-061 专用执行器实际驱动 memory Provider 与真实 commit；本阶段只建立可恢复 Job/Stage 和双重授权。
- 尚未在 memory 成功后动态冻结并创建第一章 tracking Stage。
- 尚未提供 tracking 的 edit-rebuild 专用顺序许可和 commit 双重门禁。
- 尚未把 tracking 成功与同章 aggregate writer、下一章解锁连成事务/恢复闭环。
- TEST-033、context/consistency 最终接线和全 App phase dispatcher 未完成。
- 本阶段未运行 Release/R8 或统一 797-task 离线门禁；不能沿用旧阶段门禁数字冒充本阶段证据。

## 7. 下一阶段

正式下一步是 TASK-061 Phase 2B3B2B：

1. 只在 edited-memory 的真实成功结果存在后读取权威 summary/events/facts。
2. 结合 rewind 后当前 foreshadow 与 known entities，冻结第一章 tracking 的真实 source hash。
3. 动态创建确定性 tracking 单步 Job/Stage，不删除普通 tracking 顺序保护。
4. Provider-open 与 commit 使用 stable fence + step ordinal + 已完成前驱的专用重建许可。
5. tracking 成功后调用既有 aggregate writer，并设计崩溃后可恢复的下一章解锁证据。

后续仍只使用 Fake Provider/离线测试，不调用织卷 App 内真实生成 API。
