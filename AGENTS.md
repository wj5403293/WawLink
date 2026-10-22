# AGENTS.md

## 界面约束

- 界面必须遵循 Material 3 风格，使用 Google Material Symbols 图标字体，并使用简洁的非线性交互动画。
- 背景保持纯白，布局采用现代化 Material 3 设计。
- 页面除主要文本外，尽量不使用符号和复杂装饰；能用图标表达时优先使用图标。
- UI 文字使用中文；专有名词可以使用英文。相同内容不得同时用中文和英文重复装饰。
- 文字只保留大号 UI 内容或标题；不添加小号卡片说明、装饰性文本或非必要装饰元素。
- 不得擅自修改既有显示样式；视觉与非线性动画严格以 `ref-plan/ref-home.html`、`ref-plan/ref-coding-agent-ui.html`、`ref-plan/ref-agent-setting.html` 为准，功能遵循 `ref-plan/plan.md`。
- 列表项使用“图标 + 标题 + 副标题（描述）”结构；右侧可放垂直三点按钮打开 popup menu。
- popup menu 使用“图标 + 标题”结构。

## 项目结构与索引

- `agent-protocol/`：独立 Kotlin 领域模型、会话控制、模型服务与设置契约；仅使用协程状态流，不依赖驱动或容器。
- `agent-ui/`：独立 Compose Multiplatform UI，聊天、输入区、模型服务与 Schema 设置页；`src/commonTest/kotlin/dev/wawlink/ui/MarkdownTest.kt` 覆盖标识符、强调与转义边界。
- `agent-driver-omp/`：OMP JSONL RPC、多会话、事件树、原生历史分支/迁移与 YAML 配置同步。
- `container-runtime/`：三引擎、Rootfs、发行版、包管理与流式终端。
- `app-desktop/`：460 × 920 桌面宿主；`src/sharedMain/kotlin/` 为双端共用主页与应用协调层。
- `app-android/`：仅 arm64-v8a 的 Android 宿主、系统文件选择、后台会话保活和预置 Rootfs 导入。
- `scripts/`：免 Docker Rootfs、Android 原生二进制与验证脚本。
- `ref-plan/`：用户提供的需求和视觉原型；不修改原型。
- `README.md`：构建运行、独立 UI 接入、能力边界、产物与已验证范围。
- `scripts/USAGE.txt`：固定输入、免 Docker 构建、原生程序加载与上游许可说明。

### 索引维护

- 新增、删除、移动或重命名模块、目录及索引中的关键文件时，必须在同一次修改中同步更新 `AGENTS.md` 的项目结构与索引，确保路径及职责说明与实际工程一致。
- 用户明确说明新约束时，更新`AGENTS.md`中的对应约束

## 重要约束：测试与交付

- 除非明确要求，禁止启动或使用 Android 模拟器进行 Debug 调试、测试或功能验收。
- Debug Gradle 任务仅用于本地测试与编译，不允许据此启动模拟器。
- 每次修改完成并通过必要验证后，必须为本次修改创建 Git commit，提交仅包含本次任务的变更。
- 已确认：Rootfs 完整预装功能优先，不将 150–220 MB 作为硬上限；必须报告实际解压体积和归档体积。
- 已确认：依上游真实能力提供引擎；Proroot 仅 Android arm64，桌面使用 PRoot/QEMU 或 chroot；强制只读绑定仅 chroot 支持，其他引擎必须明确拒绝，禁止静默变为可写。
- 真实模型验收仅使用用户确认的 `oc/deepseek-v4.1-flash`；不得将开发机认证复制进代码、Rootfs、APK 或交付报告。

