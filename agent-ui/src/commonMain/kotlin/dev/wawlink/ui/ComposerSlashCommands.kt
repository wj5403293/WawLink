package dev.wawlink.ui

import dev.wawlink.protocol.AgentSlashCommand

internal data class ComposerSlashCommand(
    val name: String,
    val aliases: List<String> = emptyList(),
    val icon: String = "terminal",
    val description: String? = null,
)

private data class SlashPreset(val icon: String, val description: String)

// Presentation only: availability and aliases always come from the native catalog.
private val builtinSlashPresets = mapOf(
    "security" to SlashPreset("security", "规划、运行与比较安全扫描"),
    "model" to SlashPreset("neurology", "查看当前模型选择"),
    "switch" to SlashPreset("swap_horiz", "仅切换本会话的模型"),
    "fast" to SlashPreset("bolt", "切换快速模式"),
    "slow" to SlashPreset("slow_motion_video", "切换慢速模式"),
    "skillful" to SlashPreset("psychology", "切换技能列表显示"),
    "extended-context" to SlashPreset("expand", "切换扩展上下文"),
    "computer" to SlashPreset("computer", "切换电脑操作功能"),
    "ratchet" to SlashPreset("trending_up", "构建评估并自动迭代优化流程"),
    "prewalk" to SlashPreset("route", "启动、重启或取消预探索"),
    "modelpreset" to SlashPreset("tune", "管理模型预设"),
    "effort" to SlashPreset("psychology", "查看或设置推理强度"),
    "advisor" to SlashPreset("support_agent", "切换顾问模式"),
    "export" to SlashPreset("file_export", "将会话导出为 HTML"),
    "trace" to SlashPreset("timeline", "在统计面板中打开会话追踪"),
    "dump" to SlashPreset("description", "获取完整文本记录与请求 JSON 路径"),
    "share" to SlashPreset("share", "通过加密链接分享会话"),
    "browser" to SlashPreset("web", "切换浏览器无头或可见模式"),
    "todo" to SlashPreset("checklist", "管理待办事项"),
    "session" to SlashPreset("chat", "查看或配置当前会话"),
    "jobs" to SlashPreset("work", "查看后台任务"),
    "usage" to SlashPreset("data_usage", "查看令牌用量"),
    "stats" to SlashPreset("monitoring", "打开本地统计面板"),
    "changelog" to SlashPreset("history", "查看更新日志"),
    "tools" to SlashPreset("build", "查看可用工具"),
    "context" to SlashPreset("donut_large", "查看上下文用量"),
    "mcp" to SlashPreset("hub", "管理 MCP 服务"),
    "ssh" to SlashPreset("dns", "管理 SSH 连接"),
    "fresh" to SlashPreset("refresh", "重置服务端流状态，保留本地记录"),
    "compact" to SlashPreset("compress", "压缩会话上下文"),
    "shake" to SlashPreset("filter_alt", "移除上下文中的大块内容"),
    "handoff" to SlashPreset("assignment", "生成交接摘要并压缩当前会话"),
    "pin" to SlashPreset("keep", "置顶或取消置顶会话"),
    "retry" to SlashPreset("replay", "重试上次失败的执行"),
    "memory" to SlashPreset("memory", "管理记忆"),
    "rename" to SlashPreset("edit", "重命名会话，留空则自动生成标题"),
    "move" to SlashPreset("drive_file_move", "将会话移至其他目录"),
    "wt" to SlashPreset("account_tree", "携带改动迁移到新工作树"),
    "add-dir" to SlashPreset("create_new_folder", "添加会话工作目录"),
    "remove-dir" to SlashPreset("folder_off", "移除会话工作目录"),
    "dirs" to SlashPreset("folder", "列出会话工作目录"),
    "marketplace" to SlashPreset("storefront", "管理插件市场"),
    "plugins" to SlashPreset("extension", "管理插件"),
    "reload-plugins" to SlashPreset("refresh", "重新加载所有插件"),
    "force" to SlashPreset("construction", "指定下一轮使用的工具"),
    "forget-approvals" to SlashPreset("shield", "清除已记住的文件与文件夹批准"),
)

internal fun composerSlashCommands(native: List<AgentSlashCommand>): List<ComposerSlashCommand> {
    // A custom command must not acquire builtin help text merely by sharing its name.
    val unique = linkedMapOf<String, AgentSlashCommand>()
    native.forEach { command ->
        val key = command.name.lowercase()
        val previous = unique[key]
        if (previous == null || previous.source == "builtin" && command.source != "builtin") unique[key] = command
    }
    val commands = unique.values.map { command ->
        val preset = builtinSlashPresets[command.name].takeIf { command.source == "builtin" }
        ComposerSlashCommand(command.name, command.aliases, preset?.icon ?: "terminal", preset?.description)
    }.toMutableList()
    fun local(name: String, icon: String, description: String) {
        val index = commands.indexOfFirst { it.name.equals(name, ignoreCase = true) }
        val nativeCommand = unique[name]
        val customShadow = nativeCommand != null && nativeCommand.source != "builtin"
        val command = ComposerSlashCommand(name, if (index >= 0) commands[index].aliases else emptyList(),
            if (customShadow) "terminal" else icon, if (customShadow) null else description)
        if (index >= 0) commands[index] = command else commands.add(command)
    }
    local("model", "neurology", "切换模型")
    local("cost", "monitoring", "查看用量")
    return commands
}

internal fun slashQuery(text: String): String? =
    text.takeIf { it.startsWith('/') && it.drop(1).none(Char::isWhitespace) }?.drop(1)

internal fun matchingSlashCommands(commands: List<ComposerSlashCommand>, query: String): List<ComposerSlashCommand> =
    commands.filter { command -> command.name.startsWith(query, ignoreCase = true) || command.aliases.any { it.startsWith(query, ignoreCase = true) } }

internal enum class LocalSlashAction { MODEL, COST }

internal fun localSlashAction(prompt: String): LocalSlashAction? = when (prompt.trim()) {
    "/model" -> LocalSlashAction.MODEL
    "/cost" -> LocalSlashAction.COST
    else -> null
}
