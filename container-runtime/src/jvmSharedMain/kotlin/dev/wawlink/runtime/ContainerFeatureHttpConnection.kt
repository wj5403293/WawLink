package dev.wawlink.runtime

import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.zip.GZIPInputStream

/** Small HTTP/1.1 loopback transport, including PATCH and decoded streaming/chunked responses. */
internal class ContainerFeatureHttpConnection(
    port: Int,
    method: String,
    path: String,
    headers: Map<String, String>,
    input: InputStream,
    length: Long,
) : AutoCloseable {
    val socket = Socket()
    val status: Int
    val responseHeaders = linkedMapOf<String, MutableList<String>>()
    val body: InputStream
    init {
        try {
            socket.connect(InetSocketAddress("127.0.0.1", port), 5000)
            socket.soTimeout = 30_000
            val output = socket.getOutputStream()
            val head = buildString {
                append("$method $path HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nConnection: close\r\nContent-Length: $length\r\n")
                headers.forEach { (name, value) ->
                    require(name.matches(Regex("[A-Za-z0-9!#$%&'*+.^_`|~-]+")) && '\r' !in value && '\n' !in value)
                    append("$name: $value\r\n")
                }
                append("\r\n")
            }
            output.write(head.toByteArray(Charsets.ISO_8859_1))
            featureTransfer(input, output, length)
            output.flush()
            val response = BufferedInputStream(socket.getInputStream())
            val statusLine = featureHttpLine(response).split(' ', limit = 3)
            require(statusLine.size >= 2 && statusLine[0].startsWith("HTTP/1."))
            status = statusLine[1].toInt()
            require(status in 200..599)
            var total = 0
            while (true) {
                val row = featureHttpLine(response)
                total += row.length; require(total < 65_536)
                if (row.isEmpty()) break
                val split = row.indexOf(':'); require(split > 0)
                responseHeaders.getOrPut(row.substring(0, split).lowercase()) { mutableListOf() }.add(row.substring(split + 1).trim())
            }
            val framed = when {
                method == "HEAD" || status in setOf(204, 304) -> java.io.ByteArrayInputStream(ByteArray(0))
                header("transfer-encoding")?.lowercase() == "chunked" -> ChunkedFeatureInput(response)
                header("content-length") != null -> LimitedFeatureInput(response, requireNotNull(header("content-length")).toLong().also { require(it >= 0) })
                else -> response
            }
            body = when (header("content-encoding")?.lowercase()) {
                null, "identity" -> framed
                "gzip" -> GZIPInputStream(framed)
                else -> error("功能服务返回不支持的 Content-Encoding")
            }
        } catch (failure: Throwable) { socket.close(); throw failure }
    }
    fun header(name: String): String? = responseHeaders[name]?.firstOrNull()
    override fun close() { socket.close() }
}

private class LimitedFeatureInput(private val input: InputStream, private var remaining: Long) : InputStream() {
    override fun read(): Int {
        if (remaining == 0L) return -1
        val value = input.read(); check(value >= 0) { "功能响应提前结束" }; remaining--; return value
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return -1
        val count = input.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
        check(count > 0) { "功能响应提前结束" }; remaining -= count; return count
    }
}

private class ChunkedFeatureInput(private val input: InputStream) : InputStream() {
    private var remaining = 0L
    private var afterChunk = false
    private var ended = false
    private fun next(): Boolean {
        if (ended) return false
        if (remaining > 0) return true
        if (afterChunk) require(featureHttpLine(input).isEmpty())
        remaining = featureHttpLine(input).substringBefore(';').trim().toLong(16)
        require(remaining >= 0)
        afterChunk = true
        if (remaining == 0L) {
            var total = 0
            while (true) { val trailer = featureHttpLine(input); total += trailer.length; require(total < 65_536); if (trailer.isEmpty()) break }
            ended = true; return false
        }
        return true
    }
    override fun read(): Int {
        if (!next()) return -1
        val value = input.read(); check(value >= 0) { "功能分块响应提前结束" }; remaining--; return value
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (!next()) return -1
        val count = input.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
        check(count > 0) { "功能分块响应提前结束" }; remaining -= count; return count
    }
}

private fun featureTransfer(input: InputStream, output: OutputStream, length: Long) {
    if (length == 0L) return
    var remaining = length
    val buffer = ByteArray(16_384)
    while (remaining > 0) {
        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
        check(count > 0) { "功能请求正文提前结束" }; output.write(buffer, 0, count); remaining -= count
    }
}

private fun featureHttpLine(input: InputStream): String {
    val output = java.io.ByteArrayOutputStream()
    while (true) {
        val byte = input.read(); require(byte >= 0)
        if (byte == 10) break
        require(output.size() < 8192)
        if (byte != 13) output.write(byte)
    }
    return output.toString("ISO-8859-1")
}
