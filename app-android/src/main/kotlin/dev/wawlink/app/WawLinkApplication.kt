package dev.wawlink.app

import android.app.Application
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import androidx.core.content.ContextCompat
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
        val runtime = LocalContainerRuntime(RuntimePaths(data, nativeLibraryDir = File(applicationInfo.nativeLibraryDir), isAndroid = true, resolvConf = resolvConf))
        coordinator = AppCoordinator(runtime, OmpProcessPool(runtime, data, resolvConf = resolvConf), data, isAndroid = true)
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
