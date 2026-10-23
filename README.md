# WawLink

Android / Linux Desktop 跨端 Coding Agent。Kotlin Multiplatform、Compose Multiplatform 与 Material 3；界面、图标、布局和非线性过渡以 `ref-plan/` 的三个原型为基准。

## 模块与边界

| 模块 | 职责与依赖边界 |
| --- | --- |
| `agent-protocol` | 会话控制、消息树、模型服务与设置契约；仅依赖 Kotlin 协程，不依赖 UI、OMP 或容器 |
| `agent-ui` | 聊天、Composer、模型服务、Agent 设置；业务层仅依赖 `agent-protocol` |
| `agent-driver-omp` | 一会话一进程、真实 OMP RPC-UI / JSONL、条件式 Todo 分组与 YAML 双向同步 |
| `container-runtime` | Proroot / PRoot / chroot、Rootfs、发行版部署、工具链与终端 |
| `app-desktop` | 460 × 920 桌面窗口；`src/sharedMain/kotlin` 包含双端共用主页、容器管理与应用协调层 |
| `app-android` | arm64-v8a 宿主、系统文件选择、前台服务；Rootfs 从容器页单独导入 |

`agent-driver-omp/src/jvmSharedMain/resources/` 中的模型预算扩展和 OMP 设置 Schema 同时供两端使用。Android 驱动显式将该目录接入 `main.resources`，使两份文件作为 Java 类路径资源进入驱动 AAR 和宿主 APK；仅声明 Kotlin 共享源码集依赖不足以打包这些资源。修复此类 APK 资源遗漏不需要重新生成或导入 Rootfs。

OMP 启动前会将 `models.yml` 和主设置文件重新序列化为 Bun 可解析的 YAML，自动修复旧版本写出的 pretty-flow 格式；保留认证值、注释和未知字段，不改认证存储，也不重写内容已稳定的文件。Android PRoot 的版本化库名通过应用私有目录中的符号链接解析，不再把 Android `LD_PRELOAD` 传给第一个 Linux 程序。更新 APK 即可使用这些修复，无需重新导入 Rootfs。

## 构建与运行

构建环境：Linux x86_64 或 arm64、JDK 17、Android SDK Platform 35、网络，以及 Bash、Python 3、curl、GNU tar、dpkg-deb、flock 和 GNU coreutils。工程自带 Gradle Wrapper，不需要系统 Gradle。Android 最低版本为 Android 8.0 / API 26。

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

输出：`app-android/build/outputs/apk/debug/app-android-debug.apk`。该任务不要求 Rootfs 归档存在，不会启动模拟器。原生程序作为可执行 `.so` 随 APK 安装，仅打包 arm64-v8a，保留上游二进制原始字节。

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
- 用户消息与运行中追加消息即时本地显示，发送失败只撤回对应的未接收消息并显示原因；收到原生用户消息后，后续模型执行失败仍保留该消息。加载状态使用 Material 3 标准进度组件。
- 应用设置中的“新会话默认免审批”默认开启并持久化，只影响以后创建的会话；会话内仍能单独切换。开启免审批会立即回复已等待和后续工具审批，不自动回答普通提问，不改写全局工具权限配置；关闭时在下一轮启动恢复原生审批模式。
- 新思考节点跟随当前活动 Todo 子任务；没有活动 Todo 时保持扁平。任务切换、完整快照与历史重放不迁移或重复既有思考节点。
- 模型服务支持 Chat、Response、Message 接口、真实连通性测试、远端模型同步及单模型思考参数。
- Agent 设置按 OMP Schema 展示，过滤外观及 TUI 专属项。外观独立保存在宿主设置中，不写回 TUI 配置。
- `models.yml` / `config.yml` 位于容器用户的 `~/.omp/agent/`，支持应用写入和外部修改回流。模型服务配置可能含 API Key，应留在本地数据目录，不要提交版本控制。
- Proroot 上游仅提供 Android arm64 版本；桌面使用真实 PRoot/QEMU 或具备 Root 权限的 chroot，不伪装支持 Proroot。
- 强制只读绑定仅由 chroot 实现。Proroot / PRoot 对只读挂载明确报错，不会静默变为可写。chroot 需要真实 Root 权限，并在停止容器时回收所属进程、卸载挂载点。
- 多个部署项目按首次出现顺序显示；进度或错误更新原位替换，完成后仅移除对应项目，不因并发刷新反复换位。
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

`dev.wawlink.ui` 的入口：

| 入口 | 宿主提供的契约 |
| --- | --- |
| `AgentChatScreen` | `AgentSessionController`、`ProviderConfigRepository`、返回/新建/设置导航回调、挂起文件选择回调、可选 `AgentAppearance` |
| `ProviderSettingsScreen` | `ProviderConfigRepository`、返回回调 |
| `AgentConfigScreen` | `AgentSettingsRepository`、返回回调 |
| `AgentTheme` | 宿主外观状态；设置页可置于同一主题内 |

导航、文件选择、认证和 Agent 实现由宿主负责。协议使用 `StateFlow` / `Flow`；UI 只消费状态与控制契约，没有对具体驱动或容器的引用。

## 预置环境与产物

固定输入：Ubuntu Base 24.04.4 arm64、Bun 1.3.14、Node 22.22.2、OMP 18.12.6，以及 Git、ripgrep、fd、jq、Python、curl、xz、zstd。OMP 使用完整 npm 生产依赖及原生依赖，不以替身命令代替。初始项目位于 `/workspace`，默认工具策略为 `always-ask`。

本次完整功能构建的实际体积：

| 产物 | 字节 | MiB |
| --- | ---: | ---: |
| Rootfs 文件内容合计 | 1,543,298,008 | 1471.80 |
| `build/outputs/ubuntu-main-debug.tar.zst` | 404,312,713 | 385.58 |
| Android Debug APK（不含 Rootfs） | 33,670,309 | 32.11 |

按已确认的完整功能优先策略，不执行原计划的 150–220 MB 硬上限。每次构建会重新生成 `.sha256` 与 `.sizes.json`，以后者的实际输出为准。构建缓存、Rootfs、APK、原生二进制和本地会话均不纳入源码提交，准备脚本可重建缺失输入。

原生程序的版本、完整性、依赖加载和许可证，以及字体来源，见 [`scripts/USAGE.txt`](scripts/USAGE.txt)。Proroot 为上游专有许可证；本项目不修改其二进制。字体及原生组件的上游许可通知随资源保留。

## 验证

可重复、无计费请求的回归测试：

```bash
./gradlew :container-runtime:desktopTest :agent-driver-omp:desktopTest :agent-ui:desktopTest
node --test agent-driver-omp/src/jvmSharedTest/resources/model-payload.test.mjs
```

常规单测不使用真实模型认证，不在后台发出计费请求。真实 RPC 验收使用单独的本地运行环境，与预置归档、APK 和默认用户数据隔离。

已验证 Android 编译打包、独立 UI AAR/JAR、APK arm64-only 和 13 个上游原生程序逐字节校验。当前 APK 不含 Rootfs 归档，Rootfs 作为独立文件按 SHA256 校验。实际 Linux PRoot/QEMU 容器已验证发行版下载、三种归档导入、克隆、默认切换、Git 安装/卸载、绑定和环境变量、流式终端，以及停止时仅回收所属进程树。

本次回归通过 72 项 Kotlin 测试，覆盖即时消息与失败回撤、重复文本隔离、排队编辑/删除、未接收消息晚到拒绝、审批切换与持久化、Todo 内思考归属及流式/终态重放边界，以及现有 DNS、部署顺序、归档、配置同步、原生历史、模型参数和 Markdown 回归。此前 4 项 Node 测试已验证模型 Message 请求预算。

真实模型验收仅使用用户确认的 `oc/deepseek-v4.1-flash`，已跑通双会话独立计时、Todo/扁平工具、审批、选择题、附件、排队编辑/删除、原生分支续聊和迁移续聊。桌面实际界面已检查主页展开/进展摘要、双层吸顶及回跳、工具详情、聊天标识符、容器终端和设置页；不把这些桌面结果替代为设备端验收。

本次修复使用完整交付 Rootfs，在注入文件系统硬链接拒绝的条件下完成真实解包，并在新解包的 arm64 PRoot/QEMU 容器内运行 Node、OMP、Git 和报错涉及的 `string-width` 模块；文件内容、权限和修改时间均通过检查。这验证了兼容分支，不替代设备端验收。

部署列表与符号链接修复另通过实际桌面界面验证：两个部署项目交替更新时保持原位，两份完整交付归档经由父目录别名并发导入成功，完成后部署记录移除；两个新容器的 Node、OMP、Git 与 `mnemopi --help` 均实际运行通过。该修复仅需更新 APK，现有 Rootfs 归档不变，不涉及下载源或 DNS 调整。

模型预算扩展及 Agent 设置闪退修复已验证 APK 与驱动 AAR 的两份资源逐字节匹配源码；隔离加载 AAR 的 Android 驱动类，并仅从实际 APK 读取资源，完成设置 Schema 初始化、开关状态修改及 YAML 持久化。由该 APK 安装出的预算脚本已通过 Node 执行真实请求参数转换与跨服务隔离检查，无计费请求；不将这些检查视为 Android 真机点击验收。

OMP 启动修复已在完整 arm64 Rootfs 内验证：先复现旧格式解析失败，再由实际会话启动流程自动修复配置；真实 `oc/deepseek-v4.1-flash` 返回指定内容且会话进入 `DONE`。该次推理通过本地隔离网关使用既有宿主认证，未将开发机认证复制进 Rootfs。另用真实主机 ELF 的 `libtalloc.so.2` 依赖及 PRoot/QEMU 验证无预加载启动、失效 APK 库路径更新和客体环境隔离；这不等同于验证 Android Bionic/SELinux。

DNS 修复已在完整交付 Rootfs 的 arm64 PRoot/QEMU 容器内验证：先复现失效 DNS 超时，确认当前 Bun 保留旧进程的 DNS 缓存，再验证新进程解析公共域名、HTTPS 返回 200、断网保留有效 DNS 及显式 DNS 挂载优先。真实 `oc/deepseek-v4.1-flash` 会话在 DNS 变化后更换子进程，续接上下文并正确返回首轮记住的字符串；认证仅经隔离网关提供，未复制到 Rootfs。Rootfs 构建脚本不再将构建机的 systemd stub DNS 带入新归档，已有交付归档保持不变。

会话体验调整已在完整 arm64 PRoot/QEMU 环境用真实 `oc/deepseek-v4.1-flash` 验证：首条消息在原生进程启动前可见，手动模式等待后切到免审批，随后 5 个工具审批均自动放行，完成 Todo、文件写入与读取；默认免审批的新会话执行真实 Bash 命令成功。另验证实际启动失败撤回消息、会话模式及历史恢复、应用默认值与外观设置交叉保存和重启。真实桌面检查开关默认开启、关闭后新会话模式及重启保持；该轮模型未返回思考块，Todo 内思考通过原生格式事件回放与回归用例验证，不将回放标为模型实际生成。

开发环境当前未连接 Android 真机，未启动或使用模拟器。因此修复后的 Android 手动导入、设备端 Proroot，以及 Root 设备上的 chroot 挂载/卸载不标为已验收；这些路径需要相应设备和权限，不能以桌面测试替代。
