package dev.wawlink.ui

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class RichGraphicSafetyTest {
    private val theme = RichGraphicTheme("#202020", "#fafafa", "#4058a0")
    private fun svg(body: String) = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 40 40\">$body</svg>"

    @Test fun svgRejectsActiveElementsEventsAndExternalReferences() {
        val unsafe = listOf(
            "<script>alert(1)</script>",
            "<rect width=\"1\" onLoad=\"alert(1)\"/>",
            "<foreignObject><div xmlns=\"http://www.w3.org/1999/xhtml\">x</div></foreignObject>",
            "<image href=\"file:///etc/passwd\"/>",
            "<use href=\"https://example.test/secret.svg#icon\"/>",
            "<use xmlns:xlink=\"http://www.w3.org/1999/xlink\" xlink:href=\"&#104;ttps://example.test/a.svg#icon\"/>",
            "<rect fill=\"url(https://example.test/a.svg#gradient)\"/>",
            "<style>@import 'https://example.test/a.css';</style>",
            "<style>rect{fill:u\\72l(https://example.test/a)}</style>",
            "<animate attributeName=\"href\" values=\"#ok;https://example.test/a\"/>",
        )
        unsafe.forEach { body -> assertFails(body) { safeSvg(svg(body), theme) } }
    }

    @Test fun svgRejectsEntitiesAndStylesheetProcessingInstructions() {
        assertFails { safeSvg("<!DOCTYPE svg [<!ENTITY x SYSTEM 'file:///etc/passwd'>]>" + svg("<text>&x;</text>"), theme) }
        assertFails { safeSvg("<?xml-stylesheet href='https://example.test/a.css'?>" + svg(""), theme) }
    }

    @Test fun svgKeepsLocalGradientsAndSuppliesThemeVariables() {
        val safe = safeSvg(svg("""
            <defs><linearGradient id="paint"><stop stop-color="currentColor"/></linearGradient></defs>
            <rect width="40" height="40" fill="url(#paint)" stroke="var(--accent)"/>
        """.trimIndent()), theme)
        assertTrue("url(#paint)" in safe)
        assertTrue("--surface:#fafafa" in safe)
        assertTrue("--accent:#4058a0" in safe)
        assertTrue("currentColor" in safe)
    }

    @Test fun localFilesAndUnknownDataFormatsNeverReachDecoders() {
        listOf("file:///etc/passwd", "content://private/image", "jar:file:///tmp/a.jar!/x.png", "/tmp/image.png", "javascript:alert(1)", "data:text/html;base64,PHNjcmlwdD4=")
            .forEach { source -> assertFails(source) { loadGraphicImage(source) } }
    }

    @Test fun percentDataDecodingPreservesBinaryBytesAndLiteralPlus() {
        val pngHeader = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10, 43, 255.toByte())
        val encoded = pngHeader.joinToString("") { "%%%02X".format(it.toInt() and 255) }
        assertContentEquals(pngHeader, loadGraphicImage("data:image/png,$encoded").first)
        assertContentEquals(pngHeader, loadGraphicImage("data:image/png;base64," + Base64.getEncoder().encodeToString(pngHeader)).first)
        assertTrue(loadGraphicImage("data:image/svg+xml,%3Csvg%3E+%3C/svg%3E").first.toString(Charsets.UTF_8).contains("+"))
    }

    @Test fun redirectsCannotEscapeHttpToLocalFiles() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/image") { response ->
            response.responseHeaders.add("Location", "file:///etc/passwd")
            response.sendResponseHeaders(302, -1)
            response.close()
        }
        server.start()
        try {
            assertFails { loadGraphicImage("http://127.0.0.1:${server.address.port}/image") }
        } finally {
            server.stop(0)
        }
    }
    @Test fun safeNamesUseDetectedFormatAndNeverPathsOrReservedNames() {
        kotlin.test.assertEquals("photo.gif", graphicImageFileName("https://example.test/a/photo.png?token=secret", "image/gif"))
        kotlin.test.assertEquals("image_CON.webp", graphicImageFileName("https://example.test/CON.jpg", "image/webp"))
        kotlin.test.assertEquals("image.svg", graphicImageFileName("data:image/svg+xml,<svg/>", "image/svg+xml"))
        kotlin.test.assertEquals("image.png", graphicImageFileName("https://example.test/%2e%2e", "image/png"))
        val name = graphicImageFileName("https://example.test/" + "a".repeat(200) + ".jpg", "image/jpeg")
        kotlin.test.assertEquals(84, name.length)
        assertTrue(name.none { it == '/' || it == '\\' || it == ':' })
    }

    @Test fun realGifBytesRetainOriginalFormatDespiteMisleadingDeclaration() {
        val bytes = Base64.getDecoder().decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7")
        val loaded = loadGraphicImage("data:image/png;base64," + Base64.getEncoder().encodeToString(bytes))
        assertContentEquals(bytes, loaded.first)
        kotlin.test.assertEquals("image/gif", loaded.second)
        val bitmap = rasterImage(loaded.first, loaded.second)
        kotlin.test.assertEquals(1, bitmap.width)
        kotlin.test.assertEquals(1, bitmap.height)
    }

}
