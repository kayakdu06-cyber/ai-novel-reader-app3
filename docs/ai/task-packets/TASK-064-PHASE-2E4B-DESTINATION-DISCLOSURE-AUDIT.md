# TASK-064 Phase 2E4B：外部数据目的地确认内核只读审计

## 任务身份

- 任务 ID：`TASK-064 / Phase 2E4B / destination disclosure audit`
- 仓库根目录：`D:\gptuser\projects\ai-novel-reader-app2`
- 基准分支与 HEAD：`main` / `8ce774429da1c3f7139a221bc241c34d81a2efdd`
- 当前未提交改动：保留全部既有 WIP；本次只审计 Sol 新增的目的地确认切片，不回退或重写其他改动。
- 执行模型：DeepSeek V4 Flash（纯文本，只读补丁提案）

## 运行预算

- 推理等级：`max`
- 最长运行时间：15 分钟；这是 4 个实现/测试文件的窄范围只读审计，不需要放宽到 30～45 分钟。
- 累计 Token 上限：无。
- 预计读取文件：强制规则 4 份、规格 4 份、实现/测试 5 份，共 13 份。
- 预计执行命令：只读 `git diff`/`rg`/文件读取；不运行 Gradle、不写文件。
- 提前停止条件：需要扩展到 schema/migration/预算实现、需要读取密钥、范围外缺陷、权限阻塞或重复失败。

## 目标

只读审查 Phase 2E4B 当前 WIP 是否正确建立版本化目的地规范化、持久接受与动态失效证据。重点找出能导致把小说发送到未确认 host、绑定误复用、证据伪造、敏感信息泄露、Room 半写入或兼容性回归的问题；没有实质问题时明确说明。

## 当前现场与已有 WIP

- `ExternalDataDestinationBindingV1` 将 scheme/host/effective port/protocol/disclosure version 规范化并哈希；请求 path 不属于数据接收方身份。
- `ConnectionDao.acceptCurrentDataDisclosure` 在 Room 事务中读取当前连接、计算 binding、CAS 写 disclosure 字段并回读验证。
- `readAcceptedDataDisclosureEvidence` 每次按当前 base URL/protocol 重算并核对 normalized destination、版本和 hash；它只是证据，不是 Provider permit。
- `PersistentConnectionRepository` 新连接保存 canonical destination 但 disclosure 仍为 null，并暴露接受/读取方法。
- 纯 JVM 4 项已通过；`ConnectionDatabaseTest` 4/4，API30/API35数据库全量各220/220；App debug Kotlin 已编译。
- 当前 `CHAPTER_PLAN_V1` 仍未注册，没有真实/Fake Provider 调用。

## 必读资料

1. `AGENTS.md`
2. `docs/24-AI-DEVELOPMENT-PROTOCOL.md`
3. `docs/ai/CURRENT-CONTEXT.md`（只读 §32）
4. 本任务包
5. `docs/11-SECURITY-PRIVACY-BACKUP.md`（只读 §12.1）
6. `docs/15-TEST-PLAN.md`（只读 TEST-090/091 与 §54）
7. `docs/18-DECISION-LOG.md`（只读 DEC-071）
8. `core/model/src/main/kotlin/app/zhijuan/core/model/ExternalDataDestination.kt`
9. `core/model/src/test/kotlin/app/zhijuan/core/model/ExternalDataDestinationBindingV1Test.kt`
10. `core/database/src/main/kotlin/app/zhijuan/core/database/connection/ConnectionEntities.kt`
11. `core/database/src/main/kotlin/app/zhijuan/core/database/connection/ConnectionDao.kt`
12. `core/database/src/androidTest/kotlin/app/zhijuan/core/database/ConnectionDatabaseTest.kt`
13. `app/src/main/java/app/zhijuan/reader/connection/PersistentConnectionRepository.kt`

不得读取日志、会话、密钥、原项目、其他任务 WIP 或无关文档。若需要看 `ProviderConnectionProfile` 的 URL 约束，只允许读取 `provider/common/src/main/kotlin/app/zhijuan/provider/common/ProviderValueTypes.kt` 第 65～160 行。

## 范围

只读审计；若发现确定问题，最终可提出最小补丁，但只允许涉及：

- `core/model/src/main/kotlin/app/zhijuan/core/model/ExternalDataDestination.kt`
- `core/model/src/test/kotlin/app/zhijuan/core/model/ExternalDataDestinationBindingV1Test.kt`
- `core/database/src/main/kotlin/app/zhijuan/core/database/connection/ConnectionDao.kt`
- `core/database/src/androidTest/kotlin/app/zhijuan/core/database/ConnectionDatabaseTest.kt`
- `app/src/main/java/app/zhijuan/reader/connection/PersistentConnectionRepository.kt`

明确不在范围：schema/migration、三层预算、RequestIntent、Provider executor、UI、registry、真实 API、文档状态修改。

## 不可破坏的约束

- 不能把目的地 evidence 描述成独立发送 permit；未来仍要与 exact 双租约、预算 reservation、RequestIntent 同事务边界组合。
- 首次新目的地或 scheme/host/effective-port/protocol 变化必须失效；同 origin 的 path、host 大小写、默认端口、尾斜杠不得制造不必要重复确认。
- 确认记录绑定当前 disclosure version；版本升级后旧确认失败关闭。
- 连接测试仍不需要小说内容 disclosure；新连接默认未确认。
- 不能把 base URL、binding hash、secret 或小说内容放入异常/toString/日志。
- 不改 schema；四个 disclosure 列已经存在。
- 不调用真实/Fake Provider，不连接设备，不读或输出 API Key。

## 实施要求

1. 审查 URI 规范化是否存在 origin 混淆、userinfo/query/fragment、默认端口、IPv6、大小写、尾点、非法端口或协议 ID 漏洞。
2. 审查 hash canonical input、版本绑定、比较方式和对象字符串脱敏。
3. 审查 DAO 事务/CAS 是否可能把旧 endpoint 的确认写到新 endpoint，或对缺失/部分/损坏证据误放行。
4. 审查 App 保存路径是否始终让新连接未确认，并保存 canonical destination。
5. 审查测试是否缺少能证明 TEST-090/091 核心失败路径的最小负例。
6. 不得扩大为 UI 或 Provider-open 实现。

## 验收标准

- [ ] 给出按严重度排序的具体发现，引用文件与逻辑位置。
- [ ] 每个发现说明可触发输入、实际风险和最小修复。
- [ ] 没有问题的维度明确写“未发现”。
- [ ] 不把尚未接线的 Provider-open/预算误报成此切片已完成。
- [ ] 补丁（如有）保持无 schema 变化和失败关闭。

## 验证命令

本次只读运行不执行测试。Sol 已运行并将在修改后复跑：

```powershell
.\gradlew.bat --offline :core:model:test
.\gradlew.bat --offline :core:database:connectedDebugAndroidTest
```

## 回交格式

1. `完成内容`
2. `修改文件`（只读运行应为“无”）
3. `验证`（明确未运行）
4. `未完成/风险`
5. `需要 Sol 处理`
6. `假设`

不要宣布 TASK-064 完成，不要更新正式状态。若需要补丁，在正文发现之后给出一个完整、最小的 apply_patch 块；若无问题，不要生成空补丁。
