package dev.wawlink.runtime

/** Native OMP Skill; intentionally installed only when absent, never replacing user edits. */
internal val FEATURE_SKILL = """
---
name: wawlink-functions
description: 仅当用户明确要求新增或创建 WawLink 功能页卡片时使用。普通网页开发、插件或技能安装、脚本编写、修改其他内容及笼统的功能请求均不触发。
---

# WawLink 容器功能卡片

## 严格触发条件

只有用户明确要求“添加 WawLink 功能页卡片”或“创建 WawLink Functions card”时，才使用本技能。不因普通网页、脚本、插件、Skill 安装、开发服务、修改现有内容或笼统的“新增功能”自动创建卡片。不扫描后擅自启动服务，不安装示例卡片，不写入任何演示数据、账号或凭据。

## 原生发现契约

操作当前容器中实际配置用户的 HOME（先读取环境中的 HOME，不假设 /root；不是 Android/桌面宿主 HOME）。每个卡片使用独立目录：

    ~/.wawlink/features/<id>/card.json
    ~/.wawlink/features/<id>/start.sh
    ~/.wawlink/features/<id>/card.html  # 可选，功能页中的自定义卡片
    ~/.wawlink/features/<id>/stop.sh    # 可选

id 只含字母、数字、下划线、短横线，首字符为字母或数字，长度 1–64。card.json 必须为 UTF-8 JSON 对象（不超过 64 KiB），必填 icon、title、description；icon 必须为真实 Google Material Symbols 的小写名称，不使用 emoji、图片或字母代替图标。title 与 description 使用清晰中文。旧清单无需迁移：可选 lifecycle 为 page 或 manual，省略即 page。

可选字段：color 是严格的 #RRGGBB 颜色字符串，未指定时使用应用主题；card 指定功能目录内的相对 HTML 文件路径，不能为绝对路径或含 . / .. 路径段；未指定时自动发现 card.html，文件不存在则使用原生卡片。cardHeight 为整数 dp，默认 120，原生限制到 80–480。showStatus 与 showMemory 为 JSON 布尔值，默认 false，分别在卡片显示真实服务状态、真实监管进程组 RSS。RSS 不可读时显示不可用，不以手机总内存、宿主内存、容器总内存或假数代替。HTML 文件必须是 UTF-8、自包含且不超过 256 KiB；内联 CSS、JS、data URL 资源，不依赖服务已启动或网络 CDN。

不要让目录、清单、HTML、脚本的符号链接逃出该功能目录或当前用户 HOME。启动约定永远是 start.sh，不在清单插入任意 shell 命令、固定端口、host 路径或凭据。原生扫描仅有界读取数据与文件存在性，绝不执行 start.sh/stop.sh、Lua 或卡片 JS；HTML 在卡片显示时才由 Web 渲染器执行，不为显示卡片启动后台服务。原生卡片是默认样式，HTML 卡片仅替换 Functions 页中该功能的卡片区域，不改变容器列表。用户在功能页长按多选、隐藏、排序及覆盖卡片颜色，在应用设置恢复隐藏项；不需要修改 APK、导航、rootfs 或重建容器。

原生卡片顶部为图标和标题同一行、垂直居中，描述文字在下方；不默认附加启动、停止或其他操作按钮。卡片需要哪些按钮、按钮名称及具体功能，应在该卡片设计时按用户需求明确实现；HTML 按钮可调用下述原生动作或 Android LuaJ/系统桥接，不把启停示例当作所有卡片的固定控件。设置卡片底色时，文字和图标默认使用同色系深色，极深底色改用可读的浅色；HTML 可使用 --wawlink-card-color 与 --wawlink-card-foreground，正文及按钮默认继承前景，显式样式仍由卡片作者决定。

## 真实服务及生命周期

start.sh 通过 /bin/sh 运行（写 POSIX shell，或由脚本显式 exec 需要的已安装解释器）。在卡片目录作为工作目录。必须以前台进程运行真实服务，最后使用 exec，不使用 nohup、后台守护、systemd、双重 fork 或脱离进程组。原生使用 setsid 监管整个进程组，容器需要 util-linux 的 setsid。缺少真实依赖时明确报告，按用户授权安装；不得用 demo、mock、无操作或假数据替代。

只监听 WAWLINK_HOST（固定为 127.0.0.1）与 WAWLINK_PORT（每次启动随机分配的空闲端口）；不要监听 0.0.0.0，不硬编码端口。WAWLINK_TOKEN 是每次启动的 256 位随机令牌。HTTP 请求携带 Authorization: Bearer <token> 与 X-WawLink-Token，服务必须验证令牌后才能提供数据或执行操作。令牌只能从环境读取，不打印、不写入仓库或 URL、不生成永久凭据。其余环境为 WAWLINK_FEATURE_ID、WAWLINK_FEATURE_DIR、WAWLINK_THEME_CSS。

服务在 30 秒内对根路径 / 的带令牌 HTTP GET 返回 2xx/3xx；原生检测实际 HTTP 就绪，不以进程存在、固定 sleep 或伪造成功代替。错误和依赖问题输出 stderr，保留真实非零退出码。start.sh 在就绪后仍必须保持前台运行。启动失败在原生页面展示具体原因和真实输出。资源加载使用相对或同源 URL；允许同源 WebSocket。不要依赖任意外站 CDN、跨域跳转或宿主文件访问。普通 HTTP 响应/上传目前上限 32 MiB，功能应使用分页、分块应用协议而不是巨大单次响应。

默认 lifecycle=page：离开功能页面即执行可选 stop.sh 并停止整个服务进程组。只有用户需要跨页面持久运行时才设 lifecycle=manual；离页继续运行，用户在页面或卡片明确停止。两者在容器停止或应用关闭时都回收；不要声称关闭手机进程后还能永久运行。stop.sh 是可选、幂等的收尾，不应再启动服务，读取相同功能/端口/令牌环境；尽快完成。即使 stop.sh 失败，原生仍终止监管进程，并显示停止错误。不得绕开原生监管或重新启动已停止的容器。

## HTML 卡片与原生操作

HTML 卡片与服务的完整页面均可使用 window.wawlink。卡片初始化后读取 wawlink.card（对象字段 id、status、memoryBytes、containerRunning）；监听 window 的 wawlink-card-update 事件并读取 event.detail，以真实状态更新界面。memoryBytes 为整数或 null，status 为小写的 stopped、starting、running、stopping、failed；不要把 null 当作已测得的零。卡片显示不意味着服务已经启动，不能自行伪造 running。

wawlink.action('open') 请求原生打开功能，wawlink.action('start') 请求启动，wawlink.action('stop') 请求停止；仅使用这三个动作，不通过假链接或启动脚本绕过原生生命周期。动作结果以真实状态更新和原生错误为准；不要假设点击即成功。原生设置提供确认卸载：先停止服务，再只删除经过验证的当前 HOME/.wawlink/features/<id>，不跟随链接、不删除宿主挂载或受保护根。

## Android：完整可信 LuaJ / Java 桥

卡片和完整功能页面都有 wawlink.lua(source)，返回 Promise。执行真实 LuaJ JsePlatform.standardGlobals，包含 luajava 的完整 Java 互操作，可调用 Android/Java API；不存在额外 API 白名单、WawLink 审批流程或人为能力限制。仍受 Android 系统权限、应用 UID、系统版本与设备能力限制；需要 Android 运行时权限时遵循系统真实授权，不伪造权限已获准。此桥是可信代码执行，不是沙箱；仅为用户明确需要的真实功能编写代码，不加载不可信 HTML/Lua。

Lua 全局提供 context、activity（没有 Activity 时为 nil）与 host。host.context、host.activity 对应上述对象；host.runOnUiThread(function) 同步切到 Android UI 线程并返回 Lua 结果。host.openUrl(url)、host.openBrowser(url)、host.share(text,title?)、host.launchApp(packageName) 也提供同名 Lua 全局函数；这些 Android 系统操作自动调度至主线程。普通 Lua 运行在桥的串行后台执行器，直接调用需要 UI 线程的 Java API 时使用 runOnUiThread，不在主线程阻塞等待网络或长任务。

JavaScript 的 wawlink.openUrl(url)、wawlink.openBrowser(url)、wawlink.share(text,title?)、wawlink.launchApp(packageName) 同样返回 Promise。openUrl 交给系统 URL 处理程序，openBrowser 打开外部浏览器，share 打开系统分享，launchApp 启动已安装且可启动的包；处理 Promise 拒绝并给出真实中文错误，不声称调用总能成功。这些显式接口可离开功能来源，不依赖 WebView 自动外跳或 intent: 导航。

wawlink.lua 没有返回值时解析为 null；一个返回值为对应 JSON 值，多个返回值为数组。支持 nil、布尔、有限数字、字符串；非空连续整数下标 Lua 表为数组，字符串键表为对象，空表为 {}。Java 基本值、字符串键 Map、Iterable、数组可返回；循环、非有限数字、函数及其他 Java userdata 会拒绝 Promise。操作 Java 对象后返回可 JSON 序列化的结果，不返回 Activity/Context 等对象。真实 Lua/Java 异常传到 Promise 拒绝，必须展示真实错误。

桥只接受当前受信来源的主框架；文档导航或销毁取消该文档的执行器。若系统 WebView 缺少安全文档起始注入或来源限定消息桥能力，原生明确报错，不回退到无来源保护的桥。桌面仍可使用卡片动作和实时数据，但 Android Lua/系统助手会明确拒绝 Promise；不要为桌面伪造 Android 能力。


## Web 来源与系统边界

完整服务页面运行于原生令牌门控的随机 loopback 来源。Android 使用真正 WebView，桌面使用真正 Web 渲染器。普通页面资源、表单、网络、WebSocket 与导航遵循该功能的确切来源和原生 CSP，不依赖宿主文件访问、自动外跳、弹窗或 file:/content:/intent:/javascript: 导航；外部操作使用显式 wawlink 助手或可信 LuaJ API。HTTP/CSP 是 Web 层来源规则，不限制可信 LuaJ 的完整 Java 访问，也不是容器脚本安全沙箱。容器脚本仍具有配置用户权限，Android 调用仍受操作系统边界约束；不运行不可信脚本。

## 所有 UI 与文案要求（不可简化）

- 遵循 WawLink 现有 Material 3 设计；白色主背景，大字号中文界面，足够大的触控目标（至少 48px），清晰层级、宽松留白、圆角卡片与分组，不做终端风、开发者占位界面或视觉简化。
- 所有标题、按钮、说明、设置、空状态、加载、就绪、错误、确认、删除、停止与重试文案必须是自然、清晰的中文。状态来自真实操作，不展示假记录、示例数据、硬编码数量、虚构健康状态或凭据。
- 图标必须使用 Google Material Symbols，与 WawLink 的 Symbol 语义一致；不使用 emoji、字符画、图标替代品或未经授权的外部字体请求。原生为卡片和完整页面注入 Material Symbols 字体及主题，可使用 material-symbols-rounded 样式；不重复下载外部字体。服务页面也可使用同源 /__wawlink_native/material-symbols.ttf 字体资源。
- 主色必须跟随应用当前主题的 primary、primaryContainer、onPrimary、onPrimaryContainer。原生注入 WAWLINK_THEME_CSS，同时页面的 :root 具有 --md-sys-color-primary、--md-sys-color-primary-container、--md-sys-color-on-primary、--md-sys-color-on-primary-container、--md-sys-color-surface、--md-sys-color-on-surface 等 CSS 变量。以 var(...) 使用这些值，白色背景保持 Material 3 视觉；禁止回退到经典 Material 紫色或自造固定紫色。原生主题变化会更新 CSS。
- 动效必须非线性，采用 Material emphasized 风格 cubic-bezier(.2,0,0,1) 或更精确的 emphasized easing；页面切换、卡片展开、状态变化、按钮反馈应连贯自然，不使用线性滑动、瞬移或持续无意义动画。尊重 prefers-reduced-motion，提供减少动画方案。
- 按照用户明确指定的全部界面、功能、布局、交互与文字逐项完成，不因本技能而缩减；若要求参考 ref-plan/plugin-management.html、skill-management.html 或其他设计，应准确保留对应视觉和交互，不用简化版取代。若请求还涉及插件/技能管理，Functions 入口只管理当前容器用户级对象，聊天会话弹窗入口只管理该会话工作区的项目级对象，不混用作用域。
- 文件、规则、插件、技能和终端属于真实内置能力，不在自定义卡片中伪造它们的安装、启停、重载或会话状态。配置保存不等于当前 agent 已重载；需要重启就明确提示，不虚构 runtime reload。

## 交付

保留已有用户卡片与 Skill 编辑，不覆写无关文件。只为明确请求创建所需功能，不预装样例，不改 rootfs 部署，不创建 credentials。完成后说明真实卡片目录、依赖、启动/停止行为、功能入口和已知真实限制。未获要求不运行自动化测试或模拟器，不声称未经实际验证的成功。
""".trimIndent() + "\n"
