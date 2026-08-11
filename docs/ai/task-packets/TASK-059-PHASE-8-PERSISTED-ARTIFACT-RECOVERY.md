# TASK-059 第八阶段：持久候选 artifact 恢复

## 任务身份

- 任务 ID：`TASK-059 / Phase 8 / persisted candidate artifact recovery`
- 仓库根目录：`D:\gptuser\projects\ai-novel-reader-app2`
- 分支/基线：`main` / `8ce774429da1c3f7139a221bc241c34d81a2efdd`
- 当前 WIP：Phase 1~7 已建立候选来源门禁、派生封存、有限分流和最终提交草稿映射器；必须延续，不得另建平行最终提交链。
- 执行模型：DeepSeek V4 Flash，纯文本，只读补丁提案模式，`max` 推理。

## 运行预算

- 最长运行：25 分钟。理由：需要核对三种既有严格结构化解析器、protected artifact lease 和四角色证据，但实现范围仍限定为两个新文件。
- 总 Token 上限：不设置（用户持续指令）；完成即停。
- 预计读取：强制规则 4 份、业务源文件 7 份、测试夹具 3 份，共 14 份；不得递归扫描。
- 执行命令：只读查看，不运行 Gradle、不写文件。
- 提前停止：需要第三个业务文件、需要改变既有公开契约、无法在只读提案中给出完整补丁、发现任务边界不足或任何隔离异常。

## 目标

新增一个小型生产恢复协调器：按 BODY/MEMORY/TRACKING/CONSISTENCY 四类持久证据，从受保护 artifact store 的 lease 逐个读取明文，重新验证 artifact 身份、revision、原始 hash 和规范化 hash，再用现有严格解析器恢复正文与三类结构化模型。新增纯 JVM 测试证明合法恢复、篡改/错 revision/结构无效失败及诊断脱敏。

本阶段只恢复经过严格解析的模型对象，不读取数据库中的 Stage/Attempt、不生成派生数据库行、不调用最终草稿映射器、不执行 COMMIT。

## 当前现场与已有 WIP

- `ChapterFinalCandidateCommitDraftMapperV1` 已只接受解析好的 `ChapterMemoryDerivedDraft`、`ChapterTrackingProjectionDerivedDraft`、`ChapterConsistencyDerivedDraftV1` 和四角色 evidence。
- `ChapterFinalCandidateCommitRepositoryV1` 会在最终事务前再次读取四类 artifact 并复算 hash；新恢复器是进程重启后的上层重建入口，不能削弱最终仓库的复核。
- `AndroidProtectedArtifactStore.readBytes(...).use { lease -> lease.withBytes { ... } }` 已提供解密、大小上限和关闭清零语义。
- 三个现有 Parser 都通过 `StructuredOutputValidator` 严格验证 schema；必须复用，禁止直接用宽松 JSON 解码。

## 必读资料

1. `AGENTS.md`
2. `docs/24-AI-DEVELOPMENT-PROTOCOL.md`
3. `docs/ai/CURRENT-CONTEXT.md`
4. `docs/ai/task-packets/TASK-059-PHASE-8-PERSISTED-ARTIFACT-RECOVERY.md`
5. `core/security/src/main/kotlin/app/zhijuan/core/security/ProtectedArtifact.kt`
6. `core/security/src/main/kotlin/app/zhijuan/core/security/AndroidProtectedArtifactStore.kt`
7. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/ChapterFinalCandidateCommitRepository.kt`（只看 evidence 定义和 `verifyArtifactFiles`）
8. `feature/generation/src/main/kotlin/app/zhijuan/feature/generation/ChapterMemoryStructuredOutput.kt`
9. `feature/generation/src/main/kotlin/app/zhijuan/feature/generation/ChapterTrackingStructuredOutput.kt`
10. `feature/generation/src/main/kotlin/app/zhijuan/feature/generation/ChapterConsistencyStructuredOutput.kt`
11. `feature/generation/src/main/kotlin/app/zhijuan/feature/generation/ChapterFinalCandidateCommitDraftMapper.kt`
12. `feature/generation/src/test/kotlin/app/zhijuan/feature/generation/ChapterMemoryStructuredOutputTest.kt`
13. `feature/generation/src/test/kotlin/app/zhijuan/feature/generation/ChapterTrackingStructuredOutputTest.kt`
14. `feature/generation/src/test/kotlin/app/zhijuan/feature/generation/ChapterConsistencyStructuredOutputTest.kt`

不得读取历史 DeepSeek 日志、会话、API Key、原项目或其他文档。需要扩展文件范围时停止并说明。

## 范围

最终补丁只允许新增：

1. `feature/generation/src/main/kotlin/app/zhijuan/feature/generation/ChapterFinalCandidateArtifactRecoveryCoordinator.kt`
2. `feature/generation/src/test/kotlin/app/zhijuan/feature/generation/ChapterFinalCandidateArtifactRecoveryCoordinatorTest.kt`

不得修改任何现有文件。不得创建探针、临时文件、构建产物或第三个文件。

## 不可破坏的约束

- 项目隔离：不得访问或修改 `D:\gptuser\projects\ai-novel-reader` 或其他目录。
- 只读提案：不要调用 `apply_patch`、编辑工具、Python、PowerShell/.NET 写文件或任何 shell 写入；最终回交中给出完整、最小、可由 Sol 应用的 `apply_patch` 补丁文本。
- 安全：错误和 `toString()` 不得包含正文、结构化 JSON、artifact 明文、ID 之外的个人内容或解析报告详情。
- lease：生产 reader 必须使用 `readBytes(...).use` 和 `withBytes`，不得把解密 ByteArray 保存在结果对象或字段中；结果只保留 String 正文和解析模型。
- 完整性：四个 role 必须各且仅各一个；descriptor 的 `artifactRefId`、`revision` 和 `STREAM_DRAFT` 类型必须与 evidence 一致；每个原始 SHA-256 必须匹配 `rawOutputHash`。
- 规范化 hash：BODY 的规范 hash等于正文原始 hash；三类结构化输出必须通过既有严格 Parser，解析模型 `contentHash` 必须等于 evidence 的 `canonicalOutputHash`。
- 正文：严格 UTF-8 解码、非空，最大 4 MiB；结构化 artifact 各最大 512 KiB。
- 联网与费用：不得调用 Provider、不得运行真实 API、不得写设备。
- 最终提交：不得复制或调用数据库最终提交仓库，不得宣布 TASK-059 完成。

## 实施要求

1. 建议定义一个可替换的内部/plain interface reader，以及使用 `AndroidProtectedArtifactStore` 的生产实现，便于 JVM fake reader 测试；不要把 Android Context 引入接口。
2. 恢复结果应包含 `candidateContent`、`ChapterMemoryV1`、`ChapterStoryTrackingV1`、`ChapterConsistencyReportV1`，并提供脱敏 `toString()`。
3. 协调器按固定 role 顺序读取，不能依赖调用方列表顺序；所有错误使用固定英文说明，不拼接明文或完整解析报告。
4. Parser 返回 `Invalid` 时抛出通用 `IllegalArgumentException`，不要把 report 或内容拼入消息。
5. 输入 ByteArray 所有权属于 lease/reader；协调器不得主动清零 reader 提供的底层数组，避免破坏 store lease 约定，由 `use` 关闭负责清零。
6. JVM fake reader 记录读取顺序、上限与关闭范围即可，不复制 Android 加密实现。

## 测试要求

最少 5 项纯 JVM 测试：

1. 四角色 evidence 顺序打乱时仍按 BODY→MEMORY→TRACKING→CONSISTENCY 恢复，记录上限为 4 MiB/512 KiB，并得到正确四类对象；
2. 重复或缺少 role 在读取前失败；
3. descriptor artifactRefId 或 revision 不匹配时失败；
4. 原始 payload 被替换导致 `rawOutputHash` 不匹配时失败；
5. 结构 JSON schema 无效或 canonical hash 不匹配时失败，异常/结果 `toString()` 不包含正文和夹具中的敏感 canary。

测试夹具可以从三份现有 parser 测试中最小复制合法 JSON 构造，但不要修改它们，不要引入新的测试依赖。

## 验收标准

- [ ] 只有两个新文件的完整补丁提案。
- [ ] 生产代码确实经过 protected store lease，而不是要求调用方传明文长驻对象。
- [ ] 四种身份/大小/hash/schema 门禁齐全。
- [ ] 不泄漏正文或 JSON 到默认字符串和错误。
- [ ] 测试覆盖合法恢复与四类失败路径。
- [ ] 没有数据库、Provider、设备、状态文档或现有文件改动。

## Sol 后续验证命令

DeepSeek 不运行；Sol 应用补丁后运行：

```powershell
.\gradlew.bat :feature:generation:testDebugUnitTest --tests "app.zhijuan.feature.generation.ChapterFinalCandidateArtifactRecoveryCoordinatorTest" --offline --rerun-tasks
```

成功后再运行：

```powershell
scripts/verify-build.ps1 -Offline
```

## 回交格式

严格按以下标题返回：

1. `完成内容`
2. `补丁提案`（一个完整 `*** Begin Patch` / `*** End Patch` 块）
3. `验证`（必须写明未运行）
4. `未完成/风险`
5. `需要 Sol 处理`
6. `假设`

不要宣布完整 TASK-059 完成，不要写“已修改文件”；只说明这是只读补丁提案。
