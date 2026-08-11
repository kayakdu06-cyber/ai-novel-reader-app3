# 工作汇报 109：TASK-063 确定性 Fake Provider 与生成速度基准

> 日期：2026-08-08  
> 项目：织卷 Android App  
> 唯一仓库：`D:\gptuser\projects\ai-novel-reader-app2`

## 1. 阶段结论

TASK-063 已完成。项目现在具备独立、可复现、无需真实模型和网络的流式 Fake Provider，可以按脚本精确模拟首字节、首个完整段落、正文结束、错误、意外断流和终态，并能用虚拟时钟在数秒内验证数分钟级超时场景。

本阶段还建立了 20 组参考 BODY 的可重复速度基准，并通过 TASK-062 的正式脱敏时序账本计算 P50、P95 和最慢值。结果证明当前正文流式执行路径能够正确记录速度和终态；它不等于真实模型已经达到目标速度，也不等于整章 total runner 已完成。

## 2. 主要实现

### 2.1 独立 Fake Provider 模块

- 新增 `provider:fake`，只作为测试基础设施，不进入正式运行依赖。
- `FakeStreamScript` 用显式步骤描述等待、文本块、心跳、用量、错误和终态。
- `FakeProviderAdapter` 实现正式 Provider 接口，支持可重复收集、调用统计和有限脚本执行。
- `VirtualFakeStreamClock` 不进行真实长时间等待，可快速推进虚拟时间；`RealFakeStreamClock` 保留真实时钟语义。
- 构造阶段拒绝负延时、非法脚本、重复终态和 Token 总数溢出，避免测试夹具本身制造模糊结果。

### 2.2 速度分布报告器

- 新增 `GenerationTimingBenchmarkReporter`，按最近秩规则计算 P50、P95 和最慢值。
- 失败、缺事件、跨重启和不适用样本不会被静默删除；报告会分别保留这些计数。
- 这可以避免只统计成功快样本、把失败慢样本藏起来后得到虚假漂亮数字。

### 2.3 正式执行路径接线

- `feature:generation` 仅在 `androidTest` 依赖 `provider:fake`，Release/正式实现没有 Fake Provider 依赖。
- 原有成功时序集成测试改用共享 Fake Provider，验证首字节、首段、正文结束和 Token 用量。
- 增加虚拟 301 秒无终态断流测试：经真实 RequestIntent、Room、加密草稿和正式执行器后，结果进入 `UNKNOWN`，不会被误判成功，也不会自动重发。
- 增加 20 个参考 BODY 测试，正文长度为 2,500–3,450 Unicode code point。

## 3. DeepSeek 执行与 Sol 复核

### 3.1 DeepSeek 执行记录

- Run ID：`20260808-201004-b5d24248`
- 模型：DeepSeek V4 Flash
- 推理强度：`max`
- 硬超时：30 分钟
- 总 Token 上限：未设置
- 实际用时：19 分 38 秒
- 总 Token：2,202,934
- 缓存输入：1,908,864
- 输出：125,512
- 推理输出：103,804
- 退出码：0
- 权限问题：无
- 可审查代码差异：有

DeepSeek 完成了独立模块、脚本模型、虚拟时钟、Adapter 和首批 JVM 测试。说明把任务收窄并放宽思考时间后，它能够产生有效代码，不是只返回思考过程。

### 3.2 Sol 复核与修正

Sol 没有直接接受 DeepSeek 的自测结果，复核后修正了以下问题：

- 并发收集时，多个脚本共用虚拟时钟可能把别的调用等待时间算进当前调用；现改为每次收集只累计自己已完成的等待步骤。
- 虚拟等待先 `yield` 再推进，确保并发测试具有确定性。
- “没有终态”统一按意外 EOF 表达，避免把协议不完整描述成正常结束。
- Provider 同时返回输入、输出 Token 时，会安全计算总 Token；若总数溢出，在构造阶段就拒绝。
- 增加总 Token 映射与溢出测试。

第一次 API 35 集成测试真实失败：期望总 Token 为 300，但 Fake Provider 返回了空值。原因是首版用量映射不完整；修复后目标测试和完整模块测试均通过。该失败没有被删除或绕过。

## 4. 20 组参考 BODY 基准

| 指标 | P50 | P95 | 最慢值 |
|---|---:|---:|---:|
| 首字节 | 10.9 秒 | 11.8 秒 | 11.9 秒 |
| 首个完整段落 | 18.35 秒 | 19.70 秒 | 19.85 秒 |
| BODY 正文结束 | 147 秒 | 174 秒 | 177 秒 |

边界说明：正式提交完成指标在这 20 次中明确记录为 `MISSING_EVENT`，因为统一整章 total runner 和 commit 接线属于 TASK-064。这里没有用 BODY 结束时间冒充整章完成时间。

## 5. 验证证据

- `provider:fake` JVM：24/24。
- `core:diagnostics` JVM：13/13。
- API 30 `feature:generation` Android：39/39。
- API 35 `feature:generation` Android：39/39。
- 统一 Gradle JVM 报告：537/537，0 失败、0 错误、0 跳过。
- `scripts/verify-build.ps1 -Offline`：801 actionable tasks；Debug、Release、Lint/Vital、R8 全部通过。
- 安全扫描器自测：4/4。
- 源码与 5 个现存 APK：`SECURITY_SCAN_OK`。
- 备份排除策略：`BACKUP_EXCLUSION_POLICY_OK`。

## 6. 安全与仓库边界

- 真实 Provider 调用：0。
- 物理设备写入：0；只使用 API 30 和 API 35 模拟器。
- Git remote：无。
- 原项目 `D:\gptuser\projects\ai-novel-reader`：未修改。
- 现有 TASK-059～TASK-062 大量 WIP：保留；未执行 reset、clean、checkout、commit。
- API Key：未读取、未显示、未写入报告或代码。

## 7. 尚未完成

- TASK-064：统一整章 total runner、正式提交耗时和全阶段接线。
- TASK-065：生成中正文展示与边生成边阅读。
- TASK-066：进度 watchdog、卡死检测和有限恢复。
- TASK-068：完整故障矩阵与压力验证。
- TASK-069：用户明确授权后的真实 Provider 速度与质量验证。

因此当前不能宣称 App 已经能够稳定自动生成完整章节，也不能用 Fake Provider 的基准替代真实模型速度结论。

## 8. 下一步

直接进入 TASK-064。先审计现有各 Stage 的真实入口、事务边界、恢复语义和 TASK-062 时序事件，再把它们接入一个有限、可取消、不会因不确定结果而盲目重发的整章 runner；DeepSeek 继续采用最高推理强度、窄任务拆分和 15–30 分钟单任务窗口，最终差异仍由 Sol 审查并在双模拟器验证。
