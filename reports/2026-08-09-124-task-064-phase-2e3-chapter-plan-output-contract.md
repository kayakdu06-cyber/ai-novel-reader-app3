# 工作汇报 124：TASK-064 Phase 2E3 chapter-plan 严格输出合同

> 日期：2026-08-09  
> 项目：织卷 Android App  
> 唯一仓库：`D:\gptuser\projects\ai-novel-reader-app2`  
> HEAD：`8ce774429da1c3f7139a221bc241c34d81a2efdd`  
> 结论：Phase 2E3 完成；TASK-064 继续进行中

## 1. 本阶段结果

普通 `BUILD_CHAPTER_PLAN` 已拥有独立的 `chapter-plan.v1` 输出 schema、严格 parser、稳定规范化和动态业务交叉校验。它解决的是“模型交回什么才算一份可执行、可冻结、可继续校验的单章计划”，仍不负责联网发送或数据库提交。

当前 `CHAPTER_PLAN_V1` 继续在 runner registry 中显式未注册。本阶段没有创建 RequestIntent、Attempt、Usage、artifact 或数据库行，没有调用真实/Fake Provider，也没有把输出 parser 成功冒充为生成链已接通。

## 2. 新增实现

### 2.1 有界结构合同

新增 `ChapterPlanOutputContractV1`：

- schema ID 固定为 `chapter-plan.v1`，版本固定为 1；
- UTF-8 输出硬上限 48 KiB；
- 1～12 个有序场景；
- 全章最多 64 个关键过程节点；
- exact root/scene/process keys，未知字段、重复 key、错误类型和乱序失败关闭；
- 递归限制深度、节点数、object member、array item、字符串和数字长度；
- Provider JSON schema 与本地严格 reader 双重约束。

### 2.2 可执行的场景计划

章级计划保存：章节/context 双 hash、章首状态、章目标、章末状态、尾钩子和连续性约束。

每个场景保存：目的、地点、视角人物、参与人物、开场/转折/收束状态、连续性承接、相关性、过程节点和剧情余波。

严格相关场景的每个过程节点必须分别保存：

1. 动作；
2. 可观察反应；
3. 空间状态；
4. 身体状态；
5. 衣着与物品状态；
6. 视角可达的感官变化。

严格相关场景至少需要 3 个有序节点，防止模型用一个含糊节点或事后概述冒充完整过程。该规则要求可执行的起始推进、状态变化和结果承接，但不以粗俗词汇数量或机械轮播所有感官判断详细度。

### 2.3 动态业务门禁

新增 `ChapterPlanExpectationV1` 与 `ChapterPlanBusinessValidatorV1`：

- 精确核对 chapter ID/index 与 ContextSnapshot 内容/manifest hash；
- 所有人物引用必须来自请求前已知人物集合；
- POV 必须属于场景参与人物；
- `Blocked` 场景契约不能构造 plan expectation，禁止静默降档；
- `NotApplicable` 禁止模型自行增加相关场景或严格过程节点；
- `Allowed` 必须至少存在一个相关场景；
- 相关场景所有参与人物必须属于“已确认成年且虚构”集合；
- 严格模式必须有过程节点和余波；比例模式禁止伪造严格过程节点证明。

### 2.4 稳定规范化与脱敏

- 合法 JSON 的 object key 递归排序，scene/process array 顺序保持业务语义；
- 同一语义对象只改变字段顺序时，canonical JSON 与 SHA-256 不变；
- 含计划文本、人物集合或 hash 的对象均覆盖脱敏 `toString`；
- Invalid 结果只输出有限 issue code 和安全 reference，不输出计划正文。

## 3. 修改文件

### 代码与测试

- `feature/generation/src/main/kotlin/app/zhijuan/feature/generation/ChapterPlanStructuredOutput.kt`
- `feature/generation/src/test/kotlin/app/zhijuan/feature/generation/ChapterPlanStructuredOutputTest.kt`

### 正式文档

- `docs/06-AI-GENERATION-SYSTEM.md`
- `docs/08-TECHNICAL-ARCHITECTURE.md`
- `docs/10-STATE-MACHINES.md`
- `docs/15-TEST-PLAN.md`
- `docs/18-DECISION-LOG.md`（新增 DEC-070）
- `docs/19-IMPLEMENTATION-BACKLOG.md`
- `docs/20-TRACEABILITY-MATRIX.md`
- `docs/22-WORK-STATUS.md`
- `docs/25-RELIABILITY-AND-GENERATION-PERFORMANCE-ROADMAP.md`
- `docs/ai/CURRENT-CONTEXT.md`

## 4. 验证证据

| 验证 | 结果 |
|---|---|
| 新增定向 JVM | 9/9 |
| `feature:generation` JVM 全量 | 140/140，0失败、0错误、0跳过 |
| API 30 `feature:generation` Android 全量 | 42/42，0失败、0跳过 |
| API 35 `feature:generation` Android 全量 | 42/42，0失败、0跳过 |
| `scripts/verify-build.ps1 -Offline` | 通过，801 actionable tasks |
| Debug / Release | 通过 |
| Lint / Vital / R8 | 通过 |
| 安全扫描器自测 | 通过 |
| 源码与 5 个 APK 安全扫描 | 通过 |
| Android 备份排除 | 通过 |
| `git diff --check` | 通过，仅有既存换行提示 |

覆盖的关键负例包括：重复 JSON key、未知字段、乱序、48 KiB 超限、章节身份漂移、未知人物、POV 不在场、相关场景规避、未确认成年人/虚构身份、严格节点不足、余波缺失、比例模式伪造严格节点和 Blocked 静默降档。

## 5. DeepSeek 与网络状态

用户报告断网后，本阶段没有启动 DeepSeek。原因是 DeepSeek 编码模型需要联网，而当前工作可以在既定架构与本地缓存内完整实现和验证。没有出现超时、权限请求或不完整 DeepSeek WIP；实现、审查和测试均由 Sol 完成。

断网没有影响 Gradle 离线构建、两台项目专用模拟器或本地安全扫描。

## 6. 明确未完成

Phase 2E3 不等于普通 chapter-plan 已可运行。下列工作仍未完成：

- 请求的数据目的地确认与持久绑定；
- TASK-083/084 三层预算的原子预留与结算接线；
- exact Job+Stage token 的远程执行器；
- RequestIntent、Attempt、Usage、artifact、UNKNOWN 和一次格式修复的 plan 专用编排；
- DEC-068 要求的 plan commit 与 initial DRAFT 原子 successor；
- 把 `CHAPTER_PLAN_V1` 加入 registry；
- 完整 Fake 第一章和后续多阶段循环。

下一阶段为 Phase 2E4：先审计并补齐 plan 请求的目的地、三层预算和不可变请求绑定；这些门禁完成前继续保持 route 未注册、真实生成 API 0 次。
