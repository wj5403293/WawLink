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
    @kotlinx.serialization.Transient val metadata: ContainerMetadata = ContainerMetadata(),
) {
    val displayName: String get() = metadata.title.ifBlank { name }
}
data class ContainerInfo(
    val config: ContainerConfig,
    val running: Boolean = false,
    val sizeBytes: Long = 0,
    val error: String? = null,
    val runGeneration: Long = 0,
    val metadataError: String? = null,
    /** Advanced before intentional teardown; retained across restarts so late failures stay cancelled. */
    val stopGeneration: Long = 0,
)
data class DeploymentProgress(val id: String, val name: String, val progress: Float, val phase: String, val error: String? = null)
enum class MirrorSource(val label: String) { OFFICIAL("官方源"), TSINGHUA("清华镜像"), ALIYUN("阿里镜像"), USTC("中科大镜像") }
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
    /** Raw PTY transport; terminal emulators consume bytes without line conversion. */
    val process: Process
    val processId: Long
    val isAlive: StateFlow<Boolean>
    fun resize(columns: Int, rows: Int, cellWidthPixels: Int = 0, cellHeightPixels: Int = 0)
}
data class RootfsExportSanitization(
    val ompConfigAndCredentials: Boolean = false,
    val ompSessionsAndLogs: Boolean = false,
    val shellHistory: Boolean = false,
    val sshGitAndCaches: Boolean = false,
) {
    val isSanitized: Boolean get() = ompConfigAndCredentials || ompSessionsAndLogs || shellHistory || sshGitAndCaches
}
data class RootfsImportInspection(val id: String, val name: String, val metadata: ContainerMetadata)
enum class RootfsImportMode { NEW, RENAME, UPDATE }

/** Both names are independent identities: a single archive can conflict with several containers. */
fun rootfsImportConflicts(name: String, archiveTitle: String, configs: List<ContainerConfig>): List<ContainerConfig> {
    val names = setOf(name.trim(), archiveTitle.trim()) - ""
    return configs.filter { it.name.trim() in names || it.displayName.trim() in names }
}


interface ContainerRuntime : AutoCloseable {
    val containers: StateFlow<List<ContainerInfo>>
    val deployments: StateFlow<List<DeploymentProgress>>
    val catalog: List<DistroSpec>
    suspend fun start(id: String)
    suspend fun stop(id: String)
    suspend fun update(config: ContainerConfig)
    suspend fun readMetadata(id: String): ContainerMetadataSnapshot
    suspend fun saveMetadata(id: String, metadata: ContainerMetadata, expectedRevision: String?): ContainerMetadataSnapshot
    suspend fun delete(id: String)
    suspend fun clone(id: String, name: String): ContainerConfig
    suspend fun setDefault(id: String)
    suspend fun installDistro(distro: DistroSpec, name: String, mirror: MirrorSource, engine: ContainerEngineType): ContainerConfig
    suspend fun inspectRootfsArchive(source: String, name: String, engine: ContainerEngineType = ContainerEngineType.PROOT, expectedSha256: String? = null): RootfsImportInspection
    suspend fun importRootfs(inspectionId: String, name: String, mode: RootfsImportMode = RootfsImportMode.NEW, targetId: String? = null): ContainerConfig
    suspend fun discardRootfsImport(inspectionId: String)
    suspend fun exportRootfs(id: String, destination: String, sanitization: RootfsExportSanitization = RootfsExportSanitization())
    suspend fun cleanPackageCaches(): Long
    suspend fun probeToolchains(id: String): List<ToolchainInfo>
    suspend fun changeToolchain(id: String, toolId: String, install: Boolean): String
    suspend fun execute(id: String, command: String, cwd: String = "/workspace"): String
    suspend fun openProcess(id: String, arguments: List<String>, cwd: String = "/workspace", environment: Map<String, String> = emptyMap()): Process
    /** Checks running state and launches atomically with respect to stop; never starts a stopped container. */
    suspend fun openRunningProcess(id: String, arguments: List<String>, cwd: String = "/workspace", environment: Map<String, String> = emptyMap()): Process
    /** Runs an optional trusted script once via /bin/sh, then keeps the configured interactive shell open. */
    suspend fun openTerminal(id: String, initialCommand: String? = null): TerminalSession
    suspend fun register(config: ContainerConfig)
}

/** Removal or an explicit stop invalidates work even if the same container has already restarted. */
fun ContainerRuntime.wasInterrupted(id: String, stopGeneration: Long): Boolean =
    containers.value.firstOrNull { it.config.id == id }?.stopGeneration != stopGeneration
