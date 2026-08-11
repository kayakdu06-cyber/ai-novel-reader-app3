# TASK-064 Phase 2D2：chapter-context assembly 严格 route identity

## 任务身份

- 任务 ID：`TASK-064 / Phase 2D2 chapter-context assembly route identity`
- 仓库根目录：`D:\gptuser\projects\ai-novel-reader-app2`
- 基准分支与 HEAD：`main @ 8ce774429da1c3f7139a221bc241c34d81a2efdd`
- 当前未提交改动：约 216 条连续 WIP；不得 reset、clean、checkout、覆盖或整理无关改动
- 执行模型：DeepSeek（纯文本）

## 运行预算

- 推理等级：`max`
- 最长运行时间：30 分钟；理由：改动只有一个本地 route，但需要在 factory、repository parser 和 resolver 之间消除重复合同并补负例
- 累计 Token 上限：无
- 预计读取文件数：任务包列出的 12 个文件，不得超过 24 个
- 预计执行命令/测试：8～20 个只读命令，最多 2 个 JVM Gradle 命令；不运行 Android 模拟器
- 提前停止条件：需要改 schema/migration/DAO/Provider/registry、需要访问其他项目或密钥、允许文件外出现必要生产改动、测试连续两次同因失败

## 目标

为已经存在且纯本地的 `ASSEMBLE_CONTEXT` Stage 增加唯一、严格、可失败关闭的 route identity。`ChapterContextAssemblyJobFactory` 必须写入独立 `sourcePolicyVersion`；一个权威 parser 必须同时被 repository 和 `GenerationRunnerStageRouteResolver` 复用；route enum 新增 `CHAPTER_CONTEXT_ASSEMBLY_V1`。

本阶段只做 route identity，不把该 route 加入 registry，不实现 executor，不调用 Provider。

## 当前现场与已有 WIP

- `ChapterContextAssemblyJobFactory` 已创建两个 Stage：本地 `ASSEMBLE_CONTEXT` 和远程 `BUILD_CHAPTER_PLAN`。
- `ChapterContextAssemblyRepository.assemble(stageId, leaseToken, at)` 已能本地组装 context snapshot、原子完成 Stage、激活 chapter-plan successor，并处理 BLOCKED/replay；Android 测试已存在。
- factory 的 context root 当前没有 `sourcePolicyVersion`；repository 使用私有 `parseFrozenInput`；resolver 因此无法识别该 Stage。
- Phase 2C3 registry 当前只注册 `FINAL_CHAPTER_COMMIT_V3`，必须保持不变。
- Phase 2D1 已确认现 `CANDIDATE_CHAPTER_DRAFT_V1` 合同不可执行；本任务不得修改或“修活”它。
- 最近基线：database JVM 81/81、generation JVM 131/131，双 API database 214/214、generation 41/41，统一 801 tasks 通过。

## 必读资料

1. `AGENTS.md`
2. `docs/24-AI-DEVELOPMENT-PROTOCOL.md`
3. `docs/ai/CURRENT-CONTEXT.md`（重点 22～26 节）
4. `docs/06-AI-GENERATION-SYSTEM.md`（34～38 节）
5. `docs/08-TECHNICAL-ARCHITECTURE.md`（31～35 节）
6. `docs/10-STATE-MACHINES.md`（37～41 节）
7. `docs/18-DECISION-LOG.md`（DEC-061～065）
8. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterContextAssemblyJobFactory.kt`
9. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterContextAssemblyRepository.kt`
10. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterProgressionGateRepository.kt`
11. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationRunnerStageRouteResolver.kt`
12. `core/database/src/test/kotlin/app/zhijuan/core/database/generation/ChapterContextAssemblyJobFactoryTest.kt`
13. `core/database/src/test/kotlin/app/zhijuan/core/database/generation/GenerationRunnerStageRouteResolverTest.kt`
14. `core/database/src/androidTest/kotlin/app/zhijuan/core/database/ChapterContextAssemblyDatabaseTest.kt`（只读，了解现有执行合同）

除代码直接引用外，不读取 reports、备份、缓存、其他项目或无关模块。

## 范围

允许修改且仅允许修改：

1. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterContextAssemblyJobFactory.kt`
2. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterContextAssemblyRepository.kt`
3. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationRunnerStageRouteResolver.kt`
4. `core/database/src/test/kotlin/app/zhijuan/core/database/generation/ChapterContextAssemblyJobFactoryTest.kt`
5. `core/database/src/test/kotlin/app/zhijuan/core/database/generation/GenerationRunnerStageRouteResolverTest.kt`

明确不在范围：

- 不改 registry、feature、DAO、entity、schema、migration、Gradle 或 Android 测试；
- 不改 candidate/revision/final/memory/tracking route 行为；
- 不创建 executor、不运行 Provider、不改变 context assemble 的事务和状态机；
- 不更新 docs/status/report；由 Sol 验收后处理。

## 不可破坏的约束

- 项目隔离：只修改 app2 上述五个文件，保留 216 条 WIP。
- route 选择：仍只读取 `sourcePolicyVersion` 选择唯一权威 parser；未知/损坏不得 fallback。
- 严格合同：parser 必须验证 Stage phase/target/maxAttempts、root keys、context keys、固定版本/输出 schema/targetPhase、空 dependency、预算字段类型/边界、prompt hash、progression evidence 对象及 evidenceHash、`inputVersionHash == sha256(inputSourcesJson)`。
- 不复制执行期动态检查：书/章/大纲/memory/prompt binding 的当前性继续由 `ChapterContextAssemblyRepository.assemble` 和 `ChapterProgressionGateRepository` 负责。
- parser 与 repository 必须共享同一冻结输入解析结果；不要保留两套字段解析产生漂移。
- 安全：异常不输出 input JSON、userAddition、业务 ID/hash 或 payload；toString 如新增必须脱敏。
- 兼容：不改变 context assembly 的两个 Stage 数量、顺序、phase、idempotency 规则或业务事务，只因新增 policy 字段重新计算现有 input hash。

## 实施要求

1. 在 factory 文件内新增明确命名的 initial context source policy，例如 `zhijuan.chapter-context-assembly-source.v1`，并写入 context Stage 的 root；不要写入 chapter-plan Stage root。
2. 提取/新增一个 `internal` 权威 parser，返回有限 frozen source 数据，供 factory/repository/resolver 使用：
   - root 必须严格只有 `schemaVersion/sourcePolicyVersion/promptBundleVersion/outputSchemaId/dependencyStageIds/contextAssembly/chapterProgressionGate`；
   - `contextAssembly` keys 必须严格匹配 factory 当前字段；
   - progression object 至少必须是 object、含 64 位小写 hex `evidenceHash`，并验证其去掉 `evidenceHash` 后的 SHA-256 自洽；不要复制数据库动态 progression 判定；
   - `dependencyStageIds` 必须是空 array；
   - Stage 必须 `ASSEMBLE_CONTEXT + CHAPTER + maxAttempts=1`；
   - input hash 必须严格匹配。
3. `ChapterContextAssemblyRepository` 的冻结解析改为调用该权威 parser；现有动态 gate、prompt、memory、outline 和事务校验全部保留。
4. route enum 增加 `CHAPTER_CONTEXT_ASSEMBLY_V1`，resolver 以新 policy 调 parser后返回该 route。
5. 测试：
   - factory 正向解析并命中 route；
   - policy/version/schema/phase/target/maxAttempts/inputHash/extra root/extra context key/非空 dependency/损坏 evidenceHash 任一失败；
   - 旧 memory/tracking/candidate/final route 测试保持通过；
   - route enum/string 不携带 payload。
6. 不把 `CANDIDATE_CHAPTER_DRAFT_V1` 删除、重命名或注册；Phase 2D1 的 fail-closed 现状保持。

## 验收标准

- [ ] context factory 只为本地 context Stage写新 policy。
- [ ] repository 与 resolver 共用一个严格 parser。
- [ ] 新 route 唯一命中，损坏合同全部失败关闭。
- [ ] context 业务事务和下一 Stage 行为未改。
- [ ] 五个允许文件外零差异。
- [ ] 0 Provider、0 Android/物理设备操作、0 密钥读取。

## 验证命令

```powershell
.\gradlew.bat :core:database:testDebugUnitTest --no-daemon --offline
```

可选定向：

```powershell
.\gradlew.bat :core:database:testDebugUnitTest --tests "*ChapterContextAssemblyJobFactoryTest" --tests "*GenerationRunnerStageRouteResolverTest" --no-daemon --offline
```

不运行 Android 测试；由 Sol 在审查差异后执行。

## 回交格式

请严格按以下标题返回：

1. `完成内容`
2. `修改文件`
3. `验证`
4. `未完成/风险`
5. `需要 Sol 处理`
6. `假设`

不得宣布 TASK-064 或 Phase 2D 完成，不得更新正式状态。
