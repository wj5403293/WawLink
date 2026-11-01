package dev.wawlink.app

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.experimental.LazyApplication
import org.robolectric.annotation.experimental.LazyApplication.LazyLoad
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowService
import org.robolectric.util.ReflectionHelpers

/** Production lifecycle code, with system-server decisions and Main time controlled by fixtures. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 35, 36, 37], application = WawLinkApplication::class,
    shadows = [ForegroundContractShadow::class, ForegroundTestContext::class, ForegroundTestWifiManager::class])
@LooperMode(LooperMode.Mode.PAUSED)
@LazyApplication(LazyLoad.ON)
class ContainerForegroundServiceTest {
    @get:Rule val main = ForegroundTestMain()
    private lateinit var app: WawLinkApplication
    private var controller: ServiceController<ContainerForegroundService>? = null
    private var work: Job? = null

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication() as WawLinkApplication
        shadowOf(app as Application).grantPermissions(
            Manifest.permission.FOREGROUND_SERVICE, Manifest.permission.FOREGROUND_SERVICE_SPECIAL_USE,
            Manifest.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK, Manifest.permission.WAKE_LOCK,
            Manifest.permission.ACCESS_WIFI_STATE, Manifest.permission.CHANGE_WIFI_STATE,
        )
        main.runCurrent()
    }

    @After fun tearDown() {
        ReflectionHelpers.getField<CoroutineScope>(app, "scope").cancel()
        ReflectionHelpers.getField<Handler>(app, "mainHandler").removeCallbacksAndMessages(null)
        work?.cancel()
        controller?.destroy()
        app.coordinator.close()
    }

    private fun create(): ContainerForegroundService {
        controller = Robolectric.buildService(ContainerForegroundService::class.java)
        return controller!!.create().get()
    }

    private fun await(condition: () -> Boolean) = runBlocking {
        withTimeout(5_000) {
            while (!condition()) {
                main.runCurrent()
                delay(5)
            }
        }
    }

    private fun idle(millis: Long) = main.idle(millis)
    private fun state() = app.coordinator.keepAliveStatus.value
    private fun system(service: Service): ForegroundContractShadow = Shadow.extract(service)
    private fun start(service: ContainerForegroundService, id: Int = 1) = service.onStartCommand(Intent(app, service.javaClass), 0, id)

    @Test fun idleCreatePromotesButCannotStopBeforeTheFirstStartCommand() {
        val service = create()
        val system = system(service)
        idle(600)
        assertFalse(system.isStoppedBySelf)
        assertNotNull(system.lastForegroundNotification)
        assertEquals(listOf(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE), system.acceptedTypes)
        assertTrue(state().serviceRunning)

        assertEquals(Service.START_NOT_STICKY, start(service, 17))
        idle(299)
        assertFalse(system.isStoppedBySelf)
        idle(2)
        assertTrue(system.isStoppedBySelf)
        assertEquals(17, system.stopSelfResultId)
        assertTrue(system.isForegroundStopped)
        assertNull(system.lastForegroundNotification)
        assertFalse(state().serviceRunning)
    }

    @Test fun commandAfterForegroundRemovalAlwaysReestablishesItsOwnHandshake() {
        val service = create()
        start(service)
        idle(301)
        assertTrue(system(service).isForegroundStopped)
        app.coordinator.updateKeepAlive(KeepAliveSettings(enabled = true))
        await { app.coordinator.active.value }

        assertEquals(Service.START_STICKY, start(service, 2))
        assertFalse(system(service).isForegroundStopped)
        assertNotNull(system(service).lastForegroundNotification)
        assertTrue(state().serviceRunning)
        idle(600)
        assertFalse(system(service).isForegroundStopped)
    }

    @Test fun pendingNewerSystemStartCannotLoseTheExistingForegroundNotification() {
        val service = create()
        val system = system(service)
        system.acceptStop = false // AMS already has a newer startId that the app hasn't received.
        start(service, 1)
        idle(301)
        assertFalse(system.isStoppedBySelf)
        assertFalse(system.isForegroundStopped)
        assertNotNull(system.lastForegroundNotification)
        assertTrue(state().serviceRunning)

        app.coordinator.updateKeepAlive(KeepAliveSettings(enabled = true))
        await { app.coordinator.active.value }
        start(service, 2)
        system.acceptStop = true
        app.coordinator.updateKeepAlive(KeepAliveSettings())
        await { !app.coordinator.active.value }
        idle(301)
        await { idle(50); system.isStoppedBySelf }
        assertEquals(2, system.stopSelfResultId)
        assertNull(system.lastForegroundNotification)
    }

    @Test fun rejectedOptionalMediaTypeKeepsBaseServiceAndNeverStartsAudio() {
        app.coordinator.updateKeepAlive(KeepAliveSettings(enabled = true, wakeLock = true, silentAudio = true))
        await { app.coordinator.active.value }
        val service = create()
        val system = system(service)
        system.rejectMedia = true
        assertFalse(state().wakeLockHeld) // No optional resources in onCreate.
        assertEquals(Service.START_STICKY, start(service))
        assertTrue(state().serviceRunning)
        assertTrue(state().wakeLockHeld)
        assertFalse(state().silentAudioPlaying)
        assertTrue(state().error.orEmpty().contains("mediaPlayback rejected"))
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, system.acceptedTypes.last())
        assertFalse(system.attemptedTypes.contains(0))
        assertFalse(system.isStoppedBySelf)

        app.coordinator.updateKeepAlive(KeepAliveSettings(enabled = true, wakeLock = true))
        start(service, 2)
        assertNull(state().error)
        assertTrue(state().serviceRunning)
    }

    @Test fun permanentPromotionRefusalDoesNotAcquireResourcesOrReturnStickySuccess() {
        app.coordinator.updateKeepAlive(KeepAliveSettings(enabled = true, wakeLock = true, wifiLock = true, silentAudio = true))
        await { app.coordinator.active.value }
        controller = Robolectric.buildService(ContainerForegroundService::class.java)
        val service = controller!!.get()
        system(service).setThrowInStartForeground(SecurityException("specialUse rejected"))
        controller!!.create()
        assertFalse(system(service).isStoppedBySelf)
        assertEquals(Service.START_NOT_STICKY, start(service, 31))
        assertTrue(system(service).isStoppedBySelf)
        assertEquals(31, system(service).stopSelfResultId)
        assertFalse(state().serviceRunning)
        assertFalse(state().wakeLockHeld)
        assertFalse(state().wifiLockHeld)
        assertFalse(state().silentAudioPlaying)
        assertTrue(state().error.orEmpty().contains("specialUse rejected"))
    }

    @Test fun disablingKeepAliveReleasesOptionalLocksWithoutStoppingActiveWork() {
        val finish = CompletableDeferred<Unit>()
        work = app.coordinator.launch { finish.await() }
        await { app.coordinator.active.value }
        app.coordinator.updateKeepAlive(KeepAliveSettings(enabled = true, wakeLock = true, wifiLock = true))
        val service = create()
        start(service)
        assertTrue(state().wakeLockHeld)
        assertTrue(state().wifiLockHeld)

        app.coordinator.updateKeepAlive(KeepAliveSettings())
        await { !state().wakeLockHeld && !state().wifiLockHeld }
        idle(600)
        assertTrue(work!!.isActive)
        assertTrue(state().serviceRunning)
        assertFalse(system(service).isStoppedBySelf)
        assertEquals(Service.START_NOT_STICKY, start(service, 2))

        finish.complete(Unit)
        await { !app.coordinator.active.value }
        idle(301)
        await { idle(50); system(service).isStoppedBySelf }
    }

    @Test fun freshStartCannotHideRapidKeepAliveDisableFromObserver() {
        val finish = CompletableDeferred<Unit>()
        work = app.coordinator.launch { finish.await() }
        await { app.coordinator.active.value }
        app.coordinator.updateKeepAlive(KeepAliveSettings(enabled = true, wakeLock = true))
        val service = create()
        start(service)
        main.runCurrent()
        app.coordinator.updateKeepAlive(KeepAliveSettings())
        main.runCurrent()
        assertFalse(state().wakeLockHeld)

        // No observer turn between the new command's acquisition and disabling the setting again.
        app.coordinator.updateKeepAlive(KeepAliveSettings(enabled = true, wakeLock = true))
        start(service, 2)
        assertTrue(state().wakeLockHeld)
        app.coordinator.updateKeepAlive(KeepAliveSettings())
        main.runCurrent()

        assertFalse(state().wakeLockHeld)
        assertTrue(work!!.isActive)
        assertTrue(state().serviceRunning)
        assertFalse(system(service).isStoppedBySelf)
    }

    @Test fun deniedNotificationPermissionDoesNotPreventLegalForegroundService() {
        shadowOf(app as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ContainerForegroundService.validateStartConfiguration(app)
        val service = create()
        start(service)
        assertTrue(state().serviceRunning)
        assertNotNull(system(service).lastForegroundNotification)
    }

    @Test
    @Config(shadows = [ForegroundContractShadow::class, ForegroundTestContext::class, ForegroundTestWifiManager::class, RejectedServicePendingIntent::class])
    fun notificationSetupFailureUsesARealMinimalNotificationBeforeIdleExit() {
        val service = create()
        assertNotNull(system(service).lastForegroundNotification)
        assertNull(system(service).lastForegroundNotification.contentIntent)
        assertTrue(state().serviceRunning)
        assertTrue(state().error.orEmpty().contains("pending intent rejected"))
        assertFalse(system(service).isStoppedBySelf)
        start(service, 7)
        idle(301)
        assertTrue(system(service).isStoppedBySelf)
        assertEquals(7, system(service).stopSelfResultId)
    }

    @Test
    @Config(sdk = [26])
    fun legacyAndroidAcknowledgesAndStopsWithoutNewerServiceTypeBits() {
        assertTrue(Build.VERSION.SDK_INT < 29)
        val service = create()
        assertEquals(listOf(0), system(service).acceptedTypes)
        start(service, 6)
        idle(301)
        assertEquals(6, system(service).stopSelfResultId)
        assertNull(system(service).lastForegroundNotification)
    }
}

@Implements(Service::class)
class ForegroundContractShadow : ShadowService() {
    val attemptedTypes = mutableListOf<Int>()
    val acceptedTypes = mutableListOf<Int>()
    var rejectMedia = false
    var acceptStop = true
    private var typedCall = false

    @Implementation
    public override fun startForeground(id: Int, notification: Notification) {
        if (!typedCall) attemptedTypes += 0
        super.startForeground(id, notification)
        if (!typedCall) acceptedTypes += 0
    }

    @Implementation(minSdk = 29)
    public override fun startForeground(id: Int, notification: Notification, foregroundServiceType: Int) {
        attemptedTypes += foregroundServiceType
        if (rejectMedia && foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK != 0) {
            throw SecurityException("mediaPlayback rejected")
        }
        typedCall = true
        try {
            super.startForeground(id, notification, foregroundServiceType)
            acceptedTypes += foregroundServiceType
        } finally { typedCall = false }
    }

    @Implementation
    public override fun stopSelfResult(id: Int): Boolean = acceptStop && super.stopSelfResult(id)
}

@Implements(PendingIntent::class)
class RejectedServicePendingIntent {
    companion object {
        @JvmStatic
        @Implementation
        fun getActivity(context: Context, requestCode: Int, intent: Intent, flags: Int): PendingIntent {
            throw IllegalStateException("pending intent rejected")
        }
    }
}
