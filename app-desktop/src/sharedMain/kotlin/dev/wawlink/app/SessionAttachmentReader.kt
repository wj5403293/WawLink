package dev.wawlink.app

import dev.wawlink.protocol.AttachmentFile
import dev.wawlink.protocol.AttachmentSource
import dev.wawlink.runtime.ContainerFileService
import dev.wawlink.runtime.ContainerRuntime
import dev.wawlink.ui.AgentAttachmentHandler
import dev.wawlink.ui.AttachmentContent
import java.io.ByteArrayOutputStream
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

/** Guest sources never resolve against a host rootfs; local sources are picker-owned host files. */
internal class SessionAttachmentReader(
    private val runtime: ContainerRuntime,
    private val containerId: String,
) : AgentAttachmentHandler {
    override suspend fun read(file: AttachmentFile, maxBytes: Int): AttachmentContent {
        require(maxBytes in 1..8 * 1024 * 1024) { "预览读取范围必须在 1 B 到 8 MiB 之间" }
        require('\u0000' !in file.path) { "附件路径不能包含 NUL" }
        if (file.source == AttachmentSource.LOCAL) return readLocal(file, maxBytes)
        require(file.path.startsWith('/') && ContainerFileService.path(file.path) == file.path) { "附件必须使用规范的容器绝对路径" }
        val config = runtime.containers.value.firstOrNull { it.config.id == containerId }?.config
            ?: error("会话容器已不存在")
        val result = ContainerFileService(runtime, config).readPrefix(file.path, maxBytes)
        return AttachmentContent(result.bytes, result.sizeBytes, result.truncated)
    }

    private suspend fun readLocal(file: AttachmentFile, maxBytes: Int): AttachmentContent = runInterruptible(Dispatchers.IO) {
        val path = Paths.get(file.path)
        require(path.isAbsolute && path.normalize() == path) { "本机附件必须使用规范的绝对路径" }
        check(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.isReadable(path)) { "只能预览可读的本机普通文件，不能读取链接或特殊文件" }
        Files.newByteChannel(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            val size = channel.size()
            val output = ByteArrayOutputStream(minOf(size, maxBytes.toLong()).toInt())
            val buffer = ByteArray(minOf(maxBytes + 1, 8192))
            var count = 0
            var truncated = size > maxBytes
            Channels.newInputStream(channel).use { input ->
                while (count <= maxBytes) {
                    val read = input.read(buffer, 0, minOf(buffer.size, maxBytes + 1 - count))
                    if (read < 0) break
                    val keep = minOf(read, maxBytes - count)
                    if (keep > 0) output.write(buffer, 0, keep)
                    count += read
                    if (count > maxBytes) { truncated = true; break }
                }
            }
            AttachmentContent(output.toByteArray(), maxOf(size, count.toLong()), truncated)
        }
    }
}
