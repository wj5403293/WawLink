package dev.wawlink.app

import android.app.Application
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.Environment
import android.os.Handler
import android.os.Looper
import dev.wawlink.driver.OmpProcessPool
import dev.wawlink.runtime.LocalContainerRuntime
import dev.wawlink.runtime.RuntimePaths
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.*

class WawLinkApplication : Application() {
    lateinit var coordinator: AppCoordinator
        private set
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var containerService: ContainerForegroundService? = null
    private var containerServiceStartPending = false
    override fun onCreate() {
        super.onCreate()
        val data = File(filesDir, "wawlink")
        val connectivity = getSystemService(ConnectivityManager::class.java)
        fun resolver(properties: LinkProperties?): String? {
            val servers = properties?.dnsServers.orEmpty()
            return servers.takeIf { it.isNotEmpty() }?.joinToString("") { "nameserver ${it.hostAddress}\n" }
        }
        val resolvConf = MutableStateFlow(resolver(connectivity.activeNetwork?.let(connectivity::getLinkProperties)))
        connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            private var current: Network? = null
            override fun onAvailable(network: Network) { current = network }
            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
                if (network == current) resolvConf.value = resolver(properties)
            }
            override fun onLost(network: Network) {
                if (network == current) { current = null; resolvConf.value = null }
            }
        })
        val runtime = LocalContainerRuntime(RuntimePaths(data, nativeLibraryDir = File(applicationInfo.nativeLibraryDir), isAndroid = true,
            resolvConf = resolvConf, primaryExternalStorage = Environment.getExternalStorageDirectory()))
        coordinator = AppCoordinator(runtime, OmpProcessPool(runtime, data, resolvConf = resolvConf), data, isAndroid = true)
        scope.launch {
            // AppCoordinator.active already includes Android keep-alive demand.
            coordinator.active.collect { needed ->
                if (needed) startContainerServiceIfNeeded()
            }
        }
    }

    internal fun onContainerServiceCreated(service: ContainerForegroundService) {
        containerService = service
        containerServiceStartPending = false
    }

    internal fun onContainerServiceDestroyed(service: ContainerForegroundService, restartIfNeeded: Boolean) {
        if (containerService !== service) return
        containerService = null
        if (restartIfNeeded && wantsContainerService()) {
            // Leave onDestroy before requesting a replacement, then re-read the latest demand.
            mainHandler.post { startContainerServiceIfNeeded() }
        }
    }

    private fun wantsContainerService(): Boolean = coordinator.active.value || coordinator.keepAlive.value.enabled

    private fun startContainerServiceIfNeeded() {
        if (!wantsContainerService() || containerServiceStartPending || containerService != null) return
        containerServiceStartPending = true
        try {
            ContainerForegroundService.validateStartConfiguration(this)
            checkNotNull(startForegroundService(Intent(this, ContainerForegroundService::class.java))) {
                "Android 未启动容器前台服务"
            }
        } catch (error: Exception) {
            containerServiceStartPending = false
            coordinator.showError(error)
            coordinator.updateKeepAliveStatus {
                it.copy(serviceRunning = false, wakeLockHeld = false, wifiLockHeld = false, silentAudioPlaying = false,
                    error = "前台服务：${error.message ?: error.javaClass.simpleName}")
            }
        }
        // Idle shutdown belongs to the Service; a stop IPC could cancel an unpromoted start.
    }
}
