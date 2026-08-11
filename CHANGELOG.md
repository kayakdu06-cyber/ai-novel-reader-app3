# 变更日志 (CHANGELOG)

本文件记录精简重构的所有改动，便于追溯和还原。
每条记录包含：文件路径、操作类型（删除/修改）、原因。

---

## Step 5: 简化安全层

### 分析结果
安全层 11 个文件全部有引用（包括同包引用），无法安全删除任何文件：
- AndroidKeystoreAesGcm.kt — 被 AndroidSecretStore, AndroidProtectedArtifactStore 引用
- EncryptedEnvelope.kt — 被 AndroidKeystoreAesGcm, SecretRecord, AndroidProtectedArtifactStore 引用
- EncryptedEnvelopeCodec.kt — 被 SecretRecordCodec, DatabasePassphraseStore 引用
- ProtectedArtifactFileCodec.kt — 被 AndroidProtectedArtifactStore, StreamingDraftBuffer 引用
- SecretRecord.kt — 被 AndroidSecretStore 引用
- SecretRecordCodec.kt — 被 AndroidSecretStore 引用
- StreamingDraftBuffer.kt — 被 engine 模块引用
- 其他文件同样被引用

### 操作
无文件删除。安全层代码紧密耦合，简化需要重写引用方代码，风险较高，保留原样。

---

## Step 6: 简化生成流水线

### 删除文件 (7 个)

1. **engine/src/main/kotlin/app/zhijuan/feature/generation/ArcWindowPlanningPersistenceMapper.kt**
   - 原因：定义的类型 ArcWindowPlanningPersistenceMapper 无任何外部引用
   - 可还原：从原始项目 `feature/generation/src/main/kotlin/` 目录恢复

2. **engine/src/main/kotlin/app/zhijuan/feature/generation/FirstChapterFastLanePersistenceMapper.kt**
   - 原因：定义的类型 FirstChapterFastLanePersistenceMapper 无任何外部引用
   - 可还原：从原始项目 `feature/generation/src/main/kotlin/` 目录恢复

3. **engine/src/main/kotlin/app/zhijuan/feature/generation/GenerationRunnerExecutorRegistry.kt**
   - 原因：定义的类型 GenerationRunnerExecutorRegistry 无任何外部引用
   - 可还原：从原始项目 `feature/generation/src/main/kotlin/` 目录恢复

4. **engine/src/main/kotlin/app/zhijuan/feature/generation/GenerationRunnerHeartbeatEnvelope.kt**
   - 原因：定义的类型 GenerationRunnerHeartbeatEnvelope 无任何外部引用
   - 可还原：从原始项目 `feature/generation/src/main/kotlin/` 目录恢复

5. **engine/src/main/kotlin/app/zhijuan/feature/generation/InitialPlanningPersistenceMapper.kt**
   - 原因：定义的类型 InitialPlanningPersistenceMapper 无任何外部引用
   - 可还原：从原始项目 `feature/generation/src/main/kotlin/` 目录恢复

6. **engine/src/main/kotlin/app/zhijuan/feature/generation/UnknownResultRecoveryCoordinator.kt**
   - 原因：定义的类型 UnknownResultRecoveryCoordinator 无任何外部引用
   - 可还原：从原始项目 `feature/generation/src/main/kotlin/` 目录恢复

7. **app/src/main/kotlin/app/zhijuan/provider/transport/SensitiveJsonBodyBuilder.kt**
   - 原因：定义的类型 SensitiveJsonBodyBuilder 无任何外部引用（不被 transport 或 adapter 使用）
   - 可还原：从原始项目 `provider/transport/src/main/kotlin/` 目录恢复

### 分析结果（未删除的文件）
- 33 个 feature/generation 文件有类型被其他文件引用，不能删除
- 4 个 provider/fake 文件全部被引用（FakeProviderAdapter 等）
- 32 个 app/provider 文件大部分被引用（除已删除的 SensitiveJsonBodyBuilder）
- 8 个 app/reader 文件（UI Screen、Application）是 Android 框架入口点，不被 Kotlin import 引用但被 AndroidManifest 和 Compose 引用

### 还原方法
从原始项目 `C:\Users\85086\Desktop\ai-novel-reader-app2-main\` 对应目录复制文件回工作副本即可。
