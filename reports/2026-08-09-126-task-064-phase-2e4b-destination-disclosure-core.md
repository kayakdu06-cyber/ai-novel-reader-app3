# 工作汇报 126：TASK-064 Phase 2E4B 外部数据目的地确认内核

> 日期：2026-08-09  
> 项目：织卷 Android App  
> 唯一仓库：`D:\gptuser\projects\ai-novel-reader-app2`  
> HEAD：`8ce774429da1c3f7139a221bc241c34d81a2efdd`  
> 结论：Phase 2E4B 完成；目的地确认内核可用，用户确认 UI 与 Provider-open 原子接线仍未完成

## 1. 本阶段结果

织卷现在能把一个模型连接明确转换成“用户确认过的外部数据接收方”，并在地址、端口、协议、确认版本或持久字段变化后自动判定旧确认失效。

这项能力不会要求用户逐章确认。同一个真实接收方只需确认一次；换 host、非默认端口、scheme、Provider 协议或 disclosure 文案版本后，才需要重新确认一次。

本阶段仍没有开放章节规划网络请求。目的地证据只是后续发送门禁的一部分，不能绕过三层预算、exact 双租约、RequestIntent 和一次性发送许可。

## 2. 实现内容

### 2.1 版本化 canonical destination

新增 `ExternalDataDestinationBindingV1`：

- 目的地固定为 `scheme://host:effectivePort`；
- scheme/host 统一小写，默认端口显式化；
- request path、尾斜杠和 DNS 尾点不制造重复确认；
- Provider protocol、disclosure version 和 policy version 一并进入 SHA-256；
- userinfo、query、fragment、非法端口、非 HTTP(S)、空 host 与非法 protocol ID 失败关闭；
- IPv6 使用括号化小写字面量；
- binding 和 hash 不进入对象字符串。

### 2.2 新连接默认未确认

`PersistentConnectionRepository` 创建连接时只保存 canonical destination，以下字段仍保持 `null`：

- `dataDisclosureVersion`；
- `dataDisclosureAcceptedAt`；
- `dataDisclosureBindingHash`。

连接测试不会借机接受小说数据发送。Repository 新增明确的接受和证据读取方法，等待后续极简确认 UI 接入。

### 2.3 Room 原子接受与动态失效

`ConnectionDao.acceptDataDisclosureForCurrentDestination` 在一个 Room 事务中：

1. 读取当前连接的 base URL 和 protocol；
2. 计算 canonical binding；
3. 以 connection ID、原 base URL 和原 protocol 为 CAS 条件写入；
4. 立即按数据库当前事实回读并完整验证。

读取证据时重新计算 binding，而不是相信旧字段。host、port、scheme、protocol、版本、normalized destination 或 hash 任一不一致都会失败。格式正确但数值伪造的 64 位 hash 也不能通过。

### 2.4 脱敏加固

DeepSeek 审计发现既有 `ConnectionProfileEntity` 是 data class，默认 `toString()` 会展开 base URL、密钥尾号、模型与新增 binding hash。Sol 已给实体增加脱敏字符串表示；binding 和 evidence 也都只显示版本和 redacted 标记。

## 3. DeepSeek 审计

- 运行 ID：`20260809-071823-4baf2bb8`；
- 模式：read-only patch proposal；
- 推理强度：max；
- 硬上限：15 分钟；
- 实际用时：约 5 分 36 秒；
- Token：388,479 total，311,168 cached input，32,738 output；
- 结果：无 P1 生产缺陷、无权限请求、无文件写入。

审计建议补充 host/hash/version、IPv6、默认端口和非法端口负例，并指出实体默认 `toString()` 风险。Sol 复核后采纳，同时把 DAO 方法从容易误解的 `acceptCurrentDataDisclosure` 改为 `acceptDataDisclosureForCurrentDestination`。

## 4. 修改文件

### 代码与测试

- `core/model/src/main/kotlin/app/zhijuan/core/model/ExternalDataDestination.kt`
- `core/model/src/test/kotlin/app/zhijuan/core/model/ExternalDataDestinationBindingV1Test.kt`
- `core/database/src/main/kotlin/app/zhijuan/core/database/connection/ConnectionDao.kt`
- `core/database/src/main/kotlin/app/zhijuan/core/database/connection/ConnectionEntities.kt`
- `core/database/src/androidTest/kotlin/app/zhijuan/core/database/ConnectionDatabaseTest.kt`
- `app/src/main/java/app/zhijuan/reader/connection/PersistentConnectionRepository.kt`

### 协作与正式文档

- `docs/ai/task-packets/TASK-064-PHASE-2E4B-DESTINATION-DISCLOSURE-AUDIT.md`
- `docs/06-AI-GENERATION-SYSTEM.md`
- `docs/08-TECHNICAL-ARCHITECTURE.md`
- `docs/09-DATA-MODEL.md`
- `docs/10-STATE-MACHINES.md`
- `docs/11-SECURITY-PRIVACY-BACKUP.md`
- `docs/15-TEST-PLAN.md`
- `docs/18-DECISION-LOG.md`（新增 DEC-072）
- `docs/19-IMPLEMENTATION-BACKLOG.md`
- `docs/20-TRACEABILITY-MATRIX.md`
- `docs/22-WORK-STATUS.md`
- `docs/25-RELIABILITY-AND-GENERATION-PERFORMANCE-ROADMAP.md`
- `docs/ai/CURRENT-CONTEXT.md`

上一份报告中“当前断网”的现场描述也已纠正：网络现已恢复；Phase 2E4A 未调用 DeepSeek 是当时沿用了此前断网现场，Phase 2E4B 已正常完成只读审计。

## 5. 验证证据

| 验证 | 结果 |
|---|---|
| `core:model` JVM 全量 | 17/17，0失败、0错误、0跳过 |
| API 30 `ConnectionDatabaseTest` | 6/6 |
| API 35 `ConnectionDatabaseTest` | 6/6 |
| API 30 `core:database` Android 全量 | 222/222，0失败、0跳过 |
| API 35 `core:database` Android 全量 | 222/222，0失败、0跳过 |
| App Debug Kotlin 编译 | 通过 |
| `scripts/verify-build.ps1 -Offline` | 通过，801 actionable tasks |
| Debug / Release | 通过 |
| Lint / Vital / R8 | 通过 |
| 安全扫描器自测 | 通过 |
| 源码与 5 个 APK 安全扫描 | 通过 |
| Android 备份排除 | 通过 |
| `git diff --check` | 通过，仅有既存换行提示 |

一次中间全量测试因 Kotlin 表达式体最后返回 `assertThrows` 对象，Android JUnit 报测试方法不是 void。显式返回 `Unit` 后，定向 6/6 和双 API 全量均重新通过；该问题只在测试方法签名，生产代码没有放松。

## 6. 明确未完成

- 用户可见的“首次向此目的地发送哪些数据”极简确认页；
- 把目的地动态验证放进 Provider-open 的最终原子发送事务；
- TASK-083 三层预算持久 policy/reservation；
- reservation 与 RequestIntent、Attempt、UNKNOWN/PROVISIONAL Usage 同事务；
- plan exact-token executor、UNKNOWN/恢复与 DEC-068 原子提交；
- 把 `CHAPTER_PLAN_V1` 加入 registry。

因此 TEST-090/091 目前只完成内核和数据库负例，产品级验收仍保持未勾选；真实/Fake Provider 调用均为 0，物理设备写入为 0，Git remote 仍为空。

## 7. 下一阶段

下一阶段进入 TASK-083：先设计 schema v17 的三层预算 policy/reservation 和结算状态，再证明两个并发 Job 争抢最后余额时只有一个能与 RequestIntent 同事务成功。目的地证据将在该事务内动态复核，但 plan route 仍不会提前注册。
