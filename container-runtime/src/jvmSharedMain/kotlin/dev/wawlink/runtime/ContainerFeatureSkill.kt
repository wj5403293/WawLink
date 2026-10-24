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
    ~/.wawlink/features/<id>/stop.sh    # 可选

id 只含字母、数字、下划线、短横线，首字符为字母或数字，长度 1–64。card.json 必须为 UTF-8 JSON 对象（不超过 64 KiB），必填 icon、title、description；icon 必须为真实 Google Material Symbols 的小写名称（例如用户所需语义对应的符号名，不使用 emoji、图片或字母代替图标）。title 与 description 使用清晰中文。可选 lifecycle 为 page 或 manual，省略即 page。不要让目录、清单、脚本的符号链接逃出该功能目录或当前用户 HOME。

清单仅声明图标、标题、描述和生命周期；启动约定永远是 start.sh，不在清单插入任意 shell 命令、固定端口、host 路径或凭据。原生扫描只读取清单和文件存在性，绝不执行 start.sh/stop.sh。发现后由原生 Functions 页和内置的终端、文件、规则、插件、技能卡片一起以双列卡片展示；不需要修改 APK、导航、rootfs 或重建容器。

## 真实服务及生命周期

start.sh 通过 /bin/sh 运行（写 POSIX shell，或由脚本显式 exec 需要的已安装解释器）。在卡片目录作为工作目录。必须以前台进程运行真实服务，最后使用 exec，不使用 nohup、后台守护、systemd、双重 fork 或脱离进程组。原生使用 setsid 监管整个进程组，容器需要 util-linux 的 setsid。缺少真实依赖时明确报告，按用户授权安装；不得用 demo、mock、无操作或假数据替代。

只监听 WAWLINK_HOST（固定为 127.0.0.1）与 WAWLINK_PORT（每次启动随机分配的空闲端口）；不要监听 0.0.0.0，不硬编码端口。WAWLINK_TOKEN 是每次启动的 256 位随机令牌。HTTP 请求携带 Authorization: Bearer <token> 与 X-WawLink-Token，服务必须验证令牌后才能提供数据或执行操作。令牌只能从环境读取，不打印、不写入仓库或 URL、不生成永久凭据。其余环境为 WAWLINK_FEATURE_ID、WAWLINK_FEATURE_DIR、WAWLINK_THEME_CSS。

服务在 30 秒内对根路径 / 的带令牌 HTTP GET 返回 2xx/3xx；原生检测实际 HTTP 就绪，不以进程存在、固定 sleep 或伪造成功代替。错误和依赖问题输出 stderr，保留真实非零退出码。start.sh 在就绪后仍必须保持前台运行。启动失败在原生页面展示具体原因和真实输出。资源加载使用相对或同源 URL；允许同源 WebSocket。不要依赖任意外站 CDN、跨域跳转或宿主文件访问。普通 HTTP 响应/上传目前上限 32 MiB，功能应使用分页、分块应用协议而不是巨大单次响应。

默认 lifecycle=page：离开功能页面即执行可选 stop.sh 并停止整个服务进程组。只有用户需要跨页面持久运行时才设 lifecycle=manual；离页继续运行，用户在页面或卡片明确停止。两者在容器停止或应用关闭时都回收；不要声称关闭手机进程后还能永久运行。stop.sh 是可选、幂等的收尾，不应再启动服务，读取相同功能/端口/令牌环境；尽快完成。即使 stop.sh 失败，原生仍终止监管进程，并显示停止错误。不得绕开原生监管或重新启动已停止的容器。

## 安全边界

页面运行于原生令牌门控的随机 loopback 来源中。Android 使用真正 WebView，桌面使用真正 Web 渲染器；没有宿主文件访问、原生 JS 桥、任意 intent、自动外部浏览器跳转、弹窗、摄像头/麦克风/地理位置授权。不得要求暴露这些权限。所有页面、资源、表单、网络、WebSocket 和跳转必须保持该功能的确切 loopback 来源；file:、content:、intent:、javascript: 导航和外部 origin 一律不依赖、不绕过。同源策略和 CSP 不是容器安全沙箱；容器脚本具有配置用户权限，不运行不可信脚本。明确向用户解释所需依赖、网络与破坏性操作，按其授权执行。

## 所有 UI 与文案要求（不可简化）

- 遵循 WawLink 现有 Material 3 设计；白色主背景，大字号中文界面，足够大的触控目标（至少 48px），清晰层级、宽松留白、圆角卡片与分组，不做终端风、开发者占位界面或视觉简化。
- 所有标题、按钮、说明、设置、空状态、加载、就绪、错误、确认、删除、停止与重试文案必须是自然、清晰的中文。状态来自真实操作，不展示假记录、示例数据、硬编码数量、虚构健康状态或凭据。
- 图标必须使用 Google Material Symbols，与 WawLink 的 Symbol 语义一致；不使用 emoji、字符画、图标替代品或未经授权的外部字体请求。需要的图标字体/资源随功能本地提供，符合其许可。
- 主色必须跟随应用当前主题的 primary、primaryContainer、onPrimary、onPrimaryContainer。原生注入 WAWLINK_THEME_CSS，同时页面的 :root 具有 --md-sys-color-primary、--md-sys-color-primary-container、--md-sys-color-on-primary、--md-sys-color-on-primary-container、--md-sys-color-surface、--md-sys-color-on-surface 等 CSS 变量。以 var(...) 使用这些值，白色背景保持 Material 3 视觉；禁止回退到经典 Material 紫色或自造固定紫色。原生主题变化会更新 CSS。
- 动效必须非线性，采用 Material emphasized 风格 cubic-bezier(.2,0,0,1) 或更精确的 emphasized easing；页面切换、卡片展开、状态变化、按钮反馈应连贯自然，不使用线性滑动、瞬移或持续无意义动画。尊重 prefers-reduced-motion，提供减少动画方案。
- 按照用户明确指定的全部界面、功能、布局、交互与文字逐项完成，不因本技能而缩减；若要求参考 ref-plan/plugin-management.html、skill-management.html 或其他设计，应准确保留对应视觉和交互，不用简化版取代。若请求还涉及插件/技能管理，Functions 入口只管理当前容器用户级对象，聊天会话弹窗入口只管理该会话工作区的项目级对象，不混用作用域。
- 文件、规则、插件、技能和终端属于真实内置能力，不在自定义卡片中伪造它们的安装、启停、重载或会话状态。配置保存不等于当前 agent 已重载；需要重启就明确提示，不虚构 runtime reload。

## 交付

保留已有用户卡片与 Skill 编辑，不覆写无关文件。只为明确请求创建所需功能，不预装样例，不改 rootfs 部署，不创建 credentials。完成后说明真实卡片目录、依赖、启动/停止行为、功能入口和已知真实限制。未获要求不运行自动化测试或模拟器，不声称未经实际验证的成功。
""".trimIndent() + "\n"
