# TASK-083 Phase 4B：Provider 明确未执行释放与迟到 Usage 回补

## 任务身份

- 任务 ID：`TASK-083 / Phase 4B confirmed-not-executed release`
- 仓库根目录：`D:\gptuser\projects\ai-novel-reader-app2`
- 基准分支与 HEAD：`main` / `8ce774429da1c3f7139a221bc241c34d81a2efdd`
- 当前连续 WIP：255 条 `git status --short`，SHA-256=`d47a942072b73dbc7419d6219b0fbc76dde3e9d33bf829712158f7e2895c7260`；全部属于用户持续开发，禁止 reset/clean/checkout/覆盖/整理
- 执行模型：DeepSeek V4 Flash（纯文本编码）

## 运行预算

- 推理等级：`max`
- 最长运行时间：45 分钟；用户允许长测试等待，本任务仍保持窄范围，若正常运行未结束由 Sol 自动续接
- 累计 Token 上限：无
- 预计读取文件数：规程/现场 2 个、报告/状态 3 个、生产代码 4 个、测试 2 个，共约 11 个
- 预计执行命令：一次 core:database 主机编译/JVM；不得启动模拟器
- 提前停止条件：需要改 schema/entity/migration/trigger、需要改 feature/app/provider、权限阻塞、需要真实 Provider、或同一编译失败重复两次

## 目标

仅在现有未知结果恢复策略已经裁决为 `REQUEUE_PROVEN_NOT_EXECUTED` 时，让 enforcement v1 reservation 与 UNKNOWN FINAL Usage、Attempt/Stage/Job 回队在同一个外层 Room 事务中完成：reservation 从 `RESERVED` 变为 `RELEASED`，accounted 清零。随后若出现高可信 `PROVIDER_REPORTED` 迟到 Usage，现有 `recordUsage` 必须把 `RELEASED` reservation 恢复为 `SETTLED` 并按终值重新计入。

普通失败、断网、Provider 查询无结论、本地已有正文或已知 Usage 绝不能释放。

## 当前现场与已有 WIP

- Phase 4A 已把普通 PROVISIONAL/FINAL/迟到 Provider Usage 收敛进 `GenerationDao.recordUsage` 同一事务，并通过双API数据库252/252、generation42/42、App恢复2/2和完整门禁。
- `UnknownResultRecoveryPolicy` 只有在 Provider 明确 `CONFIRMED_NOT_EXECUTED`、草稿为空且没有已知 Usage 时返回 `REQUEUE_PROVEN_NOT_EXECUTED`。
- `GenerationUnknownResultRecoveryRepository.requeueAfterProviderProof` 已在一个 `database.withTransaction` 中更新 Attempt、FINAL UNKNOWN Usage、Stage和Job，但当前普通 `recordUsage` 会把 reservation 结算为 `SETTLED`，没有释放。
- 现有数据库触发器已经允许 `RESERVED -> RELEASED` 和 `RELEASED -> SETTLED`，禁止 `SETTLED -> RELEASED`；本包不得修改触发器。现有恢复裁决路径在释放前 Usage 仍为 UNKNOWN/PROVISIONAL，因此应直接走 RESERVED release，不先普通结算成 SETTLED。
- Phase 4A 的迟到 Provider 分支目前只支持 `SETTLED -> SETTLED`；需最小扩展为高可信 `RELEASED -> SETTLED`。

## 必读资料

1. `AGENTS.md`
2. `docs/24-AI-DEVELOPMENT-PROTOCOL.md`
3. `docs/ai/CURRENT-CONTEXT.md` 第 34～38 节
4. `reports/2026-08-09-127-task-083-phase-1-persistent-budget-design.md` 第 3.3、5、8 节
5. `reports/2026-08-09-131-task-083-phase-4a-usage-settlement.md`
6. `docs/10-STATE-MACHINES.md` 未知结果恢复部分
7. `core/task/src/main/kotlin/app/zhijuan/core/task/UnknownResultRecoveryPolicy.kt`
8. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationDao.kt`
9. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationUnknownResultRecoveryRepository.kt`
10. `core/database/src/main/kotlin/app/zhijuan/core/database/LibraryDatabaseGuards.kt` reservation 更新触发器（只读）
11. `core/database/src/androidTest/kotlin/app/zhijuan/core/database/GenerationDatabaseTest.kt` 的 unknown recovery 测试
12. `core/database/src/androidTest/kotlin/app/zhijuan/core/database/PersistentBudgetReservationDatabaseTest.kt` Phase4A测试

除上述清单和代码直接引用外，不得递归扫描整套文档、历史会话、报告、备份或其他项目。

## 范围

允许修改：

- `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationDao.kt`
- `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationUnknownResultRecoveryRepository.kt`
- `core/database/src/androidTest/kotlin/app/zhijuan/core/database/GenerationDatabaseTest.kt`
- 如确有必要，可最小修改 `core/database/src/androidTest/kotlin/app/zhijuan/core/database/PersistentBudgetReservationDatabaseTest.kt`

明确不在范围：

- schema/entity/migration/`LibraryDatabaseGuards.kt`/数据库版本
- UnknownResultRecoveryPolicy 和任何状态机枚举/转换
- feature/app/provider、公开生成接口、runner注册
- 跨午夜重预留、实际profile/adapter目的地匹配
- docs/reports/status（由Sol收口）
- 真实或Fake Provider调用、模拟器、网络/代理/DNS/凭据配置

## 不可破坏的约束

- 项目隔离：不得访问或修改其他项目副本。
- 只有既有 policy 已返回 `REQUEUE_PROVEN_NOT_EXECUTED` 的 repository 私有分支可以调用专用释放入口；不得增加用户可传 `release=true` 的公开通用参数，不得根据错误码、断网或空Usage自行猜“未执行”。
- v1 release 前必须仍是：Attempt 已在同一外层事务 CAS 为 `FAILED_RETRYABLE`，Usage 为 UNKNOWN/PROVISIONAL 且全部token/金额为null，reservation为同Attempt的`RESERVED`且accounted仍等于estimate。
- 专用 DAO 入口必须在一个 `@Transaction` 内把 Usage 变为 UNKNOWN/FINAL，同时把 reservation 变为 `RELEASED`、accounted token=0、cost/currency=null、releasedAt/updatedAt=本次审计时间、settledAt仍null；任一失败整体回滚。
- legacy v0 没有reservation：同一Provider未执行恢复继续把Usage变为UNKNOWN/FINAL，但不创建或释放伪reservation。
- 已有本地草稿/已知Usage/Provider无结论路径继续按现有policy进入用户确认或等待，reservation不得RELEASED。
- 迟到回补只接受现有 `recordUsage` 的 FINAL `PROVIDER_REPORTED` 单向升级；`RELEASED -> SETTLED` 时用终值替换accounted，设置首次settledAt为迟到报告时间，保留原releasedAt审计时间。ESTIMATED/UNKNOWN不得复活released reservation。
- 所有CAS必须核对旧status、旧updatedAt、旧accounted和必要时间；写后回读验证Usage/reservation身份与终值。禁止delta、Float/Double、换汇和伪造价格。
- replay/并发必须失败关闭或返回同一事实，不得重复释放、重复累计或建立第二条reservation。
- 异常/toString不得泄漏reservation、attempt、connection、金额、币种、destination或hash。

## 实施要求

1. 在`GenerationDao`增加专用 confirmed-not-executed Usage+release事务入口及最小CAS SQL；普通`recordUsage`不能获得布尔释放开关。
2. 专用入口复用Phase4A的Attempt/reservation/Usage身份与时间约束，v1严格执行RESERVED release；v0只做原有UNKNOWN FINAL。
3. `requeueAfterProviderProof`在Attempt CAS后调用专用入口，Stage/Job后续CAS仍在同一个`database.withTransaction`中；任何一步失败时Attempt/Usage/reservation/Stage/Job全部回滚。
4. 扩展迟到Provider分支：SETTLED仍走原路径；RELEASED通过精确CAS恢复SETTLED，重新计入终值并保留releasedAt。其他状态失败关闭。
5. 更新/新增真实Room测试，至少覆盖：
   - 既有`CONFIRMED_NOT_EXECUTED`正向现在同时断言v1 reservation RELEASED/accounted=0、Usage UNKNOWN FINAL和Attempt/Stage/Job回队；
   - book/daily聚合在release后不再包含该占用；
   - 本地正文矛盾、已知Usage或Provider无结论不释放；
   - release前reservation错态/CAS冲突导致五类状态全部回滚；
   - RELEASED后FINAL PROVIDER_REPORTED迟到回补为SETTLED、终值重新计入、releasedAt保留、settledAt为回补时间；相同回补replay不重复；
   - RELEASED后ESTIMATED/UNKNOWN不能复活；
   - legacy v0未执行恢复继续工作且无reservation。
6. 不删除、放松或跳过现有断言。

## 验收标准

- [ ] 只有Provider明确未执行的既有裁决分支能释放v1 reservation。
- [ ] Usage、release和Attempt/Stage/Job回队形成单一原子事务，无半状态。
- [ ] 高可信迟到Provider Usage能幂等恢复占用；低可信来源不能。
- [ ] legacy v0和其他未知结果路径不回归。
- [ ] 无schema/trigger/feature/app/provider/docs改动。
- [ ] 主机core:database编译与JVM通过。

## 验证命令

```powershell
$env:GRADLE_USER_HOME='D:\gptuser\cache\gradle'
$env:ANDROID_USER_HOME='D:\gptuser\cache\android'
.\gradlew.bat :core:database:test :core:database:compileDebugKotlin :core:database:compileDebugAndroidTestKotlin --offline --no-daemon --console=plain '-Dkotlin.compiler.execution.strategy=in-process'
```

不得启动模拟器。未运行的验证必须在回交中写明原因，不能写成通过。

## 回交格式

1. `完成内容`
2. `修改文件`
3. `验证`
4. `未完成/风险`
5. `需要 Sol 处理`
6. `假设`

不要宣布整个TASK-083或Phase4完成，不要更新正式状态；由Sol根据差异和模拟器证据确认。
