# TASK-131 任务包：最小书架、目录和阅读器

## 任务身份

- 任务 ID：`TASK-131`
- 仓库：`D:\gptuser\projects\ai-novel-reader-app3`
- 基准：`main` / `1084e8c`
- 执行者：Sol；本次包含跨模块状态与 Compose，不调用 DeepSeek。
- 用户未提交文件：`AGENTS.md`、`.claude/`、`CLAUDE.md`，禁止修改或纳入提交。

## 目标

让用户从生成中页面进入真实本地书架、目录和阅读器：正式章节完成后立即可读，生成中只能显示受保护草稿投影，后台生成不因阅读而停止，并提供暂停、继续和停止入口。

## 审计裁决

TASK-129 的 3～5 章循环目前只有测试端口，生产前台服务在第一章后停止。缺少该接线会直接破坏“边生成边阅读”，因此允许在本任务先做一个限定的 `:data + :feature:generation` 阻断修复：只准备首窗内第 2～5 章并复用唯一 runner，不扩展到全书补窗。

现有 `LibraryRepository` 摘要没有书/章状态、目标章数、入口 Job 或草稿读口。允许 `:core` 增加只读字段和受保护草稿合同，`:data` 只扩充现有只读投影；不改 schema。

## 模块批次

1. `:core + :data`：最小只读书架/目录/草稿合同与查询；无 migration、无写事务。
2. `:feature:generation`：把既有有界章节循环接入生产前台路径，增加安全继续入口。
3. `:feature:library`：状态流和书架 Compose。
4. `:feature:reader`：目录、正式正文、生成中草稿和生成控制 Compose。
5. `:app`：只注入合同、保存书/Job 导航参数并组装页面。

十模块不变；不新增 feature 实现依赖；Compose 不接触 data 实现。

## 不可破坏约束

- 正式正文只来自当前 `ChapterVersion`；草稿只读且明确标记，不进入正式目录身份或后续上下文。
- 后章只在前章 Job=`COMPLETED` 且正式版本存在后准备；一次生产序列最多完成 5 章。
- 阅读页面不打开 Provider；后台仍由现有 FGS、预算、目的地和唯一 runner 控制。
- 暂停后不创建新请求；继续必须恢复持久 Job 后再启动原 FGS；停止不删除已完成章节。
- 不显示 Job ID、hash、Stage、Prompt、密钥或正文日志。
- 列表和正文使用惰性布局；复用已有 20 万字/万章性能证据，不重复压力测试。
- 本任务真实 Provider 调用为 0；TASK-132 才真实联调。

## 最小验收

- library JVM：书架状态优先级、目录排序和刷新状态。
- reader JVM：正式正文优先、无正文为等待、生成中草稿明确隔离。
- Compose：空/生成中书架；正文、目录、上一/下一章、暂停/继续/停止；200% 字号和横屏各一次。
- App：确认成功后进入当前书页，冷重组使用书 ID/根 Job ID 重载；返回可进入书架。
- production continuation：首章完成后只准备连续第 2～5 章，不越过未完成前章。
- 十模块边界、安全扫描和一次 `assembleDebug test`。

## 停止条件

- 若需要数据库 migration、新模块、feature 实现依赖或修改 Provider 协议，停止扩大并记录。
- 若首窗不足 5 章，按实际已规划范围停止；不得伪造章节或一次生成全书骨架。
