package dev.wawlink.driver

import dev.wawlink.protocol.AttachmentFile
import dev.wawlink.protocol.AttachmentSource
import java.security.MessageDigest

internal data class AttachmentPrompt(val text: String, val attachments: List<AttachmentFile>)

private const val ATTACHMENT_MARKER = "\n\n附件（容器内真实文件）：\n"

/** Only the complete legacy transport suffix is metadata; the preceding user text is never trimmed. */
internal fun parseAttachmentPrompt(message: String, attachments: List<AttachmentFile> = emptyList()): AttachmentPrompt {
    val marker = message.lastIndexOf(ATTACHMENT_MARKER)
    if (marker < 0) return AttachmentPrompt(message, attachments)
    val text = message.substring(0, marker)
    val references = ArrayList<Pair<String, String>>()
    for (line in message.substring(marker + ATTACHMENT_MARKER.length).split('\n')) {
        if (!line.startsWith("- ") || line.any { it.code < 32 || it.code == 127 }) return AttachmentPrompt(message, attachments)
        val separator = line.indexOf(": /", startIndex = 2)
        if (separator < 0) return AttachmentPrompt(message, attachments)
        val name = line.substring(2, separator)
        val path = line.substring(separator + 2)
        if (name.isEmpty() || '/' in name || path == "/" ||
            path.endsWith('/') || path.split('/').any { it == "." || it == ".." }) return AttachmentPrompt(message, attachments)
        references += name to path
    }
    val remaining = attachments.toMutableList()
    val restored = references.map { (name, path) ->
        val index = remaining.indexOfFirst { it.name == name && it.path == path }
        if (index >= 0) remaining.removeAt(index) else AttachmentFile(
            id = "legacy:" + MessageDigest.getInstance("SHA-256").digest("$name\n$path".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) },
            name = name, path = path, mimeType = attachmentMimeType(name), source = AttachmentSource.CONTAINER)
    }
    return AttachmentPrompt(text, restored + remaining)
}

/** Raw input may itself contain a marker example, so every real attachment appends its own suffix. */
internal fun attachmentWireMessage(text: String, attachments: List<AttachmentFile>): String =
    if (attachments.isEmpty()) text else text + ATTACHMENT_MARKER + attachments.joinToString("\n") { "- ${it.name}: ${it.path}" }

internal fun attachmentMimeType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "webp" -> "image/webp"
    "gif" -> "image/gif"
    "svg" -> "image/svg+xml"
    "json", "map" -> "application/json"
    "txt", "log", "conf", "ini", "toml", "kt", "kts", "java", "py", "sh", "rs", "go", "c", "h", "cpp", "hpp" -> "text/plain"
    "md", "markdown" -> "text/markdown"
    "yaml", "yml" -> "application/yaml"
    "html", "htm" -> "text/html"
    "css" -> "text/css"
    "js", "mjs", "cjs", "ts", "tsx", "jsx" -> "text/javascript"
    "xml" -> "application/xml"
    "csv" -> "text/csv"
    else -> "application/octet-stream"
}
