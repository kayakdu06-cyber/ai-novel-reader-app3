# TASK-059 第十阶段 B6：未绑定 Stage 来源兼容性修复

## 任务身份

- 任务 ID：`TASK-059 / Phase 10B6 / unbound stage source compatibility`
- 仓库根目录：`D:\gptuser\projects\ai-novel-reader-app2`
- 基准分支与 HEAD：`main` / `8ce774429da1c3f7139a221bc241c34d81a2efdd`；保留全部未提交 WIP。
- 执行模型：DeepSeek V4 Flash，纯文本，单文件代码修改。

## 运行预算

- 推理等级：`max`。
- 最长运行时间：10 分钟。
- 累计 Token 上限：不设置（用户明确要求；完成本小修复即停）。
- 预计读取：4 个强制入口、本任务包、1 个生产文件、2 个失败夹具片段。
- 预计命令：一次 `git status --short`、定点读取/搜索、最多一次 `:core:database:compileDebugKotlin --offline`。
- 提前停止：需要修改第二个文件、`apply_patch` 不可用、需要扩大兼容行为、发生权限阻塞或无法证明不会削弱候选门禁。

## 目标

修复 `ChapterCandidateStageBindingV1.parseIfBound` 对旧 Stage 合法 `inputSourcesJson = "[]"` 的误判。合法 JSON 但不是 object 的来源必须返回 null，继续交给后续旧阶段门禁；畸形 JSON和带当前候选 policy version 但结构非法的 object 仍必须失败关闭。

## 当前现场与已有 WIP

- API 35 全量失败：Database 23 项、Generation 13 项都在 Provider-open 被 `Candidate Stage binding is invalid or stale` 拦截。
- 共同夹具是 DRAFT_CHAPTER，`inputSourcesJson = "[]"`；这不是候选 Stage binding。
- 当前 `parseIfBound` 直接把解析结果 `as JsonObject`，合法 JsonArray 会触发类型转换异常，再被上层转换为 stale。
- candidate 专项 25/25 已证明真正绑定的正负例；不能为了兼容旧 Stage 放宽当前 policy object 的严格解析。

## 必读资料

1. `AGENTS.md`
2. `docs/24-AI-DEVELOPMENT-PROTOCOL.md`
3. `docs/ai/CURRENT-CONTEXT.md`
4. 本任务包
5. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterCandidateArtifactSealRepository.kt` 中 `ChapterCandidateStageBindingV1.parseIfBound/parseAndVerify`
6. `core/database/src/androidTest/kotlin/app/zhijuan/core/database/GenerationDatabaseTest.kt` 中 `stage(...)` 夹具
7. `feature/generation/src/androidTest/kotlin/app/zhijuan/feature/generation/AuditedStreamingProviderExecutorTest.kt` 中 `seedGenerationRows()` 夹具

除上述清单和直接引用类型外，不得递归读取大测试、报告、日志、密钥、原项目或无关模块。

## 范围

允许修改且只允许修改：

- `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterCandidateArtifactSealRepository.kt`

明确不在范围：

- 测试、文档、其他生产文件、Room schema/DAO、Provider、UI、前台服务、原项目。

## 不可破坏的约束

- 只能使用 `apply_patch` 修改文件；若工具失败，停止并回交，不得使用 `WriteAllText`、重定向或其他写文件方式。
- JSON 语法错误继续抛出 `IllegalArgumentException`；不得把损坏 JSON 当作未绑定。
- JsonObject 的 `sourcePolicyVersion` 精确等于当前候选版本时，继续调用 `parseAndVerify` 严格检查 exact keys/hash/phase/target。
- 不调用真实 API，不联网，不写密钥，不触碰其他项目。
- 不宣布 TASK-059 完成，不更新状态文档。

## 实施要求

1. 先把 `stage.inputSourcesJson` 严格解析为通用 JsonElement；解析失败抛出既有通用非法 JSON 错误。
2. 若解析成功但不是 JsonObject，返回 null。
3. 若是 JsonObject 但 policy version 不等于当前候选 policy，保持返回 null。
4. 若 policy version 匹配，继续复用 `parseAndVerify(stage)`，不要复制或放宽其规则。
5. 不改错误文本，不增加日志，不改任何其他函数。

## 验收标准

- [ ] `[]` 不再被候选门禁误判。
- [ ] 畸形 JSON 仍失败关闭。
- [ ] 当前候选 policy object 仍执行全部严格校验。
- [ ] 只修改一个授权文件且只包含最小差异。

## 验证命令

```powershell
.\gradlew.bat :core:database:compileDebugKotlin --offline --no-daemon --console=plain
```

不运行 JVM/Android 测试和统一门禁，由 Sol 补测试并验收。

## 回交格式

请严格按以下标题返回：

1. `完成内容`
2. `修改文件`
3. `验证`
4. `未完成/风险`
5. `需要 Sol 处理`
6. `假设`

不要宣布完整 TASK-059 完成，不要更新状态文档。
