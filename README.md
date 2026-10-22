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
| `app-android` | arm64-v8a 宿主、系统文件选择、前台服务与 Debug 预置环境导入 |

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

生成内置完整环境的 Debug APK：

```bash
./gradlew :app-android:assembleDebug
```

输出：`app-android/build/outputs/apk/debug/app-android-debug.apk`。该任务要求 Rootfs 归档已经生成；不会启动模拟器。原生程序作为可执行 `.so` 随 APK 安装，仅打包 arm64-v8a，保留上游二进制原始字节。

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
- 模型服务支持 Chat、Response、Message 接口、真实连通性测试、远端模型同步及单模型思考参数。
- Agent 设置按 OMP Schema 展示，过滤外观及 TUI 专属项。外观独立保存在宿主设置中，不写回 TUI 配置。
- `models.yml` / `config.yml` 位于容器用户的 `~/.omp/agent/`，支持应用写入和外部修改回流。模型服务配置可能含 API Key，应留在本地数据目录，不要提交版本控制。
- Proroot 上游仅提供 Android arm64 版本；桌面使用真实 PRoot/QEMU 或具备 Root 权限的 chroot，不伪装支持 Proroot。
- 强制只读绑定仅由 chroot 实现。Proroot / PRoot 对只读挂载明确报错，不会静默变为可写。chroot 需要真实 Root 权限，并在停止容器时回收所属进程、卸载挂载点。
- rootfs 支持 `.tar.gz`、`.tar.xz`、`.tar.zst` 导入，导出为 `.tar.zst`。归档路径和符号链接受边界检查；克隆/导出保留可执行及访问权限。体积统计不遍历运行时挂载和 PRoot/QEMU 的临时宿主视图。

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
| Android Debug APK | 438,409,081 | 418.10 |

按已确认的完整功能优先策略，不执行原计划的 150–220 MB 硬上限。每次构建会重新生成 `.sha256` 与 `.sizes.json`，以后者的实际输出为准。构建缓存、Rootfs、APK、原生二进制和本地会话均不纳入源码提交，准备脚本可重建缺失输入。

原生程序的版本、完整性、依赖加载和许可证，以及字体来源，见 [`scripts/USAGE.txt`](scripts/USAGE.txt)。Proroot 为上游专有许可证；本项目不修改其二进制。字体及原生组件的上游许可通知随资源保留。

## 验证

可重复、无计费请求的回归测试：

```bash
./gradlew :container-runtime:desktopTest :agent-driver-omp:desktopTest :agent-ui:desktopTest
node --test agent-driver-omp/src/jvmSharedTest/resources/model-payload.test.mjs
```

常规单测不使用真实模型认证，不在后台发出计费请求。真实 RPC 验收使用单独的本地运行环境，与预置归档、APK 和默认用户数据隔离。

已验证 Android 编译打包、独立 UI AAR/JAR、APK arm64-only、13 个上游原生程序逐字节校验，以及 APK 内 Rootfs 与发布归档的 SHA256 一致性。实际 Linux PRoot/QEMU 容器已验证发行版下载、三种归档导入、克隆、默认切换、Git 安装/卸载、绑定和环境变量、流式终端，以及停止时仅回收所属进程树。

本次回归通过 45 项 Kotlin 测试与 4 项 Node 测试，覆盖归档边界、配置同步、原生历史分支/迁移、终态事件幂等、模型 Message 请求预算和 Markdown 标记边界。真实原生日志离线回放确认：Todo 会话保持两个有实际工具的步骤组，未使用 Todo 的会话保持扁平；历史结果重发不会回退计划或新增空组。

真实模型验收仅使用用户确认的 `oc/deepseek-v4.1-flash`，已跑通双会话独立计时、Todo/扁平工具、审批、选择题、附件、排队编辑/删除、原生分支续聊和迁移续聊。桌面实际界面已检查主页展开/进展摘要、双层吸顶及回跳、工具详情、聊天标识符、容器终端和设置页；不把这些桌面结果替代为设备端验收。

未连接可用 Android 真机，未启动或使用模拟器。因此 Android 首次安装/解压、设备端 Proroot，以及 Root 设备上的 chroot 挂载/卸载不标为已验收；这些路径需要相应设备和权限，不能以桌面测试替代。
