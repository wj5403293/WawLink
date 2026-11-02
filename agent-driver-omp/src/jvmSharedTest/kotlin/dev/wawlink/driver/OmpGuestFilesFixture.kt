package dev.wawlink.driver

import dev.wawlink.runtime.*
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonPrimitive

/** Real worker/files with mapped test paths; an optional open gate controls cancellation timing. */
internal object OmpGuestFilesFixture {
    fun launch(arguments: List<String>, root: File, environment: Map<String, String> = emptyMap(), holdWriteOpen: Boolean = false): Process? {
        if (arguments.getOrNull(0) != "/usr/local/bin/bun" || arguments.getOrNull(1) != "-e") return null
        val prefix = """
            import testfs from 'node:fs/promises';
            import { join as testJoin } from 'node:path';
            const testRoot = ${JsonPrimitive(root.canonicalPath)};
            const mapTestPath = value => typeof value === 'string' && value.startsWith('/') ? testJoin(testRoot, value) : value;
            for (const name of ['lstat','stat','open','mkdir','readdir','opendir','unlink','rmdir','readFile','chmod']) {
              const original = testfs[name].bind(testfs);
              testfs[name] = (path, ...args) => original(mapTestPath(path), ...args);
            }
            for (const name of ['rename','link']) {
              const original = testfs[name].bind(testfs);
              testfs[name] = (source, target, ...args) => original(mapTestPath(source), mapTestPath(target), ...args);
            }
            const testRealpath = testfs.realpath.bind(testfs);
            testfs.realpath = async path => {
              const result = await testRealpath(mapTestPath(path));
              if (result !== testRoot && !result.startsWith(testRoot + '/')) throw Object.assign(Error('escape'), {code:'ELOOP',path});
              return result.slice(testRoot.length) || '/';
            };
            if ($holdWriteOpen) {
              const testOpen = testfs.open;
              testfs.open = async (path, ...args) => {
                const handle = await testOpen(path, ...args);
                if (typeof path === 'string' && /\/\.wawlink-[^/]+\.tmp${'$'}/.test(path)) {
                  await testfs.writeFile(testJoin(testRoot, '.write-opened'), '');
                  while (!(await testfs.stat('/.write-released').catch(error => { if (error.code !== 'ENOENT') throw error; return null; }))) {
                    await new Promise(resolve => setTimeout(resolve, 10));
                  }
                }
                return handle;
              };
            }
        """.trimIndent()
        return ProcessBuilder("node", "--input-type=module", "-e", prefix + "\n" + arguments[2]).apply {
            environment().putAll(environment)
        }.start()
    }
}

internal class TestOmpGuestFiles(home: String = "/root", private val holdWriteOpen: Boolean = false) : AutoCloseable {
    val root: File = Files.createTempDirectory("wawlink-guest-files-test").toFile()
    val guestDirectory = "${home.trimEnd('/')}/.omp/agent"
    val directory: File = File(root, guestDirectory.trimStart('/')).apply { mkdirs() }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val local = LocalContainerRuntime(RuntimePaths(File(root, "host-runtime")))
    private val children = CopyOnWriteArrayList<Process>()
    private val config = ContainerConfig("fixture", "验证", root.path, environment = mapOf("HOME" to home))
    val state = MutableStateFlow(listOf(ContainerInfo(config, running = true, runGeneration = 1)))
    var starts = 0
        private set
    val runtime: ContainerRuntime = object : ContainerRuntime by local {
        override val containers = state
        override suspend fun openProcess(id: String, arguments: List<String>, cwd: String, environment: Map<String, String>): Process {
            if (!state.value.single().running) { starts++; restart() }
            return requireNotNull(OmpGuestFilesFixture.launch(arguments, root, environment, holdWriteOpen)).also(children::add)
        }
        override suspend fun openRunningProcess(id: String, arguments: List<String>, cwd: String, environment: Map<String, String>): Process {
            check(state.value.single().running)
            return openProcess(id, arguments, cwd, environment)
        }
    }
    val files = OmpGuestFiles(runtime, config.id)
    fun stop() {
        state.value = state.value.map { it.copy(running = false, stopGeneration = it.stopGeneration + 1) }
        children.forEach { it.destroy() }
    }
    fun restart() { state.value = state.value.map { it.copy(running = true, runGeneration = it.runGeneration + 1) } }
    fun crashWorker() { children.forEach { it.destroyForcibly().waitFor() } }
    suspend fun awaitWriteOpened() = withTimeout(5_000) { while (!File(root, ".write-opened").exists()) delay(10) }
    fun releaseWrite() { File(root, ".write-released").writeText("") }
    override fun close() {
        files.close(); stop(); scope.cancel(); local.close()
        children.forEach { if (it.isAlive) it.destroyForcibly() }
        root.deleteRecursively()
    }
}
