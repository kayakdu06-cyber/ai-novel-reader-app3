# 工作汇报 70：最终候选提交草稿确定性映射

> 日期：2026-08-04  
> 阶段：TASK-059 第七阶段  
> 结论：本阶段完成；完整 TASK-059 仍在进行中

## 1. 本阶段完成内容

新增 `ChapterFinalCandidateCommitDraftMapperV1`，把已经过校验的候选正文、候选 hash 历史、MEMORY/TRACKING/CONSISTENCY 派生草稿和四类持久 artifact evidence，唯一组装为 `ChapterFinalCandidateCommitDraftV1`。

映射器在进入最终数据库仓库前完成以下门禁：

- 正文 hash 必须等于当前冻结候选；
- 候选历史长度、末位、格式与去重必须和修订序号一致；
- BODY、MEMORY、TRACKING、CONSISTENCY evidence 必须各一份；
- BODY/MEMORY/TRACKING 输出 hash 必须与正文或派生结果一致；
- 一致性 gate 只能是 `ACCEPT_CANDIDATE`；
- 记忆摘要、追踪投影和一致性报告必须属于同一候选版本与章节序号；
- 记忆、追踪和一致性派生数据必须属于同一本书，派生子行必须属于当前候选版本；
- TRACKING/CONSISTENCY Stage 必须与对应持久 evidence 相同；
- tracking projection 输出 hash 和 combined consistency report hash 必须重新核对；
- 所有派生行必须已经使用最终提交时间，映射器不会静默改写时间；
- 最终草稿中的 CONSISTENCY 输出 hash 来自持久 evidence，不会误用 combined report hash；
- artifact evidence 按固定角色顺序写入，保证重放输入稳定。

该部件只做纯 Kotlin 组装，不读取数据库、不解密 artifact、不调用 Provider，也不执行最终发布。

## 2. DeepSeek 执行情况

本阶段由 Sol 形成严格任务包后交给 DeepSeek V4 Flash 实现：

- 推理强度：`max`；
- 最长时间：从 15 分钟放宽到 30 分钟；
- 总 Token 上限：按用户要求不设置；
- 实际耗时：约 6 分 36 秒；
- 总处理 Token：1,777,543；其中缓存输入 1,672,064，输出 45,518，推理输出 26,526；
- 最终只新增任务包允许的两个文件，没有改动其他业务文件；
- 没有调用织卷 App 内部真实 Provider API。

延长时间证明了此前“只留下分析、没有代码”主要是任务尚未进入写入阶段就被门禁终止。此次在约 3.3 分钟后首次产生文件差异，随后完成测试。

执行过程仍有流程瑕疵：补丁写入失败后，DeepSeek 尝试过临时探针和命令行/Python 写入；安全策略拦截了多次不合规命令，最终探针没有残留，文件范围检查只发现两个允许的新文件。后续任务包将明确“补丁写入失败时停止并回交，不得创建探针或改用脚本写文件”。

## 3. Sol 独立审查

Sol 逐行检查了两个新文件，没有把 DeepSeek 的最终说明当成验收证据。检查结果：

- 目标公开类型和入口与任务包一致；
- 错误信息与 `toString()` 不包含正文、JSON 或 artifact 内容；
- 没有数据库、Android、网络或 Provider 调用；
- 两个文件无尾随空白、无中文乱码；
- 文件时间扫描确认本次运行只触碰允许的两个文件；
- 更深层的数据库行来源和 artifact 内容复算仍由现有最终提交仓库再次验证，不会因映射器遗漏而绕过最终门禁。

## 4. 测试证据

DeepSeek 首次交付的定向测试：4/4 通过。来源加固后由 Sol 扩展为 7 项。

Sol 使用 `--rerun-tasks` 强制独立重跑同一测试类：

- 测试：7；
- 失败：0；
- 错误：0；
- 跳过：0。

统一离线门禁：

- Gradle 371 项任务通过；
- 安全扫描通过；
- Android 备份排除策略通过；
- 真实织卷 Provider API：0 次；
- 物理设备写入：0 次。

## 5. DeepSeek 补丁工具结论

来源加固任务被进一步拆成只读两个文件的小包，总处理量降到约 17.6 万 Token，证明细拆能明显降低消耗；但原生 `apply_patch` 在 Windows restricted-token 沙箱中仍因项目目录与隐式 `D:\tmp` 构成分离可写根而失败。两次配置收敛重试均未产生文件差异，DeepSeek 按纪律停止，没有使用 Python、PowerShell 或探针绕过。

最终采用更安全的固定流程：DeepSeek 在 `read-only` 沙箱中输出精确补丁提案，Sol 审查后使用本地 `apply_patch` 应用并运行测试。启动器新增 `-PatchProposalOnly`，不再把“进程退出 0”或“已有修改设计”当成代码交付。

## 6. 尚未完成

当前映射器需要已经解析好的三类派生草稿，因此还不能单独解决进程重启后的恢复。完整 TASK-059 下一步是：

1. 从持久 BODY/MEMORY/TRACKING/CONSISTENCY artifact 和 Attempt/Stage 证据恢复正文与结构化结果；
2. 使用现有三个 persistence mapper 重新得到派生草稿；
3. 只通过本阶段映射器重建最终提交草稿；
4. 接入最终 COMMIT Stage 并补齐失败、恢复、并发和精确 replay 验收。
