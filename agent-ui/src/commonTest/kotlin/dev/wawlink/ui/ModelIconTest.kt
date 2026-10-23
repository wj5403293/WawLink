package dev.wawlink.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelIconTest {
    @Test fun monogramUsesTheBeginningOfTheIdAndHandlesAnEmptyEditor() {
        assertEquals('A', modelInitial("alpha-private"))
        assertEquals('B', modelInitial("  beta-private"))
        assertEquals('7', modelInitial("7b-local"))
        assertEquals('?', modelInitial(""))
        assertEquals('?', modelInitial(" \t\n"))
    }

    @Test fun matchingInitialsShareAColorRegardlessOfCaseOrTheRemainingId() {
        val alpha = modelInitialColor(modelInitial("alpha-private"))
        assertEquals(alpha, modelInitialColor(modelInitial("Apple/another-model")))
        assertEquals(alpha, modelInitialColor('a'))
    }

    @Test fun alphabetBackgroundsRemainDistinctAndReadable() {
        val backgrounds = ('A'..'Z').map(::modelInitialColor)
        assertEquals(26, backgrounds.toSet().size)
        val foreground = Color(0xFF1F1F1F).luminance()
        backgrounds.forEach { background ->
            assertTrue((background.luminance() + .05f) / (foreground + .05f) >= 4.5f)
        }
    }
}
