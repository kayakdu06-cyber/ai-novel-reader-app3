# TASK-130 纯文本任务包：普通用户生成启动入口

## 任务身份

- 任务 ID：`TASK-130`
- 仓库：`D:\gptuser\projects\ai-novel-reader-app3`
- 基准：`main` / `070ffb3`
- 执行者：Sol；跨模块状态机由 Sol 决策，不调用 DeepSeek。
- 用户已有未提交文件：`AGENTS.md`、`.claude/`、`CLAUDE.md`，禁止修改或纳入提交。

## 目标

用户在开始前确认页点击一次后，App 从不可变创建快照创建唯一持久生成入口，失败关闭地复核连接、模型、数据目的地、预算确认和快照身份，启动既有前台执行入口，并导航到最小生成中书籍页。重组、双击和进程重建不得创建第二个入口 Job。

## 审计裁决

新书此时只有创建快照，没有故事种子、故事圣经、全书总纲和首个 1～8 章窗口。TASK-128 的唯一 total runner 只注册章级五条 route；直接创建章级 Job 会因缺少权威来源必然失败。

因此普通入口的第一个 Job 必须是 `CREATE_BOOK` 初始规划 Job，而不是伪造已具备前置数据的章级 Job。初始规划与首窗必须由同一持久执行体系完成后，才可准备第一章。该缺口会直接阻断 App，属于稳定性和可用性所需的最小整改，不是主动扩展。

## 模块批次

1. `:core`：新增窄 `GenerationStarter` 合同、不可变请求/结果和用户可映射失败码；不改高影响 `GenerationController`。
2. `:data`（阻断修复）：只恢复/补齐初始规划与首窗的冻结 Job 工厂、幂等查找和所需原子状态入口；不改 schema。
3. `:feature:generation`：实现启动校验、唯一 Job 创建、有限初始规划/首窗 route，并复用既有 total runner；不创建第二 runner。
4. `:feature:creation`：确认请求携带冻结连接、模型、canonical origin、未知价格和明确 token 上限确认；不直接依赖 generation 实现。
5. `:app`：只注入合同、转交事件、启动前台服务和导航；不写业务校验。

十模块不变；不新增 feature 实现依赖。`:provider`、library、reader、template 和数据库 schema 禁止修改。

## 不可破坏约束

- 同一 `bookId + snapshotId + contentHash` 只能得到同一入口 Job；并发点击也不能生成两个 Job。
- 快照、创建时冻结的连接/模型、当前连接/模型、目的地确认任一变化时失败关闭。
- 价格未知必须明示；没有用户确认 token 上限时不得创建可执行 Job。
- Job 创建不打开 Provider；只有前台服务中的既有审计 runner 能打开。
- 不伪造故事种子、圣经、总纲、窗口、章节、预算或价格。
- 初始规划 route 只能使用已有严格 schema、输出校验、受保护 artifact、用量台账和原子提交。
- 第一章只能在初始规划与首窗正式提交后准备；后续 3～5 章继续复用 TASK-129 有界循环。
- App 错误只显示可操作中文，不显示异常、Job ID、连接秘密、Prompt 或正文。
- 本任务真实 Provider 调用为 0；真实 DeepSeek 联调属于 TASK-132。

## 最小验收

- `:core` 合同测试：输入约束、稳定幂等键、结果不泄漏。
- generation JVM：相同确认 replay、双击/并发唯一、快照/连接/模型/目的地/预算变化失败关闭。
- 一个 Room Android 交接：冻结创建快照 → 唯一入口 Job，重建后回读同一 Job。
- creation Compose：显示连接、模型、canonical origin、目标章数、未知价格、token 上限；提交一次。
- app Compose：确认成功进入生成中页；失败留在确认页并显示中文下一步。
- 十模块边界检查；只在形成可导航切片后运行一次 `assembleDebug`。

## 停止条件

- 若需要数据库 migration、修改 Provider 协议或新增第十一个模块，停止扩大并记录独立证据。
- 若初始规划执行必须改写现有章级 runner，停止；只能新增有限 route/executor 并由同一 registry/runner 调度。

