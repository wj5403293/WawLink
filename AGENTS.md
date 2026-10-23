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
- 模型列表、编辑页和输入区按钮共用模型品牌图标，保留原色；参照 Kelivo 的视觉资源，来源可核实的 MIT 图标与差异记录位于 `agent-ui/src/commonMain/composeResources/files/model-icons/`，不得复制许可来源未核实的资源。
- 模型资料仅按唯一 ID/规范化别名自动匹配，不模糊猜测；同步只追加新模型，已有编辑须显式应用匹配且保留手动字段。支持四项价格及独立的 `xhigh`、`max` 档位贯通 UI、配置和 RPC。

## 项目结构与索引

- `agent-protocol/`：独立 Kotlin 领域模型、会话控制、模型服务与设置契约；仅使用协程状态流，不依赖驱动或容器。
- `agent-ui/`：独立 Compose Multiplatform UI，聊天、输入区、模型服务与 Schema 设置页；`src/commonMain/kotlin/dev/wawlink/ui/ModelIcon.kt` 统一离线品牌图标，`src/commonMain/composeResources/files/model-icons/` 保存来源/许可，`src/commonTest/kotlin/dev/wawlink/ui/FeedExpansionStateTest.kt` 与 `MarkdownTest.kt` 覆盖展开状态保存和 Markdown 边界。
- `agent-driver-omp/`：OMP JSONL RPC、多会话、事件树、原生历史分支/迁移与 YAML 配置同步；`src/jvmSharedMain/kotlin/dev/wawlink/driver/KnownModelCatalog.kt` 实现双源能力/价格目录与唯一匹配，`src/jvmSharedTest/kotlin/dev/wawlink/driver/` 中的 `KnownModelCatalogTest.kt`、`ModelPayloadExtensionTest.kt`、`OmpPromptDeliveryTest.kt`、`OmpEventReducerTest.kt` 覆盖模型资料、请求参数、消息位置及审批/执行模式边界。
- `container-runtime/`：三引擎、Rootfs、发行版、包管理与流式终端；`src/jvmSharedMain/kotlin/dev/wawlink/runtime/RuntimeDns.kt` 负责边界安全的原子 DNS 更新，`src/jvmSharedTest/kotlin/dev/wawlink/runtime/RuntimeDnsTest.kt` 覆盖链接边界与配置切换。
- `app-desktop/`：460 × 920 桌面宿主；`src/sharedMain/kotlin/` 为双端共用主页与应用协调层。
- `app-android/`：仅 arm64-v8a 的 Android 宿主、系统文件选择和后台会话保活；Rootfs 通过容器页单独导入。
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

