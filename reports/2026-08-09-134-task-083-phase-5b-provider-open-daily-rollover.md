# 工作汇报 134：TASK-083 Phase 5B Provider-open 跨日旧请求释放

日期：2026-08-09  
项目：织卷 Android App  
结论：Phase 5B 已完成并通过双 API 与完整离线门禁；TASK-083 仍未关闭。

## 1. 本阶段解决的问题

旧实现会在 RequestIntent 已经写入、但真正打开 Provider 前直接使用旧日预算。如果排队跨过午夜，旧 DAILY reservation 可能被拿到新日继续发送，造成每日额度语义错误。

本阶段把日界检查放在发送许可的最后一道持久门禁中：

- 同日：继续既有精确 Job、Stage、来源、租约和 heartbeat 校验，然后才签发一次性 claimed permit。
- 跨日：不打开草稿、不调用 adapter、不发送请求；先原子结束旧请求并释放旧日 reservation，再用一个脱敏信号要求持久 runner 重新准备。

## 2. 已完成实现

- 新增专用错误 `DAILY_BUDGET_PERIOD_EXPIRED_BEFORE_SEND`，自动重试只允许发生在“确定未发送”上下文。
- Attempt 新事件只允许 `INTENT_RECORDED -> FAILED_RETRYABLE`；Stage 按剩余次数进入 `READY` 或 `NEEDS_ACTION`；Job 使用独立换日事件回 `READY`，次数耗尽则进入 `NEEDS_ACTION`。
- `claimForProviderOpen` 从当前 DAILY policy head/revision 的持久 IANA zone 和 `validatedAt` 重算规范日键。日键不一致时不生成 claimed permit。
- 新增与 Provider-proof 完全分离的换日释放事务。它精确要求：未发送v1 Attempt、UNKNOWN/PROVISIONAL空Usage、仍为RESERVED且accounted=estimate的reservation、当前Stage/Job、精确租约和最新Attempt。
- 同一Room事务写入五类结果：Attempt失败、Usage UNKNOWN/FINAL、reservation RELEASED/accounted=0、Stage/Job READY或NEEDS_ACTION，并清空租约；任一步CAS或回读不一致全部回滚。
- 业务异常只在事务提交后抛出，避免“正确释放被异常回滚”。异常只包含`retryAllowed`，不泄露ID、日期、时区、token、金额、目的地或快照。
- 旧permit重放失败；两个并发claim最多一个完成释放；已经SENT的Attempt不能进入换日释放。

## 3. DeepSeek 与 Sol 分工

- 设计审计运行：`20260809-132802-f5f3a58a`，max推理、约18分19秒正常完成，总Token 4,457,940。Sol采纳“发送前重新核对日键”和“专用释放语义”，但否决复用同一attemptNo和在claim内部直接创建替代Attempt：现有唯一约束与attemptCount合同不允许这样做。
- 生产实现运行：`20260809-135237-280a5812`，max推理、45分钟护栏、无总Token上限；达到时间护栏时没有final，但留下真实可审查WIP，总Token 14,233,088。Sol补齐穷举分支、精确lease/Stage/Job/latest Attempt校验、`@Transaction`边界和写后检查。
- 测试补充运行：`20260809-144801-9b9d1753`，约15分30秒异常退出，只有31,356 Token且没有测试代码差异；这不是“慢但仍在写”的正常超时。Sol没有重复空转，直接完成设备测试。
- 三次运行均没有请求修改权限，没有读取/输出密钥，没有调用织卷App内部Provider。

## 4. 测试发现与处理

- 首次数据库测试发现测试fixture给公开RequestIntent传了null草稿引用，而公开合同要求受保护工件UUID；改用固定测试UUID，生产代码无需修改。
- 首次Executor测试把明文长度误认为descriptor字段；改为比较revision/updatedAt并实际解密读取0字节，证据更直接。
- PowerShell首次Gradle定向参数未加引号，被解释成任务名；修正命令参数后重新真实执行，错误运行不计入测试通过。
- 上海时区使用精确边界：`57_599_999ms`仍是同一当地日期，`57_600_000ms`进入下一日期。测试使用扩展测试lease时限隔离“日界判断”和“lease过期”两个独立变量。

## 5. 验证结果

- `PersistentBudgetReservationDatabaseTest`：API30、API35各35/35。
- `AuditedStreamingProviderExecutorTest`：API30、API35各18/18；跨日时adapter调用0，加密草稿revision/updatedAt和0字节内容不变。
- `core:database`全量：API30、API35各264/264。
- `feature:generation`全量：API30、API35各43/43。
- 统一JVM：592/592，0失败、0错误、0跳过。
- `scripts/verify-build.ps1 -Offline`：801 actionable tasks；Debug、Release、Lint/Vital、R8、Release APK、扫描器自测、源码与5个构建产物安全扫描、备份排除全部通过。
- `git diff --check`通过；Git remote为空；真实Provider调用0；物理设备写入0；未修改网络配置。

## 6. 尚未完成

Phase 5B 只完成“可靠结束旧日未发送请求并重新排队”，没有虚报为完整重预留。后续仍需：

1. Phase 5C：runner重新取得精确Job/Stage租约后，创建新attemptNo、新日reservation和新RequestIntent；旧草稿非空时复制到新的受保护工件，不能共享旧引用或经过明文临时文件。
2. 实际Provider-open目的地匹配：执行器收到的`ProviderConnectionProfile`与adapter protocol必须精确匹配reservation冻结的connection、canonical destination和protocol。

`CHAPTER_PLAN_V1`仍未注册，完整小说自动生成链尚未完成。
