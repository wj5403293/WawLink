package dev.wawlink.runtime

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** A token-gated, single-upstream loopback origin. Native platform bridges are renderer-owned. */
internal class ContainerFeatureGateway(val upstreamPort: Int, private val token: String, @Volatile var themeCss: String, private val iconFont: () -> ByteArray?) : AutoCloseable {
    private val server = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
    private val pool = Executors.newCachedThreadPool { task -> Thread(task, "wawlink-feature-web").apply { isDaemon = true } }
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val closed = AtomicBoolean()
    private val origin = "http://127.0.0.1:${server.localPort}"
    val url = "$origin/?__wawlink_token=$token"
    private val cookie = "wawlink_${server.localPort}"
    private val appCookiePrefix = "wawlink_app_${server.localPort}_"
    private val policy = "default-src 'self' https: http://127.0.0.1:* http://localhost:* data: blob:; script-src 'self' 'unsafe-inline' 'unsafe-eval' https: http://127.0.0.1:* http://localhost:* blob:; style-src 'self' 'unsafe-inline' https: http://127.0.0.1:* http://localhost:*; img-src 'self' https: http://127.0.0.1:* http://localhost:* data: blob:; font-src 'self' https: http://127.0.0.1:* http://localhost:* data:; connect-src 'self' https: wss: http://127.0.0.1:* http://localhost:* ws://127.0.0.1:* ws://localhost:*; frame-src 'none'; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'"

    init {
        pool.execute {
            try {
                while (!closed.get()) {
                    val socket = server.accept()
                    sockets.add(socket)
                    try {
                        if (closed.get()) {
                            sockets.remove(socket)
                            runCatching { socket.close() }
                            break
                        }
                        pool.execute {
                            try { if (!closed.get()) serve(socket) } catch (_: Throwable) {
                                if (!closed.get()) runCatching { respond(socket, 502, "功能服务连接失败".toByteArray(), "text/plain; charset=utf-8") }
                            } finally { sockets.remove(socket); runCatching { socket.close() } }
                        }
                    } catch (_: Throwable) {
                        if (!closed.get()) runCatching { respond(socket, 502, "功能服务连接失败".toByteArray(), "text/plain; charset=utf-8") }
                        sockets.remove(socket)
                        runCatching { socket.close() }
                    }
                }
            } catch (failure: Throwable) {
                if (!closed.get()) runCatching { failure.printStackTrace() }
            } finally {
                close()
            }
        }
    }

    private fun serve(socket: Socket) {
        socket.soTimeout = 30_000
        val input = BufferedInputStream(socket.getInputStream())
        val request = line(input).split(' ')
        require(request.size == 3 && request[2].startsWith("HTTP/1."))
        val method = request[0]
        require(method in setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"))
        val headers = linkedMapOf<String, String>()
        var total = 0
        while (true) {
            val row = line(input)
            total += row.length
            require(total < 65_536)
            if (row.isEmpty()) break
            val split = row.indexOf(':'); require(split > 0)
            val name = row.substring(0, split).lowercase()
            require(name !in headers || name == "cookie")
            headers[name] = listOfNotNull(headers[name], row.substring(split + 1).trim()).joinToString("; ")
        }
        if (headers["host"] != "127.0.0.1:${server.localPort}" ||
            headers["origin"]?.let { it != origin } == true) {
            respond(socket, 403, "拒绝非功能来源请求".toByteArray(), "text/plain; charset=utf-8"); return
        }
        val target = URI(request[1])
        require(!target.isAbsolute && target.rawPath.startsWith('/') && !target.rawPath.startsWith("//"))
        val parts = target.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }
        val authorized = parts.any { it == "__wawlink_token=$token" } ||
            headers["cookie"].orEmpty().split(';').any { it.trim() == "$cookie=$token" }
        if (!authorized) { respond(socket, 403, "功能访问令牌无效".toByteArray(), "text/plain; charset=utf-8"); return }
        val query = parts.filterNot { it.startsWith("__wawlink_token=") }.joinToString("&")
        val path = target.rawPath + if (query.isEmpty()) "" else "?$query"
        if (target.rawPath == "/__wawlink_native/material-symbols.ttf") {
            val font = iconFont()
            respond(socket, if (font == null) 404 else 200, font ?: ByteArray(0), "font/ttf", authenticated = true)
            return
        }
        if (headers["upgrade"].equals("websocket", true)) {
            tunnel(socket, input, method, path, headers); return
        }
        require(headers["transfer-encoding"] == null) { "不接受分块请求正文" }
        val size = headers["content-length"]?.toLong() ?: 0
        require(size in 0..(32L * 1024 * 1024))
        val forwarded = headers.filterKeys { it !in excludedRequestHeaders }.toMutableMap()
        forwarded["Accept-Encoding"] = "identity"
        forwarded["X-WawLink-Token"] = token
        forwarded["Authorization"] = "Bearer $token"
        if (headers["origin"] != null) forwarded["Origin"] = "http://127.0.0.1:$upstreamPort"
        backendCookies(headers["cookie"])?.let { forwarded["Cookie"] = it }
        val upstream = Socket()
        sockets.add(upstream)
        try {
            if (closed.get()) return
            val connection = ContainerFeatureHttpConnection(upstreamPort, method, path, forwarded, input, size, upstream)
            val status = connection.status
            val type = connection.header("content-type") ?: "application/octet-stream"
            val extra = linkedMapOf<String, String>()
            connection.responseHeaders.forEach { (name, values) ->
                if (name !in excludedResponseHeaders && values.isNotEmpty()) extra[name] = values.joinToString(", ")
            }
            connection.header("location")?.let { location ->
                val redirect = URI(location)
                if (redirect.isAbsolute) {
                    require(redirect.scheme == "http" && redirect.host == "127.0.0.1" && redirect.port == upstreamPort) { "拒绝跳转到功能来源之外" }
                    extra["Location"] = origin + redirect.rawPath + (redirect.rawQuery?.let { "?$it" } ?: "")
                } else {
                    require(!location.startsWith("//"))
                    extra["Location"] = location
                }
            }
            val cookies = connection.responseHeaders["set-cookie"].orEmpty().map { appCookiePrefix + it }
            if (type.substringBefore(';').equals("text/event-stream", true)) {
                connection.socket.soTimeout = 0
                respond(socket, status, ByteArray(0), type, extra, authenticated = true, cookies = cookies, stream = connection.body)
                return
            }
            var body = featureReadBounded(connection.body, 32 * 1024 * 1024)
            if (type.substringBefore(';').equals("text/html", true) && body.isNotEmpty()) {
                val html = body.toString(Charsets.UTF_8)
                val guard = FEATURE_WEB_GUARD.replace("__WAWLINK_COOKIE_PREFIX__", appCookiePrefix)
                val style = "<script>$FEATURE_DEBUG_BOOTSTRAP</script><script>$guard</script><style id=\"wawlink-native-theme\">${themeCss.replace("</", "<\\/")}</style>"
                val head = Regex("<head(?:\\s[^>]*)?>", RegexOption.IGNORE_CASE).find(html)
                val gap = head?.range?.last?.plus(1) ?: Regex("<!doctype[^>]*>", RegexOption.IGNORE_CASE).find(html)?.range?.last?.plus(1) ?: 0
                body = (html.substring(0, gap) + style + html.substring(gap)).toByteArray()
            }
            respond(socket, status, body, if (type.startsWith("text/html", true)) "text/html; charset=utf-8" else type, extra, authenticated = true, cookies = cookies)
        } finally { sockets.remove(upstream); runCatching { upstream.close() } }
    }

    private fun tunnel(client: Socket, input: InputStream, method: String, path: String, headers: Map<String, String>) {
        require(method == "GET")
        val upstream = Socket()
        sockets.add(upstream)
        try {
            if (closed.get()) return
            upstream.connect(java.net.InetSocketAddress("127.0.0.1", upstreamPort), 5000)
            upstream.soTimeout = 30_000
            val out = upstream.getOutputStream()
            val request = buildString {
                append("GET $path HTTP/1.1\r\nHost: 127.0.0.1:$upstreamPort\r\n")
                headers.forEach { (key, value) -> if (key !in setOf("host", "cookie", "authorization", "x-wawlink-token", "origin")) append("$key: $value\r\n") }
                backendCookies(headers["cookie"])?.let { append("Cookie: $it\r\n") }
                append("Origin: http://127.0.0.1:$upstreamPort\r\nAuthorization: Bearer $token\r\nX-WawLink-Token: $token\r\n\r\n")
            }
            out.write(request.toByteArray(Charsets.ISO_8859_1)); out.flush()
            val response = BufferedInputStream(upstream.getInputStream())
            val status = line(response)
            require(status.startsWith("HTTP/1.1 101 ") || status.startsWith("HTTP/1.0 101 "))
            val responseHeaders = StringBuilder()
            while (true) { val row = line(response); if (row.isEmpty()) break; responseHeaders.append(row).append("\r\n"); require(responseHeaders.length < 65_536) }
            client.getOutputStream().apply { write(("$status\r\n$responseHeaders\r\n").toByteArray(Charsets.ISO_8859_1)); flush() }
            client.soTimeout = 0; upstream.soTimeout = 0
            pool.execute {
                try { input.copyTo(out); out.flush() }
                catch (_: Throwable) { /* Closing the tunnel reports copy failure to the peer. */ }
                finally { runCatching { upstream.close() }; runCatching { client.close() } }
            }
            response.copyTo(client.getOutputStream())
        } finally { sockets.remove(upstream); runCatching { upstream.close() } }
    }

    private fun backendCookies(raw: String?): String? = raw?.split(';')?.map { it.trim() }
        ?.filter { it.startsWith(appCookiePrefix) }?.joinToString("; ") { it.removePrefix(appCookiePrefix) }?.takeIf { it.isNotBlank() }

    private fun respond(socket: Socket, code: Int, body: ByteArray, type: String, extra: Map<String, String> = emptyMap(), authenticated: Boolean = false, cookies: List<String> = emptyList(), stream: InputStream? = null) {
        require('\r' !in type && '\n' !in type)
        val head = buildString {
            append("HTTP/1.1 $code WawLink\r\nContent-Type: $type\r\nConnection: close\r\n")
            if (stream == null) append("Content-Length: ${body.size}\r\n")
            append("Content-Security-Policy: $policy\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\nCache-Control: no-store\r\n")
            if (authenticated) append("Set-Cookie: $cookie=$token; Path=/; HttpOnly; SameSite=Strict\r\n")
            cookies.forEach { value -> require('\r' !in value && '\n' !in value); append("Set-Cookie: $value\r\n") }
            extra.forEach { (name, value) -> require('\r' !in value && '\n' !in value); append("$name: $value\r\n") }
            append("\r\n")
        }
        socket.getOutputStream().apply {
            write(head.toByteArray(Charsets.ISO_8859_1)); write(body); flush()
            if (stream != null) {
                val buffer = ByteArray(16_384)
                while (true) { val count = stream.read(buffer); if (count < 0) break; write(buffer, 0, count); flush() }
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { server.close() }
        sockets.forEach { runCatching { it.close() } }
        runCatching { pool.shutdownNow() }
    }

    private companion object {
        val excludedRequestHeaders = setOf("host", "cookie", "authorization", "x-wawlink-token", "content-length", "connection", "accept-encoding", "origin", "referer")
        val excludedResponseHeaders = setOf("content-length", "content-type", "connection", "transfer-encoding", "content-encoding", "content-security-policy", "content-security-policy-report-only", "set-cookie", "location", "access-control-allow-origin", "x-frame-options")
        fun line(input: InputStream): String {
            val bytes = ByteArrayOutputStream()
            while (true) {
                val byte = input.read(); require(byte >= 0)
                if (byte == 10) break
                require(bytes.size() < 8192)
                if (byte != 13) bytes.write(byte)
            }
            return bytes.toString("ISO-8859-1")
        }
    }
}

/** Runs before guest scripts; the embedded renderer must not expose its native file picker. */
private const val FEATURE_WEB_GUARD = """
(function(){
  'use strict';
  var prefix='__WAWLINK_COOKIE_PREFIX__', cookies=Object.getOwnPropertyDescriptor(Document.prototype,'cookie');
  if(cookies&&cookies.get&&cookies.set)Object.defineProperty(Document.prototype,'cookie',{configurable:false,get:function(){return cookies.get.call(this).split(';').map(function(c){return c.trim();}).filter(function(c){return c.indexOf(prefix)===0;}).map(function(c){return c.slice(prefix.length);}).join('; ');},set:function(c){cookies.set.call(this,prefix+c);}});
  function file(e){return e&&e.tagName==='INPUT'&&e.type==='file';}
  function cancel(e){if(file(e.target)||(e.dataTransfer&&e.dataTransfer.files&&e.dataTransfer.files.length)){e.preventDefault();e.stopImmediatePropagation();}}
  ['click','keydown','drop','dragover'].forEach(function(n){window.addEventListener(n,cancel,true);});
  var click=HTMLElement.prototype.click;
  Object.defineProperty(HTMLElement.prototype,'click',{value:function(){if(!file(this))return click.call(this);},writable:false,configurable:false});
  if(HTMLInputElement.prototype.showPicker)Object.defineProperty(HTMLInputElement.prototype,'showPicker',{value:function(){},writable:false,configurable:false});
  function disable(){document.querySelectorAll('input[type=file]').forEach(function(e){e.disabled=true;});}
  new MutationObserver(disable).observe(document.documentElement,{subtree:true,childList:true,attributes:true,attributeFilter:['type']});
  document.addEventListener('DOMContentLoaded',disable);
})();
"""

internal fun featureReadBounded(input: InputStream, maximum: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(16_384)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        require(output.size() + count <= maximum) { "功能响应超过大小上限" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
