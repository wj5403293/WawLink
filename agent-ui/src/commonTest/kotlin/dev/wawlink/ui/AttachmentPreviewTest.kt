package dev.wawlink.ui

import dev.wawlink.protocol.AttachmentFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFails

class AttachmentPreviewTest {
    @Test fun supportedImagesUseMimeOrCaseInsensitiveExtension() {
        for (name in listOf("shot.PNG", "photo.jpg", "photo.JPEG", "animation.gif", "picture.webp", "diagram.svg")) {
            assertTrue(attachmentIsImage(AttachmentFile(name, name, "/guest/$name")))
            assertTrue(attachmentMimeType(name).startsWith("image/"))
        }
        assertTrue(attachmentIsImage(AttachmentFile("opaque", "opaque", "/guest/opaque", "image/png; charset=binary")))
        assertFalse(attachmentIsImage(AttachmentFile("code", "image.svg.kt", "/guest/image.svg.kt")))
        assertFalse(attachmentIsImage(AttachmentFile("html", "page.html", "/guest/page.html", "text/html")))
    }

    @Test fun strictUtf8PreviewRetainsWhitespaceAndEmptyText() {
        for (text in listOf("", "  \n\t", "\uFEFF你好\n  val a = 1\n")) {
            assertEquals(text, attachmentPreviewText(text.encodeToByteArray()))
        }
        val boundary = "a".repeat(ATTACHMENT_TEXT_BYTES)
        assertEquals(boundary, attachmentPreviewText(boundary.encodeToByteArray()))
    }

    @Test fun binaryInvalidUtf8AndOversizePrefixesAreRejected() {
        assertFails { attachmentPreviewText(byteArrayOf(0)) }
        assertFails { attachmentPreviewText(byteArrayOf(0xc3.toByte(), 0x28)) }
        assertFails { attachmentPreviewText(byteArrayOf(0xe4.toByte(), 0xbd.toByte())) }
        assertFails { attachmentPreviewText(ByteArray(ATTACHMENT_TEXT_BYTES + 1) { 65 }) }
        assertFails { attachmentPreviewText(byteArrayOf(65, 1, 66)) }
    }

    @Test fun boundedUtf8DropsOnlyIncompleteValidFinalCharacter() {
        val prefix = "文本\n".encodeToByteArray()
        val partial = prefix + byteArrayOf(0xe4.toByte(), 0xbd.toByte())
        assertEquals("文本\n", attachmentPreviewText(partial, truncated = true))
        assertFails { attachmentPreviewText(partial, truncated = false) }
        assertFails { attachmentPreviewText(prefix + byteArrayOf(0xe0.toByte(), 0x80.toByte()), truncated = true) }
        assertFails { attachmentPreviewText(prefix + byteArrayOf(0xf4.toByte(), 0x90.toByte()), truncated = true) }
        assertFails { attachmentPreviewText(byteArrayOf(0xff.toByte()) + partial, truncated = true) }
    }
}
