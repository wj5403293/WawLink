package dev.wawlink.ui

import androidx.compose.ui.graphics.ImageBitmap
import dev.wawlink.ui.resources.Res
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.Base64
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.SAXException

internal const val MAX_GRAPHIC_BYTES = 8 * 1024 * 1024
private const val MAX_SVG_BYTES = 1024 * 1024
private const val MAX_MERMAID_CHARS = 50_000
private val engineLock = Mutex()
private var engine: String? = null

private suspend fun mermaidEngine(): String = engineLock.withLock {
    engine ?: Res.readBytes("files/rich/mermaid.min.js").decodeToString()
        .replace("</script", "<\\/script", ignoreCase = true).also { engine = it }
}

internal actual suspend fun prepareRichGraphic(graphic: MarkdownPart.Graphic, theme: RichGraphicTheme): PreparedGraphic {
    if (graphic.kind == GraphicKind.Image) {
        val (bytes, type) = loadGraphicImage(graphic.source)
        if (type != "image/svg+xml") return PreparedGraphic.Raster(rasterImage(bytes, type))
        return PreparedGraphic.Html(richGraphicDocument(graphic, theme, svgDataUri(bytes.toString(Charsets.UTF_8), theme)))
    }
    return PreparedGraphic.Html(richGraphicDocument(graphic, theme))
}

private suspend fun richGraphicDocument(graphic: MarkdownPart.Graphic, theme: RichGraphicTheme, imageData: String? = null): String {
    val content: String
    val javascript: String
    val engineScript: String?
    if (graphic.kind == GraphicKind.Mermaid) {
        require(graphic.source.length <= MAX_MERMAID_CHARS) { "图表内容过长（最多 50,000 字符）" }
        require(!graphic.source.contains("%%{") && !graphic.source.trimStart().startsWith("---")) {
            "图表不支持自定义配置指令，请移除配置后重试"
        }
        content = "<div id=graphic></div>"
        val source = Base64.getEncoder().encodeToString(graphic.source.toByteArray(Charsets.UTF_8))
        engineScript = mermaidEngine()
        javascript = """
            mermaid.initialize({startOnLoad:false,securityLevel:'strict',theme:'base',
              maxTextSize:$MAX_MERMAID_CHARS,maxEdges:500,htmlLabels:false,
              flowchart:{htmlLabels:false},fontFamily:'sans-serif',
              themeVariables:{primaryColor:'${theme.background}',primaryTextColor:'${theme.foreground}',
                primaryBorderColor:'${theme.accent}',lineColor:'${theme.foreground}',
                secondaryColor:'${theme.background}',tertiaryColor:'${theme.background}'}});
            (async function(){
              try {
                var source=decodeURIComponent(escape(atob('$source')));
                var result=await mermaid.render('wawlinkDiagram',source);
                var doc=new DOMParser().parseFromString(result.svg,'image/svg+xml');
                if(doc.querySelector('parsererror')) throw new Error('svg');
                // Defense in depth after Mermaid's strict DOMPurify pass. Never attach model links or callbacks.
                var nodes=doc.querySelectorAll('*');
                for(var i=0;i<nodes.length;i++){
                  var n=nodes[i], tag=n.localName.toLowerCase();
                  if(['script','foreignobject','iframe','object','embed','image','audio','video',
                    'animate','animatetransform','animatemotion','set'].indexOf(tag)>=0) throw new Error('unsafe');
                  for(var j=0;j<n.attributes.length;j++){
                    var a=n.attributes[j], name=a.localName.toLowerCase(), value=a.value.trim();
                    if(name.indexOf('on')===0) throw new Error('event');
                    if(name==='href' && value && value.charAt(0)!=='#') n.removeAttributeNode(a),j--;
                  }
                  if(tag==='style') checkCss(n.textContent);
                  if(n.hasAttribute('style')) checkCss(n.getAttribute('style'));
                }
                var image=document.createElement('img'); image.alt='Mermaid 图表';
                image.onload=loaded; image.onerror=failed;
                image.src='data:image/svg+xml;base64,'+btoa(unescape(encodeURIComponent(new XMLSerializer().serializeToString(doc.documentElement))));
                document.getElementById('graphic').replaceChildren(image);
              }catch(e){failed();}
            })();
        """.trimIndent()
    } else {
        engineScript = null
        val data = imageData ?: svgDataUri(graphic.source, theme)
        content = "<div id=graphic><img alt=\"${htmlEscape(graphic.description)}\" src=\"$data\"></div>"
        javascript = """
            var image=document.querySelector('#graphic img');
            image.onload=loaded; image.onerror=failed;
            if(image.complete){if(image.naturalWidth)loaded();else failed();}
        """.trimIndent()
    }
    // No remotely supplied HTML is ever interpolated. Images are data-only and scripts are app-owned.
    return buildString {
        append("""
        <!doctype html><html><head><meta charset=utf-8>
        <meta name=viewport content="width=device-width,initial-scale=1">
        <meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data:; font-src 'none'; connect-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'">
        <style>
          :root{--fg:${theme.foreground};--foreground:${theme.foreground};--bg:${theme.background};--background:${theme.background};--surface:${theme.background};--primary:${theme.accent};--accent:${theme.accent};color:${theme.foreground};background:${theme.background};color-scheme:light;}
          html,body{margin:0;padding:0;font-family:sans-serif;overflow:auto;}
          #graphic{width:100%;text-align:center;}#graphic img{display:block;max-width:100%;max-height:560px;width:auto;height:auto;margin:0 auto;}
        </style></head><body>$content<script>
          var finished=false,rendered=false;
          function report(height,error){
            window.__wawlinkResult=JSON.stringify({height:height,error:error});
            if(window.WawlinkGraphic)window.WawlinkGraphic.complete(height,error||'');
          }
          function loaded(){finished=true;requestAnimationFrame(function(){requestAnimationFrame(function(){rendered=true;measure();});});}
          function measure(){var el=document.getElementById('graphic');report(Math.max(48,Math.ceil(el.getBoundingClientRect().height)),null);}
          function failed(){finished=true;report(72,'图形渲染失败，请检查图形语法或图片格式');}
          function checkCss(css){
            // Mermaid emits keyframe definitions even for static diagrams. Validate their contents too.
            var cleaned=css.replace(/url\(\s*['"]?#[\w:.-]+['"]?\s*\)/gi,'').replace(/@keyframes\s+[\w-]+\s*\{/gi,'{');
            if(/url\s*\(|@|\\|\/\*|expression\s*\(|javascript\s*:/i.test(cleaned))throw new Error('css');
          }
          window.addEventListener('resize',function(){if(rendered)measure();});
          setTimeout(function(){if(!finished)failed();},20000);
        """.trimIndent())
        append('\n')
        engineScript?.let { append(it); append('\n') }
        append(javascript)
        append("</script></body></html>")
    }
}

private fun htmlEscape(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

internal fun loadGraphicImage(source: String): Pair<ByteArray, String> {
    val bytes: ByteArray
    val declaredType: String?
    when {
        source.startsWith("data:", ignoreCase = true) -> {
            require(source.length <= MAX_GRAPHIC_BYTES * 4 / 3 + 4096) { "图片过大（最多 8 MiB）" }
            val comma = source.indexOf(',')
            require(comma > 5) { "图片 data 地址无效" }
            val header = source.substring(5, comma).lowercase()
            declaredType = header.substringBefore(';')
            require(declaredType in setOf("image/svg+xml", "image/png", "image/jpeg", "image/gif", "image/webp")) { "图片格式不受支持" }
            val payload = source.substring(comma + 1)
            bytes = if (header.endsWith(";base64")) Base64.getDecoder().decode(payload)
                else percentDecodedBytes(payload)
        }
        else -> {
            val uri = URI(source)
            require(uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null) {
                "图片仅支持 HTTP(S) 或内嵌 data 地址，不能读取本机文件"
            }
            val fetched = fetchImage(uri)
            bytes = fetched.first
            declaredType = fetched.second
        }
    }
    require(bytes.size <= MAX_GRAPHIC_BYTES) { "图片过大（最多 8 MiB）" }
    val type = detectImageType(bytes, declaredType)
    require(type != null) { "图片格式不受支持（支持 PNG、JPEG、GIF、WebP、SVG）" }
    return bytes to type
}

private fun percentDecodedBytes(value: String): ByteArray {
    val output = ByteArrayOutputStream(value.length)
    var index = 0
    while (index < value.length) {
        if (value[index] == '%') {
            require(index + 2 < value.length) { "图片 data 地址转义无效" }
            val byte = value.substring(index + 1, index + 3).toIntOrNull(16)
            require(byte != null) { "图片 data 地址转义无效" }
            output.write(byte)
            index += 3
        } else {
            val end = value.indexOf('%', index).let { if (it < 0) value.length else it }
            output.write(value.substring(index, end).toByteArray(Charsets.UTF_8))
            index = end
        }
    }
    return output.toByteArray()
}

private fun fetchImage(initial: URI): Pair<ByteArray, String?> {
    var uri = initial
    repeat(6) {
        require(uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null) { "图片重定向地址不安全" }
        val connection = uri.toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "image/png,image/jpeg,image/gif,image/webp,image/svg+xml")
            connection.setRequestProperty("User-Agent", "WawLink-Image/1")
            val status = connection.responseCode
            if (status in setOf(301, 302, 303, 307, 308)) {
                val location = connection.getHeaderField("Location") ?: error("图片重定向缺少地址")
                val next = uri.resolve(location)
                require(!(uri.scheme.equals("https", true) && next.scheme.equals("http", true))) { "图片不允许从 HTTPS 降级到 HTTP" }
                uri = next
            } else {
                require(status in 200..299) { "图片下载失败（HTTP $status）" }
                require(connection.contentLengthLong <= MAX_GRAPHIC_BYTES) { "图片过大（最多 8 MiB）" }
                val bytes = connection.inputStream.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(output.size() + count <= MAX_GRAPHIC_BYTES) { "图片过大（最多 8 MiB）" }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                return bytes to connection.contentType?.substringBefore(';')?.trim()?.lowercase()
            }
        } finally {
            connection.disconnect()
        }
    }
    error("图片重定向次数过多")
}

private fun detectImageType(bytes: ByteArray, declared: String?): String? {
    fun starts(vararg signature: Int) = bytes.size >= signature.size && signature.indices.all { (bytes[it].toInt() and 255) == signature[it] }
    return when {
        starts(137, 80, 78, 71, 13, 10, 26, 10) -> "image/png"
        starts(255, 216, 255) -> "image/jpeg"
        bytes.size >= 6 && bytes.copyOfRange(0, 6).toString(Charsets.US_ASCII) in setOf("GIF87a", "GIF89a") -> "image/gif"
        bytes.size >= 12 && bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" && bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP" -> "image/webp"
        declared == "image/svg+xml" || bytes.take(512).toByteArray().toString(Charsets.UTF_8).trimStart('\uFEFF', ' ', '\n', '\r', '\t').let { it.startsWith("<svg") || it.startsWith("<?xml") } -> "image/svg+xml"
        else -> null
    }
}

/** Native decoders inspect bounds before allocating the raster. */
internal expect fun rasterImage(bytes: ByteArray, type: String): ImageBitmap

internal fun svgDataUri(source: String, theme: RichGraphicTheme): String =
    "data:image/svg+xml;base64," + Base64.getEncoder().encodeToString(safeSvg(source, theme).toByteArray(Charsets.UTF_8))

private val svgElements = setOf(
    "svg", "g", "defs", "title", "desc", "path", "rect", "circle", "ellipse", "line", "polyline", "polygon", "text", "tspan", "textPath",
    "use", "symbol", "view", "switch", "clipPath", "mask", "marker", "pattern", "linearGradient", "radialGradient", "stop", "style",
    "filter", "feBlend", "feColorMatrix", "feComponentTransfer", "feComposite", "feConvolveMatrix", "feDiffuseLighting", "feDisplacementMap",
    "feDistantLight", "feDropShadow", "feFlood", "feFuncA", "feFuncB", "feFuncG", "feFuncR", "feGaussianBlur", "feMerge", "feMergeNode",
    "feMorphology", "feOffset", "fePointLight", "feSpecularLighting", "feSpotLight", "feTile", "feTurbulence"
)
private val localUrl = Regex("url\\(\\s*['\"]?#[\\w:.-]+['\"]?\\s*\\)", RegexOption.IGNORE_CASE)
private val unsafeCss = Regex("url\\s*\\(|@|\\\\|/\\*|expression\\s*\\(|javascript\\s*:", RegexOption.IGNORE_CASE)
/** Reject rather than silently stripping active content. Parsed XML catches entities and mixed-case attributes. */
internal fun safeSvg(source: String, theme: RichGraphicTheme): String {
    require(source.toByteArray(Charsets.UTF_8).size <= MAX_SVG_BYTES) { "图形 SVG 过大（最多 1 MiB）" }
    require(!Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(source)) { "图形 SVG 不允许文档类型或实体声明" }
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isExpandEntityReferences = false
    }
    val builder = factory.newDocumentBuilder()
    builder.setEntityResolver { _, _ -> throw SAXException("external entity") }
    val document = try { builder.parse(ByteArrayInputStream(source.toByteArray(Charsets.UTF_8))) }
        catch (_: Exception) { error("图形 SVG 格式无效") }
    var documentChild = document.firstChild
    while (documentChild != null) {
        require(documentChild.nodeType != Node.PROCESSING_INSTRUCTION_NODE && documentChild.nodeType != Node.DOCUMENT_TYPE_NODE) {
            "图形 SVG 不允许处理指令或文档类型"
        }
        documentChild = documentChild.nextSibling
    }
    val root = document.documentElement
    require(root.localName == "svg" && root.namespaceURI in setOf(null, "http://www.w3.org/2000/svg")) { "图形必须是 SVG 文档" }
    var nodes = 0
    fun checkCss(value: String) {
        require(!unsafeCss.containsMatchIn(localUrl.replace(value, ""))) { "图形 SVG 不允许外部样式或资源引用" }
    }
    fun visit(element: Element, depth: Int) {
        require(++nodes <= 10_000 && depth <= 64) { "图形 SVG 结构过于复杂" }
        require(element.localName in svgElements && element.namespaceURI in setOf(null, "http://www.w3.org/2000/svg")) { "图形 SVG 包含不安全或不支持的元素" }
        val attributes = element.attributes
        for (index in 0 until attributes.length) {
            val attribute = attributes.item(index)
            val name = (attribute.localName ?: attribute.nodeName).lowercase()
            val value = attribute.nodeValue.trim()
            require(!name.startsWith("on") && name !in setOf("base", "src", "srcset")) { "图形 SVG 不允许脚本或外部资源" }
            require(attribute.namespaceURI in setOf(null, "http://www.w3.org/1999/xlink", "http://www.w3.org/XML/1998/namespace", "http://www.w3.org/2000/xmlns/")) { "图形 SVG 属性命名空间不受支持" }
            if (name == "href") require(value.matches(Regex("#[\\w:.-]+"))) { "图形 SVG 只能引用文档内元素" }
            if (attribute.nodeName == "xmlns" || attribute.prefix == "xmlns") {
                require(value in setOf("http://www.w3.org/2000/svg", "http://www.w3.org/1999/xlink")) { "图形 SVG 命名空间不受支持" }
            } else checkCss(value)
        }
        if (element.localName == "style") checkCss(element.textContent)
        var child = element.firstChild
        while (child != null) {
            when (child.nodeType) {
                Node.ELEMENT_NODE -> visit(child as Element, depth + 1)
                Node.PROCESSING_INSTRUCTION_NODE, Node.ENTITY_REFERENCE_NODE -> error("图形 SVG 不允许处理指令或实体")
            }
            child = child.nextSibling
        }
    }
    visit(root, 0)
    // Image documents cannot inherit host CSS variables; supply common theme tokens explicitly.
    root.setAttribute("color", theme.foreground)
    val themeStyle = "--fg:${theme.foreground};--foreground:${theme.foreground};--bg:${theme.background};--background:${theme.background};--surface:${theme.background};--primary:${theme.accent};--accent:${theme.accent};"
    root.setAttribute("style", themeStyle + root.getAttribute("style"))
    val transformer = TransformerFactory.newInstance().newTransformer().apply { setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes") }
    return ByteArrayOutputStream().also { transformer.transform(DOMSource(document), StreamResult(it)) }.toString("UTF-8")
}
