package dev.wawlink.driver

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.serialization.json.*

class OmpFrameDecoderTest {
    @Test
    fun ordinaryFramesAreNotTransformed() {
        val input = buildJsonObject { put("type", "agent_start") }
        assertEquals(input, OmpFrameDecoder().push(input))
    }

    @Test
    fun protocolTwoReassemblesUtf8AcrossByteBoundaries() {
        val text = "思考与文本".repeat(100_000)
        val expected = buildJsonObject { put("type", "command_output"); put("text", text) }
        val bytes = expected.toString().toByteArray(Charsets.UTF_8)
        val chunks = (bytes.indices step 262_144).map { start -> bytes.copyOfRange(start, minOf(start + 262_144, bytes.size)) }
        val decoder = OmpFrameDecoder()
        chunks.forEachIndexed { index, chunk ->
            val actual = decoder.push(chunk("one", index, chunks.size, bytes.size, chunk))
            if (index == chunks.lastIndex) assertEquals(expected, actual) else assertNull(actual)
        }
    }

    @Test
    fun outOfOrderOrInterruptedChunksFailClosed() {
        val decoder = OmpFrameDecoder()
        assertNull(decoder.push(chunk("one", 0, 4, 1_048_576, ByteArray(262_144))))
        assertFailsWith<IllegalArgumentException> { decoder.push(chunk("one", 2, 4, 1_048_576, ByteArray(262_144))) }
        assertFailsWith<IllegalStateException> { decoder.push(buildJsonObject { put("type", "agent_end") }) }
    }

    @Test
    fun declaredTransportCeilingAndBase64AreValidated() {
        assertFailsWith<IllegalArgumentException> {
            OmpFrameDecoder().push(chunk("too-large", 0, 257, 67_108_865, ByteArray(1)))
        }
        val invalid = buildJsonObject {
            put("type", "rpc_chunk"); put("chunkId", "invalid"); put("index", 0)
            put("count", 4); put("byteLength", 1_048_576); put("data", "not base64!")
        }
        assertFailsWith<IllegalArgumentException> { OmpFrameDecoder().push(invalid) }
    }

    private fun chunk(id: String, index: Int, count: Int, length: Int, payload: ByteArray) = buildJsonObject {
        put("type", "rpc_chunk"); put("chunkId", id); put("index", index)
        put("count", count); put("byteLength", length)
        put("data", Base64.getEncoder().encodeToString(payload))
    }
}
