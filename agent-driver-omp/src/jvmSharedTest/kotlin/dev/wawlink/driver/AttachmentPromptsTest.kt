package dev.wawlink.driver

import dev.wawlink.protocol.AttachmentFile
import dev.wawlink.protocol.AttachmentSource
import kotlin.test.*

class AttachmentPromptsTest {
    private val file = AttachmentFile("selected", "要求.txt", "/workspace/要求.txt", "text/plain", 42, AttachmentSource.WORKSPACE)

    @Test fun wireSuffixRoundTripsExactWhitespaceAndAttachmentOnlyInput() {
        for (text in listOf("", " \n\t", "  原始问题\n\n ")) {
            val wire = attachmentWireMessage(text, listOf(file))
            val parsed = parseAttachmentPrompt(wire, listOf(file))
            assertEquals(text, parsed.text)
            assertEquals(listOf(file), parsed.attachments)
            assertEquals(text, attachmentWireMessage(text, emptyList()))
        }
    }

    @Test fun completeLegacySuffixRestoresContainerFilesWithoutALocalCache() {
        val files = listOf("picture.PNG", "photo.JPEG", "photo.jpg", "animation.webp", "animation.gif", "diagram.svg", "settings.json", "notes.txt")
            .map { file.copy(id = it, name = it, path = "/other/$it") }
        val wire = attachmentWireMessage("", files)
        val restored = parseAttachmentPrompt(wire)
        assertEquals("", restored.text)
        assertEquals(files.map { it.path }, restored.attachments.map { it.path })
        assertEquals(listOf("image/png", "image/jpeg", "image/jpeg", "image/webp", "image/gif", "image/svg+xml", "application/json", "text/plain"),
            restored.attachments.map { it.mimeType })
        assertTrue(restored.attachments.all { it.source == AttachmentSource.CONTAINER && it.id.startsWith("legacy:") })
        assertEquals(restored, parseAttachmentPrompt(wire))
    }

    @Test fun ordinaryMarkerExamplesAndIncompleteSuffixesStayLiteral() {
        val marker = "\n\n附件（容器内真实文件）：\n"
        val examples = listOf(
            "附件（容器内真实文件）：\n- 要求.txt: /workspace/要求.txt",
            "用法示例：$marker- 要求.txt: /workspace/要求.txt\n后面的正文",
            "```text$marker- 要求.txt: /workspace/要求.txt\n```",
            "~~~\n示例$marker- 要求.txt: /workspace/要求.txt\n~~~",
            "正文$marker", "正文$marker- 要求.txt: relative.txt", "正文$marker- 要求.txt: /workspace/../要求.txt",
            "正文$marker- 要求.txt: /workspace/要求.txt\n", "正文$marker- 要求.txt: /workspace/要求.txt\r",
            "正文$marker- path/name: /workspace/name", "正文$marker- 要求.txt: /",
            "正文$marker- 要求.txt: /workspace/要求.txt\n- broken",
        )
        for (text in examples) assertEquals(AttachmentPrompt(text, listOf(file)), parseAttachmentPrompt(text, listOf(file)))
    }

    @Test fun rawMarkerExampleGetsItsOwnIndependentTransportSuffix() {
        val text = attachmentWireMessage("原始示例", listOf(file))
        val parsed = parseAttachmentPrompt(attachmentWireMessage(text, listOf(file)), listOf(file))
        assertEquals(text, parsed.text)
        assertEquals(listOf(file), parsed.attachments)
        val fenced = "```text\n示例\n```"
        assertEquals(fenced, parseAttachmentPrompt(attachmentWireMessage(fenced, listOf(file))).text)
    }

    @Test fun unmatchedRawCodeFencesAndLiteralFilenameCharactersStillRestoreRealAttachments() {
        val unusual = file.copy(name = " \\notes.txt ", path = "/workspace/ \\notes.txt ")
        for (text in listOf("```kotlin\nval raw = ", "~~~\n未结束的原文")) {
            val parsed = parseAttachmentPrompt(attachmentWireMessage(text, listOf(unusual)))
            assertEquals(text, parsed.text)
            assertEquals(unusual.name, parsed.attachments.single().name)
            assertEquals(unusual.path, parsed.attachments.single().path)
            assertEquals(AttachmentSource.CONTAINER, parsed.attachments.single().source)
        }
    }

    @Test fun providedMetadataKeepsIdentitySourceAndDuplicateReferenceOrder() {
        val second = file.copy(id = "second", source = AttachmentSource.LOCAL)
        val wire = attachmentWireMessage("原文", listOf(file, second))
        assertEquals(listOf(file, second), parseAttachmentPrompt(wire, listOf(file, second)).attachments)
    }
}
