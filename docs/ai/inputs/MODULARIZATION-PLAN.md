# 织卷模块化优化方案

## 一、设计原则

1. **按功能切模块**，不按技术层切 — 团队按功能认领，不按"你写 DAO 我写 UI"分
2. **单向依赖**，功能模块之间不互相依赖，只依赖公共层
3. **可独立编译**，每个功能模块能单独跑测试
4. **可插拔**，删除一个功能模块不影响其他功能

---

## 二、目标模块结构

从当前 3 模块（app / engine / data）拆分为 11 个模块，按功能划分：

```
zhijuan/
├── :core                 ← 公共契约层（所有人依赖，不依赖任何人）
├── :data                 ← 数据基础设施层
├── :provider             ← AI 供应商接入层
├── :feature:connection   ← 功能：连接管理
├── :feature:creation      ← 功能：创建小说
├── :feature:generation    ← 功能：生成流水线
├── :feature:reader        ← 功能：阅读器
├── :feature:library       ← 功能：书架/目录
├── :feature:template      ← 功能：模板系统
├── :app                   ← 壳工程（导航 + 组装）
```

---

## 三、各模块职责和边界

### `:core` — 公共契约层

- **性质**：纯 Kotlin 模块（无 Android 依赖）
- **内容**：
  - `core/model/` — 全局数据模型（`BookStatus`、`GenerationJobStatus`、`BudgetScope`、`ConsistencyIssueCode` 等）
  - `core/task/` — 策略接口和状态机（纯逻辑，不碰数据库）
  - 跨模块通信的 interface 契约（如 `BookCreationGateway`、`GenerationController`、`LibraryRepository`）
- **依赖**：无（不依赖任何其他模块）
- **规则**：所有人都能 import，不能 import 任何模块

### `:data` — 数据基础设施层

- **性质**：Android Library
- **内容**：
  - `database/` — Room 数据库 + SQLCipher（`ZhijuanDatabase`、所有 DAO/Entity）
  - `security/` — 加密存储（Keystore、密钥管理、草稿缓冲）
  - `network/` — HTTP 客户端工厂
  - `diagnostics/` — 诊断日志
- **依赖**：`:core`
- **规则**：只暴露 DAO 和 Repository 接口，不暴露业务逻辑

### `:provider` — AI 供应商接入层

- **性质**：Android Library
- **内容**：
  - `common/` — `ProviderAdapter` 接口、`GenerationRequest`、`ProviderStreamEvent`
  - `openai-chat/` — OpenAI Chat 兼容实现（可扩展为子模块）
  - `transport/` — HTTP 传输、SSE/NDJSON 流解析
  - `fake/` — 本地假模型（测试用）
- **依赖**：`:core`、`:data`（读密钥）
- **规则**：新增 Provider 只加文件，不改其他模块

### `:feature:connection` — 连接管理

- **内容**：
  - 连接向导（选服务 → 填密钥 → 验证 → 选模型）
  - 连接列表 / 编辑 / 删除 / 切换
  - UI：`ConnectionWizardScreen`、`ConnectionListScreen`
- **依赖**：`:core`、`:data`、`:provider`
- **团队**：A 负责

### `:feature:creation` — 创建小说

- **内容**：
  - 极简创建页 + 高级补充项
  - 输入标准化 + 题材识别 + 呈现档位映射
  - 不可变创建快照保存
  - 费用确认页
  - UI：`MinimalBookCreationScreen`、`CostConfirmationScreen`
- **依赖**：`:core`、`:data`
- **团队**：B 负责

### `:feature:generation` — 生成流水线

- **内容**：
  - 13 阶段生成编排
  - 章节草稿流式解码 + 截断续接
  - 一致性检查 + 修订
  - 时间线/伏笔投影
  - 前台 Service + 恢复/维护
- **依赖**：`:core`、`:data`、`:provider`
- **团队**：C 负责（最大模块，可再拆 `generation-core` + `generation-service`）

### `:feature:reader` — 阅读器（新功能）

- **内容**：
  - 章节阅读界面
  - 边生成边阅读预览
  - 暂停/继续/停止控制
- **依赖**：`:core`、`:data`
- **团队**：D 负责

### `:feature:library` — 书架（新功能）

- **内容**：
  - 书架列表 / 目录 / 章节列表
  - 书籍详情
- **依赖**：`:core`、`:data`
- **可独立开发**

### `:feature:template` — 模板系统（新功能）

- **内容**：
  - "按这套设定重新织一本"
  - 模板创建 / 分类 / 版本
- **依赖**：`:core`、`:data`、`:feature:creation`（创建逻辑复用）
- **可独立开发**

### `:app` — 壳工程

- **内容**：
  - `ZhijuanApp` 导航（功能模块间的路由）
  - `ZhijuanApplication`（Hilt 入口）
  - `MainActivity`
  - 资源（主题、图标、strings）
- **依赖**：所有 `:feature:*` 模块
- **规则**：不写业务逻辑，只做组装和导航

---

## 四、依赖关系图

```
                    :app
                 ┌──┴──┐
    ┌─────────────┤     ├─────────────┐
    ▼             ▼     ▼             ▼
:feature:     :feature:  :feature:   :feature:
connection    creation   generation  reader
    │             │         │           │
    │             │    ┌────┤           │
    │             │    ▼    ▼           │
    │             │  :provider         │
    │             │    │               │
    └─────────────┴────┴───────────────┘
                       │
                    :data
                       │
                    :core
```

### 关键约束

- feature 模块之间**不互相依赖**（除 `:feature:template` 依赖 `:feature:creation`）
- 所有 feature 依赖 `:core` + `:data`
- 只有 `:feature:generation` 和 `:feature:connection` 依赖 `:provider`
- `:app` 依赖所有 feature（用于组装）

---

## 五、接口隔离机制

功能模块之间通过 **interface + Hilt 绑定** 通信，不直接引用对方的实现类。

### 跨模块契约（定义在 `:core` 中）

```kotlin
// :core 中定义契约
interface BookCreationGateway {
    suspend fun create(
        draft: MinimalBookDraft,
        connection: SavedConnectionSnapshot,
    ): BookCreationResult
}

interface GenerationController {
    fun startGeneration(bookId: String)
    fun pause(jobId: String)
    fun stop(jobId: String)
    fun observeStatus(jobId: String): Flow<GenerationJobStatus>
}

interface LibraryRepository {
    suspend fun listBooks(): List<BookEntity>
    suspend fun getChapters(bookId: String): List<ChapterEntity>
    suspend fun getChapterContent(chapterId: String): String
}

interface ConnectionRepository {
    suspend fun listConnections(): List<SavedConnectionSnapshot>
    suspend fun selectCurrent(connectionId: String)
}
```

### Hilt 绑定方式

每个 feature 模块在自己的 `di/` 包中提供实现：

```kotlin
// :feature:creation 的 di 包
@Module
@InstallIn(SingletonComponent::class)
abstract class CreationModule {
    @Binds
    @Singleton
    abstract fun bindBookCreationGateway(
        impl: BookCreationGatewayImpl,
    ): BookCreationGateway
}
```

`:app` 只引用 interface，不引用具体类。

### 效果

- 删除 `:feature:template` 模块：从 `:app` 的 `build.gradle.kts` 移除依赖 + 删除导航入口，其他代码零改动
- 新增 `:feature:export` 模块：创建模块 + 在 `:core` 定义接口 + 在 `:app` 注册依赖和导航

---

## 六、新增功能流程

1. 创建 `:feature:xxx` 模块目录和 `build.gradle.kts`
2. 在 `:core` 定义需要的跨模块 interface（如有跨模块调用）
3. 实现功能，在模块 `di/` 包中用 Hilt `@Binds` 注册
4. 在 `:app` 的 `build.gradle.kts` 添加 `implementation(project(":feature:xxx"))`
5. 在 `:app` 的导航中添加入口
6. 其他模块零改动

## 七、删除功能流程

1. 从 `:app` 的 `build.gradle.kts` 移除 `implementation(project(":feature:xxx"))`
2. 从 `:app` 的导航移除入口
3. 删除模块目录
4. 其他模块零改动

---

## 八、团队协作模型

| 团队 | 负责模块 | 可独立工作 | 并行度 |
|------|----------|------------|--------|
| A | `:feature:connection` + `:provider` | 只依赖 `:core` + `:data`，不碰其他功能 | 高 |
| B | `:feature:creation` | 只依赖 `:core` + `:data` | 高 |
| C | `:feature:generation` | 最大模块，建议 2 人（core + service） | 中 |
| D | `:feature:reader` + `:feature:library` | 新功能，从零开始 | 高 |
| 基建 | `:core` + `:data` | 改动需 review，影响所有人 | 低（需协调） |

### 并行开发约定

- 各团队在自己的模块分支工作，不碰其他模块
- `:core` 和 `:data` 的改动通过 PR review 合并，需通知所有团队
- 跨模块接口变更：先在 `:core` 提 PR 定义 interface，各团队再适配
- 每周一次集成编译验证

---

## 九、执行计划

### 前提

从当前精简后的工作副本（3 模块 / 197 文件）出发，每个 Phase 结束后编译验证。

### 分阶段执行

| Phase | 内容 | 预计工作量 | 输出 |
|-------|------|------------|------|
| Phase 1 | 抽取 `:core` — 把 model + task 策略 + 状态机 + 跨模块 interface 移到独立模块 | 1 天 | `:core` 模块可独立编译 |
| Phase 2 | 抽取 `:provider` — 把 provider 相关代码从 app 移到独立模块 | 1 天 | `:provider` 模块可独立编译 |
| Phase 3 | 抽取 `:feature:connection` — 移动连接相关 UI + Gateway | 0.5 天 | 连接功能独立模块 |
| Phase 4 | 抽取 `:feature:creation` — 移动创建相关 UI + Gateway | 0.5 天 | 创建功能独立模块 |
| Phase 5 | 抽取 `:feature:generation` — 移动生成流水线 + Service | 1 天 | 生成功能独立模块 |
| Phase 6 | 在 `:core` 中定义跨模块 interface 契约 | 0.5 天 | 所有跨模块调用通过 interface |
| Phase 7 | 新建 `:feature:reader` + `:feature:library` 空壳 | 0.5 天 | 新功能可开始开发 |
| Phase 8 | 更新 `:app` 为纯壳工程（导航 + Hilt 组装） | 1 天 | app 不含业务逻辑 |

### 验证检查点

每个 Phase 结束后执行：

- `./gradlew assembleDebug` 编译通过
- `./gradlew test` 测试通过
- 模块依赖图无环（可用 `./gradlew dependencies` 检查）
- 无模块直接引用其他 feature 的实现类（只引用 `:core` 中的 interface）

---

## 十、与当前 3 模块结构的对比

| | 当前（精简后） | 目标 |
|---|---|---|
| 模块数 | 3（app / engine / data） | 11（core / data / provider + 6 feature + app） |
| 切分依据 | 技术层（UI / 逻辑 / 数据） | 功能（连接 / 创建 / 生成 / 阅读 / 书架 / 模板） |
| 团队协作 | 难（改动跨 3 模块） | 易（改自己模块） |
| 增删功能 | 改多处 | 加/删一个模块 |
| 编译速度 | 全量 | 可按模块增量 |
| 跨模块耦合 | 直接引用实现类 | 通过 interface 隔离 |
| 可测试性 | 耦合高，难单测 | 每个功能模块可独立测试 |

---

## 十一、风险和缓解

| 风险 | 缓解 |
|------|------|
| 模块拆分过多导致构建配置膨胀 | 使用 convention plugin 统一 Android Library 配置 |
| Hilt 跨模块绑定增加复杂度 | 在 `:core` 统一定义 interface，各模块只 `@Binds` |
| `:core` 变成"什么都有"的大杂烩 | 严格限制只放 model + interface + 纯逻辑策略，不放 Android 依赖 |
| `:feature:generation` 过大 | 后续可拆为 `:feature:generation-core` + `:feature:generation-service` |
| Room 数据库跨模块共享 | `:data` 统一持有 `ZhijuanDatabase`，各 feature 通过 DAO 访问，不直接持有数据库实例 |

---

## 十二、Convention Plugin（统一模块配置）

为避免每个模块重复写 Android 配置，创建 `buildSrc/` 或 `build-logic/` 中的 convention plugin：

```kotlin
// build-logic/convention/src/main/kotlin/AndroidLibraryConventionPlugin.kt
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply("com.android.library")
                apply("org.jetbrains.kotlin.android")
            }
            extensions.configure<ApplicationExtension> {
                compileSdk = 36
                defaultConfig.minSdk = 29
                compileOptions {
                    sourceCompatibility = VERSION_17
                    targetCompatibility = VERSION_17
                }
            }
        }
    }
}
```

每个 feature 模块的 `build.gradle.kts` 简化为：

```kotlin
plugins {
    id("zhijuan.android.library")
    id("zhijuan.hilt")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":data"))
}
```
