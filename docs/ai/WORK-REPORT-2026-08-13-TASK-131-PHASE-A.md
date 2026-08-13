# TASK-131 阶段 A 工作报告：本地阅读投影与首批连续生成

## 完成内容

- 扩充书架只读合同：书籍/章节状态、目标章节数、活动生成 Job 与生成状态摘要。
- 目录同时显示已落库章节和当前滚动规划窗内的待生成章节。
- 正式正文始终优先读取当前 `ChapterVersion`；生成中正文只能通过受保护草稿投影读取并明确隔离。
- 增加安全的继续生成合同，恢复持久 Job 后重启原前台生成服务。
- 修复生产链第一章后停止的问题：仅在上一章 Job 完成、正式版本已提交且规划节点存在时准备下一章。
- 将既有有界章节循环接入生产前台路径，单批最多连续完成第 1～5 章，不生成全书骨架。

## 验证证据

- `:core:test :data:compileDebugKotlin :feature:library:testDebugUnitTest :feature:reader:testDebugUnitTest --offline`：通过。
- `:data:compileDebugKotlin :feature:generation:compileDebugKotlin :feature:generation:testDebugUnitTest --offline`：通过。
- 两台模拟器执行 `GenerationPersistentChapterSequenceAndroidTest#fourChaptersPauseAndRestartWithoutLosingReadableStateOrOpeningProviderTwice`：各 1/1 通过。
- 持久化用例同时验证：暂停章不能越权准备后章；第 4 章正式提交后可幂等准备第 5 章；第 5 章保持 `PLANNED/READY Job`，尚未打开 Provider。
- 真实 App Provider 调用：0。

## 下一阶段

实现 `:feature:library` 书架页面与状态刷新，然后实现 `:feature:reader` 的目录、正文和暂停/继续/停止界面，最后由 `:app` 负责导航组装。
