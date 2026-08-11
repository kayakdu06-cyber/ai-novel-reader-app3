# TASK-059 第十阶段 B2：final Stage 绑定一致性映射快照

## 任务身份

- 任务 ID：`TASK-059 / Phase 10B2 / final Stage mapping snapshot binding`
- 仓库根目录：`D:\gptuser\projects\ai-novel-reader-app2`
- 基准分支与 HEAD：`main` / `8ce774429da1c3f7139a221bc241c34d81a2efdd`；保留全部未提交 WIP。
- 执行模型：DeepSeek V4 Flash，纯文本，只读补丁提案。

## 运行预算

- 推理等级：`max`。
- 最长运行时间：15 分钟。
- 累计 Token 上限：不设置（用户明确允许；本任务完成即停）。
- 预计读取：强制入口、本任务包、1 个允许修改的生产文件，以及任务包给出的外部 codec 契约；不要读取 Android 大测试或其他生产文件。
- 预计命令/测试：0；只返回补丁，不运行命令、不写文件。
- 提前停止：需要修改第二个文件、需要 Room schema/新表、需要反向依赖 feature 模块、或无法给出完整单文件补丁。

## 目标

只修改 `ChapterConsistencyOutcomeRepository.kt`：把 ACCEPT 路线的 final COMMIT Stage 来源封套升级为 v3，并将上游提供的 canonical 一致性映射快照、快照 hash 与原一致性请求 source binding 一起持久绑定。REVISE/NEEDS_ACTION 路线必须拒绝携带这份最终快照。

本阶段不改 feature coordinator、不改恢复仓库、不改最终执行器、不改测试。

## 当前现场与已有 WIP

- final Stage 当前使用 `zhijuan.chapter-final-commit-stage-source.v2`，冻结候选、history、上限、expected current、CONSISTENCY 前驱和 route binding。
- `ChapterConsistencyOutcomeDraftV1.sourceBindingHash` 已是当前 `BoundChapterConsistencyCheckRequest.sourceBindingHash`，同时也是 CONSISTENCY artifact seal 的 source binding。
- feature 模块已新增严格 codec：
  - schema id：`zhijuan.chapter-final-consistency-mapping.v1`；
  - `capture(...)` 返回 canonical JSON；
  - `contentHash(value)` 先严格解析、canonical 重编码，再 SHA-256；对 capture 的结果等于原 JSON UTF-8 SHA-256；
  - 根 exact keys：`schemaVersion,schemaId,consistencyRequestSourceBindingHash,minimumBodyCodePoints,totalRevisionAttemptsUsed,revisionStageMaximumAttempts,localReport,expectation,sceneContract`；
  - 快照不含正文、名称、evidence payload、提示词、API 或模型输出正文。
- core:database 不得依赖 feature:generation，因此本文件只做外层/根级严格验证；完整嵌套解析由之后的 feature 执行器调用现有 codec。

## 必读资料

1. `AGENTS.md`
2. `docs/24-AI-DEVELOPMENT-PROTOCOL.md`
3. `docs/ai/CURRENT-CONTEXT.md`
4. 本任务包
5. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterConsistencyOutcomeRepository.kt` 全文

禁止读取日志、会话、密钥、原项目、Android 大测试、恢复仓库或 feature 源码；下面契约已给足。

## 范围

允许修改：

- `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterConsistencyOutcomeRepository.kt`

明确不在范围：

- 其他源文件、测试、文档、Room schema、Provider、最终执行器。

## 不可破坏的约束

- 不访问或修改其他项目副本；不覆盖现有 WIP。
- 不新增数据库表/列；快照作为 final Stage `inputSourcesJson` 的嵌套 JSON object 保存，不另存第二份派生报告。
- final Stage 整体 UTF-8 不得超过 65,536 bytes；快照本身限制为 49,152 UTF-8 bytes，给外层字段留出固定余量。
- 不把快照作为转义 JSON 字符串双重编码；在外层保存为 `JsonObject`，避免放大体积。
- `ChapterFinalCommitStageBindingV1.parseAndVerify` 只接受 v3；旧 v2、缺字段、额外字段、错误类型或 stale input hash 失败关闭。
- 所有错误和 `toString` 不回显快照、hash 或 ID。
- 不调用真实 API、网络或设备。

## 实施要求

1. 为 `ChapterConsistencyOutcomeDraftV1` 新增两个默认空字段：

```kotlin
val consistencyMappingSnapshotJson: String? = null
val consistencyMappingSnapshotContentHash: String? = null
```

2. 为 `ChapterFinalCommitStageSourceV1` 新增三个非空字段：

```kotlin
val consistencyRequestSourceBindingHash: String
val consistencyMappingSnapshotJson: String
val consistencyMappingSnapshotContentHash: String
```

3. v3 final root 在 v2 exact keys 基础上新增：

```text
consistencyRequestSourceBindingHash,
consistencyMappingSnapshotContentHash,
consistencyMappingSnapshot
```

其中 `consistencyMappingSnapshot` 必须是真正的 `JsonObject`。解析后 `.toString()` 恢复 canonical 输入。

4. `requireValid(source)` 除现有规则外必须：

- 两个新增 hash 都是小写 SHA-256；
- snapshot UTF-8 大小为 2..49,152；
- snapshot 严格解析为 JsonObject，根 keys 与上面 codec 根 exact keys 完全一致；
- snapshot `schemaVersion` 为 integer 1，`schemaId` 为固定值；
- snapshot 根 `consistencyRequestSourceBindingHash` 等于 source 的同名字段；
- `sha256(snapshotJson)` 等于 `consistencyMappingSnapshotContentHash`；
- 不读取/复制 nested localReport/expectation/sceneContract 的值。

5. `stageSetup` 生成外层 JSON 后检查整体 UTF-8 `2..65,536`；嵌套 snapshot 用已解析 `JsonObject` 放入 linked map。input hash/idempotency 仍覆盖完整 final Stage JSON。

6. ACCEPT：两个 draft snapshot 字段必须非空，content hash 合法且与 canonical JSON 一致；创建 source 时 `consistencyRequestSourceBindingHash = draft.sourceBindingHash`。REVISE 和 NEEDS_ACTION：两个字段都必须为 null。

7. 通用 `validate` 要求两个 draft snapshot 字段“同时有或同时空”；若有，先做基本 hash/UTF-8/JSON object 检查，完整 v1 根验证由 ACCEPT 创建 source 时复用 `requireValid` 完成。

8. SOURCE policy 必须升级为 `zhijuan.chapter-final-commit-stage-source.v3`，不可兼容解析 v2。

## 验收标准

- [ ] 只返回该文件的完整 `apply_patch` 提案，不调用 apply_patch。
- [ ] ACCEPT final Stage 中 snapshot 是嵌套 object，hash/source binding 三者一致。
- [ ] REVISE/NEEDS_ACTION 不能夹带 snapshot。
- [ ] v3 exact keys、类型、大小、hash 和 input hash 均失败关闭。
- [ ] 无 feature 反向依赖、无 schema/网络/设备改动、错误脱敏。

## 验证命令

DeepSeek 本轮不运行。Sol 后续会编译、补 coordinator/恢复/数据库测试，并运行统一离线门禁。

## 回交格式

按以下标题返回：

1. `完成内容`
2. `补丁提案`
3. `验证（未运行）`
4. `未完成/风险`
5. `需要 Sol 处理`
6. `假设`

不要宣布 TASK-059 完成，不要更新状态文档。
