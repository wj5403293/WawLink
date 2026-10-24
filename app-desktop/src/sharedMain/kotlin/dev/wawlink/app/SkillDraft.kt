package dev.wawlink.app

import kotlinx.serialization.json.*

/** Rewrite one known top-level YAML block only; unrelated YAML, comments and body remain byte-for-byte. */
internal fun skillReplaceYamlBlock(content: String, key: String, replacement: String): String {
    val front = Regex("^\\uFEFF?---[ \\t]*\\r?\\n([\\s\\S]*?)\\r?\\n---[ \\t]*(?:\\r?\\n|$)").find(content)
        ?: return "---\n$replacement\n---\n$content"
    val yaml = front.groupValues[1]
    val lines = yaml.split('\n').toMutableList()
    val start = lines.indexOfFirst { Regex("^${Regex.escape(key)}[ \\t]*:").containsMatchIn(it) }
    if (start < 0) lines.add(replacement)
    else {
        var end = start + 1
        while (end < lines.size && (lines[end].isBlank() || lines[end].startsWith(' ') || lines[end].startsWith('\t'))) end++
        repeat(end - start) { lines.removeAt(start) }
        lines.add(start, replacement)
    }
    return content.take(front.groups[1]!!.range.first) + lines.joinToString("\n") + content.drop(front.groups[1]!!.range.last + 1)
}
internal fun skillSetYamlString(content: String, key: String, value: String) = skillReplaceYamlBlock(content, key, "$key: ${JsonPrimitive(value)}")
internal fun skillSetMetadata(content: String, metadata: SkillObject) = skillReplaceYamlBlock(content, "metadata", "metadata:\n" + metadata.entries.joinToString("\n") { (key, value) -> "  ${if (key.matches(Regex("[A-Za-z0-9_-]+"))) key else JsonPrimitive(key)}: ${skillDraftJson(value)}" })
private fun skillDraftJson(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is String -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Map<*, *> -> JsonObject(value.entries.associate { it.key.toString() to skillDraftJson(it.value) })
    is List<*> -> JsonArray(value.map(::skillDraftJson))
    else -> JsonPrimitive(value.toString())
}
