package dev.wawlink.ui

import dev.wawlink.protocol.ConfigurationDocument
import dev.wawlink.protocol.ManagedProvider
import dev.wawlink.protocol.ProviderConfig
import kotlin.test.Test
import kotlin.test.assertEquals

class ProviderListingTest {
    @Test fun nativeProvidersWithoutModelsOrRecognizedProtocolsRemainVisibleWithoutBuiltinDuplicates() {
        val configured = listOf(
            ProviderConfig("builtin", "Local builtin override"),
            ProviderConfig("legacy-only", "Legacy provider"),
        )
        val native = listOf(
            ManagedProvider("empty-provider", enabled = true),
            ManagedProvider("extension-provider", enabled = true, api = "extension-protocol"),
            ManagedProvider("builtin", builtin = true),
        )

        val entries = providerListEntries(configured, native, emptyMap())

        assertEquals(listOf("empty-provider", "extension-provider", "legacy-only", "builtin"), entries.map { it.id })
    }

    @Test fun nativeAvailabilityOverridesLocalSwitchAndSinksDisabledEntriesWithinEachGroup() {
        val configured = listOf(
            ProviderConfig("unauthenticated", "No credentials", enabled = true),
            ProviderConfig("authenticated", "Has credentials", enabled = false),
        )
        val native = listOf(
            ManagedProvider("builtin-disabled", builtin = true),
            ManagedProvider("unauthenticated", enabled = false),
            ManagedProvider("builtin-enabled", builtin = true, enabled = true),
            ManagedProvider("authenticated", enabled = true),
        )

        val entries = providerListEntries(configured, native, emptyMap())

        assertEquals(listOf("authenticated", "unauthenticated", "builtin-enabled", "builtin-disabled"), entries.map { it.id })
    }

    @Test fun providerTogglePreservesPathRulesAndUnrelatedEntries() {
        val pathRule = mapOf<String, Any?>("values" to listOf("target"), "paths" to listOf("/work"), "future" to mapOf("keep" to true))
        val globalRules = listOf<Any?>(pathRule, "other", "other", null, 7L, "target")
        val global = ConfigurationDocument("global", "/agent/config.yml", "", "global-version", values = mapOf("disabledProviders" to globalRules, "futureSetting" to true))

        assertEquals(mapOf("disabledProviders" to listOf(pathRule, "other", "other", null, 7L)), providerEnabledSettings(global, "target", true))
        assertEquals(mapOf("disabledProviders" to globalRules), providerEnabledSettings(global, "target", false))
        assertEquals(mapOf("disabledProviders" to globalRules + "new-provider"), providerEnabledSettings(global, "new-provider", false))
        assertEquals(mapOf("disabledProviders" to globalRules), providerEnabledSettings(global, "absent", true))
    }

    @Test fun providerToggleWithNoGlobalRulesCreatesOnlyTheRequestedBareRule() {
        val global = ConfigurationDocument("global", "/agent/config.yml", "", "version")
        assertEquals(mapOf("disabledProviders" to listOf("target")), providerEnabledSettings(global, "target", false))
        assertEquals(mapOf("disabledProviders" to emptyList<Any?>()), providerEnabledSettings(global, "target", true))
        val pathOnly = mapOf<String, Any?>("values" to listOf("target"), "path" to "/work")
        val scoped = global.copy(values = mapOf("disabledProviders" to listOf(pathOnly)))
        assertEquals(mapOf("disabledProviders" to listOf(pathOnly, "target")), providerEnabledSettings(scoped, "target", false))
        assertEquals(mapOf("disabledProviders" to listOf(pathOnly)), providerEnabledSettings(scoped, "target", true))
    }
}
