# 插件管理与 Skill 管理功能边界调研

## 1. 调研口径

- 调研日期：2026-10-24。
- 基准：本机实际执行的 **OMP 18.12.6**，以及同版本 `@oh-my-pi/pi-coding-agent`、`pi-utils`、`pi-wire` 发布源码。主包归档已按项目安装脚本的 SHA-512 校验，不以滚动的 `latest` 文档替代当前版本行为。
- 目的：为新增独立的“插件管理”和“技能管理”界面提供尽量完整的能力清单、状态、限制和接入依据；**不是界面实现报告，也不把管理能力缩减为几个启停开关**。
- 证据分为“原生实测”“源码确认”“协议声明”。源码中有入口，不代表网络、账号权限、第三方包或 Android 上已经验收。文中“界面应”“建议”“宿主需补充”均为 **[INFERENCE] 设计推导**，不代表 WawLink 已实现。
- 原生操作使用临时 HOME、XDG 目录、`PI_CODING_AGENT_DIR` 和项目目录；只安装自建的本地资源包与本地 Git 仓库夹具。未读取真实用户认证、未发布到线上、未调用模型、未启动 Android 模拟器。
- 实测覆盖及未验证项集中在第 13 节。两页继续遵守现有 Material 3、中文 UI、Material Symbols、白底和导航动效约束，不另改视觉规范。

## 2. 首先区分管理对象

| 对象 | 实际含义 | 应归属的页面与边界 |
| --- | --- | --- |
| OMP 插件包 | npm、Git 或本地链接的包，通常在 `package.json` 的 `omp` 或兼容 `pi` 字段声明入口 | 插件管理；包身份、来源、版本、启停、功能选择、设置、诊断 |
| Extension | 动态加载的 JS/TS 模块，可注册工具、命令、事件、Provider 等 | 插件的能力载体之一；也可以通过显式文件或发现目录加载，不能把每个 Extension 都当成可升级的 npm 包 |
| 市场插件 | 通过已添加市场的目录条目安装，身份为 `插件名@市场名` | 插件管理；拥有独立安装登记、用户/项目范围和缓存，与直接 npm 安装不是同一条路径 |
| Agent Plugins 标准包 | 根 `plugin.json` 声明 Agent Plugins 1.0.0 标准；可移植组件主要是技能与 MCP | 插件管理；标准组件与 OMP/Claude 兼容组件的规则不同 |
| 本地技能 | 某个技能根目录下的 `<name>/SKILL.md`，可附脚本、模板、参考文档、图片等资源 | 技能管理；新建、查看、编辑、复制、删除工作副本由应用侧补充文件操作 |
| Skillshare 包 | `@scope/name` 的注册表包，有版本、范围、dist-tag、完整性和 manifest/lock | 技能管理；原生 `omp skill install/update/uninstall` 的管理对象 |
| 插件提供的技能 | 技能文件属于某个插件安装目录 | 技能管理可查看、过滤、定位所属插件；整体升级/卸载回到插件管理，不伪造独立 Skillshare 安装关系 |
| managed 技能 | OMP 自动学习或 `manage_skill` 写入的隔离技能目录 | 技能管理的独立来源；不是普通用户技能目录，也不是市场包 |
| MCP 资源 | 远程或进程型 MCP 提供资源、工具、提示词；部分 `skill://` 读取可回退 MCP 精确资源 | 不等于已安装本地技能；连接、授权和运行状态需保留 MCP 的独立语义 |

因此需要同时保留 **包视角、文件来源视角、会话实际解析结果视角**。同一包可能提供多个能力；发现技能不证明其包被注册表安装，安装成功也不证明当前会话已加载全部能力。

依据：[插件类型][P-types]、[Extension API][E-types]、[兼容插件发现][D-claude]、[标准插件格式][D-format]、[Skillshare manifest][S-manifest]。

## 3. 共同上下文：容器、配置档、项目与保存位置

### 3.1 上下文不能只显示“全局/项目”

页面应明确当前容器、当前 OMP profile、当前项目/工作区、原生解析出的配置与安装目录。“用户级”不是 Android 宿主全局，也不是开发机的 OMP 安装。

默认路径只是说明，不能硬编码：

| 数据 | 默认/常见位置 | 作用域与注意点 |
| --- | --- | --- |
| npm/Git/link 插件根 | `~/.omp/plugins/` | 包管理的 `package.json`、`node_modules`、`bun.lock` 位于这里；**不是** `~/.omp/agent/plugins/` |
| 插件运行状态和设置 | 插件根的 `omp-plugins.lock.json` | 包名到版本、启停、功能选择，以及设置值；不是 Bun 的依赖锁文件 |
| 项目插件覆盖 | `.omp/plugin-overrides.json` | `disabled`、`features`、`settings`；与“项目安装了一份插件”不同 |
| 市场源登记 | 原生配置根的 `marketplaces.json` | 保存市场名、来源类型/URI、缓存目录、添加和更新时间；没有通用的“项目市场源列表” CLI |
| 市场插件安装登记 | 用户插件根或项目 `.omp/plugins/` 下的 `installed_plugins.json` | 同一插件 ID 可各装一份用户/项目记录 |
| 市场缓存 | 用户插件根 `cache/marketplaces/`、`cache/plugins/` | 插件缓存可由当前用户和项目范围共同引用，不能把项目卸载画成必定删除全部物理文件 |
| authored 技能 | `<agentDir>/skills/`、项目祖先的 `.omp/skills/` | native 用户默认 `~/.omp/agent/skills/`；发现范围与实际新建目标要分开 |
| Skillshare 声明与锁 | 项目 `.omp/skills.json`、`skills.lock.json`；用户 `<agentDir>/` 下同名文件 | 项目安装选最近的 `.omp`，否则 Git 根，最后才是 cwd；特殊用户/发现边界要求改用 `--global` |
| Skillshare 内容 store | `<agentDir>` 父目录的 `skillshare/@scope/name/version/` | 默认 `~/.omp/skillshare/`；并非每个项目复制一份 |
| managed 技能 | `<agentDir>/managed-skills/` | 默认 `~/.omp/agent/managed-skills/`，与 authored 分开 |

补充路径规则：

- named profile 的默认配置根为 `~/.omp/profiles/<profile>`，插件也随此根隔离。
- **仅修改 `PI_CODING_AGENT_DIR` 不隔离插件根**：它改变 agentDir，不改变插件依赖的 configRoot。应用不能只换 agentDir 就宣称整套插件配置档已隔离。
- XDG 路径是否采用还受平台、已有 `omp`/profile 目录和 agentDir override 条件影响；设置 XDG 环境变量不保证立即迁移到新目录。`PI_CONFIG_DIR` 等原生目录配置也会影响位置。
- 插件加载器与部分管理方法对 `.omp`/`.pi` 项目覆盖文件的搜寻并非完全相同；高级界面应展示**实际读到和写入的文件**。

依据：[目录解析][U-dirs]（365–456、700–719 行）、[插件加载器][P-loader]（66–141、194–284 行）、[技能安装位置][S-manifest]（65–119 行）。

### 3.2 保存值、有效值和来源必须分开

- 插件全局禁用，再叠加项目禁用；项目覆盖不能把全局已禁用插件重新启用。
- 项目 feature 覆盖优先于用户选择；插件 settings 是全局到项目的浅合并，不是任意深合并。
- 启用的项目同名安装可遮蔽用户安装；禁用项目副本后，用户副本可能重新生效。
- 清除当前层、恢复默认、禁用、卸载包、删除文件、移除市场源是不同动作。
- 保存成功、当前解析生效、运行会话完成重载是三个状态；不能只用一个“已启用”布尔值表达。

## 4. 安装与导入入口的来源分流

这一节决定安装表单能接受什么，不能把所有输入交给同一命令。

| 用户输入/来源 | 原生路径 | 真实边界 |
| --- | --- | --- |
| npm 名称、scoped 包、版本、dist-tag | `omp plugin install my-plugin@1.2.3`、`@scope/plugin@next` | 直接 npm 安装可用；由 Bun 安装依赖。不是任意 npm package-spec 语法都获完整支持 |
| npm 可选功能 | `'my-plugin[search,web]'`、`'my-plugin[*]'`、`'my-plugin[]'` | 分别指定功能、安装时展开全部功能、不选可选功能；shell 下应引用，避免 glob 展开 |
| Git shorthand | `github:user/repo#ref`、`gitlab:group/subgroup/repo#ref`，以及 `bitbucket:`、`codeberg:`、`sourcehut:`/`srht:` | 直接插件安装路径；仓库要能被 Bun 安装并产生合法包，不是任意技能文件下载器 |
| 完整 Git 地址 | HTTPS、`git+https://`、`ssh://`、`git://`、SCP 风格 `git@host:user/repo`、`git:` 形式 | 支持通用 Git 主机；凭据、SSH、网络、ref 可达性仍是前置条件。不要在输入示例中嵌入 token |
| 裸 `owner/repo` | 添加市场时可作 GitHub shorthand | **直接插件安装不等于支持这条简写**；插件 Git parser 不把普通裸路径自动当 GitHub |
| `name@marketplace` | 已登记市场的插件安装 | 市场范围支持 `user`/`project`；来源需要再按第 6 节解析 |
| 显式本地包路径 | `omp plugin install ./plugin` 或 `omp plugin link /path/plugin` | 实际是链接，不复制、不安装依赖；要求 `package.json.name`；可在 cwd 外；字面 `~` 需调用方展开 |
| 已存在的裸本地目录 | `omp install existing-dir` | 顶层 `install` 会判存在并转 link；`omp plugin install existing-dir` 不做同样的存在性分流 |
| Skillshare scoped ID | `omp skill install @alice/pdf-tools@^1.2` | 注册表技能包，不是同名 npm 包；支持 exact、range、dist-tag |
| 本地独立 `SKILL.md`/技能目录 | 写入 authored 根或配置 `skills.customDirectories` | 没有通用 `skill create/edit/copy/import-local` CLI；复制资源、覆盖确认和安全写入需应用补足 |
| `.skill` 文件 | `omp skill import file.skill` | **上传到服务器转换并发布**，不是本地解压安装；需要认证，不能放进“无账号本地导入”流程 |
| raw Markdown URL、任意 ZIP/tgz URL | 没有统一的 Skillshare 安装入口 | 不能用 Git 安装能力冒充任意 URL 导入；若设计此入口，属于新增下载、审阅、解包及资源处理能力 |
| npm 市场搜索、其他第三方技能市场 | 非当前 `plugin discover`/`skill search` 的通用聚合能力 | `plugin discover` 只列已加市场目录；Skillshare 搜索只查所选注册表。没有原生跨 npm、skills.sh、其他网站统一搜索承诺 |

分类还有一个陷阱：以 `@` 开头的插件 spec 总走 npm；常见 dist-tag 或版本后缀不会当市场名；只有后缀是**已登记**市场才分到市场路径。因此 `name@unknown-market` 可能落到 npm，而不是返回“市场不存在”。不要给市场名或本地路径随意追加 npm 的 `[features]` 语法。

直接 npm/Git/link 安装仍面向用户插件根；`--scope project` 不会使这些安装自动变为项目安装。顶层 `omp install` 混合输入还会先处理 local，再处理 remote，不保证输入的全局执行顺序。

依据：[安装分类][P-classify]（1–113 行）、[顶层安装入口][P-install]、[Git 地址解析][P-git]、[插件 spec][P-parser]、[技能 CLI][S-cli]（449–507 行）。

## 5. 插件管理：包、能力、配置与生命周期

### 5.1 列表和详情的数据边界

`omp plugin list --json` 返回 `{npm: [...], marketplace: [...]}`，不能把两组强行压成相同来源：

- npm/link 条目含 `name`、`version`、`path`、`manifest`、`enabled`、`enabledFeatures`；来源 spec 不在这个结构中，需受控读取管理用的 `package.json`。
- 市场条目含 `id`、`scope`、`entries`、`shadowedBy`；entry 含版本、安装路径、安装/更新时间、启用状态等。`gitCommitSha` 虽在类型中存在，不应默认每次安装都会填充。
- 包元数据、声明的入口、成功加载的能力、当前会话可调用的能力应分栏表达，不能把 manifest 当运行健康证明。
- 无单独的通用 `plugin info` CLI；详情需要组合原生元数据、受控文件读取与会话诊断。
- npm 列表可包含已登记的 link；已经识别为市场 runtime link 的包会从 npm 组排除，避免展示成两次安装。

列表建议提供来源、范围、启停、遮蔽、可更新和异常筛选；搜索包名、显示名、描述和功能名。没有原生数据时，不编造评分、下载数、可信认证或兼容性等级。

### 5.2 命令能力矩阵

| 操作 | 原生入口 | 管理界面必须保留的语义 |
| --- | --- | --- |
| 安装 | `omp plugin install <source> ...`；顶层 `omp install` | 来源分流、版本/功能、风险确认、逐项结果；多包不是全局事务 |
| 本地开发链接 | `omp plugin link <path>` | 当前 CLI 只使用第一个 path；覆盖同名链接/目录有风险；不会验证 factory 或装依赖 |
| 卸载 | `omp plugin uninstall <name-or-id> ...` | 市场多范围需消歧；唯一市场裸名可解析，多市场同名要求完整 ID；link 卸载不应删源目录 |
| 启用/禁用 | `omp plugin enable/disable <name...>` | 市场应传完整 `name@marketplace` 和明确 scope；裸名不像 uninstall/upgrade 那样自动唯一匹配市场 |
| 可选功能 | `omp plugin features <pkg>`，配 `--enable`、`--disable`、`--set` | `--set` 是逗号分隔功能列表，不是 `key=value`；此入口不是完整的市场 scoped feature API |
| 配置查看 | `config list/get <pkg> ...` | 区分原始设置、schema 默认、项目覆盖和有效值；JSON 可能含秘密 |
| 配置写入 | `config set/delete <pkg> ...` | 当前 CLI **`--local` 没有实现项目写入**，set/delete 最终仍写用户状态；不能靠拼 flag 做项目编辑 |
| 配置校验 | `config validate [--json]` | 返回 `valid` 与逐字段 errors；不是完整 manifest/required/未知键校验 |
| 健康检查 | `doctor [--json]` | 消费结构化 `status`，不是只看进程退出码；适用范围见 5.5 |
| 修复 | `doctor --fix` | 有写入、删除孤立状态和联网装依赖的可能，不能作为后台自动刷新 |
| 单包升级 | `upgrade <pkg-or-id>` | npm 包升级到最新发布，Git 重新解析记录来源/ref；link 不可升级；保留升级前启停与功能选择 |
| 全部升级 | `upgrade` | **只处理市场插件**，不是全部 npm/Git 包；按条目可能部分成功 |

#### CLI 输出与“预览”不能照帮助文字泛化

- `--json` 不是全命令纯 JSON 契约：市场 discover/source CRUD/install/uninstall/upgrade 有文本输出；features 修改还可能先输出成功文本再 JSON。适合 GUI 的稳定接口应复用内部方法或增加受控桥接，避免解析装饰终端文本。
- `doctor --json`、`config validate --json` 即便结构内有错误，也不能仅据 exit 0 判成功。
- npm/Git `install --dry-run` 在判断 dryRun 前可能初始化插件根 `package.json`；返回的 `0.0.0-dryrun` 不是已解析版本，也没验证真实 manifest。
- local link 的 dry-run 只是路径预览，不验证目标或 `package.json`；npm uninstall 的 dry-run 也可能未验证确已安装。
- **`upgrade --dry-run` 未被 upgrade handler 使用，仍可能真实升级**；市场 source CRUD 等也不能套用统一 dry-run。GUI 不得用这个 flag 实现“检查更新”。

依据：[插件 CLI][P-cli]（312–407、417–863、865–1102 行）、[插件管理器][P-manager]（480–500、716–773 行）。

### 5.3 manifest 与功能开关

OMP 包从 `package.json` 读取 `omp`，没有时兼容 `pi`；版本取 package 本身的 version。可以保存未知字段，但 UI 不能创造原生不存在的字段语义。

| 字段 | 已有能力 | 限制 |
| --- | --- | --- |
| `name`、`description`、`version` | 显示元数据 | 管理身份仍是 package name，不是任意显示名 |
| `tools`、`hooks` | 基础入口，类型定义为路径 string | resolver 还容忍部分数组形式；目录通常走 index 约定，不是递归执行所有文件 |
| `extensions` | JS/TS 扩展入口列表 | 可文件/目录，按原生 resolver 的 manifest/index/一层子目录规则解析；不把 `.d.ts` 当模块 |
| `commands` | 命令入口列表 | 声明不代表命令已通过诊断并进入当前会话 |
| `features` | 每项有描述、`default` 与附加 extensions/tools/hooks/commands | base 入口始终在；开关只影响附加入口，不是细粒度权限控制 |
| `settings` | 插件自带的简化设置 schema | 不是完整 JSON Schema，也没有通用权限、插件冲突、最低 OMP 版本的求解协议 |

功能选择必须保留三态：`null` 表示采用 manifest 的 `default: true`；`[]` 表示不选任何可选功能；显式数组表示选中集合。安装时 `[*]` 只展开当时所有功能，不保证将来新增功能自动加入。

当前 `features --enable/disable` 对 `null` 默认态构造集合时可能从空集合开始，不能假定它会先展开其余默认功能。GUI 应显示并保存真实三态，区分“恢复默认”和“显式全选”。

**feature 不是整个插件资源开关**：约定目录里的 Skills、Agents、MCP 等可能按启用的整个插件根发现，不随某个 manifest feature 自动关闭。插件间工具、命令、Agent 的同名冲突也不是统一规则：工具/扩展命令可能后写覆盖，Agent 则 first-wins；保留内置命令、快捷键还另有约束。

依据：[插件类型][P-types]（9–163 行）、[入口解析][P-loader]、[能力发现][D-omp]、[扩展运行器][E-runner]。

### 5.4 插件设置：可生成什么表单

- 支持 `string`、`number`、`boolean`、`enum`；公共元数据有 `description`、`secret`、`env`；数值还有 min/max/step，枚举有 values。
- 校验强度有限：number 检查数值、NaN、min/max，不等于有限整数或 step 校验；没有通用 required、数组、对象或完整 JSON Schema 表单契约。
- CLI boolean parser 只把 `true/yes/1` 视为 true，其余字符串可变为 false；不能拿它当严格的用户输入验证器。
- `env` 和默认值出现在 schema/展示路径，不代表 manager 已把它们统一注入每个消费组件；有效值应以插件真实消费行为和原生结果为准。
- `secret` 是遮罩提示，**不是加密保险库**。JSON 输出可能是原值，持久化也不是密文；应用不得将原始 JSON、token、带认证 URL 直接写日志或导出。
- 本层删除设置后仍可能被项目覆盖；项目设置写入若需要支持，应定位真实 override 文件及验证路径，而不是依赖当前无效的 CLI `--local`。

依据：[设置类型][P-types]（55–163 行）、[配置 CLI][P-cli]（865–1047 行）、[管理器校验与写入][P-manager]（981–1011、1287–1336 行）。

### 5.5 诊断、安装验证与回滚

`PluginManager.doctor` 主要检查用户 npm/link 管理视图：插件根、管理 package、node_modules、缺失依赖、孤立 link 状态、依赖版本漂移、缺 manifest、部分基础入口路径、已选未知 feature。`--fix` 可修复其中部分问题。

它**不是**市场全部范围的完整健康扫描、所有命令/feature 内资源校验、MCP 连通性检查、漏洞扫描或权限审计。源码另有旧 `doctor.ts`/`installer.ts`，不能把它们未被当前 CLI 采用的检查和路径限制套进来。

npm/Git 安装会快照管理 package、Bun lock 和目标包目录；失败有回滚。但扩展验证会实际动态 import 并调用 factory，包管理也会调用 Bun。回滚这些文件不能撤销扩展普通 JS、安装过程或第三方依赖产生的所有网络/文件副作用。

普通重新 install 可能重置启用与功能选择；upgrade 才显式保留相关状态。npm upgrade 用包名重装最新，不保证保留最初 range/pin。卸载和 link 没有与安装同等级的快照事务，失败要重新读取实际状态，而不是宣称“失败一定完全没改动”。

依据：[插件管理器][P-manager]（366–455、501–773、863–917、1024–1252 行）、[扩展加载][E-loader]。

## 6. 插件市场：源、目录、包来源、版本与范围

### 6.1 市场源管理

原生有 `plugin marketplace add/remove/update/list`，以及 `plugin discover [marketplace]`。TUI/共享 slash 还有 `/marketplace`、`/plugins`、`/reload-plugins`。

| 添加的市场源 | 原生处理 | 需要表达的限制 |
| --- | --- | --- |
| `owner/repo` | GitHub 仓库克隆 | 与“直接插件安装”所接受的 shorthand 不同 |
| Git HTTPS、SSH、SCP 地址 | 克隆仓库 | 私有仓库认证、网络和 clone 耗时可能失败 |
| HTTP(S) 且 pathname 以 `.json` 结尾 | 拉取目录 JSON | 不是仓库克隆，不能安装依赖该仓库的相对路径条目 |
| 显式本地目录、绝对路径、`~/...` | 读取目录 | 目录 fetcher 会展开 `~`，不要与 link 的行为混淆；某些裸/`../` 写法未获相同分类支持 |

仓库/本地目录优先读取 `.omp-plugin/marketplace.json`，其次 `.claude-plugin/marketplace.json`。添加时缓存目录；日常浏览读缓存，不等于每次都实时拉远端。

市场目录至少有 name、owner.name、plugins；插件条目含 name/source，可携带 description/version/author/homepage/repository/license/keywords/category/tags 及部分能力配置。无效条目可被警告并跳过，不一定导致整个市场失败。名称、大小写等价冲突和目录更新后改名有保护。

- 更新市场＝重取目录/仓库，**不等于升级已安装插件**。
- 移除市场＝删源登记及目录缓存，**不卸载已安装插件**；它们可能继续可用，但失去后续目录更新入口。
- `discover` 没有原生通用评分、下载排行、远程全文搜索或分页参数；宿主可在已取得目录上搜索/按 category、tags 筛选，但应保留所属市场，不能把合并数组中的同名项丢掉。
- 原生示例可添加 `anthropics/claude-plugins-official`；其在线目录确有相对、Git URL、git-subdir 等条目。来源目录可读，不保证每个插件的实际功能都与 OMP/Compose 兼容。

依据：[市场 fetcher][M-fetcher]、[目录类型][M-types]、[市场管理器][M-manager]（109–259 行）、[当前兼容市场示例][W-market]。

### 6.2 市场中的插件 source 与“市场源”是两层

| 条目 `source` | 18.12.6 实现 | 边界 |
| --- | --- | --- |
| `"./plugins/foo"` | 从市场仓库/本地根解析并缓存 | 支持 metadata.pluginRoot；不允许词法路径越过市场根；仅 JSON URL 市场不能使用此来源 |
| `{source:"github", repo, ref?, sha?}` | 克隆 GitHub 插件仓库 | 可指定 ref/SHA；实际 commit/网络/认证仍需成功 |
| `{source:"url", url, ref?, sha?}` | **按 Git 地址克隆** | 名为 url，不等于下载任意 ZIP/文件 |
| `{source:"git-subdir", url, path, ref?, sha?}` | 克隆后选仓库子目录 | 支持 monorepo；拒绝子目录越界和不存在目录 |
| `{source:"npm", package, version?, registry?}` | 类型存在，但 resolver **明确抛错未支持** | 不得因为有 schema 字段就提供可成功安装的选项；这不影响第 4 节的直接 npm 安装 |

安装生成缓存、用户/项目登记、runtime link 和运行状态；还处理运行 package name 与市场 ID 不同、大小写冲突及升级后的包名迁移。目录中的 LSP/DAP 配置可落到 `.lsp.*`/`.dap.*` 对应文件，但安装配置文件不证明语言服务/调试器二进制已存在并正常运行。

`strict`、commands/hooks 等目录字段的出现，也不能泛化成完整 Claude Code 插件协议执行器；应核对具体 discovery provider 是否消费相应能力。

依据：[来源 resolver][M-resolver]（52–88、127–188 行）、[市场安装][M-manager]（264–540、941–1102 行）。

### 6.3 安装范围、遮蔽与更新

- 市场插件支持 `--scope user` 或 `project`；项目实际登记文件按原生解析，不按 UI 输入路径猜测。
- 同 ID 可以同时有用户/项目安装。启用的项目副本优先；禁用它可能恢复用户副本，而不是让能力彻底消失。
- 双范围的启停、卸载和底层单范围升级需要明确 scope。CLI 指定 ID 但不指定范围的 upgrade 会跨已安装范围处理；共享 slash 路径不应被假定具有完全相同的消歧策略。
- 缓存可跨范围共享；`--force` 同版本重装有影响另一范围所用内容的可能。界面应展示共享引用和影响范围。
- 版本来自 manifest、目录、package 或 SHA 的规则依来源而异；更新检查不遍历一切远程 ref 变化。远端条目没有可比较版本时可能跳过，不能把“未报告更新”当成确知最新。
- 检查主要比较更高 SemVer；非 SemVer 标记退回不相等。升级等于按当前目录强制重装，不是通用可回滚的版本历史选择器。
- 批量升级可能跳过失败条目、返回部分成功；GUI 应逐项报告，不能仅显示“全部成功”。登记、缓存、runtime 状态是多步写入，不是跨所有文件的全局事务。

`marketplace.autoUpdate` 有 `off/notify/auto`，默认 notify。启动调度按超过约 24 小时的目录刷新，再查更新；网络/解析失败可能被静默忽略。当前实现的通知主要走日志，不能据设置名称承诺 GUI 已有系统通知、可靠升级徽标或后台任务进度。也不应把这个设置当成所有 npm/Git 插件自动更新策略。

依据：[市场管理器][M-manager]（583–908 行）、[缓存][M-cache]、[自动更新调度][M-auto]、[设置声明][R-settings]（1139–1155 行）。

## 7. 技能管理：发现目录、冲突、文件与使用语义

### 7.1 发现来源与优先级

技能列表不是简单扫描一个目录。当前 provider 有不同优先级与内部顺序：

| 来源 | 优先级/范围 | 边界 |
| --- | --- | --- |
| native authored | 100；项目祖先 `.omp/skills/`，然后用户 `<agentDir>/skills/` | 由 cwd 向上，受仓库、home、配置根等 ceiling 限制；不是所有磁盘祖先无界扫描 |
| Skillshare | 95；项目祖先 lock，再用户 lock | 只加载 store marker 与 lock 匹配的版本，缺内容不在发现时自动联网修复 |
| OMP 扩展/npm/link | 90；effective extension roots 的 `skills/` | CLI、配置及启用包的真实根共同决定，不能只按安装表判断 |
| Claude 目录 | 80；用户与项目 `.claude/skills/` | 用户默认关闭；provider 内并非一律项目优先，不能套统一 project-first |
| Agent Plugins 标准 | 75；标准包 `skills/<name>/SKILL.md` | 严格 frontmatter、目录名和包内 realpath 校验 |
| Codex | 70；用户与 cwd `.codex/skills/` | 用户默认关闭，项目范围不是 native 的相同逐级规则 |
| `.agent`/`.agents` | 70；项目祖先和用户目录 | 有自己的开关与候选目录；兼容目录不代表兼容其全部运行权限 |
| 兼容市场插件 | 70；已安装/显式 plugin roots | foreign Claude 用户源 opt-in 与 OMP 自己安装的插件要区分 |
| OpenCode | 55；用户配置目录与 cwd `.opencode/skills/` | 用户源受 opt-in 控制 |
| GitHub 项目约定 | 30；cwd `.github/skills/` | 这是磁盘发现，不是在线 GitHub 搜索/安装 |
| managed | 5；`managed-skills/` | 关闭自动学习写入后，已有 managed 技能仍可被发现 |
| 自定义根 | `skills.customDirectories` 有序数组 | 扫描根下直接技能子目录，不保证任意深度递归，也不默认把根自身 SKILL.md 当技能 |

默认 `skills.enabled=true`、`enableSkillCommands=true`；Pi/Agents 用户与项目来源、Claude 项目来源默认开，Claude/Codex 用户来源默认关。其他 provider 还受 discovery user opt-in 约束。附加 workspace `/add-dir` 不是自动附加 Skill 根，应单独管理 `customDirectories`。

依据：[技能 loader][S-loader]（272–600 行）、[发现 helpers][D-helpers]、[Skillshare 发现][D-skillshare]、[标准插件发现][D-agent]。

### 7.2 同名技能不是“最后一个覆盖后其余消失”

- authored 对同裸名 Skillshare 具有优先关系；custom root 可优先于 provider。
- 相同 raw name 且正文/frontmatter 完全相同的副本可折叠；真实不同的冲突可被保留为 `namespace/name`，必要时再加 `~2` 等后缀。
- namespace 可来自插件名或目录/provider；UI 应保留 raw name、有效 alias、实际文件及所属包，不能只以显示名作为编辑/删除主键。
- realpath 相同的重复来源去重；最终列表按稳定规则排序。
- managed 优先级最低，遇已占名 authored/custom 不建立同样的别名冲突条目。
- ignored/disabled 会考虑原始名及最终别名；include 针对最终名。被隐藏、过滤、折叠、遮蔽不是卸载。

依据：[技能 loader][S-loader]（62–198、363–600 行）。

### 7.3 `SKILL.md` 至少存在三套验证语义

| 类型 | 字段与验证 | 界面不能混淆的点 |
| --- | --- | --- |
| 普通 legacy 本地技能 | frontmatter + Markdown；可按目录推导 name；`enabled:false` 可跳过；name 不允许路径分隔符；部分 provider 要 description | 相对宽松，未知字段可保留；“本地能加载”不证明符合发布规范 |
| 标准 Agent Plugins 技能 | 封闭顶层字段：name、description、license、compatibility、metadata、allowed-tools | 严格 YAML，不修复；name 与技能目录一致；description 非空且有长度限制；额外顶层字段会拒绝 |
| Skillshare 发布 | 上述严格 schema，再要求 ASCII kebab 名≤64、`metadata.version` 为严格 SemVer | version 不在顶层；metadata 为字符串映射；keywords、repository/homepage 也有规则 |
| managed | 专用名称、非空 description/body、输出 UTF-8 ≤64,000 字节，并清洗描述 | 不是把普通编辑器任意路径交给 `manage_skill`；规则也不完全等于 registry 命名规则 |

legacy 的 `hide:true` 或 `disable-model-invocation:true` 仅使技能从模型系统目录隐藏，仍可能被手动 slash 或 `skill://` 读取；**不是 ACL**。这些顶层字段放进严格发布/标准技能 schema 又可能直接不合法。

不能照搬其他产品的 frontmatter 功能：当前 `user-invocable` 没有可依赖的执行语义；`globs/alwaysApply` 不构成已实现的按文件自动触发引擎；`allowed-tools` 主要是声明/验证元数据，没有在 Skill invocation 中形成强制工具权限约束。

建议编辑器区分“本地模式”“标准/可发布模式”，保留未知字段；默认不直接修改插件缓存或 Skillshare store，提供复制为 authored 工作副本并一并处理相对资源。

依据：[技能类型][S-capability]、[格式验证][D-format]（89–161 行）、[打包][S-pack]（68–147 行）、[managed 写入][S-managed]。

### 7.4 技能使用、隐藏、禁用与会话上下文

| 入口/设置 | 实际行为 | 产品含义 |
| --- | --- | --- |
| `skillful` | 控制系统提示是否列技能目录；默认 true | 不等于卸载、不等于禁止读取；切关不会擦掉已进入历史的内容 |
| `skills.enabled=false`、`--no-skills` | 停止对应技能发现/加载 | 与只隐藏目录不同；显式传固定 skills 的 SDK 会话还有自己的刷新边界 |
| `skills.includeSkills`、`ignoredSkills` | 有序/命名规则下的 glob 过滤 | 空数组、原始名、alias、不同来源要准确处理 |
| `disabledExtensions` 的 `skill:<name>` | 禁止对应技能进入加载结果 | 不是单纯 UI 筛选 |
| `skills.enableSkillCommands` | 是否注册 `/skill:name` | 关闭 slash 不等于关闭模型读取技能 |
| `skill://name` | read 入口读取 SKILL.md；带后缀可读技能根资源 | namespaced 名按实际登记匹配；URL 文件系统的 bare URI 又可表示目录，入口语义不同 |
| `/skill:name args` | 读当前磁盘正文、剥 YAML、带 baseDir/args 构造用户归属的 skill-prompt，进入正常模型任务 | “使用技能”不是纯文件预览，会产生模型执行；必须与“查看内容”分开 |
| Agent 的 `autoloadSkills` | 子代理启动前选定技能正文注入 | 字段属于 Agent 定义，按精确技能名匹配；不是每个 Skill 的全局自动执行开关 |

常规自动注入的是技能名和描述目录，模型再决定是否读完整正文；不是确定性的事件触发规则。`skill://` 对标准/registry 包有 canonical 包内约束，普通 authored 技能不一定具有同等级的 realpath 防护；应用侧文件预览和编辑还要维持自己的安全边界。

普通 SDK 会话还可能后台使用 smol/tiny 模型压缩技能描述并缓存。因此不能承诺“打开任何带技能的会话都不产生额外模型请求”。**静态管理列表/预览不应为取得元数据而启动完整会话**；本次研究只运行管理命令，没有触发该描述压缩路径。

子代理会继承父会话技能集合，autoload 只选全文预注入子集；缺名时可尝试一次刷新，仍找不到会被过滤。刷新或禁用不会撤回已经发给模型的正文、描述及旧历史。

依据：[技能调用][S-loader]（609–714 行）、[URI handler][S-uri]、[描述压缩][S-descriptions]、[SDK][S-sdk]（3928–3945 行）、[子代理准备][S-subagent]、[会话刷新][R-session]。

### 7.5 本地新建、编辑、复制、删除与 managed

这些不是现成的通用 Skill CLI CRUD，应按来源给出可用操作：

- authored：新建目录和 SKILL.md，编辑正文/frontmatter，资源树查看，复制/另存为，重命名并校验目录名，删除目录前预览引用与低优先级同名回退。
- 插件技能：默认只读；可转到所属插件、禁用/卸载所属包，或复制为独立技能。删除某个缓存文件不是正确的插件卸载。
- Skillshare：版本内容默认只读；改范围、更新、卸载走 manifest/lock。手改 store 会影响所有引用者且不保证被完整性 marker 发现。
- managed：保留来源和专用 API 边界；`manage_skill` 的 create/update/delete 仅作用于隔离目录，受自动学习工具注册及写入验证约束，不是给 authored 目录随意 CRUD 的通道。
- 目录复制不能只拿 SKILL.md：正文可能依赖 scripts/references/templates/assets。导入/另存时应列出资源、处理重名、链接、包内外引用、只读和受保护路径。
- 保存使用原生编辑器、原子写入及版本冲突检查；失败保留草稿，返回确认舍弃；预览复用安全 Markdown/图像渲染，不执行脚本或自动打开外部资源。

## 8. Skillshare 技能市场与使用者操作

### 8.1 搜索、详情、安装清单不是同一份列表

| 功能 | 原生提供 | 缺口/边界 |
| --- | --- | --- |
| 会话解析列表 | `omp skill list [dir] --json`，亦有 `omp skills` 入口 | 返回 skills 与 warnings，是所有已发现来源；**不是只列 registry 包**，也不是包含被过滤项的完整库存 |
| registry 已安装列表 | 内部 `listInstalledSkills(cwd)`、TUI `/skills installed` | 可合并 project/user manifest+lock，含 id/range/version/scope/stored；不是独立 CLI `skill installed` action |
| 搜索 | `skill search <query> --json`，配 `--sort` 选择 `relevance`、`downloads` 或 `recent` | JSON 有 total/page/perPage/hits；CLI 只取首个默认页，没有 `--page/--limit`；内部 client 可请求 page |
| 高级 query | 协议支持 `owner:<scope>`、`keyword:<kw>` | 服务端搜索质量、组合规则以实际注册表为准 |
| 首页 | 内部 client.home() | stats、recent/popular/trending、keywords；没有独立 CLI home action |
| 包详情 | `skill info @scope/name --json` | packument 含版本、tags、owners、下载统计、license/repository/homepage、canManage 等 |
| 版本详情 | `skill info @scope/name@range-or-tag --json` | resolve 后含文件清单、大小/hash/可执行、metadata、compatibility、provenance；不等于 CLI 已拉源码/README 正文 |
| README/源码/原文件 | wire 有 readme/source/file routes | 当前 SkillshareClient/CLI 未提供完整 convenience 方法；GUI 需另接安全读取，不执行服务端 HTML/脚本 |
| 作者/组织/举报/管理员 | wire 有相应 profile/org/report/admin 契约 | 不等于 OMP CLI 已有组织管理、举报、下架界面；需要独立权限/服务端接入，不能伪装现成能力 |

搜索 hit 可展示 scope/name、description、keywords、version、publisher、updatedAt、weeklyDownloads、deprecated；版本还可展示脚本风险、压缩/解压大小、文件数、yanked、integrity、reported/verified provenance。不要把“作者自报 Git 来源”和“服务端验证的 GitHub Actions 来源”画成同一个可信徽标。

原生公开 GET 通常不携带已有登录的 Bearer；`info` 的 canManage 不能直接当作已登录用户的最终权限证明。实际写操作仍需服务器鉴权。

市场网站的首页、搜索和排序已只读观察；当前内容规模见第 13 节。网站能显示的页面不等于所有页面都有现成 CLI/API 包装。

依据：[技能列表][S-list]、[安装与搜索包装][S-installer]（465–585、687–712 行）、[注册表 client][S-client]、[固定 wire 契约][S-wire]。

### 8.2 安装、恢复、更新、锁定与卸载

```text
omp skill install @alice/pdf-tools
omp skill install -g '@alice/pdf-tools@^1.2'
omp skill install '@alice/pdf-tools@1.2.3'
omp skill install '@alice/pdf-tools@latest'
omp skill install
omp skill update [@alice/pdf-tools ...] [-g]
omp skill uninstall @alice/pdf-tools [-g]
```

上面是语法示例，不代表示例包当前在线存在。

- install 默认项目，`-g/--global` 为用户；registry 的 `scope` 是发布者命名身份，与本地 project/user 安装范围是两回事。
- 无版本后缀首次选 latest，但 manifest 保存 `^<resolvedVersion>`；以后范围内 update **不等于永远跟 latest**。显式 `@latest` 才保存 tag，exact 版本则形成锁定效果。
- 范围解析选最高满足且未 yanked 的版本；显式 exact/tag 仍可能选到 yanked 并警告。deprecated 是风险警告，不自动禁止安装，不自动替换为别的包。
- 无参数 install 根据 manifest/lock 恢复：lock 与 store marker 匹配时可不联网；缺 store 时恢复锁定版本；不是每次都把依赖重解到最新。
- update 按现有 range/tag 重解并修复内容，跨 major 要显式修改范围；没有通用的“只检查不写入”的 CLI 预览模式。
- uninstall 管理包声明与锁，不是删除任意 authored 技能；名称与是否已装有校验。

声明结构如下，`resolved` 为注册表相对路由，不是任意远端下载 URL：

```json
{
  "skills": {
    "@alice/pdf-tools": "^1.2.3"
  }
}
```

```json
{
  "version": 1,
  "skills": {
    "@alice/pdf-tools": {
      "version": "1.2.3",
      "integrity": "sha512-<base64>",
      "resolved": "/api/v1/skills/@alice/pdf-tools/versions/1.2.3/tarball"
    }
  }
}
```

前者是 `skills.json`，后者是 `skills.lock.json`。UI 应区分已声明、已锁定、已下载、当前被选中、过滤/冲突、锁有而内容缺失等状态；不能只显示“安装成功”。无效 JSON/schema/ID/lock version 应保留可修复入口，不能当空列表覆盖。

### 8.3 脚本确认、完整性与故障边界

- 包携带 executable 或 scripts/ 下文件时，原生有 hasScripts 确认流程；`--yes` 跳过确认，不是提供沙箱。非 TTY CLI 默认拒绝未确认脚本，GUI 必须接原生确认 hook，不能偷偷始终加 `--yes`。
- 安装器下载普通资源，不执行 Skill scripts/npm lifecycle；但之后模型可以按技能内容运行终端命令。这不是“技能绝不执行代码”。
- 下载 tarball 先核 SHA-512 SRI，再解包；拒绝越界、重复、链接/设备等非普通文件，要求根 SKILL.md，并在 staging 写入。
- `.skillshare-integrity` 是已验证归档的 marker。发现和 stored 判断只比较 marker，**不每次重哈希磁盘全部内容**；不能把它当“手改缓存必被检测”的保证。
- 多包下载/确认失败通常发生在 manifest/lock 写入前，但已经进入 store 的前序版本可留下；manifest 和 lock 各自原子替换，**不是跨两文件事务**，后续 prune 失败也可能发生在新声明已落盘之后。
- prune 只检查当前项目与用户 lock，**不扫描全部其他项目引用**；另一项目可能需重新 install/update 恢复内容。不能宣称原生是全机引用计数 GC。
- 未见通用自动重试、完整离线 metadata 缓存、多包全局回滚承诺；网络/完整性/磁盘失败要显示逐阶段实际结果。

### 8.4 注册表切换的重要缺口

`skills.registryUrl` 默认 `https://skills.omp.sh`；底层 client 接受指定 registryUrl，URL 限 http/https 并清理凭据/查询/fragment，但不强制 HTTPS。

**当前消费类 CLI 的 `install/update/search/info` 没有透传 `--registry`**。帮助里虽列这个 flag，实际包装器仍从设置/默认值建 client；作者 management 路径才消费该 flag。本次隔离实测也观察到 `skill search pdf --registry https://127.0.0.1:1 --json` 仍正常返回默认市场结果，而不是连接该端口。

GUI 若需要单次切换，应传明确 client 或使用真实配置解析，不能只拼 `--registry`。store 坐标也没有 registry origin；不同 registry 的同 scope/name/version 可能发生完整性冲突，不是天然多源隔离。

依据：[installer][S-installer]（43–188、272–462、620–650 行）、[manifest][S-manifest]、[CLI dispatch][S-cli]（490–507 行）、[client][S-client]（81–125 行）。

## 9. Skill 作者与市场管理高级功能

这些能力应可发现，但不必全堆在普通技能列表；适合作为“发布与维护”的高级入口。以下多为客户端源码/协议确认，**未使用真实账号执行写操作**。

| 操作 | 原生语法/能力 | 权限、风险与限制 |
| --- | --- | --- |
| 发布 | `skill publish [dir] --scope <scope> --tag <tag>` | 先本地 pack/校验，需登录或发布 token；版本不可变，409 应引导升版本，不覆盖线上同版本 |
| 发布预检 | `publish [dir] --dry-run` | 同一打包/扫描，不上传；仍可能创建 client/读认证/解析 scope，不能宣传绝对无凭据读取 |
| 改版本 | `version <kind> [dir]`，kind 为 `patch`、`minor`、`major` 或具体版本 | 只改 SKILL.md 的 metadata.version 并保留其他字节；首次 patch=0.0.1；显式版本须升高；某些 inline YAML 布局会拒绝重写 |
| 标签 | `tag @scope/name@version tag`；`tag rm @scope/name tag` | exact 版本；latest 不能删，只能改指向；tag 不能伪装成版本/range |
| 撤回解析资格 | `yank @scope/name@version [--undo]` | 不是删除已下载文件/撤销历史；range 排除，exact/tag 仍可能安装并警告 |
| 弃用提示 | `deprecate @scope/name[@range] message` 或 `--undo` | 可按版本范围/全部版本逐一处理；消息≤500 字符；不是批量事务，也不是删除包 |
| 维护者 | `owner add/rm @scope/name username` | 整包权限，不能带版本；owners 可由 info 读，没有独立 owner-list action |
| 创建发布 token | `token create <name> --package @scope/name --expires <days>` | 包限制可重复，天数为正数；未列包表示创建者所管理包范围；secret 仅返回一次 |
| token 管理 | `token ls --json`、`token revoke <id>` | ls 读元数据，不能恢复 secret；revoke 是写操作，需确认，不写入诊断导出 |
| `.skill` 转换发布 | `skill import file.skill` | POST ZIP 到注册表，由服务器转换并发布；此路径不消费 dry-run/scope/tag，不能当本地安全预览 |
| 发布来源 | reported Git remote/commit、OMP version；GitHub Actions OIDC | reported 不等于 verified；OIDC 由服务器验证；不能把任意自报仓库当可信签名 |
| 组织、举报、下架 | wire 有组织角色/成员、report、管理员 takedown routes | 当前 CLI/client 没有相应完整管理入口；不把协议类型冒充已可直接调用的图形功能 |

### 9.1 认证不是模型 Provider 认证的副本

- 读搜索、info、tarball、home 通常匿名；作者 mutation 走 Stencil 认证/共享凭据或适用环境入口。
- **`SKILLS_TOKEN` 只在 publish 路径读取**，不是 tag/yank/deprecate/owner/token/import 的通用凭据替代品；发布 token 不等于全部维护权限。
- 默认 scope 可从登录身份推导，但 registry scope 与账号不可变身份绑定属于服务器契约；最终权限以服务器 401/403/409 等为准，不能只靠前端解析 JWT 用户名。
- 新界面不能复用模型 Provider 的 API Key 表单并宣称同时登录技能市场；凭据不进项目、不跟目录复制、不导出到包。

### 9.2 发布 schema、打包和扫描

| 项目 | 客户端限制 |
| --- | --- |
| 名称 | ASCII kebab-case，1–64 字符；scope 为 3–32 位小写字母/数字/下划线规则 |
| 顶层 frontmatter | name、description、license、compatibility、metadata、allowed-tools；不是任意顶层字段 |
| 发布版本 | `metadata.version` 必需且严格 SemVer |
| 描述/兼容性 | description≤1024 字符；严格技能 compatibility 有自身≤500 字符约束 |
| keywords | metadata 中逗号分隔，最多 20 个，每个≤50 字符 |
| repository/homepage | 发布元数据要求 HTTPS URL |
| 包体 | `.tgz`≤5 MiB，解包总大小≤20 MiB，最多 1000 文件；不是每文件都只准 5 MiB |
| 路径/文件 | 路径 UTF-8≤255 字节并符合 tar 切分约束；只普通文件，拒 symlink 和不支持类型 |
| 忽略 | 默认排除 `.git`、`.DS_Store`、node_modules、`__pycache__`、`*.pyc`、evals、`.skillignore`，再叠加 `.skillignore` 规则 |
| 打包 | 排序、固定 mtime/uid/gid 和 0644/0755 模式，产生确定性归档与 SRI |
| 凭据扫描 | 检查文本中的 credential-shaped 内容，输出路径/行/种类，不输出匹配 secret；默认拒绝，可被 `--allow-secrets` 绕过 |

`.skillignore` 是 gitignore 子集，不等于自动读取 `.gitignore`；反向规则可能重新包含默认排除项，不能把默认忽略当不可绕过的保密屏障。二进制/模式扫描也不是病毒、提示注入或恶意脚本审计。wire 声明服务端也执行限制，不代表本次已验证服务端实现，尤其 `.skill import` 不能直接套用本地 pack 的所有保障。

依据：[作者 CLI][S-cli]（122–465 行）、[打包实现][S-pack]、[tar 安全][S-tar]、[注册表 client][S-client]、[wire][S-wire]。

## 10. 两个管理界面的建议功能版图

以下均为 **[INFERENCE] 设计建议**，覆盖原生能力与必要的 GUI 补足，不规定实现架构，也不把未接入能力画成已完成。

### 10.1 插件管理

| 逻辑界面 | 应包含的内容/动作 |
| --- | --- |
| 已安装列表 | 当前上下文、来源/范围分组、搜索、启停/遮蔽/异常/更新过滤；返回保留滚动与筛选 |
| 插件详情 | 管理身份、显示元数据、来源 spec、实际路径、版本、scope、共享缓存、声明入口、加载诊断、所属市场；转到提供的 Skills/Agents/MCP |
| 安装 | 明确选择 npm、Git、市场、本地链接；版本/ref/功能和范围仅在适用来源提供；风险确认与逐项进度 |
| 功能选择 | 默认/显式集合三态、每项说明、缺失功能、恢复默认；不虚构权限开关 |
| 设置 | 原生 schema 表单、秘密遮罩、本层/继承/有效值、清除覆盖、验证和保留未知字段的高级查看 |
| 更新 | 按来源区分查新与执行，展示 from/to/同版本新 revision、pin/range 改变、范围、部分失败；没有真实预检就不显示伪造 diff |
| 市场源 | 添加、列表、刷新、移除；来源、更新时间、失败、目录改名、无效条目提示；移除不隐式卸载 |
| 市场浏览 | 每市场目录、搜索/分类/标签、详情、已装 scope、来源支持/拒绝原因、安装入口 |
| 开发链接 | 容器目录选择、package 检查、覆盖风险、源目录定位、断链状态；不承诺 watch 热更新 |
| 诊断修复 | 原生检查项、warning/error、修复计划与确认、逐项结果；修复不自动批准第三方执行 |

### 10.2 技能管理

| 逻辑界面 | 应包含的内容/动作 |
| --- | --- |
| 已发现技能 | 按当前项目/profile 的真实解析列表，名称/描述、来源/别名、隐藏/过滤/冲突/warnings；与“市场已安装”可切视角 |
| 技能详情 | raw name/alias、SKILL.md/资源树、所属包/版本/范围、完整原始描述、模型目录展示差异、被遮蔽关系 |
| 内容预览 | Markdown/代码/图像安全预览、原文查看和容器内资源定位；绝不借预览自动执行 Skill |
| 本地编辑器 | 新建、schema 模式、frontmatter+正文、资源引用、预览、保存、冲突、舍弃确认；复用原生代码编辑器 |
| 复制/导入 | 用户/项目目标、目录选择、资源清单、重名/链接/越界检查；“.skill 在线发布”单独命名并风险确认 |
| 加载设置 | 总开关、模型目录展示、slash 开关、来源 opt-in、自定义目录、include/ignore；提供本层与有效状态 |
| Skillshare 浏览 | 首页/搜索/分页/排序/owner 与 keyword 条件、空态/网络失败；不要为补画列表造假市场数据 |
| 包与版本详情 | tags/版本、文件清单、脚本风险、完整性、大小、发布者、来源、yanked/deprecated、安装范围与约束 |
| 已声明包管理 | manifest/lock/store 三层、恢复锁定版本、范围内更新、显式改范围/版本、卸载和共享缓存影响 |
| 作者高级 | 本地预检、发布、版本/标签/弃用/撤回、owners/tokens、实际登录和权限错误；组织等非现成入口另标接入缺口 |
| managed 来源 | 只在真实可写/可桥接条件下提供专用操作，清晰标识生成来源和限制，不混成市场内容 |

两页之间应能按所属关系跳转，而不是各自复制一套安装状态。Agent 设置的 `autoloadSkills` 继续从真实技能目录选择；技能被禁用/改名/卸载时显示失效引用，不擅自改写 Agent 定义。

## 11. WawLink 接入边界与生效时机

### 11.1 当前工程已经提供什么

- `AgentManagementState.skills` 目前只是 `ConfigurationCapability` 摘要，主要用于 Agent 编辑时选择技能。
- `omp-management/agents.ts` 调用原生 loadSkills 后只映射 name/description/source，没有完整路径、所属包、版本、过滤库存或安装操作；还为该目录读取显式开启 enabled，不能当当前聊天会话的有效加载状态。
- `AgentManagementRepository` 和 `omp-management/main.mjs` 没有插件/Skillshare 的完整 CRUD/市场/发布契约。现有全局设置与受控文件能力是复用基础，不代表新增两个界面已自动拥有原生管理能力。
- 聊天 RPC 的公开 `RpcCommand` 有会话、模型、工具桥接、命令目录等，没有现成独立 `plugin_install`/`skill_search` 管理 RPC。应通过容器中的原生管理模块/命令受控桥接，不在 Kotlin 里另造包管理器或技能解析规则。

依据：[管理协议](../agent-protocol/src/commonMain/kotlin/dev/wawlink/protocol/AgentManagement.kt)、[现有目录映射](../agent-driver-omp/src/jvmSharedMain/resources/omp-management/agents.ts)（253–273 行）、[桥接 dispatch](../agent-driver-omp/src/jvmSharedMain/resources/omp-management/main.mjs)、[原生 RPC 类型][R-types]（35–145 行）。

### 11.2 原生 Extension 能力不等于 Compose 可渲染能力

| 能力 | 原生边界 | 当前 GUI 接入含义 |
| --- | --- | --- |
| 工具、命令、事件 | Extension API 可注册并影响 session/turn/tool/approval/input 等 | 需要实际加载和事件桥接；manifest 里有声明不等于已经可调用 |
| Provider/模型 | 可通过 Extension 注册 Provider、模型、OAuth、动态发现等 | 不是每个插件自带统一 Provider 表单，且需要刷新真实模型/认证目录 |
| 兼容资源 | OMP/Claude 可发现 commands/rules/skills/agents/pre-post hooks/tools/MCP；标准包 portable 组件另有严格规则 | 不能宣称任意 Claude hooks.json 事件、模型别名或全部插件 API 都兼容 |
| RPC 结构化交互 | select/confirm/input/editor、通知/状态可通过帧表达；ask 需宿主 opt-in 或 fallback | 当前 reducer 已映射部分对话/状态；不是完整的可视插件 UI 宿主 |
| RPC widget/editor | widget 仅文本数组；set editor text 有协议退化，读编辑器文本也有限制 | 当前工程没有完整映射全部 widget/editor/open_url 方法，不能宣传完整支持 |
| TUI 自定义组件 | header/footer、custom component、终端输入、编辑器替换、自动补全/主题等不具等价 RPC 渲染 | 不能在 WebView 里执行终端组件来冒充 Compose 支持；需显示不适用/不支持能力 |
| `--no-ui` | Extension 真正 headless，hasUI=false，部分对话默认取消/undefined | 与普通 RPC 不同；工具 UI、宿主登录还有各自通路，不能用一个总开关解释一切 |
| LSP/DAP/MCP | 插件可供配置或服务器定义 | 真正进程、二进制、平台、连接、授权要独立验收，不能因安装成功显示全绿 |

当前映射依据：[OmpEventReducer.kt](../agent-driver-omp/src/jvmSharedMain/kotlin/dev/wawlink/driver/OmpEventReducer.kt) 的 `extensionRequest`；原生依据：[RPC 实现][R-mode]（1331–1669 行）、[Extension 类型][E-types]、[标准格式][D-format]。

### 11.3 重载必须按资源/宿主区分

- CLI 安装/启停/编辑是另一个进程，不会自动更新已经运行的 WawLink 会话。
- `/reload-plugins` 名称中的“plugins”不能解释成所有 JS factory、hooks、tools 都已安全热替换。
- RPC 实现清 plugin/cache，调用 `refreshSkillsAndCommands()`，刷新技能与文件 slash 命令、系统提示和命令目录事件。
- TUI 对应路径还刷新 Agent discovery、能力缓存及 MCP 连接；与 RPC 不是相同覆盖面。
- SDK 若显式传固定 `options.skills`（包括 `[]`），刷新不一定重新发现技能；已有 extension roots 的取值也有创建时语义。
- 对未有明确重绑定路径的可执行 Extension/工具/hooks，稳妥反馈为“已保存，新会话/重启后生效”；运行中会话不得因后台安装被无提示中断。
- 禁用/卸载/重载不会擦除已投递给模型的历史内容，也不保证终止插件先前启动的一切外部副作用。

依据：[RPC reload][R-mode]（1745–1752 行）、[会话刷新][R-session]（6787–6811 行）、[TUI reload][M-slash]（25–42、489–503 行）。

## 12. 安全与必须呈现的状态/验收边界

### 12.1 信任模型

插件是可信本地代码，不是声明式安全资源。Extension 可执行普通 JS、exec、注册工具/Provider 并影响会话；加载期 HostGuard 主要阻止 process.exit/stdin 劫持，不是文件、网络或凭据隔离沙箱。工具免审批也不能代替安装风险确认，更不能证明第三方代码受工具审批机制约束。

安装前应明确来源、实际 scope/路径、版本或 ref、执行型组件/脚本、覆盖和共享缓存影响；不提供无依据的“官方安全”“权限最小化”“完全可回滚”标签。包描述、README、SVG、链接和日志均为不可信内容，按现有安全渲染与容器文件操作约束处理。

### 12.2 原型与实现应覆盖的状态矩阵

| 场景 | 必须表达/验证的结果 |
| --- | --- |
| 无项目/容器已停止/OMP 未安装或版本不符 | 用户/项目动作适用性真实；可修复/重试，不能写到开发机或默认根目录 |
| 空安装、无市场源、搜索无结果 | 三种独立空态；不使用原型样例冒充真实目录 |
| 首屏加载 | 本地已装/已发现先展示，网络市场与作者认证分阶段；不用完整会话或模型 SDK 阻塞首屏 |
| 市场目录部分坏条目、缓存缺失、上游改名 | 保留可用数据和明确诊断，不把读取失败当“全部已卸载” |
| npm/Git 下载、Bun、ref/认证、包 manifest/factory 失败 | 可定位且脱敏的原生原因，实际回滚/残留状态重新读取 |
| 脚本确认取消、非 TTY 确认 | 不默认 yes，不启动伪成功安装；返回保留选择和草稿 |
| 双 scope、同市场/跨市场同名、大小写/package name 冲突 | 安装/启停/升级/卸载均绑定稳定 ID+scope，解释遮蔽与回退 |
| declared/locked/store 缺失或损坏 | 区分恢复锁定版本与查新；有修复入口，不擅自升级或清空配置 |
| registry 切换和同坐标内容变化 | 防止误用默认源；完整性冲突显式失败，不静默信任替换内容 |
| 批量安装/升级/卸载部分成功 | 每项成功/失败/未执行和实际持久状态；不能统一报成功或统一假称回滚 |
| authored 编辑与外部改动冲突 | 保存不静默覆盖，保留草稿，支持重读/差异/适用覆盖确认 |
| 删除/改名被 Agent autoload 引用的技能 | 展示影响；不自动重绑到相似名字，不偷偷改 Agent 定义 |
| 保存后当前会话未重载 | 独立 pending-reload 状态与适用重载入口；回到聊天保持滚动/草稿 |
| token/secret/带认证 URL 错误 | 展示对象/字段/阶段，隐藏实际秘密；一次性 token 不进日志/项目/截图导出 |
| 平台不可执行、MCP 连接或 TUI-only 能力 | “安装完成”与“能力不可用/未加载”分开，不伪造平台支持 |
| 发布账号无权限、immutable 版本、撤回/弃用 | 原生 401/403/409 和语义正确，危险 mutation 确认；不伪装删除线上历史 |

## 13. 本次实际验证与未验证范围

### 13.1 已运行和观察

以下均在独立临时 HOME/配置/项目中执行，市场包是自建的纯元数据、SKILL.md 与未执行脚本资源；没有安装第三方可执行 Extension。

| 实际场景 | 观察结果 |
| --- | --- |
| `omp --version` 及顶层/plugin/skill/install help | 本机 `omp/18.12.6`，确认真实 action 与 flag 入口 |
| `omp config list --json` | 确认 marketplace.autoUpdate、skillful、skills.*、extensions/disabledExtensions 的真实默认配置 |
| 空 `plugin list --json`、`skill list --json` | 分别返回 npm/marketplace 空组、skills/warnings 空组 |
| 本地 `.omp-plugin/marketplace.json` add/discover | 添加、缓存、列目录成功；discover 加 `--json` 仍为文本 |
| 市场 install/uninstall dry-run | 适用路径返回操作预览；不是整个 CLI 的通用无副作用承诺 |
| 市场 `source:npm` dry-run | exit 1，明确 `npm plugin sources are not yet supported` |
| 市场相对 source `./../outside` dry-run | exit 1，明确越过 marketplace root |
| 同插件用户/项目安装 | 项目先列，用户条目有 `shadowedBy: project`；两份可共享缓存 |
| 未指定 scope 禁用/卸载双范围 ID | exit 1，要求明确 user/project |
| 禁用项目副本、再禁用用户副本 | Skill 来源由 `claude-plugins:project` 回退 `claude-plugins:user`，两份都禁用后列表为空 |
| 刷目录并升级用户副本 | 自建插件 1.0.0→1.1.0 成功；项目禁用状态及其旧记录仍可区分 |
| 移除市场源 | 已装技能仍能发现；后续卸载包后才从技能列表消失 |
| Git ref+SHA 市场来源 | 从临时本地 Git 仓库按 tag+SHA 克隆安装到项目成功，技能可发现；卸载成功 |
| 缺 metadata.version 的 publish dry-run | 被本地校验拒绝，不上传 |
| `skill version patch` 后 publish dry-run | 写出 0.0.1，打印文件/大小/integrity，明确 nothing published |
| 添加 scripts/ 文件后 publish dry-run | 打包成功并显示 ships scripts 风险；脚本未执行 |
| 默认 Skillshare `search pdf --sort downloads --json` | 在线连接成功，返回 total 0、page 1、perPage 20、hits 空 |
| 消费 CLI `--registry` 缺口 | 指向不可连接的本地 HTTPS 端口仍返回默认市场搜索结果，与源码未透传一致 |
| Skillshare 网站只读浏览 | 首页与搜索/排序控件加载正常；当时首页 packages/versions/publishers/weekly downloads 均为 0，pdf 搜索为空；已关闭浏览器标签 |

网站规模是观察时点的结果，不保证未来仍为空；也不能据此伪造线上包下载、版本详情或维护者操作的验收。

### 13.2 未执行、不能宣称验收

- 真实 npm/GitHub/私有仓库插件安装及依赖脚本、任意第三方 Extension factory、全部 Provider/MCP/LSP/DAP 插件运行。
- 线上真实 Skillshare 包下载/更新/含脚本安装确认、注册表间切换安装、恶意归档和磁盘故障注入。相关边界来自源码，不冒充实测通过。
- 真实账号登录、发布、`.skill` 在线转换、tag/yank/deprecate、维护者/token、组织/举报/管理员和 OIDC 服务端验证。
- 模型实际选择技能、后台 smol/tiny 描述压缩、子代理 autoload、已有会话完整热重载。
- 新增 Compose 管理页面、Android/桌面交互验收。本次是能力研究，不是功能实现，不需要运行 Gradle/测试套件冒充 UI 验收。

## 14. 核对依据与后续设计入口

### 14.1 固定版本原生源码

以下全部锁定 18.12.6；正文行号用于定位同一发布文件，不引用滚动分支作原生能力基准。

- [主包发布元数据与归档完整性][V-package]；[pi-utils 目录解析][U-dirs]；[Skillshare wire][S-wire]。
- 插件：[CLI handler][P-cli]、[安装分类][P-classify]、[顶层 install][P-install]、[manager][P-manager]、[loader][P-loader]、[类型/schema][P-types]、[spec parser][P-parser]、[Git parser][P-git]。
- Extension：[API/上下文/UI 类型][E-types]、[动态加载][E-loader]、[运行器与冲突][E-runner]、[HostGuard][E-guard]。
- 市场：[类型][M-types]、[fetcher/目录校验][M-fetcher]、[source resolver][M-resolver]、[manager][M-manager]、[registry][M-registry]、[cache][M-cache]、[自动更新][M-auto]、[slash/reload][M-slash]。
- 发现：[OMP 插件][D-omp]、[Claude 兼容插件][D-claude]、[Agent Plugins][D-agent]、[标准格式与验证][D-format]、[公共 helpers][D-helpers]、[Skillshare provider][D-skillshare]、[Agent 发现][D-agents]。
- 技能：[设置][S-settings]、[loader/冲突/调用][S-loader]、[能力类型][S-capability]、[CLI list][S-list]、[Skill CLI/作者命令][S-cli]、[registry client][S-client]、[installer][S-installer]、[manifest/路径][S-manifest]、[pack/version][S-pack]、[tar][S-tar]、[managed][S-managed]、[URI][S-uri]、[描述压缩][S-descriptions]、[SDK][S-sdk]、[子代理][S-subagent]。
- 会话/宿主：[RPC 类型][R-types]、[RPC 执行与 UI/reload][R-mode]、[会话资源刷新][R-session]、[市场设置][R-settings]。

### 14.2 外部市场与工程设计资料

- [Skillshare 公开市场][W-skills]、[搜索页][W-search]：本次只读访问；内容与规模会变化，不锁定为版本事实。
- [Claude 官方兼容市场目录示例][W-market]：用于确认现实目录格式，不承诺全部插件兼容或安全。
- [Agent 与 Models 能力清单](./agent-settings-capabilities.md)、[Agent 设置原型](./agent-settings.html)、[总体规划](./plan.md)：继续保留已有页面职责与视觉，不修改原型演示内容。
- 本文不新增运行协议、不实现安装器、不更改现有模型/认证策略；后续实现应按实际容器版本重新发现原生能力，并用本地/注册表夹具及适用真实服务逐条验收第 12 节边界。

[V-package]: https://registry.npmjs.org/@oh-my-pi/pi-coding-agent/18.12.6
[U-dirs]: https://unpkg.com/@oh-my-pi/pi-utils@18.12.6/src/dirs.ts
[P-cli]: https://unpkg.com/@oh-my-pi/pi-coding-agent@18.12.6/src/cli/plugin-cli.ts
[P-classify]: https://unpkg.com/@oh-my-pi/pi-coding-agent@18.12.6/src/cli/classify-install-target.ts
[P-install]: https://unpkg.com/@oh-my-pi/pi-coding-agent@18.12.6/src/commands/install.ts
[P-manager]: https://unpkg.com/@oh-my-pi/pi-coding-agent@18.12.6/src/extensibility/plugins/manager.ts
[P-loader]: https://unpkg.com/@oh-my-pi/pi-coding-agent@18.12.6/src/extensibility/plugins/loader.ts
[P-types]: https://unpkg.com/@oh-my-pi/pi-coding-agent@18.12.6/src/extensibility/plugins/types.ts
[P-parser]: https://unpkg.com/@oh-my-pi/pi-coding-agent@18.12.6/src/extensibility/plugins/parser.ts
[P-git]: https://unpkg.com/@oh-my-pi/pi-coding-agent@18.12.6/src/extensibility/plugins/git-url.ts
[E-types]: https://unpkg.com/@oh-my-pi/pi-coding-agent@18.12.6/src/extensibility/extensions/types.ts
[E-loader]: https://unpkg.com/@oh-my-pi/pi-coding-agent@18.12.6/src/extensibility/extensions/loader.ts
[E-runner]: https://unpkg.com/@oh-my-pi/pi-coding-agent@18.12.6/src/extensibility/extensions/runner.ts
[E-guard]: https://unpkg.com/@oh-my-pi/pi-coding-agent@18.12.6/src/extensibility/utils.ts
[M-types]: https://unpkg.com/@oh-my-pi/pi-coding-agent@18.12.6/src/extensibility/plugins/marketplace/types.ts
[M-fetcher]: https://unpkg.com/@oh-my-pi/pi-coding-agent@18.12.6/src/extensibility/plugins/marketplace/fetcher.ts
[M-resolver]: https://unpkg.com/@oh-my-pi/pi-coding-agent@18.12.6/src/extensibility/plugins/marketplace/source-resolver.ts
[M-manager]: https://unpkg.com/@oh-my-pi/pi-coding-agent@18.12.6/src/extensibility/plugins/marketplace/manager.ts
[M-registry]: https://unpkg.com/@oh-my-pi/pi-coding-agent@18.12.6/src/extensibility/plugins/marketplace/registry.ts
[M-cache]: https://unpkg.com/@oh-my-pi/pi-coding-agent@18.12.6/src/extensibility/plugins/marketplace/cache.ts
[M-auto]: https://unpkg.com/@oh-my-pi/pi-coding-agent@18.12.6/src/extensibility/plugins/marketplace-auto-update.ts
[M-slash]: https://unpkg.com/@oh-my-pi/pi-coding-agent@18.12.6/src/slash-commands/builtin-marketplace.ts
[D-omp]: https://unpkg.com/@oh-my-pi/pi-coding-agent@18.12.6/src/discovery/omp-plugins.ts
[D-claude]: https://unpkg.com/@oh-my-pi/pi-coding-agent@18.12.6/src/discovery/claude-plugins.ts
[D-agent]: https://unpkg.com/@oh-my-pi/pi-coding-agent@18.12.6/src/discovery/agent-plugins.ts
[D-format]: https://unpkg.com/@oh-my-pi/pi-coding-agent@18.12.6/src/discovery/agent-plugin-format.ts
[D-helpers]: https://unpkg.com/@oh-my-pi/pi-coding-agent@18.12.6/src/discovery/helpers.ts
[D-skillshare]: https://unpkg.com/@oh-my-pi/pi-coding-agent@18.12.6/src/discovery/skillshare.ts
[D-agents]: https://unpkg.com/@oh-my-pi/pi-coding-agent@18.12.6/src/task/discovery.ts
[S-settings]: https://unpkg.com/@oh-my-pi/pi-coding-agent@18.12.6/src/extensibility/settings.ts
[S-loader]: https://unpkg.com/@oh-my-pi/pi-coding-agent@18.12.6/src/extensibility/skills.ts
[S-capability]: https://unpkg.com/@oh-my-pi/pi-coding-agent@18.12.6/src/capability/skill.ts
[S-list]: https://unpkg.com/@oh-my-pi/pi-coding-agent@18.12.6/src/cli/skill-list.ts
[S-cli]: https://unpkg.com/@oh-my-pi/pi-coding-agent@18.12.6/src/cli/skill-cli.ts
[S-client]: https://unpkg.com/@oh-my-pi/pi-coding-agent@18.12.6/src/skillshare/client.ts
[S-installer]: https://unpkg.com/@oh-my-pi/pi-coding-agent@18.12.6/src/skillshare/installer.ts
[S-manifest]: https://unpkg.com/@oh-my-pi/pi-coding-agent@18.12.6/src/skillshare/manifest.ts
[S-pack]: https://unpkg.com/@oh-my-pi/pi-coding-agent@18.12.6/src/skillshare/pack.ts
[S-tar]: https://unpkg.com/@oh-my-pi/pi-coding-agent@18.12.6/src/skillshare/tar.ts
[S-managed]: https://unpkg.com/@oh-my-pi/pi-coding-agent@18.12.6/src/autolearn/managed-skills.ts
[S-uri]: https://unpkg.com/@oh-my-pi/pi-coding-agent@18.12.6/src/internal-urls/skill-protocol.ts
[S-descriptions]: https://unpkg.com/@oh-my-pi/pi-coding-agent@18.12.6/src/extensibility/skill-descriptions.ts
[S-sdk]: https://unpkg.com/@oh-my-pi/pi-coding-agent@18.12.6/src/sdk.ts
[S-subagent]: https://unpkg.com/@oh-my-pi/pi-coding-agent@18.12.6/src/task/structured-subagent.ts
[S-wire]: https://unpkg.com/@oh-my-pi/pi-wire@18.12.6/src/skillshare.ts
[R-types]: https://unpkg.com/@oh-my-pi/pi-coding-agent@18.12.6/src/modes/rpc/rpc-types.ts
[R-mode]: https://unpkg.com/@oh-my-pi/pi-coding-agent@18.12.6/src/modes/rpc/rpc-mode.ts
[R-session]: https://unpkg.com/@oh-my-pi/pi-coding-agent@18.12.6/src/session/agent-session.ts
[R-settings]: https://unpkg.com/@oh-my-pi/pi-coding-agent@18.12.6/src/modes/settings.ts
[W-skills]: https://skills.omp.sh/
[W-search]: https://skills.omp.sh/search
[W-market]: https://raw.githubusercontent.com/anthropics/claude-plugins-official/main/.claude-plugin/marketplace.json
