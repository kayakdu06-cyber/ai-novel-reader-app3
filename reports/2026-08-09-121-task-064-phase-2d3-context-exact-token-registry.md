# 工作汇报 121：TASK-064 Phase 2D3 context exact-token registry

> 日期：2026-08-09  
> 仓库：`D:\gptuser\projects\ai-novel-reader-app2`  
> 结论：Phase 2D3 已完成并通过双 API、Release/R8 与安全门禁；TASK-064 总体仍进行中。

## 1. 本阶段结果

纯本地 `ASSEMBLE_CONTEXT` route 已从“只能识别”升级为“可由 total runner 安全执行”：

1. repository 新增只消费数据库 bound snapshot 的 exact-token 入口；
2. Job/Stage 双 token、current cursor、状态、heartbeat、租约和 attempt 边界与业务提交在同一个 Room 事务复核；
3. 旧入口保留，两个入口共用唯一 context assembly/commit 实现；
4. 成功 Stage 可只读 durable replay，不重复插入 snapshot 或推进 chapter-plan；
5. registry 白名单从仅 final commit 扩为 final commit+context，其余九条 remote route继续失败关闭。

本阶段没有接入或调用 Provider，没有创建 Attempt/Usage，没有修改 schema、migration 或 DAO。

## 2. DeepSeek 交接与 Sol 回收

DeepSeek 运行 `20260809-025115-1315827e` 使用 `max` 推理、30分钟硬上限和无累计Token上限，在第30分钟触发安全终止：

- total tokens：8,520,253；
- cached input：7,762,688；
- output：162,050；
- reasoning output：113,599；
- final 回交：无；
- 权限请求：无；
- 结果：留下 repository/registry 的部分WIP，但测试与代码结构未收口。

Sol 没有把超时WIP视为完成。首次编译发现 context repository 多出一个闭合括号，导致后半个类被解析为顶层代码，并连带造成既有 Provider-open guard 调用不可见。修复结构后，Sol继续复核 exact双租约、时间、attempt、cursor、durable replay和registry分流，并完成全部测试。

用户随后允许放宽DeepSeek思考时长。工作流现保留15分钟默认值和通常15～30分钟预算；只有同一窄任务已在30分钟正常推理超时且没有权限、范围或重复失败阻塞时，才可在新任务包说明理由后提高到最多45分钟。隔离验证命令已确认45分钟、无Token上限、`max`推理配置有效。

## 3. 关键实现

### 3.1 数据库执行边界

- 新增 `ChapterContextAssemblyBoundExecutorV1`。
- `assembleBound(snapshot, assembledAt)` 只接受 `CHAPTER_CONTEXT_ASSEMBLY_V1`。
- active path 在同一 `withTransaction` 中重读 Job/Stage，再验证：
  - Job仍为`RUNNING`；
  - Stage仍为current `PREPARING`；
  - persisted Job/Stage token分别与snapshot完全一致；
  - 两个token为同一owner；
  - heartbeat不早于acquiredAt和snapshot heartbeat；
  - 操作时间不倒退；
  - 60秒临界已视为过期；
  - attemptCount/maxAttempts未变化且仍有额度。
- 验证成功后才进入共享 assembly/commit；不存在两个事务之间的TOCTOU窗口。

### 3.2 registry

- 新增有限结果 `ChapterContextAssembly`。
- registry注入context bound executor，并把原始snapshot与requestedAt原样传递。
- `registeredRoutes`严格为：
  - `FINAL_CHAPTER_COMMIT_V3`；
  - `CHAPTER_CONTEXT_ASSEMBLY_V1`。
- 九条remote route保持逐项`notRegistered`，没有`else`、phase fallback或通用Provider executor。

### 3.3 DeepSeek运行预算

- `scripts/start-deepseek-codex.ps1`允许的显式上限由30分钟提高到45分钟；默认仍为15分钟。
- 开发协议、任务包模板和运行手册同步规定：45分钟只用于已在30分钟正常推理超时的同一窄任务，不能扩大范围。

## 4. 测试证据

| 范围 | API/入口 | 结果 |
|---|---|---:|
| context定向 | API 35 | 9/9 |
| context定向 | API 30 | 9/9 |
| registry定向 | API 35 | 3/3 |
| registry定向 | API 30 | 3/3 |
| `core:database` JVM | 本地 | 86/86 |
| `feature:generation` JVM | 本地 | 131/131 |
| `core:database` Android全量 | API 35 | 218/218 |
| `core:database` Android全量 | API 30 | 218/218 |
| `feature:generation` Android全量 | API 35 | 42/42 |
| `feature:generation` Android全量 | API 30 | 42/42 |
| 统一离线门禁 | Debug/Release/Lint/R8 | 801 actionable tasks通过 |
| 安全门禁 | 源码+5 APK+备份排除 | 通过 |
| DeepSeek隔离参数 | 45分钟、无Token上限、max | ValidateOnly通过 |

曾尝试在同一Gradle命令中让database和generation同时连接同一模拟器，测试XML本身已分别为218/218和42/42，但Windows在采集logcat时产生文件占用并使命令非零退出。随后按模块拆开重跑，API30/API35四条全量命令均独立以0退出；报告不把第一次非零退出隐藏为成功。

## 5. 安全与边界

- App内真实Provider调用：0；
- Fake Provider调用：0；
- 物理设备写入：0；
- schema/migration/DAO变更：0；
- Attempt/Usage新增：0；
- Git remote：0；
- 项目始终绑定`D:\gptuser\projects\ai-novel-reader-app2`。

## 6. 未完成与下一步

TASK-064尚未完成。context成功后会激活`BUILD_CHAPTER_PLAN`，但该Stage还没有独立严格route identity与生产executor；initial draft、其余remote route、持久多阶段循环和完整Fake第一章也仍未完成。

下一阶段先处理chapter-plan，避免把context的执行权错误继承给后继Stage。chapter-plan收口后，再按已经确认的request前可得证据建立initial-draft冻结合同。
