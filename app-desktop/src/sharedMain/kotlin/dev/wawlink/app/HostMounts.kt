package dev.wawlink.app

import java.io.File
import java.nio.file.Files

internal fun validateHostMountSource(source: String, readWrite: Boolean): String {
    val file = File(source)
    require(file.isAbsolute && '\u0000' !in source && ':' !in source) { "挂载源必须是本机的绝对路径，不能使用文档 URI" }
    val actual = file.canonicalFile
    require(actual.exists()) { "主机路径不存在或尚未获得访问权限：$source" }
    check(actual.canRead()) { "无法读取主机路径：$source" }
    if (actual.isDirectory) Files.newDirectoryStream(actual.toPath()).use { it.iterator().hasNext() }
    check(!readWrite || actual.canWrite()) { "主机路径不可写，请检查储存权限：$source" }
    return actual.path
}

/** A document tree is mountable only when its provider exposes a known local volume. */
internal fun storageTreeMountSource(documentId: String, roots: Map<String, File>): String {
    val separator = documentId.indexOf(':')
    require(separator > 0) { "所选目录没有有效的本机储存卷标识" }
    val volume = documentId.substring(0, separator)
    val relative = documentId.substring(separator + 1)
    require(!relative.startsWith('/') && '\u0000' !in relative && ':' !in relative && relative.split('/').none { it == "." || it == ".." }) { "所选目录路径无效" }
    val root = roots.entries.firstOrNull { it.key.equals(volume, ignoreCase = true) }?.value?.canonicalFile
        ?: error("无法取得所选储存卷的实际路径；请选择本机目录，或手动填写可访问的主机路径")
    val source = File(root, relative).canonicalFile
    require(source.toPath().startsWith(root.toPath())) { "所选目录超出本机储存卷" }
    return source.path
}
