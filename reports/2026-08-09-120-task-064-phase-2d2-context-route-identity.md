# 工作汇报 120：TASK-064 Phase 2D2 context route identity

日期：2026-08-09  
项目：织卷 Android（app开发2）  
唯一项目根目录：`D:\gptuser\projects\ai-novel-reader-app2`

## 本阶段目标

让已经存在的纯本地 `ASSEMBLE_CONTEXT` Stage 获得唯一、严格、可失败关闭的 route identity，同时保持 executor registry 不执行它，为下一阶段 exact-token 本地接线提供可信输入。

## 完成内容

- `ChapterContextAssemblyJobFactory` 只给 context Stage 写入 `zhijuan.chapter-context-assembly-source.v1`；chapter-plan successor 不继承该身份。
- 新增唯一 `parseAndVerify`，由 context repository 和 route resolver 共用，删除两套解析规则漂移的风险。
- parser 严格验证 Stage phase/target/maxAttempts、root/context 精确键集合、版本/schema、空依赖、预算边界、prompt binding hash、progression evidence 自哈希、chapterId/chapterIndex 以及完整 input hash。
- route enum 新增 `CHAPTER_CONTEXT_ASSEMBLY_V1`；resolver 只有完整 parser 成功时才返回该 route。
- Phase 2C3 registry 白名单仍只有 `FINAL_CHAPTER_COMMIT_V3`；新增 context route 被显式拒绝，不会触发 assembly、Attempt、Provider 或状态写入。
- Sol 额外加固 chapter index 交叉验证，并把解析结果的预算字符串也改为脱敏。

## DeepSeek 运行

- run：`20260809-020430-8f974ec6`
- 模型：DeepSeek V4 Flash
- 推理：max
- 硬上限：30 分钟；实际约 20 分 37 秒，正常完成
- Token：total 5,948,170；cached input 5,513,600；output 88,767；reasoning 54,170
- 写入范围：任务包授权的 5 个文件
- 权限请求：0

DeepSeek 正确识别到新增 enum 会让 feature registry 的穷举 `when` 无法编译，但没有越过任务边界修改 feature。Sol 审查后补充显式未注册分支，没有把 context route 加入白名单。

## 验证

- `core:database` JVM 全量：86/86。
- Sol 加固后 factory+resolver 定向 JVM：19/19。
- `feature:generation` 正式 Kotlin 与 AndroidTest Kotlin：编译通过。
- API 35 `core:database` Android 全量：214/214；加固后 context 定向：5/5。
- API 30 `core:database` Android 全量：214/214；加固后 context 定向：5/5。
- 全部为 0 失败、0 错误、0 跳过。
- App 内真实 Provider：0；Fake Provider：0；物理设备写入：0；Git remote：空。

## 审查结论

本阶段实现可靠，可以作为 Phase 2D3 的输入。route identity 与执行权限仍然分离：现有 `ChapterContextAssemblyRepository.assemble` 只接 Stage token，尚不能证明调用方仍持有 Phase 2B 快照中的 exact Job token，因此不能在本阶段注册。

## 下一步

Phase 2D3 增加 context exact-token bound adapter：同时重验 Job token、Stage token、current cursor、同 owner、状态和时效，再调用现有 assembly 事务；随后为 registry 正向注册、陈旧 token、cursor 变化、replay 和双 API real Room 场景补测试。

TASK-064 仍为进行中。
