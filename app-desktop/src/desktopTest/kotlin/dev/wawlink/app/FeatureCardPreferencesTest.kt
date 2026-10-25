package dev.wawlink.app

import java.io.File
import java.nio.file.Files
import kotlin.test.*
import kotlinx.coroutines.runBlocking

class FeatureCardPreferencesTest {
    @Test fun keepsCustomizationWhenCardsDisappearAndReturnWithoutLeakingBetweenContainers() = runBlocking {
        val root = Files.createTempDirectory("feature-card-preferences-").toFile()
        try {
            val file = File(root, "cards.json")
            val preferences = FeatureCardPreferences(file)
            val terminal = "builtin/terminal"
            val custom = "extension/terminal"
            preferences.reconcile("first", listOf(terminal, custom))
            preferences.reorder("first", listOf(custom, terminal))
            preferences.hide("first", setOf(custom))
            preferences.setColor("first", setOf(custom), "#aBcDeF")
            preferences.reconcile("second", listOf(terminal, custom))
            val reopened = FeatureCardPreferences(file)
            reopened.reconcile("first", listOf(terminal))
            reopened.reconcile("first", listOf(terminal, custom, "extension/new"))
            assertEquals(FeatureCardLayout(listOf(custom, terminal, "extension/new"), setOf(custom), mapOf(custom to "#ABCDEF")), reopened.state.value["first"])
            assertEquals(FeatureCardLayout(listOf(terminal, custom)), reopened.state.value["second"])
            reopened.restore("first", setOf(custom))
            reopened.setColor("first", setOf(custom), null)
            val restored = FeatureCardPreferences(file).state.value.getValue("first")
            assertEquals(listOf(custom, terminal, "extension/new"), restored.order)
            assertEquals(emptySet(), restored.hidden)
            assertEquals(emptyMap(), restored.colors)
        } finally { root.deleteRecursively() }
    }

    @Test fun failedDiskCommitDoesNotPublishOrLosePreviouslySavedPreferences() = runBlocking {
        val root = Files.createTempDirectory("feature-card-save-failure-").toFile()
        try {
            val parent = File(root, "settings").apply { mkdir() }
            val preferences = FeatureCardPreferences(File(parent, "cards.json"))
            preferences.reconcile("container", listOf("builtin/files"))
            val before = preferences.state.value
            val preserved = File(root, "preserved")
            assertTrue(parent.renameTo(preserved))
            parent.writeText("not a directory")
            assertFails { preferences.hide("container", setOf("builtin/files")) }
            assertEquals(before, preferences.state.value)
            assertEquals(before, FeatureCardPreferences(File(preserved, "cards.json")).state.value)
            assertEquals("not a directory", parent.readText())
        } finally { root.deleteRecursively() }
    }
}
