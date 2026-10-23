package dev.wawlink.ui

import androidx.compose.runtime.saveable.SaverScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class NativeProviderDraftTest {
    private val scope = object : SaverScope {
        override fun canBeSaved(value: Any) = true
    }

    @Test fun firstReadKeepsTypedValuesAndExplicitRemovalsWithoutDroppingUntouchedFields() {
        val initial = mapOf<String, Any?>(
            "baseUrl" to "https://old.invalid/v1",
            "api" to "openai-responses",
            "apiKey" to "OLD_KEY",
            "headers" to mapOf("x-project" to "existing"),
        )
        val typed = mapOf<String, Any?>("baseUrl" to "https://typed.invalid/v1")
        val merged = mergeProviderFirstRead(initial, typed, listOf("baseUrl", "apiKey"))
        assertEquals("https://typed.invalid/v1", merged["baseUrl"])
        assertEquals("openai-responses", merged["api"])
        assertEquals(initial["headers"], merged["headers"])
        assertFalse(merged.containsKey("apiKey"))
    }

    @Test fun modelSubformAndInvalidNativeFieldValuesSurviveSavedStateRoundTrip() {
        val draft = mapOf<String, Any?>(
            "api" to "not-a-native-protocol",
            "baseUrl" to "unfinished address",
            "models" to listOf(mapOf("id" to "typed-model", "contextWindow" to "still typing", "maxTokens" to 4096L)),
            "modelOverrides" to mapOf("builtin/model" to mapOf("reasoning" to false, "thinking" to mapOf("efforts" to listOf("low", "high")))),
            "headers" to mapOf("x-draft" to "keep exactly"),
        )
        val saved = with(NativeProviderFieldsSaver) { scope.save(draft) }
        val restored = requireNotNull(NativeProviderFieldsSaver.restore(requireNotNull(saved)))
        assertEquals(draft, restored)
        assertEquals(restored, mergeProviderFirstRead(mapOf("baseUrl" to "external refresh"), restored, restored.keys.toList()))
    }
}
