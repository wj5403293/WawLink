package dev.wawlink.driver

import dev.wawlink.runtime.ContainerRuntime
import dev.wawlink.runtime.wasInterrupted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout

enum class OmpInstallationState { READY, MISSING, BROKEN }
data class OmpInstallationCheck(val state: OmpInstallationState, val message: String)

/** Installation health only: the guest probe uses an empty temporary HOME, not user settings or credentials. */
object OmpInstallation {
    private val script by lazy {
        checkNotNull(javaClass.getResourceAsStream("/omp-install.sh")) { "缺少打包的 OMP 安装修复脚本" }
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    fun command(): String = script

    suspend fun inspect(
        runtime: ContainerRuntime,
        containerId: String,
        stopGeneration: Long = runtime.containers.value.firstOrNull { it.config.id == containerId }?.stopGeneration
            ?: throw OmpProcessStoppedException(),
        runningOnly: Boolean = false,
    ): OmpInstallationCheck = coroutineScope {
        fun interrupted() = runtime.wasInterrupted(containerId, stopGeneration) ||
            (runningOnly && runtime.containers.value.none { it.config.id == containerId && it.running })
        if (interrupted()) throw OmpProcessStoppedException()
        // Do not use execute(): the configured login shell may itself be missing or broken.
        // Engine failures and missing probe frames must propagate rather than suggesting reinstall.
        val process = try {
            val arguments = listOf("/bin/sh", "-c", script, "wawlink-omp-install", "--inspect")
            if (runningOnly) runtime.openRunningProcess(containerId, arguments, "/")
            else runtime.openProcess(containerId, arguments, "/")
        } catch (failure: Exception) {
            if (failure !is CancellationException && interrupted()) throw OmpProcessStoppedException()
            throw failure
        }
        val interruption = launch(start = CoroutineStart.UNDISPATCHED) {
            runtime.containers.first { interrupted() }
            process.destroyForcibly()
        }
        // Keep readers outside the timeout scope so finally can kill the guest
        // before cancellation waits for blocking stream reads to finish.
        val stdout = async(Dispatchers.IO) { runCatching { process.inputStream.bufferedReader().use { it.readText() } } }
        val stderr = async(Dispatchers.IO) { runCatching { process.errorStream.bufferedReader().use { it.readText() } } }
        try {
            withTimeout(90_000) {
                val exit = runInterruptible(Dispatchers.IO) { process.waitFor() }
                val output = stdout.await().getOrThrow()
                val diagnostic = stderr.await().getOrThrow()
                if (interrupted()) throw OmpProcessStoppedException()
                check(exit == 0) { "OMP 安装检查无法完成（$exit）：${diagnostic.trim().take(800)}" }
                decodeCheck(output)
            }
        } catch (failure: Exception) {
            if (failure !is CancellationException && interrupted()) throw OmpProcessStoppedException()
            throw failure
        } finally {
            interruption.cancel()
            if (process.isAlive) process.destroyForcibly()
        }
    }

    internal fun decodeCheck(output: String): OmpInstallationCheck {
        val frames = output.lineSequence().filter { it.startsWith("WAWLINK_OMP_CHECK\t") }.toList()
        check(frames.size == 1) { "容器未返回唯一有效的 OMP 安装检查结果；请检查容器引擎" }
        val parts = frames.single().split('\t', limit = 3)
        val state = OmpInstallationState.entries.firstOrNull { it.name == parts.getOrNull(1) }
        check(state != null && parts.size == 3 && parts[2].isNotBlank()) { "OMP 安装检查协议无效" }
        return OmpInstallationCheck(state, parts[2])
    }
}
