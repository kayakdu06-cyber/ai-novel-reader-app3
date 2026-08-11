# 工作汇报 119：TASK-064 Phase 2D1 candidate draft 合同审计

日期：2026-08-09  
项目：织卷 Android（app开发2）  
唯一项目根目录：`D:\gptuser\projects\ai-novel-reader-app2`

## 本阶段目标

在把第一条远程 route 加入 registry 前，查清 candidate draft 是否已经具备真实的初始 Stage 创建、请求、流式执行、验证、BODY seal、MEMORY successor 和崩溃恢复入口。

## 结论

不能直接实现或注册当前 `CANDIDATE_CHAPTER_DRAFT_V1`。它不是“只差一根接线”，而是初始正文身份合同尚未完成：

- route resolver 使用 `ChapterCandidateStageBindingV1` 的 BODY+DRAFT 识别它；
- 该 binding 要求 candidateChapterVersionId、candidateContentHash、predecessor 和 route/request binding；
- 初次正文还没有生成，candidate version/hash 不可能是真实的请求前输入；
- Provider-open guard 明确规定 bound BODY 必须是 `REVISE_CHAPTER`；
- BODY seal 对初始 DRAFT 只检查 revisionIndex=0，不解析 candidate source；
- final recovery 也要求 revisionIndex=0 的 initial body `inputSource == null`。

这不是合法双格式，而是 resolver 先行、initial-draft 冻结来源尚未落地的合同死路。保持未注册是正确行为。

## 生产能力盘点

| 能力 | 当前状态 |
|---|---|
| Phase 2B exact-token PREPARING 快照 | 已完成 |
| Attempt/Usage/受保护流式草稿 | 底层生产原语完整 |
| STOP/LENGTH/INVALID/拒绝/失败/取消/UNKNOWN | 底层生产原语完整 |
| 输出 validation | 底层生产原语完整 |
| candidate BODY seal + MEMORY successor 原子事务 | repository 完整 |
| initial DRAFT Stage factory | 不存在 |
| initial request/profile/adapter 生产装配 | 不存在 |
| STOP 后 initial BODY validation+seal 调用者 | 不存在 |
| initial crash resume/continuation runner 入口 | 不存在 |

生产代码中 `ChapterCandidateStageBindingV1.stageSetup` 直接调用只有三处，均用于派生 successor 或 revision successor。`ChapterDraftStreamingResult.ReadyForValidation` 的生产消费者只有 revision coordinator。Android 测试能够手工造裸 DRAFT Stage 并 seal，不代表 App 存在生产入口。

## 风险

- P0：伪造尚未生成的 candidate hash 会让 request identity 与输出 identity 混为一体，导致错发或重复付费请求。
- P0：只放宽 Provider-open guard 会造成 resolver、seal、recovery 对同一 Stage 解释不一致。
- P0：STOP 后没有 initial seal 调用者，可能出现“Provider 已成功、Stage 永远未推进”。
- P1：continuation/UNKNOWN 底层原语存在，但没有 total runner 恢复入口；直接接线可能从 PREPARING 重发。
- P1：绕过 seal 直接推进 Stage/Job 会破坏同事务 BODY evidence、MEMORY successor 和 cursor 原子性。

## DeepSeek 运行

- run：`20260809-014555-0cb91ec2`
- 模型：DeepSeek V4 Flash
- 推理：max
- sandbox：read-only / patch-proposal-only
- 硬上限：30 分钟；实际约 9 分 48 秒，正常完成，未超时
- Token：total 2,781,479；cached input 2,406,656；output 40,780；reasoning 21,731
- 任务前后 Git status：215 条，零新增差异
- 权限请求：0；代码写入：0；Gradle/模拟器：未运行；Provider：0

DeepSeek 的完整回交保存在 `D:\gptuser\logs\ai-novel-reader-app2\deepseek\20260809-014555-0cb91ec2.final.md`。Sol 已独立复核 guard、seal、recovery 和生产调用点，采纳合同死路结论；没有直接采纳“马上写 adapter”的部分。

## 本阶段改动

只有任务包、文档和本报告，没有修改 Kotlin、Room schema、migration、Gradle、registry 或 Provider。

## 当前基线

本阶段是只读审计，没有用旧测试冒充新合同验收。最近完整基线仍为：

- generation JVM 131/131；
- API 30/API 35 generation 各41/41；
- 统一离线门禁 801 actionable tasks；
- Debug/Release、Lint/Vital、R8、源码与 5 APK 安全扫描、备份排除通过；
- Git remote 为空，物理设备写入 0。

## 下一步

先审计已持久化的 planning/context/scene contract，定义独立 `initial-draft` 冻结来源：其全部字段必须在 Provider 请求前存在并可从 Room 重验。随后建立生产 Stage factory 和 route parser；只有合同、Provider-open、seal/recovery 对称后，才实现 exact-token streaming adapter 并考虑 registry 注册。

TASK-064 仍为进行中。
