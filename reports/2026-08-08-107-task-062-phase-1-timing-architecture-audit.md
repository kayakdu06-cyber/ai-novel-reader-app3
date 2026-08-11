# 工作汇报 107：TASK-062 Phase 1 脱敏生成时序架构复核

> 日期：2026-08-08  
> 项目：织卷 Android App  
> 唯一仓库：`D:\gptuser\projects\ai-novel-reader-app2`

## 1. 本阶段结论

TASK-062 采用以下唯一主方案：

1. 在正式 SQLCipher 数据库中新增 append-only 的逐事件生成时序账本，作为性能报告的唯一权威来源；
2. 现有 `EncryptedDiagnosticStore` 继续承担 512 条/512 KiB 的低敏故障窗口，不扩成长期性能数据库；
3. 时序契约使用独立的有限 milestone 枚举，不直接复用当前 `GenerationPhase`。现有业务枚举把记忆与故事追踪共用 `EXTRACT_MEMORY`，无法产生可信的分阶段报告；
4. 持续时间只用 Android `elapsedRealtime` 计算；epoch 只用于展示。每条事件同时绑定 boot 指纹，同 boot 才允许相减，跨 boot 或证据缺失直接返回“不可计算”；
5. 数据库、报告和 `toString` 中不保存正文、人物名、提示词、端点、provider request id、密钥、异常自由文本或原始内部 ID/hash。关联只保存域分离指纹；
6. 第一版报告必须能独立计算排队、本地准备、Provider 到首字节、Provider 到首个完整段落、正文流、记忆、追踪、一致性、可选修订、正式提交和总耗时。

## 2. 对 DeepSeek 建议的复核

本次只读审计：

- Run ID：`20260808-184643-6858d55d`
- 模型：DeepSeek V4 Flash
- 推理强度：`max`
- 允许时长：25 分钟
- 实际用时：约 7 分 54 秒
- 累计 Token：711,706
- 缓存输入：525,568
- 输出 Token：54,777
- 退出码：0
- 权限请求：0
- 工作树代码改动：0
- App 内真实 Provider 调用：0

已采纳：

- SQLCipher 正式逐事件表，而不是扩大滚动诊断；
- epoch 与 monotonic 双时间证据；
- 同 boot 才计算 duration，跨 boot 不猜测；
- append-only、精确 replay、迟到/冲突事件失败关闭；
- 段落判定只保留有限状态和计数，不落正文；
- v15→v16 正式迁移和 TEST-093 canary 测试方向。

未原样采纳并由 Sol 修正：

1. DeepSeek 的事件集合缺少 `STAGE_STARTED`，其 `STAGE_QUEUED → LOCAL_CONTEXT_READY` 实际是“排队＋本地准备”，不能单独计算排队；正式契约将补齐两段。
2. DeepSeek 只有单一 `FORMAL_COMMIT`，却声称可报告提交耗时；正式契约补 `COMMIT_STARTED → FORMAL_COMMIT`。
3. DeepSeek 把 `LOCAL_CONTEXT_READY → BODY_STREAM_END` 命名为 body duration；正式报告改为 `PROVIDER_OPENED → BODY_STREAM_ENDED`，本地准备独立计算。
4. DeepSeek 要求四段派生全部存在，等同于强迫正常章必须修订；正式报告把 MEMORY/TRACKING/CONSISTENCY 设为正常必需，REVISION 仅在实际开始后计入。
5. DeepSeek 的“不可备份 boot 文件”无法自行判断设备已经重启；正式时钟优先使用 Android boot count 生成域分离 boot 指纹，读取失败时退化为进程会话指纹，使跨进程 duration 保守不可用。
6. 不采用调用方任意 UUID 作为 replay 身份；事件 key 将从有限 milestone 与域分离关联指纹确定性生成，避免同一逻辑事件用另一 UUID 重复写入。
7. 本阶段不需要为通用诊断新增大量 timing code；故障关联的最小扩展只有出现实际消费者时再做，避免 TASK-062 变成通用诊断重写。

## 3. 最小事件集合

- `CHAPTER_REQUESTED`
- `STAGE_QUEUED`
- `STAGE_STARTED`
- `LOCAL_CONTEXT_READY`
- `PROVIDER_OPENED`
- `FIRST_BYTE`
- `FIRST_FULL_PARAGRAPH`
- `BODY_STREAM_ENDED`
- `MEMORY_STARTED` / `MEMORY_ENDED`
- `TRACKING_STARTED` / `TRACKING_ENDED`
- `CONSISTENCY_STARTED` / `CONSISTENCY_ENDED`
- `REVISION_STARTED` / `REVISION_ENDED`
- `COMMIT_STARTED`
- `FORMAL_COMMIT`
- `NEXT_CHAPTER_STARTED`

开始事件不伪造结果；结束事件使用有限 outcome。字符/token 只保存非负计数，连接/模型只保存域分离指纹。

## 4. TASK-062 实施边界

TASK-062 将交付：

- 时序领域契约、确定性哈希工厂和基准时钟；
- v16 正式表、迁移、不可变与 replay 约束；
- 报告器及缺事件/跨 boot/时钟回拨三态结果；
- 流式正文首字节、首完整段落和流结束的 Fake 路径接线；
- TEST-093 隐私 canary、公式、迁移、replay 和错误顺序测试；
- 双 API 离线验证与阶段工作汇报。

不属于本任务：

- TASK-063 固定延迟/慢流 Fake 性能夹具；
- TASK-064 total runner 自动调度与完整生产阶段接线；
- TASK-065 生成中正文 UI；
- TASK-066 5/10 分钟 watchdog；
- 真实模型速度测试或任何真实生成费用。

## 5. 安全与现场

- 唯一 Git 根目录已确认是 `D:/gptuser/projects/ai-novel-reader-app2`；
- 大量 TASK-059～061 累计 WIP 原样保留；
- DeepSeek 使用只读沙箱，无代码写入、无权限请求；
- 没有访问或修改原项目、物理设备、密钥或真实生成接口；
- 下一阶段直接实现时序领域契约、v16 存储与报告器，不等待用户再次确认。
