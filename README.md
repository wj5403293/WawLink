# WawLink

Android / Linux Desktop 跨端 Coding Agent。Kotlin Multiplatform、Compose Multiplatform 与 Material 3；主页、聊天与框架设置以既有 `ref-plan/` 原型为基准，Agent、插件、技能管理分别遵循 `agent-settings.html`、`plugin-management.html`、`skill-management.html`，控件使用当前主题色而非原型紫色。

## 模块与边界

| 模块 | 职责与依赖边界 |
| --- | --- |
| `agent-protocol` | 会话控制、消息树、模型服务与设置契约；仅依赖 Kotlin 协程，不依赖 UI、OMP 或容器 |
| `agent-ui` | 聊天、Composer、模型服务、Agent 管理与框架设置；业务层仅依赖 `agent-protocol` |
| `agent-driver-omp` | 一会话一进程、真实 OMP RPC-UI / JSONL、条件式 Todo 分组与 YAML 双向同步 |
| `container-runtime` | Proroot / PRoot / chroot、Rootfs、发行版部署、工具链、终端与容器声明的真实 WebUI 功能 |
| `app-desktop` | 460 × 920 桌面窗口；`src/sharedMain/kotlin` 包含双端共用主页、单容器选择、功能、规则、插件/技能管理与应用协调层 |
| `app-android` | arm64-v8a 宿主、系统文件选择、前台服务与功能 WebView；Rootfs 从容器页单独导入 |
| `terminal-emulator` / `terminal-view` | 从 Love2Droid 固定提交移植的 Android Termux 终端与 JNI PTY；只构建 arm64-v8a |

`agent-driver-omp/src/jvmSharedMain/resources/` 中的模型预算扩展、OMP 设置 Schema 和 `omp-management/` 原生管理桥同时供两端使用。Android 驱动显式将该目录接入 `main.resources`，使这些文件作为 Java 类路径资源进入驱动 AAR 和宿主 APK；仅声明 Kotlin 共享源码集依赖不足以打包这些资源。修复此类 APK 资源遗漏不需要重新生成或导入 Rootfs。

OMP 启动前会将 `models.yml` 和主设置文件重新序列化为 Bun 可解析的 YAML，自动修复旧版本写出的 pretty-flow 格式；保留认证值、注释和未知字段，不改认证存储，也不重写内容已稳定的文件。Android PRoot 的版本化库名通过应用私有目录中的符号链接解析，不再把 Android `LD_PRELOAD` 传给第一个 Linux 程序。更新 APK 即可使用这些修复，无需重新导入 Rootfs。

新增“Agent 设置”与原有“框架设置”独立导航。新页面按当前容器、工作区及可选会话建立管理上下文，直接使用已安装 OMP 的配置注册表、定义发现、角色解析、模型目录及认证实现：

- Agent 支持全局/项目定义、来源和遮蔽关系、只读来源复制、启停、系统提示词、候选模型链、真实工具及技能目录、原始 YAML + Markdown，以及按名称生效的独立运行覆盖。
- 顾问支持原生 WATCHDOG 配置、共享/专属指令、模型与工具、审查时机及输出预算；没有可靠运行状态时不伪造运行或暂停信息。
- 默认模型与原生角色、候选顺序、模型预设、允许范围、快速切换和故障转移独立管理；`xhigh` 与 `max` 保持不同值。AI 生成 Agent 使用有效默认角色的原生补全，不自动保存定义。
- 模型服务读取 OMP 原生可用状态与 OAuth 注册，不默认启用全部内置服务；用户项在前，内置项分隔置底，各组禁用项灰显下沉。Provider 使用稳定颜色与名称首字标识。“获取模型”执行实际远端目录请求或原生定向发现，只追加新模型，保留已有编辑；不支持的 OAuth 和协议操作不显示。内置模型通过原生字段覆盖编辑，认证由 OMP 独立存储。
- 运行设置来自当前 OMP 注册表；原始配置保存前校验原生 Schema，原子写入并拒绝覆盖外部新修改。脱敏导出不包含凭据。Android 原文编辑使用可编辑 Sora 与离线 TextMate，Desktop 使用 RSyntaxTextArea；保存、舍弃与返回分别处理。

Agent 设置先加载运行配置、Agent 与文件来源，再异步读取模型和认证目录，不用模型 SDK 初始化阻塞整页。模型列表以有界分页传输，完整字段仅在编辑模型时读取；无效 `models.yml` 或管理进程退出会显示可恢复错误，原始配置仍可打开修复并重新加载。配置错误保留文件、字段路径及可得行列，不回显凭据或服务响应正文；保存失败保留表单和模型子页草稿。旧版 pretty-flow YAML 先经原生校验，成功才转换为 Bun 可解析格式，拒绝的文档保留原字节。

启动和容器列表更新仅加载会话元数据；进入某个会话时才恢复其消息与控制器，未打开的历史不批量恢复。已打开的会话仍持续更新主页状态。

“应用设置 → 整体缩放”对主页、聊天、Agent 设置、框架设置及其他页面统一生效，支持 80%–125%、重启保留和恢复默认 100%。新版 100% 等于此前 115%，原有 115% 偏好自动迁移为 100%，旧默认值随新基线放大，其他自定义比例按基线换算且只迁移一次。窄屏仍按 460 逻辑像素视口统一校准；系统字体缩放保持有效，嵌套页面主题不重复缩放。

“应用设置 → 主题配色”支持默认蓝色、系统配色和自定义主色；自定义通过 Material Color Utilities 的 HCT 色调生成明暗两套 Material 3 角色，可选择预设或输入六位 HEX，设置跨重启保留。Android 12+ 直接读取系统莫奈调色板，旧版 Android 与 Desktop 的系统选项明确回退默认蓝色；默认界面的背景保持纯白，分段选择器及其他控件统一使用主题色，不残留默认紫色。完整上游许可随 UI 资源打包。

Android“应用设置 → 外观与显示 → 屏幕常亮”默认关闭，开关状态跨重启保留。开启后主页、聊天、终端和其他应用页面在前台可见时均保持亮屏；关闭或切到后台恢复系统自动熄屏，终端不再单独强制常亮。仅使用当前窗口的常亮标志，不修改系统熄屏时间，也不申请唤醒锁权限。

## 构建与运行

构建环境：Linux x86_64 或 arm64、JDK 17、Android SDK Platform 35 与 Android NDK（编译终端 JNI）、网络，以及 Bash、Python 3、curl、GNU tar、dpkg-deb、flock 和 GNU coreutils。工程自带 Gradle Wrapper，不需要系统 Gradle。Android 最低版本为 Android 8.0 / API 26。

在工程根目录执行：

```bash
./scripts/fetch-android-binaries.sh
./scripts/build-debug-rootfs.sh
./gradlew :app-desktop:run
```

两个准备脚本均不使用 Docker、sudo 或 Android 模拟器。缺失的宿主工具下载到 `build/host-tools`，不会覆盖系统工具。输入版本和校验值固定在 `scripts/assets.lock.json`；构建失败不会覆盖已完成的 Rootfs 归档。

桌面端检测并加载 `build/debug-rootfs/ubuntu-main`，数据默认保存在 `build/user-data`。在“模型服务”中配置自己的接口和模型后即可新建会话；预置环境不含开发机认证，也没有默认 API Key。

单独构建不内置 Rootfs 的 Debug APK：

```bash
./scripts/fetch-android-binaries.sh
./gradlew :app-android:assembleDebug
```

输出：`app-android/build/outputs/apk/debug/app-android-debug.apk`。后续更新始终覆盖这个 Gradle 默认产物，不再按任务复制或另存新命名 APK。该任务不要求 Rootfs 归档存在，不会启动模拟器。预置原生命令作为可执行 `.so` 随 APK 安装，仅打包 arm64-v8a，保留上游二进制原始字节；新增终端 PTY 的 `libtermux.so` 从移植的 JNI 源码构建。

Rootfs 独立由 `./scripts/build-debug-rootfs.sh` 构建，输出归档与 SHA256 文件到 `build/outputs/`。首次安装后，将独立归档复制到手机，在“容器 → 导入 rootfs”选择文件并完成导入；应用启动不再从 APK 解包环境。以后覆盖安装同签名 APK 即可保留已有容器和会话，不需要重新下载或导入未变化的 Rootfs，也不要为更新而卸载应用。

在线安装基础镜像后会检查容器内 OMP；缺失或损坏时弹出安装/修复确认，OMP 会话进程及原生管理进程故障也会提示。点击“打开终端并安装/修复”后，先暂停该容器的 OMP 进程，再打开独立真实终端自动执行应用内置脚本；取消不执行。容器详情另提供“安装/修复 OMP”入口。已有配置、认证、工作区和会话保留，不需要重新导入镜像。

安装需要联网及容器内 root 身份，固定安装 OMP 18.12.6、Bun 1.3.14 和完整运行依赖，校验上游包完整性、真实原生模块与 RPC `ready/get_state`，校验后才切换安装，切换失败恢复旧程序。无 CA 的 Debian/Ubuntu 基础镜像通过保留 APT 签名及包哈希校验的临时 HTTP 源安装证书，之后使用原 HTTPS 源；不修改用户源配置或关闭 TLS 校验。安装期间请留在终端，失败原因和 Shell 会保留；返回后重新自检，成功才刷新已有 OMP 控制器。不会复制开发机认证或发起模型推理。

### 桌面路径覆盖

| 环境变量 | 用途 |
| --- | --- |
| `WAWLINK_DATA_DIR` | 容器元数据、会话、设置的保存目录 |
| `WAWLINK_ROOTFS` | 自动注册的预置 Rootfs 目录 |
| `WAWLINK_TOOLS_DIR` | 宿主 PRoot / QEMU 所在目录 |
| `WAWLINK_PROOT` | 指定宿主 PRoot 可执行文件 |
| `WAWLINK_QEMU` | 指定 qemu-aarch64-static；原生 arm64 宿主不需要 QEMU |

对应 JVM 属性为 `wawlink.dataDir`、`wawlink.rootfs`、`wawlink.toolsDir`、`wawlink.proot`、`wawlink.qemu`，属性优先于环境变量。

## 使用与能力边界

- 主页管理多会话、搜索、容器/状态筛选、分支、批量置顶/归档/删除；长按会话列表项直接进入多选，并可直接处理待审批操作。当前容器为持久化单选：点击容器卡片选择，右侧开关仍独立启停，选中卡片有主题主色粗边框，长名称单行截断；终端入口移至“功能”。新建会话直接使用所选容器且不预设名称，未发送消息即离开的空会话不进入持久化列表。发送后以首条用户问题命名，保留手动标题；聊天内新建沿用当前容器与工作区。
- 主页会话卡片展开/收起动画分别为 260/220 毫秒，保留非线性曲线；分支操作使用带中文无障碍说明的纯图标按钮，减少窄屏操作栏换行。
- 主页底栏按“会话、功能、容器”排列，功能位于中间；页面切换方向跟随标签位置，标题、操作按钮和各页滚动状态与对应页面保持一致。
- 聊天页支持真实流式输出、Todo 条件归组、工具详情和 Diff、审批/提问、运行中消息排队与编辑、附件、项目文件、斜杠命令和用量详情；右上角设置使用锚定 popup menu，保留模型服务、Agent 设置与框架设置入口。Android 状态栏与聊天顶栏共享背景色及滚动变色动画，保留顶部安全区与明暗主题的状态栏图标。
- 聊天顶栏在设置左侧新增“历史树”和“Agent Hub”。历史树沿用 `session-history.html` 的树形/时间线、筛选、搜索、展开、当前叶定位与节点操作；预览不切换续写位置，标签、回退、可取消的分支摘要、历史 Ask 重答和独立会话分支走原生 API。复制按选定消息提供可见正文、代码、引用、链接与该轮命令，不混入思考、工具输出或其他轮次；聊天与原生节点按精确 ID 互相定位。
- Agent Hub 沿用 `agent-hub.html` 的 Agents/活动、树形/平铺、搜索筛选、跟随最新及批量操作，展示原生注册表与递归发现的持久子会话。独立子代理页保留会话、概况、产物、后台任务与身份链；查看停放记录不自动恢复运行。继续、停止、终止、模型/思考、Ask 与审批均校验会话及运行实例，顾问保持只读；导出仅包含选定子代理及其下级，不导出主会话。
- 两页使用当前蓝色、系统配色或自定义主题，不写入原型演示状态。OMP 18.12.6 发行版未向扩展导出实时 IRC 邮箱计数时显示未测量，不冒充零条；模型不可用、记录缺失、失效实例和原生操作失败保留实际错误。需要模型的摘要、续写或恢复仍要求容器自身配置和认证，不使用开发机凭据。
- 历史树首屏仅传完整轻量节点索引，不携带全部正文、预览 Feed、原始 JSON 或复制块；大索引使用固定快照分页，刷新只应用节点/标签/叶子增量。预览、原始记录、复制与历史 Ask 按节点读取，详情按会话身份、节点和版本缓存，拒绝过期响应。Hub 实例首屏不依赖主会话历史或全部子会话正文，持久实例与活动摘要异步补齐；首次打开仍需启动当前容器内的 OMP。
- 会话输入与排队消息编辑支持多行，Enter 和软键盘回车用于换行，不发送或保存；使用发送按钮提交、勾选按钮保存。Slash 菜单使用 Tab 或点击选择，不拦截回车。
- 主会话运行中点按追加使用 OMP 原生 `steer` 干预当前执行，长按使用原生 `followUp` 等待后续处理，均支持附件。主输入框为空时点击队列编辑，在确认撤出队列后将原始文字和附件放回输入框；已有草稿则保留行内编辑，不覆盖输入。队列种类贯穿本地预览、编辑及停止恢复，撤回失败显示实际原因。
- 容器独立文件管理按 WawSSH 对齐列表、图标操作栏、面包屑、路径编辑、筛选/排序、多选、复制/粘贴、重命名、属性、书签与导入导出；路径、更多、下载、新建、书签及同类操作面板使用全宽简洁图标列表，不叠加卡片背景、重复标题和取消行。支持系统打开图片、Android APK 安装、单文件下载及目录/多选 ZIP 下载。删除确认后可递归删除非空目录，先检查整批对象及子树，拒绝符号链接、特殊文件、宿主绑定路径及其祖先，不先删掉部分再因边界错误中断；不覆盖已有目标。文件操作通过客体进程执行，不直接读写宿主 Rootfs。失效历史会显示实际错误，但不会重新入栈，仍可继续返回或退出。
- 聊天加号菜单保留项目文件，并提供可跨目录选择的“容器文件”；取消保留草稿及已有附件，容器附件使用真实客体绝对路径，不复制到宿主后伪造工作区路径。
- 会话发送过消息后，用量条持续显示，运行、空闲、继续下一轮及执行出错时均不再隐藏；项目文件展开子目录后使用独立列表行，不再叠放。
- 工具、Todo 和思考的运行耗时实时更新，结束后固定；不足 1 秒隐藏，1–59 秒显示 `15s`，达到 1 分钟后显示 `1:05`。展开的长思考/工具概况吸附在计划、子任务下方，三层均可回跳。
- 思考仅在展开动画期间冻结显示快照，结束后追上最新内容；平时按 32 ms 节奏平滑推进尾部，已完成 Markdown 段落保持稳定，不以整段防抖替代流式输出。上滑、阅读或选择时不强制追尾，使用独立的“回到底部”恢复跟随。思考全文以 Markdown 显示，展开时不从头播放。
- 点击思考或工具左侧图标，可直接进入该节点的现有全屏内容页，无需先展开；图标保留原有外形并带 Material 3 波纹与中文无障碍名称。标题仍控制展开/收起，打开和返回不改变展开状态，计划与任务组图标行为不变；展开后的全文/详情入口继续保留。
- 超长聊天保留完整历史、正文和既有动画/富媒体/交互。吸顶几何改为相对所属 Lazy 行记录，滚动时不重写每个节点的绝对坐标，候选仅取可见根项并隔离观察；追尾仍按实际尾项和底部留白计算有限距离。思考折叠预览只准备可见宽度所需的头部/尾部文字，不删改全文；普通段落跳过内联代码专用子组合布局，大块 Markdown 后台准备，高亮注册表在 Android 串行复用。
- Markdown 代码块、未展开工具的命令摘要、执行脚本与文件内容共用原生离线高亮；Android 使用 Sora TextMate，Desktop 使用 RSyntaxTextArea 3.6.0。语言依据显式标记、工具名、文件后缀或 shebang 识别，未知显式语言保留纯文本。读取路径支持行号、范围、raw、归档成员和查询选择器，不将这些选择器误当后缀。
- 读取、写入、创建及查找代码可打开全屏只读查看器，支持行号、选择和复制原文，默认开启自动换行；右上角三点菜单的“自动换行”项可切换，换行不改变原文或复制内容。Android 使用 Sora Editor 0.23.6，Desktop 使用 RSyntaxTextArea 3.6.0，不以网页组件替代 Sora；桌面全屏窗口沿用混合画布，避免新菜单被原生编辑器遮挡。内置 34 项 TextMate 语言映射、完整语法依赖及明暗主题，包含 HTML/JavaScript/CSS、TypeScript/JSX/TSX、Shell、Python、Kotlin、Java、C/C++/C#、Go、Rust、JSON/YAML/TOML、SQL 等；HTML 内嵌脚本与样式保持跨行状态。来源、哈希、差异和完整许可位于 `agent-ui/src/commonMain/composeResources/files/code/`。
- 内联代码参照 Kelivo，使用带 5×3 dp 留白、6 dp 圆角的中性浅底胶囊与深色等宽文字；长代码可分段换行，原文仍参与选择复制。Markdown 表格使用统一列宽、对齐网格、细边框与浅表头，保留左右/居中对齐和转义竖线，窄屏多列表格可横向滚动。Markdown 中的绝对路径、带目录相对路径和文件链接可点击；相对路径以当前会话工作区解析，打开、安装和下载均通过当前容器的文件服务，不把容器路径当宿主文件；同样支持 `app-release.apk (app/build/outputs/apk/release/app-release.apk)` 这种输出格式。
- 聊天文件路径操作仅保留对应类型的动作：目录为“打开”“打包下载”；普通文件为“打开”“打开所在文件夹”“编辑”“下载”。目录在当前容器的独立浏览器中打开，返回保留聊天滚动和草稿；文件打开复用系统查看器及 APK 安装器。编辑使用原生代码编辑器，可显式保存并确认舍弃；通过客体进程暂存后原子替换，保留文件权限，保存失败保留草稿，检测到外部内容变化时拒绝覆盖。仅支持最大 2 MiB 的有效 UTF-8 文本，拒绝二进制、链接、虚拟路径与挂载根，未知语言仍可作为纯文本编辑。
- Slash 菜单从当前会话 OMP 的原生命令目录加载并跟随动态更新；第一次输入 `/` 时才启动目录查询，不发送用户 prompt。当前内置命令匹配中文描述与 Material Symbols 图标；未知、扩展或被自定义覆盖的命令仍显示，使用默认图标且不显示描述。无参数 `/model`、`/cost` 保留应用内快捷入口，带参数的命令交给 OMP 原生解析。
- 同一次 ask 的多个问题共用一张卡片，左下角灰色无背景前后按钮配合左右非线性切换；问题 ID 固定答案、页码和预览状态。单选选择后自动进入下一题，多选停留当前题；切题和检查答案原位更新，不把会话滚回顶部。支持推荐项、选项描述、自定义输入、提交前回看与一次提交完整答案，提交后仍可查看；免审批不会自动回答普通提问。
- Agent 正文与 ask 富预览共用 Markdown、SVG/Mermaid 代码块与 Markdown 图像显示。点击图片进入原生全屏，可缩放、拖动、复位并保存；PNG/JPEG/GIF/WebP 保存已加载的原始文件，不截屏或转码，动画图像显示首帧但保留原始动画字节。SVG 保存安全校验后的矢量内容；全屏与保存不重复下载或解码，取消保存不提示成功，失败可重试。Mermaid 12.2.0 随 UI 离线打包，本轮不新增 Mermaid 导出。图像来源仅 HTTP(S) 与图像 data URL，不读取宿主文件；SVG 不允许脚本、外部资源、实体、事件或 foreignObject，错误内容显示中文原因。
- 用户消息与运行中追加消息即时本地显示，发送失败只撤回对应的未接收消息并显示原因；收到原生用户消息后，后续模型执行失败仍保留该消息。消息按发送时的 Todo 与前置节点定位，后续工具、思考、任务切换及历史重放不改变其位置。加载状态使用 Material 3 标准进度组件。
- 应用设置中的“新会话默认免审批”默认开启并持久化，只影响以后创建的会话；会话内仍能单独切换。开启免审批会立即回复已等待和后续工具审批，不自动回答普通提问，不改写全局工具权限配置；关闭时在下一轮启动恢复原生审批模式。
- 新思考节点和模型正文跟随创建时的活动 Todo 子任务，正文显示在任务子级；没有活动 Todo 时保持扁平。任务切换、完整快照与历史重放不迁移或重复既有节点，不改变用户消息锚点；Todo 全部完成或清空后的新总结仍显示在顶层。
- 用户消息和新一轮执行不重复追加未改变的 Todo；原生更新、提醒和完整状态按内容、状态与阶段判重，真正变化才生成新计划卡片，先前快照和用户消息位置保持不变。
- 返回聊天、切换会话或进入设置后，保留各会话的滚动位置、Todo/思考/工具展开状态及未发送草稿；主页筛选和相关设置页的滚动、展开状态独立保存。不跨会话共享草稿，也不承诺结束应用进程后恢复所有临时 UI 状态。
- 页面进入保持原有右侧滑入和非线性曲线；返回时当前页向右退出、上一级从左侧恢复。主页底栏“会话、容器、功能”按位置定向切换，保留各页已有状态。
- 模型服务支持 Chat、Response、Message 接口、真实连通性测试与远端模型同步；搜索框下方与首个列表项保留 16 dp 间距。模型及服务的通用入口图标统一为 Material Symbols `deployed_code` 立方体，已有模型品牌、服务首字标识及真正的思考控件保持不变。模型可编辑显示名、推理/图片能力、上下文与最大输出，以及输入、输出、缓存读取、缓存写入四项美元/百万 token 价格。
- 模型资料参考 ipmt 的目录匹配方式，联网读取主/备目录并缓存六小时；按唯一 ID 或规范化别名匹配，歧义或未知模型不猜测。新增模型可补全能力与价格；已保存模型须显式“应用匹配”，手动改过的字段保留。远端同步仅追加新模型，不覆盖既有编辑。
- 思考强度包含无、低、中、高、超高（`xhigh`）和最大（`max`）；支持接口自定义 effort 与 Message 数值预算，预算须至少 1024 且小于最大输出。两个最高档分别贯通原生 RPC、配置和请求参数，不互相合并。
- 模型列表与输入区模型选择按钮共用离线品牌图标并保留原色；按钮随当前模型切换，列表项始终展示各自模型图标。未匹配品牌的模型显示 ID 首字符的大写单字标识及圆形底色，底色按字母稳定生成，同字母同色，不使用服务商图标或通用脑图标替代。选中背景覆盖整行，弹出菜单动画保留外部阴影。品牌图标参照 Kelivo，DeepSeek 使用原蓝鲸；Codex、Kimi 因 Kelivo 对应资源的许可来源未能核实，使用 MIT 许可的 LobeHub 同品牌图标。来源与差异见 `agent-ui/src/commonMain/composeResources/files/model-icons/NOTICE.txt` 和 `provenance.json`。
- Agent 设置按 OMP Schema 展示，过滤外观及 TUI 专属项。外观独立保存在宿主设置中，不写回 TUI 配置。
- `models.yml` / `config.yml` 位于容器用户的 `~/.omp/agent/`，支持应用写入和外部修改回流。模型服务配置可能含 API Key，应留在本地数据目录，不要提交版本控制。
- Proroot 上游仅提供 Android arm64 版本；桌面使用真实 PRoot/QEMU 或具备 Root 权限的 chroot，不伪装支持 Proroot。
- Proroot 参照 DSHA 的显式 `host:guest` 绑定方式修复 `/dev` 等默认路径启动报错，工作区与自定义挂载同样显式映射。修复位于宿主启动参数，无需重新导入 Rootfs。
- Proroot 五库固定为作者 [andClaw 2.3.47 的同一提交](https://github.com/coderredlab/andClaw/tree/376460cc8e76f1652f51493de5180daceecb78c7/app/src/main/jniLibs/arm64-v8a)，逐文件校验 SHA-256 并原样打包；启动时显式选择当前 APK 配套的 runtime/linker，避免自动发现混用旧组件。该更新修复旧 syscall relay 破坏寄存器导致的 Bun 崩溃及原生模块加载失败；保留原 Bun、OMP 和 Rootfs，无需禁用缓存、屏蔽错误或加入兼容 shim，更新 APK 即可生效。
- 容器详情按原型重置操作按钮、工具链卡片、主题色分段控件和挂载 bottom sheet；保留原有功能。普通登录身份可填写容器中已有的用户名或数字 UID，实际启动使用该账户的 UID/GID、HOME、USER 与 LOGNAME，不固定为 `user`，不自动创建账户。
- 容器详情 → 挂载目录支持主机目录选择，主机路径右侧的文件夹按钮在 Android 打开系统目录选择器，Desktop 打开原生目录选择器；也可手填 `/sdcard/项目` 等实际主机路径并指定容器内挂载点。Android 11+ 按需申请“所有文件访问权限”，Android 8–10 请求储存读写权限，Android 10 使用传统共享储存访问；启动不自动申请。仅解析本机共享储存卷的目录，保存实际路径而非 `content://`，不复制或同步目录内容；取消选择、拒绝授权或保存失败保留草稿。云盘等文档提供方不能直接挂载；系统选择器不允许选择共享储存根目录或 Download 时，可在授权后手填路径。权限不绕过 Android 对其他应用私有目录的限制；只读保护仍仅由 chroot 提供。更新 APK 即可使用，无需重新导入 Rootfs。
- 强制只读绑定仅由 chroot 实现。Proroot / PRoot 对只读挂载明确报错，不会静默变为可写。chroot 需要真实 Root 权限，并在停止容器时回收所属进程、卸载挂载点。
- 多个部署项目按首次出现顺序显示；进度或错误更新原位替换，完成后仅移除对应项目，不因并发刷新反复换位。
- 容器终端是独立导航页，直接连接真实 PTY，不再使用命令输入框加日志模拟。Android 保留 Love2Droid 的黑色控制台、Maple Mono 字体及两行等宽快捷键栏，移植 Termux TerminalView/TerminalSession；挂载和窗口获焦后重建输入连接，点击终端唤出软键盘，快捷键不抢焦点。Desktop 使用 JediTerm 3.63 + pty4j 0.13.4。支持 ANSI、交互式 Shell、方向键、Ctrl-C、选择复制与窗口尺寸更新；离开页面关闭其终端进程，退出后保留输出并提示重新打开，不关闭其他会话的进程。
- PRoot/Proroot 启动前为宿主补充 GID 追加缺失的容器组名，保留已有 `/etc/group` 条目并原子更新，解决 `groups: cannot find name for ...`，不屏蔽报错。DNS 继续使用已有的默认网络监听与客体配置更新，无需替换 PRoot 二进制或重新导入 Rootfs。
- 工具链安装/卸载先确认，取消不执行，执行期间禁止重复触发；同一行卡片按较高内容等高，长版本自动换行。Git 版本去掉 `git version` 前缀。
- rootfs 支持 `.tar.gz`、`.tar.xz`、`.tar.zst` 导入，导出为 `.tar.zst`。归档路径和符号链接受边界检查；克隆/导出保留可执行及访问权限。文件系统允许时保留真实硬链接；Android 等环境拒绝创建硬链接时，导入器改为复制普通文件并保留权限和修改时间，不生成替代符号链接。此时文件不再共享 inode，可能增加磁盘占用。体积统计不遍历运行时挂载和 PRoot/QEMU 的临时宿主视图。
- 导入时以解压目录的真实路径统一校验符号链接，支持 Android 应用数据父目录的路径别名及合法的 `node_modules/.bin` 相对链接；仍拒绝作为解压目录的符号链接、路径穿越和真正逃逸的链接链。
- Android 容器启动及执行命令前使用当前默认网络的 DNS，并在网络变化时原子更新已运行容器的 `/etc/resolv.conf`；断网时保留最后一份有效配置。显式绑定 `/etc/resolv.conf` 或 `/etc` 时，以用户挂载为准。现有 Rootfs 的旧 DNS 会在运行时修正，无需重新下载或导入归档。
- Bun 会缓存 DNS 服务器；默认网络的 DNS 变化后，OMP 在下一次空闲发送时重启子进程并续接原会话，不中断正在执行的任务，不自动重发请求。独立宿主接入时，将同一个 `StateFlow<String?>` 同时传给 `RuntimePaths.resolvConf` 与 `OmpProcessPool` 的 `resolvConf` 参数；`null` 表示不覆盖现有 DNS。

### 当前容器的功能

底栏“功能”以两列卡片提供终端、文件、规则、插件、技能及容器声明的自定义功能。内置操作只针对当前选择容器；规则目录来自实际配置用户的 `~/.omp/agent/rules/`，支持目录浏览、新建、重命名、删除及原生全屏编辑，Android 使用 Sora，Desktop 使用 RSyntaxTextArea。文件读写与外部修改冲突处理复用客体文件服务，不从宿主直接操作 Rootfs。

插件与技能直接调用容器内已安装 OMP 的实现，不展示原型演示数据：

- “功能”入口管理用户级对象；聊天设置菜单中的插件/技能入口固定当前会话工作区的项目级对象，不提供跨范围切换。OMP 仅支持用户级的 npm、Git 和本地插件链接，项目入口不会假装能独立安装或卸载这些用户插件；项目可管理原生允许的市场安装与覆盖。
- 插件支持 npm、Git、本地链接与市场来源，真实安装/卸载、启停、可选功能、声明设置及来源覆盖、市场登记与缓存、诊断和更新；本地链接不伪装支持升级。风险操作确认后执行，原生耗时操作可请求取消，取消不保证撤回已经发生的外部副作用。
- 技能支持真实发现、加载过滤、别名/冲突与来源、完整原文及资源预览、创建/复制/保存/删除、外部修改冲突、Skillshare 搜索与包版本安装、锁定恢复、账号、发布预检、发布和维护。包或插件中的技能保持只读，修改先复制为独立技能；包含脚本的精确版本安装单独确认，不在安装时执行脚本。`.skill` 导入是上传转换并公开发布，不冒充本地安装。
- 技能市场默认打开独立的 [ClawHub](https://clawhub.ai/) 公开目录，支持真实浏览、下载量/最新/趋势排序、游标翻页、搜索、版本与资源预览；右上“切换来源”保留官方 Skillshare，原有包安装、锁定恢复、账号和发布管理不变。官方空库只表示该 Skillshare 服务当前没有包，不代表 ClawHub 或本地技能为空。
- ClawHub 安装固定发布者、技能及精确版本，保存全部原始资源并核验大小与 SHA256；GitHub 来源固定公开审核交接的提交和技能目录，核验 Git 对象及完整目录哈希，不安装整个仓库或静默改用最新分支。仅写入当前入口的用户级或项目级技能目录，不修改官方包声明/锁文件；同名目录拒绝覆盖，可改本地目录名后重试。`.wawlink-clawhub.json` 保存原版身份与安装时间，不认证后续本地编辑内容。
- 第三方技能预览不执行脚本、不自动加载外部图片或打开内容链接；安装也不执行脚本、依赖安装或改写 frontmatter。ClawHub/OpenClaw 与 OMP 的工具、依赖、权限和格式不保证兼容，扫描 `clean` 仍可能含警告；安装前必须阅读并明确确认精确版本风险。公开服务超时、限流及审核拦截显示实际类别，不绕过限制或自动重试。
- 两页的路径、版本、默认值和可操作项来自实际容器。秘密设置遮罩且错误脱敏；草稿在失败后保留。保存明确提示新会话或重启后生效，不把发现清单当加载成功证明，不伪造现有 RPC 没有提供的运行时重载。

### 容器声明的自定义卡片

统一目录为当前容器用户的 `~/.wawlink/features/<id>/`，不是宿主 HOME。`id` 为 1–64 个字母、数字、下划线或短横线，首字符须为字母或数字。每个功能包含 `card.json`、前台启动脚本 `start.sh`，以及可选的幂等收尾脚本 `stop.sh`。目录扫描仅读取声明，不执行脚本；添加后点击功能页刷新即可发现，不重建 APK 或 Rootfs。

`card.json` 为不超过 64 KiB 的 UTF-8 JSON 对象：

```json
{
  "icon": "dashboard",
  "title": "工作台",
  "description": "查看与处理当前项目",
  "lifecycle": "manual",
  "color": "#D9EAFD",
  "showStatus": true,
  "showMemory": true
}
```

`icon`、`title`、`description` 必填；图标使用 Google Material Symbols 小写名称，标题和描述使用中文。`lifecycle` 省略为 `page`，离开该功能即停止；`manual` 离页继续运行，由用户明确启停；新增 `container`，每次启动容器时自动发现并启动，不必先打开功能页，离页或隐藏卡片不停止。三者均在停止容器或关闭应用时回收，不承诺 Android 杀进程后继续运行。

`container` 每个真实运行代次只自动尝试一次，单个功能失败不阻塞其他功能或容器；扫描、启动及进程退出错误会真实提示。手动停止或失败后不会因刷新、切页或元数据更新反复拉起，下次容器启动再尝试；运行中新增的卡片可手动启动，或下次容器启动时自动运行。快速重启即使合并了中间的停止状态，也按新的运行代次处理；等待服务就绪时停止容器同样回收进程组，不反向启动已停止容器。

功能页以 `ref-plan/feature-cards.html` 为视觉基准，自定义卡片紧随五个内置功能。半宽原生卡片图标位于标题上方，整行卡片图标与标题/状态并排；不默认附加任何按钮。`cardSpan` 为整数 `1`（默认，半宽）或 `2`（占满两列，原生和 HTML 均支持）。长按进入顶部编辑栏，零选择仍保持编辑；可隐藏/显示、调色和卸载，只有全部选中自定义功能时才允许卸载。隐藏项在编辑模式底部和应用设置均可恢复。拖动保留原有抬起、跟手、边缘滚动和非线性换位。顺序、隐藏与原生颜色覆盖保存在当前容器用户的 `~/.wawlink/feature-cards.json`，不再保存在软件数据目录；文件不依赖容器 ID，随 Rootfs 导出、导入和克隆保留。导入后使用同一容器用户即可读取其设置；本次不迁移旧版未发布软件的测试设置。顶部胶囊切换全局当前容器，功能清单及设置沿用既有客体进程读取和按需启动语义，状态来自真实运行时。

功能页已加载的 HTML 卡片滚出可见区域后保留同一 WebView 和文档，滚回不重建或重载，网页内部状态继续保留；尚未加载的卡片仍按需创建。此保留仅用于滚动，不延长切换底栏后的页面生命周期；手动隐藏卡片、切换容器或销毁页面仍正常回收。内存占用随本页已加载的 HTML 卡片数量增加，原生卡片仍沿用懒加载。

原生卡片可选 `color` 为严格 `#RRGGBB`，用户调色覆盖它；默认色恢复清单颜色或应用主题。自定义底色生成同色系深色文字和图标，极深底色使用可读浅色。调色 bottom sheet 提供默认/七色、HEX 输入和实时预览，取消或保存失败不丢草稿。HTML 卡片自行决定完整布局和配色，不参与原生调色；仍提供 `--wawlink-card-color` 与 `--wawlink-card-foreground` 供作者主动使用，不覆盖显式背景/渐变/前景。`showStatus`、`showMemory` 默认为 `false`，内存为真实服务进程组 RSS，不可读时显示不可用。页面与卡片不展示生命周期说明，功能启动只显示水平垂直居中的标准圆形进度组件。

Desktop 的编辑和模态弹层期间使用当前 WebView 的固定合成快照，避免原生网页盖住选择标记、拖动层和 bottom sheet；保留同一网页实例与脚本状态，退出编辑/弹层后恢复原视图，不重载文档。Android 直接使用原生 WebView 并按卡片圆角裁剪；原生布局参数明确填满 Compose 分配的区域，不使用 `WRAP_CONTENT`，避免百分比高度及 `vh` 被计算为零而出现白屏。

#### 原生按钮

`actions` 是可选数组，最多三个，缺省不显示按钮；每项必须包含唯一小写 `id`、中文 `title`、Material Symbols `icon` 和 `type`。每张卡片只有一行按钮，等宽、44 dp 等高；半宽三个按钮隐藏全部文字但保留无障碍名称，整行保留图标/文字。启停过渡与脚本/Lua 执行显示标准进度组件并禁止重复触发，不使用假进度或模拟成功。

```json
{
  "icon": "dns", "title": "开发服务器", "description": "管理项目服务",
  "lifecycle": "manual", "cardSpan": 2, "showStatus": true,
  "actions": [
    {"id": "power", "title": "启动", "icon": "play_arrow", "type": "toggle"},
    {"id": "open", "title": "打开", "icon": "open_in_new", "type": "open"},
    {"id": "logs", "title": "日志", "icon": "receipt_long", "type": "logs"}
  ]
}
```

`open` 打开真实功能页，`start`/`stop`/`toggle` 使用既有服务生命周期，`logs` 查看实际服务和最近动作输出。`script` 需要功能目录内的相对 `script` 路径（例如 `tasks/backup.sh`），使用当前容器身份、实际功能目录和 `/bin/sh` 执行；`WAWLINK_FEATURE_ID`、`WAWLINK_FEATURE_DIR`、`WAWLINK_ACTION_ID` 提供执行上下文。脚本不脱离进程组，返回真实输出与退出错误，取消、停容器或关闭应用回收进程组。`lua` 需要非空内联 `lua`，例如 `{"id":"share","title":"分享","icon":"share","type":"lua","lua":"host.share('分享内容', '分享')"}`；直接复用下述 Android LuaJ 引擎，不创建隐藏 WebView，每次调用独立 globals。离开功能页或切容器取消仍在执行的按钮任务；任意不可中断 Java API 不保证立即终止。清单扫描不执行按钮。

#### HTML 卡片

在功能目录放置可选 `card.html` 即完整替换卡片区域，亦可用 `card` 指定目录内的相对 HTML 文件。静态 HTML 为不超过 256 KiB 的 UTF-8，可以内联，也可使用 HTTPS 资源、接口与 CDN，不再强制所有 CSS/JS 自包含。`cardHeight` 为 80–480 dp，默认 172 dp，半宽 HTML 至少填满同一行相邻卡片的高度。宿主不增加图标、标题、描述、按钮或内边距，仅裁剪 28 dp 圆角并提供选择遮罩。目录扫描不执行 HTML、Lua 或功能脚本，显示卡片才执行 JS；只有用户操作或 `container` 启动策略会启动服务。

静态卡片首个脚本即可读取 `window.wawlink.card`，字段为 `id`、`status`、`memoryBytes`（整数或 `null`）、`containerRunning`。动态 URL 文档等待原生初始化：`if (window.wawlink?.ready) init(); else addEventListener('wawlink-ready', init, {once:true});`。状态为 `stopped`、`starting`、`running`、`stopping`、`failed`；监听 `wawlink-card-update` 并读取 `event.detail` 获取真实增量，主题/数据更新不会重载文档。调用 `wawlink.action('open' | 'start' | 'stop')` 可放置自己的打开、启停按钮；错误仍由原生真实结果反馈。卡片和完整功能页面均注入主题变量及离线 Material Symbols，可使用 `material-symbols-rounded`；桌面自动将名称解析为打包字形。

动态卡片使用 `cardUrl`，与显式 `card` 互斥，且优先于自动发现的 `card.html`：

```json
{
  "icon": "monitoring", "title": "服务器状态", "description": "查看当前服务",
  "lifecycle": "container", "cardUrl": "/card/index.html", "cardSpan": 2, "cardHeight": 220
}
```

`/card/index.html` 等单斜线绝对路径指向该功能真实服务，宿主在就绪后接入随机端口和访问令牌，保留查询与片段；不把端口或令牌写死在清单。页面按真实 URL 加载，保留相对脚本、样式、图片、`fetch` 和 WebSocket 的基址。亦支持带显式端口的 `http://127.0.0.1:端口/路径`、`http://localhost:端口/路径`，直接加载已有本地服务，不附带本功能令牌；该服务由实际所有者管理，不因卡片显示而伪造启动或接管生命周期。禁止清单自填保留的 `__wawlink_token` 参数。自身路径在服务停止后显示启动入口，等待启停时仅显示居中进度。

推荐天气类使用 `cardSpan: 2`、`cardHeight: 176`，由 HTML 自行绘制整幅渐变、前景及天气图形；专注类使用半宽卡片，在完整视口中居中计时器。`html, body` 使用 `width:100%; height:100%; margin:0; overflow:hidden`，不要再嵌套一张有边距的卡片。需要主题配色时主动使用上述 CSS 变量，明确设置的颜色、渐变和非线性动画不会被宿主重写。天气、服务器指标必须来自真实接口；可以使用本地服务、外部 HTTPS API、WebSocket、常用前端框架或 Android 可信 LuaJ，正确处理网络失败、CORS 及系统权限，不把原型数据当实测数据。

以下是可独立运行的专注卡片正文（放入 `card.html`，`cardSpan` 省略或设 `1`），计时使用实际截止时间，不因回调延迟漂移；不预装到用户容器：

```html
<!doctype html><html lang="zh-CN"><head><meta charset="UTF-8"><style>
*{box-sizing:border-box}html,body{width:100%;height:100%;margin:0;overflow:hidden}
body{background:radial-gradient(circle at 30% 20%,#ff8a65,#e5484d 70%);color:white;
  display:flex;flex-direction:column;align-items:center;justify-content:center;gap:6px;
  font-family:system-ui,sans-serif;user-select:none}
button{position:relative;width:112px;height:112px;border:0;background:none;color:inherit;padding:0}
svg{position:absolute;inset:0;transform:rotate(-90deg)}circle{fill:none;stroke-width:9}
.track{stroke:rgba(255,255,255,.25)}.progress{stroke:white;stroke-linecap:round;stroke-dasharray:314}
time{position:absolute;inset:0;display:grid;place-items:center;font-size:26px}
p{margin:0;font-size:16px}
</style></head><body>
<button id="toggle" aria-label="开始或暂停专注"><svg viewBox="0 0 112 112" aria-hidden="true">
<circle class="track" cx="56" cy="56" r="50"/><circle id="ring" class="progress" cx="56" cy="56" r="50"/>
</svg><time id="clock">25:00</time></button><p id="label">点按开始专注</p>
<script>
const duration=25*60*1000;let remaining=duration,deadline=0,running=false;
const clock=document.getElementById('clock'),ring=document.getElementById('ring'),label=document.getElementById('label');
function draw(){
  if(running)remaining=Math.max(0,deadline-Date.now());
  const s=Math.ceil(remaining/1000);
  clock.textContent=String(Math.floor(s/60)).padStart(2,'0')+':'+String(s%60).padStart(2,'0');
  ring.style.strokeDashoffset=314*(1-remaining/duration);
  if(running&&!remaining){running=false;label.textContent='完成';}
}
document.getElementById('toggle').onclick=()=>{
  draw();if(!remaining)remaining=duration;
  running=!running;if(running)deadline=Date.now()+remaining;
  label.textContent=running?'专注中':'已暂停';draw();
};
const timer=setInterval(draw,250);addEventListener('pagehide',()=>clearInterval(timer),{once:true});draw();
</script></body></html>
```


#### Android LuaJ 与系统调用

卡片和完整功能页均提供 Promise API：`wawlink.lua(source)`、`wawlink.openBrowser(url)`、`wawlink.openUrl(url)`、`wawlink.share(text, title?)`、`wawlink.launchApp(packageName)`。Lua 使用真实 LuaJ 标准库及完整 `luajava` 互操作，不增加 WawLink 审批或 Android API 白名单；这是用户可信代码执行，仍受 Android 系统权限、应用 UID、系统版本和设备能力限制。

Lua 提供 `context`、`activity` 和 `host`，并提供同名全局助手 `openBrowser`、`openUrl`、`share`、`launchApp`。需要 UI 线程的 Java 调用使用 `host.runOnUiThread(function)`；普通 Lua 在串行后台执行器运行。Lua 可返回 JSON 值或连续数组/字符串键表，多返回值变为数组；循环、非有限数字、函数和不可序列化 Java 对象会拒绝 Promise。导航和销毁取消旧文档任务，真实异常通过 Promise 拒绝返回，调用方应显示错误。例如：

```javascript
await wawlink.openBrowser('https://example.com');
await wawlink.share('待分享的文字', '分享');
const sdk = await wawlink.lua(`
  local Build = luajava.bindClass('android.os.Build$VERSION')
  return Build.SDK_INT
`);
```

桌面支持 HTML、实时数据及卡片动作；Android Lua/系统助手明确拒绝，不伪造 Android API。旧系统 WebView 缺少来源限定消息桥或文档起始注入能力时显示可定位错误，不回退到无来源保护的桥。

启动脚本由 `/bin/sh` 在功能目录运行，需以前台方式 `exec` 真实服务，不使用 `nohup`、守护化或脱离进程组。容器需有 `setsid`（util-linux）。服务读取以下环境：

| 环境变量 | 契约 |
| --- | --- |
| `WAWLINK_HOST` / `WAWLINK_PORT` | 只监听提供的 `127.0.0.1` 与每次启动分配的端口，不绑定公网地址或固定端口 |
| `WAWLINK_TOKEN` | 每次启动独立令牌；校验请求的 `Authorization: Bearer …` 或 `X-WawLink-Token`，不写入代码、日志或持久配置 |
| `WAWLINK_FEATURE_ID` / `WAWLINK_FEATURE_DIR` | 当前功能身份与客体目录 |
| `WAWLINK_THEME_CSS` | 当前 Material 3 配色；页面使用注入的 `--md-sys-color-*` CSS 变量 |

服务须在 30 秒内对根路径的带令牌 GET 返回 2xx/3xx；实际 HTTP 就绪后才打开 WebUI。Android 使用 WebView，Desktop 使用 JavaFX WebView。主文档导航和原生桥限定当前受信来源；资源与连接允许 loopback HTTP/WS、外部 HTTPS/WSS、接口及 CDN，仍遵守浏览器 CORS 和服务自身更严格的 CSP。关闭宿主 file/content 访问、子框架和 object；Android 可信 Lua/系统助手是显式宿主入口，不是 Web 跨域导航。外部脚本在受信页面内执行时拥有该页面桥能力，仅使用用户信任的依赖。网关支持同源资源、表单与 WebSocket；普通 HTTP 单次请求/响应上限 32 MiB，上传不接受 HTTP 分块传输。页面默认沿用白底、中文大字号、Material 3、离线 Material Symbols 与非线性动画，卡片按用户设计自由布局和配色。脚本及可信 Lua 不是不可信代码安全沙箱。

应用在容器运行接入、原生 Agent 启动前或读取功能页时自动检查 `~/.omp/agent/skills/wawlink-functions/SKILL.md`；缺失时安装，在同目录 `.wawlink-builtin.sha256` 记录已安装正文的 SHA-256。软件内置内容更新后，容器正文仍匹配安装记录才原子同步新内容和哈希；用户修改过的正文及原记录保留不动，不仅改记录冒充内容已更新。没有记录的旧容器仅在正文精确匹配已发布内置版本时接管升级；之后支持跨软件版本更新，不再依赖单个写死的上一版哈希。更新中断可按暂存记录的前后哈希恢复；内容已一致时不重复写文件。检查不会重开已停止容器，也不伪造现有 Agent 的运行时重载，新 Agent 启动前会读取更新后的文件。Skill 包含 `container`、动态 `cardUrl`、外部接口/依赖及初始化时序契约，不强制 HTML 仅用内联资源。它**仅在用户明确要求“添加 WawLink 功能页卡片”或等价新增请求时触发**，普通网页、脚本、插件/技能或笼统功能请求不创建卡片。不预装示例卡片，不复制开发机认证。

#### 容器内轻量调试命令

`wawlink-feature` 复用容器已有 Bun，连接应用当前挂载的真实 WebView，无需 Chrome、Chromium、Playwright 或 ADB。应用为运行中的容器安装到当前用户 `~/.wawlink/bin/`，新建 Agent/终端进程的 PATH 包含此目录；旧 shell 可直接使用完整路径。更新 APK 不需要重新导入 Rootfs。

```bash
wawlink-feature list
wawlink-feature inspect weather --surface card
wawlink-feature logs weather --surface card
wawlink-feature eval weather 'getComputedStyle(document.body).height' --surface card
wawlink-feature screenshot weather --surface card --output /tmp/weather-card.png
wawlink-feature reload weather --surface card
wawlink-feature inspect weather --surface page
wawlink-feature eval weather 'document.querySelector("button").click()' --surface page
wawlink-feature screenshot weather --surface page --output /tmp/weather-page.png
```

将 `weather` 替换为实际功能 ID。`--surface card`（默认）选择功能列表中的 HTML 卡片，`--surface page` 选择打开后的完整功能页；按容器、功能 ID 与页面种类隔离，不根据标题或 URL 猜测。`inspect` 提供实际生命周期、服务状态、挂载状态、原生尺寸及 DOM 计算布局，便于区分未启动服务与高度为零等布局故障。`logs` 合并有界的服务输出、页面 console、JavaScript 异常、未处理 Promise 拒绝及资源错误；页面未挂载时仍能读取服务输出，不伪造页面日志。

`eval` 返回实际 JSON 值并支持 Promise；异常和拒绝按真实错误退出。`screenshot` 保存当前可见 WebView 的实际 PNG 到客体路径，拒绝覆盖已有文件；不包含宿主 AppBar 或其他页面。`reload` 只重载所选文档，静态卡片重新读取当前 HTML，动态文档重新加载实际 URL，不冒充重启服务。服务端代码修改仍须按真实生命周期启停。

调试不会自动启动容器、服务或打开页面。尚未加载或被手动隐藏的卡片、尚未进入的功能页可能未挂载，`inspect` 明确显示未挂载，执行、截图和重载返回可定位错误；不能用 HTTP 就绪或另一份离屏浏览器冒充应用画面已验证。日志有界且只覆盖已捕获范围，重载后的新文档与旧请求隔离。

通道使用随机 loopback 端口和当前容器运行代次凭据，不向网页开放公共 CORS，容器停止或应用关闭即撤销；凭据不出现在命令结果中。`eval` 具有受信页面原有的 LuaJ/Java 桥权限，是可信代码执行，不是不可信脚本沙箱。新 Skill 强调天气、监控等随容器持续运行的托管服务应按需求显式声明 `lifecycle: container`；默认 `page` 不会因存在动态卡片而自动改变。


## 独立嵌入 UI

```bash
./gradlew :agent-ui:assembleRelease :agent-ui:desktopJar \
  :agent-protocol:assembleRelease :agent-protocol:desktopJar
```

Android AAR 位于各模块的 `build/outputs/aar/`，Desktop JAR 位于 `build/libs/`。外部宿主需同时引入协议库及 Compose / 协程等正常运行依赖；不需要引入 OMP 驱动或容器模块。

富图形的 Android 宿主需要可用且较新的 Android System WebView。Desktop 同时需要当前平台的 OpenJFX 21.0.13 `base/graphics/controls/media/web` 运行依赖（兼容 JDK 17），Linux 需要 GTK 3 及 JavaFX WebKit 的原生库；Gradle 工程已按平台声明。富图形在桌面使用无窗口 WebView/Canvas 生成原生 Compose 图片，不嵌入 Swing 子窗口，不需要 interop blending 参数；全屏代码查看单独使用原生 Swing 代码组件。Mermaid 来源与 MIT 许可位于 `agent-ui/src/commonMain/composeResources/files/rich/`；分发 OpenJFX 运行库时保留其 GPL/ClassPath exception 及 WebKit 第三方通知。

Desktop 宿主若使用全屏代码查看器，需在创建界面前设置 JVM 属性 `compose.interop.blending=true` 与 `compose.swing.render.on.graphics=true`，使混合画布中的三点菜单显示在原生编辑器上方；本项目桌面入口已设置。

`dev.wawlink.ui` 的入口：

| 入口 | 宿主提供的契约 |
| --- | --- |
| `AgentChatScreen` | `AgentSessionController`、`ProviderConfigRepository`、同容器/项目的 `AgentManagementRepository`、返回/新建/设置导航、挂起的主机与容器附件选择回调、可选 `AgentAppearance` |
| `ProviderSettingsScreen` | `ProviderConfigRepository`、返回回调 |
| `AgentManagementScreen` | `AgentManagementRepository`、`ProviderConfigRepository`、返回回调；可选会话、初始分区及返回处理注册 |
| `AgentConfigScreen` | `AgentSettingsRepository`、返回回调；对应框架设置 |
| `AgentTheme` | 宿主外观状态；设置页可置于同一主题内 |
| `CodeDetailScreen` | 标题、原文、可选语言、返回回调；外层提供 `AgentTheme` |
| `EditableCodeScreen` | 标题、原文、可选语言、返回及保存回调；可选错误、保存状态及返回处理注册。返回前确认未保存修改，保存期间禁用编辑与退出，失败保留草稿 |
| `AgentActionSheet` / `AgentActionRow` | WawSSH 风格的全宽操作列表；标准拖拽条、可选标题、图标与标题/必要副标题，不嵌套纵向滚动容器 |
| `LocalAgentImageSaveHandler` | 宿主提供 `suspend (name: String, mimeType: String, bytes: ByteArray) -> Boolean`；保存成功返回 `true`，取消返回 `false`，异常由查看器显示可重试错误。应用已接入 Android 系统文档选择器与 Desktop 原生保存窗口 |

导航、文件选择、认证和 Agent 实现由宿主负责。协议使用 `StateFlow` / `Flow`；UI 只消费状态与控制契约，没有对具体驱动或容器的引用。

`AgentSessionController.loadSlashCommands()` 负责按需加载目录，`ChatSessionUiState.slashCommands`/`slashCommandsError` 提供命令元数据与加载错误；目录不会作为历史重放。`respondAsk(nodeId, answers: List<AskAnswer>)` 一次接收同一原生请求的完整答案，以 `questionId` 对齐，不再按每道题分别调用。

`AgentSessionController.sendPrompt()` 在运行中发送原生 steer；`enqueuePrompt(text, attachments)` 发送原生 follow-up。`takeQueuedPrompt(id)` 挂起到对应队列消息成功撤出后，返回可重新编辑的 `RestoredSessionPrompt`；已消费或移除失败则抛出原因，不先假移除。`QueuedPrompt.queue` 保留 steering/followUp 种类。

`AgentChatScreen` 的 `onPickFiles` 与 `onPickContainerFiles` 均由宿主提供；容器选择返回 `source = AttachmentSource.CONTAINER` 的 `AttachmentFile`，`path` 必须是当前容器的真实绝对路径。驱动在容器内校验普通文件并读取图像字节，消息与历史保留附件来源；不把工作区之外的容器附件伪装成项目文件。

文件路径交互由宿主通过 `LocalAgentFileLinkHandler` 提供当前会话的处理器；没有处理器时，不把文件路径交给系统 URI 打开。宿主须把相对路径解析到该会话工作区，再通过当前容器检查并流式打开/下载。Android 使用非导出 FileProvider 和临时读取授权交给系统查看器或 APK 安装器，仅共享应用缓存中的文件；Desktop 使用已有系统文件关联，APK 安装明确提示仅 Android 支持。

## 预置环境与产物

固定输入：Ubuntu Base 24.04.4 arm64、Bun 1.3.14、Node 22.22.2、OMP 18.12.6，以及 Git、ripgrep、fd、jq、Python、curl、xz、zstd。OMP 使用完整 npm 生产依赖及原生依赖，不以替身命令代替。初始项目位于 `/workspace`，默认工具策略为 `always-ask`。

当前交付产物的实际体积（本轮未重建 Rootfs，保留已有归档与校验文件）：

| 产物 | 字节 | MiB |
| --- | ---: | ---: |
| Rootfs 文件内容合计 | 1,543,298,008 | 1471.80 |
| `build/outputs/ubuntu-main-debug.tar.zst` | 404,312,713 | 385.58 |
| Android Debug APK（不含 Rootfs） | 56,412,538 | 53.80 |

按已确认的完整功能优先策略，不执行原计划的 150–220 MB 硬上限。每次构建会重新生成 `.sha256` 与 `.sizes.json`，以后者的实际输出为准。构建缓存、Rootfs、APK、原生二进制和本地会话均不纳入源码提交，准备脚本可重建缺失输入。

原生程序的版本、完整性、依赖加载和许可证，以及字体来源，见 [`scripts/USAGE.txt`](scripts/USAGE.txt)。Proroot 为上游专有许可证；本项目不修改其二进制。字体及原生组件的上游许可通知随资源保留。

## 验证

可重复、无计费请求的回归测试：

```bash
./gradlew :container-runtime:desktopTest :agent-driver-omp:desktopTest \
  :agent-ui:desktopTest :agent-ui:testDebugUnitTest :app-desktop:desktopTest
node --test agent-driver-omp/src/jvmSharedTest/resources/model-payload.test.mjs
```

常规单测不使用真实模型认证，不在后台发出计费请求。真实 RPC 验收使用单独的本地运行环境，与预置归档、APK 和默认用户数据隔离。

已验证 Android 编译打包、独立 UI AAR/JAR、APK arm64-only 和 13 个上游原生程序逐字节校验。当前 APK 不含 Rootfs 归档，Rootfs 作为独立文件按 SHA256 校验。实际 Linux PRoot/QEMU 容器已验证发行版下载、三种归档导入、克隆、默认切换、Git 安装/卸载、绑定和环境变量、流式终端，以及停止时仅回收所属进程树。

此前完整回归通过 95 项 Kotlin 测试（容器 19、驱动 68、UI 8；无失败或跳过），覆盖即时消息与失败回撤、重复文本隔离、排队编辑/删除、审批持久化、用户消息定位、Todo 内思考归属及流式/终态重放、展开状态保存，以及现有 DNS、归档、配置同步、原生历史、模型参数与 Markdown 边界。4 项 Node 测试通过，验证 Message 请求预算与跨服务隔离。

真实模型验收仅使用用户确认的 `oc/deepseek-v4.1-flash`，已跑通双会话独立计时、Todo/扁平工具、审批、选择题、附件、排队编辑/删除、原生分支续聊和迁移续聊。桌面实际界面已检查主页展开/进展摘要、双层吸顶及回跳、工具详情、聊天标识符、容器终端和设置页；不把这些桌面结果替代为设备端验收。

本次修复使用完整交付 Rootfs，在注入文件系统硬链接拒绝的条件下完成真实解包，并在新解包的 arm64 PRoot/QEMU 容器内运行 Node、OMP、Git 和报错涉及的 `string-width` 模块；文件内容、权限和修改时间均通过检查。这验证了兼容分支，不替代设备端验收。

部署列表与符号链接修复另通过实际桌面界面验证：两个部署项目交替更新时保持原位，两份完整交付归档经由父目录别名并发导入成功，完成后部署记录移除；两个新容器的 Node、OMP、Git 与 `mnemopi --help` 均实际运行通过。该修复仅需更新 APK，现有 Rootfs 归档不变，不涉及下载源或 DNS 调整。

模型预算扩展及 Agent 设置闪退修复已验证 APK 与驱动 AAR 的两份资源逐字节匹配源码；隔离加载 AAR 的 Android 驱动类，并仅从实际 APK 读取资源，完成设置 Schema 初始化、开关状态修改及 YAML 持久化。由该 APK 安装出的预算脚本已通过 Node 执行真实请求参数转换与跨服务隔离检查，无计费请求；不将这些检查视为 Android 真机点击验收。

OMP 启动修复已在完整 arm64 Rootfs 内验证：先复现旧格式解析失败，再由实际会话启动流程自动修复配置；真实 `oc/deepseek-v4.1-flash` 返回指定内容且会话进入 `DONE`。该次推理通过本地隔离网关使用既有宿主认证，未将开发机认证复制进 Rootfs。另用真实主机 ELF 的 `libtalloc.so.2` 依赖及 PRoot/QEMU 验证无预加载启动、失效 APK 库路径更新和客体环境隔离；这不等同于验证 Android Bionic/SELinux。

DNS 修复已在完整交付 Rootfs 的 arm64 PRoot/QEMU 容器内验证：先复现失效 DNS 超时，确认当前 Bun 保留旧进程的 DNS 缓存，再验证新进程解析公共域名、HTTPS 返回 200、断网保留有效 DNS 及显式 DNS 挂载优先。真实 `oc/deepseek-v4.1-flash` 会话在 DNS 变化后更换子进程，续接上下文并正确返回首轮记住的字符串；认证仅经隔离网关提供，未复制到 Rootfs。Rootfs 构建脚本不再将构建机的 systemd stub DNS 带入新归档，已有交付归档保持不变。

会话体验调整已在完整 arm64 PRoot/QEMU 环境用真实 `oc/deepseek-v4.1-flash` 验证：首条消息在原生进程启动前可见，手动模式等待后切到免审批，随后 5 个工具审批均自动放行，完成 Todo、文件写入与读取；默认免审批的新会话执行真实 Bash 命令成功。另验证实际启动失败撤回消息、会话模式及历史恢复、应用默认值与外观设置交叉保存和重启。真实桌面检查开关默认开启、关闭后新会话模式及重启保持；该轮模型未返回思考块，Todo 内思考通过原生格式事件回放与回归用例验证，不将回放标为模型实际生成。

本轮另运行真实桌面界面：展开 Todo、思考与工具、滚动聊天并输入草稿，往返模型设置及切换另一个会话后，原会话消息区域截图逐像素一致；另一会话没有串入草稿。已检查菜单动画中的阴影、整行模型选中背景、品牌图标切换，以及模型编辑保存后的上下文、输出、四项价格和最大思考档位。真实目录联网匹配、唯一别名与未知 ID 行为通过独立烟测；原生 OMP RPC 分别设置 `xhigh`、`max` 并由 `get_state` 回读确认，未发送 prompt 或产生模型推理请求。历史 journal 回放及重复终态快照保持“前置工具 → 用户消息 → 思考 → 后置工具”的 ID、顺序与所属 Todo。Android APK 和 Desktop JAR 构建通过；不将这些结果标为 Android 真机交互验收。

模型图标修正另通过实际桌面检查：在同一 OpenAI 服务下切换三个未知模型和 DeepSeek，按钮与列表按模型独立呈现；像素采样确认同首字母同底色、不同首字母不同底色。3 项新增回归覆盖 ID 首字符、大小写/后缀无关的配色、26 个字母的颜色区分及文本对比度。UI 测试、Android Debug APK 和 Desktop JAR 构建均通过，无模型推理请求。

Slash、Ask 与富媒体本轮回归通过 109 项 Kotlin 测试（驱动 74、UI 35，无失败或跳过），Android Debug APK 与 Desktop JAR 构建通过。真实 OMP RPC 发现 52 条命令（含临时扩展），运行 `/todo help`，并验证同次 ask 的两道题按原生顺序一次提交、历史恢复及过期命令目录不重放；没有模型推理或认证使用。

实际桌面已操作动态命令菜单、未知命令发送、多问题前后切换、推荐单选、多选、自定义输入、切页保留、完整答案检查与提交；正文及 Ask 预览均显示 SVG、Mermaid 和 PNG 图像，图表渲染生成有效彩色像素且始终仅一个应用窗口。另检查无效 Mermaid、不安全 SVG 与本机文件图像地址均显示中文原因，不执行内容。Android 富媒体仅完成编译打包，未将其标为设备端 WebView 渲染验收。

本轮八项体验优化回归通过 153 项 Kotlin 测试（容器 19、驱动 86、UI 48，无失败或跳过）；Android Debug APK 与 Desktop JAR 构建通过。实际 APK 仅含 arm64-v8a 原生库，包含新终端 JNI 和代码高亮许可，不含 Rootfs。

桌面实际操作验证实时耗时、三层吸顶及回跳、流式思考展开/选择、思考 Markdown、正文与读取工具代码高亮、全屏只读和原文复制。流式输出时，阅读选择区域间隔 2.2 秒的截图逐像素一致；全屏复制的 7,011 个字符与原文一致，键入不能修改内容。原生格式事件烟测确认下一轮 Todo 未变仍只有一份计划、状态变化后才追加，并保留先前计划。

真实 arm64 Alpine PRoot/QEMU 容器验证独立终端的 ANSI 颜色、`tty`、Ctrl-C、历史方向键和复制，窗口调整后 `stty size` 从 `45 48` 更新为 `50 85`。同一隔离容器实际完成 Git 安装/卸载；打开或取消确认时包数据库校验值不变，确认后才改变，版本只显示 `2.47.3`。另用隔离的长版本文本样本检查同一行卡片等高，样本已移除。本轮不使用模型认证或计费推理；Android 的 Sora、TerminalView/JNI 仅完成编译打包，不标为真机交互验收。

后续十项优化通过 225 项 Kotlin 回归（Desktop UI 54、Android JVM UI 53、驱动 86、容器 27、文件服务 5，无失败或跳过），Android Debug APK 构建成功。实际 APK 仅含 arm64-v8a，61 份固定 TextMate 资源的字节数与 SHA256 全部匹配来源清单，包含终端字体与 OFL 许可，不含 Rootfs。

桌面实际操作验证单选自动切题、多选停留、检查答案不跳顶、展开动画与恢复后的平滑思考、未展开命令高亮、发送后空闲用量保持，以及项目文件连续展开多级目录不重叠。真实容器界面验证文件浏览、HTML 原生全屏高亮与返回状态、工作区目录回填且不丢表单、取消附件选择保留草稿，以及跨工作区选择两个文件返回输入区。

真实 arm64 Alpine PRoot/QEMU 烟测通过 10 MiB 原始字节导入导出、含空格/引号/换行的文件名、文本原文、复制重命名、非空目录删除预检、覆写拒绝、导入失败清理、链接拒绝与 ZIP 内容检查；实际终端运行 `id`、`groups`、`stty size`，缺失补充组名已修复且无 `cannot find name`。容器外部路径图像附件经真实客体读取，验证 RPC 请求字节、即时消息对齐、无效附件回撤与历史重放；RPC 回执使用受控传输，不标为真实模型验收。本轮没有模型推理请求或认证使用。

Sora 实际 TextMate 词法器另在 JVM 上验证全部语言映射、HTML 内嵌 JS/CSS、多行状态、明暗主题及 CRLF/UTF-16 原文保留；Android 原生 Sora 视图和软键盘只完成编译打包，不标为真机交互验收。

新增 Agent 设置已在完整 arm64 PRoot/QEMU 容器中验证：读取 OMP 18.12.6 的真实工具、Agent、模型与角色目录；全局/项目 Agent 定义读写、未知 frontmatter 保留、项目遮蔽及删除后回退；独立的 `xhigh` / `max` 持久化；模型模式思考后缀、Prewalk、顾问、请求预算、服务等级、压缩阈值与账号池覆盖的写入及清除；顾问共享配置的作用域隔离，以及原始配置版本冲突拒绝且保留外部修改。

桌面实际新建 Agent、从原生目录选择 `read` 工具，通过全屏 RSyntaxTextArea 输入并保存多行中文 Markdown；写出的定义与原文及工具选择一致。该轮没有模型推理请求，也未复制开发机认证。Android 可编辑 Sora 仅完成编译打包，不标为设备端输入与软键盘验收。

原生模型配置另先复现了同进程中写入后仍读取旧 YAML 的问题；修复后，自定义服务及模型新增、名称修改、删除均即时回流原生目录，角色预览读取新模型及 `max`，脱敏导出不含注入的测试标记。不可用的有效默认角色会在 AI 生成前明确拒绝请求；未将这一失败路径标为真实模型成功生成或 OAuth 登录验收。

最终构建通过 225 项 Kotlin 回归（Desktop UI 54、Android JVM UI 53、驱动 86、容器 27、应用文件服务 5）及 4 项 Node 回归，无失败或跳过。Android Debug APK 和 Desktop JAR 构建成功；实际 APK 中四份原生管理桥资源逐字节匹配源码，仅含 arm64-v8a 原生库且不含 Rootfs。本次未重建或修改交付 Rootfs 归档。

Agent 设置加载与闪退修复后通过 235 项 Kotlin 回归（Desktop UI 54、Android JVM UI 53、驱动 92、容器 27、应用 9）及 4 项 Node 回归，无失败或跳过。Android Debug APK 与 Desktop JAR 构建成功；实际 APK 的五份原生管理桥逐字节匹配源码，仅含 arm64-v8a，不含 Rootfs，交付 Rootfs 归档保持不变。

真实 arm64 PRoot/QEMU 管理流程在 `-Xmx256m` 下读取 5,952 个模型、16 个角色和 8 个 Agent：配置首屏数据 8.9 秒就绪，完整模型目录 23.2 秒就绪，采样堆峰值 219 MiB。已实际验证按需读取完整模型、服务新增/修改/删除、脱敏导出、无效模型配置的就地修复和旧 YAML 格式迁移；未调用模型推理、登录或复制开发机凭据。这是桌面 JVM 的堆测量，不是 Android 真机内存测量。

真实桌面另验证 360 / 460 像素窗口的默认密度、作用域锚定 popup menu、大目录模型搜索，以及原生管理子进程退出后界面保留配置和重试入口、无效模型配置下仍可进入模型服务。历史会话按需恢复及并发打开复用由实际状态断言回归覆盖；启动不再批量创建未打开历史的控制器。整体缩放在应用设置中持久化，原生代码视图、图表和终端按同一应用缩放协调。

Provider 与默认缩放调整后通过 253 项 Kotlin 回归（Desktop UI 58、Android JVM UI 57、驱动 100、容器 27、应用 11）及 4 项 Node 回归，无失败或跳过；Android Debug APK 与 Desktop JAR 构建成功。实际 APK 的六份原生管理桥逐字节匹配源码，仅含 arm64-v8a 且不含 Rootfs。

真实 arm64 PRoot/QEMU 读取 97 个原生 Provider，其中 5 个可用、23 个注册 OAuth；验证无凭据内置项禁用、认证目录获取及已有模型保留、禁用后重新启用、无自定义声明时的内置模型覆盖，以及非法字段诊断和拒绝写入。`-Xmx256m` 下再次读取 5,952 个模型，配置与完整目录分别在 8.4 秒、23.0 秒就绪，采样堆峰值 219 MiB。

实际桌面检查新版默认 100%、360 / 460 像素布局、服务分组与禁用灰显、蓝色获取按钮、重开表单回顶、内置 OAuth 入口和模型编辑；点击“获取模型”产生受控服务记录的认证 GET，追加两项模型。实际非法保存显示缺失 `baseUrl` 的原生原因，原配置 SHA256 不变，API Key 与已编辑模型草稿仍保留，修复后可直接重试保存。本轮未执行模型推理、OAuth 登录或复制开发机凭据。

另在桌面实际复现并修复了未显式声明协议、尚无模型的服务保存后从列表消失；重开应用后的原生目录与用户列表均保留该服务，未把未知协议伪装成 Chat。

模型设置的“使用默认值”只移除覆盖，不创建 `thinking: {}` 或补写控制方式；清除控制方式时移除整个可选思考对象，必填结构未完成时在模型表单内提示。默认选项在当前值及菜单中用灰字显示原生实际默认值，例如“使用默认值 对话”；默认值按需调用 OMP 原生模型构建与目录继承读取，不物化为配置。没有固定值时显示未指定或跟随会话。放弃同时恢复模型 ID、字段与认证选择，清除保存错误并关闭提示；错误提示避开底部操作栏。

本轮通过 234 项 Kotlin 回归（Desktop UI 62、Android JVM UI 61、驱动 100、应用 11）及 4 项 Node 回归，构建 Android Debug APK 与 Desktop JAR。真实 arm64 PRoot/QEMU 验证未配置思考可省略、空对象被拒绝，以及普通、内置引用与 Runner API 的原生默认值；真实桌面 360 / 460 像素验证默认值灰字、重复默认选择不产生草稿及文件变更、模型 ID/名称放弃恢复、默认选择后成功保存、整段清除思考覆盖，以及错误提示仍显示时立即放弃。保存前后由原生 Schema 检查配置，未编辑模型保留原值，拒绝保存时原文件哈希不变；未调用模型推理或 OAuth 登录。

模型选择器固定按 OMP 原生 `available` 状态过滤，服务筛选同样只列有可用模型的服务，移除可关闭的“仅可用”开关；默认/自定义角色、Agent、顾问、预设、允许范围、故障转移和当前会话的管理选择入口共用该规则。聊天输入区的快捷菜单同样读取当前容器/项目的原生目录，仅列可用的对话模型，包含未写入自定义模型文件但已通过原生认证的模型。管理目录与已有绑定保留，不删除配置，也不限制手动高级选择器。Desktop 与 Android JVM 的 123 项 UI 回归通过；真实桌面验证未配置认证的 DeepSeek 等内置模型从候选及搜索结果消失，原生可用的免认证模型仍可选入角色草稿。

新会话在首次发送前，通过同容器、同项目共享的原生管理桥读取默认角色的独立服务/模型 ID，而不是从带路由信息的显示选择器拆分。加载时使用 Material 3 标准进度组件；成功后直接显示当前模型品牌图标及选中项，失败仍可进入模型设置，不显示空 ID 的问号。晚到的结果不覆盖手动选择、历史或运行中 RPC 状态；预览不写配置，也不把默认模型作为显式启动参数，真正启动仍遵循 OMP 原生默认/恢复逻辑。

本轮最终通过 237 项 Kotlin 回归（驱动 103、Desktop UI 62、Android JVM UI 61、应用 11），包含默认模型发送前可见、迟到默认值不覆盖手动选择、选择历史重放及配置失败结束加载；修复前新增默认模型用例超时失败。真实桌面 360 / 460 像素已验证新会话从标准加载组件切换到 DeepSeek 图标，菜单选中 `oc/deepseek-v4.1-flash`；最终构建验证聊天原生目录加载、当前模型选中，以及切换后菜单选中项和按钮图标同步，过程中未发送消息。Android Debug APK 与 Desktop JAR 构建通过，APK 中六份原生管理资源与源码逐字节匹配。

真实 arm64 PRoot/QEMU + OMP 验证：发送前为 `oc/deepseek-v4.1-flash` / `MAX`，聊天进程数为 0；两个会话共用 1 个原生模型桥，手动选择仍保留。预览后把默认角色改为另一可用模型，再仅加载原生 Slash 目录，OMP 无显式模型参数启动，`get_state` 最终同步为更新后的模型，返回 51 项原生命令且无消息/推理执行；验收结束恢复原配置。

本轮完成内联代码与文件路径交互、WawSSH 文件浏览器对齐、递归安全删除、Proroot 显式绑定、首问标题、长按多选、容器详情原型样式、已有用户身份、全局蓝色/莫奈/自定义配色及会话内直接新建。最终通过 299 项 Kotlin 回归（Desktop UI 71、Android JVM UI 70、驱动 103、容器 30、应用 25），无失败、错误或跳过；Android Debug APK 与 Desktop JAR 构建成功。本轮未重建 Rootfs，已有归档及其校验文件保持不变。

实际 APK 仅含 arm64-v8a 原生库且不含 Rootfs，Material Color Utilities 完整许可与源码逐字节一致；打包后的 Manifest 已确认 APK 安装权限、非导出 FileProvider 及 URI 读取授权配置。既有 Rootfs 归档 SHA-256 校验通过。

真实桌面已验证蓝色浅色界面、深主色内联代码、长按多选、首问自动标题、无表单的当前上下文新建、容器按钮/工具链卡片/挂载 bottom sheet、已有 `nobody` 用户保存、自定义 HEX 明暗配色及跨重启保持。文件链接实际下载 APK，下载文件与容器原文件 SHA-256 一致；PNG 通过隔离配置的系统关联交给原生 feh 查看器打开。已实际删除前一历史目录，再继续返回有效路径，未陷入导航循环。Desktop 系统配色的默认蓝色回退已检查；Android 系统莫奈取色及 APK 安装未冒充设备验收。

真实 arm64 PRoot/QEMU 另验证 root 身份和宿主补充组、非空目录递归删除、整批删除前的链接子树拒绝且其他目标不受影响、宿主绑定文件删除被拒绝且哨兵不变、显式允许的挂载导入、PNG 字节流及数字 UID 65534 解析为 `nobody` 与其实际 HOME。未调用模型推理、登录或复制开发机凭据。

2026-10-24 已在 Android 真机、同一份已导入 Ubuntu Rootfs 上验证 Proroot/Bun 修复：极小内联 `openat` 探针保留 x16/x17，Bun 1.3.14 的 `stat/readFile/readdir` 正常，原生模块版本导出为 `18.12.6`；默认缓存下 `omp --version` 返回 `omp/18.12.6`，`rpc-ui --no-session` 的 `get_state` 和原生命令目录请求均成功。原生 TUI `omp --no-session` 正常显示版本、模型与输入区，Ctrl-D 正常退出。未发送模型推理请求、未复制认证、未使用模拟器；本次不将 Rootfs 手动导入、其他 Android UI 交互或 Root/chroot 挂载卸载标为已验收。

本次通过 30 项容器回归，无失败、错误或跳过；Android Debug APK 构建与签名校验通过，仅含 arm64-v8a 原生库、不含 Rootfs。APK 内五库与上述真机验收的上游原文件逐字节一致。真机验证使用独立临时目录，未覆盖安装用户现有应用；交付更新 APK 后重新打开应用及容器终端即可，无需重导入 Rootfs。

符号诊断以当前 `PROROOT_LIB_PATH` 指向的实际 runtime 为准，并原样保留直接调用 libc 的对比结果；后者在新版 Proroot 中仍返回空地址，不能用它断言 OMP 缺少 N-API。实际 runtime 的符号解析与真实原生模块加载均已通过。探针使用 64 位整数保留主程序伪句柄 `-2`，避免经 FFI 指针浮点转换后截断。

Markdown 代码胶囊与表格视觉参数参照 [Kelivo 的固定版本](https://github.com/Chevey339/kelivo/tree/199647a5d718ed47783dde405007e4c080efdec5/dependencies/markstream)，使用 Compose 独立实现，未复制其 AGPL 代码或资源；正文及 Ask 共用图像全屏与原文件保存入口。

本轮通过 74 项 Android JVM UI 回归，无失败、错误或跳过；Android Debug APK 构建成功，APK 签名校验通过，最低 API 26，仅含 arm64-v8a 原生库。按用户要求暂停 Desktop 验收，未启动模拟器；图片全屏、手势、实际保存及跨胶囊选择复制留待用户真机实测，不将 JVM 测试和编译打包视为设备端交互验收。

本轮操作面板、设置 popup、模型通用立方体图标及服务列表间距调整后，通过 80 项 Android JVM 回归（UI 74、容器文本保存 6），无失败、错误或跳过；Android Debug APK 构建及签名校验通过。使用该轮编译的 Android 共享运行时代码，在真实 arm64 PRoot/QEMU 客体中验证 UTF-8 编辑、权限保留、外部修改拒绝、链接与挂载根保护、目录 ZIP 与原文件字节导出。未调用模型、未重建 Rootfs；按要求未做 Desktop UI 或 Android 模拟器验收，操作面板视觉、系统打开/安装及原生编辑器手势和返回交互待用户真机实测。

会话回车与转场调整通过 102 项 Desktop 回归（UI 77、宿主 25，无失败或跳过），Android Debug APK 编译打包通过。实际桌面使用键盘连续输入三行、Slash 候选打开时回车换行、Tab 选择、点击发送按钮执行本地 `/cost`，以及排队消息换行并点击勾选保存；多行草稿在返回会话后保持。逐帧检查页面进入、返回聊天/设置、Agent 设置中的服务详情返回，以及主页底栏双向滑动；未发送模型推理请求，未使用 Android 模拟器，不将桌面结果等同于真机软键盘验收。

聊天状态栏同色调整通过 Android Debug APK 与 Desktop JAR 编译。使用真实 Compose 聊天界面和临时 24 px 顶部安全区进行离屏渲染，逐帧检查状态栏区域与顶栏同色，覆盖顶部、滚动、回到顶部、自定义主色、深色主题及宽屏；确认顶部安全区只应用一次，并检查真实宿主的主页与应用设置页面。未连接 Android 真机，未使用模拟器；这些渲染结果不替代设备端状态栏验收。

OMP 安装引导本轮通过 182 项 Kotlin 回归（容器 32、驱动 121、应用 29，无失败、错误或跳过），Android Debug APK 构建与签名校验通过；APK 内安装脚本逐字节匹配源码，仅含 arm64-v8a 原生库，不含 Rootfs。真实桌面与新下载的 Ubuntu arm64 PRoot/QEMU 镜像已验证：缺失 OMP 弹窗、取消不安装、确认后进入真实 PTY 自动安装，以及无 CA 基础镜像的签名证书引导；安装完成后 OMP 18.12.6、Bun 1.3.14、原生模块和 RPC 自检均通过。另实际移走 Bun 并触发原生管理进程故障，界面显示“修复 OMP”，确认后自动重装并恢复 `READY`；界面重组不重复执行安装，原配置与保留文件的 SHA256 始终不变。未启动 Android 模拟器，编译打包不替代 Android 真机点击验收；本轮未重建交付 Rootfs，未使用模型认证或计费推理。

单容器与功能页本轮未运行自动化测试或 Android 模拟器；Android Debug APK、Desktop JAR 编译打包成功。真实桌面已操作单容器选中边框、双列功能、插件与技能页面、规则新建/原生全屏编辑/保存/重新打开/删除，以及自定义卡片的 JavaFX WebUI 与同源按钮请求。真实 arm64 PRoot/QEMU + OMP 18.12.6 验证空会话不持久化、本地插件链接与卸载、设置与秘密值脱敏、启停、可选功能恢复默认、用户/项目覆盖隔离，以及技能创建/原文字节/保存/版本冲突/范围隔离/删除。真实 WebUI HTTP 返回 200，未认证访问返回 403；页面生命周期离页停止且进程回收，手动生命周期离页保留并可明确停止。临时卡片、插件、技能及规则均在检查后移除，没有模型推理请求。

以上不替代 Android 真机交互验收；Sora/软键盘、WebView、实际系统返回及手机上的进程回收仍需真机验证。联网市场与账号发布操作未作为本轮实际成功证据，不把预检、缓存目录或配置保存冒充在线安装、公开发布或运行时重载。真机检查入口：更新 APK 后选择容器 → 新建空会话后返回 → 功能页规则编辑 → 插件/技能用户级管理与聊天项目级管理 → 明确要求 Agent 添加功能卡片并检查其启停和主题色；不需要重新导入 Rootfs。

追加的底栏位置已在真实桌面逐项点击确认：中间进入功能，右侧进入容器并保留安装/导入操作，左侧返回会话。桌面嵌入 WebUI 使用宿主显示密度计算 Swing 坐标，不将应用内容缩放重复用于原生视口；默认缩放下已观察到页面填满可用区域，原生面板与 WebKit Scene 尺寸一致，同源按钮实际返回“就绪”。

技能市场修复（2026-10-24）未运行自动化测试或 Android 模拟器；Android Debug APK、Desktop JAR 编译打包及 APK 签名校验通过。真实 arm64 PRoot/QEMU + OMP 18.12.6 的官方 `https://skills.omp.sh` 首页统计返回 packages、versions、publishers 均为 0；ClawHub 真实浏览、cursor 翻页、搜索、精确版本详情和资源预览成功。临时项目安装 `pskoett/self-improving-agent@4.0.5`，全部 16 个资源逐文件 SHA256 与公开版本清单一致；再次安装明确拒绝同名目录，原生重新发现显示正确来源与版本，用户范围及官方包声明/锁状态未变化，检查后移除整个临时项目。

桌面实际操作了默认 ClawHub 浏览、ClawHub/官方 Skillshare 锚定来源切换与官方空态、搜索 `skill-vetter`、发布者限定的详情、完整资源清单、安全预览、安装风险确认及返回保留查询；最终构建的发布时间显示为可读日期，未在用户目录进行无意安装。第三方技能不保证 OMP 兼容或安全。GitHub-backed 公开交接分支依据官方固定提交源码实现，本轮未取得对应真实可安装样本；429、待审核及恶意拦截未主动制造，不列为本轮实测成功证据。Android 真机仍需验证市场来源切换、预览、风险确认及安装后的技能发现；无需重新导入 Rootfs。

### 聊天性能回归处理（2026-10-25）

上一轮曾根据用户真机反馈按回归处理普通滑动掉帧和长思考展开卡死/闪退；用户随后更正：回退之前也会闪退，目前版本仍然很卡，因此不能认定闪退由上一轮优化引入。上一轮桌面窗口和微基准结果不能证明 Android 表现，也不作为当前实现的性能结论。

先前提交 `161ead9` 采用了保守回退：

- 撤回 `FeedRenderIndex`、自定义几何 Map 包装及源列表视图，恢复原有列表快照、消息位置、展开状态维护和原生 `SnapshotStateMap`。自定义 Map 的结构相等还可能使两个空会话的 CompositionLocal 复用旧值；恢复原生状态容器的身份语义。
- 撤回每段 Markdown 的两层长期增量解析缓存及关联行模型，恢复此前的解析和布局路径。新增缓存会保留源文本、`StringBuilder`、行起点与块条目，展开时仍在 UI 线程集中创建；这些新增持有与分配已移除，但没有崩溃堆栈，不能断言用户闪退一定是 OOM。
- 保留有限距离追尾、运行计时标签隔离、按需生成工具复制内容和事件归并的局部更新；不截断思考或历史，不关闭动画、富媒体、选择复制与吸顶回跳。

按用户要求，本轮未运行桌面端或移动端测试、性能基准、UI 烟测或模拟器；仅静态排查并运行 `./gradlew :app-android:assembleDebug --console=plain`，APK 构建成功，由用户真机复测。构建通过只说明可打包，不代表卡顿、卡死或闪退已通过设备验证；无需重新导入 Rootfs。

### 长聊天持续掉帧与渲染退出处理（2026-10-25）

本轮直接处理当前路径中的可确认开销，不再把回退当作修复依据：

- 折叠思考不再每次把全文转为超长单行并无限宽测量；根据实际视口逐步取足预览，保留全文、语义、全屏及复制来源。长分隔线改为迭代识别，避免递归正则匹配的栈增长。
- 吸顶几何保留原生状态 Map 的身份语义，位置改为相对所属根行，结合 Lazy 布局偏移定位；普通滚动不触发所有节点坐标写入，候选仅取可见根项，历史计划二分查找，概况观察隔离于聊天与输入区。
- 普通 Markdown 行可独立跳过重组；无代码段落不再使用 `BoxWithConstraints`，无链接段落不再维护点击及文字布局观察。大块正文在有界后台队列拆分，不持有逐段字符构建器。思考未闭合围栏只扫描新完整行，平滑输出在追平后挂起。
- Android 行内 TextMate 按明暗两套注册表串行复用；原生编辑器仍独立持有分析器。高亮缓存查询/字符串键计算移出 UI 线程。
- Android Mermaid 文档不再逐张复制整套离线引擎，改为精确 URL 拦截共享打包字节，其余资源请求仍被拒绝。WebView 创建/释放随原生视图生命周期；SVG 栅格化串行执行并等待桥回执，不再周期查询。图形渲染进程退出会结束等待、释放失效视图并展示真实失败/重试入口；不吞掉 OOM 或其他 `Error`。
- 原生事件归并按稳定 ID 定位，只复制变更节点的祖先兄弟列表，去掉重复终态/工具渲染；事件日志逐条写入并关闭 UTF-8 writer，不额外复制整条记录字节。主页不再随每个聊天增量排序历史会话，持久化会话不再反复扫描已接收消息。

按当前授权只做静态排查与 APK 构建，不运行测试、基准、桌面/手机界面验收、模型请求或模拟器。新增分隔线、流式围栏/SVG 及重写的回归用例未执行。没有 Android 崩溃堆栈，不能将 WebView 退出处理、去掉超长单行测量或降低分配写成“已证明修复用户闪退”；真机掉帧和闪退仍由用户使用本轮 APK 验证。无需重新导入 Rootfs。

本轮 `./gradlew :app-android:assembleDebug --console=plain` 已在只含本次修改的隔离源码快照上构建成功（35 秒）；未运行测试任务。产物：`app-android/build/outputs/apk/debug/wawlink-chat-performance-debug.apk`，51,209,557 bytes；SHA-256：`612e055bc4e54586587c631e6f717628b3b350e20acc571ed1a1defb2d6dc4f7`。


### Session 历史树与 Agent Hub（2026-10-25）

本轮新增界面及原生接入通过 6 项针对性驱动回归，覆盖分支返回、消息 ID、多展示节点关联和正文复制边界；Android Debug APK 与 Desktop JAR 构建成功。APK 内 `session.mjs`、`explorer.mjs`、`explorer-history.mjs`、`explorer-runtime.mjs` 与源码逐字节一致，仅含 arm64-v8a 原生库、不含 Rootfs，APK v2 签名验证通过。

实际 arm64 PRoot/QEMU 中的 OMP 18.12.6 发行版 CLI 已验证历史读取、标签写入、Hub 及停放子会话读取；状态模块经过原生扩展加载器共享真实注册表，而不是另建源码单例。实际 Kotlin 宿主另验证标签、回退草稿、消息定位、子代理历史 Ask、只读边界、HTML 导出、独立分支接入及源会话保留，并在没有本地消息日志的隔离数据目录中完成首次恢复。

直接使用原生 SDK 的离线冒烟验证全部分支、图片草稿、工具批次分支、历史多题 Ask 重答、递归子会话及派发定位、顾问只读、纯浏览不唤醒、真实子会话 Ask/工具审批及响应隔离、HTML 导出、停止/终止与失效实例拒绝。使用真实离线模型目录并禁止联网，最终网络尝试为零；没有伪造工具执行或模型成功结果。需要推理的摘要、续写和冷恢复成功路径未作为本轮模型验收，仅验证无可用模型时的真实失败边界；没有复制开发机认证，没有启动 Android 模拟器，没有重建交付 Rootfs。

真实桌面已逐项操作聊天 AppBar 的历史树与 Hub 两个入口、历史节点正文预览及逐级返回、Hub 层级列表与实例菜单、取消终止，以及独立子代理的会话、详情、活动和后台任务页面。界面读取真实停放实例、递归子代理及顾问记录；历史与活动时间按本地时区展示，页面使用当前应用主题。Android 真机的系统返回、触摸与软键盘交互仍需设备验证。

上述为本轮新增历史/Hub 功能验证，不是聊天性能、Android 真机流畅度或闪退修复结论。

本轮交付 APK：`app-android/build/outputs/apk/debug/wawlink-session-explorer-debug.apk`，58,300,992 bytes；SHA-256：`118d257fa59e4f9e9b9b8a2fa9d566476c5855d5e546f081760b7eba2d335b0e`。更新 APK 即可使用已有容器，无需重新导入 Rootfs。


### 历史树与 Hub 加载改造（2026-10-25）

将“读取全部正文并重建所有预览后显示”替换为原生 `SessionManager` 的完整轻量索引、按需详情和版本增量。分页固定会话身份、当前叶子与版本，收齐同一快照后再发布；分页期间新增条目留给下一次增量，不丢弃其他分支。历史 Ask 只读取所在祖先路径的工具调用，避免不同分支中相同工具 ID 串题；精确定位只传选定消息，不重传整份历史。Hub 注册表首屏、持久发现、活动摘要和实例正文分离，未变化的摘要复用缓存，活动与通信使用有界偏移读取。

通过 9 项针对性 Kotlin 驱动回归，覆盖完整匹配快照、版本变化/过期响应、可见正文复制、Hub 与历史解耦、分支及消息身份。Android Debug APK 和 Desktop JAR 构建成功。真实 arm64 PRoot/QEMU 中的 OMP 18.12.6 发行版验证 1,020 个节点分 4 页完整返回、分页中追加后的稳定快照与后续增量、12,073 字符的选定正文、原始记录、跨分支 Ask、标签、导航、独立分支及停放实例发现；未执行模型推理或使用开发机凭据。

实际跨进程侧通道连续完成首次索引和版本增量往返，并验证应用原子写入可触发原生请求处理。真实桌面已打开历史树、节点按需预览、独立 Hub 的 3 个真实实例和子代理正文。未启动 Android 模拟器；这些结果不代表 Android 真机的加载耗时、流畅度或闪退已验证，仍需设备复测。

用户随后要求停止 Desktop 验证，已关闭本轮应用和隔离显示进程，此后不再启动桌面验收。以上回归和桌面观察均发生在该要求之前；最终交付 APK 由用户真机验证。

本轮交付 APK：`app-android/build/outputs/apk/debug/wawlink-session-explorer-loading-debug.apk`，56,413,507 bytes；SHA-256：`b9f1c312530e6b392dfed86e1d5666819740df42f07aa8836d83de4e3c81ec23`。APK v2 签名验证通过，5 份管理桥与源码逐字节匹配，仅含 arm64-v8a 原生库、不含 Rootfs。更新 APK 即可继续使用已有容器，无需重新导入 Rootfs。

### 小会话历史树与 Hub 管理通道修复（2026-10-25）

静态排查发现管理扩展在首次排空请求前调用 `fs.watch`，而固定 Bun 的 Linux 实现直接使用原始 `inotify_add_watch` syscall；当前固定 Proroot runtime 的路径翻译表未覆盖该 syscall。[INFERENCE] 这可导致扩展初始化失败、聊天 RPC 却仍正常，与只有一句“你好”也无法加载历史树或 Hub 的现象吻合；尚无真机日志确认本次设备的具体断点。

管理请求改为会话生命周期内的顺序定时扫描，每轮完成后再调度下一轮，不叠加扫描或依赖文件通知；保留原子请求/响应和 Explorer 并发处理。通道就绪及排空错误使用原生进程实例身份隔离，已知管理扩展错误不再被聊天 RPC 存活状态掩盖。本地等待超时改为普通操作失败，保留真实页面取消传播；Hub 读取失败结束发现进度，历史消息定位不再被周期性 loading 翻转反复取消。活动索引改用原生规范包导入，不硬编码安装目录。

本轮仅做静态排查、两份管理桥的 `node --check` 与 Android APK 构建；`./gradlew :app-android:assembleDebug --console=plain` 在 43 秒内成功，未运行测试、Desktop 应用或 Android 模拟器。APK 签名验证通过，两份变更后的管理桥与源码逐字节一致。`adb devices -l` 未发现已连接设备，加载耗时、流畅度与本次故障是否消除仍由用户真机验证，不能以构建成功替代。

交付覆盖默认产物 `app-android/build/outputs/apk/debug/app-android-debug.apk`，58,308,296 bytes；SHA-256：`048d72d7eb9780935fca41f6df27cdafd8548ad09136bed1f69fd02310b431c6`。未创建新命名 APK，更新现有应用即可保留容器与会话，无需重新导入 Rootfs。


### 功能卡片与 Android LuaJ 桥接（2026-10-25）

实现原生/HTML 卡片、按容器保存的颜色/隐藏/排序、多选与拖动、隐藏项恢复和停服卸载；生命周期文案移除，功能启动使用居中标准进度组件。Android 卡片及完整页接入 LuaJ 标准库、完整 Java 互操作、UI 线程调度及系统 Intent 助手；随 APK 打包 LuaJ 的 MIT 许可，不修改已有 Rootfs 或覆盖用户 Skill。

通过 2 项卡片偏好回归，覆盖容器隔离、卡片暂时消失后的设置保留、恢复及磁盘失败不发布未保存状态。真实 PRoot 中完成声明扫描不启动脚本、带令牌 HTTP 服务启动、实际进程组 RSS、manual/page 离页行为、停服卸载及外部链接目标保留。真实 WebKit 完成首个脚本的数据可用性、事件更新不重载、原生动作、离线图标字形和桌面 Android API 明确拒绝；实际桌面操作了多选、拖动、隐藏、设置恢复、HTML 启停和原生弹层。桌面改用混合 ComposePanel，字体和映射只加载一次，实时更新不重复传输字体内容。

实际 APK 编译字节码的 LuaJ 求值器通过 Java 反射、JSON 编解码、循环返回拒绝及文档代际取消烟测；此烟测不构造 Android Activity/Context，也不验证 WebMessage 或 Intent 投递。未启动 Android 模拟器，未执行真实模型推理；Android 浏览器、分享、启动其他应用及设备 WebView 的端到端行为仍需真机验收，不能用 JVM 结果或构建成功替代。

最终 `:app-android:assembleDebug`、Desktop JAR 及针对性偏好回归构建通过；默认交付文件为 `app-android/build/outputs/apk/debug/app-android-debug.apk`，65,053,733 bytes，SHA-256：`a47a808826bfd3e52ebf8e416185f09530dabd5328f4f5f153a4ba09e6ca2d10`，APK v2 签名验证通过。真实界面确认调色保存及隐藏项恢复，加载区没有生命周期/加载说明文字；本轮桌面和隔离显示进程已关闭，临时诊断已移除。


### 卡片配色与布局修订（2026-10-25）

6 项配色回归通过，新增覆盖同色系深色与 4,096 个 RGB 背景的正文对比度边界；Android APK 与 Desktop JAR 构建通过。实际桌面确认蓝色/绿色卡片的文字和图标同步配色、图标标题同排且描述在下方、原生自定义卡片不附带启停按钮，HTML 自定义按钮仍按卡片原始设计显示；调色提交及点击进入真实容器文件浏览器正常。未启动 Android 模拟器，Android 真机显示仍待用户复测。

默认 APK 为 `app-android/build/outputs/apk/debug/app-android-debug.apk`，65,053,733 bytes，SHA-256：`264e489b19933708bc8d8be6e10e2a82da8c26e00adfc2333e50b236ac91ba1d`，v2 签名验证通过；不新增命名 APK，不改已有 Rootfs。


### 功能页原型与可配置原生按钮（2026-10-25）

功能页按 `ref-plan/feature-cards.html` 重构，保留既有拖动的抬起、跟手、边缘滚动和非线性换位。原生及 HTML 卡片均支持 `cardSpan: 1/2`；原生按钮只按清单显式声明，一行最多三个且等宽等高，半宽三个按钮仅显示图标并保留无障碍名称。调色、编辑操作栏与容器选择使用原型布局，HTML 自行绘制完整卡片，不额外叠加原生标题、内边距或配色。

原生按钮接入真实打开、启停、日志、容器脚本及 Android LuaJ。脚本按当前身份执行并在取消时回收进程组；原生 Lua 与网页复用宿主引擎，不创建隐藏 WebView，每次原生执行隔离 globals，保留完整标准库、`luajava` 和 Android 助手。内置 Skill 及本文补充单双列配置、按钮契约、天气式整行和专注式半宽 HTML 推荐写法，不预装演示卡片或覆盖用户修改。

9 项定向回归通过：7 项运行时/动作边界和 2 项偏好持久化。真实桌面操作确认单按钮脚本生成归档、单双列混排、原生/HTML 整行跨行拖动及排序落盘、调色与持久化、容器选择和真实运行状态。修复了原生 WebKit 盖住调色弹层的问题；编辑/弹层使用同一 WebView 的合成快照，恢复后时钟和专注倒计时继续运行，未重载文档。

对 APK 编译产生的真实原生 LuaJ 执行器完成 JVM 烟测，验证算术、Java 对象方法、JSON 返回、实际 Lua 错误、globals 隔离、`pcall` 循环取消及取消后的后续执行。仅 Android 类型使用 JVM 替身；未验证设备 Context/Activity、UI 线程调度、WebMessage 或 Intent 投递。未启动 Android 模拟器、未执行模型推理；Android 浏览器、分享及其他应用调用仍需真机验收。本轮桌面应用、原型浏览器和隔离显示进程已关闭，临时验证程序已移除。

`:app-android:assembleDebug` 与 Desktop JAR 构建通过，默认 APK 为 `app-android/build/outputs/apk/debug/app-android-debug.apk`，65,053,733 bytes，SHA-256：`6762cec402c77d87b5389075d08fd0c1d4bedf57e7534d61df712ad84fc10fef`，v2 签名验证通过。不新增命名 APK，不改交付 Rootfs，更新应用无需重新导入容器。

### 功能卡片设置随容器携带（2026-10-26）

功能页与应用设置恢复入口改为按需读写容器用户 `~/.wawlink/feature-cards.json`，每次修改重新读取客体最新内容，同目录暂存后原子替换，成功才更新界面状态。配置损坏、符号链接、特殊文件、超过 2 MiB 或外部内容冲突会报错，不以默认值覆盖原文件；不再读取或写入旧的软件侧卡片设置，不保留迁移或兼容分支。

20 项定向回归通过：4 项客体存储边界、6 项卡片偏好以及 10 项既有功能运行时回归，无失败或跳过。真实原生 x86_64 PRoot 隔离容器完成调色、隐藏、排序保存、实际 Rootfs 导出、全新软件数据目录下导入、新容器 ID 读取、克隆、重启与恢复显示/默认色，设置均保持一致。Android Debug APK 构建及签名校验通过，产物仍为 `app-android/build/outputs/apk/debug/app-android-debug.apk`；未启动 Android 模拟器，未执行 Android 真机界面验收或模型请求。

### Android 动态天气卡片白屏（2026-10-26）

通过用户转发的 ADB 检查实际天气卡片：清单为 `lifecycle: page`，因此显示启动入口而不随容器自动运行；仅将该卡片清单的生命周期改为 `container`，其余字段和天气代码保留。应用不根据卡片内容猜测或擅自更改生命周期。

白屏另为应用侧 WebView 布局问题：真机文档已收到真实天气数据，脚本正常执行，但 `html/body` 高度为 0，`100vh` 也为 0，`overflow:hidden` 将内容裁掉。Android 共享功能 WebView 现显式使用宽高 `MATCH_PARENT`，由 Compose 分配实际尺寸，保留作者原始 HTML/CSS，不注入固定像素高度补丁。

`:app-android:assembleDebug` 构建成功，默认 APK 已覆盖安装并保留原有数据；用户确认真机实际显示正常、手动验证通过，随后按要求停止进一步验证。未启动模拟器、未运行模型请求、未重建或重新导入 Rootfs。本次未新增自动化测试，设备 WebView 布局以本轮真机排查与用户验收为证据。

### 容器内轻量卡片与功能页调试（2026-10-26）

新增 `wawlink-feature`，复用已有 Bun 与应用实际挂载的 WebView，提供 `list`、`inspect`、`logs`、`eval`、`screenshot`、`reload`；卡片和完整功能页通过 `--surface card/page` 独立选择。内置 Skill 补充真实调试流程和生命周期选择，不安装浏览器或依赖 ADB，不为调试自动启服、打开页面或改写功能清单。

14 项定向 Kotlin 回归通过（调试通道 4、既有功能运行时 10），无失败或跳过。真实 x86_64 PRoot 客体通过命令 PATH 调用宿主的桌面 JavaFX WebView，验证卡片与完整页的独立身份、DOM 点击、Promise 成功与拒绝、首个页面脚本的 console、真实 Bun 服务输出、两种页面的 PNG 实际像素、已有文件与符号链接拒绝覆盖、静态客体 HTML 重新读取及完整页 URL 重载、导航取消旧 Promise、遮挡时保留页面但拒绝截图、销毁后拒绝执行。临时容器、界面及烟测入口均已关闭或移除。

Android Debug APK 构建通过，默认产物为 `app-android/build/outputs/apk/debug/app-android-debug.apk`，65,053,733 bytes，SHA-256：`b7b55216f74a2fd4ecc8ffb42414cb837f197a89e32afd6df1dd090e527908dc`。本轮未运行 Android 模拟器、Android 真机调试命令或模型请求；桌面结果不替代设备端 WebView、LuaJ/Intent 与 PNG 截图验收。更新 APK 即可继续使用已有容器，无需重新导入 Rootfs。

### 预置 Skill 自动更新（2026-10-26）

预置 Skill 改为比较软件正文、容器实际正文与持久化安装哈希，自动升级未修改版本，同时更新哈希记录；未知同名 Skill、用户修改、链接和无效记录均保留。安装记录随容器携带，后续软件版本不必逐次补写“上一版哈希”。已有入口继续在容器运行接入、功能页读取及 Agent 启动前执行检查，不新增轮询或为升级唤醒已停止容器。

18 项定向回归通过（预置 Skill 4、既有功能运行时 10、调试通道 4），无失败或跳过。真实 x86_64 PRoot 开启 `link2symlink` 后，使用本轮编译的生产安装脚本验证 6 个已发布 Skill 正文的无记录接管升级、后续未列入历史清单的新版本更新、用户修改及原哈希记录保留、正文缺失后重新安装；临时程序与客体已移除。另有回归覆盖更新中断前后两侧恢复、当前内容幂等及权限保留。

Android Debug APK 构建及 v2 签名校验通过，默认产物 `app-android/build/outputs/apk/debug/app-android-debug.apk` 为 65,053,733 bytes，SHA-256：`d5eae4db478f4d916ac2ef04ea3cd8f4d735b2d70537f7bbaabd6521b884c80a`。未运行 Android 模拟器、Android 真机验收或模型请求；更新 APK 无需重新导入 Rootfs。

