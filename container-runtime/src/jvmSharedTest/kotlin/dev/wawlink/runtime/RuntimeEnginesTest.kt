package dev.wawlink.runtime

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuntimeEnginesTest {
    private fun fixture(block: (File, File, File, File) -> Unit) {
        val directory = Files.createTempDirectory("wawlink-engines-test").toFile()
        val data = File(directory, "app-data").apply { mkdirs() }
        val root = File(directory, "root").apply { mkdirs() }
        val native = File(directory, "native").apply { mkdirs() }
        File(root, "etc").mkdirs()
        File(root, "bin").mkdirs()
        File(root, "bin/sh").apply { writeText("fixture only; never executed\n"); setExecutable(true) }
        File(root, "etc/passwd").writeText("root:x:0:0::/root:/bin/sh\nalice:x:1001:1002::/home/alice:/bin/sh\n")
        // Argument-construction tests never execute these fixtures or any runtime command.
        listOf("libproroot.so", "libproroot-runtime.so", "libproroot-linker.so", "libproroot-bridge.so", "libproroot-stub-loader.so").forEach {
            File(native, it).apply { writeText("fixture only; never executed\n"); setExecutable(true) }
        }
        try { block(directory, data, root, native) } finally { RootfsArchives.deleteTree(directory) }
    }

    private fun binds(command: List<String>): List<String> = command.take(command.indexOf("/usr/bin/env"))
        .windowed(2).filter { it[0] == "-b" }.map { it[1] }

    @Test fun prorootUsesExplicitBindPairsAndPreservesRuntimeEnvironment() = fixture { directory, data, root, native ->
        val source = File(directory, "host files").apply { mkdirs() }
        val alias = File(directory, "mount-alias")
        Files.createSymbolicLink(alias.toPath(), source.toPath())
        val config = ContainerConfig(
            id = "proroot-test", name = "Proroot", rootfsPath = root.path,
            engine = ContainerEngineType.PROROOT, shell = "/bin/sh",
            mounts = listOf(ContainerMount(alias.path, "/mnt/shared files")),
            environment = mapOf("CUSTOM" to "configured", "CONFIG_ONLY" to "present"),
        )
        val arguments = listOf("/bin/sh", "-c", "printf '%s' \"\$CUSTOM\"", "quoted argument '")
        val cwd = "/workspace/it's here"
        val builder = RuntimeEngines(RuntimePaths(data, native, isAndroid = true))
            .builder(config, arguments, cwd, mapOf("CUSTOM" to "per-process"), mergeError = false)
        val command = builder.command()
        val guestIndex = command.indexOf("/usr/bin/env")
        val bindings = binds(command)
        listOf("/dev", "/proc", "/sys").filter { File(it).exists() }.forEach {
            assertTrue("$it:$it" in bindings)
        }
        assertTrue("${source.canonicalPath}:/mnt/shared files" in bindings)
        assertTrue(bindings.all { it.count { character -> character == ':' } == 1 && it.split(':').all { path -> path.startsWith('/') } })
        // Keep launcher-injected LD_PRELOAD/PROROOT_* across env -> shell -> guest execs.
        assertEquals("HOME=/root", command[guestIndex + 1])
        assertFalse("-i" in command.subList(guestIndex + 1, command.indexOf("/bin/sh")))
        assertTrue("CUSTOM=per-process" in command)
        assertTrue("CONFIG_ONLY=present" in command)
        assertFalse("CUSTOM=configured" in command)
        assertEquals(File(data, "tmp").path, builder.environment()["PROROOT_TMP_DIR"])
        assertTrue(File(builder.environment().getValue("PROROOT_TMP_DIR")).isDirectory)
        assertFalse("LD_LIBRARY_PATH" in builder.environment())
        assertFalse("LD_PRELOAD" in builder.environment())
    }

    @Test fun prorootResolvesNamedAndNumericAccounts() = fixture { _, data, root, native ->
        val engines = RuntimeEngines(RuntimePaths(data, native, isAndroid = true))
        for (account in listOf("alice", "1001")) {
            val config = ContainerConfig("proroot-user", "Proroot", root.path,
                engine = ContainerEngineType.PROROOT, user = account, shell = "/bin/sh")
            val command = engines.builder(config, listOf("/bin/sh"), "/home/alice", emptyMap(), mergeError = true).command()
            val launcher = command.take(command.indexOf("/usr/bin/env"))
            assertEquals("1001:1002", launcher[launcher.indexOf("-i") + 1])
            assertFalse("-0" in launcher)
            assertTrue("HOME=/home/alice" in command)
            assertTrue("USER=alice" in command)
            assertTrue("LOGNAME=alice" in command)
            assertEquals("/home/alice", containerHome(config))
        }
    }

    @Test fun prorootHomeOverridePreservesResolvedAccountIdentity() = fixture { _, data, root, native ->
        val config = ContainerConfig("proroot-home", "Proroot", root.path,
            engine = ContainerEngineType.PROROOT, user = "1001", shell = "/bin/sh",
            environment = mapOf("HOME" to "/srv/alice profile"))
        val engines = RuntimeEngines(RuntimePaths(data, native, isAndroid = true))
        val command = engines.builder(config, listOf("/bin/sh"), "/", emptyMap(), mergeError = true).command()
        assertEquals("/srv/alice profile", containerHome(config))
        assertEquals("/srv/alice profile", engines.home(config))
        assertTrue("HOME=/srv/alice profile" in command)
        assertTrue("USER=alice" in command)
        assertTrue("LOGNAME=alice" in command)
        assertEquals("1001:1002", command[command.indexOf("-i") + 1])
    }

    @Test fun offlineHomeRejectsUnknownAccountsAndInvalidOverrides() = fixture { _, _, root, _ ->
        val passwd = File(root, "etc/passwd")
        val original = passwd.readText()
        val config = ContainerConfig("home-test", "用户", root.path, user = "alice", shell = "/bin/sh")
        for (account in listOf("missing-user", "9876")) {
            assertFailsWith<IllegalStateException> { containerHome(config.copy(user = account)) }
        }
        for (home in listOf("relative", "//host", "/home/../escape", "/home/./alice", "/home/\u0000alice")) {
            assertFailsWith<IllegalArgumentException> { containerHome(config.copy(environment = mapOf("HOME" to home))) }
        }
        assertEquals(original, passwd.readText())
    }

    @Test fun mappedRootValidationRetainsTheActuallyMissingPathWithoutEscalatingToRoot() {
        for (missing in listOf("etc", "bin/sh")) fixture { _, data, root, _ ->
            RootfsArchives.deleteTree(File(root, missing))
            val config = ContainerConfig("missing-entry", "缺失路径", root.path)
            val engines = RuntimeEngines(RuntimePaths(data)) { error("Missing paths must not request Root") }
            val failure = assertFailsWith<IllegalArgumentException> { engines.validateRoot(config) }
            assertTrue(failure.cause is java.nio.file.NoSuchFileException)
            assertTrue(failure.message.orEmpty().contains(File(root, missing).path))
            assertFalse(File(root, missing).exists())
        }
    }

    @Test fun mappedRootValidationUsesRootOnlyForInaccessibleStructureWithoutChangingItsMode() = fixture { directory, data, root, _ ->
        val config = ContainerConfig("restricted-entry", "私有根目录", root.path)
        RestrictedRootShell(directory, root).use { shell ->
            if (!shell.appTraversalDenied) return@use
            val engines = RuntimeEngines(RuntimePaths(data), shell::run)
            assertEquals(root, engines.validateRoot(config))
            assertEquals(0, shell.mode(root))
            shell.inspect { assertEquals("fixture only; never executed\n", File(root, "bin/sh").readText()) }
        }
    }

    @Test fun rootValidationAcceptsInternalMergedUsrButRejectsHostShellLinksForBothAccessPaths() = fixture { directory, data, root, _ ->
        val shell = File(root, "usr/bin/sh").apply { parentFile.mkdirs(); writeText("guest shell") }
        RootfsArchives.deleteTree(File(root, "bin"))
        Files.createSymbolicLink(File(root, "bin").toPath(), File("usr/bin").toPath())
        val config = ContainerConfig("linked-entry", "合并 usr", root.path)
        val engines = RuntimeEngines(RuntimePaths(data)) { command ->
            HostCommands.run(listOf("/bin/sh", "-c", "id() { printf '0\\n'; };\n$command"))
        }
        for (engine in listOf(ContainerEngineType.PROOT, ContainerEngineType.CHROOT)) {
            assertEquals(root, engines.validateRoot(config.copy(engine = engine)))
        }
        val outside = File(directory, "host-shell").apply { writeText("host must remain untouched") }
        Files.delete(shell.toPath())
        Files.createSymbolicLink(shell.toPath(), outside.toPath())
        assertFailsWith<IllegalArgumentException> { engines.validateRoot(config) }
        assertFailsWith<IllegalStateException> { engines.validateRoot(config.copy(engine = ContainerEngineType.CHROOT)) }
        assertEquals("host must remain untouched", outside.readText())
    }

    @Test fun prootKeepsIdentityFlagsAndCleanGuestEnvironment() = fixture { _, data, root, _ ->
        val config = ContainerConfig(
            id = "proot-test", name = "PRoot", rootfsPath = root.path,
            arch = System.getProperty("os.arch"), user = "1001", shell = "/bin/sh",
            link2symlink = false, sysvipc = true, fakeKernel = true,
        )
        val command = RuntimeEngines(RuntimePaths(data, hostProot = "proot"))
            .builder(config, listOf("/bin/sh"), "/home/alice", emptyMap(), mergeError = true).command()
        val guestIndex = command.indexOf("/usr/bin/env")
        assertEquals("proot", command.first())
        assertEquals("1001:1002", command[command.indexOf("-i") + 1])
        assertTrue("--sysvipc" in command)
        assertEquals("6.8.0-generic", command[command.indexOf("-k") + 1])
        assertFalse("--link2symlink" in command)
        assertFalse("-q" in command)
        assertEquals("-i", command[guestIndex + 1])
        assertTrue("HOME=/home/alice" in command)
        assertTrue("USER=alice" in command)
        assertTrue("LOGNAME=alice" in command)
        listOf("/dev", "/proc", "/sys").filter { File(it).exists() }.forEach {
            assertTrue("$it:$it" in binds(command))
        }
        if (File("/etc/resolv.conf").isFile) assertTrue("/etc/resolv.conf:/etc/resolv.conf" in binds(command))
    }

    @Test fun prorootRejectsDesktopInsteadOfFallingBack() = fixture { _, data, root, _ ->
        val config = ContainerConfig(
            id = "unsupported", name = "Proroot", rootfsPath = root.path,
            engine = ContainerEngineType.PROROOT, shell = "/bin/sh",
        )
        assertFailsWith<IllegalArgumentException> {
            RuntimeEngines(RuntimePaths(data)).validate(config)
        }
    }

    @Test fun featureActionsRemainOptInAndSpanIsStrict() {
        val old = Json.parseToJsonElement("{}").jsonObject
        assertEquals(1, parseFeatureCardSpan(old))
        assertTrue(parseFeatureActions(old).isEmpty())
        for (span in listOf(1, 2)) {
            assertEquals(span, parseFeatureCardSpan(Json.parseToJsonElement("{\"cardSpan\":$span}").jsonObject))
        }
        for (span in listOf("0", "3", "1.5", "\"2\"", "true", "null")) {
            assertFailsWith<IllegalArgumentException> {
                parseFeatureCardSpan(Json.parseToJsonElement("{\"cardSpan\":$span}").jsonObject)
            }
        }
    }

    @Test fun featureActionSchemaRejectsDuplicatesOverflowAndInvalidPayloads() {
        fun parse(actions: String) = parseFeatureActions(Json.parseToJsonElement("{\"actions\":$actions}").jsonObject)
        val open = """{"id":"open","title":"打开","icon":"open_in_new","type":"open"}"""
        assertEquals(ContainerFeatureActionType.OPEN, parse("[$open]").single().type)
        val script = """{"id":"run","title":"运行","icon":"terminal","type":"script","script":"tasks/run.sh"}"""
        assertEquals("tasks/run.sh", parse("[$script]").single().script)
        val lua = """{"id":"native","title":"原生操作","icon":"android","type":"lua","lua":"return 1"}"""
        assertEquals("return 1", parse("[$lua]").single().lua)
        for (invalid in listOf(
            "[$open,$open]", "[$open,$script,$lua,$open]", "{}",
            "[$open]".replace("open_in_new", "Bad-Icon"),
            "[$open]".replace("\"id\":\"open\"", "\"id\":\"Bad\""),
            "[$open]".replace("\"type\":\"open\"", "\"type\":\"OPEN\""),
            "[$open]".replace("\"title\":\"打开\"", "\"title\":false"),
            "[$open]".replace("\"type\":\"open\"", "\"type\":\"script\""),
            "[$script]".replace("tasks/run.sh", "../run.sh"),
            "[$script]".replace("tasks/run.sh", "/run.sh"),
            "[$lua]".replace("return 1", "   "),
        )) assertFailsWith<IllegalArgumentException>(invalid) { parse(invalid) }
    }

    @Test fun featureScriptActionsEnforceCurrentHomeExactDirectoryAndLinkBoundary() {
        val root = Files.createTempDirectory("wawlink-feature-action-test").toFile()
        try {
            val home = File(root, "home").apply { mkdirs() }
            val directory = File(home, ".wawlink/features/demo").apply { mkdirs() }
            val feature = ContainerFeature("demo", "功能", "描述", "terminal", directory.canonicalPath)
            val outside = File(root, "outside.sh").apply { writeText("printf 'ESCAPED'\n") }
            val script = File(directory, "run.sh").apply { writeText("printf '%s|%s' \"${'$'}PWD\" \"${'$'}WAWLINK_ACTION_ID\"\n") }
            fun run(selected: ContainerFeature = feature, selectedHome: File = home): Pair<Int, String> {
                val process = ProcessBuilder("/bin/sh", "-c", featureScriptActionSupervisor(selected, "run.sh"))
                    .redirectErrorStream(true).apply {
                        environment()["HOME"] = selectedHome.path
                        environment()["WAWLINK_ACTION_ID"] = "run"
                    }.start()
                try {
                    assertTrue(process.waitFor(10, TimeUnit.SECONDS), "动作监管进程未退出")
                    return process.exitValue() to process.inputStream.bufferedReader().use { it.readText() }
                } finally {
                    process.outputStream.close()
                    if (process.isAlive) process.destroyForcibly()
                }
            }
            val success = run()
            assertEquals(0, success.first)
            assertEquals("${directory.canonicalPath}|run", success.second)
            assertTrue(run(selectedHome = File(root, "other-home").apply { mkdirs() }).first != 0)
            val alias = File(home, ".wawlink/features/alias")
            Files.createSymbolicLink(alias.toPath(), directory.toPath())
            assertTrue(run(feature.copy(id = "alias", directory = alias.path)).first != 0)
            assertTrue(script.delete())
            Files.createSymbolicLink(script.toPath(), outside.toPath())
            val escaped = run()
            assertTrue(escaped.first != 0)
            assertFalse("ESCAPED" in escaped.second)
        } finally { RootfsArchives.deleteTree(root) }
    }

    @Test fun featureScriptSupervisorRecyclesItsProcessGroupOnCancellation() {
        val home = Files.createTempDirectory("wawlink-feature-cancel-test").toFile()
        try {
            val directory = File(home, ".wawlink/features/demo").apply { mkdirs() }
            File(directory, "run.sh").writeText("""
                trap 'exit 0' TERM
                (
                    trap 'printf stopped > ./child-stopped; exit 0' TERM
                    printf ready > ./child-ready
                    while :; do sleep 1; done
                ) &
                wait
            """.trimIndent())
            val feature = ContainerFeature("demo", "功能", "描述", "terminal", directory.canonicalPath)
            val process = ProcessBuilder("/bin/sh", "-c", featureScriptActionSupervisor(feature, "run.sh"))
                .redirectErrorStream(true).apply { environment()["HOME"] = home.path }.start()
            try {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (!File(directory, "child-ready").isFile && process.isAlive && System.nanoTime() < deadline) Thread.sleep(20)
                assertTrue(File(directory, "child-ready").isFile)
                process.outputStream.close()
                assertTrue(process.waitFor(10, TimeUnit.SECONDS), "取消后监管进程未退出")
                assertTrue(File(directory, "child-stopped").isFile, "取消必须终止脚本子进程，而非仅关闭宿主 Process")
            } finally {
                process.outputStream.close()
                if (process.isAlive) process.destroyForcibly()
            }
        } finally { RootfsArchives.deleteTree(home) }
    }
    @Test fun mappedStartupRepairsManagedOwnerAccessWithoutOpeningPrivateDataOrWalkingDependencies() = fixture { directory, data, root, _ ->
        val home = File(root, "srv/selected-home").apply { mkdirs() }
        val bin = File(home, ".wawlink/bin").apply { mkdirs() }
        val preferences = File(home, ".wawlink/feature-cards.json").apply { writeText("{\"hidden\":[\"demo\"]}") }
        val agent = File(home, ".omp/agent").apply { mkdirs() }
        val settings = File(agent, "config.yml").apply { writeText("model: preserved") }
        val session = File(agent, "wawlink/sessions/native/events.jsonl").apply { parentFile.mkdirs(); writeText("history") }
        val builtin = File(agent, "skills/wawlink-functions/SKILL.md").apply { parentFile.mkdirs(); writeText("managed Skill") }
        val dependencies = listOf("plugins/custom/node_modules/keep", "skills/custom/keep", "wawlink/node_modules/keep")
            .map { File(agent, it).apply { parentFile.mkdirs(); writeText("third-party") } }
        val workspace = File(root, "workspace").apply { mkdirs() }
        val project = File(workspace, "private").apply { writeText("project") }
        val outside = File(directory, "outside").apply { writeText("outside") }
        Files.createLink(File(agent, "hardlink").toPath(), outside.toPath())
        Files.createSymbolicLink(File(agent, "link").toPath(), outside.toPath())
        val mounted = File(agent, "wawlink/mounted").apply { mkdirs() }
        val bound = File(mounted, "keep").apply { writeText("bound") }
        for (file in dependencies + project + outside + bound + preferences + settings + session + builtin) {
            Files.setAttribute(file.toPath(), "unix:mode", 0x100) // 0400
        }
        Files.setAttribute(home.toPath(), "unix:mode", 0x1C0) // 0700 is already sufficient for the actual App owner
        Files.setAttribute(bin.toPath(), "unix:mode", 0xC0) // 0300: writable but debugger O_RDONLY open fails
        for (folder in listOf(agent, workspace)) Files.setAttribute(folder.toPath(), "unix:mode", 0x140) // 0500
        val config = ContainerConfig("mapped-modes", "权限", root.path, arch = System.getProperty("os.arch"),
            engine = ContainerEngineType.PROOT, user = "alice", shell = "/bin/sh", link2symlink = false,
            environment = mapOf("HOME" to "/srv/selected-home"),
            mounts = listOf(ContainerMount(directory.path, "/srv/selected-home/.omp/agent/wawlink/mounted")))
        try { RuntimeEngines(RuntimePaths(data, hostProot = "/bin/true")).prepare(config) }
        catch (failure: Throwable) {
            for (folder in listOf(bin, agent, workspace)) Files.setAttribute(folder.toPath(), "unix:mode", 0x1C0)
            throw failure
        }
        assertEquals(0x1C0, (Files.getAttribute(home.toPath(), "unix:mode") as Number).toInt() and 0x1FF)
        for (folder in listOf(bin, agent, workspace)) {
            assertEquals(0x1C0, (Files.getAttribute(folder.toPath(), "unix:mode") as Number).toInt() and 0x1FF)
            val probe = File(folder, "write-probe").apply { writeText("writable") }
            assertEquals("writable", probe.readText())
        }
        for (file in listOf(preferences, settings, session, builtin)) {
            assertEquals(0x180, (Files.getAttribute(file.toPath(), "unix:mode") as Number).toInt() and 0x1FF)
            file.appendText("\nupdated")
        }
        assertEquals("model: preserved\nupdated", settings.readText())
        assertEquals("history\nupdated", session.readText())
        for (file in dependencies + project + outside + bound) {
            assertEquals(0x100, (Files.getAttribute(file.toPath(), "unix:mode") as Number).toInt() and 0x1FF, file.path)
        }
        assertEquals("outside", outside.readText())
    }

    @Test fun mappedHostWritesPrepareStrictParentsBeforeStartingAnyGuest() = fixture { _, _, root, _ ->
        for (engine in listOf(ContainerEngineType.PROOT, ContainerEngineType.PROROOT)) {
            val home = File(root, "selected-${engine.name}").apply { mkdirs() }
            val omp = File(home, ".omp").apply { mkdirs() }
            Files.setAttribute(omp.toPath(), "unix:mode", 0x140)
            val config = ContainerConfig("host-write", "权限", root.path, engine = engine)
            val directory = File(omp, "agent/wawlink/sessions/active")
            prepareContainerHostDirectory(config, directory)
            val request = File(directory, "request.json").apply { writeText("original") }
            Files.setAttribute(request.toPath(), "unix:mode", 0x100)
            prepareContainerHostFile(config, request)
            request.writeText("request")
            assertEquals("request", request.readText())
            assertEquals(0x180, (Files.getAttribute(request.toPath(), "unix:mode") as Number).toInt() and 0x1FF)
            assertEquals(0x1C0, (Files.getAttribute(omp.toPath(), "unix:mode") as Number).toInt() and 0x1FF)
        }
    }

    @Test fun concurrentConfigurationAndSessionWritersCanShareNewManagedParents() = fixture { _, _, root, _ ->
        val pool = java.util.concurrent.Executors.newFixedThreadPool(12)
        try {
            for (engine in listOf(ContainerEngineType.PROOT, ContainerEngineType.PROROOT)) {
                val ready = java.util.concurrent.CountDownLatch(12)
                val start = java.util.concurrent.CountDownLatch(1)
                val config = ContainerConfig("concurrent", "并发写入", root.path, engine = engine)
                val directory = File(root, "selected-${engine.name}/.omp/agent/wawlink/sessions/active")
                val writers = (1..12).map { index -> pool.submit {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS))
                    prepareContainerHostDirectory(config, directory)
                    val request = File(directory, "request-$index.json")
                    check(request.createNewFile())
                    prepareContainerHostFile(config, request)
                    request.writeText("request-$index")
                } }
                assertTrue(ready.await(5, TimeUnit.SECONDS))
                start.countDown()
                writers.forEach { it.get(10, TimeUnit.SECONDS) }
                for (index in 1..12) assertEquals("request-$index", File(directory, "request-$index.json").readText())
            }
        } finally { pool.shutdownNow() }
    }

    @Test fun unsafeOptionalSupportIsLeftForItsInstallerWithoutBlockingNativeEngineAdmission() = fixture { directory, data, root, _ ->
        val home = File(root, "root").apply { mkdirs() }
        val outside = File(directory, "unmanaged").apply { mkdirs() }
        val user = File(outside, "keep").apply { writeText("user data"); Files.setAttribute(toPath(), "unix:mode", 0x100) }
        File(home, ".wawlink").mkdirs()
        for (name in listOf("bin", "feature-debug", "features")) {
            Files.createSymbolicLink(File(home, ".wawlink/$name").toPath(), outside.toPath())
        }
        File(home, ".omp/agent").mkdirs()
        Files.createSymbolicLink(File(home, ".omp/agent/skills").toPath(), outside.toPath())
        val config = ContainerConfig("optional", "辅助路径隔离", root.path, engine = ContainerEngineType.PROOT,
            arch = System.getProperty("os.arch"), shell = "/bin/sh", link2symlink = false)
        RuntimeEngines(RuntimePaths(data, hostProot = "/bin/true")).prepare(config)
        File(home, ".omp/agent/config.yml").writeText("native: writable")
        assertEquals("native: writable", File(home, ".omp/agent/config.yml").readText())
        assertEquals("user data", user.readText())
        assertEquals(0x100, (Files.getAttribute(user.toPath(), "unix:mode") as Number).toInt() and 0x1FF)
    }

    @Test fun mappedManagementLinksCannotChangeOutsidePermissions() = fixture { directory, _, root, _ ->
        val home = File(root, "root").apply { mkdirs() }
        val outside = File(directory, "outside").apply { mkdirs() }
        val file = File(outside, "config.yml").apply { writeText("private"); Files.setAttribute(toPath(), "unix:mode", 0x100) }
        Files.createSymbolicLink(File(home, ".omp").toPath(), outside.toPath())
        val config = ContainerConfig("linked", "链接", root.path, engine = ContainerEngineType.PROOT)
        assertFailsWith<IllegalArgumentException> { prepareMappedOwnership(config, root, "/root") }
        assertEquals(0x100, (Files.getAttribute(file.toPath(), "unix:mode") as Number).toInt() and 0x1FF)
        assertEquals("private", file.readText())
    }
}
