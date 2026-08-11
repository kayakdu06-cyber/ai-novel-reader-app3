# 工作汇报 74：一致性映射恢复快照 Codec

> 日期：2026-08-04  
> 阶段：TASK-059 第十阶段 B1  
> 结论：本小阶段完成；完整 TASK-059 仍在进行中

## 1. 本阶段完成内容

新增 `ChapterFinalConsistencyMappingSnapshotV1` 与 `ChapterFinalConsistencyMappingSnapshotCodecV1`，补齐最终提交在进程重启后无法仅靠 hash 恢复的一组最小输入：

- 原一致性请求的 source binding hash；
- 本地确定性一致性报告；
- `ChapterConsistencyExpectation`；
- `ChapterSceneConsistencyContractV1`；
- 最小正文码点数、已用修订次数和当前 Stage 最大尝试数。

快照只能从已经绑定的 `BoundChapterConsistencyCheckRequest` 与同一候选的 routing spec 创建，不允许调用方重新手填候选身份。创建前核对候选版本、章节、正文 hash、章节序号和正文码点数；创建后立即用同一严格解析器复验，避免内存对象绕过持久格式约束。

快照明确不保存章节正文、人物名称、证据 payload、提示词、API 信息或模型输出正文。默认字符串只显示章节序号、修订次数和本地问题数量。

## 2. 持久格式门禁

Codec 使用 schema `zhijuan.chapter-final-consistency-mapping.v1`，具备以下失败关闭约束：

- 根、本地报告、问题、expectation 和场景契约均要求 exact keys；
- 字符串、整数、布尔值、枚举和真正 JSON null 分类型读取，不接受字符串伪装的 `"null"`、数字或布尔值；
- hash 只能是小写 SHA-256，ID 只能使用受限字符和长度；
- 检查标准按 enum 顺序，三个 ID 集合按字典序，无重复；
- 本地报告正文 hash/码点数必须等于 expectation；
- expectation 的场景 hash、检查标准和关键过程节点必须等于场景契约；
- 本地报告必须包含来源完整性和基础可读性检查；
- 完整 JSON 最多 49,152 UTF-8 字节，为它嵌入 65,536 字节上限的 final Stage 外层封套保留余量；
- `contentHash` 对严格解析后的 canonical 重编码计算，因此合法但根键顺序不同的输入得到同一内容 hash。

这份快照只恢复现有 persistence mapper 的输入，不创建第二套一致性报告或绕过原映射器。

## 3. DeepSeek 执行与 Sol 审查

DeepSeek 运行：

- 运行 ID：`20260804-122218-5da343d9`；
- `DeepSeek-V4-Flash`、`max` 推理、只读补丁提案、15 分钟、无总 Token 上限；
- 实际约 7 分 30 秒，退出码 0；
- 总处理 1,134,042 Token，其中缓存输入 1,021,568，输出 56,407，推理输出 42,296；
- 完整返回一个新增生产文件的补丁，没有请求修改权限、没有写项目、没有调用测试或网络。

Sol 审查后采用主体设计，并修正两点：

1. 为 criteria 的通用 enum 解码补显式泛型，解决 Kotlin 编译器无法推断 reified 类型的问题；
2. `capture` 生成 canonical JSON 后立即调用 `parseAndVerify`，使创建路径与恢复路径共享全部字段级校验，而不只做跨对象检查。

DeepSeek 本轮任务范围只有一个生产文件，交付速度和可利用率明显好于之前的大任务，但仍需要编译和测试才能发现上述类型问题。

## 4. 验证证据

- 新增 JVM 专项 7 项，全部通过：
  - 不同输入 Set 顺序生成完全相同快照并可往返；
  - 非 canonical 根键顺序仍基于 canonical 内容得到同一 hash；
  - 未知嵌套字段拒绝且不回显其值；
  - 字符串伪 `null` 和字符串数字拒绝；
  - ID 集合乱序与重复拒绝；
  - 跨对象场景 hash 篡改拒绝；
  - 候选身份错配拒绝，错误和默认字符串不泄露正文、ID 或 hash。
- `feature:generation` 生产与测试 Kotlin 编译通过；
- 项目统一离线门禁：371 项 Gradle 任务通过；
- 安全扫描：`SECURITY_SCAN_OK`；
- Android 备份排除：`BACKUP_EXCLUSION_POLICY_OK`。

本阶段只运行 JVM/构建检查，没有安装或写入任何设备；真实 Provider/API 调用为 0。

## 5. 尚未完成

快照 codec 目前还没有进入数据库来源封套。下一阶段需要：

1. 将快照 JSON、canonical hash 和原一致性请求 binding 写入 ACCEPT 路线的 final Stage 新版来源封套；
2. 让数据库恢复仓库严格验证并返回该快照，旧版或缺失快照失败关闭；
3. 随后由最终执行器恢复四类受保护 artifact、三份模型快照和本快照，复用现有 mapper 构造最终提交草稿；
4. 最终提交仍须由现有事务内仓库重新验证，不把恢复快照当成提交许可。
