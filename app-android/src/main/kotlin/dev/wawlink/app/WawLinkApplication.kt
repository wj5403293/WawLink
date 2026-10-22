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
    private var initialized = false
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
    fun initializeBundledEnvironment() {
        if (initialized) return
        initialized = true
        if (coordinator.runtime.containers.value.isNotEmpty()) return
        coordinator.launch {
            val archive = File(cacheDir, "ubuntu-main-debug.tar.zst")
            try {
                coordinator.setStartup("正在读取 APK 内的 Ubuntu 开发环境")
                val asset = assets.list("").orEmpty().firstOrNull { it == "ubuntu-main-debug.tar.zst" }
                    ?: error("此安装包未包含预置 Rootfs。请使用包含 ubuntu-main-debug.tar.zst 的 Debug APK，或在容器页手动导入 rootfs。")
                assets.open(asset).use { input ->
                    archive.outputStream().use { output ->
                        val buffer = ByteArray(1024 * 1024)
                        var total = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            total += count
                            coordinator.setStartup("正在复制预置归档：${bytesLabel(total)}")
                        }
                    }
                }
                coordinator.importBundledRootfs(archive)
            } finally {
                coordinator.setStartup(null)
                archive.delete()
            }
        }
    }
}
