package dev.wawlink.ui

// Match only documented read suffixes; preserve drive colons and archive member separators.
private val codeReadSelectors = Regex(
    "(?::(?:raw|img|conflicts|(?:\\d+(?:-\\d*|\\+\\d+)?|-\\d+)(?:,(?:\\d+(?:-\\d*|\\+\\d+)?|-\\d+))*))+$"
)

/** Explicit metadata wins, including an explicit unknown/plain language. No source is executed or read. */
internal fun inferCodeLanguage(
    explicit: String? = null,
    path: String? = null,
    text: String = "",
    interpreter: String? = null,
): String? {
    if (!explicit.isNullOrBlank()) return canonicalCodeLanguage(explicit)
    if (!path.isNullOrBlank()) {
        val sourcePath = path.substringBefore('?').replace(codeReadSelectors, "")
        val name = sourcePath.substringAfterLast('/').substringAfterLast('\\').lowercase()
        val fromPath = when {
            name == "dockerfile" || name.startsWith("dockerfile.") -> "dockerfile"
            name == "makefile" || name == "gnumakefile" -> "makefile"
            name == ".bashrc" || name == ".zshrc" || name == ".profile" -> "shell"
            else -> canonicalCodeLanguage(name.substringAfterLast('.', ""))
        }
        if (fromPath != null) return fromPath
    }
    val firstLine = text.lineSequence().firstOrNull().orEmpty().removePrefix("\uFEFF")
    if (firstLine.startsWith("#!")) {
        interpreterLanguage(firstLine.drop(2))?.let { return it }
    }
    return interpreter?.let(::interpreterLanguage)
}

internal fun canonicalCodeLanguage(value: String): String? {
    val name = value.trim().substringBefore(' ').lowercase().removePrefix("language-")
    return when (name) {
        "c", "h" -> "c"
        "c++", "cpp", "cc", "cxx", "hpp", "hh", "hxx" -> "cpp"
        "c#", "cs", "csharp" -> "csharp"
        "kt", "kts", "kotlin" -> "kotlin"
        "java" -> "java"
        "js", "jsx", "mjs", "cjs", "javascript", "node", "nodejs" -> "javascript"
        "ts", "tsx", "mts", "cts", "typescript", "deno", "bun" -> "typescript"
        "py", "pyw", "python", "python2", "python3" -> "python"
        "rb", "ruby" -> "ruby"
        "rs", "rust" -> "rust"
        "sh", "bash", "zsh", "fish", "dash", "ksh", "shell", "shellscript", "console" -> "shell"
        "pl", "pm", "perl" -> "perl"
        "go", "golang" -> "go"
        "php", "phtml" -> "php"
        "dart" -> "dart"
        "swift" -> "swift"
        "coffee", "coffeescript" -> "coffeescript"
        "json", "jsonl", "ndjson", "jsonc" -> "json"
        "yaml", "yml" -> "yaml"
        "xml", "xsd", "xsl", "svg" -> "xml"
        "html", "htm" -> "html"
        "css" -> "css"
        "sql" -> "sql"
        "lua" -> "lua"
        "md", "markdown" -> "markdown"
        "ini", "conf", "cfg" -> "ini"
        "properties" -> "properties"
        "dockerfile", "docker" -> "dockerfile"
        "makefile", "make" -> "makefile"
        else -> null
    }
}

private fun interpreterLanguage(command: String): String? {
    val words = command.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
    if (words.isEmpty()) return null
    var executable = words.first().trim('"', '\'').substringAfterLast('/').substringAfterLast('\\')
    if (executable == "env") {
        executable = words.drop(1).firstOrNull { !it.startsWith('-') && '=' !in it } ?: return null
        executable = executable.trim('"', '\'').substringAfterLast('/').substringAfterLast('\\')
    }
    executable = executable.lowercase().removeSuffix(".exe")
    return canonicalCodeLanguage(executable.replace(Regex("(?<=\\D)\\d+(?:\\.\\d+)*$"), ""))
}
