package dev.wawlink.app

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.experimental.LazyApplication
import org.robolectric.annotation.experimental.LazyApplication.LazyLoad
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(
    application = WawLinkApplication::class,
    sdk = [34, 35, 36, 37],
    shadows = [WawLinkApplicationServiceTest.RefusingServiceContext::class],
)
@LooperMode(LooperMode.Mode.PAUSED)
@LazyApplication(LazyLoad.ON)
class WawLinkApplicationServiceTest {
    @get:Rule val main = ForegroundTestMain()
    private lateinit var application: WawLinkApplication
    private var activeWork: Job? = null

    @Before fun setUp() {
        RefusingServiceContext.resetServiceRequests()
        application = RuntimeEnvironment.getApplication() as WawLinkApplication
        shadowOf(application).grantPermissions(
            Manifest.permission.FOREGROUND_SERVICE,
            Manifest.permission.FOREGROUND_SERVICE_SPECIAL_USE,
        )
        // Notification visibility is not a prerequisite for starting the foreground service.
        shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        dispatchMain()
        assertEquals(0, RefusingServiceContext.startAttempts)
        assertTrue(shadowOf(application).allStartedServices.isEmpty())
        assertNoStopRequests()
    }

    @After fun tearDown() {
        ReflectionHelpers.getField<CoroutineScope>(application, "scope").cancel()
        ReflectionHelpers.getField<Handler>(application, "mainHandler").removeCallbacksAndMessages(null)
        runBlocking { activeWork?.cancelAndJoin() }
        application.coordinator.close()
    }

    @Test fun demandBurstsBeforeCreationRequestOneStartAndNeverStop() {
        repeat(6) {
            demand(true)
            demand(false)
        }
        demand(true)

        assertEquals(1, RefusingServiceContext.startAttempts)
        assertEquals(1, shadowOf(application).allStartedServices.size)
        assertEquals(
            ComponentName(application, ContainerForegroundService::class.java),
            shadowOf(application).allStartedServices.single().component,
        )
        assertNoStopRequests()
    }

    @Test fun demandBurstsWhileLiveDoNotSendStartOrStopIpc() {
        demand(true)
        application.onContainerServiceCreated(ContainerForegroundService())

        repeat(6) {
            demand(false)
            demand(true)
        }

        assertEquals(1, RefusingServiceContext.startAttempts)
        assertEquals(1, shadowOf(application).allStartedServices.size)
        assertNoStopRequests()
    }

    @Test fun revivedDemandDuringIdleTeardownPostsOneReplacement() {
        demand(true)
        val service = ContainerForegroundService()
        application.onContainerServiceCreated(service)
        demand(false)
        demand(true)

        application.onContainerServiceDestroyed(service, restartIfNeeded = true)
        assertEquals("Restart must leave onDestroy first", 1, RefusingServiceContext.startAttempts)
        dispatchMain()
        dispatchMain()

        assertEquals(2, RefusingServiceContext.startAttempts)
        assertEquals(2, shadowOf(application).allStartedServices.size)
        assertNoStopRequests()
    }

    @Test fun idleDestructionDoesNotRestart() {
        demand(true)
        val service = ContainerForegroundService()
        application.onContainerServiceCreated(service)
        demand(false)

        application.onContainerServiceDestroyed(service, restartIfNeeded = true)
        dispatchMain()

        assertEquals(1, RefusingServiceContext.startAttempts)
        assertNoStopRequests()
    }

    @Test fun permanentFailureDoesNotRestartWhileDemandRemains() {
        demand(true)
        val service = ContainerForegroundService()
        application.onContainerServiceCreated(service)

        application.onContainerServiceDestroyed(service, restartIfNeeded = false)
        repeat(3) { dispatchMain() }
        assertEquals(1, RefusingServiceContext.startAttempts)

        demand(false)
        demand(true)
        assertEquals("A later explicit demand must remain possible", 2, RefusingServiceContext.startAttempts)
        assertNoStopRequests()
    }

    @Test fun staleDestructionCannotClearTheCurrentInstance() {
        demand(true)
        val old = ContainerForegroundService()
        val current = ContainerForegroundService()
        application.onContainerServiceCreated(old)
        application.onContainerServiceCreated(current)

        application.onContainerServiceDestroyed(old, restartIfNeeded = true)
        demand(false)
        demand(true)
        assertEquals(1, RefusingServiceContext.startAttempts)

        application.onContainerServiceDestroyed(current, restartIfNeeded = true)
        dispatchMain()
        assertEquals(2, RefusingServiceContext.startAttempts)
        assertNoStopRequests()
    }

    @Test fun postedReplacementRechecksDemandBeforeRequestingStart() {
        demand(true)
        val service = ContainerForegroundService()
        application.onContainerServiceCreated(service)
        application.onContainerServiceDestroyed(service, restartIfNeeded = true)

        // Update the real coordinator without draining the already-posted restart runnable.
        demand(false, dispatch = false)
        dispatchMain()

        assertEquals(1, RefusingServiceContext.startAttempts)
        assertNoStopRequests()
    }

    @Test fun keepAliveDemandRetainsAndReplacesServiceWithoutContainerActivity() {
        application.coordinator.updateKeepAlive(KeepAliveSettings(enabled = true))
        awaitActive(true)
        runBlocking { withTimeout(5_000) { application.coordinator.busy.first { it == 0 } } }
        dispatchMain()
        val service = ContainerForegroundService()
        application.onContainerServiceCreated(service)

        application.onContainerServiceDestroyed(service, restartIfNeeded = true)
        dispatchMain()

        assertEquals(0, application.coordinator.busy.value)
        assertTrue(application.coordinator.runtime.containers.value.isEmpty())
        assertEquals(2, RefusingServiceContext.startAttempts)
        assertNoStopRequests()
    }

    @Test fun invalidBaselineIsReportedBeforeStartAndDoesNotLoop() {
        shadowOf(application).denyPermissions(Manifest.permission.FOREGROUND_SERVICE_SPECIAL_USE)
        demand(true)

        assertEquals(0, RefusingServiceContext.startAttempts)
        assertTrue(shadowOf(application).allStartedServices.isEmpty())
        val status = application.coordinator.keepAliveStatus.value
        assertFalse(status.serviceRunning)
        assertFalse(status.wakeLockHeld)
        assertFalse(status.wifiLockHeld)
        assertFalse(status.silentAudioPlaying)
        assertTrue(status.error.orEmpty().contains(Manifest.permission.FOREGROUND_SERVICE_SPECIAL_USE))
        assertErrorNoticeContains(Manifest.permission.FOREGROUND_SERVICE_SPECIAL_USE)
        repeat(3) { dispatchMain() }
        assertEquals(0, RefusingServiceContext.startAttempts)

        shadowOf(application).grantPermissions(Manifest.permission.FOREGROUND_SERVICE_SPECIAL_USE)
        demand(false)
        demand(true)
        await { RefusingServiceContext.startAttempts == 1 }
        assertNoStopRequests()
    }

    @Test fun startExceptionIsReportedAndDoesNotWedgeNextExplicitDemand() {
        RefusingServiceContext.nextFailure = SecurityException("framework denied foreground start")
        demand(true)

        assertEquals(1, RefusingServiceContext.startAttempts)
        assertTrue(shadowOf(application).allStartedServices.isEmpty())
        assertFalse(application.coordinator.keepAliveStatus.value.serviceRunning)
        assertTrue(application.coordinator.keepAliveStatus.value.error.orEmpty().contains("framework denied foreground start"))
        assertErrorNoticeContains("framework denied foreground start")
        repeat(3) { dispatchMain() }
        assertEquals(1, RefusingServiceContext.startAttempts)

        demand(false)
        demand(true)
        assertEquals(2, RefusingServiceContext.startAttempts)
        assertEquals(1, shadowOf(application).allStartedServices.size)
        assertNoStopRequests()
    }

    @Test fun nullStartRefusalDoesNotLeaveAnInFlightRequest() {
        RefusingServiceContext.returnNullOnce = true
        demand(true)

        assertEquals(1, RefusingServiceContext.startAttempts)
        assertTrue(shadowOf(application).allStartedServices.isEmpty())
        assertNotNull(application.coordinator.keepAliveStatus.value.error)
        assertErrorNoticeContains("Android 未启动容器前台服务")

        demand(false)
        demand(true)
        assertEquals(2, RefusingServiceContext.startAttempts)
        assertEquals(1, shadowOf(application).allStartedServices.size)
        assertNoStopRequests()
    }

    private fun demand(active: Boolean, dispatch: Boolean = true) = runBlocking {
        // Subscribe before changing the producer so its Main observer is notified before we resume.
        val changed = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(5_000) { application.coordinator.active.first { it == active } }
        }
        if (active && activeWork == null) {
            val started = CompletableDeferred<Unit>()
            activeWork = application.coordinator.launch { started.complete(Unit); awaitCancellation() }
            withTimeout(5_000) { started.await() }
        } else if (!active) {
            activeWork?.cancelAndJoin()
            activeWork = null
        }
        changed.await()
        if (dispatch) dispatchMain()
    }

    private fun awaitActive(active: Boolean) = runBlocking {
        withTimeout(5_000) { application.coordinator.active.first { it == active } }
    }

    private fun dispatchMain() = main.runCurrent()

    private fun await(condition: () -> Boolean) = runBlocking {
        withTimeout(5_000) {
            while (!condition()) {
                dispatchMain()
                delay(5)
            }
        }
    }

    private fun assertNoStopRequests() { assertNull(shadowOf(application).nextStoppedService) }

    private fun assertErrorNoticeContains(message: String) {
        val notice = runBlocking {
            withTimeout(5_000) { application.coordinator.notice.first { it != null }!! }
        }
        assertTrue(notice.error)
        assertTrue(notice.text.contains(message))
    }

    @Implements(className = "android.app.ContextImpl", isInAndroidSdk = false)
    class RefusingServiceContext : ForegroundTestContext() {
        @Implementation(minSdk = 26)
        override fun startForegroundService(service: Intent): ComponentName? {
            startAttempts++
            nextFailure?.let {
                nextFailure = null
                throw it
            }
            if (returnNullOnce) {
                returnNullOnce = false
                return null
            }
            return super.startForegroundService(service)
        }

        companion object {
            var startAttempts = 0
            var nextFailure: RuntimeException? = null
            var returnNullOnce = false

            @JvmStatic fun resetServiceRequests() {
                startAttempts = 0
                nextFailure = null
                returnNullOnce = false
            }
        }
    }
}
