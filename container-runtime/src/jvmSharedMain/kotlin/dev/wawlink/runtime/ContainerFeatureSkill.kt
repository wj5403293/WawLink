package dev.wawlink.runtime

import java.security.MessageDigest
import java.util.Base64

/** Native OMP Skill; installed bundle hashes distinguish app updates from user edits. */
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

id 只含字母、数字、下划线、短横线，首字符为字母或数字，长度 1–64。card.json 必须为 UTF-8 JSON 对象（不超过 64 KiB），必填 icon、title、description；icon 使用真实 Google Material Symbols 小写名称，title 与 description 使用清晰中文。lifecycle 为 page（默认）、manual 或 container；旧清单无需迁移。

可选字段：color 为 #RRGGBB；card 为功能目录内的相对 HTML 文件路径，不能绝对或含 . / ..，缺省自动发现 card.html。cardUrl 用于动态卡片，支持自身服务的 /card 等绝对路径，或 http://127.0.0.1:端口/路径、http://localhost:端口/路径；card 与 cardUrl 不同时声明，cardUrl 优先于自动发现 card.html。自身路径由宿主对接当前真实服务和随机端口，不写死 WAWLINK_PORT 或访问令牌；独立 loopback 地址用于已有服务，不附带本功能令牌，服务由实际所有者负责生命周期。cardHeight 是整数 dp，默认 172，范围 80–480；cardSpan 是 1（默认半宽）或 2（占满两列）。showStatus、showMemory 是布尔值，默认 false，显示真实服务状态、监管进程组 RSS，无法读取时不得伪造。静态 HTML 文件为不超过 256 KiB 的 UTF-8；无需强制把所有 CSS/JS 内联，可以使用外部 HTTPS API/CDN/图片，动态服务可以拆分同源文件、fetch、WebSocket/SSE 和常用框架。发现仍要求 card.json 和真实 start.sh，不放入假启动脚本。

目录、清单、HTML 和脚本不得通过符号链接逃出功能目录或当前 HOME。托管服务的启动入口仍是 start.sh，不在清单插入任意 shell 命令或凭据；它监听宿主提供的动态端口。扫描只读取数据，不执行脚本、Lua 或 JS；只有显式操作或 lifecycle=container 的容器启动策略才运行服务。HTML 在显示时执行，不为显示卡片擅自启动 page/manual 服务。动态卡片等待服务时使用宿主的居中进度或启动入口，不伪造天气/服务器状态。用户可长按多选、隐藏、排序，隐藏不等于停止 container 服务；无需改 APK 或重建容器。

原生卡片默认不附加启动、停止或其他按钮。可选 actions 数组最多三个，超过三个直接拒绝整个清单，不截断；省略或空数组保持无按钮。每个动作必须使用小写字段 id、title、icon、type：id 为 1–64 位小写字母、数字、下划线或短横线，首位为字母或数字，同一卡片不可重复；title 为清晰中文，icon 为真实小写 Material Symbols 名称。type 只能是小写 open（打开服务）、start（启动）、stop（停止）、toggle（按真实状态启停）、logs（查看真实输出）、script（容器脚本）或 lua（Android 原生 LuaJ）。script 动作额外填写 script 相对文件路径，不能绝对、含空路径段或 . / ..；lua 动作额外填写非空内联 lua 源码字符串。不要以按钮标题或 id 推断动作类型，也不要添加用户未要求的动作。

窄卡原生按钮为 1–3 个同宽按钮，不换行；一个或两个显示图标和标题，三个只显示图标但保留可访问性名称；cardSpan=2 的整行卡片按钮显示图标和标题。原生卡片头部按布局使用图标、标题和描述，不要求 HTML 复刻原生布局。设置底色时，文字和图标默认使用同色系深色，极深底色改用可读浅色；HTML 可使用 --wawlink-card-color 与 --wawlink-card-foreground，正文及按钮默认继承前景，显式样式仍由作者决定。

script 动作仅在用户触发时以当前容器配置身份执行声明文件；工作目录是经过当前实际 HOME 校验的原始 HOME/.wawlink/features/<id>，注入 WAWLINK_FEATURE_ID、WAWLINK_FEATURE_DIR、WAWLINK_ACTION_ID。同一动作并发重复点击会拒绝而非排队。脚本不受数据读取命令的 20 秒超时限制；输出、stderr 和非零退出码按真实结果报告。脚本必须留在监管进程组中，不得后台脱离；调用取消、page 生命周期离页、卸载、容器停止和应用关闭都会回收子进程。manual 允许离页持续执行，显式取消或调用方销毁仍会终止，不应依赖页面任务永远存活。lua 动作使用下述相同 Android LuaJ/Java 能力，不在容器中执行、不调用 start.sh，也不伪造桌面支持。

## 真实服务及生命周期

start.sh 通过 /bin/sh 运行（写 POSIX shell，或由脚本显式 exec 需要的已安装解释器）。在卡片目录作为工作目录。必须以前台进程运行真实服务，最后使用 exec，不使用 nohup、后台守护、systemd、双重 fork 或脱离进程组。原生使用 setsid 监管整个进程组，容器需要 util-linux 的 setsid。缺少真实依赖时明确报告，按用户授权安装；不得用 demo、mock、无操作或假数据替代。

只监听 WAWLINK_HOST（固定为 127.0.0.1）与 WAWLINK_PORT（每次启动随机分配的空闲端口）；不要监听 0.0.0.0，不硬编码端口。WAWLINK_TOKEN 是每次启动的 256 位随机令牌。HTTP 请求携带 Authorization: Bearer <token> 与 X-WawLink-Token，服务必须验证令牌后才能提供数据或执行操作。令牌只能从环境读取，不打印、不写入仓库或 URL、不生成永久凭据。其余环境为 WAWLINK_FEATURE_ID、WAWLINK_FEATURE_DIR、WAWLINK_THEME_CSS。

服务在 30 秒内对根路径 / 的带令牌 HTTP GET 返回 2xx/3xx；以真实 HTTP 就绪判断，不用固定 sleep 或假成功。启动后 start.sh 保持前台，错误保留真实 stderr/退出码。页面可以使用相对/同源 JS/CSS、图片、fetch、WebSocket、SSE，以及外部 HTTPS API/CDN、图片和 wss；遵守浏览器 CORS 与服务器实际认证，不绕过 TLS 校验。普通 HTTP 响应/上传上限 32 MiB，长列表分页、流式数据使用真实协议。

page 离开功能页后运行可选 stop.sh 并终止服务组；manual 离页继续，用户明确启停。container 在每次容器从停止变为运行时自动扫描并启动，不需要先进入功能页，离页/隐藏不停止，停止容器或关闭应用时回收。手动停止或失败后不因刷新、改名或切页反复拉起，下次容器启动再自动尝试；在已运行容器中新建卡片可手动启动，或下次容器启动自动运行。stop.sh 可选、幂等、尽快完成；应用正常停止功能时使用同一环境执行，容器已关闭则直接回收，失败也不遗留进程组。不承诺手机杀进程后永久后台运行，不擅自重新启动已停止容器。

创建天气、内存监控等需要随容器运行、无需用户先打开页面的服务型动态卡片时，按用户需求显式写 lifecycle: container；不要漏写后落到默认 page。只在打开功能期间运行的服务才选 page，需要用户独立控制启停才选 manual。外部已有服务的 cardUrl 不会因本功能 lifecycle 自动接管其进程。

## 轻量调试：卡片和完整功能页

容器内可直接运行 wawlink-feature（复用已有 Bun，不安装 Chrome、Chromium、Playwright 或 ADB）。若当前旧 shell 尚未更新 PATH，使用 "${'$'}HOME/.wawlink/bin/wawlink-feature"。命令对接 WawLink 应用已经挂载的真实 WebView；不是另一份浏览器，也不会为调试启动容器、启动服务或擅自打开页面。

    wawlink-feature list
    wawlink-feature inspect weather --surface card
    wawlink-feature logs weather --surface card
    wawlink-feature eval weather 'getComputedStyle(document.body).height' --surface card
    wawlink-feature screenshot weather --surface card --output /tmp/weather-card.png
    wawlink-feature reload weather --surface card
    wawlink-feature inspect weather --surface page
    wawlink-feature eval weather 'document.querySelector("button").click()' --surface page
    wawlink-feature screenshot weather --surface page --output /tmp/weather-page.png

weather 替换为真实功能 id。--surface card（默认）是功能列表里的外部卡片，--surface page 是点入后的完整功能页面，二者独立选中，不用标题、相同 URL 或正文猜测目标。list 和 inspect 先检查是否挂载；列表懒加载、离页或隐藏可能使卡片尚未挂载，完整页面也要实际打开后才存在。未打开就明确报告，不能把服务 HTTP 成功冒充画面已显示；截图要求真实可见画面。

inspect 包含真实生命周期、服务状态、挂载状态、原生尺寸、视口、html/body 计算布局和桥就绪状态。纯白时先查服务错误，再查 viewport、html/body 的高度、display、visibility 和 overflow，不盲目改数据接口。logs 同时给出有界的服务输出及页面 console、JavaScript 错误、未处理 Promise 拒绝、资源加载错误；日志只覆盖已经挂载页面的捕获范围，不承诺保留全部历史。eval 支持 Promise，可查询 DOM、样式、状态或触发真实交互，拒绝和异常必须报告；不要伪造交互结果。截图为当前 WebView 的真实 PNG，保存到指定客体路径，不重复写已有文件。

reload 只重载选中的文档，不重启服务；静态卡片重新读取实际 HTML 文件，动态页面重新请求实际 URL，宿主重新注入主题和桥。修改服务端程序需要按真实生命周期停止、再启动，不把文档重载声称为后端重启。命令 JSON 输出供读取，失败退出码非零并保留原因；执行超时、导航、销毁、切换容器或容器重启后的旧请求不得当作新页面结果。

调试 eval 是用户可信代码执行，具有该页面现有的 Android LuaJ/Java 桥能力；不是只读检查或不可信脚本沙箱。调试通道仅本地、按当前容器运行代次认证，不读取、输出或写入代码中的调试凭据，不将它发布到公网。

## HTML 卡片与原生操作

HTML 拥有整个卡片区域，宿主不额外叠加标题、描述、按钮或内边距。可自由组织 DOM、CSS、JS，使用合适的框架及真实网络数据，不要求复刻原生布局，不强制每一行样式或禁止 CDN；确保响应式铺满实际视口，cardHeight 决定高度，cardSpan 决定单双列。宿主负责圆角和编辑手势。保持大字号中文、可读对比度及非线性反馈，天气/状态样式可按用户设计使用自己的配色和图形，不伪造数据。

HTML 卡片可读取 window.wawlink.card（id、status、memoryBytes、containerRunning）并监听 wawlink-card-update。动态 URL 文档在宿主初始化完成后发送 wawlink-ready，初始化写成 if(window.wawlink&&wawlink.ready) init(); else window.addEventListener('wawlink-ready', init, {once:true})，不要假设桌面首个脚本已获得桥。memoryBytes 是整数或 null，status 为 stopped/starting/running/stopping/failed，不把 null 当测得零；自行 fetch 的天气、服务器指标来自真实 API，加载/失败须真实反映。数据和主题更新不重载文档。

wawlink.action('open') 请求原生打开功能，wawlink.action('start') 请求启动，wawlink.action('stop') 请求停止；仅使用这三个动作，不通过假链接或启动脚本绕过原生生命周期。动作结果以真实状态更新和原生错误为准；不要假设点击即成功。原生设置提供确认卸载：先停止服务，再只删除经过验证的当前 HOME/.wawlink/features/<id>，不跟随链接、不删除宿主挂载或受保护根。

## Android：完整可信 LuaJ / Java 桥

卡片和完整功能页面都有 wawlink.lua(source)，返回 Promise。执行真实 LuaJ JsePlatform.standardGlobals，包含 luajava 的完整 Java 互操作，可调用 Android/Java API；不存在额外 API 白名单、WawLink 审批流程或人为能力限制。仍受 Android 系统权限、应用 UID、系统版本与设备能力限制；需要 Android 运行时权限时遵循系统真实授权，不伪造权限已获准。此桥是可信代码执行，不是沙箱；仅为用户明确需要的真实功能编写代码，不加载不可信 HTML/Lua。

Lua 全局提供 context、activity（没有 Activity 时为 nil）与 host。host.context、host.activity 对应上述对象；host.runOnUiThread(function) 同步切到 Android UI 线程并返回 Lua 结果。host.openUrl(url)、host.openBrowser(url)、host.share(text,title?)、host.launchApp(packageName) 也提供同名 Lua 全局函数；这些 Android 系统操作自动调度至主线程。普通 Lua 运行在桥的串行后台执行器，直接调用需要 UI 线程的 Java API 时使用 runOnUiThread，不在主线程阻塞等待网络或长任务。

JavaScript 的 wawlink.openUrl(url)、wawlink.openBrowser(url)、wawlink.share(text,title?)、wawlink.launchApp(packageName) 同样返回 Promise。openUrl 交给系统 URL 处理程序，openBrowser 打开外部浏览器，share 打开系统分享，launchApp 启动已安装且可启动的包；处理 Promise 拒绝并给出真实中文错误，不声称调用总能成功。这些显式接口可离开功能来源，不依赖 WebView 自动外跳或 intent: 导航。

wawlink.lua 没有返回值时解析为 null；一个返回值为对应 JSON 值，多个返回值为数组。支持 nil、布尔、有限数字、字符串；非空连续整数下标 Lua 表为数组，字符串键表为对象，空表为 {}。Java 基本值、字符串键 Map、Iterable、数组可返回；循环、非有限数字、函数及其他 Java userdata 会拒绝 Promise。操作 Java 对象后返回可 JSON 序列化的结果，不返回 Activity/Context 等对象。真实 Lua/Java 异常传到 Promise 拒绝，必须展示真实错误。

桥只接受当前受信来源的主框架；文档导航或销毁取消该文档的执行器。若系统 WebView 缺少安全文档起始注入或来源限定消息桥能力，原生明确报错，不回退到无来源保护的桥。桌面仍可使用卡片动作和实时数据，但 Android Lua/系统助手会明确拒绝 Promise；不要为桌面伪造 Android 能力。


## Web 来源与系统边界

服务页和动态卡片使用真实 loopback 来源，托管服务经过随机令牌网关。网页可加载本地 loopback 和 HTTPS 资源、外部接口及 WebSocket；外部依赖在受信主页面中执行时具有该页面的桥能力，只使用用户信任的依赖。主文档导航和 Android 原生桥仍限定当前受信来源，子框架及 object 不开放，file:/content: 宿主文件访问关闭；系统浏览器、分享、外部应用等使用显式 wawlink 助手或可信 LuaJ。保留 CORS、TLS、Android 系统权限边界；此桥不是不可信 HTML/Lua 的沙箱。

## 所有 UI 与文案要求（不可简化）

- 页面以现有 Material 3、白底大字号中文为默认，卡片可按用户需求自由布局和配色，使用合适的框架、图表或动画，不为了限制编写方式而拆减真实功能。
- 所有标题、按钮、说明、设置、空状态、加载、就绪、错误、确认、删除、停止与重试文案必须是自然、清晰的中文。状态来自真实操作，不展示假记录、示例数据、硬编码数量、虚构健康状态或凭据。
- 图标必须使用 Google Material Symbols，与 WawLink 的 Symbol 语义一致；不使用 emoji、字符画、图标替代品或未经授权的外部字体请求。原生为卡片和完整页面注入 Material Symbols 字体及主题，可使用 material-symbols-rounded 样式；不重复下载外部字体。服务页面也可使用同源 /__wawlink_native/material-symbols.ttf 字体资源。
- 原生提供 --md-sys-color-* 主题变量与 Material Symbols；需要跟随主题时直接使用，不覆盖作者明确指定的背景、渐变、颜色和图形。不要无意采用默认紫色；用户明确设计的配色按要求保留。
- 动效必须非线性，采用 Material emphasized 风格 cubic-bezier(.2,0,0,1) 或更精确的 emphasized easing；页面切换、卡片展开、状态变化、按钮反馈应连贯自然，不使用线性滑动、瞬移或持续无意义动画。尊重 prefers-reduced-motion，提供减少动画方案。
- 按照用户明确指定的全部界面、功能、布局、交互与文字逐项完成，不因本技能而缩减；若要求参考 ref-plan/plugin-management.html、skill-management.html 或其他设计，应准确保留对应视觉和交互，不用简化版取代。若请求还涉及插件/技能管理，Functions 入口只管理当前容器用户级对象，聊天会话弹窗入口只管理该会话工作区的项目级对象，不混用作用域。
- 文件、规则、插件、技能和终端属于真实内置能力，不在自定义卡片中伪造它们的安装、启停、重载或会话状态。配置保存不等于当前 agent 已重载；需要重启就明确提示，不虚构 runtime reload。

## 交付

保留已有用户卡片与 Skill 编辑，不覆写无关文件。只为明确请求创建所需功能，不预装样例，不改 rootfs 部署，不创建 credentials。完成后说明真实卡片目录、依赖、启动/停止行为、功能入口和已知真实限制。未获要求不运行自动化测试或模拟器，不声称未经实际验证的成功。
""".trimIndent() + "\n"

/** Historical bundles lacked an installation record; adopt only these exact published bytes. */
private val LEGACY_FEATURE_SKILL_HASHES = listOf(
    "e20e10acc702cea94148e6b49ea2bf140fa1690dcca3192faf8faeb03852b4bb",
    "839859f7c73299098068e50902b895a81802ed86ef98c6c411b0e1b8a5e09db9",
    "5a96b2621b9c1a8a8497b1b5c469030206ce0a8c916f7b1fd6e5f564eac914a7",
    "a669ee3b065497efb0962e8fb1ea68a0f183e876bf283fa8e1ef4358d941600c",
    "80d19787c9463af5cb4e4f11a0d41f952651d00fae85194713397e51999a782b",
    "44d4cc62f75c175ca0c0105dda3ff0eb6ba5bb85d651d06dd15e92029b737cac",
)

internal val FEATURE_SKILL_INSTALL = featureSkillInstallScript(FEATURE_SKILL)

internal fun featureSkillInstallScript(content: String): String {
    val bytes = content.toByteArray(Charsets.UTF_8)
    val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    return """
        set -eu
        case "${'$'}HOME" in /*) ;; *) echo '容器 HOME 无效' >&2; exit 1;; esac
        home=${'$'}(readlink -f -- "${'$'}HOME")
        # Resolve each existing ancestor before creating the next: BusyBox has no readlink -m,
        # and readlink -f cannot resolve an entire tree with missing intermediate directories.
        root="${'$'}home"
        for component in .omp agent skills; do
            next="${'$'}{root%/}/${'$'}component"
            if [ -e "${'$'}next" ] || [ -L "${'$'}next" ]; then
                actual=${'$'}(readlink -f -- "${'$'}next")
                case "${'$'}actual/" in "${'$'}{home%/}/"*) ;; *) echo 'Skill 目录通过符号链接越出 HOME' >&2; exit 1;; esac
                [ -d "${'$'}actual" ] || { echo 'Skill 路径不是目录' >&2; exit 1; }
            else
                mkdir -- "${'$'}next"
                actual=${'$'}(readlink -f -- "${'$'}next")
                case "${'$'}actual/" in "${'$'}{home%/}/"*) ;; *) echo 'Skill 目录通过符号链接越出 HOME' >&2; exit 1;; esac
            fi
            root="${'$'}actual"
        done
        dir="${'$'}root/wawlink-functions"
        [ ! -L "${'$'}dir" ] || exit 0
        mkdir -p -- "${'$'}dir"
        file="${'$'}dir/SKILL.md"
        record="${'$'}dir/.wawlink-builtin.sha256"
        target=$hash
        is_hash() { [ "${'$'}{#1}" = 64 ] && case "${'$'}1" in *[!0-9a-f]*) return 1;; *) return 0;; esac; }
        recorded=''
        prior=''
        if [ -e "${'$'}record" ] || [ -L "${'$'}record" ]; then
            [ ! -L "${'$'}record" ] && [ -f "${'$'}record" ] || exit 0
            [ "${'$'}(wc -c < "${'$'}record")" -le 130 ] || exit 0
            recorded=${'$'}(cat -- "${'$'}record")
            case "${'$'}recorded" in *'
'*) prior=${'$'}{recorded#*'
'}; recorded=${'$'}{recorded%%'
'*}; is_hash "${'$'}prior" || exit 0;; esac
            is_hash "${'$'}recorded" || exit 0
        fi
        [ ! -L "${'$'}file" ] || exit 0
        digest=''
        if [ -e "${'$'}file" ]; then
            [ -f "${'$'}file" ] || exit 0
            digest=${'$'}(sha256sum -- "${'$'}file"); digest=${'$'}{digest%% *}
            if [ "${'$'}digest" != "${'$'}target" ]; then
                if [ -n "${'$'}recorded" ]; then
                    [ "${'$'}digest" = "${'$'}recorded" ] || [ "${'$'}digest" = "${'$'}prior" ] || exit 0
                else
                    case "${'$'}digest" in ${LEGACY_FEATURE_SKILL_HASHES.joinToString("|")}) ;; *) exit 0;; esac
                fi
            elif [ "${'$'}recorded" = "${'$'}target" ] && [ -z "${'$'}prior" ]; then
                exit 0
            fi
        fi
        stage=${'$'}(mktemp -d "${'$'}dir/.wawlink-skill.XXXXXX")
        trap 'rm -f -- "${'$'}stage/content" "${'$'}stage/record"; rmdir -- "${'$'}stage"' 0
        umask 077
        printf '%s' ${shellQuote(Base64.getEncoder().encodeToString(bytes))} | base64 -d > "${'$'}stage/content"
        if [ -n "${'$'}digest" ]; then
            [ ! -L "${'$'}file" ] && [ -f "${'$'}file" ] || exit 0
            latest=${'$'}(sha256sum -- "${'$'}file"); [ "${'$'}{latest%% *}" = "${'$'}digest" ] || exit 0
            mode=${'$'}(stat -c %a -- "${'$'}file")
            chmod -- "${'$'}mode" "${'$'}stage/content"
        else
            [ ! -e "${'$'}file" ] && [ ! -L "${'$'}file" ] || exit 0
        fi
        [ ! -L "${'$'}record" ] || exit 0
        # Retain both sides until content replacement completes, so interruption is recoverable.
        printf '%s\n' "${'$'}target" > "${'$'}stage/record"
        if [ -n "${'$'}digest" ] && [ "${'$'}digest" != "${'$'}target" ]; then
            printf '%s\n' "${'$'}digest" >> "${'$'}stage/record"
        fi
        mv -fT -- "${'$'}stage/record" "${'$'}record"
        if [ "${'$'}digest" != "${'$'}target" ]; then
            if [ -n "${'$'}digest" ]; then
                [ ! -L "${'$'}file" ] && [ -f "${'$'}file" ] || exit 0
                latest=${'$'}(sha256sum -- "${'$'}file"); [ "${'$'}{latest%% *}" = "${'$'}digest" ] || exit 0
                mv -fT -- "${'$'}stage/content" "${'$'}file"
            else
                mv -nT -- "${'$'}stage/content" "${'$'}file"
                [ ! -e "${'$'}stage/content" ] || exit 0
            fi
        fi
        printf '%s\n' "${'$'}target" > "${'$'}stage/record"
        mv -fT -- "${'$'}stage/record" "${'$'}record"
    """.trimIndent()
}
