package dev.wawlink.app

import dev.wawlink.runtime.ContainerFile
import dev.wawlink.runtime.ContainerFileService
import java.net.URLConnection
import java.util.Locale

/** Only a basename is ever staged on a host; guest paths remain guest paths. */
internal fun hostFileName(name: String): String = name.substringAfterLast('/').substringAfterLast('\\')
    .map { if (it.isISOControl() || it in "<>:\"|?*") '_' else it }.joinToString("")
    .takeUnless { it.isBlank() || it == "." || it == ".." } ?: "document"

internal fun containerFileMimeType(name: String): String {
    val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return when (extension) {
        "png" -> "image/png"
        "jpg", "jpeg", "jpe" -> "image/jpeg"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        "ico" -> "image/x-icon"
        "tif", "tiff" -> "image/tiff"
        "apk" -> "application/vnd.android.package-archive"
        "webp" -> "image/webp"
        "heic", "heif" -> "image/heif"
        "avif" -> "image/avif"
        "svg" -> "image/svg+xml"
        "md", "markdown", "kt", "kts", "py", "sh", "go", "rs", "c", "h", "cpp", "java", "log", "toml", "yaml", "yml", "ini", "conf", "properties" -> "text/plain"
        "json", "jsonl" -> "application/json"
        "zip" -> "application/zip"
        "pdf" -> "application/pdf"
        else -> URLConnection.guessContentTypeFromName(name) ?: "application/octet-stream"
    }
}

private val containerCodeFileNames = setOf("dockerfile", "makefile", "jenkinsfile")

internal fun containerFileIcon(file: ContainerFile): String {
    if (file.kind == "link") return "link"
    if (file.directory) return "folder"
    val name = file.name.lowercase(Locale.ROOT)
    val extension = name.substringAfterLast('.', "").takeUnless { name.startsWith('.') }.orEmpty()
    return when (extension) {
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "ico", "heic", "heif", "avif" -> "image"
        "mp4", "mkv", "webm", "mov", "avi", "m4v", "3gp" -> "movie"
        "mp3", "wav", "flac", "ogg", "m4a", "aac", "opus" -> "music_note"
        "apk" -> "android"
        "zip", "tar", "gz", "bz2", "xz", "7z", "rar", "tgz", "tbz2", "zst", "jar", "war" -> "folder_zip"
        "pdf" -> "picture_as_pdf"
        "doc", "docx", "odt", "rtf", "epub" -> "article"
        "xls", "xlsx", "csv", "ods" -> "table_chart"
        "ppt", "pptx", "odp" -> "slideshow"
        "c", "cc", "cpp", "h", "hpp", "java", "kt", "kts", "go", "rs", "swift", "dart", "py", "rb", "php", "lua", "sh", "bash", "zsh", "fish", "ps1", "bat", "sql" -> "code"
        "html", "htm", "css", "scss", "less", "xml", "xhtml", "md", "markdown", "rst" -> "html"
        "json", "jsonl", "yaml", "yml", "toml", "ini", "conf", "properties", "env" -> "data_object"
        "ttf", "otf", "woff", "woff2" -> "font_download"
        "bin", "exe", "elf", "run", "com" -> "terminal"
        else -> if (name in containerCodeFileNames) "code" else "description"
    }
}

internal fun containerFileSubtitle(file: ContainerFile): String = when {
    file.directory -> "目录"
    file.regular -> "普通文件 · ${bytesLabel(file.size)}"
    file.kind == "link" -> "符号链接（仅显示属性，不跟随链接）"
    else -> "特殊文件（不支持操作）"
}

internal fun filterContainerFiles(files: List<ContainerFile>, query: String): List<ContainerFile> =
    query.trim().let { normalized -> if (normalized.isEmpty()) files else files.filter { it.name.contains(normalized, ignoreCase = true) } }

/** Shared by the browser and message file-link sheet; all bytes come from a guest process. */
internal class ContainerFileActions(private val service: ContainerFileService, private val host: HostFiles) {
    suspend fun open(source: String) {
        val file = service.stat(source)
        check(file.regular && file.readable) { "只能打开可读普通文件，不能打开链接或特殊文件：$source" }
        host.openFile(file.name, containerFileMimeType(file.name)) { output -> service.exportFile(source, output) }
    }

    suspend fun download(sources: List<String>, forceZip: Boolean = false): Boolean {
        require(sources.isNotEmpty())
        val files = sources.map { service.stat(it) }
        check(files.all { it.supported && it.readable }) { "只能下载可读普通文件与目录，不能下载链接或特殊文件" }
        val singleFile = !forceZip && files.size == 1 && files.single().regular
        val name = if (singleFile) files.single().name else if (files.size == 1) files.single().name + ".zip" else "container-files.zip"
        return host.saveFile(name, if (singleFile) containerFileMimeType(name) else "application/zip") { output ->
            if (singleFile) service.exportFile(sources.single(), output) else service.exportZip(sources, output)
        }
    }
}
