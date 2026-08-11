# TASK-059 第七阶段 B：最终草稿来源门禁加固

## 任务身份

- 任务 ID：`TASK-059 / Phase 7B / final draft provenance hardening`
- 仓库根目录：`D:\gptuser\projects\ai-novel-reader-app2`
- 分支/基线：`main` / `8ce774429da1c3f7139a221bc241c34d81a2efdd`
- 当前 WIP：上一阶段已新增最终草稿映射器及 4 项测试，均未提交；必须做最小增量修改，不得重写。
- 模型：DeepSeek V4 Flash，纯文本，`max` 推理

## 运行预算

- 最长运行：20 分钟（用户允许适当延长；完成即停）
- 总 Token 上限：不设置
- 只读业务文件：下方两个允许修改的文件
- 只允许一个定向测试命令
- 停止条件：需要第三个业务文件、补丁工具无法修改现有文件、测试连续失败 2 次、任何范围/隔离异常

## 目标

在现有 `ChapterFinalCandidateCommitDraftMapperV1` 中补齐三个纯本地、低成本的早期来源门禁，使跨书派生数据、被替换的 tracking output hash、被替换的 combined consistency report hash 在进入最终数据库仓库前失败。补充最小负例测试，保持现有 4 项测试通过。

## 允许读取和修改

只允许读取和修改：

1. `feature/generation/src/main/kotlin/app/zhijuan/feature/generation/ChapterFinalCandidateCommitDraftMapper.kt`
2. `feature/generation/src/test/kotlin/app/zhijuan/feature/generation/ChapterFinalCandidateCommitDraftMapperTest.kt`

除了强制规则文档，不得读取其他业务文件。不得递归扫描、不得搜索整个模块或仓库。

## 必须实现

在现有 `map` 中增加静态错误信息的 `require`，至少证明：

1. `memory.summary.bookId`、`tracking.projection.bookId`、`consistency.report.bookId` 完全相等；
2. `memory.entityEvents` 和 `memory.canonFacts`（若存在）的 `bookId` 与共同 bookId 相同，`sourceChapterVersionId` 与当前候选版本相同；
3. `tracking.timelineEvents`、`tracking.newForeshadows`、`tracking.foreshadowTransitions`（若存在）的 `bookId` 与共同 bookId 相同，`sourceChapterVersionId` 与当前候选版本相同；
4. `tracking.projection.outputContentHash == tracking.trackingContentHash`；
5. 对 `consistency.report.issuesJson` 做 SHA-256，结果必须等于 `consistency.reportContentHash`。

不要在此解析报告 JSON，不复制最终数据库仓库的完整 provenance 验证，不改变公开类型和返回字段，不修改现有校验含义。

## 测试要求

在现有测试类中最小新增 3 项负例：

1. consistency report 使用另一 `bookId` 时拒绝；
2. tracking projection 的 `outputContentHash` 与 `tracking.trackingContentHash` 不同时拒绝；
3. consistency `reportContentHash` 与 `issuesJson` 实际 SHA-256 不同时拒绝。

如现有合法 fixture 的 `issuesJson` 与 `reportContentHash` 本来不一致，应只修正 fixture 使其表达真实 hash，不得放松生产校验。

## 文件编辑纪律

- 只能使用 `apply_patch` 修改上述两个已存在文件。
- 禁止创建探针、临时文件、占位文件或第三个文件。
- 禁止用 Python、PowerShell/.NET 写文件、重定向、here-string、`Set-Content`、`Out-File` 或其他命令行写入替代补丁工具。
- 如果 `apply_patch` 失败，立即停止并回交“工具失败”，不要尝试绕过。
- 禁止格式化整个文件、统一换行、reset、checkout、clean 或添加 remote。

## 安全和隔离

- 修改/测试前确认 Git 根精确为 `D:/gptuser/projects/ai-novel-reader-app2`。
- 不读取或输出 API Key、正文 artifact、私人内容。
- 不调用织卷 App 真实 Provider API，不写物理设备，只跑离线 JVM 测试。
- 不宣布完整 TASK-059 完成，不更新状态文档。

## 验收命令

只运行：

```powershell
.\gradlew.bat :feature:generation:testDebugUnitTest --tests "app.zhijuan.feature.generation.ChapterFinalCandidateCommitDraftMapperTest" --offline
```

## 回交

按“完成内容 / 修改文件 / 验证 / 未完成风险 / 需要 Sol 处理 / 假设”返回，并明确报告是否只使用 `apply_patch`、是否读取/修改清单外文件。
