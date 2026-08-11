# 工作汇报 129：TASK-083 Phase 3A 原子预算预留核心

## 1. 阶段结论

TASK-083 Phase 3A 已完成并通过 API 30/API 35 双版本全量数据库回归。现在内部预算入口能在一次 Room 事务中完成“候选预留先写入、三层额度后聚合、RequestIntent 最后提交”；任何额度拒绝或 Attempt 写入失败都会整笔回滚，不留下 reservation、Attempt、Usage 或 Stage 半成品。

本阶段还用同一 Room 实例并发、两个 Room 实例指向同一 WAL 文件并发以及关闭重开后的再次申请，证明同一份书级额度不会被两个请求重复花费。公开 `GenerationRequestAuditRepository` 尚未切换到这个 v1 入口，Usage 终值结算、跨日重预留和 Provider-open v0 阻断仍属于后续阶段；因此 TASK-083 整体仍是进行中，`CHAPTER_PLAN_V1` 继续未注册。

## 2. 已实现内容

### 2.1 原子预留事务

- 从 Stage→Job 得到权威 Book，读取当前 BOOK/DAILY policy head 与 revision；缺失、身份不符或证据晚于请求时间均失败关闭。
- 动态读取当前连接的数据外发确认，不信任调用方提供的 destination、protocol 或 binding hash。
- 用 DAILY policy 的显式 IANA zone 和请求 epoch 派生 canonical daily key，调用方传入的日键被忽略。
- 在任何余额聚合前先插入 `RESERVED` candidate，以 SQLite 写事务取得竞争顺序；聚合会包含候选自身。
- 书级聚合同一 Book、日级聚合同一 daily key 的全部非 `RELEASED` reservation，不按 policy revision 过滤，换 policy 不会清空已经占用的额度。
- request/book/daily token 上限分别拒绝；存在金额上限时，只要任一占用缺金额、缺币种或币种不同就保守拒绝，不做汇率换算，也不只累计“碰巧同币种”的行。
- 额度通过后才调用既有 `recordRequestIntent`，写入 enforcement v1、reservation ID、UNKNOWN/PROVISIONAL Usage 和 Stage 状态；写后回读 reservation/Attempt/Usage/Stage 完整核对。
- 旧直接调用保持 `budgetEnforcementVersion=0`、`budgetReservationId=null`，避免 Phase 3A 在公开入口切换前伪造已经受预算保护。

### 2.2 并发与重启证明

- 同一 Room 实例两条协程同时申请书级剩余额度，只允许一个成功；失败方四类状态均为零写入。
- 两个 Room 实例指向同一个 WAL 数据库文件同时申请，只允许一个成功；失败是有限的 BOOK/LIMIT_EXCEEDED，而不是留下半状态。
- 两实例关闭后重新打开数据库，胜者 reservation 仍计入余额；第三次申请继续被拒绝，证明余额不是内存 counter。
- 并发和重启后的书级聚合都精确为胜者的 100 tokens。

### 2.3 脱敏与边界

- draft、成功结果和拒绝异常的字符串不会展开 ID、金额、币种、zone、destination 或 hash。
- 拒绝只暴露有限 `BudgetScope` 与有限 reason。
- 没有新增平行余额表、内存 counter、汇率换算或 policy revision 过滤。
- 没有修改 schema、migration、Provider、feature、App 或 Gradle；Phase 3A 只增加内部数据库核心和测试。

## 3. DeepSeek 执行与 Sol 审查

- 任务包：`docs/ai/task-packets/TASK-083-PHASE-3A-ATOMIC-RESERVATION-CORE.md`
- 运行：`20260809-091858-713b52af`
- 模式：workspace-write、max reasoning、35 分钟上限、无累计 Token 上限。
- 结果：约 19 分 44 秒正常结束，退出码 0；总 Token 4,043,379，cached input 3,666,176，output 97,889，reasoning output 61,555。
- DeepSeek 只改动任务包允许的 DAO、intent 兼容字段、reservation repository 和专项测试，没有请求权限，也没有切换公开入口。

Sol 逐条复核了候选先写顺序、跨 revision 聚合、金额失败关闭、动态 disclosure、日键派生、v1 Attempt 绑定和字符串脱敏；随后独立补齐同 Room、双 Room 同文件及重开数据库三类竞争证据。并发测试初次编译暴露的是测试夹具中可空数据库变量的 Kotlin smart-cast 问题，改用稳定局部引用后通过，生产约束没有放松。

## 4. 验证证据

- `git diff --check`：退出 0。
- `core:database` JVM：90/90，0 失败、0 错误、0 跳过。
- `compileDebugKotlin`、`compileDebugAndroidTestKotlin`：通过。
- API 30 `emulator-5556`：reservation 专项 11/11；数据库全量 237/237。
- API 35 `emulator-5558`：reservation 专项 11/11；数据库全量 237/237。
- `scripts/security-scan.ps1 -SkipArtifacts`：`SECURITY_SCAN_OK`。
- Git remote 为空；真实 Provider 调用 0，Fake Provider 调用 0，物理设备写入 0。

## 5. 下一阶段边界

Phase 3B 必须把唯一公开 RequestIntent 路径切换到原子 v1 reservation，并同步改造所有调用方和测试夹具，不能保留可发送的 v0 旁路。随后继续完成：

1. 所有 FINAL/迟到 Usage 在唯一事务入口把 reservation 按终值结算；UNKNOWN 保留 estimate，实际超预留也必须如实保存。
2. 只有 Provider 明确证明请求未执行时才能 RELEASE；RELEASED 后到达高可信 Usage 要重新 SETTLED。
3. 未发送请求跨午夜时释放旧日预留并按新日重新申请，重启后仍不能绕过。
4. Provider-open 永久拒绝 v0 Attempt，并重新校验 reservation、实际 connection profile、adapter protocol、canonical destination 与 disclosure binding 全部相等。
5. 上述证据完成前不注册 chapter-plan route，不调用 App 内真实付费生成 API。
