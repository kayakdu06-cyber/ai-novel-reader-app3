# TASK-063 Phase 1：确定性 Fake Provider 核心夹具

## 任务身份

- 任务 ID：`TASK-063 / Phase 1 确定性 Fake Provider 核心夹具`
- 仓库根目录：`D:\gptuser\projects\ai-novel-reader-app2`
- 基准分支与 HEAD：`main` / `8ce7744`；HEAD 只是现场识别，不得回退。
- 当前未提交改动：工作树包含 TASK-040～062 的大量已验收 WIP；`feature:generation`、`core:diagnostics` 和数据库均有未提交改动，必须完整保留。
- 执行模型：DeepSeek（纯文本）

## 运行预算

- 推理等级：`max`。
- 最长运行时间：30 分钟。理由：用户明确允许放宽 DeepSeek 思考时长；本子任务边界窄，但需要同时处理协程取消、虚拟时钟、流契约和隐私。
- 累计 Token 上限：无。用户明确不设置 Token 上限；仍受 30 分钟硬超时、单任务锁和项目隔离约束。
- 预计读取文件数与明确清单：14 个，见“必读资料”。
- 预计执行命令/测试数：最多 12 个只读搜索/查看命令、最多 4 个 Gradle JVM 测试命令。
- 提前停止条件：需要触碰其他项目、密钥、真实 Provider、数据库 schema、total runner、watchdog 或现有 TASK-062 时序契约；同一构建问题重复两次；需要扩大允许文件范围。

## 目标

新增一个不进入 App Release 依赖图、可供 TASK-063/064/068 测试复用的 `provider:fake` JVM 模块。它应通过脚本和虚拟时间确定性地产生固定延迟、慢流、无终态断流、显式失败/未知结果和取消可观测行为，不等待真实几十秒或几分钟，并用独立 JVM 测试证明事件顺序、虚拟耗时、取消和脱敏边界。

本阶段只交付 Fake Provider 核心夹具，不把它接入正式数据库执行器，不声称 TEST-094/095 的整章正式提交门槛已通过。

## 当前现场与已有 WIP

- 已存在的实现：`ProviderAdapter`、`ProviderStreamEvent`、`ProviderEventGate`、`SensitiveProviderText` 和有限错误/恢复枚举已经定义；`AuditedStreamingProviderExecutor` 已能消费 Provider 流；TASK-062 已提供 `GenerationTimingClock` 和 BODY 时序接线。
- 已存在的测试：各真实协议 adapter 有局部 Fake；`AuditedStreamingProviderExecutorTest` 内有私有 `FakeAdapter`，但它用真实 `delay`、不可跨任务复用，也不能瞬时模拟 5 分钟。
- 已知失败或缺口：没有共享脚本 DSL、虚拟等待器、确定性调用统计、可控断流/未知结果/取消夹具；不能快速重复 20 次固定分布。
- 必须延续、不得从零重写的部分：`provider:common` 领域接口、事件单终态约束、TASK-062 时序契约和现有私有 Fake 测试。不要移动或重写现有执行器。

## 必读资料

1. `AGENTS.md`
2. `docs/24-AI-DEVELOPMENT-PROTOCOL.md`
3. `docs/ai/CURRENT-CONTEXT.md`
4. `docs/19-IMPLEMENTATION-BACKLOG.md` 的 TASK-063 行
5. `docs/25-RELIABILITY-AND-GENERATION-PERFORMANCE-ROADMAP.md` 的第 5、6、7、8、11 节
6. `docs/15-TEST-PLAN.md` 的 5.7 和性能门槛段落
7. `provider/common/src/main/kotlin/app/zhijuan/provider/common/ProviderAdapter.kt`
8. `provider/common/src/main/kotlin/app/zhijuan/provider/common/ProviderStreamEvent.kt`
9. `provider/common/src/main/kotlin/app/zhijuan/provider/common/GenerationRequest.kt`
10. `provider/common/src/main/kotlin/app/zhijuan/provider/common/ProviderValueTypes.kt`
11. `provider/common/src/main/kotlin/app/zhijuan/provider/common/ProviderProtocol.kt`
12. `provider/common/build.gradle.kts`
13. `feature/generation/src/main/kotlin/app/zhijuan/feature/generation/AuditedStreamingProviderExecutor.kt`
14. `feature/generation/src/androidTest/kotlin/app/zhijuan/feature/generation/AuditedStreamingProviderExecutorTest.kt` 中 `FakeAdapter` 及相关慢流/控制测试

除上述清单和代码直接引用外，不得递归扫描整套文档、历史会话、备份或无关模块；需要扩展读取范围时在回交中说明。

## 范围

允许修改：

- `settings.gradle.kts`：只允许新增 `:provider:fake` include。
- `provider/fake/build.gradle.kts`
- `provider/fake/src/main/kotlin/app/zhijuan/provider/fake/**`
- `provider/fake/src/test/kotlin/app/zhijuan/provider/fake/**`

明确不在范围：

- `app`、`feature:generation`、`core:*`、`provider:common` 和真实 Provider adapter 的任何修改。
- 数据库/schema/迁移、TASK-064 total runner、TASK-065 阅读投影、TASK-066 watchdog、20 章整链执行、UI、真实联网、真实等待 5 分钟、文档完成状态。

## 不可破坏的约束

- 项目隔离：不得访问或修改其他项目副本。
- 多模态：无多模态输入。
- 安全与隐私：夹具可以在内存中携带测试正文片段，但统计、异常消息、默认 `toString()`、调用记录和测试名称/断言不得暴露正文、prompt、endpoint、secret、原始远端 request ID 或 API Key 形态；统计只保留有限枚举、事件数、字符/token 数和虚拟毫秒。
- 状态机与幂等：单次脚本必须有且只有一个正常终止方式，或显式选择“无终态断流”；终态后事件必须在构造时拒绝。重复 `cancel` 必须有确定结果且调用计数明确。
- 协程与取消：虚拟等待必须至少让出调度权并响应协程取消；不得用 `Thread.sleep`、忙等或真实 5 分钟 `delay`。流被取消与 adapter `cancel()` 的观察要能分别验证。
- 联网与费用：不得联网，不调用 App 真实生成 API，不读密钥，不产生费用。
- 兼容性与构建基线：JVM 17、Kotlin warnings-as-errors、JUnit 5；模块不得成为 `app` 或正式 feature 的 `implementation` 依赖。
- 需要保留的用户/未提交改动：除四个允许路径外不改任何文件；不得 reset、clean、checkout、commit 或添加 remote。

## 实施要求

1. 提供小而明确的脚本模型，至少表达：等待虚拟毫秒、Started、Text/Structured delta、Usage、Heartbeat、Completed、Failed、Refused、无终态断流。避免通过任意 lambda 暴露不可审计行为。
2. 提供可注入的等待/时钟抽象，以及线程安全的测试虚拟实现。虚拟等待推进单调毫秒且 `yield`，负数、溢出、回退和非单调脚本在执行前失败。
3. `ProviderAdapter` 实现必须检查 profile protocol、request 基本身份并记录脱敏调用统计；测试能力、列表和连接方法可返回固定本地结果或明确不支持，但不得联网。
4. 支持显式 UNKNOWN/STREAM_INTERRUPTED 等有限失败，以及脚本自然结束但无 Provider 终态的场景；不要在 adapter 内替执行器偷偷补终态。
5. 取消必须可观测：记录 flow collection 被协程取消，以及 `cancel(profile, requestId)` 调用；两者都不能记录敏感参数。并发读统计必须安全。
6. 新增 JVM 测试覆盖：固定延迟事件顺序与总虚拟耗时；慢流在毫秒级墙上测试时间内模拟至少 5 分钟虚拟时间；无终态断流；显式未知结果；协程取消/重复 cancel；非法脚本；统计和默认字符串 canary 脱敏。
7. 保持 API 足够简单，使 Sol 后续能把同一虚拟时钟适配给 `GenerationTimingClock`，把同一 adapter 传给 `AuditedStreamingProviderExecutor`，并构造 20 次确定性分布。

## 验收标准

- [ ] `:provider:fake:test` 离线通过，测试不使用真实网络和真实长等待。
- [ ] 固定脚本的事件顺序、累计虚拟毫秒和调用次数完全确定。
- [ ] 5 分钟慢流测试的实际墙上时间不依赖 5 分钟。
- [ ] 断流不被 adapter 偷偷改成成功或补终态；UNKNOWN 保留明确 request state。
- [ ] 取消和重复 cancel 行为可验证且线程安全。
- [ ] 默认字符串/统计/异常不含 canary 正文、prompt、endpoint、secret 或原始 request ID。
- [ ] `app` 与 `feature:generation` 没有新增正式 implementation 依赖。

## 验证命令

```powershell
$root=(git rev-parse --show-toplevel).Trim()
if ($root -cne 'D:/gptuser/projects/ai-novel-reader-app2') { throw "Unexpected git root: $root" }
$env:GRADLE_USER_HOME='D:\gptuser\cache\gradle'
.\gradlew.bat :provider:fake:test --offline --no-daemon --console=plain
```

```powershell
git diff --check
```

不运行模拟器和统一门禁。未运行的验证必须在回交中写明原因，不能写成通过。

## 回交格式

请严格按以下标题返回：

1. `完成内容`
2. `修改文件`
3. `验证`
4. `未完成/风险`
5. `需要 Sol 处理`
6. `假设`

不要宣布整个 TASK-063 完成，不要更新正式完成状态；由 Sol 根据差异和后续执行器集成证据确认。
