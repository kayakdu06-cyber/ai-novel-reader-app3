# 工作汇报 136：TASK-083 Phase 5D Provider-open 实际目的地匹配

日期：2026-08-09  
项目：织卷 Android App  
项目根目录：`D:\gptuser\projects\ai-novel-reader-app2`  
结论：Phase 5D 完成；TASK-083 在“持久预算 + 实际发送目的地门禁”边界正式关闭。

## 1. 本阶段解决的问题

Phase 5C 之前，系统已经可以在数据库中冻结 connection、canonical destination、protocol 和 disclosure binding，也能完成三层预算预留，但真正执行请求时仍缺最后一层证明：实际传给 adapter 的 profile 是否就是当初确认并预留预算的目的地。

如果不补这层，理论上可能发生：按连接 A 完成隐私确认和预算预留，实际却把请求发到连接 B。Phase 5D 将该旁路关闭。

## 2. 已完成的实现

### 2.1 短生命周期实际目的地证据

新增 `ProviderOpenDestinationEvidence`：

- 从实际 `ProviderConnectionProfile` 派生；
- 只保存 connection ID、canonical destination、protocol ID；
- canonical 规则复用既有 `ExternalDataDestinationBindingV1`；
- 不保存原始 base URL、path、查询参数或凭据；
- `toString` 完全脱敏。

### 2.2 Provider-open 强制匹配

生产代码中的 `claimForProviderOpen` 现在强制要求实际目的地证据，不再保留无证据重载。领取发送许可时，单个 Room 事务会同时核对：

1. 实际 connection ID；
2. 实际 canonical origin；
3. 实际 adapter protocol；
4. 当前连接动态读取的 accepted disclosure；
5. reservation 冻结的 destination、protocol、disclosure version、binding 与 acceptedAt。

当前 disclosure 可以是同一 binding 的更新接受记录，但接受时间不能倒退。endpoint、protocol、版本或 binding 漂移时全部失败关闭。

### 2.3 副作用顺序加固

目的地比较放在以下动作之前：

- 同日 heartbeat 更新；
- 跨日旧 reservation 释放；
- 受保护草稿打开；
- adapter 调用；
- Provider 网络连接。

目的地不匹配时，Attempt、Usage、reservation、Stage、Job 五类持久状态零写入，而且一次性 permit 不被消耗；使用正确 profile 可以重试。

### 2.4 executor 二次栅栏

`AuditedStreamingProviderExecutor` 现在：

1. 先检查 `profile.protocol == adapter.protocol`；
2. 从同一不可变 profile 派生 evidence 并领取 claim；
3. 打开受保护草稿前再次从该 profile 派生并比较；
4. 匹配成功后才打开草稿并调用 adapter。

claimed request、`mark sent` 和 `mark stream started` 继续绑定同一 evidence，防止 claim 后换接其他连接、origin 或 protocol。

## 3. 主要文件

生产代码：

- `core/model/src/main/kotlin/app/zhijuan/core/model/ProviderOpenDestinationEvidence.kt`
- `core/model/src/main/kotlin/app/zhijuan/core/model/ExternalDataDestination.kt`
- `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationRequestAuditRepository.kt`
- `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationStreamingDraftRepository.kt`
- `feature/generation/src/main/kotlin/app/zhijuan/feature/generation/AuditedStreamingProviderExecutor.kt`

测试与测试支持：

- `core/database/src/androidTest/kotlin/app/zhijuan/core/database/BudgetedRequestTestSupport.kt`
- `core/database/src/androidTest/kotlin/app/zhijuan/core/database/PersistentBudgetReservationDatabaseTest.kt`
- `feature/generation/src/androidTest/kotlin/app/zhijuan/feature/generation/BudgetedGenerationTestSupport.kt`
- `feature/generation/src/androidTest/kotlin/app/zhijuan/feature/generation/AuditedStreamingProviderExecutorTest.kt`
- 既有 RequestIntent 测试调用已迁移到只存在于测试源码的 evidence helper；生产代码没有重新引入旁路。

## 4. DeepSeek 与 Sol 分工

- DeepSeek 以 max 推理等级完成只读设计审计：`20260809-165246-d326a6d6`。
- 该运行没有请求修改权限、没有写入代码、没有调用 App Provider；最终回交存在字符编码问题，但审计结论仍可用于核对边界。
- Sol 完成正式实现、迁移既有测试、修复 feature 测试跨模块访问内部 DAO 的问题，并把关键检查从“网络之后”修正为“工件和网络之前”。
- 所有差异与测试结果由 Sol 独立审查后确认。

## 5. 验证结果

### 编译

- `:core:model:compileKotlin`：通过。
- `:core:database:compileDebugKotlin`：通过。
- `:feature:generation:compileDebugKotlin`：通过。
- 数据库与生成模块 Android 测试源码编译：通过。

### API 30 模拟器

- `PersistentBudgetReservationDatabaseTest`：43/43。
- `AuditedStreamingProviderExecutorTest`：23/23。
- `core:database` 模块全量：272/272。
- `feature:generation` 模块全量：48/48。

### API 35 模拟器

- `PersistentBudgetReservationDatabaseTest`：43/43。
- `AuditedStreamingProviderExecutorTest`：23/23。
- `core:database` 模块全量：272/272。
- `feature:generation` 模块全量：48/48。

### 统一离线门禁

`scripts/verify-build.ps1 -Offline`：通过。

- 801 个 actionable Gradle task；
- Debug/Release 构建通过；
- Lint/Vital、R8 通过；
- 安全扫描脚本自测 4/4；
- 源码与 5 个 APK 扫描通过；
- `allowBackup=false`；
- cloud backup/device transfer 排除策略通过。

测试期间没有调用真实 Provider，没有向物理设备写入，也没有添加 Git remote。

## 6. 中途暴露并修正的问题

1. `core:model` 是纯 Kotlin 模块，正确任务是 `compileKotlin`，不是不存在的 `compileDebugKotlin`。
2. feature Android 测试不能直接访问 database 模块的 internal DAO；改为只读 SQL 行快照验证零写入，没有放宽生产可见性。
3. 统一门禁首次发现两个旧 JVM 测试仍调用旧 claim 形式；已迁移到测试专用 evidence helper并重跑通过。

这些都是测试或接线缺口，没有以跳过测试、放宽生产 API 或删除断言的方式处理。

## 7. 完成边界

TASK-083 已完成的范围：

- request/book/daily 三层持久预算策略与原子预留；
- RequestIntent v1；
- Usage 唯一终值结算；
- 明确未执行释放与迟到 usage 回补；
- 跨日旧请求终止和新日替代请求；
- 实际 profile、adapter、当前 disclosure 与冻结 reservation 的精确目的地匹配。

仍未完成、不能误报：

- `CHAPTER_PLAN_V1` 尚未注册到 total runner；
- chapter-plan 真实执行、解析与提交尚未闭环；
- App 尚不能从创建页一路自动生成并边生成边阅读；
- 本阶段不代表可分发成品。

## 8. 下一步

回到 TASK-064，先完成 chapter-plan exact-token 远程执行、严格结构解析和 DEC-068 原子提交，并创建 initial DRAFT；之后才注册 plan executor、接入其余阶段并形成 Fake-only 首章闭环。
