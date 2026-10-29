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

    @Test fun selectingAnAbsentThinkingDefaultDoesNotCreateAnOverride() {
        val draft = mapOf<String, Any?>("id" to "plain", "name" to "保留名称")
        assertEquals(draft, changeModelThinkingField(draft, emptyMap(), "defaultLevel", null))
        assertEquals(draft, changeModelThinkingField(draft, emptyMap(), "mode", null))
    }

    @Test fun resettingTheThinkingModeRemovesTheWholeOptionalObject() {
        val draft = mapOf<String, Any?>(
            "id" to "configured",
            "thinking" to mapOf("mode" to "budget", "efforts" to listOf("high"), "supportsDisplay" to false),
            "supportsTools" to true,
        )
        assertEquals(draft - "thinking", changeModelThinkingField(draft, emptyMap(), "mode", null))
    }

    @Test fun resettingOneOptionalThinkingFieldPreservesRequiredAndUnknownFields() {
        val thinking = mapOf<String, Any?>("mode" to "effort", "efforts" to listOf("low", "high"), "defaultLevel" to "high", "future" to "keep")
        val draft = mapOf<String, Any?>("thinking" to thinking, "reasoning" to true)
        assertEquals(draft + ("thinking" to (thinking - "defaultLevel")), changeModelThinkingField(draft, emptyMap(), "defaultLevel", null))
    }

    @Test fun inheritedThinkingIsOnlyMaterializedForAnActualNestedChange() {
        val inherited = mapOf<String, Any?>("mode" to "anthropic-adaptive", "efforts" to listOf("high", "max"), "supportsDisplay" to true)
        val draft = mapOf<String, Any?>("name" to "覆盖名称")
        assertEquals(draft, changeModelThinkingField(draft, inherited, "requiresEffort", null))
        assertEquals(draft, changeModelThinkingField(draft, inherited, "mode", null))
        assertEquals(draft + ("thinking" to (inherited + ("supportsDisplay" to false))), changeModelThinkingField(draft, inherited, "supportsDisplay", false))
    }

    @Test fun unchangedNativeObjectsDoNotMaterializeAbsentOrInheritedOverrides() {
        val draft = mapOf<String, Any?>("name" to "keep")
        assertEquals(draft, changeNativeObjectField(draft, "compat", emptyMap(), emptyMap()))
        val inherited = mapOf<String, Any?>("supportsStore" to false, "future" to mapOf("unknown" to "keep"))
        assertEquals(draft, changeNativeObjectField(draft, "compat", inherited, inherited))
        val editedThenRestored = (inherited + ("supportsStore" to true)) + ("supportsStore" to false)
        assertEquals(draft, changeNativeObjectField(draft, "compat", inherited, editedThenRestored))
        val configured = draft + ("compat" to inherited)
        assertEquals(configured, changeNativeObjectField(configured, "compat", inherited, inherited))
    }

    @Test fun nativeObjectEditsAndClearsPreserveUnrelatedAndUnknownFields() {
        val initial = mapOf<String, Any?>("enabled" to true, "future" to mapOf("unknown" to "keep"))
        val draft = mapOf<String, Any?>("remoteCompaction" to initial, "name" to "keep", "futureRoot" to listOf(1L, false))
        val changed = initial + ("enabled" to false)
        assertEquals(draft + ("remoteCompaction" to changed), changeNativeObjectField(draft, "remoteCompaction", initial, changed))
        assertEquals(draft - "remoteCompaction", changeNativeObjectField(draft, "remoteCompaction", initial, emptyMap()))
        val withoutOverride = draft - "remoteCompaction"
        assertEquals(withoutOverride + ("remoteCompaction" to changed), changeNativeObjectField(withoutOverride, "remoteCompaction", initial, changed))
        assertEquals(withoutOverride, changeNativeObjectField(withoutOverride, "remoteCompaction", initial, emptyMap()))
    }

    @Test fun conditionalCompatNoOpDoesNotMakeItsParentDirtyAndRealEditKeepsUnknownRules() {
        val compat = mapOf<String, Any?>("supportsStore" to false, "future" to listOf("keep"))
        val untouched = changeNativeObjectField(compat, "whenThinking", emptyMap(), emptyMap())
        val model = mapOf<String, Any?>("name" to "keep")
        assertEquals(model, changeNativeObjectField(model, "compat", compat, untouched))
        val conditional = mapOf<String, Any?>("supportsStore" to true, "futureConditional" to 7L)
        val changed = changeNativeObjectField(compat, "whenThinking", emptyMap(), conditional)
        assertEquals(compat + ("whenThinking" to conditional), changed)
        assertEquals(model + ("compat" to changed), changeNativeObjectField(model, "compat", compat, changed))
        assertEquals(compat, changeNativeObjectField(changed, "whenThinking", conditional, emptyMap()))
    }
}
