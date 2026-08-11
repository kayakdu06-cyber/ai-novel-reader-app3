# 工作汇报 91：TASK-061 Phase 1 用户编辑原子提交与失效

> 日期：2026-08-05  
> 项目：织卷 Android App  
> 唯一仓库：`D:\gptuser\projects\ai-novel-reader-app2`  
> 状态：TASK-061 进行中；Phase 1 已完成，Phase 2 有序重建待继续

## 1. 本阶段结果

本阶段关闭了一个会导致严重数据错乱的生产缺口：过去低层章节 DAO 虽然能保存 `USER_EDIT` 版本，却不会同步失效旧摘要、人物/事实、时间线/伏笔、聚合、后续上下文和 FTS 索引。若直接把它作为编辑入口，后续生成可能继续读取旧记忆。

现在新增了用户编辑专用事务边界。编辑一本已有 10 章的书中第 3 章时，系统会一次性完成：

1. 校验书、章节、预期旧 current version 和时间；
2. 在 repository 内计算新正文 SHA-256，不信任调用方提供 hash；
3. 在旧派生状态改变前捕获全部受影响搜索 source identity；
4. 调用既有 stale 级联；
5. 保存新的不可变 `USER_EDIT` 版本，旧版本继续保留；
6. 以旧 current、旧状态、旧一致性状态和单调时间做 CAS，把当前章切换为 `EDITED/UNKNOWN`；
7. 删除旧 FTS 来源，不为尚未重建的新正文伪造摘要或索引。

上述步骤都在同一个 Room/SQLCipher 事务边界内；任何异常都会整体回滚。后续章节正文和 current version 不删除，只按原有规则变为一致性未知。

## 2. DeepSeek 审计与 Sol 决策

先形成了独立任务包，再用项目隔离的 DeepSeek V4 Flash 做只读补丁审计：

- 运行 ID：`20260805-125556-8f67effa`
- 有效沙箱：`read-only`
- 推理等级：`max`
- 耗时：约 7 分 35 秒
- 累计 Token：1,197,395；其中缓存输入 932,096，输出 51,930
- 结果：正常完成并返回完整审计和 unified diff；没有写工作树，没有调用 App Provider。

DeepSeek 正确识别了编辑专用 CAS、失效前捕获索引 identity、稳定版本 ID replay 和 generic DAO 夹具兼容问题。Sol 没有原样套用提案，而是做了以下加固：

- 命令显式增加 `bookId`，跨书错误在任何写入前失败；
- CAS 除 current version 外还比较旧 `status` 和旧 `consistencyStatus`，防止状态竞态；
- replay 同时核对正文内容与 hash、parent、source、Stage/model 空值和当前章状态；
- 命令和结果默认字符串表示隐藏正文及标识符；
- 把搜索计数命名为“失效 identity 数”，不虚假声称每个 identity 都实际删除了一行；
- 没有把编辑章自己的历史上下文/报告状态固化为新语义，也没有扩大到 Phase 2 重建。

## 3. 代码与测试改动

### 生产代码

- `core/database/src/main/kotlin/app/zhijuan/core/database/library/LibraryDao.kt`
  - 新增编辑专用原子 CAS；一次 UPDATE 同时写 current version、`EDITED`、`UNKNOWN` 和时间。
- `core/database/src/main/kotlin/app/zhijuan/core/database/library/ChapterUserEditRepository.kt`
  - 新增用户编辑命令、脱敏结果、失效计数和完整跨 DAO 事务。

### 测试

- `core/database/src/androidTest/kotlin/app/zhijuan/core/database/ChapterUserEditDatabaseTest.kt`
  - 10 章编辑第 3 章；
  - 旧版本保留、新版本来源/parent/hash/current/status；
  - 第 3 章旧摘要、第 3–10 章聚合、第 4–10 章上下文/报告 stale；
  - 第 4–10 章正文/current 保留、一致性未知；
  - 正式 FTS 与外部内容表旧行删除；
  - 精确 replay；
  - 同版本 ID 不同正文冲突；
  - 跨书、错章和过期 expected current 失败关闭；
  - 命令/结果默认字符串脱敏。

### 文档

- 新增 `docs/ai/task-packets/TASK-061-PHASE-1-ATOMIC-USER-EDIT-INVALIDATION.md`。
- 同步生成系统、测试计划、实现待办、追踪矩阵、工作状态、AI 协议和当前交接现场。

## 4. 验证证据

| 验证 | 结果 |
|---|---|
| `:core:database:compileDebugKotlin` | 通过 |
| `:core:database:compileDebugAndroidTestKotlin` | 通过 |
| TASK-061 Phase 1 定向测试，API 30 | 3/3 通过 |
| TASK-061 Phase 1 定向测试，API 35 | 3/3 通过 |
| `core/database` 全量，API 30 | 146/146 通过 |
| `core/database` 全量，API 35 | 146/146 通过 |
| `scripts/verify-build.ps1 -Offline` | 797 actionable tasks，BUILD SUCCESSFUL |
| Release/R8 | 通过 |
| 安全扫描 | `SECURITY_SCAN_TESTS_OK`、`SECURITY_SCAN_OK`，源码与 5 个 APK |
| 备份排除策略 | `BACKUP_EXCLUSION_POLICY_OK` |
| 文档后安全扫描 | 通过 |
| `git diff --check` | 通过；仅现有 LF/CRLF 提示 |

第一次定向设备命令被 PowerShell 错误拆分 Gradle `-P` 参数，测试没有启动；改为单引号保护完整参数后，双 API 均正常通过。这是测试命令转义问题，不是代码失败。

## 5. 风险与明确未完成项

- TASK-061 还没有完成：当前只保证“编辑提交与旧数据失效”正确，尚未按章节顺序重建新摘要、人物/事实、时间线/伏笔、聚合、索引和后续上下文。
- 现有 tracking 来源仓库在编辑章之后已有正式章节时会主动拒绝单章重建；Phase 2 必须用明确的顺序编排解决，不能删除保护条件或并行乱序重建。
- 当前 schema 没有独立 user-edit request ID。精确 replay 依赖调用方对同一次保存复用稳定 `newVersionId`；同一逻辑请求若每次换新 ID，数据库无法判断它们是否同一次请求。Phase 2/后续 UI 接线必须把稳定 ID 当作契约。
- generic `LibraryDao.commitChapterVersion(USER_EDIT)` 仍保留供现有数据库测试建夹具；生产调用必须只走 `ChapterUserEditRepository`。后续接 UI 时需要做一次调用点旁路审计。
- 没有调用织卷 App 内真实生成 API，没有产生费用；没有向物理设备安装、写入或修改设置；没有 Git remote 操作。
- 当前 App 仍无按 phase 分发的总 runner，不能描述为已经能够自动完成整本书生成。

## 6. 下一阶段

继续 TASK-061 Phase 2：从编辑章开始建立确定性的有序重建计划，复用现有 memory extraction、story tracking、context assembly 和原子提交边界；先完成不联网的计划/状态/依赖证明，再用 Fake Provider 完成 TEST-033。任何带真实模型费用的重建都必须保留费用与用户选择边界。
