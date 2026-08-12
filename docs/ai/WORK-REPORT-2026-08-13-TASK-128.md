# TASK-128 工作报告：单章 persistent total runner

> 日期：2026-08-13  
> 状态：完成

## 完成内容

- 恢复并接通持久 READY 队列、双租约 heartbeat、唯一有界 Job 循环。
- 五条有限 route 共用一个 registry：context、chapter plan v2、initial body、post-analysis、final commit；未知 route 不回退。
- plan、正文和合并章后分析均从 Room exact-token 冻结来源装配请求；本地 final route 不调用 Provider。
- FGS 与 WorkManager 使用同一个 runner/runtime，不维护第二套游标或执行逻辑。
- 修复 runner 遇到已被别处领取的 READY Job 时错误提前结束的问题。
- 修复后分析谱系重验遗漏 initial-draft 权威链的问题；仍逐级验证计划、上下文和章节推进许可。
- 区分“当前候选 route 标识”和“本次决策生成的下一 route 标识”，关闭首次候选在接受、修订和 needs-action 分支中的标识混用。

## 单章闭环证据

- 设备：API 35 `emulator-5558`。
- 数据：真实 Room 内存库、受保护 artifact、Fake Provider；真实 Provider 调用 0。
- 流程：真实 context assembly → 冻结 chapter plan v2 → pause/resume → plan → body → post-analysis → local final commit。
- 结果：5 个 Stage 全部 `SUCCEEDED`；runner 执行 4 个后续 Stage；3 次 Fake 请求；3 个 FINAL Usage；Job=`COMPLETED`。
- 最终只生成 1 个正式 ChapterVersion，正文与摘要均可回读。
- 混合状态样本同时包含人物、关系、系统、道具和虚构成年亲密场景连续性；本任务只验合同、逻辑和持久一致性，不评价文笔。

## 验证

- `:data:testDebugUnitTest :feature:generation:testDebugUnitTest`：通过。
- 全量 JVM XML：182/182，0 失败、0 错误、0 跳过。
- TASK-128 Android 专项：1/1，通过。
- `assembleDebug test`：通过，365 tasks。
- 模块边界：10 模块、依赖无环；唯一 feature 例外仍为 `template -> creation`。
- 安全扫描：通过。
- Debug APK SHA-256：`DEC92DF9B4804C414D70AA70215C565A6DB7737B4E1C6E578D2AB651C66CCB68`。

## 提交

- `1904b34`～`36406e3`：队列、runner、五 route executor、runtime 和双 host 共用入口。
- `59967de`：Fake 单章 Android 闭环与谱系安全修复。
- `5893a17`：候选 route 身份分离及 needs-action 同类加固。

## 未完成边界

- 当前证明的是“已有冻结 chapter plan v2 起点可以跑完整章”。普通用户点击开始并创建该冻结起点属于 TASK-130。
- 下一章自动创建和 3～5 章连续循环属于 TASK-129。
- 书架/阅读器属于 TASK-131；真实 DeepSeek 验收从 TASK-132 开始。

## 下一步

TASK-129：只在 `:feature:generation` 复用现有 runner，完成 3～5 章 Fake 自动循环和一次暂停/恢复验收。
