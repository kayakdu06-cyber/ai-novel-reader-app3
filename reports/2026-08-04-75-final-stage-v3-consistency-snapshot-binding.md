# 工作汇报 75：final Stage v3 一致性快照绑定

> 日期：2026-08-04  
> 阶段：TASK-059 第十阶段 B2  
> 结论：本小阶段完成；完整 TASK-059 仍在进行中

## 1. 本阶段完成内容

ACCEPT 路线的最终 `COMMIT_CHAPTER` Stage 来源封套由 v2 升级为 v3。上一阶段的最小一致性映射快照现在不再只存在进程内，而是作为真正的嵌套 JSON object 与以下证据共同冻结：

- 原一致性请求 `sourceBindingHash`；
- 快照 canonical 内容 SHA-256；
- 候选版本、正文 hash、revision、完整候选历史；
- CONSISTENCY predecessor、有限策略 route binding；
- expected current version 和最大自动修订数；
- 覆盖完整外层 JSON 的 Stage input hash 与 idempotency key。

生产协调器只在策略结果为 `AcceptCandidate` 时创建快照。自动修订和需要用户处理路线必须保持快照与快照 hash 同时为空，避免把尚未接受的候选伪装成可提交候选。

## 2. 大小与模块边界

`core:database` 不能反向依赖 `feature:generation`，因此采用两层验证：

- feature codec 负责完整解析本地报告、expectation、场景契约和所有跨对象关系；
- database 封套负责 exact 根键、schema、请求 binding、快照 hash、嵌套 object 类型、体积和 final Stage input hash。

快照上限从 65,536 收紧为 49,152 UTF-8 字节；final Stage 完整外层仍不得超过 65,536 字节。快照直接嵌套，不作为转义字符串二次编码，避免无谓放大。

恢复仓库和最终提交仓库都会把 v3 中的请求 binding 对回实际 CONSISTENCY seal 的 `sourceBindingHash`。v2 或缺失新字段的来源不会被兼容猜测，而是失败关闭。

## 3. DeepSeek 执行与 Sol 审查

DeepSeek 运行：

- 运行 ID：`20260804-125445-5e4ed578`；
- `DeepSeek-V4-Flash`、`max` 推理、只读补丁提案、15 分钟、无总 Token 上限；
- 实际约 4 分 44 秒，退出码 0；
- 总处理 121,584 Token，缓存输入 53,760，输出 39,753，推理输出 33,833；
- 完整返回一个数据库文件补丁，没有请求权限、没有写项目或运行测试。

DeepSeek 的提案主体直接采用。Sol 继续完成任务包明确禁止 DeepSeek 修改的部分：

1. 将 feature 生产协调器接到快照 capture/hash；
2. 将 codec 上限与 final Stage 外层余量对齐为 49,152 字节；
3. 在恢复仓库与最终提交仓库增加 CONSISTENCY request binding 复核；
4. 更新 Android fixture，并新增缺快照和绑错请求两个原子失败用例。

本轮没有发现 DeepSeek 提案中的编译错误；它对单文件边界、版本拒绝和脱敏处理均符合要求。

## 4. 验证证据

- `core:database`、`feature:generation` 生产编译与数据库 Android 测试编译通过；
- feature 相关 JVM 15 项通过：快照 codec 7 项 + 一致性接受/路由规划 8 项；
- API 35 `emulator-5554` 最终候选数据库专项：25/25；
- 新负例证明：
  - ACCEPT 缺少快照时 final Stage 不创建，CONSISTENCY Stage 保持可恢复，正式章节不发布；
  - 快照绑定另一请求时，即使重新计算快照 hash，也不能创建 final Stage；
- 成功路径证明 final Stage 保存的是嵌套 object，恢复出的快照 hash 与内容一致，request binding 等于 CONSISTENCY seal；
- 统一离线门禁：371 项 Gradle 任务通过；
- 安全扫描：`SECURITY_SCAN_OK`；
- 备份排除：`BACKUP_EXCLUSION_POLICY_OK`。

设备列表只有 API 35 模拟器 `emulator-5554`；没有实体设备安装或写入。真实 Provider/API 调用为 0。

## 5. 尚未完成

final Stage v3 已经具备重启恢复所需的全部来源材料，但还缺唯一的本地最终执行入口。下一阶段需要：

1. 从恢复仓库取得持久链与三份模型快照；
2. 通过受保护 artifact 恢复器取得正文和三类严格结构化输出；
3. 严格解析 v3 映射快照；
4. 只调用现有 memory/tracking/consistency persistence mapper 与最终草稿 mapper；
5. 携带当前 COMMIT lease 调用现有最终事务仓库，继续由事务内证据复核决定是否发布。
