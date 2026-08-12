# TASK-121 第一阶段任务包：Writing Policy Pack 核心合同

## 1. 任务身份

- 任务：TASK-121
- 基线提交：`7951066`
- 唯一项目根目录：`D:\gptuser\projects\ai-novel-reader-app3`
- 产品代码模块锁：仅 `:core`
- 执行模式：只输出补丁草案，不直接写入工作树
- 推理强度：最高
- 总 token：不设上限
- 建议最长运行：25 分钟

## 2. 开始前必须读取

1. 根目录 `AGENTS.md`
2. `docs/24-AI-DEVELOPMENT-PROTOCOL.md`
3. `docs/ai/CURRENT-CONTEXT.md`
4. `docs/28-WRITING-POLICY-PACK-SPEC.md`
5. `docs/30-IMPLEMENTATION-BACKLOG-V2.md` 的 TASK-121 段落
6. `core/build.gradle.kts`
7. `core/src/main/kotlin/app/zhijuan/core/task/PromptBundleContract.kt`
8. `core/src/main/kotlin/app/zhijuan/core/model/GenerationPhase.kt`

## 3. 已知工作树边界

- 用户或其他工具拥有的未提交内容：`AGENTS.md`、`.claude/`、`CLAUDE.md`。
- 不得读取密钥，不得修改、暂存或删除上述内容。
- 不得修改 `PromptBundleContract.kt` 或已有 `PromptBundleCatalogV1`。依赖分析显示改动该符号会影响 42 个直接调用点、111 个总节点。
- 不得修改 `:data`、`:provider`、`:feature-*`、`:app`、构建脚本或其他项目。

## 4. 目标

在新的独立文件中实现 Writing Policy Pack v1 的纯 Kotlin、不可变核心合同，以及面向现有 PromptBundle v1 的最小适配引用：

1. 策略片段元数据至少覆盖：
   - 稳定片段 ID；
   - 适用生成阶段；
   - 必需与禁止能力；
   - 明确的优先级；
   - 字符预算；
   - 结构化输出字段；
   - 指令内容。
2. 策略包至少覆盖：稳定包 ID、schema/version、locale、片段、校验器 ID、总预算及规范 SHA-256。
3. 规范哈希必须：
   - 使用明确域标记和无歧义长度前缀；
   - 覆盖所有会影响提示词语义的字段；
   - 对集合型元数据规范排序；
   - 输出小写十六进制 SHA-256；
   - 同一输入稳定得到同一值。
4. 优先级冲突声明必须可验证；优先级相同或低优先级片段被声明为胜者时应拒绝。
5. 最小 PromptBundle v1 适配结果只携带版本、schema、策略包身份/哈希和选中片段 ID，不复制或编译提示词正文。
6. 未知策略包版本、未知片段、错误 PromptBundle 版本或 schema 必须快速失败。
7. 所有 `toString()` 都不得暴露指令正文或其他提示词内容。
8. 对传入集合做防御性快照，避免调用方后续修改污染合同。

## 5. 建议文件范围

只允许提出下列两个新文件的补丁；若认为命名需微调，可在最终说明中提出，不得扩大范围：

- `core/src/main/kotlin/app/zhijuan/core/task/WritingPolicyPackContract.kt`
- `core/src/test/kotlin/app/zhijuan/core/task/WritingPolicyPackContractTest.kt`

## 6. 明确不做

- 不实现 PolicyCompiler、PolicyRouter、运行时策略装载、数据库、迁移、UI、远程模型或 Skill 导入。
- 不内置完整写作规则正文；本任务只交付可靠合同和适配边界。
- 不调用任何真实 Provider 或外部付费 API。
- 不重构既有 PromptBundle、预算、队列或生成流水线。
- 不增加依赖。

## 7. 最小测试

必须包含且只保留具有合同价值的离线测试：

1. 同一语义输入得到相同规范哈希；
2. 未知版本与未知片段被拒绝；
3. 至少一个错误优先级冲突被拒绝；
4. `toString()` 不包含测试用敏感指令正文。

可以在同一测试中顺带验证防御性快照，不要扩展大而全的测试矩阵。

## 8. 输出要求

- 先概述合同设计和主要取舍。
- 输出可由 `git apply` 使用的 unified diff，只包含两个新文件。
- 给出建议验证命令：`gradlew.bat :core:test` 与 `gradlew.bat :core:compileKotlin`。
- 若发现必须跨模块才能完成，停止并说明，不得自行扩大范围。
