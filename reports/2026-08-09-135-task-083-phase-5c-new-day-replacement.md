# 工作汇报 135：TASK-083 Phase 5C 新日替代请求准备

日期：2026-08-09  
项目：织卷 Android App  
结论：Phase 5C 的 repository 级新日替代请求准备已完成并通过双 API 与完整离线门禁；TASK-083 仍未关闭。

## 1. 本阶段解决的问题

Phase 5B 只能在 Provider-open 前发现跨日，并可靠结束旧日未发送请求、释放旧日 reservation、把 Job/Stage 放回持久队列。它不会创建新请求。

本阶段补齐重新领取之后的安全准备边界：

- 持久 runner 重新 claim Job，并领取当前 Stage 的精确 lease。
- 重新验证 Phase 5B 父 Attempt、Usage、旧 reservation 和当前 Job/Stage 双租约。
- 创建新的 Attempt、Usage、新日 reservation 和新的受保护草稿工件。
- 旧草稿非空时复制续写种子；为空时仍创建独立新工件。
- 任何预算、状态或证据拒绝都不留下半请求，并清理本次新建工件。
- 全过程不打开 Provider，也不调用织卷 App 内部真实生成 API。

## 2. 已完成实现

- `PersistentBudgetReservationRepository` 新增专用 daily-rollover replacement 入口，并复用既有原子 reservation 核心，未复制平行预算算法。
- 专用事务要求最新父 Attempt 精确为未发送的 `FAILED_RETRYABLE/DAILY_BUDGET_PERIOD_EXPIRED_BEFORE_SEND`；Usage 必须 UNKNOWN/FINAL 且无用量，旧 reservation 必须 `RELEASED/accounted=0`，完成、封账、释放和更新时间必须一致。
- 调用方必须提交真实 `GenerationRunnerExecutionLeaseSnapshot`。事务内重新核对 Job=`RUNNING`、Stage=`PREPARING`、current Stage cursor、同 owner 的 Job+Stage 精确 token、当前 heartbeat 和未过期 lease；只传父 ID 或单 Stage token 不构成授权。
- 新 Attempt 使用唯一 ID、`attemptNo=parent+1`、`retryParentAttemptId=parent`；新 Usage、reservation 和 artifact 也必须唯一。请求快照、input hash、连接、request limit、estimate、币种与 estimate source 都必须沿用父请求。
- 当前 disclosure 可以有更晚的接受时间，但 connection、canonical destination、protocol、disclosure version 和 binding hash 必须与父 reservation 一致。
- 新 createdAt 必须晚于父结束时间，当前 DAILY policy 必须派生不同日键；新的 reservation 重新进入单书累计，只进入新日 DAILY 累计。
- 普通 `prepareBeforeSend` 发现最新 Attempt 是换日终态时直接失败，防止绕过专用父证据、双租约和种子复制。
- `GenerationStreamingDraftRepository` 在单进程生命周期锁内有界读取旧加密草稿，并创建不同的新受保护工件。ByteArray 在使用后清零，不经过明文临时文件。
- 数据库拒绝时删除新工件并保留旧工件；数据库提交前崩溃最多留下加密 orphan，由既有 retention/cleanup 回收；提交后新工件已被新 Attempt 唯一引用。
- 本阶段不改 schema、migration、trigger 或总 runner registry。

## 3. DeepSeek 与 Sol 分工

- DeepSeek 只读设计审计：`20260809-155744-bbeaf68d`。
- 配置：DeepSeek V4 Flash、最高推理强度、无总 Token 上限、30 分钟安全护栏。
- 结果：约 16 分 12 秒正常完成，退出码 0；总 Token 4,180,592，其中缓存输入 3,687,808、输出 124,640、推理 84,053；0 文件写入、0 权限请求。
- Sol 采纳“新工件先创建、数据库失败清理”“父 Attempt/Usage/reservation 在同一事务重验”“不新增 schema”的方向。
- Sol 进一步加固：调用方不能自造纯 ID evidence，必须提供真实双租约快照；普通 prepare 必须阻断换日父 Attempt；当前 disclosure 必须保持同一目的地 binding；新请求时间必须晚于父请求结束时间。

## 4. 测试发现与修正

- 一次大测试补丁输出被工具截断，但文件实际写入了五个测试；辅助函数和常量未落盘。Sol先检查真实文件，再补齐缺失部分，没有重复覆盖现有 WIP。
- 首次 AndroidTest 编译发现测试文件缺少 `leaseTokenOrNull` 扩展导入；补充导入后通过，生产代码未放松。
- 错误 Job token 负例初稿误用了真实 token 的时间值，已改成明确不同的 acquiredAt，确保测试真的验证陈旧 token。
- Kotlin 的 `Result<*>::isSuccess` 方法引用写法改为显式 lambda，避免编译歧义。

## 5. 验证结果

- 定向数据库：`PersistentBudgetReservationDatabaseTest` 在 API 30、API 35 各 40/40。
- 定向生成：`AuditedStreamingProviderExecutorTest` 在 API 30、API 35 各 21/21。
- 数据库全量：API 30、API 35 各 269/269，0 失败、0 跳过。
- 生成模块全量：API 30、API 35 各 46/46，0 失败、0 跳过。
- 统一 JVM：592/592，0 失败、0 错误、0 跳过。
- `scripts/verify-build.ps1 -Offline`：801 actionable tasks；Debug、Release、Lint/Vital、R8、Release APK、扫描器自测、源码与 5 个构建产物安全扫描、备份排除全部通过。
- `git diff --check` 通过；Git remote 为空；真实 Provider 调用 0；物理设备写入 0；未修改网络、DNS、代理或防火墙。

## 6. 关键行为证据

- 非空旧草稿复制到新受保护工件，旧/新解密内容逐字节相同，但引用不同；旧 descriptor 和内容不变。
- 空旧草稿仍生成独立新工件，禁止因为 0 字节复用旧引用。
- 普通 prepare 旁路失败后，本次临时新工件已删除，数据库无新 Attempt/Usage/reservation。
- 父请求快照变化导致专用 prepare 失败时，只删除新工件，旧工件保持。
- 新日 DAILY quota 不足时，candidate、Attempt、Usage 全回滚；Stage 保持 `PREPARING` 和当前 lease，旧 Phase 5B release 不被撤销。
- 两个并发专用 replacement 最多一个成功，最终只有一个新 Attempt 和一个新的 `RESERVED` reservation。
- 所有新增集成场景中 adapter 调用计数均为 0。

## 7. 尚未完成

TASK-083 下一阶段仍需完成实际 Provider-open 目的地匹配：执行器收到的 `ProviderConnectionProfile` 与 adapter protocol 必须精确匹配 reservation 冻结的 connection、canonical destination 和 protocol，不能“确认连接 A，却发送连接 B”。

Phase 5C 只交付未来 total runner 可调用的专用 repository 能力，没有把该路线注册进当前尚不存在的完整 total runner。`CHAPTER_PLAN_V1` 仍未注册，不能把本阶段描述为整 App 已能自动生成小说。
