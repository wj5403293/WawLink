package dev.wawlink.app

import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.wifi.WifiManager
import android.os.Looper
import java.io.File
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowContextImpl

/** Robolectric has no installed APK/native directory; no guest executable is supplied or invoked. */
@Implements(className = "android.app.ContextImpl", isInAndroidSdk = false)
open class ForegroundTestContext : ShadowContextImpl() {
    @RealObject private lateinit var context: Context

    @Implementation
    fun getApplicationInfo(): ApplicationInfo {
        val info = Shadow.directlyOn<ApplicationInfo>(context, "android.app.ContextImpl", "getApplicationInfo")
        if (info.nativeLibraryDir == null) {
            info.nativeLibraryDir = File(info.dataDir, "test-native-libs").apply { mkdirs() }.absolutePath
        }
        return info
    }
}

/** Robolectric 4.17's manager shadow links newer BlockingOption even on API 34.
 * Keep its existing WifiLock state model without loading that unrelated manager signature. */
@Implements(WifiManager::class)
class ForegroundTestWifiManager {
    @Implementation
    fun createWifiLock(lockType: Int, tag: String): WifiManager.WifiLock =
        Shadow.newInstanceOf(WifiManager.WifiLock::class.java)
}

/** Install before lazy Application creation; keep coroutine time and Android message time explicit. */
@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundTestMain : TestWatcher() {
    private val scheduler = TestCoroutineScheduler()

    override fun starting(description: Description) {
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
    }

    override fun finished(description: Description) {
        try { runCurrent() } finally { Dispatchers.resetMain() }
    }

    fun runCurrent() {
        scheduler.runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
        scheduler.runCurrent()
    }

    fun idle(millis: Long) {
        scheduler.advanceTimeBy(millis)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))
        runCurrent()
    }
}
