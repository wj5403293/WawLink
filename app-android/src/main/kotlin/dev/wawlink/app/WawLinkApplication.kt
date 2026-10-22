package dev.wawlink.app

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import dev.wawlink.driver.OmpProcessPool
import dev.wawlink.runtime.LocalContainerRuntime
import dev.wawlink.runtime.RuntimePaths
import java.io.File
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.*

class WawLinkApplication : Application() {
    lateinit var coordinator: AppCoordinator
        private set
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    override fun onCreate() {
        super.onCreate()
        val data = File(filesDir, "wawlink")
        val runtime = LocalContainerRuntime(RuntimePaths(data, nativeLibraryDir = File(applicationInfo.nativeLibraryDir), isAndroid = true))
        coordinator = AppCoordinator(runtime, OmpProcessPool(runtime, data), data, isAndroid = true)
        scope.launch {
            coordinator.active.collect { active ->
                try {
                    if (active) ContextCompat.startForegroundService(this@WawLinkApplication, Intent(this@WawLinkApplication, ContainerForegroundService::class.java))
                    else stopService(Intent(this@WawLinkApplication, ContainerForegroundService::class.java))
                } catch (e: Exception) { coordinator.showError(e) }
            }
        }
    }
}
