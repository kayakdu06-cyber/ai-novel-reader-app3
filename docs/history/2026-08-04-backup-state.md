# 织卷开发状态备份说明

## 备份定位

- 产品：织卷，单人使用的 Android AI 小说生成与阅读 App。
- 对话任务：`ai阅读`。
- Codex Thread ID：`019fbb83-bfd4-75a2-8999-3e05766481b7`。
- 备份日期：2026-08-04（Asia/Shanghai）。
- 本文件记录的是工作中快照，不代表 TASK-059 已完成。

## 开发进度

- 最近完整完成：TASK-058，章节一致性裁决与呈现检查。
- 当前进行中：TASK-059，有限修订与最终提交门禁。
- TASK-059 已加入有限修订策略、修订请求绑定、候选产物封存和最终候选原子提交等主体实现与测试。
- 当前已知未闭环点：候选阶段来源绑定仍需修正，专项数据库测试在候选记忆提取阶段的来源校验处失败。
- 恢复开发时应先从候选阶段绑定解析、Provider 开启前的候选来源守卫和专项数据库测试继续，不应跳过失败直接宣告 TASK-059 完成。

## 已验证基线

- TASK-058 完成报告：`reports/2026-08-03-61-E5.9-consistency-presentation-check.md`。
- TASK-058 结束时，Android 15 与 Android 11 模拟器上的相关回归和发布门禁均已通过；TASK-059 的新增代码尚未完成同等级全量回归。
- 当前工程的安全扫描与备份排除规则在建立本快照前再次通过。
- 没有为了本次备份调用真实模型 API，也没有操作物理测试手机。

## 对话备份

- 可提交版本：`docs/history/2026-08-04-ai-reading-conversation.md`。
- 可提交版本只保留用户与 Codex 的可见消息，排除了内部推理和工具输出。
- 导出器会遮蔽 API 密钥与令牌形态的内容；提交前还必须再次执行密钥扫描。
- 包含全部工具日志的原始 JSONL 快照只保存在项目仓库之外的本地备份目录，不得推送到远程。

## 恢复环境

项目约定的本地环境位于 `D:\gptuser`：

- JDK：`D:\gptuser\tools\jdk`
- Android SDK：`D:\gptuser\tools\android-sdk`
- Gradle 缓存：`D:\gptuser\cache\gradle`
- Android 用户与 AVD：`D:\gptuser\cache\android-user`
- 项目根目录：`D:\gptuser\projects\ai-novel-reader`

克隆后需要在本机重新创建不入库的 `local.properties`，并把 Android SDK 路径指向本机的 D 盘环境。构建目录、模拟器、缓存、APK、崩溃日志、数据库、密钥文件和机器本地配置均不属于源码备份。

## 安全边界

- 远程仓库必须为私有仓库。
- 真实 API 密钥不得出现在 Git 历史、提交信息、远程地址、工作报告或脱敏对话中。
- 原始对话快照含敏感上下文，只允许本地保存。
- 恢复后首次测试默认使用模拟器和假服务，不自动调用付费 API。
