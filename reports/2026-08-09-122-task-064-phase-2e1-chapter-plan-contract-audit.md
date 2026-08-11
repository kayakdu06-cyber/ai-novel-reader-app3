# 工作汇报 122：TASK-064 Phase 2E1 普通章节计划生产合同审计

> 日期：2026-08-09  
> 范围：只读审计与架构裁决；未改生产代码、未构建、未测试、未调用 App 内 Provider  
> 项目：`D:\gptuser\projects\ai-novel-reader-app2`

## 1. 阶段结论

context 成功后虽然会激活普通 `BUILD_CHAPTER_PLAN` Stage，但当前生产链只能停在这里并失败关闭。它缺少独立 route identity、`chapter-plan.v1` 严格输出合同、exact-token 远程执行器、原子提交和 initial DRAFT successor，因此不能直接加入 registry。

本次确认的最严重持久化漏洞是：不能只把章内计划留在 `STREAM_DRAFT` 受保护 artifact 中。成功 Stage 的这类 artifact 默认 24 小时后即可被清理；如果用户隔天再继续写，初稿会失去计划来源。

Sol 已作出 DEC-068：章节计划提交时解析并规范化有界 `chapter-plan.v1`，在同一 SQLCipher 事务中动态创建 initial DRAFT Stage，并把规范计划、plan Stage/Attempt、raw/canonical hash 及 context/progression 证据冻结到 DRAFT 的不可变 `inputSourcesJson`。plan Stage 的输出引用只保存核对所需 identity/hash 和 DRAFT Stage ID；成功 artifact 可按既有策略清理。无需新增表、schema migration 或误用窗口级 `OutlineRevision`。

## 2. DeepSeek 审计记录

任务包：`docs/ai/task-packets/TASK-064-PHASE-2E1-CHAPTER-PLAN-PRODUCTION-CONTRACT-AUDIT.md`。

| 运行 | 结果 | 时长与用量 | 工作树 |
|---|---|---|---|
| `20260809-041913-9c111e91` | 网络中断，五次连接 `api.deepseek.com/responses` 重试后退出 1；无 final | 约 8 分 56 秒；总 1,382,784，缓存输入 1,017,984，输出 25,539，推理 13,160 | 无改动 |
| `20260809-054854-76d0c42d` | 网络恢复后正常完成只读审计，退出 0 | 约 9 分 54 秒；总 3,132,957，缓存输入 2,745,344，输出 51,542，推理 28,392 | 默认 status 220 条，SHA-256 仍为 `aea947bd69aade7bd206865966297c04f38390d2f010c5e27e61cbd89486f94f` |

第二次运行的一个文件名猜测错误通过符号搜索自行收敛，未影响结论。回交把 `git diff --name-only` 和 220 条 status 描述为一一对应并不严谨：未跟踪目录在默认 status 中会合并。Sol 复核确认默认 status 是 220 条，使用 `-uall` 展开后是 279 条；这只是计数口径不同，不是文件新增或丢失。

## 3. 缺口矩阵

| 环节 | 当前状态 | 严重风险 |
|---|---|---|
| plan 冻结输入 | 已有 context Stage/input/policy/manifest、progression gate、bundle 与 `chapter-plan.v1`，缺 `sourcePolicyVersion` | runner 无法可靠识别，当前按设计失败关闭 |
| Provider-open | context/progression 权威重算已有通用门禁 | 旧入口没有绑定 Phase 2B exact Job+Stage 双 token |
| RequestIntent/Attempt/Usage | 只有通用原语 | 缺 plan 专用输入指纹、目的地和三层预算原子预留，提前联网可能重复付费 |
| 输出合同 | 不存在严格 `chapter-plan.v1` schema/parser/业务交叉校验 | 错 schema 或越界场景计划可能被误接收 |
| 提交 | 无 plan commit repository | 无法原子结算 Usage、Stage、cursor 与后继 |
| initial DRAFT | 无生产 factory/source contract | 不能在计划成功后安全、可恢复地开始正文 |
| 恢复/replay | 只有 UNKNOWN、artifact、租约等通用原语 | 无 plan 专用成功重放和防重复发送闭环 |

普通 `chapter-plan.v1` 不得与首章快车道 `first-chapter-bootstrap.v1` 或窗口规划 `arc-plan.v1` 混用。三者虽可复用通用 Attempt、artifact、严格校验和事务模式，但身份、业务校验和提交目标不同。

## 4. Sol 对持久模型的修正

DeepSeek 倾向“受保护 artifact + Stage output reference”。该方向只适合作为提交时证据，不足以单独承担下一阶段的长期输入：

- 当前远程结果统一使用 `ProtectedArtifactType.STREAM_DRAFT`；
- `GenerationStreamingDraftRepository.cleanupExpired` 默认允许在成功 Stage 提交 24 小时后清理；
- 用户可能在计划完成数天后才继续初稿，不能要求 artifact 永远存在；
- `OutlineRevision` 的 CHAPTER 节点是窗口级粗粒度规划，不是单章内部场景执行合同；
- 新建专用表会引入 migration、历史清理和另一套权威身份，目前没有必要。

因此采用“artifact 负责提交时证据，initial DRAFT 冻结来源负责长期执行”的组合：

1. plan commit 从已校验 artifact 读取并规范化有界计划；
2. 同一事务内动态插入 initial DRAFT Stage；
3. DRAFT `inputSourcesJson` 冻结规范计划、上下游 identity 和 hash；
4. plan `outputReferenceJson` 保存 Attempt、raw/canonical hash 和 DRAFT Stage ID；
5. DRAFT 外层继续受既有 64 KiB 限制，规范计划内层目标上限不超过 48 KiB，为 envelope 留出余量；
6. artifact 之后可以清理，DRAFT 仍能精确恢复和重放。

## 5. 后续实施切片

1. Phase 2E2：增加 `zhijuan.chapter-plan-source.v1`、严格 plan 输入 parser 和 `CHAPTER_PLAN_V1` route；不注册、不联网。
2. Phase 2E3：实现严格、有界的 `chapter-plan.v1` schema/parser/业务交叉校验，明确 scene 数量、字段、长度和章节目标约束。
3. Phase 2E4：补 exact-token RequestIntent/Provider 执行。任何真实发送前必须先把目的地确认与 TASK-083/084 三层预算原子预留接入；Fake 也不得绕过同一审计边界。
4. Phase 2E5：原子提交、动态 initial DRAFT、durable replay、UNKNOWN 与防重复发送；全部证据齐全后才扩大 registry。

Phase 2E2 是下一项最小实现。TASK-064 整体仍未完成。

## 6. 验证边界

- 本审计没有运行 Gradle、模拟器或统一 Release/R8 门禁；上一阶段基线仍为数据库 JVM 86/86、生成 JVM 131/131，API 30/API 35 数据库各 218/218、生成各 42/42，以及 801-task 离线门禁。
- 本阶段 App 内真实/Fake Provider 调用 0、物理设备写入 0、Git remote 为空。
- 审计前后默认 Git status 保持 220 条和相同 SHA-256；没有文件丢失。
