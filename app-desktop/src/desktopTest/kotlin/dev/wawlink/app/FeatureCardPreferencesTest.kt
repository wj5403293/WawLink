package dev.wawlink.app

import dev.wawlink.runtime.ContainerConfig
import dev.wawlink.runtime.ContainerInfo
import dev.wawlink.runtime.ContainerRuntime
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class FeatureCardPreferencesTest {
    /** Runs the production guest scripts with an isolated HOME, not an engine or host-rootfs fixture. */
    private class Fixture : AutoCloseable {
        val root = Files.createTempDirectory("feature-card-preferences-").toFile()
        val launches = AtomicInteger()
        var beforeLaunch: ((String) -> Unit)? = null
        val containers = MutableStateFlow(listOf("first", "second", "container", "original-id", "new-id").map {
            ContainerInfo(ContainerConfig(it, it, root.resolve(it).path))
        })
        val runtime = Proxy.newProxyInstance(ContainerRuntime::class.java.classLoader, arrayOf(ContainerRuntime::class.java)) { _, method, arguments ->
            if (method.name == "getContainers") return@newProxyInstance containers
            check(method.name == "openProcess" || method.name == "openRunningProcess") { "Unexpected runtime API: ${method.name}" }
            val id = arguments[0] as String
            check(arguments[2] == "/")
            @Suppress("UNCHECKED_CAST")
            val command = arguments[1] as List<String>
            @Suppress("UNCHECKED_CAST")
            val processEnvironment = arguments[3] as Map<String, String>
            launches.incrementAndGet()
            beforeLaunch?.invoke(id)
            ProcessBuilder(command).apply {
                directory(root)
                environment().putAll(processEnvironment)
                environment()["HOME"] = home(id).absolutePath
            }.start()
        } as ContainerRuntime

        fun home(id: String): File = File(root, "homes/$id").apply { mkdirs() }
        fun document(id: String): File = File(home(id), ".wawlink/feature-cards.json")
        fun writeDocument(id: String, contents: String) {
            document(id).apply { parentFile.mkdirs(); writeText(contents, Charsets.UTF_8) }
        }

        override fun close() { root.deleteRecursively() }
    }

    @Test fun constructorIsLazyAndDoesNotLaunchGuestProcesses() {
        Fixture().use { fixture ->
            val preferences = FeatureCardPreferences(fixture.runtime)
            assertEquals(0, fixture.launches.get())
            assertEquals(emptyMap(), preferences.state.value)
            assertNull(preferences.error.value)
            assertFalse(File(fixture.root, "homes").exists())
        }
    }

    @Test fun keepsCustomizationWhenCardsDisappearAndReturnWithoutLeakingBetweenContainers() = runBlocking {
        Fixture().use { fixture ->
            val preferences = FeatureCardPreferences(fixture.runtime)
            val terminal = "builtin/terminal"
            val custom = "extension/terminal"
            preferences.load("first")
            preferences.reconcile("first", listOf(terminal, custom))
            preferences.reorder("first", listOf(custom, terminal))
            preferences.hide("first", setOf(custom))
            preferences.setColor("first", setOf(custom), "#aBcDeF")
            preferences.load("second")
            preferences.reconcile("second", listOf(terminal, custom))

            val reopened = FeatureCardPreferences(fixture.runtime)
            reopened.load("first")
            reopened.load("second")
            reopened.reconcile("first", listOf(terminal))
            reopened.reconcile("first", listOf(terminal, custom, "extension/new"))
            assertEquals(FeatureCardLayout(listOf(custom, terminal, "extension/new"), setOf(custom), mapOf(custom to "#ABCDEF")), reopened.state.value["first"])
            assertEquals(FeatureCardLayout(listOf(terminal, custom)), reopened.state.value["second"])
            reopened.restore("first", setOf(custom))
            reopened.setColor("first", setOf(custom), null)

            val restoredPreferences = FeatureCardPreferences(fixture.runtime)
            restoredPreferences.load("first")
            restoredPreferences.load("second")
            val restored = restoredPreferences.state.value.getValue("first")
            assertEquals(listOf(custom, terminal, "extension/new"), restored.order)
            assertEquals(emptySet(), restored.hidden)
            assertEquals(emptyMap(), restored.colors)
            assertEquals(FeatureCardLayout(listOf(terminal, custom)), restoredPreferences.state.value["second"])
        }
    }

    @Test fun failedGuestCommitDoesNotPublishOrLosePreviouslySavedPreferences() = runBlocking {
        Fixture().use { fixture ->
            val preferences = FeatureCardPreferences(fixture.runtime)
            preferences.reconcile("container", listOf("builtin/files"))
            val before = preferences.state.value
            val savedDocument = fixture.document("container").readText()
            val parent = fixture.document("container").parentFile
            val preserved = File(fixture.root, "preserved")
            var launches = 0
            fixture.beforeLaunch = { id ->
                assertEquals("container", id)
                if (++launches == 2) {
                    // The mutation has reread valid data; make its actual commit fail.
                    assertTrue(parent.renameTo(preserved))
                    parent.writeText("not a directory")
                }
            }

            assertFails { preferences.hide("container", setOf("builtin/files")) }
            fixture.beforeLaunch = null
            assertEquals(2, launches)
            assertEquals(before, preferences.state.value)
            assertNotNull(preferences.error.value)
            assertEquals(savedDocument, File(preserved, "feature-cards.json").readText())
            assertEquals("not a directory", parent.readText())

            assertTrue(parent.delete())
            assertTrue(preserved.renameTo(parent))
            val reopened = FeatureCardPreferences(fixture.runtime)
            reopened.load("container")
            assertEquals(before, reopened.state.value)
        }
    }

    @Test fun stoppedContainerCancelsPreferenceSaveWithoutPublishingErrorOrOverwriting() = runBlocking {
        Fixture().use { fixture ->
            val preferences = FeatureCardPreferences(fixture.runtime)
            preferences.reconcile("container", listOf("builtin/files"))
            val before = preferences.state.value
            val document = fixture.document("container").readText()
            val launches = fixture.launches.get()
            fixture.beforeLaunch = { id ->
                fixture.containers.value = fixture.containers.value.map {
                    if (it.config.id == id) it.copy(stopGeneration = it.stopGeneration + 1) else it
                }
            }
            assertFailsWith<CancellationException> { preferences.hide("container", setOf("builtin/files")) }
            assertEquals(launches + 1, fixture.launches.get(), "Interrupted read must not start a subsequent write")
            assertEquals(before, preferences.state.value)
            assertEquals(document, fixture.document("container").readText())
            assertNull(preferences.error.value)
            fixture.beforeLaunch = null
            preferences.hide("container", setOf("builtin/files"))
            assertEquals(setOf("builtin/files"), preferences.state.value.getValue("container").hidden)
        }
    }

    @Test fun copiedGuestDataLoadsUnderNewContainerIdWithoutHostSettings() = runBlocking {
        Fixture().use { source ->
            val preferences = FeatureCardPreferences(source.runtime)
            val terminal = "builtin/terminal"
            val custom = "extension/copied"
            preferences.reconcile("original-id", listOf(terminal, custom))
            preferences.reorder("original-id", listOf(custom, terminal))
            preferences.hide("original-id", setOf(custom))
            preferences.setColor("original-id", setOf(custom), "#123ABC")
            val expected = preferences.state.value.getValue("original-id")
            val document = source.document("original-id").readText()
            assertEquals(setOf("order", "hidden", "colors"), Json.parseToJsonElement(document).jsonObject.keys)
            assertFalse(document.contains("original-id"))

            // A distinct runtime/home root has only copied guest data, never the source's host settings.
            Fixture().use { imported ->
                assertTrue(source.home("original-id").copyRecursively(imported.home("new-id")))
                val reopened = FeatureCardPreferences(imported.runtime)
                assertEquals(emptyMap(), reopened.state.value)
                reopened.load("new-id")
                assertEquals(mapOf("new-id" to expected), reopened.state.value)
                assertEquals(document, imported.document("new-id").readText())
                assertFalse(File(imported.root, "homes/original-id").exists())
            }
        }
    }

    @Test fun malformedGuestDocumentIsReportedAndNeverOverwritten() = runBlocking {
        Fixture().use { fixture ->
            val preferences = FeatureCardPreferences(fixture.runtime)
            preferences.reconcile("container", listOf("builtin/files"))
            val before = preferences.state.value
            val malformed = """{"order":["builtin/files"],"hidden":[],"colors": """
            fixture.writeDocument("container", malformed)

            assertFails { preferences.load("container") }
            assertEquals(before, preferences.state.value)
            assertNotNull(preferences.error.value)
            assertEquals(malformed, fixture.document("container").readText())
            assertFails { preferences.hide("container", setOf("builtin/files")) }
            assertEquals(before, preferences.state.value)
            assertEquals(malformed, fixture.document("container").readText())

            val reopened = FeatureCardPreferences(fixture.runtime)
            assertFails { reopened.load("container") }
            assertEquals(emptyMap(), reopened.state.value)
            assertNotNull(reopened.error.value)
            assertEquals(malformed, fixture.document("container").readText())
        }
    }

    @Test fun mutationPreservesFreshExternalOrderVisibilityAndColors() = runBlocking {
        Fixture().use { fixture ->
            val preferences = FeatureCardPreferences(fixture.runtime)
            val terminal = "builtin/terminal"
            val custom = "extension/terminal"
            preferences.reconcile("container", listOf(terminal, custom))
            fixture.writeDocument("container", """{"order":["$custom","$terminal"],"hidden":["$custom"],"colors":{"$custom":"#123ABC"}}""")

            preferences.hide("container", setOf(terminal))

            val expected = FeatureCardLayout(listOf(custom, terminal), setOf(custom, terminal), mapOf(custom to "#123ABC"))
            assertEquals(expected, preferences.state.value["container"])
            val reopened = FeatureCardPreferences(fixture.runtime)
            reopened.load("container")
            assertEquals(expected, reopened.state.value["container"])
        }
    }
}
