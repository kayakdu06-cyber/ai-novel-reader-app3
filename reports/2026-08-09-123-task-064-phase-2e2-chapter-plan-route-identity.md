# 工作汇报 123：TASK-064 Phase 2E2 普通章节计划来源身份

> 日期：2026-08-09  
> 项目：`D:\gptuser\projects\ai-novel-reader-app2`  
> 结果：完成严格来源身份与 route；保持 registry 未注册、Provider 0

## 1. 完成内容

普通 `BUILD_CHAPTER_PLAN` Stage 现在拥有独立、请求前可得的 `zhijuan.chapter-plan-source.v1` 身份，不再因缺少 `sourcePolicyVersion` 而只能表现为未知合同。

工厂冻结的 plan root 现在严格包含：

- `schemaVersion=1`；
- `sourcePolicyVersion=zhijuan.chapter-plan-source.v1`；
- Prompt Bundle 与 `outputSchemaId=chapter-plan.v1`；
- 唯一 context Stage 依赖、context input hash、context policy 与 manifest schema；
- 原有完整 `chapterProgressionGate`。

`parseAndVerifyChapterPlan` 会在纯内存中验证：

- Stage 必须是 `BUILD_CHAPTER_PLAN + CHAPTER`，目标非空，最大尝试数 1～4；
- root 必须是严格 JSON object 且字段集合完全相等；
- policy、bundle、输出 schema、context policy/manifest 都是当前固定版本；
- dependency 必须恰好一项并与 `contextAssemblyStageId` 相等；
- context Stage ID、context input hash、progression evidence hash 合法；
- progression evidence 自哈希一致，chapterId 与 Stage target 相同，chapterIndex 至少为 1；
- Stage `inputVersionHash` 与完整冻结 JSON 一致。

解析结果只返回有限 identity，`toString` 隐藏 Stage ID 和 hash。没有复制 context payload、书籍正文、prompt、连接或密钥。

## 2. route 与执行边界

新增有限 route `CHAPTER_PLAN_V1`。resolver 只能先命中 plan source policy，再调用唯一严格 parser；不存在按 `BUILD_CHAPTER_PLAN` phase 猜测的 fallback。

`GenerationRunnerExecutorRegistryV1` 对该 route 有显式 `notRegistered` 分支，注册集合仍严格是：

1. `FINAL_CHAPTER_COMMIT_V3`；
2. `CHAPTER_CONTEXT_ASSEMBLY_V1`。

因此本阶段只解决“这是什么工作”，没有授予“可以发送请求”的权限。未创建 RequestIntent/Attempt/Usage，没有 Provider、费用或状态推进。

## 3. 修改文件

生产代码：

- `ChapterContextAssemblyJobFactory.kt`：plan policy、严格 parser、有限 source identity；
- `GenerationRunnerStageRouteResolver.kt`：新增 `CHAPTER_PLAN_V1` 并接唯一 parser；
- `GenerationRunnerExecutorRegistry.kt`：新增 route 显式未注册分支。

测试：

- `ChapterContextAssemblyJobFactoryTest.kt`：独立 policy、正向解析、脱敏与合同损坏负例；
- `GenerationRunnerStageRouteResolverTest.kt`：plan 正向 route、policy/phase/target/hash 失败关闭；
- `GenerationRunnerExecutorRegistryTest.kt`：以 plan route 证明未注册异常只携带有限 enum。

没有改 schema、migration、DAO、Provider adapter、Gradle 依赖或 App UI。

## 4. 验证

| 验证 | 结果 |
|---|---|
| factory + resolver + registry 定向 JVM | 8 + 15 + 2，全部通过 |
| `:core:database:testDebugUnitTest` | 90/90，0 失败/错误/跳过 |
| `:feature:generation:testDebugUnitTest` | 131/131，0 失败/错误/跳过 |
| API 30 `:core:database:connectedDebugAndroidTest` | 218/218 |
| API 35 `:core:database:connectedDebugAndroidTest` | 218/218 |
| API 30 `:feature:generation:connectedDebugAndroidTest` | 42/42 |
| API 35 `:feature:generation:connectedDebugAndroidTest` | 42/42 |
| `scripts/verify-build.ps1 -Offline` | 801 actionable tasks；Debug/Release、Lint/Vital、R8通过 |
| 安全与备份 | 扫描器自测、源码与5个APK扫描、备份排除通过 |
| `git diff --check` | 通过，仅既有行尾转换提示 |

App 内真实/Fake Provider 调用 0，物理设备写入 0，Git remote 为空。

## 5. 尚未完成

- `chapter-plan.v1` 严格输出 schema/parser/业务交叉校验；
- 目的地确认与三层预算原子预留；
- exact Job+Stage token 的 RequestIntent/Provider executor；
- plan 原子提交、DEC-068 initial DRAFT 冻结、UNKNOWN 与成功 replay；
- registry 注册和完整 Fake 第一章。

下一阶段 Phase 2E3 先完成严格、有界的 `chapter-plan.v1` 输出合同，不开放 registry 或真实 Provider。
