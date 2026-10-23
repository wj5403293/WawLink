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
- 用户消息和运行中追加消息先即时本地显示，不等待 OMP 启动或 RPC 回执；按稳定 ID 与回执对齐，发送失败撤回对应的未接收消息并显示原因，已接收消息不因后续模型执行失败而撤回。
- 加载状态使用 Compose Material 3 标准进度组件，不使用旋转图标模拟；静态“运行中”筛选、部署等语义使用对应的 Material Symbols 图标，不把进度圆圈当图标。
- 新会话默认开启免审批；应用设置提供“新会话默认免审批”并持久化，仅影响之后创建的会话。会话内仍可独立切换；开启时立即放行已等待及后续工具审批，不自动回答普通提问。
- 思考节点可归入创建时正在执行的 Todo 子任务；没有活动 Todo 时保持扁平。流式更新、完整快照和历史重放保留同一节点的归属与 ID，不因任务切换迁移或重复。
- 用户消息按发送时的 Todo 与前置节点固定位置；后续工具、思考、任务切换、排队消费、完整快照和历史重放不得重新归属或改变顺序。
- 返回页面、进入设置或切换会话时保留滚动与展开状态；草稿按会话隔离。弹出菜单缩放/淡入淡出时保留外部阴影，选中背景覆盖完整列表项。
- 模型选择按钮始终显示当前模型图标，列表项显示各自模型图标，保留品牌原色；未匹配品牌时，以模型 ID 首字符的大写单字标识加圆形底色显示，底色按字母稳定生成，同字母同色，不得套用服务商图标或通用脑图标。品牌资源参照 Kelivo，来源可核实的 MIT 图标与差异记录位于 `agent-ui/src/commonMain/composeResources/files/model-icons/`，不得复制许可来源未核实的资源。
- 模型资料仅按唯一 ID/规范化别名自动匹配，不模糊猜测；同步只追加新模型，已有编辑须显式应用匹配且保留手动字段。支持四项价格及独立的 `xhigh`、`max` 档位贯通 UI、配置和 RPC。
- Slash 补全以当前会话 OMP 的原生命令目录及动态更新为准，不用固定菜单冒充可用命令；当前版本的内置命令可预设中文描述与 Material Symbols 图标，未预设或扩展命令仍显示，但使用默认图标且不显示描述。保留无参数 `/model` 的应用模型选择和 `/cost` 的用量快捷入口，带参数的命令交给 OMP。
- 同一次 OMP ask 的多个问题显示在一张卡片中，按原生问题 ID 保存答案及预览状态；左下角使用灰色、无背景圆形前后切换按钮，配合左右非线性滑动。支持选项说明、推荐项、多选、自定义输入和提交前回看，一次提交完整答案。
- Ask 富预览与 Agent 正文共用 SVG、Mermaid、图像及 Markdown 渲染；图表不依赖在线渲染服务，模型内容不得执行脚本、读取宿主文件或通过 SVG 加载外部资源。
- 工具、Todo 与思考的运行耗时实时刷新，结束后固定；不足 1 秒隐藏，1–59 秒显示 `15s`，达到 1 分钟后显示 `1:05`，不显示静止的 `0:00`。
- 长思考及工具内容展开后，标题滚出可视区时将概况吸附在 Todo 与子任务概况下方；各层可回跳，不遮挡原始标题。流式思考按稳定段落增量渲染，阅读、选择或上滑时不强制追尾，全文查看使用 Markdown。
- LLM 代码块、工具执行的脚本及读取/写入/查找的代码共用成熟库高亮；未知语言保留纯文本。全屏代码只读且可选择、复制原文，Android 使用 Sora Editor，Desktop 使用原生桌面代码组件，不使用 WebView 冒充 Sora。
- 容器终端使用独立页面与真实 PTY；Android 直接移植 Love2Droid 的 Termux 终端模块，保留上游许可，Desktop 使用成熟终端组件。支持 ANSI、控制键、交互式程序及窗口尺寸更新，离开终端回收其进程。
- 工具链卡片点击只弹出安装或卸载确认，取消不执行；确认后避免重复触发。同一行卡片按较高内容等高，版本允许换行，Git 仅显示版本号，不重复 `git version`。
- 用户发送消息、继续下一轮或收到完整状态时，Todo 内容和状态未变化就不追加计划卡片；真正变化才追加，保留先前计划快照及用户消息位置。

## 项目结构与索引

- `agent-protocol/`：独立 Kotlin 领域模型、会话控制、模型服务与设置契约；仅使用协程状态流，不依赖驱动或容器。
- `agent-ui/`：独立 Compose Multiplatform UI，聊天、输入区、模型服务与 Schema 设置页；`src/commonMain/kotlin/dev/wawlink/ui/ModelIcon.kt` 统一离线品牌图标与 ID 字母标识，`src/commonMain/composeResources/files/model-icons/` 保存来源/许可，`src/commonTest/kotlin/dev/wawlink/ui/` 中的 `ModelIconTest.kt`、`FeedExpansionStateTest.kt` 和 `MarkdownTest.kt` 覆盖字母标识/颜色区分、展开状态和 Markdown 边界。
  - `src/commonMain/kotlin/dev/wawlink/ui/ComposerSlashCommands.kt` 提供原生命令的图标/中文展示匹配，`AskDraftState.kt` 保存按问题 ID 隔离的答案、页码与预览状态。
  - 同目录的 `MarkdownParts.kt`、`RichGraphic.kt` 共用正文/Ask 富媒体入口；`src/jvmSharedMain/kotlin/dev/wawlink/ui/RichGraphicDocument.kt` 负责离线图表文档、SVG 安全与图像加载，`src/androidMain/kotlin/dev/wawlink/ui/RichGraphic.android.kt` 和 `src/desktopMain/kotlin/dev/wawlink/ui/RichGraphic.desktop.kt` 分别提供沙箱 WebView 与无窗口 Canvas/原生图像显示。
  - `src/commonMain/composeResources/files/rich/` 保存 Mermaid 离线引擎、来源与许可证；`src/commonTest/kotlin/dev/wawlink/ui/` 中的 `ComposerSlashCommandsTest.kt`、`AskDraftStateTest.kt`、`MarkdownPartsTest.kt` 覆盖命令覆盖优先级、分组作答和富媒体解析边界，`src/desktopTest/kotlin/dev/wawlink/ui/RichGraphicSafetyTest.kt` 覆盖 SVG 主动内容、图像 URI 与重定向边界。
  - `src/commonMain/kotlin/dev/wawlink/ui/FeedPresentation.kt`、`src/jvmSharedMain/kotlin/dev/wawlink/ui/FeedClock.kt` 统一运行耗时、思考稳定展示与详情；`FeedPresentationTest.kt` 覆盖耗时边界，`MarkdownPartsTest.kt` 覆盖稳定 Markdown 段落与代码围栏边界。
  - 同目录的 `CodeLanguage.kt`、`HighlightedCode.kt`、`CodeDetailScreen.kt` 提供语言识别、Highlights 高亮与全屏入口；`src/androidMain/kotlin/dev/wawlink/ui/CodeDetailScreen.android.kt` 接入只读 Sora，`src/desktopMain/kotlin/dev/wawlink/ui/CodeDetailScreen.desktop.kt` 接入只读 RSyntaxTextArea。`src/commonMain/composeResources/files/code/` 保存精确版本来源与许可，`CodeLanguageTest.kt` 覆盖语言别名、路径选择器和未知语言边界。
- `agent-driver-omp/`：OMP JSONL RPC、多会话、事件树、原生历史分支/迁移与 YAML 配置同步；`src/jvmSharedMain/kotlin/dev/wawlink/driver/KnownModelCatalog.kt` 实现双源能力/价格目录与唯一匹配，`src/jvmSharedTest/kotlin/dev/wawlink/driver/` 中的 `KnownModelCatalogTest.kt`、`ModelPayloadExtensionTest.kt`、`OmpPromptDeliveryTest.kt`、`OmpEventReducerTest.kt` 覆盖模型资料、请求参数、消息位置、审批/执行模式及原生命令目录/批量 Ask 边界。
- `container-runtime/`：三引擎、Rootfs、发行版、包管理与流式终端；`src/jvmSharedMain/kotlin/dev/wawlink/runtime/RuntimeDns.kt` 负责边界安全的原子 DNS 更新，`src/jvmSharedTest/kotlin/dev/wawlink/runtime/RuntimeDnsTest.kt` 覆盖链接边界与配置切换。
  - 同目录的 `PtyTransport.kt` 及 `src/androidMain/kotlin/dev/wawlink/runtime/PtyTransport.android.kt`、`src/desktopMain/kotlin/dev/wawlink/runtime/PtyTransport.desktop.kt` 提供两端真实 PTY，`ContainerContract.kt` 定义原始字节读写、调整尺寸与会话回收契约。
- `app-desktop/`：460 × 920 桌面宿主；`src/sharedMain/kotlin/` 为双端共用主页与应用协调层。
  - `src/sharedMain/kotlin/dev/wawlink/app/TerminalScreen.kt` 为独立终端导航页，`src/desktopMain/kotlin/dev/wawlink/app/TerminalSurface.desktop.kt` 提供 JediTerm 终端；`src/desktopMain/resources/licenses/` 随桌面分发保留 JediTerm 与 pty4j 许可。
- `app-android/`：仅 arm64-v8a 的 Android 宿主、系统文件选择和后台会话保活；Rootfs 通过容器页单独导入。
  - `src/main/kotlin/dev/wawlink/app/TerminalSurface.android.kt` 将共享终端会话接入移植的 Android TerminalView。
- `terminal-emulator/`、`terminal-view/`：从 Love2Droid 固定提交移植的 Android Termux 终端解析器、JNI PTY 与视图，仅构建 arm64-v8a；`terminal-emulator/NOTICE` 记录上游来源及修改，许可同时保存在模块 assets 与 `licenses/`。
- `licenses/`：Termux 终端、JediTerm 与 pty4j 的完整上游许可及来源。
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
- 已确认：APK 与 Rootfs 分开构建和交付；APK 不内置 Rootfs、不依赖 Rootfs 归档即可构建，首次启动不自动解包。保留独立 Rootfs 归档及校验文件，更新 APK 不要求重新下载或导入已有容器。

