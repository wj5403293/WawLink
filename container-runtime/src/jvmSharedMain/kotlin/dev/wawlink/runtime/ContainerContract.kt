package dev.wawlink.runtime

import java.io.File
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

@Serializable
enum class ContainerEngineType { PROROOT, PROOT, CHROOT }
@Serializable
data class ContainerMount(val source: String, val target: String, val readWrite: Boolean = true)
@Serializable
data class ContainerConfig(
    val id: String,
    val name: String,
    val rootfsPath: String,
    val distro: String = "ubuntu",
    val version: String = "24.04",
    val arch: String = "arm64",
    val engine: ContainerEngineType = ContainerEngineType.PROOT,
    val isDefault: Boolean = false,
    val user: String = "root",
    val shell: String = "/bin/bash",
    val link2symlink: Boolean = true,
    val sysvipc: Boolean = false,
    val fakeKernel: Boolean = false,
    val autoSleep: Boolean = true,
    val mounts: List<ContainerMount> = emptyList(),
    val environment: Map<String, String> = emptyMap(),
)
data class ContainerInfo(val config: ContainerConfig, val running: Boolean = false, val sizeBytes: Long = 0, val error: String? = null)
data class DeploymentProgress(val id: String, val name: String, val progress: Float, val phase: String, val error: String? = null)
enum class MirrorSource(val label: String) { TSINGHUA("清华镜像"), ALIYUN("阿里镜像"), USTC("中科大镜像"), OFFICIAL("官方源") }
data class DistroSpec(val id: String, val name: String, val version: String, val arch: String = "arm64", val icon: String = "deployed_code")
data class ToolchainInfo(val id: String, val title: String, val icon: String, val installed: Boolean, val version: String = "")
data class RuntimePaths(
    val dataDir: File,
    val nativeLibraryDir: File? = null,
    val isAndroid: Boolean = false,
    val hostProot: String = "proot",
    val qemuExecutable: String? = null,
    val debugRootfs: File? = null,
    /** Active host resolver configuration; null values mean the network has no DNS configuration. */
    val resolvConf: StateFlow<String?>? = null,
)
interface TerminalSession : AutoCloseable {
    val output: StateFlow<List<String>>
    val isAlive: StateFlow<Boolean>
    fun send(command: String)
}
interface ContainerRuntime : AutoCloseable {
    val containers: StateFlow<List<ContainerInfo>>
    val deployments: StateFlow<List<DeploymentProgress>>
    val catalog: List<DistroSpec>
    suspend fun start(id: String)
    suspend fun stop(id: String)
    suspend fun update(config: ContainerConfig)
    suspend fun delete(id: String)
    suspend fun clone(id: String, name: String): ContainerConfig
    suspend fun setDefault(id: String)
    suspend fun installDistro(distro: DistroSpec, name: String, mirror: MirrorSource, engine: ContainerEngineType): ContainerConfig
    suspend fun importRootfs(source: String, name: String, engine: ContainerEngineType = ContainerEngineType.PROOT, expectedSha256: String? = null): ContainerConfig
    suspend fun exportRootfs(id: String, destination: String)
    suspend fun cleanPackageCaches(): Long
    suspend fun probeToolchains(id: String): List<ToolchainInfo>
    suspend fun changeToolchain(id: String, toolId: String, install: Boolean): String
    suspend fun execute(id: String, command: String, cwd: String = "/workspace"): String
    suspend fun openProcess(id: String, arguments: List<String>, cwd: String = "/workspace", environment: Map<String, String> = emptyMap()): Process
    suspend fun openTerminal(id: String): TerminalSession
    suspend fun register(config: ContainerConfig)
}
