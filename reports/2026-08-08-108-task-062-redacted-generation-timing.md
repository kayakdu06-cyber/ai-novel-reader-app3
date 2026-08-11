# 工作汇报 108：TASK-062 脱敏生成时序、基准时钟和报告器

> 日期：2026-08-08  
> 项目：织卷 Android App  
> 唯一仓库：`D:\gptuser\projects\ai-novel-reader-app2`

## 1. 阶段结果

TASK-062 已完成，TEST-093 已关闭。本阶段建立了可长期保存在 SQLCipher 主库中的脱敏时序账本，能够分别计算章节排队、本地准备、Provider 首响应、首个完整段落、正文流、记忆、追踪、一致性、可选修订、提交和全章耗时。

这不是 total runner：当前只把 BODY 的真实 Fake 流式执行路径接入时序，其余阶段由 TASK-064 在统一调度时发射；固定延迟、慢流和断流分布由下一任务 TASK-063 建立。

## 2. 主要实现

### 2.1 时序领域与时钟

- 新增 `GenerationTimingPhase`、有限 milestone/outcome、确定性事件 ID 和域分离关联指纹。
- 事件身份包含 phase，BODY、MEMORY、TRACKING 等阶段的同名 Stage 事件不会碰撞或混算。
- 每个 mark 同时保存 epoch、`SystemClock.elapsedRealtime()` 和 boot 指纹；epoch 只展示，duration 只用 monotonic。
- Android boot count 不可读时退化为进程会话指纹，使跨进程/跨重启 duration 保守不可用，不猜值。
- 首段探测器只保存非空白状态、完成标记和 Unicode code point 计数，不保留正文。

### 2.2 schema v16

- 新增 append-only `generation_timing_event` 和 v15→v16 正式迁移。
- 只持久化有限枚举、时间、非负计数和 24/64 位指纹；没有自由文本、正文、人物、提示词、端点、Provider request id、secret、原始业务 ID 或原始内容 hash 字段。
- 索引覆盖 run 时间线、stage+phase+milestone、attempt+phase+milestone 和 boot 时间线。
- 触发器拒绝非法 phase/milestone、错误固定阶段、关联层级错误、缺前驱、同 boot 时间倒退、成功正式提交后的迟到事件、UPDATE 和 DELETE。
- 同一确定性事件精确 replay；同 ID 不同时间、结果或计数失败关闭。

### 2.3 报告器与流式接线

- CONTEXT 排队/准备按相同 Stage 指纹配对；首字节/首段/正文结束按相同 Attempt 指纹配对。
- 跨 boot、缺事件、单调回退、失败终态和依赖缺失分别返回明确不可用原因；未发生修订返回 `NotApplicable`。
- `AuditedStreamingProviderExecutor` 增加可选 timing clock/context/recorder，旧调用默认 no-op 兼容。
- Heartbeat 和 `NOT_SENT` 本地失败不冒充首字节。
- 正常、截断、拒绝、未知结果、暂停、取消和流无终态均产生有限 BODY 结束结果；迟到重复结算不重复写事件。

## 3. DeepSeek 使用与 Sol 裁决

- Run ID：`20260808-184643-6858d55d`
- 模型：DeepSeek V4 Flash
- 推理强度：`max`
- 允许时长：25 分钟；用户已允许放宽后续 DeepSeek 思考时长
- 实际用时：约 7 分 54 秒
- 总 Token：711,706；缓存输入 525,568；输出 54,777
- 退出码：0；权限请求 0；工作树代码写入 0

采纳了 SQLCipher 追加账本、epoch+monotonic+boot 和滚动诊断分工。Sol 修正了 DeepSeek 提案缺少 `STAGE_STARTED`、`COMMIT_STARTED`、正文公式错误、把修订当必需阶段、boot 文件不能识别重启和任意 UUID replay 身份等问题，并额外加入 phase 隔离、同 Stage/Attempt 配对和失败终态。

## 4. 验证证据

### Android 项目模拟器

- API 30 `emulator-5556`：`core:database` 192/192，`feature:generation` 37/37。
- API 35 `emulator-5558`：`core:database` 192/192，`feature:generation` 37/37。
- 运行时构造 secret canary 后，`GenerationTimingDatabaseTest` 在两台模拟器再次各 4/4。

### JVM

- `core:diagnostics`：10/10。
- `core:database`：70/70。
- `feature:generation`：120/120。

### 统一门禁

- `scripts/verify-build.ps1 -Offline`：797 actionable tasks；Debug、Release、Lint/Vital、R8 和 JVM 通过。
- 扫描器自测：4/4。
- 源码与 5 个现存 APK：`SECURITY_SCAN_OK`。
- 备份排除：`BACKUP_EXCLUSION_POLICY_OK`。
- `git diff --check`：返回 0，仅有既有 LF→CRLF 提示。

第一次统一门禁已完成全部 797 个 Gradle 任务，但最后发现 Kotlin 把测试中的假密钥字符串常量折叠进 androidTest APK。Sol 将 canary 改为运行时字符数组构造、重建测试 APK并重跑门禁；最终 APK 扫描为 0 命中。这不是忽略扫描规则，而是保留运行时密钥 canary 测试并消除测试产物中的静态假密钥材料。

## 5. 安全与未完成边界

- App 内真实 Provider 调用：0。
- 物理设备写入：0。
- Git remote：无。
- 原项目及其他相似目录：未访问、未修改。
- 大量 TASK-059～061 既有 WIP：保留，未 reset/clean/checkout/commit。
- TASK-063 固定延迟/慢流/断流 Fake、TASK-064 total runner、TASK-065 生成中正文、TASK-066 watchdog、20 章速度统计和真实模型档案均未冒充完成。

## 6. 下一步

直接进入 TASK-063：构建可重复、可配置但有界的固定延迟、慢流和断流 Fake Provider 夹具，用本任务的权威时序表输出 P50、P95 和最慢值；仍不调用真实 Provider，不等待用户再次确认。
