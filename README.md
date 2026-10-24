# WawLink

Android / Linux Desktop 跨端 Coding Agent。Kotlin Multiplatform、Compose Multiplatform 与 Material 3；主页、聊天与框架设置以既有 `ref-plan/` 原型为基准，新增 Agent 设置以 `ref-plan/agent-settings.html` 为基准。

## 模块与边界

| 模块 | 职责与依赖边界 |
| --- | --- |
| `agent-protocol` | 会话控制、消息树、模型服务与设置契约；仅依赖 Kotlin 协程，不依赖 UI、OMP 或容器 |
| `agent-ui` | 聊天、Composer、模型服务、Agent 管理与框架设置；业务层仅依赖 `agent-protocol` |
| `agent-driver-omp` | 一会话一进程、真实 OMP RPC-UI / JSONL、条件式 Todo 分组与 YAML 双向同步 |
| `container-runtime` | Proroot / PRoot / chroot、Rootfs、发行版部署、工具链与终端 |
| `app-desktop` | 460 × 920 桌面窗口；`src/sharedMain/kotlin` 包含双端共用主页、容器管理与应用协调层 |
| `app-android` | arm64-v8a 宿主、系统文件选择、前台服务；Rootfs 从容器页单独导入 |
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

输出：`app-android/build/outputs/apk/debug/app-android-debug.apk`。该任务不要求 Rootfs 归档存在，不会启动模拟器。预置原生命令作为可执行 `.so` 随 APK 安装，仅打包 arm64-v8a，保留上游二进制原始字节；新增终端 PTY 的 `libtermux.so` 从移植的 JNI 源码构建。

Rootfs 独立由 `./scripts/build-debug-rootfs.sh` 构建，输出归档与 SHA256 文件到 `build/outputs/`。首次安装后，将独立归档复制到手机，在“容器 → 导入 rootfs”选择文件并完成导入；应用启动不再从 APK 解包环境。以后覆盖安装同签名 APK 即可保留已有容器和会话，不需要重新下载或导入未变化的 Rootfs，也不要为更新而卸载应用。

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

- 主页管理多会话、搜索、容器/状态筛选、分支、批量置顶/归档/删除，并可直接处理待审批操作。
- 聊天页支持真实流式输出、Todo 条件归组、工具详情和 Diff、审批/提问、运行中消息排队与编辑、附件、项目文件、斜杠命令和用量详情。
- 容器独立文件管理移植 WawSSH 的列表、面包屑、路径编辑、筛选/排序、多选、复制/粘贴、重命名、属性、书签与导入导出；删除需确认，仅删除普通文件和空目录，不递归删除或覆盖已有目标。文件操作通过客体进程执行，拒绝符号链接与特殊文件，不直接读写宿主 Rootfs。
- 新建会话可用工作区输入框右侧的文件夹按钮选择当前容器中的目录；往返浏览器保留名称、容器与原工作区表单。聊天加号菜单保留项目文件，并新增可跨目录选择的“容器文件”；取消保留草稿及已有附件，容器附件使用真实客体绝对路径。
- 会话发送过消息后，用量条持续显示，运行、空闲、继续下一轮及执行出错时均不再隐藏；项目文件展开子目录后使用独立列表行，不再叠放。
- 工具、Todo 和思考的运行耗时实时更新，结束后固定；不足 1 秒隐藏，1–59 秒显示 `15s`，达到 1 分钟后显示 `1:05`。展开的长思考/工具概况吸附在计划、子任务下方，三层均可回跳。
- 思考仅在展开动画期间冻结显示快照，结束后追上最新内容；平时按 32 ms 节奏平滑推进尾部，已完成 Markdown 段落保持稳定，不以整段防抖替代流式输出。上滑、阅读或选择时不强制追尾，使用独立的“回到底部”恢复跟随。思考全文以 Markdown 显示，展开时不从头播放。
- Markdown 代码块、未展开工具的命令摘要、执行脚本与文件内容共用原生离线高亮；Android 使用 Sora TextMate，Desktop 使用 RSyntaxTextArea 3.6.0。语言依据显式标记、工具名、文件后缀或 shebang 识别，未知显式语言保留纯文本。读取路径支持行号、范围、raw、归档成员和查询选择器，不将这些选择器误当后缀。
- 读取、写入、创建及查找代码可打开全屏只读查看器，支持行号、选择和复制原文；Android 使用 Sora Editor 0.23.6，Desktop 使用 RSyntaxTextArea 3.6.0，不以网页组件替代 Sora。内置 34 项 TextMate 语言映射、完整语法依赖及明暗主题，包含 HTML/JavaScript/CSS、TypeScript/JSX/TSX、Shell、Python、Kotlin、Java、C/C++/C#、Go、Rust、JSON/YAML/TOML、SQL 等；HTML 内嵌脚本与样式保持跨行状态。来源、哈希、差异和完整许可位于 `agent-ui/src/commonMain/composeResources/files/code/`。
- Slash 菜单从当前会话 OMP 的原生命令目录加载并跟随动态更新；第一次输入 `/` 时才启动目录查询，不发送用户 prompt。当前内置命令匹配中文描述与 Material Symbols 图标；未知、扩展或被自定义覆盖的命令仍显示，使用默认图标且不显示描述。无参数 `/model`、`/cost` 保留应用内快捷入口，带参数的命令交给 OMP 原生解析。
- 同一次 ask 的多个问题共用一张卡片，左下角灰色无背景前后按钮配合左右非线性切换；问题 ID 固定答案、页码和预览状态。单选选择后自动进入下一题，多选停留当前题；切题和检查答案原位更新，不把会话滚回顶部。支持推荐项、选项描述、自定义输入、提交前回看与一次提交完整答案，提交后仍可查看；免审批不会自动回答普通提问。
- Agent 正文与 ask 富预览共用 Markdown、SVG/Mermaid 代码块与 Markdown 图像显示。Mermaid 12.2.0 随 UI 离线打包；PNG/JPEG/GIF/WebP 使用原生解码，动画图像显示首帧。图像来源仅 HTTP(S) 与图像 data URL，不读取宿主文件；SVG 不允许脚本、外部资源、实体、事件或 foreignObject，错误内容显示中文原因。
- 用户消息与运行中追加消息即时本地显示，发送失败只撤回对应的未接收消息并显示原因；收到原生用户消息后，后续模型执行失败仍保留该消息。消息按发送时的 Todo 与前置节点定位，后续工具、思考、任务切换及历史重放不改变其位置。加载状态使用 Material 3 标准进度组件。
- 应用设置中的“新会话默认免审批”默认开启并持久化，只影响以后创建的会话；会话内仍能单独切换。开启免审批会立即回复已等待和后续工具审批，不自动回答普通提问，不改写全局工具权限配置；关闭时在下一轮启动恢复原生审批模式。
- 新思考节点跟随当前活动 Todo 子任务；没有活动 Todo 时保持扁平。任务切换、完整快照与历史重放不迁移或重复既有思考节点。
- 用户消息和新一轮执行不重复追加未改变的 Todo；原生更新、提醒和完整状态按内容、状态与阶段判重，真正变化才生成新计划卡片，先前快照和用户消息位置保持不变。
- 返回聊天、切换会话或进入设置后，保留各会话的滚动位置、Todo/思考/工具展开状态及未发送草稿；主页筛选和相关设置页的滚动、展开状态独立保存。不跨会话共享草稿，也不承诺结束应用进程后恢复所有临时 UI 状态。
- 模型服务支持 Chat、Response、Message 接口、真实连通性测试与远端模型同步；模型可编辑显示名、推理/图片能力、上下文与最大输出，以及输入、输出、缓存读取、缓存写入四项美元/百万 token 价格。
- 模型资料参考 ipmt 的目录匹配方式，联网读取主/备目录并缓存六小时；按唯一 ID 或规范化别名匹配，歧义或未知模型不猜测。新增模型可补全能力与价格；已保存模型须显式“应用匹配”，手动改过的字段保留。远端同步仅追加新模型，不覆盖既有编辑。
- 思考强度包含无、低、中、高、超高（`xhigh`）和最大（`max`）；支持接口自定义 effort 与 Message 数值预算，预算须至少 1024 且小于最大输出。两个最高档分别贯通原生 RPC、配置和请求参数，不互相合并。
- 模型列表与输入区模型选择按钮共用离线品牌图标并保留原色；按钮随当前模型切换，列表项始终展示各自模型图标。未匹配品牌的模型显示 ID 首字符的大写单字标识及圆形底色，底色按字母稳定生成，同字母同色，不使用服务商图标或通用脑图标替代。选中背景覆盖整行，弹出菜单动画保留外部阴影。品牌图标参照 Kelivo，DeepSeek 使用原蓝鲸；Codex、Kimi 因 Kelivo 对应资源的许可来源未能核实，使用 MIT 许可的 LobeHub 同品牌图标。来源与差异见 `agent-ui/src/commonMain/composeResources/files/model-icons/NOTICE.txt` 和 `provenance.json`。
- Agent 设置按 OMP Schema 展示，过滤外观及 TUI 专属项。外观独立保存在宿主设置中，不写回 TUI 配置。
- `models.yml` / `config.yml` 位于容器用户的 `~/.omp/agent/`，支持应用写入和外部修改回流。模型服务配置可能含 API Key，应留在本地数据目录，不要提交版本控制。
- Proroot 上游仅提供 Android arm64 版本；桌面使用真实 PRoot/QEMU 或具备 Root 权限的 chroot，不伪装支持 Proroot。
- 强制只读绑定仅由 chroot 实现。Proroot / PRoot 对只读挂载明确报错，不会静默变为可写。chroot 需要真实 Root 权限，并在停止容器时回收所属进程、卸载挂载点。
- 多个部署项目按首次出现顺序显示；进度或错误更新原位替换，完成后仅移除对应项目，不因并发刷新反复换位。
- 容器终端是独立导航页，直接连接真实 PTY，不再使用命令输入框加日志模拟。Android 保留 Love2Droid 的黑色控制台、Maple Mono 字体及两行等宽快捷键栏，移植 Termux TerminalView/TerminalSession；挂载和窗口获焦后重建输入连接，点击终端唤出软键盘，快捷键不抢焦点。Desktop 使用 JediTerm 3.63 + pty4j 0.13.4。支持 ANSI、交互式 Shell、方向键、Ctrl-C、选择复制与窗口尺寸更新；离开页面关闭其终端进程，退出后保留输出并提示重新打开，不关闭其他会话的进程。
- PRoot/Proroot 启动前为宿主补充 GID 追加缺失的容器组名，保留已有 `/etc/group` 条目并原子更新，解决 `groups: cannot find name for ...`，不屏蔽报错。DNS 继续使用已有的默认网络监听与客体配置更新，无需替换 PRoot 二进制或重新导入 Rootfs。
- 工具链安装/卸载先确认，取消不执行，执行期间禁止重复触发；同一行卡片按较高内容等高，长版本自动换行。Git 版本去掉 `git version` 前缀。
- rootfs 支持 `.tar.gz`、`.tar.xz`、`.tar.zst` 导入，导出为 `.tar.zst`。归档路径和符号链接受边界检查；克隆/导出保留可执行及访问权限。文件系统允许时保留真实硬链接；Android 等环境拒绝创建硬链接时，导入器改为复制普通文件并保留权限和修改时间，不生成替代符号链接。此时文件不再共享 inode，可能增加磁盘占用。体积统计不遍历运行时挂载和 PRoot/QEMU 的临时宿主视图。
- 导入时以解压目录的真实路径统一校验符号链接，支持 Android 应用数据父目录的路径别名及合法的 `node_modules/.bin` 相对链接；仍拒绝作为解压目录的符号链接、路径穿越和真正逃逸的链接链。
- Android 容器启动及执行命令前使用当前默认网络的 DNS，并在网络变化时原子更新已运行容器的 `/etc/resolv.conf`；断网时保留最后一份有效配置。显式绑定 `/etc/resolv.conf` 或 `/etc` 时，以用户挂载为准。现有 Rootfs 的旧 DNS 会在运行时修正，无需重新下载或导入归档。
- Bun 会缓存 DNS 服务器；默认网络的 DNS 变化后，OMP 在下一次空闲发送时重启子进程并续接原会话，不中断正在执行的任务，不自动重发请求。独立宿主接入时，将同一个 `StateFlow<String?>` 同时传给 `RuntimePaths.resolvConf` 与 `OmpProcessPool` 的 `resolvConf` 参数；`null` 表示不覆盖现有 DNS。

## 独立嵌入 UI

```bash
./gradlew :agent-ui:assembleRelease :agent-ui:desktopJar \
  :agent-protocol:assembleRelease :agent-protocol:desktopJar
```

Android AAR 位于各模块的 `build/outputs/aar/`，Desktop JAR 位于 `build/libs/`。外部宿主需同时引入协议库及 Compose / 协程等正常运行依赖；不需要引入 OMP 驱动或容器模块。

富图形的 Android 宿主需要可用且较新的 Android System WebView。Desktop 同时需要当前平台的 OpenJFX 21.0.13 `base/graphics/controls/media/web` 运行依赖（兼容 JDK 17），Linux 需要 GTK 3 及 JavaFX WebKit 的原生库；Gradle 工程已按平台声明。富图形在桌面使用无窗口 WebView/Canvas 生成原生 Compose 图片，不嵌入 Swing 子窗口，不需要 interop blending 参数；全屏代码查看单独使用原生 Swing 代码组件。Mermaid 来源与 MIT 许可位于 `agent-ui/src/commonMain/composeResources/files/rich/`；分发 OpenJFX 运行库时保留其 GPL/ClassPath exception 及 WebKit 第三方通知。

`dev.wawlink.ui` 的入口：

| 入口 | 宿主提供的契约 |
| --- | --- |
| `AgentChatScreen` | `AgentSessionController`、`ProviderConfigRepository`、返回/新建/设置导航、挂起的主机与容器附件选择回调、可选 `AgentAppearance` |
| `ProviderSettingsScreen` | `ProviderConfigRepository`、返回回调 |
| `AgentManagementScreen` | `AgentManagementRepository`、`ProviderConfigRepository`、返回回调；可选会话、初始分区及返回处理注册 |
| `AgentConfigScreen` | `AgentSettingsRepository`、返回回调；对应框架设置 |
| `AgentTheme` | 宿主外观状态；设置页可置于同一主题内 |
| `CodeDetailScreen` | 标题、原文、可选语言、返回回调；外层提供 `AgentTheme` |
| `EditableCodeScreen` | 标题、原文、可选语言、返回及保存回调；返回前确认未保存修改 |

导航、文件选择、认证和 Agent 实现由宿主负责。协议使用 `StateFlow` / `Flow`；UI 只消费状态与控制契约，没有对具体驱动或容器的引用。

`AgentSessionController.loadSlashCommands()` 负责按需加载目录，`ChatSessionUiState.slashCommands`/`slashCommandsError` 提供命令元数据与加载错误；目录不会作为历史重放。`respondAsk(nodeId, answers: List<AskAnswer>)` 一次接收同一原生请求的完整答案，以 `questionId` 对齐，不再按每道题分别调用。

`AgentChatScreen` 的 `onPickFiles` 与 `onPickContainerFiles` 均由宿主提供；容器选择返回 `source = AttachmentSource.CONTAINER` 的 `AttachmentFile`，`path` 必须是当前容器的真实绝对路径。驱动在容器内校验普通文件并读取图像字节，消息与历史保留附件来源；不把工作区之外的容器附件伪装成项目文件。

## 预置环境与产物

固定输入：Ubuntu Base 24.04.4 arm64、Bun 1.3.14、Node 22.22.2、OMP 18.12.6，以及 Git、ripgrep、fd、jq、Python、curl、xz、zstd。OMP 使用完整 npm 生产依赖及原生依赖，不以替身命令代替。初始项目位于 `/workspace`，默认工具策略为 `always-ask`。

当前交付产物的实际体积（本轮未重建 Rootfs，保留已有归档与校验文件）：

| 产物 | 字节 | MiB |
| --- | ---: | ---: |
| Rootfs 文件内容合计 | 1,543,298,008 | 1471.80 |
| `build/outputs/ubuntu-main-debug.tar.zst` | 404,312,713 | 385.58 |
| Android Debug APK（不含 Rootfs） | 53,125,482 | 50.66 |

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

开发环境当前未连接 Android 真机，未启动或使用模拟器。因此修复后的 Android 手动导入、设备端 Proroot，以及 Root 设备上的 chroot 挂载/卸载不标为已验收；这些路径需要相应设备和权限，不能以桌面测试替代。
