# 工作汇报 71：持久最终候选 artifact 严格恢复

> 日期：2026-08-04  
> 阶段：TASK-059 第八阶段  
> 结论：本阶段完成；完整 TASK-059 仍在进行中

## 1. 本阶段完成内容

新增 `ChapterFinalCandidateArtifactRecoveryCoordinator`，为应用进程重启后的最终候选恢复建立唯一的受保护文件入口：

- 无论 evidence 输入顺序如何，都固定按 BODY→MEMORY→TRACKING→CONSISTENCY 读取；
- 四种角色必须各且仅各一份，缺失或重复时在任何明文读取前失败；
- 生产入口只使用 `AndroidProtectedArtifactStore.readBytes(...).use` 与 `withBytes`，关闭 lease 后由既有安全存储清零解密数组；
- 每份 artifact 都核对引用 ID、`STREAM_DRAFT` 类型、revision 和原始 SHA-256；
- BODY 使用严格 UTF-8、非空和 4 MiB 上限，规范 hash 必须等于原始正文 hash；
- MEMORY、TRACKING、CONSISTENCY 各使用 512 KiB 上限，并复用现有严格 Parser，不新增宽松 JSON 旁路；
- 三类结构化模型的 `contentHash` 必须等于持久 evidence 的 canonical hash；
- 恢复结果只保留正文 String 和三类解析模型，不保留 artifact ByteArray；异常和默认字符串不拼接正文、JSON 或解析报告。

本阶段不读取 Stage/Attempt 数据库行、不生成派生数据库草稿、不调用最终提交映射器、不执行 COMMIT。

## 2. DeepSeek 执行情况

本阶段采用新的只读补丁提案流程：

- 运行 ID：`20260804-093808-5484eae4`；
- 推理强度：`max`；
- 最长时间：25 分钟；
- 总 Token 上限：按用户要求不设置；
- 实际耗时：约 20 分 21 秒，未触发超时；
- 总处理 Token：2,657,865；缓存输入 2,034,560；输出 150,056；推理输出 124,946；
- 沙箱：`read-only`，DeepSeek 没有项目写权限，也没有请求修改权限；
- 交付：一个包含两个新文件的完整补丁提案；没有直接修改工作区；
- 织卷 App 真实 Provider API、物理设备写入和外部付费调用均为 0。

这次结果证明：对需要读取多套既有契约的任务，适当延长时间可以换来完整代码提案，而不是只有中途思考。但 265 万级处理量仍然偏高，后续继续缩小单包职责。

## 3. Sol 审查与修正

Sol 逐项检查提案后使用本地 `apply_patch` 应用，没有把模型说明当成完成证据。

第一次定向测试能够编译，5 项中 4 项通过、1 项失败。失败原因是测试 fake lease 直接清零了调用方后续还要复用的同一个 ByteArray，使第二次恢复读到已清空的夹具；生产恢复逻辑没有失败。Sol 将 fake reader 改为保存和租借独立副本，使清零只作用于本次 lease，符合真实 store 的语义，然后强制重跑。

审查还确认：

- 没有数据库、网络、Provider 或状态机调用；
- 没有复制最终提交仓库；
- 三种结构化输出均走现有严格 Parser；
- 错误文本不包含 canary、正文或 JSON；
- `git diff --check` 无新增格式错误。

## 4. 测试证据

定向 JVM 强制重跑：

- 测试：5；
- 失败：0；
- 错误：0；
- 跳过：0。

覆盖乱序输入、固定读取顺序与大小上限、缺失/重复角色、descriptor 引用/类型/revision、原始 payload 篡改、无效 schema、canonical hash 替换和脱敏。

统一离线门禁：

- Gradle 371 项任务通过；
- 安全扫描通过；
- Android 备份排除策略通过；
- 真实织卷 Provider API：0 次；
- 物理设备写入：0 次。

## 5. 尚未完成

当前恢复器解决的是“四个受保护文件如何安全恢复成可信模型”，还没有解决“进程重启后从哪些数据库行恢复完整映射参数”。完整 TASK-059 下一步仍需：

1. 从持久 Stage、Attempt、冻结请求和候选路线证据恢复 book ID、模型快照、场景契约、候选 hash 历史、修订上限和最终提交时间；
2. 使用现有三个 persistence mapper 把恢复模型重建为派生数据库草稿；
3. 只通过 `ChapterFinalCandidateCommitDraftMapperV1` 组装最终草稿；
4. 接入最终 COMMIT Stage，并补齐崩溃恢复、lease 过期、并发、事务失败和精确 replay 证据；
5. 完整 TASK-059 收口时再跑 API 30/API 35 全量、Release/R8 和发布级门禁。

生产 `AndroidProtectedArtifactStore` 适配路径已经编译，但本阶段没有新增 Android instrumentation；不能把 JVM fake reader 证据描述成真实设备加密文件验收。
