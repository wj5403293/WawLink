package dev.wawlink.app

import dev.wawlink.driver.OmpProcessPool
import dev.wawlink.protocol.AgentAppearance
import dev.wawlink.protocol.AgentColorSource
import dev.wawlink.runtime.ContainerInfo
import dev.wawlink.runtime.ContainerRuntime
import dev.wawlink.runtime.DeploymentProgress
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class AppCoordinatorAppearanceTest {
    private class Fixture(properties: Properties) : AutoCloseable {
        val directory = Files.createTempDirectory("wawlink-appearance-").toFile()
        val preferences = File(directory, "appearance.properties")
        private val containers = MutableStateFlow<List<ContainerInfo>>(emptyList())
        private val deployments = MutableStateFlow<List<DeploymentProgress>>(emptyList())
        private val runtime = Proxy.newProxyInstance(ContainerRuntime::class.java.classLoader, arrayOf(ContainerRuntime::class.java)) { _, method, _ ->
            when (method.name) {
                "getContainers" -> containers
                "getDeployments" -> deployments
                "close" -> Unit
                else -> error("Unexpected engine API: ${method.name}")
            }
        } as ContainerRuntime

        init { preferences.outputStream().use { properties.store(it, null) } }
        fun open() = AppCoordinator(runtime, OmpProcessPool(runtime, directory), directory)
        suspend fun awaitSaved(key: String, value: String) = withTimeout(5_000) {
            while (runCatching { Properties().apply { preferences.inputStream().use(::load) }.getProperty(key) }.getOrNull() != value) delay(10)
        }
        override fun close() { directory.deleteRecursively() }
    }

    @Test fun legacyZoomIsRebasedOnceAndSurvivesUnrelatedPreferenceSaves() = runBlocking {
        for ((legacy, rebased) in listOf(115 to 100, 125 to 109, 100 to 100)) {
            Fixture(Properties().apply { setProperty("uiScalePercent", legacy.toString()) }).use { fixture ->
                fixture.open().use { app ->
                    assertEquals(rebased, app.appearance.value.effectiveUiScalePercent)
                    app.updateAppearance(app.appearance.value.copy(darkTheme = true))
                    fixture.awaitSaved("dark", "true")
                }
                fixture.open().use { app ->
                    assertEquals(rebased, app.appearance.value.effectiveUiScalePercent)
                    assertTrue(app.appearance.value.darkTheme)
                    app.updateDefaultAutoApprove(false)
                    fixture.awaitSaved("defaultAutoApprove", "false")
                }
                fixture.open().use { app ->
                    assertEquals(rebased, app.appearance.value.effectiveUiScalePercent)
                    assertFalse(app.defaultAutoApprove.value)
                }
            }
        }
    }

    @Test fun rebasedZoomCanBeAdjustedAndResetWithoutChangingOtherPreferences() = runBlocking {
        Fixture(Properties().apply {
            setProperty("uiScalePercent", "120")
            setProperty("uiScaleBaseline", "115")
            setProperty("defaultAutoApprove", "false")
        }).use { fixture ->
            fixture.open().use { app ->
                assertEquals(120, app.appearance.value.effectiveUiScalePercent)
                assertEquals(1.38f, app.appearance.value.densityScale, .0001f)
                app.updateAppearance(app.appearance.value.copy(uiScalePercent = 90))
                fixture.awaitSaved("uiScalePercent", "90")
            }
            fixture.open().use { app ->
                assertEquals(90, app.appearance.value.effectiveUiScalePercent)
                assertFalse(app.defaultAutoApprove.value)
                app.updateAppearance(app.appearance.value.copy(uiScalePercent = AgentAppearance.DEFAULT_UI_SCALE_PERCENT))
                fixture.awaitSaved("uiScalePercent", "100")
            }
            fixture.open().use { app ->
                assertEquals(100, app.appearance.value.effectiveUiScalePercent)
                assertEquals(1.15f, app.appearance.value.densityScale, .0001f)
                assertFalse(app.defaultAutoApprove.value)
            }
        }
    }

    @Test fun customSeedSurvivesSourceChangesAndUnrelatedPreferenceSaves() = runBlocking {
        Fixture(Properties()).use { fixture ->
            fixture.open().use { app ->
                app.updateAppearance(app.appearance.value.copy(colorSource = AgentColorSource.CUSTOM, customColor = 0xFF167D45L))
                fixture.awaitSaved("customColor", "${0xFF167D45L}")
                app.updateDefaultAutoApprove(false)
                fixture.awaitSaved("defaultAutoApprove", "false")
            }
            fixture.open().use { app ->
                assertEquals(AgentColorSource.CUSTOM, app.appearance.value.colorSource)
                assertEquals(0xFF167D45L, app.appearance.value.customColor)
                assertFalse(app.defaultAutoApprove.value)
                app.updateAppearance(app.appearance.value.copy(colorSource = AgentColorSource.SYSTEM))
                fixture.awaitSaved("colorSource", "SYSTEM")
            }
            fixture.open().use { app ->
                assertEquals(AgentColorSource.SYSTEM, app.appearance.value.colorSource)
                assertEquals(0xFF167D45L, app.appearance.value.customColor)
            }
        }
    }

    @Test fun keepAliveSettingsSurviveReopenAndUnrelatedPreferenceChanges() = runBlocking {
        val enabled = KeepAliveSettings(enabled = true, wakeLock = true, wifiLock = false, silentAudio = true)
        val disabled = KeepAliveSettings(enabled = false, wakeLock = false, wifiLock = true, silentAudio = false)
        Fixture(Properties()).use { fixture ->
            fixture.open().use { app ->
                assertEquals(KeepAliveSettings(), app.keepAlive.value)
                app.updateKeepAlive(enabled)
                fixture.awaitSaved("keepAlive.enabled", "true")
                app.updateKeepAliveStatus { it.copy(serviceRunning = true, wakeLockHeld = true) }
            }
            fixture.open().use { app ->
                assertEquals(enabled, app.keepAlive.value)
                assertEquals(KeepAliveStatus(), app.keepAliveStatus.value)
                app.updateDefaultAutoApprove(false)
                fixture.awaitSaved("defaultAutoApprove", "false")
                app.updateKeepAlive(disabled)
                fixture.awaitSaved("keepAlive.wifiLock", "true")
            }
            fixture.open().use { app ->
                assertEquals(disabled, app.keepAlive.value)
                assertFalse(app.defaultAutoApprove.value)
                app.updateAppearance(app.appearance.value.copy(darkTheme = true))
                fixture.awaitSaved("dark", "true")
            }
            fixture.open().use { app ->
                assertEquals(disabled, app.keepAlive.value)
                assertTrue(app.appearance.value.darkTheme)
                assertFalse(app.defaultAutoApprove.value)
            }
        }
    }
}
