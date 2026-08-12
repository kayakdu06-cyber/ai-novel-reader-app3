# TASK-125 工作报告：普通章节规划闭环

> 日期：2026-08-12
> 状态：完成

## 完成内容

- `chapter-plan.v2` 冻结 expectation、能力激活、策略清单与上下文证据，并绑定独立清单哈希和策略编译哈希。
- v2 route 复用既有 exact-token、目的地、预算、当前 Job/Stage 双租约和来源哈希重验，不重写门禁。
- 请求工厂输出固定 v2 schema；Fake Provider 可流式返回严格计划。
- 结构校验和人物、activation、义务、状态命名空间、场景因果业务校验均在签发提交许可前完成。
- 计划提交、最终用量和唯一 initial DRAFT 创建处于同一 Room 事务；精确重放幂等。
- 有限 registry 只注册 final commit、context assembly、chapter plan v2；其余 route 全部失败关闭。

## 最小验证

- `:data:testDebugUnitTest`：6/6。
- `:feature:generation:testDebugUnitTest`：26/26。
- 参数化负例：schema、人物、activation、义务、上下文/计划证据错配全部拒绝。
- 计划规范哈希错配：零 DRAFT；正确提交及 replay：始终一个 DRAFT。
- Android API 30、35 模拟器：事务用例各 1/1。
- 模块边界：10 模块、无环、唯一 feature 例外仍为 template→creation。
- 安全扫描：通过；真实 Provider 调用 0。

## 提交

- `ad404ae` 冻结 v2 来源。
- `5585e39` 扩展 v2 bound route。
- `0d7b92e` 构建 v2 请求。
- `bb08ee1` 修正策略清单自绑定。
- `1bdf4dc` 原子提交计划与 initial DRAFT。
- `79219da` Fake 严格执行与解析。
- `ca1b2e0` 有限 registry。
- `d3c8baa` 计划哈希零提交负例。

## 风险与下一步

- 当前生产 registry 已获得 v2 执行接口，但总 runner 仍未完成；由 TASK-128 统一接线，不在本任务扩展。
- initial DRAFT 仅被可靠创建，尚未生成正文；下一任务为 TASK-126。
- 本轮末 GitHub 网络不稳定；未同步提交保留在本地 Git，需继续重试推送。
