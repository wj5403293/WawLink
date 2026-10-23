package dev.wawlink.ui

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
}
