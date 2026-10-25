package dev.wawlink.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import dev.wawlink.protocol.AgentAppearance
import dev.wawlink.protocol.AgentColorSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AgentPaletteTest {
    @Test fun defaultsAreExactBlueWithWhiteCanvasAndBlueSecondaryRoles() {
        val appearance = AgentAppearance()
        assertEquals(AgentColorSource.BLUE, appearance.colorSource)
        assertEquals(AgentBlueSeed, appearance.customColor)
        val scheme = agentPaletteScheme(appearance, null)
        assertEquals(Color(0xFF0B57D0), scheme.primary)
        assertEquals(Color.White, scheme.surface)
        assertEquals(Color.White, scheme.background)
        assertNotEquals(Color(0xFFE8DEF8), scheme.secondaryContainer)
        assertNotEquals(Color(0xFF625B71), scheme.secondary)
        assertNotEquals(Color(0xFF6750A4), scheme.inversePrimary)
        assertEquals(scheme.secondaryContainer, AgentColorScheme().secondaryContainer)
    }

    @Test fun customSeedsDriveAllAccentAndNeutralRolesInBothModes() {
        for (dark in listOf(false, true)) {
            val red = agentSeedColorScheme(0xFFBA1A1A, dark)
            val green = agentSeedColorScheme(0xFF146C2E, dark)
            val blue = agentPaletteScheme(AgentAppearance(darkTheme = dark), null)
            assertNotEquals(red.primary, green.primary)
            assertNotEquals(red.secondary, green.secondary)
            assertNotEquals(red.secondaryContainer, green.secondaryContainer)
            assertNotEquals(red.tertiary, green.tertiary)
            assertNotEquals(red.surfaceContainer, green.surfaceContainer)
            assertNotEquals(red.outline, green.outline)
            assertNotEquals(red.inversePrimary, blue.inversePrimary)
            val selected = agentPaletteScheme(AgentAppearance(darkTheme = dark, colorSource = AgentColorSource.CUSTOM, customColor = 0xFFBA1A1A), null)
            assertEquals(red.primary, selected.primary)
            assertEquals(red.surfaceContainer, selected.surfaceContainer)
            assertTrue(contrast(red.primary, red.onPrimary) >= 4.5)
            assertTrue(contrast(red.primaryContainer, red.onPrimaryContainer) >= 4.5)
            assertTrue(contrast(red.secondaryContainer, red.onSecondaryContainer) >= 4.5)
            assertTrue(contrast(red.surface, red.onSurface) >= 7.0)
            assertTrue(contrast(red.error, red.onError) >= 4.5)
            if (dark) assertTrue(red.surface.luminance() < .1f) else assertEquals(Color.White, red.surface)
        }
    }

    @Test fun systemUsesNativeRolesOrExplicitBlueFallback() {
        for (dark in listOf(false, true)) {
            val appearance = AgentAppearance(darkTheme = dark, colorSource = AgentColorSource.SYSTEM)
            val fallback = agentPaletteScheme(appearance, null)
            assertSame(if (dark) AgentBlueDarkScheme else AgentBlueLightScheme, fallback)
            val native = agentSeedColorScheme(0xFF795548, dark)
            val selected = agentPaletteScheme(appearance, native)
            assertEquals(native.primary, selected.primary)
            assertEquals(native.secondaryContainer, selected.secondaryContainer)
            assertEquals(native.tertiary, selected.tertiary)
            if (!dark) assertEquals(Color.White, selected.surface)
        }
    }

    @Test fun accessibilityChangesSemanticColorsWithoutLosingCustomAccents() {
        for (dark in listOf(false, true)) {
            val material = agentSeedColorScheme(0xFF795548, dark)
            val regular = AgentColorScheme.fromMaterial(material, dark)
            val accessible = AgentColorScheme.fromMaterial(material, dark, assist = true)
            assertEquals(material.primary, accessible.primary)
            assertEquals(material.secondaryContainer, accessible.secondaryContainer)
            assertEquals(material.tertiary, accessible.material.tertiary)
            assertNotEquals(regular.tertiary, accessible.tertiary)
            assertNotEquals(regular.error, accessible.error)
            assertTrue(contrast(accessible.material.error, accessible.material.onError) >= 4.5)
            assertEquals(accessible.error, accessible.material.error)
        }
    }

    @Test fun invalidHexDraftsCannotBecomeSeedsAndAlphaIsAlwaysOpaque() {
        assertEquals(AgentBlueSeed, parseAgentSeedHex("#0B57D0"))
        assertEquals(AgentBlueSeed, parseAgentSeedHex(" 0b57d0 "))
        assertEquals(0xFF000000L, parseAgentSeedHex("000000"))
        assertEquals(0xFFFFFFFFL, parseAgentSeedHex("#ffffff"))
        for (draft in listOf("", "#", "#123", "#12345", "#1234567", "#FF123456", "#12345Z", "0x123456", "##123456")) {
            assertNull(parseAgentSeedHex(draft), draft)
        }
        assertEquals("#0B57D0", agentSeedHex(AgentBlueSeed))
        assertEquals("#000001", agentSeedHex(1))
        val opaque = agentSeedColorScheme(0xFF123456, false)
        val transparent = agentSeedColorScheme(0x00123456, false)
        assertEquals(opaque.primary, transparent.primary)
        assertEquals(opaque.secondaryContainer, transparent.secondaryContainer)
    }

    @Test fun containerContentRetainsColorFamilyAndReadableContrast() {
        val backgrounds = listOf(Color(0xFFD3E3FD), Color(0xFFC4EED0), Color(0xFFFFF2CC), Color(0xFFFAD2CF), Color(0xFFEADDFF))
        val contents = backgrounds.map(::agentContainerContentColor)
        assertEquals(contents.size, contents.distinct().size)
        assertTrue(contents[0].blue > contents[0].red)
        assertTrue(contents[1].green > contents[1].red && contents[1].green > contents[1].blue)
        assertTrue(contents[2].red > contents[2].blue && contents[2].green > contents[2].blue)
        assertTrue(contents[3].red > contents[3].green && contents[3].red > contents[3].blue)
        assertTrue(contents[4].red > contents[4].green && contents[4].blue > contents[4].green)
        backgrounds.zip(contents).forEach { (background, content) ->
            assertTrue(content.luminance() < .05f)
            assertTrue(contrast(background, content) >= 4.5f)
        }
        for (red in 0..255 step 17) for (green in 0..255 step 17) for (blue in 0..255 step 17) {
            val background = Color(red, green, blue)
            assertTrue(contrast(background, agentContainerContentColor(background)) >= 4.5f, "RGB $red/$green/$blue")
        }
    }

    private fun contrast(first: Color, second: Color): Float {
        val a = first.luminance()
        val b = second.luminance()
        return (maxOf(a, b) + .05f) / (minOf(a, b) + .05f)
    }
}
