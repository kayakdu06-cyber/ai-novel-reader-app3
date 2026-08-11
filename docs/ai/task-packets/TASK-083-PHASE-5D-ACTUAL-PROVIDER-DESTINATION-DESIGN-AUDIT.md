# TASK-083 Phase 5D：实际 Provider profile / adapter 目的地匹配设计审计

## 任务身份

- 任务 ID：`TASK-083 / Phase 5D actual provider-open destination matching design audit`
- 仓库根目录：`D:\gptuser\projects\ai-novel-reader-app2`
- 基准分支与 HEAD：`main` / `8ce774429da1c3f7139a221bc241c34d81a2efdd`
- 当前未提交改动：约269条连续WIP，均属于用户持续开发；禁止reset/clean/checkout/覆盖/整理
- 执行模型：DeepSeek V4 Flash（纯文本只读设计审计）

## 运行预算

- 推理等级：`max`
- 最长运行时间：30分钟；这是TASK-083最后一项已知发送前安全边界，只审计现有profile、adapter、reservation和claim链
- 累计Token上限：无
- 预计读取文件数与明确清单：本任务列出的13个文件及其直接类型定义
- 预计执行命令/测试数：只允许`git status`、`rg`、`Get-Content`、`git diff`等只读命令；不运行Gradle、模拟器、Provider或网络命令
- 提前停止条件：正确方案必须新增schema/migration、必须修改Provider传输实现、或需要扩展到total runner/plan route才能成立

## 目标

只读审计最窄且不可旁路的发送前门禁：`AuditedStreamingProviderExecutor`实际收到的`ProviderConnectionProfile`与`ProviderAdapter.protocol`，必须在打开草稿和调用`adapter.generate`之前，精确匹配v17 reservation冻结的connection、canonical destination、protocol和当前有效disclosure。

输出应给出具体分层、有限证据类型/方法签名、事务内逐字段验证、验证顺序与API30/API35测试矩阵。本任务不修改文件、不运行测试、不调用Provider、不宣称Phase 5D或TASK-083完成。

## 当前现场与已有 WIP

- Phase3B后公开prepare只创建enforcement v1 Attempt，并把`request_budget_reservation`身份写入一次性permit。
- reservation冻结`connectionId`、`normalizedDestination`、`protocolId`、`disclosureVersion`、`disclosureBindingHash`和`disclosureAcceptedAt`。
- `claimForProviderOpen`当前在一个Room事务内重验Attempt/Usage/Job/Stage/reservation，处理Phase5B日界释放，同日才heartbeat并签发claimed permit；但它不知道执行器实际收到的profile/adapter。
- `AuditedStreamingProviderExecutor.execute`当前顺序为：`drafts.claimForProviderOpen`→打开加密草稿buffer→`adapter.generate(profile, request)`。
- `ProviderConnectionProfile`公开`connectionId`、`protocol`，base URL只能通过`withBaseUrl`短回调读取；对象`toString`不显示base URL/connection ID。
- `ProviderAdapter`公开`protocol`。四个正式adapter各有固定protocol，测试Fake也实现该字段。
- `ExternalDataDestinationBindingV1.create(baseUrl, protocolId)`会生成canonical origin、当前disclosure version和binding hash，支持同origin path/大小写/默认端口等价，并拒绝非法URI。
- `ConnectionDao.readAcceptedDataDisclosureEvidence`会从当前connection/baseUrl/protocol和stored disclosure重新生成并核对binding；连接删除、endpoint/protocol/version/hash漂移均失败关闭。
- Phase5C已完成repository级新日替代请求准备；本阶段不得重写或破坏Phase5B/5C。
- 现有`GenerationStreamingDraftRepository.claimForProviderOpen(request, validatedAt)`在多个测试中直接调用；如果保留无目的地证据的生产overload，会形成旁路。

## 必读资料

1. `AGENTS.md`
2. `docs/24-AI-DEVELOPMENT-PROTOCOL.md`
3. `docs/ai/CURRENT-CONTEXT.md`第33、34、37～41节
4. `reports/2026-08-09-126-task-064-phase-2e4b-destination-disclosure-core.md`
5. `reports/2026-08-09-130-task-083-phase-3b-public-request-v1.md`
6. `reports/2026-08-09-134-task-083-phase-5b-provider-open-daily-rollover.md`
7. `reports/2026-08-09-135-task-083-phase-5c-new-day-replacement.md`
8. `core/model/src/main/kotlin/app/zhijuan/core/model/ExternalDataDestination.kt`
9. `provider/common/src/main/kotlin/app/zhijuan/provider/common/ProviderValueTypes.kt`中的`ProviderConnectionProfile`
10. `provider/common/src/main/kotlin/app/zhijuan/provider/common/ProviderAdapter.kt`中的`ProviderAdapter`
11. `core/database/src/main/kotlin/app/zhijuan/core/database/connection/ConnectionDao.kt`的disclosure evidence读取
12. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/BudgetEntities.kt`的reservation字段
13. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationRequestAuditRepository.kt`
14. `core/database/src/main/kotlin/app/zhijuan/core/database/generation/GenerationStreamingDraftRepository.kt`
15. `feature/generation/src/main/kotlin/app/zhijuan/feature/generation/AuditedStreamingProviderExecutor.kt`
16. `core/database/src/androidTest/kotlin/app/zhijuan/core/database/GenerationDatabaseTest.kt`中claim调用
17. `core/database/src/androidTest/kotlin/app/zhijuan/core/database/PersistentBudgetReservationDatabaseTest.kt`中claim/rollover fixture
18. `feature/generation/src/androidTest/kotlin/app/zhijuan/feature/generation/AuditedStreamingProviderExecutorTest.kt`

除上述清单和直接引用外，不得递归扫描整套历史文档、会话、备份、其他项目或无关模块。

## 范围

允许修改：

- 无；本次为只读审计和补丁方案。

明确不在范围：

- schema、migration、trigger、Room实体字段变化。
- 连接UI、确认UI、Secret Store、Provider HTTP传输或adapter编码逻辑。
- total runner、registry、`CHAPTER_PLAN_V1`注册、真实Provider调用。
- Phase5B/5C状态机重写。

## 不可破坏的约束

- 项目隔离：只读当前副本，不访问或修改原项目/其他副本。
- 安全与隐私：不得在异常、日志、`toString`或测试输出中展开base URL、host、connection ID、binding hash、secret引用或正文。
- 状态机与幂等：目的地不匹配时Provider调用0、草稿buffer不开启、permit不被一次性消费、Attempt/Usage/reservation/Stage/Job零写入；正确证据随后仍可重试同一permit。
- 数据库与事务：实际证据、reservation和当前动态disclosure必须在同一个Provider-open事务中对照，事务外预检查不能作为唯一授权。
- 联网与费用：不得调用真实API或产生费用，不修改网络、DNS、代理或防火墙。
- 兼容性与构建基线：minSdk29、API30/API35；尽量复用`ExternalDataDestinationBindingV1`，不得使用API30不支持的Java时间接口。
- 保留全部当前未提交WIP，不改正式完成状态。

## 必答设计问题

1. 最窄证据类型应放在`core:model`、`core:database`还是`feature:generation`？请给出字段、构造权限、`toString`和具体方法签名，使executor能从profile短租借base URL构造，但database不依赖`provider:common`。
2. 应怎样强制`profile.protocol == adapter.protocol`，并将实际connection/base URL canonical origin/protocol与reservation逐字段对照？证据是否需要携带disclosure version和binding hash？
3. Provider-open事务应怎样再次读取当前`AcceptedDataDisclosureEvidence`并与reservation/实际证据比较？相同binding但更晚`acceptedAt`应允许还是拒绝？连接删除、确认撤销、base URL或protocol改变时如何失败关闭？
4. 目的地核对与Phase5B日界判断的顺序是什么？实际证据不匹配且同时跨日时，应零写入保留旧reservation，还是仍释放旧日请求？请说明安全、恢复与可测试性理由。
5. 如何删除或收紧现有不带实际目的地证据的`claimForProviderOpen`调用面，避免未来生产调用旁路，同时以有限fixture迁移现有测试？是否允许仅测试可见helper？
6. 失败异常应如何有限分类和脱敏？哪些是调用配置错误，哪些是持久证据stale？是否需要新标准错误码或状态转换，还是保持Stage/Job原状态等待正确route重试？
7. `markRequestSent`和`markStreamStarted`是否需要把同一目的地证据继续绑定到claimed permit并再次核对，防止claim后上层换profile再传给adapter？结合当前executor参数生命周期说明。
8. 列出最小API30/API35测试：正确同origin path等价成功；connection ID错；不同host/scheme/non-default port；profile protocol与adapter protocol错；reservation protocol错；确认在prepare后撤销/endpoint变化/connection删除；合法同binding重新确认；全部失败adapter调用0、artifact未打开/未变、五类状态零写、正确证据可重试；跨日与目的地错的优先级；异常/toString无敏感值。

## 实施要求（仅输出方案）

1. 推荐最小生产文件范围和精确方法职责。
2. 给出不可旁路的调用链，不能用可选参数、默认值、nullable evidence或通用boolean。
3. 指出所有现有测试调用迁移策略，不要求本次实际修改。
4. 明确拒绝只在executor做字符串比较、只查当前connection不查reservation、只查reservation不查当前disclosure、在`adapter.generate`后补审计等捷径。

## 验收标准

- [ ] 行为标准：实际profile+adapter与冻结reservation+当前disclosure四方一致才签发claimed permit。
- [ ] 失败路径：不匹配在草稿/Provider之前失败，持久状态零写，permit可用正确证据重试。
- [ ] 安全/隐私：无endpoint/ID/hash/secret正文泄漏。
- [ ] 向后兼容：不改schema，Phase5B/5C和API30保持。
- [ ] 文档同步：列出后续Sol必须更新的章节，但本任务不修改。

## 验证命令

本次只读审计不运行测试。建议Sol实现后至少运行：

```powershell
.\gradlew.bat :core:database:compileDebugAndroidTestKotlin :feature:generation:compileDebugAndroidTestKotlin --offline --no-daemon
```

```powershell
.\gradlew.bat :core:database:connectedDebugAndroidTest :feature:generation:connectedDebugAndroidTest --offline --no-daemon
```

```powershell
scripts/verify-build.ps1 -Offline
```

## 回交格式

1. `推荐证据类型与方法签名`
2. `Provider-open事务逐字段验证与顺序`
3. `claim后profile不可替换分析`
4. `拒绝的方案`
5. `最小实现文件范围`
6. `API30/API35测试矩阵`
7. `需要Sol最终裁决`

不得宣布Phase 5D或TASK-083完成。
