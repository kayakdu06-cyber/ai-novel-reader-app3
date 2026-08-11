# 工作汇报 114：TASK-064 Phase 2A 派生 route identity

日期：2026-08-09  
项目：织卷 Android（app开发2）  
唯一项目根目录：`D:\gptuser\projects\ai-novel-reader-app2`

## 本阶段目标

先关闭 total runner 最危险的分发歧义：同一个 `EXTRACT_MEMORY` phase 同时承载章节记忆和剧情追踪，不能只看 phase 选择 executor。本阶段只建立冻结来源合同的有限 route identity，不接网络、不推进状态。

## 完成内容

1. 新增 `GenerationRunnerStageRouteResolver` 和 10 个有限 route：
   - 普通章节记忆 v1；
   - 编辑重建章节记忆 v2；
   - 普通剧情追踪 v1；
   - 编辑重建剧情追踪 v2；
   - candidate 正文、记忆、追踪、一致性、修订；
   - final chapter commit v3。
2. 解析器先严格读取 `sourcePolicyVersion`，随后必须调用现有权威 parser 完整验证 schema、root keys、phase、target、targetId、inputVersionHash 和 binding/hash。
3. 权威 parser 抛错后不尝试另一个 route，也没有 generic/unknown route 可继续执行。
4. candidate 与 final-commit 的 policy 常量改为包内可见，避免复制版本字符串；值与解析行为未改变。
5. planning、context 和普通未绑定 draft 明确不在本阶段支持范围，遇到时失败关闭。

## DeepSeek 交接

- 任务包：`docs/ai/task-packets/TASK-064-PHASE-2A-DERIVED-ROUTE-IDENTITY.md`
- 运行：`20260808-234024-85842439`
- 模型：DeepSeek V4 Flash，推理强度 `max`
- 硬上限：30 分钟；累计 Token 上限：无
- 实际耗时：约 15 分 28 秒，正常结束
- 总 Token：2,402,928；cached input 2,136,064；output 58,593；reasoning 40,611
- 本次产生了实际代码和测试差异，没有权限请求。Sol 没有直接接受自述，而是重新阅读解析器、测试和常量可见性，并独立复测。

## 代码变化

- 新增：
  - `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationRunnerStageRouteResolver.kt`
  - `core/database/src/test/kotlin/app/zhijuan/core/database/generation/GenerationRunnerStageRouteResolverTest.kt`
- 极小可见性调整：
  - `ChapterCandidateStageBindingV1.SOURCE_POLICY_VERSION`
  - `ChapterFinalCommitStageBindingV1.SOURCE_POLICY_VERSION`
- 未修改 DAO、Room schema、migration、Provider、App、UI、WorkManager 或真实模型配置。

## 验证证据

- resolver JVM：11/11。
- `core:database` JVM 全量：81/81。
- API 30 `emulator-5556` 数据库 Android 全量：209/209。
- API 35 `emulator-5558` 数据库 Android 全量：209/209。
- 源码安全扫描：`SECURITY_SCAN_OK`。
- `git diff --check`：0。
- Git remote：为空。
- App 内真实 Provider：0；Fake Provider：0；物理设备写入：0。

## 已关闭风险

- memory/tracking 不会再因共用 `EXTRACT_MEMORY` 被 phase-only dispatcher 混淆。
- 仅把 schema 数字改成 v2 不能冒充编辑重建；必须存在并通过正式 rebuild binding。
- candidate 的 role 与 phase 不兼容时不会获得 executor route。
- 未知、缺失、畸形、额外字段、错误 hash 或未来 policy 不会降级到某个默认执行器。

## 仍未完成

- route 还没有与当前 Job/Stage 的精确有效租约绑定；纯解析器不能直接成为外部执行权限。
- planning、context、普通 draft 的 route identity 尚未建立。
- 有限 executor registry、request preparation adapter、多阶段循环、全 phase timing、完整 Fake 第一章与统一 Release/R8 尚未完成。
- TASK-064 整体保持进行中。

## 下一步

实现只读的 current-leased-stage route repository：在同一事实截面验证 Job/Stage 仍是 current、两个 token 同 owner 且精确匹配、租约未过期、Stage 状态允许分发，然后才返回 Phase 2A 的有限 route。该层仍不调用 Provider、不创建 Attempt、不推进状态。
