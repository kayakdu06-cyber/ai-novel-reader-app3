# 工作汇报 137：当前系统检查点与后续工作计划

日期：2026-08-09  
项目：织卷 Android App  
唯一项目根目录：`D:\gptuser\projects\ai-novel-reader-app2`  
基准 HEAD：`8ce774429da1c3f7139a221bc241c34d81a2efdd`  
Git remote：0  
用途：上下文丢失或换任务后，直接依据本文件恢复当前开发现场。

## 1. 产品目标不变

织卷是单人、本地优先的 Android AI 小说生成与阅读 App：

- 用户只提供少量人物资料，选择题材、文风、隐蔽命名的内容尺度和篇幅；
- 短篇至少 80 章，中篇至少 300 章，长篇由用户自定义；
- 生成过程尽量自动，用户少操作；
- 已完成章节立即可读，后续章节继续生成；
- 阅读体验以多看的沉浸排版为核心，书架信息组织参考起点；
- 不做账号、会员、支付、社区或后台；连接、密钥、小说、模板和设置保存在本地；
- 支持主流模型 API 与 OpenAI-compatible 中转站，但每次真实发送必须满足隐私目的地确认、预算和审计门禁；
- 模板必须保留来源、归类、版本和复制关系，允许对不满意的书快速复用旧模板重新生书。

## 2. 当前可靠能力

### 本地产品与数据基础

- 首次启动说明、连接向导、持久连接列表、创建页和开始前确认占位页已经存在。
- SQLCipher 主库、迁移链、备份排除、安全扫描与 Release/R8 门禁已经建立。
- 书籍冻结快照、Prompt Bundle、成人事实门、最小 80/300 章规则已有测试。

### 小说生成底层原语

- 故事种子、故事圣经、总纲、分卷/8章窗口、第一章快车道、第二章硬闸门已有本地合同与 Fake 流证据。
- 章前上下文预算、正文流式草稿、有限续写、记忆提取、时间线/伏笔、一致性检查、有限修订、最终候选原子提交已经具备底层实现。
- 用户改章后的跨章派生失效、顺序重建、旧投影退役和 FTS 更新已经完成。
- 确定性 Fake Provider 与脱敏生成时序已完成，可在不产生真实费用的情况下验收流程和速度。

### runner 与发送安全基础

- 持久 Job/Stage、双 lease、heartbeat、过期恢复、route identity 和 exact-token executor 基础已完成。
- final 与 context 两类本地 executor 已进入最小 registry。
- TASK-083 已完成三层预算、RequestIntent、Usage、跨日替代和实际目的地匹配；错误目的地在任何 Provider/工件打开前失败且持久状态零写入。

## 3. 当前真实缺口

当前 APK 仍不是可以完整使用的成品，核心原因不是页面数量，而是 total runner 尚未把已有底层原语串成自动循环：

1. `CHAPTER_PLAN_V1` 尚未注册；
2. chapter-plan 尚缺 exact-token 远程执行、严格解析和原子提交；
3. plan 成功后尚未原子创建第一条正文 `DRAFT` Stage；
4. 其余 remote route 尚未逐类接入统一 runner；
5. 尚未形成“创建书 → 自动规划 → 生成首章 → 立即阅读 → 后台继续”的 Fake-only 闭环；
6. 生成状态投影、阅读页和模板复制 UI 尚未接到真实 runner 状态；
7. watchdog、恢复提示、费用预估和 20 章稳定性验收仍待完成；
8. 真实 API 验收属于后期独立任务，当前开发继续保持真实 Provider 调用 0。

## 4. 代码量检查点

按 2026-08-09 当前工作树统计：

| 类别 | 文件数 | 行数 |
|---|---:|---:|
| 主 Kotlin/KTS（排除 test/androidTest/debug） | 264 | 60,673 |
| 测试 Kotlin/KTS | 155 | 43,831 |
| `docs/` 与 `reports/` Markdown（创建本报告前） | 235 | 20,696 |

当前 `git status --short` 有 270 条记录。它们是此前持续开发形成的已知 WIP，不得 reset、clean 或从原项目覆盖。仓库没有 remote。

## 5. 后续开发顺序

### A. TASK-064：chapter-plan exact-token 执行

目标：把已冻结的 `CHAPTER_PLAN_V1` route、输出合同、目的地门和预算门接成一次可审计远程执行。

必须包含：

- 精确 Job/Stage lease 与 route token；
- RequestIntent + reservation；
- 实际 profile/adapter/destination 证据；
- 受保护响应工件；
- 严格 `ChapterPlanStructuredOutput` 解析；
- UNKNOWN、截断、非法结构和 replay 的有限处理；
- 真实 Provider 仍为 0，只用 Fake 验证。

### B. TASK-064：plan 原子提交与 initial DRAFT

目标：严格输出通过后，在一个本地原子边界中提交 chapter plan，并创建 runner 下一步需要的第一条正文 DRAFT Stage。

关键门禁：

- 输入来源版本、output artifact、Usage FINAL 和当前 Stage 必须精确绑定；
- 重放不能重复创建计划或 DRAFT；
- 提交失败不能留下“计划已写但正文 Stage 不存在”的半状态；
- 遵守 DEC-068。

### C. TASK-064：plan registry 与持久恢复

目标：正式注册 `CHAPTER_PLAN_V1`，让队列能够从进程终止、lease 过期、UNKNOWN 和本地提交中断后继续。

### D. TASK-064：其余 remote route 与 Fake-only total runner

按最小可证明顺序接入正文、续接、记忆、追踪、一致性、修订和最终提交，先完成一章闭环，再扩展到多章；不做一次性大拼装。

### E. TASK-065：边生成边阅读的状态投影

把 Job/Stage、章节 current version、草稿可见性和错误/等待动作投影成稳定的书架、目录和阅读页状态。正文正式提交后立即可读，生成进度不能阻塞阅读。

### F. TASK-066：watchdog 与恢复

覆盖进程死亡、设备重启、网络变化、FGS 超时、UNKNOWN、草稿损坏、预算不足和需要用户处理的有限状态。

### G. TASK-067：性能与模型角色路由

以“不能十分钟才出一章”为产品硬约束，测量首段时间、整章时间、各阶段耗时和 Provider 角色分配。优先减少串行阶段与不必要远程调用，不以牺牲一致性和可恢复性换速度。

### H. TASK-084/085：费用预估与恢复体验

在 20 章长跑前补齐面向用户的预计费用、额度不足、失败恢复和继续生成入口，避免后台系统正确但用户无法理解或恢复。

### I. TASK-068：20 章 Fake-only 稳定性验收

验证长时间运行、暂停/恢复、重启、跨日、编辑后重建、边生成边阅读、费用账本和无重复章节。通过后再进入真实 API 冒烟。

### J. TASK-069 及后续：真实 API、阅读 UI 与模板复制产品化

- 真实 API 只做受控小额冒烟，不能直接跑长篇；
- 阅读器完成暖纸色正文、夜间模式、目录抽屉、字号/字体/背景/翻页设置；
- 模板库完成来源、分类、版本、复制链和“一键重新生书”；
- 最终完成安装包、迁移、备份恢复、性能和发布验收。

## 6. 下一项精确工作

下一项不是继续扩数据库，也不是做 UI，而是：

> TASK-064 Phase 2E5A：审计并实现 `CHAPTER_PLAN_V1` 的 exact-token 远程执行最小闭环，先做到 Fake Provider 响应进入受保护工件并被严格解析；本阶段先不注册 total runner，也不把多类 route 一次接完。

该阶段由 Sol 直接承担核心架构和原子边界；只有出现边界清晰、可独立审查的局部测试或静态审计时，才选择性调用 DeepSeek。

## 7. 上下文恢复清单

任何后续 Codex 任务开始时按以下顺序恢复：

1. 确认 `git rev-parse --show-toplevel` 精确等于 `D:/gptuser/projects/ai-novel-reader-app2`；
2. 完整读取 `D:\gptuser\AGENTS.md` 与仓库根 `AGENTS.md`；
3. 完整读取 `docs/24-AI-DEVELOPMENT-PROTOCOL.md`；
4. 完整读取 `docs/ai/CURRENT-CONTEXT.md`，以最后一节为最新现场；
5. 读取 `docs/19-IMPLEMENTATION-BACKLOG.md` 中 TASK-064、TASK-083；
6. 读取本报告与工作汇报 136；
7. 读取 `docs/06-AI-GENERATION-SYSTEM.md`、`docs/07-API-ADAPTER-SPEC.md`、`docs/09-DATA-MODEL.md`、`docs/10-STATE-MACHINES.md`、`docs/14-COST-CONTROL.md`、`docs/15-TEST-PLAN.md` 的 chapter-plan/TASK-083 相关段落；
8. 检查当前未提交差异与现有 WIP，禁止从零重写、reset、clean、同步原项目或添加 remote；
9. 所有缓存、构建与临时产出留在 `D:\gptuser`，Gradle 使用 `D:\gptuser\cache\gradle`；
10. 只在 API30/API35 模拟器运行 Android 验证，不写入物理设备，不调用 App 真实 Provider。

## 8. 可靠性判断

当前工程的主要风险已经从“底层数据会不会乱、费用会不会重复扣、请求会不会发错目的地”转移到“能否把已经可靠的底层原语正确串成自动生成闭环”。后续应继续采用小阶段、双 API、失败路径先行的方式推进；不应为了看起来更快而一次性接通所有 route，也不应继续无限扩充底层能力却迟迟不形成首章闭环。
