package dev.wawlink.runtime

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Script and shell are positional parameters, never interpolated into shell source or pasted into the PTY. */
internal fun terminalArguments(shell: String, initialCommand: String?): List<String> {
    if (initialCommand == null) return listOf(shell, "-i")
    require('\u0000' !in initialCommand) { "终端初始命令不能包含 NUL" }
    return listOf("/bin/sh", "-c", """
        trap ':' INT
        /bin/sh -c "${'$'}1"
        status=${'$'}?
        trap - INT
        if [ "${'$'}status" -ne 0 ]; then printf '\n初始命令失败（退出码 %s）；终端保持打开。\n' "${'$'}status"; fi
        exec "${'$'}2" -i
    """.trimIndent(), "wawlink-terminal", initialCommand, shell)
}

@Serializable
private data class SavedRuntime(val containers: List<ContainerConfig> = emptyList(), val debugRootfsRemoved: Boolean = false)

/** One application-owned runtime. All guest commands go through the selected engine. */
class LocalContainerRuntime(val paths: RuntimePaths) : ContainerRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val engines = RuntimeEngines(paths)
    private val metadataStore = ContainerMetadataStore(engines)
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val stateFile = File(paths.dataDir, "containers.json")
    private val rootfsDir = File(paths.dataDir, "rootfs")
    private val cacheDir = File(paths.dataDir, "downloads")
    private val configurations = linkedMapOf<String, ContainerConfig>()
    private val processes = mutableMapOf<String, MutableList<TrackedProcess>>()
    private val keepers = mutableMapOf<String, TrackedProcess>()
    private val runGenerations = mutableMapOf<String, Long>()
    private val stopGenerations = mutableMapOf<String, Long>()
    private var nextRunGeneration = 0L
    private val errors = mutableMapOf<String, String>()
    private val dnsErrors = mutableMapOf<String, String>()
    private val metadataErrors = mutableMapOf<String, String>()
    private val sizes = mutableMapOf<String, Long>()
    private val idleJobs = mutableMapOf<String, Job>()
    private val packageLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    private var debugRootfsRemoved = false
    @Volatile private var closed = false
    private val _containers = MutableStateFlow<List<ContainerInfo>>(emptyList())
    override val containers: StateFlow<List<ContainerInfo>> = _containers.asStateFlow()
    private val _deployments = MutableStateFlow<List<DeploymentProgress>>(emptyList())
    override val deployments: StateFlow<List<DeploymentProgress>> = _deployments.asStateFlow()
    override val catalog: List<DistroSpec> = DistroSources.catalog

    init {
        require(paths.dataDir.isDirectory || paths.dataDir.mkdirs()) { "无法创建运行时数据目录" }
        rootfsDir.mkdirs()
        cacheDir.mkdirs()
        if (stateFile.isFile) {
            val saved = try { json.decodeFromString<SavedRuntime>(stateFile.readText()) }
            catch (failure: Exception) { throw IllegalStateException("容器配置文件损坏，已保留原文件：${stateFile.path}", failure) }
            debugRootfsRemoved = saved.debugRootfsRemoved
            saved.containers.forEach { config ->
                require(!configurations.containsKey(config.id)) { "容器配置包含重复 ID：${config.id}" }
                configurations[config.id] = config
            }
        }
        if (!debugRootfsRemoved && !configurations.containsKey("ubuntu-main")) {
            paths.debugRootfs?.takeIf { it.isDirectory }?.let { root ->
                val config = ContainerConfig("ubuntu-main", "ubuntu-main", root.canonicalPath, isDefault = configurations.values.none { it.isDefault }, link2symlink = paths.isAndroid)
                try { engines.validateRoot(config); configurations[config.id] = config; persist() }
                catch (failure: Exception) { errors[config.id] = "预置 rootfs 无效：${failure.message}" }
            }
        }
        configurations.values.toList().forEach { config ->
            try { engines.validateRoot(config); sizes[config.id] = sizeOf(config) }
            catch (failure: Exception) { errors[config.id] = "rootfs 不可访问：${failure.message}" }
            refreshMetadata(config)
        }
        publish()
        paths.resolvConf?.let { resolver ->
            scope.launch {
                resolver.collect { content ->
                    if (content != null) mutex.withLock {
                        for (config in configurations.values) {
                            if (keepers[config.id]?.process?.isAlive != true) continue
                            try { updateDns(config, content) } catch (failure: Exception) {
                                if (failure is CancellationException) throw failure
                                // The affected container exposes the error; keep observing network changes.
                            }
                        }
                    }
                }
            }
        }
    }

    private fun ensureOpen() = check(!closed) { "容器运行时已关闭" }
    private fun config(id: String): ContainerConfig = configurations[id] ?: error("找不到容器：$id")
    private fun sizeOf(config: ContainerConfig): Long {
        val root = File(config.rootfsPath).canonicalFile
        val rootPath = root.toPath()
        val excluded = config.mounts.mapNotNull { mount ->
            val target = File(root, mount.target.trimStart('/')).canonicalFile.toPath()
            if (target.startsWith(rootPath)) rootPath.relativize(target).toString() else null
        }.toSet()
        return RootfsArchives.size(root, excluded)
    }
    private fun publish() {
        _containers.value = configurations.values.map {
            ContainerInfo(it, keepers[it.id]?.let { keeper -> !keeper.stopping && keeper.process.isAlive } == true,
                sizes[it.id] ?: 0, errors[it.id] ?: dnsErrors[it.id], runGenerations[it.id] ?: 0,
                metadataErrors[it.id], stopGenerations[it.id] ?: 0)
        }
    }
    private fun persist() {
        val temp = File(paths.dataDir, "containers.json.tmp")
        FileOutputStream(temp).use { output -> output.write(json.encodeToString(SavedRuntime(configurations.values.toList(), debugRootfsRemoved)).toByteArray()); output.fd.sync() }
        try { Files.move(temp.toPath(), stateFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        catch (_: AtomicMoveNotSupportedException) { Files.move(temp.toPath(), stateFile.toPath(), StandardCopyOption.REPLACE_EXISTING) }
    }

    /** Metadata failures are independent of engine/config repair and never start a guest. */
    private fun refreshMetadata(config: ContainerConfig): ContainerMetadataSnapshot? = try {
        metadataStore.read(config).also { snapshot ->
            configurations[config.id] = config.copy(metadata = snapshot.value)
            metadataErrors.remove(config.id)
        }
    } catch (failure: Exception) {
        if (failure is CancellationException) throw failure
        configurations[config.id] = config.copy(metadata = ContainerMetadata())
        metadataErrors[config.id] = "容器信息读取失败：${failure.message}"
        null
    }

    override suspend fun readMetadata(id: String): ContainerMetadataSnapshot = withContext(Dispatchers.IO) { mutex.withLock {
        ensureOpen()
        val snapshot = refreshMetadata(config(id))
        publish()
        snapshot ?: throw IllegalStateException(metadataErrors.getValue(id))
    } }

    override suspend fun saveMetadata(id: String, metadata: ContainerMetadata, expectedRevision: String?): ContainerMetadataSnapshot = withContext(Dispatchers.IO) { mutex.withLock {
        ensureOpen()
        val current = config(id)
        val snapshot = metadataStore.save(current, metadata, expectedRevision)
        configurations[id] = current.copy(metadata = snapshot.value)
        metadataErrors.remove(id)
        publish()
        snapshot
    } }

    private fun updateDns(config: ContainerConfig, content: String) {
        try {
            val overridden = config.mounts.any { mount ->
                val target = mount.target.trimEnd('/')
                target == "/etc" || target == "/etc/resolv.conf"
            }
            if (!overridden) RuntimeDns.update(engines.validateRoot(config), content)
            if (dnsErrors.remove(config.id) != null) publish()
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            val message = "DNS 配置更新失败：${failure.message}"
            dnsErrors[config.id] = message
            publish()
            throw IllegalStateException(message, failure)
        }
    }

    override suspend fun register(config: ContainerConfig) = register(config, initialTitle = null)

    private suspend fun register(config: ContainerConfig, initialTitle: String?) = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureOpen()
            require(!configurations.containsKey(config.id)) { "容器 ID 已存在：${config.id}" }
            engines.validateRoot(config)
            require(config.id.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")) && config.name.isNotBlank()) { "容器 ID 或名称无效" }
            require(configurations.values.none { File(it.rootfsPath).canonicalPath == File(config.rootfsPath).canonicalPath }) { "此 rootfs 已注册为另一个容器" }
            configurations[config.id] = config.copy(rootfsPath = File(config.rootfsPath).canonicalPath, isDefault = config.isDefault || configurations.isEmpty())
            val snapshot = refreshMetadata(configurations.getValue(config.id))
            if (initialTitle != null && snapshot != null && snapshot.revision == null) {
                try {
                    val saved = metadataStore.save(config, ContainerMetadata(title = initialTitle), null)
                    configurations[config.id] = configurations.getValue(config.id).copy(metadata = saved.value)
                } catch (failure: Throwable) {
                    configurations.remove(config.id)
                    metadataErrors.remove(config.id)
                    throw failure
                }
            }
            if (config.isDefault) configurations.replaceAll { key, existing -> existing.copy(isDefault = key == config.id) }
            sizes[config.id] = sizeOf(config)
            persist()
            publish()
        }
    }

    override suspend fun start(id: String) = withContext(Dispatchers.IO) { mutex.withLock { ensureOpen(); startLocked(id) } }
    private suspend fun startLocked(id: String) {
        val config = config(id)
        paths.resolvConf?.value?.let { updateDns(config, it) }
        if (keepers[id]?.let { !it.stopping && it.process.isAlive } == true) return
        if (keepers.containsKey(id)) stopLocked(id, intentional = false)
        try {
            engines.prepare(config)
            if (config.engine == ContainerEngineType.PROOT || config.engine == ContainerEngineType.PROROOT) {
                RuntimeIdentity.update(engines.validateRoot(config), RuntimeIdentity.hostSupplementaryGroupIds(File("/proc/self/status").readText(Charsets.UTF_8)))
            }
            val workspace = File(config.rootfsPath, "workspace")
            require(workspace.canonicalFile.toPath().startsWith(File(config.rootfsPath).canonicalFile.toPath())) { "工作区路径越界" }
            workspace.mkdirs()
            val health = launchLocked(config, listOf("/bin/sh", "-c", "id >/dev/null"), "/", emptyMap(), true, false).process
            val checkOutput = scope.async { health.inputStream.bufferedReader().use { it.readText() } }
            if (!health.waitFor(90, TimeUnit.SECONDS)) {
                stopLocked(id, intentional = false)
                error("容器引擎启动超时，请检查 Rootfs/QEMU")
            }
            val output = checkOutput.await()
            check(health.exitValue() == 0) { "容器引擎启动失败：${output.trim()}" }
            val keeper = launchLocked(config, listOf("/bin/sh", "-c", "trap 'exit 0' TERM INT; while :; do sleep 3600 & wait \$!; done"), "/", emptyMap(), true, true)
            keepers[id] = keeper
            runGenerations[id] = ++nextRunGeneration
            errors.remove(id)
            publish()
        } catch (failure: Throwable) {
            errors[id] = failure.message ?: "容器启动失败"
            try { engines.unmount(id) } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            publish()
            throw failure
        }
    }

    override suspend fun stop(id: String) = withContext(Dispatchers.IO) { mutex.withLock {
        ensureOpen(); config(id)
        try { stopLocked(id) } catch (failure: Throwable) { errors[id] = failure.message ?: "容器停止失败"; publish(); throw failure }
    } }
    private fun stopLocked(id: String, intentional: Boolean = true) {
        idleJobs.remove(id)?.cancel()
        val owned = processes[id].orEmpty().toList()
        if (intentional) stopGenerations[id] = (stopGenerations[id] ?: 0) + 1
        owned.forEach { it.stopping = true }
        // Readers can observe EOF immediately after TERM; invalidate their lifetime first.
        publish()
        owned.forEach { it.outputDrainer?.cancel(); it.refreshDescendants() }
        val root = configurations[id]?.engine == ContainerEngineType.CHROOT
        terminate(owned, root, false)
        owned.forEach { it.process.destroy() }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (System.nanoTime() < deadline && owned.any { it.hasLiveProcesses() }) Thread.sleep(50)
        terminate(owned, root, true)
        owned.forEach { if (it.process.isAlive) it.process.destroyForcibly(); it.process.waitFor(2, TimeUnit.SECONDS) }
        owned.forEach { it.terminal?.close() }
        check(owned.none { it.hasLiveProcesses() }) { "容器仍有已跟踪进程未退出，已保留挂载以保护文件系统" }
        processes.remove(id)
        keepers.remove(id)
        runGenerations.remove(id)
        try { engines.unmount(id); errors.remove(id) }
        catch (failure: Throwable) { errors[id] = failure.message.orEmpty(); publish(); throw failure }
        publish()
    }

    private fun terminate(owned: List<TrackedProcess>, asRoot: Boolean, force: Boolean) {
        val identities = owned.flatMap { it.liveIdentities() }.distinct().asReversed()
        if (identities.isEmpty()) return
        val signal = if (force) "KILL" else "TERM"
        val command = identities.joinToString("\n") { (pid, start) -> """
            stat=${'$'}(cat /proc/$pid/stat 2>/dev/null)
            if [ -n "${'$'}stat" ]; then
                rest=${'$'}{stat##*) }; set -- ${'$'}rest
                if [ "${'$'}#" -ge 20 ]; then
                    shift 19
                    if [ "${'$'}1" = ${shellQuote(start)} ]; then kill -$signal $pid 2>/dev/null || true; fi
                fi
            fi
        """.trimIndent() } + "\ntrue"
        if (asRoot) engines.rootCommand(command) else HostCommands.run(listOf("sh", "-c", command))
    }

    override suspend fun update(config: ContainerConfig) = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureOpen()
            val previous = config(config.id)
            require(File(previous.rootfsPath).canonicalPath == File(config.rootfsPath).canonicalPath) { "请通过导入/克隆更换 rootfs，不能直接修改路径" }
            engines.validate(config)
            val running = keepers[config.id]?.process?.isAlive == true
            if (running) stopLocked(config.id)
            if (config.isDefault) configurations.replaceAll { _, value -> value.copy(isDefault = false) }
            configurations[config.id] = config.copy(metadata = previous.metadata)
            if (configurations.values.none { it.isDefault }) configurations[config.id] = configurations.getValue(config.id).copy(isDefault = true)
            refreshMetadata(configurations.getValue(config.id))
            persist()
            publish()
            if (running) startLocked(config.id)
        }
    }

    override suspend fun setDefault(id: String) = withContext(Dispatchers.IO) { mutex.withLock {
        ensureOpen(); config(id)
        configurations.replaceAll { key, value -> value.copy(isDefault = key == id) }
        persist(); publish()
    } }

    override suspend fun delete(id: String) = withContext(Dispatchers.IO) { mutex.withLock {
        ensureOpen()
        val existing = config(id)
        stopLocked(id)
        val root = File(existing.rootfsPath).canonicalFile
        // Registered developer/user-owned external trees are never recursively deleted.
        if (root.toPath().startsWith(rootfsDir.canonicalFile.toPath()) && root != rootfsDir.canonicalFile) RootfsArchives.deleteTree(root)
        configurations.remove(id)
        errors.remove(id)
        dnsErrors.remove(id)
        metadataErrors.remove(id)
        sizes.remove(id)
        if (id == "ubuntu-main") debugRootfsRemoved = true
        if (configurations.isNotEmpty() && configurations.values.none { it.isDefault }) {
            val first = configurations.values.first(); configurations[first.id] = first.copy(isDefault = true)
        }
        persist(); publish()
    } }

    override suspend fun clone(id: String, name: String): ContainerConfig = withContext(Dispatchers.IO) {
        ContainerMetadataStore.validate(ContainerMetadata(title = name))
        mutex.withLock {
            ensureOpen()
            val original = config(id)
            val wasRunning = keepers[id]?.process?.isAlive == true
            stopLocked(id)
            val newId = UUID.randomUUID().toString()
            val target = File(rootfsDir, newId)
            val archive = File(cacheDir, "$newId.clone.tar.zst")
            try {
                RootfsArchives.export(File(original.rootfsPath), archive)
                RootfsArchives.extract(archive, target)
                val copiedConfig = original.copy(id = newId, name = name, rootfsPath = target.canonicalPath, isDefault = false)
                val previous = metadataStore.read(copiedConfig)
                val metadata = metadataStore.save(copiedConfig, previous.value.copy(title = name), previous.revision)
                val copied = copiedConfig.copy(metadata = metadata.value)
                configurations[newId] = copied
                sizes[newId] = sizeOf(copied)
                persist(); publish()
                copied
            } catch (failure: Throwable) { RootfsArchives.deleteTree(target); throw failure }
            finally { archive.delete(); if (wasRunning) startLocked(id) }
        }
    }

    private fun deployment(progress: DeploymentProgress) { synchronized(_deployments) {
        val current = _deployments.value
        val index = current.indexOfFirst { it.id == progress.id }
        if (index >= 0 && current[index] == progress) return@synchronized
        _deployments.value = current.toMutableList().apply {
            if (index >= 0) this[index] = progress else add(progress)
        }
    } }
    private fun finishDeployment(id: String) { synchronized(_deployments) { _deployments.value = _deployments.value.filterNot { it.id == id } } }

    override suspend fun installDistro(distro: DistroSpec, name: String, mirror: MirrorSource, engine: ContainerEngineType): ContainerConfig = withContext(Dispatchers.IO) {
        ensureOpen(); ContainerMetadataStore.validate(ContainerMetadata(title = name))
        val id = UUID.randomUUID().toString()
        deployment(DeploymentProgress(id, name, 0f, "读取发行者 SHA256 元数据"))
        try {
            val source = DistroSources.resolve(distro, mirror)
            deploy(id, name, source.url, source.sha256, engine, distro, mirror, source.phase)
        } catch (failure: Throwable) {
            val message = "发行版部署失败：${failure.message}"
            deployment(DeploymentProgress(id, name, 0f, "部署失败", message))
            throw IllegalStateException(message, failure)
        }
    }

    override suspend fun importRootfs(source: String, name: String, engine: ContainerEngineType, expectedSha256: String?): ContainerConfig = withContext(Dispatchers.IO) {
        ensureOpen(); ContainerMetadataStore.validate(ContainerMetadata(title = name))
        val id = UUID.randomUUID().toString()
        try { deploy(id, name, source, expectedSha256, engine, null, null, if (expectedSha256 == null) "导入归档（未提供发行者 SHA256）" else "导入并校验 SHA256") }
        catch (failure: Throwable) {
            val message = "rootfs 导入失败：${failure.message}"
            deployment(DeploymentProgress(id, name, 0f, "导入失败", message))
            throw IllegalStateException(message, failure)
        }
    }

    private suspend fun deploy(id: String, name: String, source: String, checksum: String?, engine: ContainerEngineType, distro: DistroSpec?, mirror: MirrorSource?, phase: String): ContainerConfig {
        val archive = File(cacheDir, "$id.part")
        val target = File(rootfsDir, id)
        try {
            var lastUpdate = 0L
            DistroSources.transfer(source, archive, checksum) { bytes, total ->
                val now = System.nanoTime()
                if (now - lastUpdate > 100_000_000 || bytes == total) {
                    deployment(DeploymentProgress(id, name, if (total > 0) (bytes.toFloat() / total * .70f).coerceIn(0f, .70f) else 0f, "$phase · ${bytes / 1024 / 1024} MB${if (total > 0) " / ${total / 1024 / 1024} MB" else ""}"))
                    lastUpdate = now
                }
            }
            deployment(DeploymentProgress(id, name, .72f, "安全解压 rootfs"))
            RootfsArchives.extract(archive, target) { bytes ->
                val now = System.nanoTime()
                if (now - lastUpdate > 100_000_000) { deployment(DeploymentProgress(id, name, .85f, "解压中 · ${bytes / 1024 / 1024} MB")); lastUpdate = now }
            }
            val os = readOsRelease(target)
            val distroId = distro?.id ?: when (os["ID"]) { "archarm", "arch" -> "arch"; else -> os["ID"] ?: "custom" }
            if (mirror != null) DistroSources.configure(target, distroId, mirror)
            else {
                File(target, "workspace").mkdirs()
                if (!File(target, "etc/resolv.conf").isFile) DistroSources.writeGuest(target, "etc/resolv.conf", "nameserver 223.5.5.5\nnameserver 1.1.1.1\n")
            }
            val config = ContainerConfig(id, name, target.canonicalPath, distroId, distro?.version ?: os["VERSION_ID"] ?: "custom", distro?.arch ?: detectArch(target), engine, link2symlink = paths.isAndroid, shell = if (File(target, "bin/bash").exists()) "/bin/bash" else "/bin/sh")
            engines.validateRoot(config)
            deployment(DeploymentProgress(id, name, .98f, "保存容器配置"))
            register(config, initialTitle = name)
            finishDeployment(id)
            return containers.value.first { it.config.id == id }.config
        } catch (failure: Throwable) { RootfsArchives.deleteTree(target); throw failure }
        finally { archive.delete() }
    }

    private fun readOsRelease(root: File): Map<String, String> {
        val file = File(root, "etc/os-release")
        require(file.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())) { "os-release 路径越界" }
        return file.takeIf { it.isFile }?.readLines()?.filter { it.contains('=') && !it.startsWith('#') }?.associate { it.substringBefore('=') to it.substringAfter('=').trim().trim('"', '\'') }.orEmpty()
    }
    private fun detectArch(root: File): String {
        val shell = File(root, "bin/sh").canonicalFile
        require(shell.toPath().startsWith(root.canonicalFile.toPath())) { "Shell 链接逃逸 rootfs" }
        val header = shell.inputStream().use { stream -> ByteArray(20).also { check(stream.read(it) == 20) { "无法读取 Shell ELF 架构" } } }
        require(header.take(4) == listOf(0x7f.toByte(), 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())) { "导入的 /bin/sh 不是 ELF 程序" }
        val machine = if (header[5].toInt() == 1) (header[18].toInt() and 255) or ((header[19].toInt() and 255) shl 8) else ((header[18].toInt() and 255) shl 8) or (header[19].toInt() and 255)
        return when (machine) { 183 -> "arm64"; 62 -> "amd64"; 40 -> "arm"; else -> error("不支持的 ELF 架构：$machine") }
    }

    override suspend fun exportRootfs(id: String, destination: String) = withContext(Dispatchers.IO) { mutex.withLock {
        ensureOpen()
        val config = config(id)
        val target = if (destination.startsWith("file:")) File(java.net.URI(destination)) else File(destination)
        require(target.name.endsWith(".tar.zst")) { "导出文件名必须以 .tar.zst 结尾" }
        require(!target.exists()) { "导出文件已存在，请选择新文件名" }
        val running = keepers[id]?.process?.isAlive == true
        stopLocked(id)
        try { RootfsArchives.export(File(config.rootfsPath), target) }
        catch (failure: Throwable) { target.delete(); throw IllegalStateException("rootfs 导出失败：${failure.message}", failure) }
        finally { if (running) startLocked(id) }
    } }

    override suspend fun openProcess(id: String, arguments: List<String>, cwd: String, environment: Map<String, String>): Process {
        val generation = containers.value.firstOrNull { it.config.id == id }?.stopGeneration
        val operation = currentCoroutineContext()[ContainerFileOperation]?.takeIf { it.containerId == id }
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                currentCoroutineContext().ensureActive()
                if (generation != null && wasInterrupted(id, generation)) throw CancellationException("容器已停止或重新启动")
                if (operation != null && !operation.isCurrent(this@LocalContainerRuntime)) throw CancellationException("容器已停止或重新启动")
                ensureOpen()
                if (operation?.runGeneration == null) startLocked(id)
                else if (keepers[id]?.let { !it.stopping && it.process.isAlive } != true) throw CancellationException("容器已停止")
                if (operation != null) operation.runGeneration = runGenerations[id]
                idleJobs.remove(id)?.cancel()
                launchLocked(config(id), arguments, cwd, environment, false, false).process
            }
        }
    }

    override suspend fun openRunningProcess(id: String, arguments: List<String>, cwd: String, environment: Map<String, String>): Process {
        val generation = containers.value.firstOrNull { it.config.id == id }?.stopGeneration
        val operation = currentCoroutineContext()[ContainerFileOperation]?.takeIf { it.containerId == id }
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                currentCoroutineContext().ensureActive()
                if (generation != null && wasInterrupted(id, generation)) throw CancellationException("容器已停止或重新启动")
                if (operation != null && !operation.isCurrent(this@LocalContainerRuntime)) throw CancellationException("容器已停止或重新启动")
                ensureOpen()
                check(keepers[id]?.let { !it.stopping && it.process.isAlive } == true) { "容器已停止" }
                idleJobs.remove(id)?.cancel()
                launchLocked(config(id), arguments, cwd, environment, false, false).process
            }
        }
    }

    private fun launchLocked(config: ContainerConfig, arguments: List<String>, cwd: String, environment: Map<String, String>, merge: Boolean, keeper: Boolean, pty: Boolean = false): TrackedProcess {
        val root = config.engine == ContainerEngineType.CHROOT
        val pidFile = if (root) File(paths.dataDir, "tmp/root-${UUID.randomUUID()}.pid").apply { parentFile.mkdirs(); check(createNewFile()) { "无法创建 Root PID 握手文件" } } else null
        var terminal: PtyTransport? = null
        val process = try {
            val builder = engines.builder(config, arguments, cwd, environment, merge, pidFile)
            if (pty) createPtyTransport(builder).also { terminal = it }.process else builder.start()
        }
        catch (failure: Throwable) { pidFile?.delete(); throw IllegalStateException("无法启动容器引擎：${failure.message}", failure) }
        val identity = if (pidFile != null) {
            var result: Pair<Long, String>? = null
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (System.nanoTime() < deadline && result == null && process.isAlive) {
                result = TrackedProcess.handshake(pidFile)
                if (result == null) Thread.sleep(20)
            }
            result ?: TrackedProcess.handshake(pidFile)
        } else null
        if (root && identity == null) {
            process.destroyForcibly(); terminal?.close(); pidFile?.delete()
            error("无法安全识别 su 启动的 chroot PID；执行确认未发送，Root Shell 将超时退出")
        }
        val tracked = try { TrackedProcess(process, keeper, identity, if (root) engines::rootSnapshot else null, terminal) }
        catch (failure: Throwable) { process.destroyForcibly(); terminal?.close(); pidFile?.delete(); throw failure }
        if (tracked.pid == null) { process.destroyForcibly(); terminal?.close(); pidFile?.delete(); error("无法识别容器进程 PID，拒绝启动以避免遗留进程") }
        processes.getOrPut(config.id) { mutableListOf() } += tracked
        if (keeper) tracked.outputDrainer = scope.launch {
            try {
                process.inputStream.use { input ->
                    val buffer = ByteArray(4096)
                    while (input.read(buffer) >= 0) { /* Drain only; keeper has no terminal consumer. */ }
                }
            } catch (failure: java.io.IOException) {
                coroutineContext.ensureActive()
                throw failure
            }
        }
        scope.launch {
            try {
                while (if (keeper) process.isAlive else tracked.hasLiveProcesses()) { tracked.refreshDescendants(); delay(300) }
            } catch (failure: Exception) {
                mutex.withLock { errors[config.id] = "无法安全跟踪容器进程：${failure.message}"; publish() }
                return@launch
            }
            mutex.withLock {
                if (tracked.stopping || closed) { processes[config.id]?.remove(tracked); return@withLock }
                if (keeper && keepers[config.id] === tracked) {
                    val reason = "容器引擎进程已退出（${process.exitValue()}）"
                    try { stopLocked(config.id, intentional = false); errors[config.id] = reason }
                    catch (failure: Throwable) { errors[config.id] = "$reason；${failure.message}" }
                    publish()
                } else {
                    processes[config.id]?.remove(tracked)
                    if (!keeper && configurations[config.id]?.autoSleep == true && processes[config.id].orEmpty().none { !it.keeper }) {
                        idleJobs[config.id] = scope.launch { delay(30_000); mutex.withLock { if (processes[config.id].orEmpty().none { !it.keeper }) stopLocked(config.id) } }
                    }
                }
            }
        }
        if (pidFile != null) {
            try {
                check(File(pidFile.path + ".ack").createNewFile()) { "无法确认 Root 进程执行" }
            } catch (failure: Throwable) {
                // Tracking is installed before acknowledgement, including when filesystem I/O fails.
                try { terminate(listOf(tracked), root, true) } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                process.destroyForcibly()
                try { terminal?.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                throw failure
            } finally { pidFile.delete() }
        }
        return tracked
    }

    override suspend fun execute(id: String, command: String, cwd: String): String {
        val generation = containers.value.firstOrNull { it.config.id == id }?.stopGeneration
        try {
            val shell = mutex.withLock { config(id).shell }
            if (generation != null && wasInterrupted(id, generation)) throw CancellationException("容器已停止或重新启动")
            val process = openProcess(id, listOf(shell, "-lc", command), cwd)
            return collect(process)
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            if (generation != null && wasInterrupted(id, generation)) throw CancellationException("容器已停止或重新启动", failure)
            throw failure
        }
    }
    private suspend fun collect(process: Process): String = coroutineScope {
        val stdout = async(Dispatchers.IO) { process.inputStream.bufferedReader().use { it.readText() } }
        val stderr = async(Dispatchers.IO) { process.errorStream.bufferedReader().use { it.readText() } }
        try {
            val code = withContext(Dispatchers.IO) { process.waitFor() }
            val output = stdout.await() + stderr.await()
            check(code == 0) { "容器命令失败（$code）：${output.trim()}" }
            output
        } finally { if (process.isAlive) process.destroy() }
    }

    override suspend fun openTerminal(id: String, initialCommand: String?): TerminalSession {
        val generation = containers.value.firstOrNull { it.config.id == id }?.stopGeneration
        return withContext(Dispatchers.IO) { mutex.withLock {
            currentCoroutineContext().ensureActive()
            if (generation != null && wasInterrupted(id, generation)) throw CancellationException("容器已停止或重新启动")
            ensureOpen(); startLocked(id); idleJobs.remove(id)?.cancel()
            val config = config(id)
            val tracked = launchLocked(config, terminalArguments(config.shell, initialCommand), "/workspace", emptyMap(), true, false, pty = true)
            object : TerminalSession {
                override val process = tracked.process
                override val processId = requireNotNull(tracked.pid)
                private val alive = MutableStateFlow(process.isAlive)
                override val isAlive = alive.asStateFlow()
                private val closing = java.util.concurrent.atomic.AtomicBoolean()
                init { scope.launch { process.waitFor(); alive.value = false } }
                override fun resize(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
                    require(columns > 0 && rows > 0)
                    if (!closing.get() && !tracked.stopping && process.isAlive) {
                        try { requireNotNull(tracked.terminal).resize(columns, rows, cellWidthPixels, cellHeightPixels) }
                        catch (failure: Exception) { if (!closing.get() && !tracked.stopping) throw failure }
                    }
                }
                override fun close() {
                    if (!closing.compareAndSet(false, true)) return
                    scope.launch {
                        mutex.withLock {
                            val root = config.engine == ContainerEngineType.CHROOT
                            try {
                                tracked.refreshDescendants()
                                terminate(listOf(tracked), root, false)
                                process.destroy()
                                process.waitFor(2, TimeUnit.SECONDS)
                                terminate(listOf(tracked), root, true)
                                if (process.isAlive) process.destroyForcibly()
                                process.waitFor(2, TimeUnit.SECONDS)
                                check(!tracked.hasLiveProcesses()) { "终端仍有已跟踪子进程未退出" }
                            } catch (failure: Exception) {
                                errors[id] = "终端关闭失败：${failure.message}"
                                publish()
                            } finally {
                                try { tracked.terminal?.close() }
                                catch (failure: Exception) { errors[id] = "终端 PTY 关闭失败：${failure.message}"; publish() }
                                alive.value = false
                            }
                        }
                    }
                }
            }
        } }
    }

    override suspend fun probeToolchains(id: String): List<ToolchainInfo> = tools.map { tool ->
        val output = execute(id, "if command -v ${tool.probe} >/dev/null 2>&1; then ${tool.version} 2>&1 | head -n 1; else printf '__WAWLINK_MISSING__'; fi", "/")
        val missing = output.contains("__WAWLINK_MISSING__")
        val version = if (missing) "" else output.trim().let { if (tool.id == "git") it.removePrefix("git version ") else it }
        ToolchainInfo(tool.id, tool.title, tool.icon, !missing, version)
    }

    override suspend fun changeToolchain(id: String, toolId: String, install: Boolean): String = packageLocks.getOrPut(id) { Mutex() }.withLock {
        val tool = tools.firstOrNull { it.id == toolId } ?: error("未知工具链：$toolId")
        val distro = mutex.withLock { config(id).distro }
        val manager = manager(distro)
        val packages = tool.packages.getValue(manager)
        val command = when (manager) {
            "apt" -> if (install) "export DEBIAN_FRONTEND=noninteractive; apt-get update && apt-get install -y $packages" else """
                selected=''
                for package in $packages; do
                    if dpkg-query -W -f='${'$'}{Status}' "${'$'}package" 2>/dev/null | grep -q '^install ok installed${'$'}'; then selected="${'$'}selected ${'$'}package"; fi
                done
                if [ -n "${'$'}selected" ]; then DEBIAN_FRONTEND=noninteractive apt-get purge -y ${'$'}selected && DEBIAN_FRONTEND=noninteractive apt-get autoremove -y; fi
            """.trimIndent()
            "apk" -> "apk ${if (install) "add --no-cache" else "del"} $packages"
            "pacman" -> "pacman ${if (install) "-Syu --needed" else "-Rns"} --noconfirm $packages"
            "dnf" -> "dnf ${if (install) "install" else "remove"} -y $packages"
            else -> error("不支持的软件包管理器")
        }
        val result = StringBuilder(execute(id, command, "/"))
        if (toolId == "node" && !install) {
            result.append(execute(id, """
                marker=/usr/local/share/wawlink/preinstalled-node
                if [ -f "${'$'}marker" ] && [ -x /usr/local/bin/node ] && [ "${'$'}(cat "${'$'}marker")" = "${'$'}(/usr/local/bin/node --version)" ]; then
                    rm -f /usr/local/bin/node /usr/local/bin/npm /usr/local/bin/npx /usr/local/bin/corepack
                    rm -rf /usr/local/lib/node_modules/npm /usr/local/lib/node_modules/corepack /usr/local/include/node /usr/local/share/doc/node
                    rm -f /usr/local/share/man/man1/node.1 "${'$'}marker"
                fi
            """.trimIndent(), "/"))
        }
        val installed = execute(id, "if command -v ${tool.probe} >/dev/null 2>&1; then printf installed; fi", "/").trim() == "installed"
        check(installed == install) {
            if (install) "软件包命令已完成，但 ${tool.title} 仍不可执行，请查看包管理器输出：$result"
            else "包管理器已卸载其管理的文件，但 ${tool.title} 仍在其他路径中；无法安全删除未知的手动安装，请在终端检查 command -v ${tool.probe}"
        }
        mutex.withLock { sizes[id] = sizeOf(config(id)); publish() }
        result.toString()
    }

    override suspend fun cleanPackageCaches(): Long {
        val ids = mutex.withLock { ensureOpen(); configurations.keys.toList() }
        var total = 0L
        ids.forEach { id -> packageLocks.getOrPut(id) { Mutex() }.withLock {
            val manager = manager(mutex.withLock { config(id).distro })
            val cache = when (manager) { "apt" -> "/var/cache/apt/archives"; "apk" -> "/var/cache/apk"; "pacman" -> "/var/cache/pacman/pkg"; else -> "/var/cache/dnf" }
            val clean = when (manager) { "apt" -> "apt-get clean"; "apk" -> "apk cache clean"; "pacman" -> "pacman -Scc --noconfirm"; else -> "dnf clean all" }
            val result = execute(id, "before=\$(du -sk $cache 2>/dev/null | cut -f1); $clean && after=\$(du -sk $cache 2>/dev/null | cut -f1) && printf '\\n__WAWLINK_FREED__%s\\n' \"\$(( (\${before:-0} - \${after:-0}) * 1024 ))\"", "/")
            val freed = Regex("__WAWLINK_FREED__(-?[0-9]+)").find(result)?.groupValues?.get(1)?.toLongOrNull() ?: error("无法读取实际缓存清理结果")
            total += freed.coerceAtLeast(0)
            mutex.withLock { sizes[id] = sizeOf(config(id)); publish() }
        } }
        return total
    }
    private fun manager(distro: String): String = when (distro) {
        "ubuntu", "debian", "kali" -> "apt"; "alpine" -> "apk"; "arch", "archlinux", "archarm" -> "pacman"; "fedora" -> "dnf"; else -> error("发行版 $distro 未配置受支持的包管理器")
    }

    override fun close() {
        runBlocking(Dispatchers.IO) { mutex.withLock {
            if (closed) return@withLock
            val failures = mutableListOf<Throwable>()
            configurations.keys.toList().forEach { id -> try { stopLocked(id) } catch (failure: Throwable) { failures += failure } }
            if (failures.isNotEmpty()) throw IllegalStateException("部分容器未能安全关闭；请恢复 Root 权限后重试，进程与挂载记录已保留", failures.first()).also { error -> failures.drop(1).forEach(error::addSuppressed) }
            closed = true
            scope.cancel()
        } }
    }

    private data class Tool(val id: String, val title: String, val icon: String, val probe: String, val version: String, val packages: Map<String, String>)
    private companion object {
        fun packages(apt: String, apk: String = apt, pacman: String = apt, dnf: String = apt) = mapOf("apt" to apt, "apk" to apk, "pacman" to pacman, "dnf" to dnf)
        val tools = listOf(
            Tool("git", "Git", "account_tree", "git", "git --version", packages("git")),
            Tool("node", "Node.js", "javascript", "node", "node --version", packages("nodejs npm")),
            Tool("python", "Python", "code", "python3", "python3 --version", packages("python3 python3-pip", "python3 py3-pip", "python python-pip", "python3 python3-pip")),
            Tool("rust", "Rust", "settings", "rustc", "rustc --version", packages("rustc cargo", "rust cargo", "rust", "rust cargo")),
            Tool("go", "Go", "code_blocks", "go", "go version", packages("golang", "go", "go", "golang")),
            Tool("jdk", "JDK", "coffee", "java", "java -version", packages("default-jdk", "openjdk21", "jdk-openjdk", "java-21-openjdk-devel")),
            Tool("cpp", "C++", "terminal", "g++", "g++ --version", packages("build-essential", "build-base", "base-devel", "gcc-c++ make")),
            Tool("neovim", "Neovim", "edit_note", "nvim", "nvim --version", packages("neovim")),
        )
    }
}


/** Starttime pins ownership even when a PID is recycled. Root commands use privileged
 * snapshots because Android hides daemon-spawned su children from an ordinary app. */
private class TrackedProcess(
    val process: Process,
    val keeper: Boolean,
    rootIdentity: Pair<Long, String>? = null,
    private val privilegedSnapshot: ((List<Long>) -> List<String>)? = null,
    val terminal: PtyTransport? = null,
) {
    @Volatile var stopping = false
    var outputDrainer: Job? = null
    val pid: Long? = rootIdentity?.first ?: findPid(process)
    private val identities = java.util.concurrent.ConcurrentHashMap<Long, String>()
    private var rootLive = emptyList<Long>()
    private var snapshotTime = 0L
    init {
        if (rootIdentity != null) identities[rootIdentity.first] = rootIdentity.second
        else pid?.let { readStat(it)?.let { stat -> identities[it] = stat.start } }
        require(identities.isNotEmpty() || !process.isAlive) { "无法读取容器进程 /proc 身份，拒绝启动不受跟踪的进程" }
        refreshDescendants()
    }

    @Synchronized fun refreshDescendants() {
        if (privilegedSnapshot != null) { refreshPrivileged(true); return }
        val pending = java.util.ArrayDeque<Long>(livePids())
        val seen = mutableSetOf<Long>()
        while (pending.isNotEmpty()) {
            val parent = pending.removeFirst()
            if (!seen.add(parent)) continue
            File("/proc/$parent/task").listFiles().orEmpty().forEach { task ->
                val children = try { File(task, "children").readText() } catch (_: Exception) { "" }
                children.trim().split(Regex("\\s+")).mapNotNull(String::toLongOrNull).forEach childLoop@ { child ->
                    val stat = readStat(child) ?: return@childLoop
                    identities.putIfAbsent(child, stat.start)
                    pending.add(child)
                }
            }
        }
    }

    private fun refreshPrivileged(force: Boolean) {
        val inspector = privilegedSnapshot ?: return
        val now = System.nanoTime()
        if (!force && now - snapshotTime < 150_000_000) return
        val snapshot = inspector(identities.keys.toList()).mapNotNull(::parseStat).associateBy { it.pid }
        val accepted = identities.entries.filter { (pid, start) -> snapshot[pid]?.start == start }.map { it.key }.toMutableSet()
        var changed: Boolean
        do {
            changed = false
            snapshot.values.forEach { stat ->
                if (stat.parent in accepted && accepted.add(stat.pid)) { identities[stat.pid] = stat.start; changed = true }
            }
        } while (changed)
        rootLive = accepted.filter { snapshot[it]?.state != "Z" }
        snapshotTime = now
    }

    @Synchronized fun livePids(): List<Long> {
        if (privilegedSnapshot != null) { refreshPrivileged(false); return rootLive }
        return identities.entries.filter { (pid, start) -> readStat(pid)?.let { it.start == start && it.state != "Z" } == true }.map { it.key }
    }
    fun liveIdentities(): List<Pair<Long, String>> = livePids().mapNotNull { pid -> identities[pid]?.let { pid to it } }
    fun hasLiveProcesses(): Boolean = process.isAlive || livePids().isNotEmpty()

    companion object {
        private data class ProcStat(val pid: Long, val parent: Long, val start: String, val state: String)
        private fun parseStat(text: String): ProcStat? {
            val pid = text.substringBefore(' ').toLongOrNull() ?: return null
            val fields = text.substringAfterLast(") ").split(' ')
            val parent = fields.getOrNull(1)?.toLongOrNull() ?: return null
            val start = fields.getOrNull(19)?.takeIf { it.toLongOrNull() != null } ?: return null
            return ProcStat(pid, parent, start, fields[0])
        }
        private fun readStat(pid: Long): ProcStat? = try { parseStat(File("/proc/$pid/stat").readText()) } catch (_: Exception) { null }
        fun handshake(file: File): Pair<Long, String>? = try {
            val lines = file.readLines()
            val pid = lines.firstOrNull()?.toLongOrNull()
            val stat = lines.getOrNull(1)?.let(::parseStat)
            if (pid != null && pid > 0 && stat != null && stat.pid == pid) pid to stat.start else null
        } catch (_: Exception) { null }
        private fun findPid(process: Process): Long? {
            try { return (Process::class.java.getMethod("pid").invoke(process) as Number).toLong() } catch (_: Exception) { }
            Regex("(?:pid=|pid: ?)([0-9]+)").find(process.toString())?.groupValues?.get(1)?.toLongOrNull()?.let { return it }
            return try { process.javaClass.getDeclaredField("pid").apply { isAccessible = true }.getInt(process).toLong() } catch (_: Exception) { null }
        }
    }
}
