# 工作汇报 73：最终候选持久恢复快照

> 日期：2026-08-04  
> 阶段：TASK-059 第十阶段 A  
> 结论：本阶段完成；完整 TASK-059 仍在进行中

## 1. 本阶段完成内容

新增 `ChapterFinalCandidateRecoveryRepository`。进程重启后，调用方不再按 phase 或时间猜测哪几条数据库记录属于最终候选，而是从 final `COMMIT_CHAPTER` Stage v2 的唯一 predecessor 开始，在同一个只读 Room 事务中反向恢复：

```text
BODY → MEMORY → TRACKING → CONSISTENCY → FINAL
```

恢复时逐项验证：

- final Stage、Job、Book、Chapter、chapter index 和当前状态互相一致；
- 四段候选 Stage 同 Job、同章节、均已成功，输入 predecessor 和封存输出 next Stage 形成唯一连续链；
- 候选版本、正文 hash、revision index 与 final v2 来源完全相同；
- 初始正文必须来自 `DRAFT_CHAPTER`；修订正文必须来自 `REVISE_CHAPTER`，且上一版 hash 等于冻结候选历史的上一项；
- 每段 evidence 都指向该 Stage 最后一次成功 Attempt，input/output/artifact ref 未变化；
- 每段 Usage 属于同一本书且已经 `FINAL`；
- MEMORY、TRACKING、CONSISTENCY 三份模型快照是非空、限长、严格 JSON object。

返回值只包含最终执行器后续需要的脱敏不可变快照：final/source 元数据、固定顺序的四类 artifact evidence 和三份模型快照；它不包含 Entity 或 ByteArray，也不是最终提交许可。`ChapterFinalCandidateCommitRepositoryV1` 的事务内独立复核保持原样，因此恢复读取与正式写入之间发生竞态时仍会失败关闭。

现有封存输出 Parser 被整理为同模块可复用的内部结构，完整保留 attempt/artifact/hash 字段，默认字符串继续隐藏证据内容。Provider-open guard 的行为未改变。

## 2. DeepSeek 执行与拆分效果

第一次任务包同时要求生产仓库、Parser 调整和 Android 测试：

- 运行 ID：`20260804-113133-75c55ea2`；
- `max` 推理、只读补丁提案、25 分钟、无总 Token 上限；
- 到 25 分钟由安全门禁终止，退出码 124；
- 总处理 6,431,937 Token，缓存输入 5,582,464，输出 154,190，推理输出 109,517；
- 原因：反复读取大文件时多次输出截断，仍停留在取上下文阶段；没有最终回交、没有可应用补丁、没有项目写入，也没有残留子进程。

Sol 随后先把 Parser 复用接口局部整理并通过编译，再把 DeepSeek 工作缩为“只新增一个恢复仓库文件”：

- 运行 ID：`20260804-120056-99a23b24`；
- `max` 推理、只读补丁提案、15 分钟、无总 Token 上限；
- 实际约 5 分 20 秒，正常退出 0；
- 总处理 1,082,242 Token，缓存输入 1,000,192，输出 39,596，推理输出 29,422；
- 返回一个完整单文件补丁，没有请求修改权限、没有运行测试或写项目。

结论：仅延长大任务时限并不稳定；把大文件读取和测试拆出、给出冻结接口、让 DeepSeek 一次只交付一个生产文件，明显更有效。

## 3. Sol 审查与修正

DeepSeek 的总体结构被采用，但原提案不能直接编译或正确处理初始候选。Sol 修正：

1. 把调用 suspend DAO 的 `loadSealedStage`、`verifyAttempt`、`verifyUsage` 改为 suspend；
2. 初始 `DRAFT_CHAPTER` 没有 candidate revision binding，不能调用 `ChapterCandidateStageBindingV1.parseAndVerify`；改为只在派生 Stage 和 REVISE BODY 解析该 binding；
3. 新增真实 Chapter 读取，确保 chapter 的 book/index 与 final Job/source 一致；
4. 合并重复判断并保持静态脱敏错误；
5. 增加完整一次修订后恢复测试，验证旧 hash、route binding 和四段新候选链。

这些修正说明 DeepSeek 的单文件提案已具有较高利用率，但仍必须由 Sol 编译、检查状态机并覆盖缺失分支，不能直接视为完成代码。

## 4. 验证证据

- `:core:database:compileDebugKotlin`：通过；
- `:core:database:compileDebugAndroidTestKotlin`：通过；
- API 35 模拟器 `emulator-5554` 最终候选数据库专项：23/23；
- 成功路径覆盖 revision 0 和 revision 1 完整链；
- 失败路径覆盖损坏模型快照和被篡改的 sealed next Stage，失败后正式版本、summary、report 均为空，final Stage/Job 不前进；
- 统一离线门禁：371 项 Gradle 任务通过；
- 安全扫描：`SECURITY_SCAN_OK`；
- Android 备份排除：`BACKUP_EXCLUSION_POLICY_OK`；
- `git diff --check` 无新增格式错误，仅有工作区既有的 LF/CRLF 提示。

设备列表中只有 `emulator-5554`。没有实体设备连接、安装或写入；真实 Provider/API 调用为 0。

## 5. 尚未完成

数据库恢复快照已经提供 book、final source、四段 evidence 和三份模型快照，artifact 恢复器也能提供正文、memory、tracking、consistency 四个严格模型。最终执行器仍缺少把一致性报告重新映射为数据库草稿所需的：

- 本地一致性报告；
- `ChapterConsistencyExpectation`；
- `ChapterSceneConsistencyContractV1`；
- 一个统一的最终提交时间与有效 COMMIT lease。

下一阶段先审计这些输入能否从现有冻结请求/持久事实确定性重建；如果数据库当前只保存 hash 而没有足够原文，必须补最小来源封套，不能猜测，也不能创建第二套绕过现有 persistence mapper 的映射逻辑。
