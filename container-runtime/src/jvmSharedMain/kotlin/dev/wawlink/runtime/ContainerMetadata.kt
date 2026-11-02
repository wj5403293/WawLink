package dev.wawlink.runtime

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.CRC32
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class ContainerMetadata(
    val title: String = "",
    val description: String = "",
    val version: String = "",
    val icon: String = "",
)

data class ContainerMetadataSnapshot(val value: ContainerMetadata, val revision: String?)

/** Offline guest storage. Callers serialize access with the runtime mutex, never start a guest. */
internal class ContainerMetadataStore(private val engines: RuntimeEngines) {
    private val json = Json { prettyPrint = true }
    private data class Document(val value: ContainerMetadata, val fields: JsonObject, val revision: String?) {
        val snapshot get() = ContainerMetadataSnapshot(value, revision)
    }

    fun read(config: ContainerConfig): ContainerMetadataSnapshot = document(config, location(config)).snapshot

    fun save(config: ContainerConfig, metadata: ContainerMetadata, expectedRevision: String?): ContainerMetadataSnapshot {
        validate(metadata, editing = true)
        val file = location(config)
        val previous = document(config, file)
        check(previous.revision == expectedRevision) { CONFLICT }
        val fields = previous.fields.toMutableMap().apply {
            put("title", JsonPrimitive(metadata.title))
            put("description", JsonPrimitive(metadata.description))
            put("version", JsonPrimitive(metadata.version))
            put("icon", JsonPrimitive(metadata.icon))
        }
        val content = json.encodeToString(JsonObject(fields)).toByteArray(Charsets.UTF_8)
        require(content.size <= MAX_DOCUMENT_BYTES) { "容器信息文件过大" }
        RootfsHostFiles(config, engines::rootCommand).write(file.toFile(), content, expectedRevision)
        return ContainerMetadataSnapshot(metadata, revision(content))
    }

    private fun location(config: ContainerConfig): Path {
        val root = engines.validateRoot(config).toPath()
        val homeName = engines.home(config)
        require(homeName != "/") { "容器 HOME 越界" }
        val home = root.resolve(homeName.trimStart('/')).normalize()
        require(home.startsWith(root) && home != root) { "容器 HOME 越界" }
        return home.resolve(".wawlink/container.json")
    }

    private fun document(config: ContainerConfig, file: Path): Document {
        val bytes = RootfsHostFiles(config, engines::rootCommand).read(file.toFile(), MAX_DOCUMENT_BYTES)
            ?: return Document(ContainerMetadata(), JsonObject(emptyMap()), null)
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        checkMetadataNesting(text)
        val fields = json.parseToJsonElement(text) as? JsonObject ?: error("容器信息必须是 JSON 对象")
        fun field(name: String): String {
            val value = fields[name] ?: return ""
            require(value is JsonPrimitive && value.isString) { "容器信息字段 $name 必须是字符串" }
            return value.content
        }
        val metadata = ContainerMetadata(field("title"), field("description"), field("version"), field("icon"))
        validate(metadata, editing = false)
        return Document(metadata, fields, revision(bytes))
    }

    private fun revision(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val alphabet = "0123456789abcdef"
        return buildString(64) {
            for (byte in digest) {
                append(alphabet[(byte.toInt() and 255) ushr 4])
                append(alphabet[byte.toInt() and 15])
            }
        }
    }

    companion object {
        private const val MAX_DOCUMENT_BYTES = 1024 * 1024
        private const val MAX_ICON_BYTES = 512 * 1024
        private const val IMAGE_PREFIX = "data:image/png;base64,"
        private const val CONFLICT = "容器信息已被其他操作修改，请重新加载后再保存"
        private val symbol = Regex("[a-z][a-z0-9_]{0,63}")

        fun validate(metadata: ContainerMetadata, editing: Boolean = true) {
            require(!editing || metadata.title.isNotBlank()) { "容器标题不能为空" }
            require(metadata.title.length <= 120) { "容器标题最多 120 个字符" }
            require(metadata.description.length <= 4000) { "容器描述最多 4000 个字符" }
            require(metadata.version.length <= 64) { "发布版本最多 64 个字符" }
            for (value in listOf(metadata.title, metadata.description, metadata.version)) {
                var index = 0
                while (index < value.length) {
                    val character = value[index++]
                    if (character.isHighSurrogate()) require(index < value.length && value[index++].isLowSurrogate()) { "容器信息包含无效 Unicode 字符" }
                    else require(!character.isLowSurrogate()) { "容器信息包含无效 Unicode 字符" }
                }
            }
            if (metadata.icon.isEmpty() || symbol.matches(metadata.icon)) return
            require(metadata.icon.startsWith(IMAGE_PREFIX)) { "请选择 Material Symbol 或 PNG 图片" }
            val encoded = metadata.icon.substring(IMAGE_PREFIX.length)
            require(encoded.length <= ((MAX_ICON_BYTES + 2) / 3) * 4) { "图标文件不能超过 512 KiB" }
            val png = Base64.getDecoder().decode(encoded)
            require(png.size <= MAX_ICON_BYTES && Base64.getEncoder().encodeToString(png) == encoded) { "图标必须使用规范的 PNG Base64 编码，且不能超过 512 KiB" }
            validatePng(png)
        }

        /** Media decoding belongs to the platform codec; storage checks only bounded PNG framing. */
        private fun validatePng(png: ByteArray) {
            val signature = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
            require(png.size >= 57 && signature.indices.all { png[it] == signature[it] }) { "图标不是有效 PNG 图片" }
            fun integer(index: Int): Int = ((png[index].toInt() and 255) shl 24) or
                ((png[index + 1].toInt() and 255) shl 16) or ((png[index + 2].toInt() and 255) shl 8) or (png[index + 3].toInt() and 255)
            var offset = 8
            var data = false
            var ended = false
            while (offset < png.size) {
                require(png.size - offset >= 12) { "PNG 数据不完整" }
                val length = integer(offset)
                require(length >= 0 && length <= png.size - offset - 12) { "PNG 数据长度无效" }
                val type = String(png, offset + 4, 4, Charsets.US_ASCII)
                require(type.all { it in 'a'..'z' || it in 'A'..'Z' } && type[2] in 'A'..'Z') { "PNG 数据块无效" }
                val crc = CRC32().apply { update(png, offset + 4, length + 4) }.value
                require(crc == (integer(offset + 8 + length).toLong() and 0xffffffffL)) { "PNG 校验失败" }
                require(offset != 8 || type == "IHDR") { "PNG 缺少图片头" }
                when (type) {
                    "IHDR" -> {
                        require(offset == 8 && length == 13) { "PNG 图片头无效" }
                        require(integer(offset + 8) in 1..512 && integer(offset + 12) in 1..512) { "图标尺寸不能超过 512 × 512" }
                        require(png[offset + 18] == 0.toByte() && png[offset + 19] == 0.toByte() && png[offset + 20].toInt() in 0..1) { "PNG 编码无效" }
                    }
                    "IDAT" -> data = data || length > 0
                    "IEND" -> {
                        require(length == 0 && data && offset + 12 == png.size) { "PNG 数据不完整" }
                        ended = true
                    }
                }
                offset += length + 12
            }
            require(ended) { "PNG 缺少结束数据块" }
        }
    }
}

/** Bound parser recursion before decoding metadata supplied by an imported Rootfs. */
private fun checkMetadataNesting(text: String) {
    var depth = 0
    var quoted = false
    var escaped = false
    for (character in text) {
        if (quoted) {
            if (escaped) escaped = false
            else if (character == '\\') escaped = true
            else if (character == '"') quoted = false
        } else when (character) {
            '"' -> quoted = true
            '{', '[' -> { depth++; require(depth <= 64) { "容器信息 JSON 嵌套过深" } }
            '}', ']' -> depth--
        }
    }
}
