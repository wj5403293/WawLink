# AGENTS.md

## 界面约束

- 界面必须遵循 Material 3 风格，使用 Google Material Symbols 图标字体，并使用简洁的非线性交互动画。
- 背景保持纯白，布局采用现代化 Material 3 设计；独立终端的控制台、快捷键栏与字体按 Love2Droid 原布局保留。
- 页面除主要文本外，尽量不使用符号和复杂装饰；能用图标表达时优先使用图标。
- UI 文字使用中文；专有名词可以使用英文。相同内容不得同时用中文和英文重复装饰。
- 文字只保留大号 UI 内容或标题；不添加小号卡片说明、装饰性文本或非必要装饰元素。
- 不得擅自修改既有显示样式；主页、聊天与框架设置的视觉及非线性动画以 `ref-plan/ref-home.html`、`ref-plan/ref-coding-agent-ui.html`、`ref-plan/ref-agent-setting.html` 为准，新增 Agent 设置以 `ref-plan/agent-settings.html` 为准，功能遵循 `ref-plan/plan.md`。
- 列表项使用“图标 + 标题 + 副标题（描述）”结构；右侧可放垂直三点按钮打开 popup menu。
- popup menu 使用“图标 + 标题”结构。
- Agent 设置的作用域、选择项及新建入口使用原型中的锚定 popup menu，不改成对话框；提示与空态以原型为准，不额外堆叠说明卡片。确认删除、覆盖及舍弃修改仍使用对应确认对话框。
- 应用默认密度统一校准到原型，主页、聊天和所有设置页共用同一缩放，不在嵌套主题中重复缩放；应用设置提供持久化的“整体缩放”和恢复默认，保留系统字体缩放。当前基线以旧版 115% 为新版 100%；已有 115% 设置迁移为 100%，不得在升级或重启后重复放大。
- 启动及容器列表更新只读取会话元数据，打开某个会话时才恢复其正文与控制器；主页继续观察已打开的会话，不批量恢复未打开的历史。
- Agent 配置首屏不等待模型/认证 SDK；模型目录按有界分页传输，仅在编辑时读取完整模型字段。模型配置无效或原生进程退出时结束加载并保留可重试、可修复的界面，不抛到主线程导致闪退。
- 内置 Provider 的启用状态、OAuth 和操作能力以当前 OMP 原生发现、认证及禁用状态为准，不默认全开，也不对自定义 Provider 显示不支持的 OAuth 或协议功能。内置 Provider 使用稳定颜色与名称首字标识；用户项在前，内置项置底并用分隔线分组，禁用项灰显且在各组内下沉。
- Provider 配置每次打开从顶部展示；模型获取按钮显示蓝色“获取模型”并执行实际获取，不用静态目录刷新冒充。删除“读取当前配置档的……”等无实际意义说明及图标；保留必要错误、空态与风险确认。保存失败展示可定位且已脱敏的实际原因，保留全部草稿供修复和重试。
- 模型选项使用默认值时，在同一行及菜单的“使用默认值”后以灰字显示原生实际默认值；默认值只展示、不写入覆盖。依赖接口或会话而无确定值时明确显示未指定或跟随会话，不猜测。重复选择未设置的默认项不生成空对象；思考控制方式恢复默认时移除整段可选 `thinking`，放弃修改恢复全部字段并清除本次保存错误。
- 用户消息和运行中追加消息先即时本地显示，不等待 OMP 启动或 RPC 回执；按稳定 ID 与回执对齐，发送失败撤回对应的未接收消息并显示原因，已接收消息不因后续模型执行失败而撤回。
- 加载状态使用 Compose Material 3 标准进度组件，不使用旋转图标模拟；静态“运行中”筛选、部署等语义使用对应的 Material Symbols 图标，不把进度圆圈当图标。
- 新会话默认开启免审批；应用设置提供“新会话默认免审批”并持久化，仅影响之后创建的会话。会话内仍可独立切换；开启时立即放行已等待及后续工具审批，不自动回答普通提问。
- 思考节点可归入创建时正在执行的 Todo 子任务；没有活动 Todo 时保持扁平。流式更新、完整快照和历史重放保留同一节点的归属与 ID，不因任务切换迁移或重复。
- 用户消息按发送时的 Todo 与前置节点固定位置；后续工具、思考、任务切换、排队消费、完整快照和历史重放不得重新归属或改变顺序。
- 返回页面、进入设置或切换会话时保留滚动与展开状态；草稿按会话隔离。弹出菜单缩放/淡入淡出时保留外部阴影，选中背景覆盖完整列表项。
- 模型选择按钮始终显示当前模型图标，列表项显示各自模型图标，保留品牌原色；未匹配品牌时，以模型 ID 首字符的大写单字标识加圆形底色显示，底色按字母稳定生成，同字母同色，不得套用服务商图标或通用脑图标。品牌资源参照 Kelivo，来源可核实的 MIT 图标与差异记录位于 `agent-ui/src/commonMain/composeResources/files/model-icons/`，不得复制许可来源未核实的资源。
- 模型资料仅按唯一 ID/规范化别名自动匹配，不模糊猜测；同步只追加新模型，已有编辑须显式应用匹配且保留手动字段。支持四项价格及独立的 `xhigh`、`max` 档位贯通 UI、配置和 RPC。
- 新增“Agent 设置”独立管理 Agent、顾问、模型角色、服务、认证及运行配置；原有 Agent 设置改名为“框架设置”。Agent 与顾问的指定工具列表来自当前容器 OMP 的真实可配置工具目录，不使用原型示例冒充；保留模型在线拉取和已有控件，顾问及原始配置的全屏编辑使用 Android Sora / Desktop 原生编辑器。
- Slash 补全以当前会话 OMP 的原生命令目录及动态更新为准，不用固定菜单冒充可用命令；当前版本的内置命令可预设中文描述与 Material Symbols 图标，未预设或扩展命令仍显示，但使用默认图标且不显示描述。保留无参数 `/model` 的应用模型选择和 `/cost` 的用量快捷入口，带参数的命令交给 OMP。
- 同一次 OMP ask 的多个问题显示在一张卡片中，按原生问题 ID 保存答案及预览状态；左下角使用灰色、无背景圆形前后切换按钮，配合左右非线性滑动。支持选项说明、推荐项、多选、自定义输入和提交前回看，一次提交完整答案。单选选择后自动进入下一题，多选不自动切题；切题与检查答案不得改变会话滚动锚点。
- Ask 富预览与 Agent 正文共用 SVG、Mermaid、图像及 Markdown 渲染；图表不依赖在线渲染服务，模型内容不得执行脚本、读取宿主文件或通过 SVG 加载外部资源。
- 工具、Todo 与思考的运行耗时实时刷新，结束后固定；不足 1 秒隐藏，1–59 秒显示 `15s`，达到 1 分钟后显示 `1:05`，不显示静止的 `0:00`。
- 长思考及工具内容展开后，标题滚出可视区时将概况吸附在 Todo 与子任务概况下方；各层可回跳，不遮挡原始标题。思考仅在展开动画期间冻结显示快照，结束后恢复平滑增量输出，复用已完成的 Markdown 段落；阅读、选择或上滑时不强制追尾，全文查看使用 Markdown。
- LLM 代码块、未展开工具的命令摘要、执行脚本及读取/写入/查找的代码共用成熟库高亮；未知语言保留纯文本。全屏代码只读且可选择、复制原文，Android 使用 Sora Editor 与内置 TextMate 语法、依赖和明暗主题，Desktop 使用原生桌面代码组件，不使用 WebView 冒充 Sora；HTML 中的 JavaScript/CSS 也需高亮。
- 容器终端使用独立页面与真实 PTY；Android 直接移植 Love2Droid 的 Termux 终端模块、Maple Mono 字体与两行等宽快捷键栏，保留上游许可，不叠加另一套键栏。终端挂载和窗口获焦后建立输入连接，点击终端唤出软键盘，快捷键不抢输入焦点；Desktop 使用成熟终端组件。支持 ANSI、控制键、交互式程序及窗口尺寸更新，离开终端回收其进程。
- 工具链卡片点击只弹出安装或卸载确认，取消不执行；确认后避免重复触发。同一行卡片按较高内容等高，版本允许换行，Git 仅显示版本号，不重复 `git version`。
- 用户发送消息、继续下一轮或收到完整状态时，Todo 内容和状态未变化就不追加计划卡片；真正变化才追加，保留先前计划快照及用户消息位置。
- 会话发送过消息后，用量条持续显示，不因进入空闲、开始下一轮或后续执行失败而消失。
- 容器独立文件管理沿用 WawSSH 的路径、面包屑、筛选、排序、选择、书签与文件操作布局；新建会话的工作区输入框右侧提供文件夹按钮，通过容器浏览器选择目录，取消或返回不得丢失原表单。
- 聊天加号菜单保留项目文件并增加容器文件，支持跨目录选择普通文件；附件按来源区分主机与容器路径，不伪造工作区路径，不将容器文件复制到主机再发送。浏览器取消不清空草稿或已有附件；项目文件展开子目录后各行不得重叠。
- PRoot/Proroot 启动前按宿主补充 GID 为容器追加缺失的组名，不覆盖已有组记录，不隐藏 `groups` 错误；既有动态 DNS 修复继续适用，更新 APK 不要求重新导入 Rootfs。

## 项目结构与索引

- `agent-protocol/`：独立 Kotlin 领域模型、会话控制、模型服务与设置契约；仅使用协程状态流，不依赖驱动或容器。
  - `src/commonMain/kotlin/dev/wawlink/protocol/AgentManagement.kt` 定义作用域、来源、Agent、顾问、角色、认证、原始配置与原生管理仓库契约。
- `agent-ui/`：独立 Compose Multiplatform UI，聊天、输入区、模型服务与 Schema 设置页；`src/commonMain/kotlin/dev/wawlink/ui/ModelIcon.kt` 统一离线品牌图标与 ID 字母标识，`src/commonMain/composeResources/files/model-icons/` 保存来源/许可，`src/commonTest/kotlin/dev/wawlink/ui/` 中的 `ModelIconTest.kt`、`FeedExpansionStateTest.kt` 和 `MarkdownTest.kt` 覆盖字母标识/颜色区分、展开状态和 Markdown 边界。
  - `src/commonMain/kotlin/dev/wawlink/ui/ComposerSlashCommands.kt` 提供原生命令的图标/中文展示匹配，`AskDraftState.kt` 保存按问题 ID 隔离的答案、页码与预览状态。
  - 同目录的 `MarkdownParts.kt`、`RichGraphic.kt` 共用正文/Ask 富媒体入口；`src/jvmSharedMain/kotlin/dev/wawlink/ui/RichGraphicDocument.kt` 负责离线图表文档、SVG 安全与图像加载，`src/androidMain/kotlin/dev/wawlink/ui/RichGraphic.android.kt` 和 `src/desktopMain/kotlin/dev/wawlink/ui/RichGraphic.desktop.kt` 分别提供沙箱 WebView 与无窗口 Canvas/原生图像显示。
  - `src/commonMain/composeResources/files/rich/` 保存 Mermaid 离线引擎、来源与许可证；`src/commonTest/kotlin/dev/wawlink/ui/` 中的 `ComposerSlashCommandsTest.kt`、`AskDraftStateTest.kt`、`MarkdownPartsTest.kt` 覆盖命令覆盖优先级、分组作答和富媒体解析边界，`src/desktopTest/kotlin/dev/wawlink/ui/RichGraphicSafetyTest.kt` 覆盖 SVG 主动内容、图像 URI 与重定向边界。
  - `src/commonMain/kotlin/dev/wawlink/ui/FeedPresentation.kt`、`src/jvmSharedMain/kotlin/dev/wawlink/ui/FeedClock.kt` 统一运行耗时、思考稳定展示与详情；`FeedPresentationTest.kt` 覆盖耗时边界，`MarkdownPartsTest.kt` 覆盖稳定 Markdown 段落与代码围栏边界。
  - 同目录的 `CodeLanguage.kt`、`HighlightedCode.kt`、`CodeDetailScreen.kt` 提供语言识别、原生高亮与全屏入口；`src/androidMain/kotlin/dev/wawlink/ui/OfflineTextMate.android.kt` 为行内与 Sora 共用离线 TextMate 语法，`CodeDetailScreen.android.kt` 接入只读 Sora；`src/desktopMain/kotlin/dev/wawlink/ui/HighlightedCode.desktop.kt` 与 `CodeDetailScreen.desktop.kt` 接入 RSyntaxTextArea。
  - `src/commonMain/composeResources/files/code/` 保存精确版本来源与许可，`textmate/` 保存语法依赖、主题、索引与哈希；`CodeLanguageTest.kt` 覆盖语言别名、路径选择器和未知语言，`src/androidUnitTest/kotlin/dev/wawlink/ui/OfflineTextMateTokenizerTest.kt` 与 `src/desktopTest/kotlin/dev/wawlink/ui/NativeCodeHighlightTest.kt` 覆盖真实词法器的嵌入语言、多行及颜色边界。
  - `src/commonMain/kotlin/dev/wawlink/ui/AgentManagementScreen.kt` 为新 Agent 设置入口，`ManagementComponents.kt` 提供原型一致的页面、作用域、候选模型链与配置编辑控件；`ManagementAgentsScreen.kt`、`ManagementAdvisorsScreen.kt` 管理定义、运行覆盖及顾问，`ManagementRolesScreen.kt`、`ManagementPoliciesScreen.kt`、`ManagementRuntimeScreen.kt` 分别管理角色、模型策略与原生运行设置，`ManagementNativeProviderScreen.kt` 编辑完整原生服务及模型覆盖。
  - 同目录的 `EditableCodeScreen.kt` 提供带保存与舍弃确认的全屏可编辑原文入口；`src/androidMain/kotlin/dev/wawlink/ui/EditableCodeScreen.android.kt` 接入可编辑 Sora 与既有离线 TextMate，`src/desktopMain/kotlin/dev/wawlink/ui/EditableCodeScreen.desktop.kt` 接入可编辑 RSyntaxTextArea。
  - `src/commonTest/kotlin/dev/wawlink/ui/NativeProviderDraftTest.kt` 覆盖首次配置读取与用户输入合并、显式移除、无效输入和模型子表单状态保存，以及思考默认值的无操作、整段清除、继承与未知字段保留；`ProviderListingTest.kt` 覆盖零模型及未知协议服务可见、内置去重和原生可用性优先的组内排序。
- `agent-driver-omp/`：OMP JSONL RPC、多会话、事件树、原生历史分支/迁移与 YAML 配置同步；`src/jvmSharedMain/kotlin/dev/wawlink/driver/KnownModelCatalog.kt` 实现双源能力/价格目录与唯一匹配，`src/jvmSharedTest/kotlin/dev/wawlink/driver/` 中的 `KnownModelCatalogTest.kt`、`ModelPayloadExtensionTest.kt`、`OmpPromptDeliveryTest.kt`、`OmpEventReducerTest.kt` 覆盖模型资料、请求参数、消息位置、审批/执行模式及原生命令目录/批量 Ask 边界。
  - `src/jvmSharedMain/kotlin/dev/wawlink/driver/OmpAgentManagementRepository.kt` 管理按容器、项目和会话隔离的原生管理进程、版本冲突与原子写入；`src/jvmSharedMain/resources/omp-management/` 的 Bun 桥直接复用已安装 OMP 的配置注册表、Agent/顾问发现、模型角色解析及认证 API，不内置另一份可编辑配置规则。
  - `src/jvmSharedMain/resources/omp-management/diagnostics.mjs` 统一原生配置诊断与敏感值清理，保留字段路径和可得语法位置，不转发源文本片段、凭据或 HTTP 响应正文。
  - `src/jvmSharedTest/kotlin/dev/wawlink/driver/OmpAgentManagementRepositoryTest.kt` 覆盖首屏与模型分阶段加载、无效模型配置修复、原生进程退出后恢复、目录分页完整性及 JSONL 大小/格式边界。
- `container-runtime/`：三引擎、Rootfs、发行版、包管理与流式终端；`src/jvmSharedMain/kotlin/dev/wawlink/runtime/RuntimeDns.kt` 负责边界安全的原子 DNS 更新，`src/jvmSharedTest/kotlin/dev/wawlink/runtime/RuntimeDnsTest.kt` 覆盖链接边界与配置切换。
  - 同目录的 `RuntimeIdentity.kt` 负责 PRoot/Proroot 补充组名的边界检查与原子更新，`src/jvmSharedTest/kotlin/dev/wawlink/runtime/RuntimeIdentityTest.kt` 覆盖保留已有记录、幂等与链接越界；`ContainerFileService.kt` 通过客体进程提供文件枚举、流式导入导出、复制、重命名和删除，不访问宿主 Rootfs 路径。
  - 同目录的 `PtyTransport.kt` 及 `src/androidMain/kotlin/dev/wawlink/runtime/PtyTransport.android.kt`、`src/desktopMain/kotlin/dev/wawlink/runtime/PtyTransport.desktop.kt` 提供两端真实 PTY，`ContainerContract.kt` 定义原始字节读写、调整尺寸与会话回收契约。
- `app-desktop/`：460 × 920 桌面宿主；`src/sharedMain/kotlin/` 为双端共用主页与应用协调层。
  - `src/sharedMain/kotlin/dev/wawlink/app/TerminalScreen.kt` 为独立终端导航页，`src/desktopMain/kotlin/dev/wawlink/app/TerminalSurface.desktop.kt` 提供 JediTerm 终端；`src/desktopMain/resources/licenses/` 随桌面分发保留 JediTerm 与 pty4j 许可。
  - `src/sharedMain/kotlin/dev/wawlink/app/ContainerFilesScreen.kt` 移植 WawSSH 独立文件浏览器并提供工作区与附件选择模式；`src/desktopTest/kotlin/dev/wawlink/app/ContainerFileServiceTest.kt` 覆盖文件名、路径、流式字节、覆写与链接边界，不冒充真实引擎验收。
  - `src/sharedMain/kotlin/dev/wawlink/app/AppCoordinator.kt` 负责按需恢复会话及持久化整体缩放；`src/desktopTest/kotlin/dev/wawlink/app/AppCoordinatorSessionRestoreTest.kt` 覆盖大量历史的惰性加载、单会话恢复与并发重复打开，`AppCoordinatorAppearanceTest.kt` 覆盖缩放基线迁移、跨重启保持、调整及恢复默认。
- `app-android/`：仅 arm64-v8a 的 Android 宿主、系统文件选择和后台会话保活；Rootfs 通过容器页单独导入。
  - `src/main/kotlin/dev/wawlink/app/TerminalSurface.android.kt` 将共享终端会话接入移植的 Android TerminalView，保留 Love2Droid 原快捷键布局与输入焦点；`src/main/res/font/maple_mono_nf_cn_regular.ttf` 保存原项目终端字体。
- `terminal-emulator/`、`terminal-view/`：从 Love2Droid 固定提交移植的 Android Termux 终端解析器、JNI PTY 与视图，仅构建 arm64-v8a；`terminal-emulator/NOTICE` 记录上游来源及修改，许可同时保存在模块 assets 与 `licenses/`。
- `licenses/`：Termux 终端、JediTerm、pty4j、Love2Droid/r2droid 与 Maple Mono 的完整上游许可及来源。
- `scripts/`：免 Docker Rootfs、Android 原生二进制与验证脚本。
- `ref-plan/`：用户提供的需求和视觉原型；不修改原型。
  - `ref-plan/agent-settings-capabilities.md`：以 OMP 18.12.6 为基准的 Agent 与 Models 设置原型能力清单，涵盖作用域、定义、角色、模型服务及高级配置；仅作设计能力说明，不代表已实现。
  - `ref-plan/agent-settings.html`：新增 Agent 设置的完整视觉与交互原型，保留原文件，不修改演示数据。
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

