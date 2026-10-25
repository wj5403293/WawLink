# Session 历史树与 Agent Hub 原型能力清单

## 1. 目标与口径

为 WawLink 设计以下能力：**Session 历史树 bottom sheet、运行时 Agent Hub、可单独打开且明确标识身份的 Subagent 查看与操作页面**。覆盖 OMP TUI 的历史浏览、回退、分支、消息复制及子代理观察与控制，而不只展示几条历史摘要或最终任务结果。

- 基准：2026-10-25 本机实际运行的 **OMP 18.12.6**，以及同版本 `pi-coding-agent`、`pi-tui` 发布源码。
- 本文只说明原型需要表达的功能、对象、状态和操作边界；除用户指定的历史树 bottom sheet 与独立 Subagent 页面外，不规定布局、控件摆放、配色或动效。
- “原生已有”表示该版本 TUI 或底层确有此能力，**不表示 WawLink 已实现，也不表示标准 RPC 已全部开放**。
- 除明确描述的原生现状外，下文“应／需／必须”均为 **[INFERENCE] 原型设计要求**。GUI 补充能力会单独注明，不冒充 TUI 原样行为。
- 继续遵守项目既有中文界面与交互约束；不修改已有 HTML 原型。

## 2. 先区分对象与原生入口

| 对象／入口 | 实际含义与原型边界 |
| --- | --- |
| 当前会话历史树 `/tree` | 同一个 Session 内，按节点父子关系保存的多条对话路径；可以查看非当前分支并改变当前续写位置，不是主页历史会话列表 |
| 默认双击 Esc；`/branch`、`/rewind` | 打开 transcript Rewind：选择消息、浏览相邻分支并回退；默认不是 `/tree`。原生可将双 Esc 配置为 Rewind、历史树或无动作 |
| 创建独立会话 `/fork` | 创建新 Session 文件／ID，保留来源关系；与同一 Session 内移动当前分支不同 |
| 消息复制 `/copy` | 在当前路径选择某个 turn 或其内部内容复制；不是导出全部分支，也不是复制所有子代理 |
| 运行时 Agent Hub | 双击左箭头、显式 Hub 入口对应的运行实例与活动总览；原生顶层为 Agents／Activity，Agents 可按平铺或父子关系浏览 |
| Subagent 独立查看 | 打开某次真实子代理运行的会话及状态；不是只打开该 Agent 的定义、提示词或最终摘要 |
| `/agents` | Agent 定义与默认设置管理，继续归属已有 Agent 设置；不能用它替代运行时 Hub |
| Todo 子任务、后台 Job、Advisor | Todo 是计划分组；Job 是后台执行记录；Advisor 是被动评审记录。三者均不能直接等同普通 Subagent |

原生双 Esc 还受空闲状态、输入草稿、运行中中断等条件影响；双左箭头在主会话打开 Hub，在已聚焦子代理时实际返回 Main。移动端应有不依赖物理键盘的等价入口，不要求照搬按键手势。[入口依据][A-input] [命令依据][A-session-commands]

## 3. 历史树 bottom sheet

### 3.1 历史结构与节点信息

- 浏览当前 Session 的**全部分支关系**，而非只把当前聊天倒序列出；区分根、父节点、分叉、兄弟路径、当前续写叶子及其祖先路径。
- 初次打开能定位当前续写位置；支持前后消息、前后用户轮次、分支间跳转，以及返回当前路径，长历史不能只显示末尾几条。
- 每个可见节点能够识别所属角色／类型、内容摘要与标签；详情可定位稳定节点身份、时间和所属会话，不以截断文本充当身份。
- 覆盖用户、Assistant、工具结果、Bash／Python 执行、自定义消息，以及压缩摘要、分支摘要、模型／思考／服务等级变化、模式变化、标题／标签和上下文重置边界等原生日志类型。
- 区分“真实原生历史节点”与应用展示出来的 Todo、思考、工具分组。一个原生消息可投影为多个 UI 节点，不能因此产生重复历史或伪造分叉。
- 已压缩内容、旧分支和当前模型上下文是不同范围；能看见旧记录，不意味着下一轮仍会完整发送给模型。

### 3.2 检索与可见范围

- 提供原生五种可见范围的等价能力：默认、隐藏工具结果、仅用户请求、仅已标记节点、全部可见类型；切换范围不改变真实历史与当前叶子。
- 支持按内容摘要、角色／类型和标签检索；搜索无结果、清除搜索、返回原定位均有明确状态。
- 需要如实表达原生检索边界：该版本树搜索对普通消息正文只索引前 **200 字符**，不是全文搜索；“全部”也会过滤部分无可见正文的纯工具调用 Assistant 节点。
- 原型若增加全文检索或手动折叠子树，应标为 GUI 补充；不能把原生的线性链压平、工具内容展开误称为已有子树折叠功能。

### 3.3 节点查看、标记与交叉定位

- 在不改变当前续写位置的情况下查看选中节点；长正文可完整阅读，代码、链接、工具输入输出、思考及富媒体沿用已有查看能力。
- 区分节点预览、可见消息正文与原始日志详情。原生树预览并不完整包含思考、图片或所有非消息节点数据，不能将它包装成“所有原始内容”。
- 新增、修改和清除节点标签；标签可持久保存，并用于搜索及“仅已标记”筛选。节点标签不是主页会话置顶。
- 历史节点与聊天正文之间可相互定位；返回后保留原聊天滚动、展开状态、草稿和树内定位。
- **[INFERENCE] GUI 补充**：在历史消息与 Subagent 派发记录、Hub 运行实例之间建立可追溯入口；消息路径树与 Agent 父子树仍是两种不同结构，不能混成一棵没有类型区分的树。

依据：[树选择器][T-tree]、[Session 树与标签][A-manager]、[节点选择与导航][A-selector]。

## 4. 回退、切换分支与创建独立分支

### 4.1 同一 Session 内回退／切换

- 从当前路径的 transcript 选择回退点，包含用户与 Assistant 等可见 turn，而非只列用户问题；支持查看分叉处的兄弟延续。
- 回退选择器还需覆盖较早记录加载、当前路径筛选、逐条／逐轮定位、查看工具内容，以及取消而不改变会话。
- 选择用户请求时，原生回到该请求之前，并恢复原始输入及图片，供用户修改后重新发送；用户调用的 Skill／协作请求应恢复用户原稿，而不是展开后的内部提示词。
- 选择其他可导航节点按原生类型语义移动续写位置；不能把所有目标一律做成“保留到这一行”。
- 旧路径保留为历史分支，不以清空后续消息来模拟回退；用户能再次找到原路径。
- 区分浏览位置、准备操作的选中项与已经生效的续写位置。当前叶子的无操作状态也应明确，历史 Ask 重答除外。
- 回退后同步真实上下文及 Todo 状态；不能保留新分支正文却误用旧分支的活动任务。
- 有未发送草稿时明确恢复旧输入的影响：原生 Rewind 可以替换草稿，`/tree` 仅在编辑器为空时填回；GUI 不得静默丢失用户现有草稿。

### 4.2 离开当前路径时的摘要

- 覆盖“不生成摘要”“生成分支摘要”“带自定义重点生成摘要”三种能力；Rewind 本身是不摘要回退。
- 分支摘要用于把离开路径的信息带到目标位置，不是改写原消息；应能在后续历史中识别摘要节点。
- 原生自动询问分支摘要默认关闭，但允许显式“摘要并切换”；不能强制每次导航都消耗模型请求。
- 摘要生成有等待、取消、失败、认证／模型不可用及原生扩展取消导航的状态；取消不被当成成功切换。

### 4.3 重新回答历史 Ask

- 对历史 `ask` 工具结果提供重新回答入口，恢复原问题、选项、多选、自定义输入和预览；保留按问题 ID 的完整问卷语义。
- 先查看／填写答案，再一次提交；取消不提交，也不覆盖旧答案。
- 新答案形成兄弟历史结果，原答案保留；当前叶子上的 Ask 也允许重答。
- 原生交互 `/tree` 提交重答后会继续运行 Agent，**不是单纯修改一条历史文字**；原型必须让用户理解这一执行影响。
- 问题原文无法恢复时，不伪造重答表单；普通导航与可重新回答应区分。

### 4.4 创建独立 Session

- 保留“从当前会话创建独立副本”，并覆盖从指定历史节点创建独立会话的能力；新会话有独立身份，可追溯并返回来源。
- 按原生边界包含目标历史与附件／产物；截在工具调用批次中时不能制造缺失已有工具结果的半截上下文。
- 表达运行中不可安全分叉、尚未持久化、原生取消和创建失败；不能展示一个没有真实历史的空会话却提示成功。
- TUI 的另开终端 pane／window 是独立打开副本的终端表达，不要求 GUI 复制终端分屏，但应保留“原会话仍在，副本独立继续”的能力。

**所有上述历史回退、树切换和 Session fork 都不是工作区文件回滚。** 已执行的写文件、Shell 副作用不会自动撤销；不得标成“恢复项目到该时刻”。同样，不存在可凭空承诺的通用 `/undo` 或“合并任意两条对话分支”。另需注意，标准 RPC 的 `branch(entryId)` 是创建新 Session 的旧语义，不能直接当成当前 TUI `/branch` 的原地 Rewind。[导航依据][A-selector] [分支实现][A-agent-session] [回退浏览器][T-rewind]

## 5. 消息与内容复制

### 5.1 对应 `/copy` 的选择能力

- 从当前分支选择具体 turn 复制，支持加载更早消息；不能只提供“复制最后一次回复”。
- 查看 turn 内部可单独复制的内容：围栏代码、引用、HTTP(S) 链接、Bash／Python 命令与输出、工具结果等。
- 覆盖用户原文、Assistant 可见正文、自定义消息、摘要和工具文本；用户 Skill／协作请求复制原始输入，不复制展开的内部提示词。
- 保留“完整 turn”与“内部块”的区别。原生 Assistant turn 有正文时，整项复制优先取正文，**不自动拼入所有工具输出**；思考与图片也不属于 `/copy` 整项正文的复制结果。
- 代码复制原始代码体，不加代码围栏；引用去除引用标记；链接复制完整目标地址，而不是显示标题或折行文字；复制结果为纯文本，不声称是富文本或截图。
- 预览截断不能截断真正复制内容；内容为空、当前无可复制消息、复制失败与复制成功必须可区分，取消不提示成功。

### 5.2 原生快捷取值与现有复制

| 能力 | 应保留的语义 |
| --- | --- |
| `/copy code` | 最近 Assistant 消息中的最后一个围栏代码块，不是全会话任意代码 |
| `/copy cmd`、`/copy command` | 最近 Assistant 的 Bash／Eval 工具调用命令／代码，不是任意手工终端历史 |
| `/copy link`、`/copy url` | 最近 Assistant 中最后一个可解析 HTTP(S) 链接 |
| `/open`、`/open link`、`/open url` | 打开该最近链接；没有链接时显示真实空态 |
| 已有工具、代码与思考查看器 | 保留原文选择、代码复制、工具内容复制；与消息选择复制并存，不相互替代 |
| **[INFERENCE] GUI 补充：Subagent 消息复制** | 独立子代理页也能复制正在查看的消息／内容；对象必须是该子会话，不能误取主会话最后回复 |

原生 `/copy` 没有确认任意起止范围、多消息多选或多种导出格式选择；若原型增加，需作为额外 GUI 能力说明。图片原文件保存继续走已有图像能力，不冒充 `/copy` 文本复制。HTTP(S) 链接打开与容器文件路径操作也须分流，不能把模型给出的 `file://` 直接交给宿主。[复制选择器][T-copy] [内容取值规则][T-copy-targets] [复制命令][A-collaboration]

## 6. 运行时 Agent Hub

### 6.1 对象范围与身份

- 以**当前主 Session**及其真实子代理为范围，包括递归下级、已结束但仍保留的实例，以及可发现的持久历史；不是跨所有磁盘 Session 的全局 Agent 目录。
- 平铺浏览与按父子关系浏览；识别 Agent ID、显示名、父级、下级、任务内容及当前活动；搜索 ID／名称时仍能理解所属关系。
- 普通 Subagent 与只读 Advisor 必须区分。普通 Agent 的只读工作权限不等于 Advisor 的不可交互身份。
- Main 是明确可返回的主会话；原生 Hub roster 不包含 Main 行，GUI 的 Main 导航属于补充表达，不伪造原生运行实例。
- 同名 Agent 的不同运行实例，以及不同主会话内相同局部 ID，不能混淆；单凭名称不能作为打开、发消息或停止操作的目标。

### 6.2 状态、进度与详情

- 覆盖原生生命周期：`running`（运行）、`idle`（存活但空闲）、`parked`（已停放、无存活会话）、`aborted`（已终止）。
- 任务执行结果另分完成、失败、取消、合并失败等；**任务完成不等于一定已 parked，Hub 也没有独立的 completed 生命周期值**。
- 显示当前工具／意图、任务与错误／终止原因、请求重试进度，以及模型、模型角色、故障转移信息与思考档位。
- 查看运行时间、请求数、工具数、输入／输出／缓存 token、费用和上下文占用；缺失测量与真实 0 值分开，统计范围及已测量覆盖范围可辨认。
- 查看注册／运行时间信息、未读通信、近期活动，以及任务输出、Patch、嵌套 Patch 等真实产物。
- 共享工作区下无法准确归属的每代理改动量不能编造；只读、共享目录、隔离目录和实际产物属于不同事实。
- 数据更新保留当前选中实例和阅读位置，避免心跳刷新导致操作对象跳变；搜索后的计数与子树范围不能冒充未筛选的总计。

### 6.3 活动记录

- 浏览响应、工具、通信与生命周期活动，并可切换全部、错误、响应、工具等原生分类。
- 可查看全部 Agent、选中 Agent 或其子树的活动，支持文本搜索、跟随最新与暂停跟随。
- 活动可追溯到所属 Agent 和具体 transcript 节点；从错误或输出进入独立 Subagent 页时定位相应消息，而非只打开会话顶部。
- 保留最近活动与较完整活动查询的区别；原生近期预览是最近 20 行、活动查询上限为 2000 行，不应声称无限历史已全部载入。

### 6.4 生命周期操作

| 对象状态／类型 | 原型需要表达的操作与条件 |
| --- | --- |
| 运行中的普通 Subagent | 打开真实会话、发送定向消息／干预，以及终止；可用性取决于所属运行时与控制权限 |
| 存活但 idle 的普通 Subagent | 继续查看与对话；不能把它当成已被删除，也不能因“任务已完成”就强制只读 |
| parked／aborted | 历史仍可读；具备真实恢复条件时可 revive，再继续对话。打开／发送也可能触发原生恢复，不得无提示地把高风险操作当成纯浏览 |
| Advisor | 观察 transcript、活动及已有用量；不可发送、revive 或 kill |
| 无法恢复的历史 | 保留查看与复制；显示具体不可恢复原因，不假装启动成功 |

Kill 保留终止标记与 transcript，不等于删除历史或撤销代码；Revive 不是从头重跑任务，也不是再次合并产物。仅浏览历史应有不触发运行的路径。原生的自动重试、隔离任务交付，不构成通用“手动重试／手动合并任意 Subagent”按钮的依据。[Hub 源码][T-hub] [生命周期][A-lifecycle] [历史发现][A-persisted]

## 7. 独立 Subagent 页面

### 7.1 页面身份与导航

- 持续明确“当前是 Subagent”，可识别显示名、稳定运行身份、所属主会话、直接父级与递归层级；Advisor 及只读历史有独立身份说明。
- 显示实际容器、工作区与当前模型，不把主会话配置误显示成子代理的有效状态。
- 从 Hub、活动、聊天中的派发记录和相关历史进入；支持返回来源、进入父级、回 Main 和继续浏览下级 Agent。
- 主会话与每个子会话独立保存阅读位置、展开状态与草稿；返回不停止后台任务，不将子代理草稿带进 Main。

### 7.2 完整会话查看

- 展示真实用户输入、Assistant 正文、思考、工具调用与结果、后续追问和错误；不是只有 task result、日志尾或终态摘要。
- 有存活会话时展示实时增量内容，进入前已经发生的内容与进入后的事件连续衔接，不重复、不缺失；阅读旧内容时不强制追尾。
- 可离线查看已持久化记录；正在写入、文件截断／轮转、记录缺失、尚无 Session 文件与重新读取失败均有明确状态。
- 复用现有 Markdown、代码高亮／全屏原文、图像、SVG／Mermaid、文件路径和工具详情查看；容器路径仍按真实子会话上下文解析。
- 展示原始任务要求、当前进度、最终结果、错误原因和可用产物，并能返回父会话中的对应派发／结果位置。
- 区分实时 focus 与文件 transcript viewer：原生前者订阅存活 Session，后者按约 250 ms 增量读取已落盘内容；文件查看器不能承诺展示所有尚未落盘的流式 token。

### 7.3 对当前子代理的操作

- 支持向可交互的子代理发送文字与图片；区分运行中干预 `steer`、后续消息 `follow-up` 和空闲时继续对话，消息落在子会话而非父会话。
- 表达消息提交中、已接收、等待处理、失败与草稿保留；接收回执不表示子任务已经完成。
- 对存活子会话提供临时模型与思考档位切换；依据该模型真实能力及可用认证，不修改持久 Agent 定义／角色绑定，也不误切 Main。
- 将返回、停止当前可中断执行、Kill、Revive 分开；返回页面不能隐式 Kill。原生 focused view 的 Esc 是清草稿／回 Main，不是停止子代理。
- 覆盖原生 focused view 的 `/btw`、模型／思考、当前子会话 HTML 导出、只读后台 Job 与适用用量查看；账号级用量不能误称该子代理独享用量。
- 不支持的 Slash、Shell／Python 输入或其他主会话操作，明确要求返回 Main；不能悄悄转交父会话或当普通提示词执行。
- 若原生请求审批／Ask，展示真实请求所属对象并路由到正确会话；历史只读记录只能查看原决策，不能拿主会话控制器代答。
- **[INFERENCE] GUI 补充**：提供子消息复制、消息全文查看和本页内容定位；若进一步开放子会话历史树操作，必须有真实子会话导航能力。该版 focused Slash 白名单并不包含 `/tree`、`/copy`，不能靠复用 Main 命令冒充。

### 7.4 恢复与只读边界

- App／原生进程重启后能重新发现当前主 Session 的持久子代理及父子关系，区分普通 parked 历史、终止历史和 Advisor。
- 可读历史不等于可继续执行。冷恢复依赖持久会话契约、真实对话内容、仍存在的工作区及提供服务的宿主运行时；缺失／旧格式时保留只读查看。
- 原生明确不允许隔离运行在冷恢复时脱离隔离继续执行；不能因为残留目录还在就显示“可恢复”。
- 主会话结束、容器停止、控制进程退出、实例所有权不符时，控制入口应反映真实不可用状态；不会因只读历史尚可查看就假装能发送或切模型。

依据：[聚焦控制][A-focus]、[输入与命令边界][A-input]、[完整记录查看器][T-transcript]、[冷恢复条件][A-revive]。

## 8. 可纳入历史／Hub 的其他相关能力

这些是相关能力入口，不要求全部变成独立页面，也不要求将现有主页功能强行搬走。

| 能力组 | 原型应覆盖的功能与边界 |
| --- | --- |
| 会话查找与继续 | 当前工作区／全部工作区会话、搜索与预览、当前会话识别、来源关系、切换继续；保留已有标题、置顶、归档与删除。列表读取元数据，不批量启动历史会话 |
| 历史来源 | 对应 `/resume` 的原生会话选择，以及原生支持的 Claude／Codex 历史导入；导入后形成真实 OMP 会话，不把其他格式直接当作当前可操作 Session |
| 中断恢复 | 区分普通 resume 与 `--restore`：后者还尝试继续中断轮次和当时的子代理；依真实运行状态与恢复条件展示，不是只恢复聊天文字 |
| Session／上下文详情 | 会话 ID、文件／内存状态、容器与工作区、模型及来源、消息／工具数量、token／缓存／费用、上下文占用；可查看运行版本与可得的连接诊断，不暴露凭据 |
| 上下文维护 | `/compact` 的原生模式与自定义重点、`/shake` 的重内容／图片／思考移除、`/handoff` 生成交接并原地压缩；区分历史正文与有效上下文，显示进度、取消与真实结果 |
| 重置与删除 | `/fresh` 只重置 Provider 流状态；`/clear` 清当前上下文但保留 Session 与历史；新建 Session、归档和删除是其他操作，不能统一叫“清空历史” |
| HTML 导出 | `/export` 导出所查看会话及其子代理；选择位置、保存结果与失败可见。子代理 focus 下应导出子会话，不误导出 Main |
| 文本与诊断包 | `/dump` 的全文与请求 JSON 路径；`/dump all` 的主会话、子代理与请求资料归档；`/dump anon` 的匿名化归档。与局部 `/copy` 分开，原始上下文可能含敏感信息，不默认上传或假称完全脱敏 |
| 分享与追踪 | 原生 `/share` 的加密快照链接、取消／失败／体积裁剪状态，以及 `/trace` 的当前 Session 追踪入口；外发前明确范围，不自动分享全部容器数据 |
| 后台 Job 查看 | 当前 Agent 及下级的运行／最近 Job 树、命令、工作目录、PID／退出码、实时尾输出、最终输出与完整产物；Job 与 Agent 非一一对应，运行中的 Agent 也可能没有 Job |
| Job 控制权限 | 原生主会话只能取消自己可控制的 Job；focused 子代理的 Job 页只读。看得见下级 Job 不等于有跨所有者取消权限 |
| Agent 定义入口 | 从运行实例定位已有 Agent 设置／来源定义；区分临时运行控制与影响未来派发的持久配置，不复制另一套设置页 |

后台 Jobs 与插件／扩展各有原生独立界面；可从 Hub 提供相关入口，但不能写成原生 Hub 已有 Jobs／Extensions 顶层页签。移动工作区／工作树、容器切换继续沿用已有真实操作边界，不与“历史分支切换”混为一谈。[会话选择器][T-sessions] [相关命令][A-commands] [后台 Job][T-jobs] [生命周期命令][A-lifecycle-commands]

## 9. WawLink 现状与必须补足的能力

以下为代码核对，不是本次新增实现报告。

| 现有基础 | 原型中如何复用／仍缺什么 |
| --- | --- |
| 主页历史会话、按需恢复、创建副本、标题／置顶／归档／删除 | 保留；当前副本是会话级历史复制，不等于从选定消息原地回退或完整历史树导航 |
| 主聊天的 Todo、思考、工具、审批、Ask、用量与富媒体 | 复用渲染及操作语义；Todo 子任务分组不是 Subagent，不能据此宣称已有子代理页面 |
| 工具复制、代码原文选择与全文查看 | 保留；仍需消息级 `/copy` 等价选择和子会话目标隔离 |
| 原生 entries、消息重放与压缩边界处理 | 可作为历史基础；当前协议/UI 未提供树浏览、节点标签、原地导航、历史 Ask 重答等完整能力 |
| Agent 设置 | 管理定义、顾问、角色与配置，不是运行实例 Hub |
| 当前主会话模型／思考／发送／停止控制 | 不能直接拿来控制任意子代理；需要真实的子会话身份、流式内容、状态和控制能力 |

标准 RPC 与 TUI 的差距必须在能力清单中保留，不能因现成 API 较少而将原型缩成只读摘要：

- 原生已提供树／entries 读取及 fork 等；但没有正式的原地 `navigate_tree`、节点标签写入、历史 Ask 重答或复制选择器命令。
- 子代理已有订阅、运行集合、增量记录读取、`steer_subagent` 与 `cancel_subagent`；后两项仅能控制当前父会话所属、文件身份匹配且仍在运行的真实子代理，不覆盖全部 idle／parked／历史实例。
- 标准 RPC 的运行集合不是 Hub 完整 roster，保留的已结束 transcript 引用也不是无限历史目录；不能只据它绘制“全部历史 Subagent”。
- TUI 的完整 focus、Revive、重启后历史发现、子会话模型／思考操作仍需真实接入。切换主 RPC 的 Session，或把无目标 ID 的主会话模型命令发出去，都不等于操作原子代理。
- TUI-only Slash 在 RPC 中可能落入普通模型提示词路径；**不能通过发送 `/tree`、`/copy` 来假装已经打开 GUI 界面**。

现有依据：[领域与会话控制][W-protocol]、[主页会话协调][W-coordinator]、[消息与工具展示][W-feed]、[OMP 驱动][W-driver]；原生接入边界：[RPC 协议][A-rpc-types]、[子代理控制边界][A-rpc-subagents]。

## 10. 原型必须包含的状态与行为边界

- **空与缺失**：新会话无历史、无 Subagent／活动／Job、无复制目标、搜索无结果、记录／产物缺失、尚无持久化文件。
- **加载与实时**：首次读取、较早内容加载、订阅连接／断开、局部记录未落盘、文件重读、摘要／恢复／导出／分享处理中；不使用演示加载或假状态。
- **失败与取消**：解析失败、文件不可读、模型／认证不可用、容器停止、原生进程退出、恢复条件不满足、控制对象已结束／不属当前会话、复制／保存失败；保留可定位原因、用户草稿与可重试入口。
- **操作进行中**：消息已接收但未处理、Kill／Revive 尚未确认、重复点击、异步响应到达时用户已切换目标；不能把结果作用于新的选中实例。
- **副作用**：纯查看不改变续写位置、不启动模型、不停止任务；恢复、重答继续执行、Kill、删除、上下文清除与外发有明确影响范围及适用确认。
- **返回与隔离**：关闭 bottom sheet、离开 Hub、返回 Main／父级、切换 Session 时保留各自状态；容器、主会话、Subagent 与不同分支不能串用草稿、请求、控制目标或滚动锚点。
- **安全与真实性**：代码／图形／Markdown 继续使用现有安全渲染；历史文本不执行脚本；文件与产物按当前容器解析；错误与导出提示不泄露认证，匿名化与扫描结果不冒充绝对安全保证。

## 11. 核对依据与验证范围

已执行：`omp --version`、`omp --help`；下载并核对 18.12.6 主包／TUI 归档 SHA-512。主包与项目安装脚本固定值一致，TUI 与 npm `dist.integrity` 一致。

已用临时 HOME、XDG、配置目录、Agent 目录与工作区，在**真实已安装 OMP TUI** 中加载自建双分支历史夹具，观察到：

- `/tree` 展示两个兄弟分支与当前路径；搜索并执行不生成摘要的分支切换后，正文切换到目标路径，再次打开树仍保留原分支。
- `/copy` 打开真实消息选择器；默认双 Esc 打开 Rewind 选择器。
- 显式 Alt+A 打开空 Agent Hub，展示 Agents／Activity、Flat／By parent 及生命周期操作提示。

临时模型仅提供启动所需元数据，端点固定为不可用 loopback 地址；未发送模型提示词，未读取真实用户凭据，未创建真实 Subagent，未启动 Android 模拟器。**剪贴板实际落地、Ask 重答执行、摘要生成、活跃子代理通信／控制／恢复、导出与线上分享没有在本次实测；相关结论仅由固定版本源码确认。** 本次不修改应用代码，不做 UI／设备端实现验收。

关键源码定位：

| 主题 | 固定版本定位 |
| --- | --- |
| 双 Esc、双左箭头、focused 操作范围 | [input-controller.ts][A-input]：167–188、574–618、713–739、1435–1494 |
| Rewind／复制／树／Ask 重答 | [selector-controller.ts][A-selector]：1312–1751 |
| 树筛选、搜索、预览、标签 | [tree-selector.ts][T-tree]：482–795、1268–1450 |
| 复制规则与块取值 | [copy-selector.ts][T-copy]：350–543、1025–1175；[copy-targets.ts][T-copy-targets] |
| Hub 身份、活动及生命周期操作 | [agent-hub.ts][T-hub]：1535–1684、2259–2578 |
| 实时聚焦与文件查看 | [session-focus-controller.ts][A-focus]；[agent-transcript-viewer.ts][T-transcript] |
| 持久历史与恢复限制 | [persisted-agents.ts][A-persisted]；[persisted-revive.ts][A-revive]：76–164 |
| RPC 子代理控制所有权 | [rpc-subagents.ts][A-rpc-subagents]：287–312；[rpc-types.ts][A-rpc-types] |

[A-input]: https://unpkg.com/@oh-my-pi/pi-coding-agent@18.12.6/src/modes/controllers/input-controller.ts
[A-session-commands]: https://unpkg.com/@oh-my-pi/pi-coding-agent@18.12.6/src/slash-commands/builtin-session.ts
[A-manager]: https://unpkg.com/@oh-my-pi/pi-coding-agent@18.12.6/src/session/session-manager.ts
[A-selector]: https://unpkg.com/@oh-my-pi/pi-coding-agent@18.12.6/src/modes/controllers/selector-controller.ts
[A-agent-session]: https://unpkg.com/@oh-my-pi/pi-coding-agent@18.12.6/src/session/agent-session.ts
[A-collaboration]: https://unpkg.com/@oh-my-pi/pi-coding-agent@18.12.6/src/slash-commands/builtin-collaboration.ts
[A-lifecycle]: https://unpkg.com/@oh-my-pi/pi-coding-agent@18.12.6/src/registry/agent-lifecycle.ts
[A-persisted]: https://unpkg.com/@oh-my-pi/pi-coding-agent@18.12.6/src/registry/persisted-agents.ts
[A-focus]: https://unpkg.com/@oh-my-pi/pi-coding-agent@18.12.6/src/modes/controllers/session-focus-controller.ts
[A-revive]: https://unpkg.com/@oh-my-pi/pi-coding-agent@18.12.6/src/task/persisted-revive.ts
[A-commands]: https://unpkg.com/@oh-my-pi/pi-coding-agent@18.12.6/src/modes/controllers/command-controller.ts
[A-lifecycle-commands]: https://unpkg.com/@oh-my-pi/pi-coding-agent@18.12.6/src/slash-commands/builtin-lifecycle.ts
[A-rpc-types]: https://unpkg.com/@oh-my-pi/pi-coding-agent@18.12.6/src/modes/rpc/rpc-types.ts
[A-rpc-subagents]: https://unpkg.com/@oh-my-pi/pi-coding-agent@18.12.6/src/modes/rpc/rpc-subagents.ts
[T-tree]: https://unpkg.com/@oh-my-pi/pi-tui@18.12.6/src/overlays/tree-selector.ts
[T-rewind]: https://unpkg.com/@oh-my-pi/pi-tui@18.12.6/src/overlays/rewind-selector.ts
[T-copy]: https://unpkg.com/@oh-my-pi/pi-tui@18.12.6/src/overlays/copy-selector.ts
[T-copy-targets]: https://unpkg.com/@oh-my-pi/pi-tui@18.12.6/src/overlays/copy-targets.ts
[T-hub]: https://unpkg.com/@oh-my-pi/pi-tui@18.12.6/src/overlays/agent-hub.ts
[T-transcript]: https://unpkg.com/@oh-my-pi/pi-tui@18.12.6/src/overlays/agent-transcript-viewer.ts
[T-sessions]: https://unpkg.com/@oh-my-pi/pi-tui@18.12.6/src/overlays/session-selector.ts
[T-jobs]: https://unpkg.com/@oh-my-pi/pi-tui@18.12.6/src/overlays/jobs-panel.ts
[W-protocol]: ../agent-protocol/src/commonMain/kotlin/dev/wawlink/protocol/AgentProtocol.kt
[W-coordinator]: ../app-desktop/src/sharedMain/kotlin/dev/wawlink/app/AppCoordinator.kt
[W-feed]: ../agent-ui/src/commonMain/kotlin/dev/wawlink/ui/FeedNodes.kt
[W-driver]: ../agent-driver-omp/src/jvmSharedMain/kotlin/dev/wawlink/driver/OmpProcessPool.kt
