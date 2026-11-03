package dev.wawlink.runtime

import java.io.File
import java.nio.file.Files
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.URLClassLoader
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Runs production prepare with real host shell/tools; only privileged mount/UID operations are simulated. */
class RuntimeChrootMountTest {
    private class Fixture(val directory: File, android: Boolean) {
        val data = File(directory, "app data").apply { mkdirs() }
        val root = File(directory, "root ' quoted \\ path").apply { mkdirs() }
        val source = File(directory, "host ' files \\ literal \$HOME").apply { mkdirs() }
        val target = File(root, "mnt/shared ' files \\ literal \$HOME")
        val state = File(directory, "mount state").apply { mkdirs() }
        private val shell = System.getenv("WAWLINK_TEST_HOST_SHELL") ?: "/bin/sh"
        private val tools = System.getenv("WAWLINK_TEST_HOST_TOOLS")?.let { executable ->
            val binary = File(executable).canonicalFile
            require(binary.canExecute()) { "WAWLINK_TEST_HOST_TOOLS must name an executable Toybox/BusyBox binary" }
            File(directory, "host tools").apply {
                mkdir()
                for (applet in listOf("readlink", "stat", "chown", "chgrp", "chmod", "mkdir", "mv", "ln", "id", "printf", "find", "xargs", "mktemp", "cp", "rm", "rmdir", "sha256sum", "base64", "tr", "cat")) {
                    Files.createSymbolicLink(File(this, applet).toPath(), binary.toPath())
                }
                Files.createSymbolicLink(File(this, "sh").toPath(), File(shell).canonicalFile.toPath())
            }
        }
        private val interceptors = File(directory, "interceptors").apply { mkdir() }
        private val privilegedTools = File(directory, "privileged tools").apply {
            mkdir()
            File(this, "chown").apply {
                writeText("#!$shell\n" + """
                    [ "${'$'}MOCK_FAILURE" != ownership ] || { echo 'ownership denied' >&2; exit 1; }
                    [ "${'$'}1" != -h ] || shift
                    shift
                    exec ${shellQuote(tool("chown"))} -h "${'$'}(${shellQuote(tool("id"))} -u):${'$'}(${shellQuote(tool("id"))} -g)" "${'$'}@"
                """.trimIndent())
                setExecutable(true)
            }
            File(this, "chgrp").apply {
                writeText("#!$shell\nshift\nexec ${shellQuote(tool("chgrp"))} \"${'$'}(${shellQuote(tool("id"))} -g)\" \"${'$'}@\"\n")
                setExecutable(true)
            }
        }
        private val log = File(directory, "arguments")
        var failure = ""
        var confirmReadOnly = true
        var uid = "0"
        val config = ContainerConfig(
            "chroot-mount-test", "Chroot", root.path, arch = System.getProperty("os.arch"),
            engine = ContainerEngineType.CHROOT, shell = "/bin/sh",
            mounts = listOf(ContainerMount(source.path, "/mnt/${target.name}", readWrite = false)),
        )
        val engines = RuntimeEngines(RuntimePaths(data, isAndroid = android)) { command ->
            val builder = builder(MOCK_COMMANDS + "\n" + command, privileged = true)
            builder.environment().apply {
                put("MOCK_STATE", state.path)
                put("MOCK_LOG", log.path)
                put("MOCK_TARGET", target.path)
                put("MOCK_FAILURE", failure)
                put("MOCK_RO", if (confirmReadOnly) "ro" else "rw")
                put("MOCK_UID", uid)
                put("MOCK_MOUNTINFO", mounts().joinToString("\n") { entry ->
                    val path = File(entry, "target").readText().trimEnd('\n')
                        .replace("\\", "\\134").replace(" ", "\\040")
                        .replace("\t", "\\011").replace("\n", "\\012")
                    "100 1 0:1 / $path ${File(entry, "options").readText().trim()} - mock mock rw"
                })
            }
            HostCommands.run(builder, 10)
        }

        init {
            File(root, "etc").mkdirs()
            File(root, "etc/passwd").writeText("root:x:0:0::/root:/bin/sh\n")
            File(root, "bin").mkdirs()
            File(root, "bin/sh").apply { writeText("fixture only; never executed\n"); setExecutable(true) }
            // A real /dev bind exposes pts. The fake bind must not touch the host /dev.
            File(root, "dev/pts").mkdirs()
            // Mount/managed-path tests explicitly simulate privileged UID operations; full-root
            // conversion is covered separately, not silently skipped by impersonating real Root.
            RootfsOwnership.save(root, OwnershipManifest(emptyMap(), physical = ContainerEngineType.CHROOT))
        }

        fun tool(name: String): String = File(tools ?: File("/bin"), name).path
        fun intercept(name: String, body: String) {
            File(interceptors, name).apply { writeText("#!$shell\n$body\n"); setExecutable(true) }
        }
        private fun builder(command: String, privileged: Boolean = false) = ProcessBuilder(shell, "-c", command).apply {
            environment()["PATH"] = listOfNotNull(interceptors.path, privilegedTools.path.takeIf { privileged },
                tools?.path ?: environment()["PATH"]).joinToString(":")
        }
        fun run(command: String, privileged: Boolean = false): String = HostCommands.run(builder(command, privileged), 15)

        fun rootProbe(operation: String): String {
            // This adapter exercises su dispatch and output collection without claiming Root.
            intercept("su", "exec ${shellQuote(shell)} \"${'$'}@\"")
            val classpath = (System.getProperty("java.class.path").split(File.pathSeparator) +
                generateSequence(javaClass.classLoader) { it.parent }.filterIsInstance<URLClassLoader>()
                    .flatMap { it.urLs.asSequence() }.map { File(it.toURI()).path }.toList())
                .distinct().joinToString(File.pathSeparator)
            return HostCommands.run(ProcessBuilder(
                File(System.getProperty("java.home"), "bin/java").path, "-cp", classpath,
                RuntimeChrootMountTest::class.java.name, operation, root.path, data.path,
            ).apply {
                environment()["PATH"] = listOf(interceptors.path, tools?.path ?: environment().getValue("PATH"))
                    .joinToString(":")
            }, timeout = 30)
        }

        fun mounts(): List<File> = state.listFiles().orEmpty().filter { it.isDirectory }
        fun calls(name: String): List<List<String>> = if (!log.exists()) emptyList() else
            log.readLines().map { it.split('\u0000').dropLast(1) }.filter { it.firstOrNull() == name }
        fun assertRolledBack(vararg relativeTargets: String) {
            assertTrue(mounts().isEmpty(), "失败后不应遗留模拟挂载")
            assertEquals(relativeTargets.map { listOf("umount", "-l", File(root, it).path) }, calls("umount"))
            val count = calls("umount").size
            engines.unmount(config.id)
            assertEquals(count, calls("umount").size, "成功回滚后必须释放所有权记录")
        }
    }

    private fun fixture(android: Boolean = true, block: (Fixture) -> Unit) {
        val directory = Files.createTempDirectory("wawlink-chroot-mount-test").toFile()
        try { block(Fixture(directory, android)) } finally { RootfsArchives.deleteTree(directory) }
    }
    private class FailingPipeProcess(private val failure: IOException, private val blockUntilDestroyed: Boolean) : Process() {
        private val started = CountDownLatch(1)
        private val exited = CountDownLatch(1)
        private val pipe = object : InputStream() {
            override fun available(): Int = 1
            override fun read(): Int = throw failure
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                started.countDown()
                if (blockUntilDestroyed) exited.await()
                throw failure
            }
        }
        override fun getInputStream(): InputStream = pipe
        override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun isAlive(): Boolean = exited.count != 0L
        override fun waitFor(): Int { exited.await(); return 137 }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            check(started.await(5, TimeUnit.SECONDS)) { "Reader did not start" }
            return exited.await(timeout, unit)
        }
        override fun exitValue(): Int { check(!isAlive); return 137 }
        override fun destroy() { exited.countDown() }
        override fun destroyForcibly(): Process { destroy(); return this }
    }

    private fun commandFailure(block: () -> Unit): Throwable {
        val uncaught = ConcurrentLinkedQueue<Throwable>()
        val group = object : ThreadGroup("host-command-regression") {
            override fun uncaughtException(thread: Thread, failure: Throwable) { uncaught.add(failure) }
        }
        val caught = AtomicReference<Throwable?>()
        val caller = Thread(group, { try { block() } catch (failure: Throwable) { caught.set(failure) } }, "command-caller")
        caller.start()
        caller.join(6000)
        assertFalse(caller.isAlive, "命令超时必须返回调用协程，不能等待输出线程")
        assertTrue(uncaught.isEmpty(), "后台线程不能把异常交给 Android UncaughtExceptionHandler：$uncaught")
        val threads = arrayOfNulls<Thread>(16)
        group.enumerate(threads, true)
        assertFalse(threads.any { it?.isAlive == true }, "命令返回后不得遗留读取线程")
        return requireNotNull(caught.get()) { "Expected command failure" }
    }

    @Test fun commandTimeoutKillsActualProcessAndReturnsFailureToCaller() {
        val process = ProcessBuilder("/bin/sleep", "5").start()
        val failure = commandFailure { HostCommands.collect(process, "sleep", 1) }
        assertTrue(failure is IllegalStateException)
        assertEquals("系统命令超时：sleep", failure.message)
        assertFalse(process.isAlive)
    }

    @Test fun interruptedPipeReadAfterTimeoutDoesNotEscapeReaderThread() {
        val process = FailingPipeProcess(InterruptedIOException("read interrupted by close() on another thread"), true)
        val failure = commandFailure { HostCommands.collect(process, "su", 1) }
        assertTrue(failure is IllegalStateException)
        assertEquals("系统命令超时：su", failure.message)
        assertFalse(process.isAlive)
    }

    @Test fun unexpectedPipeReadFailureReachesCallerWithItsCause() {
        val cause = IOException("active pipe failed")
        val process = FailingPipeProcess(cause, false)
        val failure = commandFailure { HostCommands.collect(process, "su", 2) }
        assertTrue(failure is IllegalStateException)
        assertEquals("系统命令输出读取失败：su：active pipe failed", failure.message)
        assertTrue(failure.cause === cause)
        assertFalse(process.isAlive)
    }

    @Test fun ownershipScanKeepsTheCompleteSnapshotBeyondOrdinaryRootOutputLimit() = fixture { fixture ->
        with(fixture) {
            repeat(1200) { index ->
                Files.createSymbolicLink(File(root, "large-link-$index").toPath(),
                    java.nio.file.Path.of("missing-$index/" + "x".repeat(3800)))
            }
            val expected = RootfsOwnership.snapshot(config).sortedBy { it.path }.joinToString("\n")
            assertEquals(RootfsHostFiles.revision(expected.toByteArray()), rootProbe("snapshot").trim())
        }
    }

    @Test fun ordinaryRootOutputStillFailsWhenItExceedsItsLimit() = fixture { fixture ->
        val failure = assertFailsWith<IllegalStateException> { fixture.rootProbe("ordinary") }
        assertTrue(failure.message.orEmpty().contains("阶段=其他 Root 操作；超时=45秒"))
        assertTrue(failure.message.orEmpty().contains("系统命令输出超过上限：su"))
    }

    @Test fun failedGeneratedScansRetainTheirStageDeadlineAndActualCause() {
        for ((operation, stage) in listOf("snapshot" to "属主扫描", "size" to "Rootfs体积扫描")) fixture { fixture ->
            fixture.intercept("stat", "echo 'scan metadata denied' >&2; exit 37")
            val failure = assertFailsWith<IllegalStateException> { fixture.rootProbe(operation) }
            assertTrue(failure.message.orEmpty().contains("阶段=$stage；超时=300秒"), failure.message)
            assertTrue(failure.message.orEmpty().contains("scan metadata denied"), failure.message)
        }
    }

    @Test fun androidPrepareUsesQuotedTwoOperandBindsAndConfirmsReadOnly() = fixture { fixture ->
        with(fixture) {
            failure = "private"
            engines.prepare(config)
            val calls = calls("mount")
            assertEquals(listOf(
                listOf("mount", "-t", "proc", "proc", File(root, "proc").path),
                listOf("mount", "-t", "sysfs", "sysfs", File(root, "sys").path),
                listOf("mount", "-o", "bind", "/dev", File(root, "dev").path),
                listOf("mount", "-t", "devpts", "devpts", File(root, "dev/pts").path),
                listOf("mount", "-o", "bind", source.canonicalPath, target.canonicalPath),
                listOf("mount", "-o", "remount,bind,ro", source.canonicalPath, target.canonicalPath),
            ), calls.filter { it.getOrNull(1) != "--make-private" })
            assertEquals(5, calls.count { it.getOrNull(1) == "--make-private" })
            val before = calls.size
            engines.prepare(config)
            assertEquals(before, calls("mount").size, "重复 prepare 不得重复挂载")
            engines.unmount(config.id)
            assertRolledBack("mnt/${target.name}", "dev/pts", "dev", "sys", "proc")
        }
    }

    @Test fun desktopKeepsMountPropagationProtection() = fixture(android = false) { fixture ->
        with(fixture) {
            failure = "private"
            val error = assertFailsWith<IllegalStateException> { engines.prepare(config) }
            assertTrue(error.message.orEmpty().startsWith("chroot Root 命令执行失败："))
            assertTrue("unknown option '--make-private'" in error.message.orEmpty())
            assertRolledBack("proc")
        }
    }

    @Test fun androidBindFailureRetainsActualErrorAndRollsBackPriorMounts() = fixture { fixture ->
        with(fixture) {
            failure = "bind"
            val error = assertFailsWith<IllegalStateException> { engines.prepare(config) }
            assertTrue("mount: bind denied for ${source.canonicalPath}" in error.message.orEmpty())
            assertTrue(error.cause?.message.orEmpty().contains("系统命令失败（32）"))
            assertFalse(error.message.orEmpty().contains("请授权"))
            assertRolledBack("dev/pts", "dev", "sys", "proc")
        }
    }

    @Test fun androidRemountFailureIsNotHiddenByOptionalPropagationStep() = fixture { fixture ->
        with(fixture) {
            failure = "remount"
            val error = assertFailsWith<IllegalStateException> { engines.prepare(config) }
            assertTrue("mount: read-only remount denied" in error.message.orEmpty())
            assertRolledBack("mnt/${target.name}", "dev/pts", "dev", "sys", "proc")
        }
    }

    @Test fun successfulRemountWithoutKernelReadOnlyConfirmationRollsBack() = fixture { fixture ->
        with(fixture) {
            confirmReadOnly = false
            val error = assertFailsWith<IllegalArgumentException> { engines.prepare(config) }
            assertEquals("内核未确认只读绑定挂载：$target", error.message)
            assertEquals(1, calls("cat").size)
            assertRolledBack("mnt/${target.name}", "dev/pts", "dev", "sys", "proc")
        }
    }

    @Test fun uidFailureIsDistinctFromAnActualRootCommandFailure() = fixture { fixture ->
        with(fixture) {
            uid = "1000"
            val denied = assertFailsWith<IllegalStateException> { engines.prepare(config) }
            assertTrue(denied.message.orEmpty().contains("su 未返回真实 UID 0"))
            assertTrue(calls("mount").isEmpty())
            failure = "id"
            val command = assertFailsWith<IllegalStateException> { engines.prepare(config) }
            assertTrue(command.message.orEmpty().contains("su: permission denied"))
            assertTrue(generateSequence<Throwable>(command) { it.cause }.any { it.message == "系统命令失败（1）：su: permission denied" })
            assertTrue(calls("mount").isEmpty())
        }
    }

    @Test fun ownershipFailureRollsBackMountsAndKeepsItsActualReason() = fixture { fixture ->
        with(fixture) {
            failure = "ownership"
            val error = assertFailsWith<IllegalStateException> { engines.prepare(config) }
            assertTrue("ownership denied" in error.message.orEmpty())
            assertRolledBack("mnt/${target.name}", "dev/pts", "dev", "sys", "proc")
        }
    }

    @Test fun nonrootPrepareRepairsOfflineFilesWithoutAddingAppGroupMembership() = fixture { fixture ->
        with(fixture) {
            File(root, "etc/passwd").writeText("alice:x:1001:1002::/home/alice:/bin/sh\n")
            val group = File(root, "etc/group").apply { writeText("users:x:1002:existing\nshared:x:${ChrootHostIdentity.gid}:another\n") }
            val home = File(root, "srv/custom home")
            val offline = File(home, ".wawlink/container.json").apply { parentFile.mkdirs(); writeText("offline metadata") }
            Files.setAttribute(home.toPath(), "unix:mode", 0x1E9) // Existing guest-selected 0751.
            val selected = config.copy(user = "1001", environment = mapOf("HOME" to "/srv/custom home"))
            engines.prepare(selected)
            assertEquals("offline metadata", offline.readText())
            assertEquals(0x1E9, (Files.getAttribute(home.toPath(), "unix:mode") as Number).toInt() and 0xFFF)
            assertTrue(File(home, ".omp").isDirectory)
            assertTrue(group.readText().contains("shared:x:${ChrootHostIdentity.gid}:another\n"))
            assertTrue(group.readText().contains("users:x:1002:existing\n"))
            assertFalse(File(root, "home/alice").exists(), "HOME 覆盖不得修改账户原目录")
            engines.unmount(config.id)
        }
    }

    @Test fun linkedHomeNeverRepairsOutsideOwnership() = fixture { fixture ->
        with(fixture) {
            val outside = File(directory, "outside-home").apply { mkdirs() }
            val file = File(outside, "keep").apply { writeText("untouched") }
            val originalMode = Files.getAttribute(file.toPath(), "unix:mode")
            Files.createSymbolicLink(File(root, "root").toPath(), outside.toPath())
            assertFailsWith<IllegalArgumentException> { engines.prepare(config) }
            assertEquals(originalMode, Files.getAttribute(file.toPath(), "unix:mode"))
            assertEquals("untouched", file.readText())
            assertRolledBack("mnt/${target.name}", "dev/pts", "dev", "sys", "proc")
        }
    }

    @Test fun startupPreservesGuestModesAndKeepsLinksAndMountedSubtreesUntouched() = fixture { fixture ->
        with(fixture) {
            val home = File(root, "home/alice").apply { mkdirs() }
            val metadata = File(home, ".wawlink/container.json").apply { parentFile.mkdirs(); writeText("metadata") }
            val configFile = File(home, ".omp/agent/config.yml").apply { parentFile.mkdirs(); writeText("settings") }
            val privateFile = File(home, ".wawlink/feature-debug/endpoint.json").apply { parentFile.mkdirs(); writeText("private") }
            val workspace = File(root, "workspace").apply { mkdirs() }
            val script = File(workspace, "run.sh").apply { writeText("printf ready"); setExecutable(true) }
            val mounted = File(home, "mounted").apply { mkdirs() }
            val protected = File(mounted, "keep").apply { writeText("mounted") }
            val outside = File(directory, "outside").apply { writeText("untouched") }
            Files.setAttribute(outside.toPath(), "unix:mode", 0x100) // 0400
            val outsideMode = Files.getAttribute(outside.toPath(), "unix:mode")
            Files.createLink(File(home, ".omp/agent/hardlink").toPath(), outside.toPath())
            Files.createSymbolicLink(File(home, ".omp/agent/link").toPath(), outside.toPath())
            Files.setAttribute(configFile.toPath(), "unix:mode", 0x100)
            Files.setAttribute(script.toPath(), "unix:mode", 0x1E9) // 0751
            Files.setAttribute(privateFile.toPath(), "unix:mode", 0x180)
            Files.setAttribute(privateFile.parentFile.toPath(), "unix:mode", 0x1C0)
            Files.setAttribute(home.toPath(), "unix:mode", 0x1E9)
            Files.setAttribute(configFile.parentFile.toPath(), "unix:mode", 0x1E8)
            val protectedMode = Files.getAttribute(protected.toPath(), "unix:mode")
            val uid = ChrootHostIdentity.uid.toString()
            val gid = ChrootHostIdentity.gid.toString()
            val command = chrootOwnershipScript(root, home, workspace, uid, gid, listOf(mounted))
            run(command)
            assertEquals(0x1E9, (Files.getAttribute(home.toPath(), "unix:mode") as Number).toInt() and 0xFFF)
            assertEquals(0x100, (Files.getAttribute(configFile.toPath(), "unix:mode") as Number).toInt() and 0x1FF)
            assertEquals("settings", configFile.readText())
            assertEquals(0x180, (Files.getAttribute(privateFile.toPath(), "unix:mode") as Number).toInt() and 0x1FF)
            assertEquals(0x1C0, (Files.getAttribute(privateFile.parentFile.toPath(), "unix:mode") as Number).toInt() and 0x1FF)
            assertEquals(0x1E9, (Files.getAttribute(script.toPath(), "unix:mode") as Number).toInt() and 0x1FF, "工作区只准备顶层，不修改项目文件") // 0751
            assertEquals("printf ready", script.readText())
            assertEquals(outsideMode, Files.getAttribute(outside.toPath(), "unix:mode"))
            assertEquals(protectedMode, Files.getAttribute(protected.toPath(), "unix:mode"))
            assertEquals("untouched", outside.readText())
            assertEquals(0x1E8, (Files.getAttribute(configFile.parentFile.toPath(), "unix:mode") as Number).toInt() and 0xFFF,
                "启动不覆盖客体自行设置的模式")
        }
    }
    @Test fun privilegedPreparationPreservesModesWhenAdoptingManagedData() = fixture { fixture ->
        with(fixture) {
            val home = File(root, "home/imported").apply { mkdirs() }
            val preferences = File(home, ".wawlink/feature-cards.json").apply { parentFile.mkdirs(); writeText("layout") }
            val settings = File(home, ".omp/agent/config.yml").apply { parentFile.mkdirs(); writeText("native settings") }
            val skill = File(home, ".omp/agent/skills/wawlink-functions/SKILL.md").apply { parentFile.mkdirs(); writeText("Skill") }
            val record = File(skill.parentFile, ".wawlink-builtin.sha256").apply { writeText("record") }
            for (file in listOf(preferences, settings, skill, record)) Files.setAttribute(file.toPath(), "unix:mode", 0x100)
            Files.setAttribute(home.toPath(), "unix:mode", 0x1E9)
            val actual = ChrootHostIdentity.uid
            val login = if (actual == 30000) "30001" else "30000"
            val foreign = if (actual == 30003) 30004 else 30003
            // Kernel UID changes are simulated by the fixture; modes and file operations are real.
            intercept("stat", """
                if [ "${'$'}1" = -c ] && [ "${'$'}2" = '%d %i %h %u %g %a' ]; then
                    set -- ${'$'}(${shellQuote(tool("stat"))} "${'$'}@")
                    printf '%s %s %s %s %s %s\n' "${'$'}1" "${'$'}2" "${'$'}3" '$foreign' "${'$'}5" "${'$'}6"
                else
                    exec ${shellQuote(tool("stat"))} "${'$'}@"
                fi
            """.trimIndent())
            run(chrootOwnershipScript(root, home, null, login, "30002", emptyList()), privileged = true)
            assertEquals(0x1E9, (Files.getAttribute(home.toPath(), "unix:mode") as Number).toInt() and 0xFFF)
            for (file in listOf(preferences, settings, skill, record)) {
                assertEquals(0x100, (Files.getAttribute(file.toPath(), "unix:mode") as Number).toInt() and 0x1FF)
            }
            assertEquals("native settings", settings.readText())
            assertEquals("record", record.readText())
        }
    }

    @Test fun ownershipChangesRestoreGuestSetgidClearedByChownBeforeLaterOfflineWrites() = fixture { fixture ->
        with(fixture) {
            val home = File(root, "home/imported").apply { mkdirs() }
            val channel = File(home, ".wawlink").apply { mkdirs() }
            for (folder in listOf(home, channel)) Files.setAttribute(folder.toPath(), "unix:mode", 0x5F8)
            val login = if (ChrootHostIdentity.uid == 30000) "30001" else "30000"
            File(root, "etc/passwd").writeText("root:x:0:0::/root:/bin/sh\nalice:x:$login:30002::/home/imported:/bin/sh\n")
            val selected = config.copy(user = "alice")
            RootfsOwnership.save(root, OwnershipManifest(mapOf(
                "/home/imported" to LogicalOwner(login.toLong(), 30002, 0x5F8),
                "/home/imported/.wawlink" to LogicalOwner(login.toLong(), 30002, 0x5F8),
            ), physical = ContainerEngineType.CHROOT))
            // Model kernels/filesystems clearing setgid on chown, independent of test runner privileges.
            intercept("chown", """
                [ "${'$'}1" != -h ] || shift
                shift
                ${shellQuote(tool("chown"))} -h '${ChrootHostIdentity.uid}:${ChrootHostIdentity.gid}' "${'$'}@" || exit
                if [ -d "${'$'}1" ] && [ ! -L "${'$'}1" ]; then
                    ${shellQuote(tool("chmod"))} g-s "${'$'}@"
                fi
            """.trimIndent())
            val command = chrootOwnershipScript(root, home, null, login, "30002", emptyList())
            run(command)
            for (folder in listOf(home, channel)) {
                assertEquals(0x5F8, (Files.getAttribute(folder.toPath(), "unix:mode") as Number).toInt() and 0xFFF)
            }
            val session = File(channel, "later-session").apply { mkdir() }
            assertEquals(0x400, (Files.getAttribute(session.toPath(), "unix:mode") as Number).toInt() and 0x400,
                "客体明确设置的 setgid 不由后续启动擅自删除")
            val request = Files.createTempFile(session.toPath(), "request-", ".tmp").toFile()
            request.writeText("later request")
            val sessionMode = (Files.getAttribute(session.toPath(), "unix:mode") as Number).toInt() and 0xFFF
            val requestMode = (Files.getAttribute(request.toPath(), "unix:mode") as Number).toInt() and 0xFFF
            // Explicit UID-operation simulation, not a real Root integration test.
            val simulatedRoot: (String) -> String = { command -> run("id() { printf '0\\n'; };\n" + command, privileged = true) }
            prepareContainerHostDirectory(selected, session, simulatedRoot)
            prepareContainerHostFile(selected, request, simulatedRoot)
            assertEquals("later request", request.readText())
            val committed = RootfsOwnership.load(root)!!.entries
            assertEquals(LogicalOwner(login.toLong(), 30002, sessionMode), committed["/home/imported/.wawlink/later-session"])
            assertEquals(LogicalOwner(login.toLong(), 30002, requestMode), committed["/home/imported/.wawlink/later-session/${request.name}"])
        }
    }

    @Test fun largeDependencyAndProjectTreesDoNotParticipateInStartupRepair() = fixture { fixture ->
        with(fixture) {
            val home = File(root, "home/alice").apply { mkdirs() }
            val workspace = File(root, "workspace").apply { mkdirs() }
            val folders = listOf(".bun/install/cache", ".npm", ".cache", ".local", ".omp/agent/node_modules", ".omp/agent/plugins", ".omp/agent/skills/custom/node_modules")
                .map { File(home, it) } + listOf(File(workspace, "node_modules"), File(workspace, ".git"))
            val files = folders.flatMap { folder ->
                folder.mkdirs()
                (1..600).map { index -> File(folder, "entry-$index").apply { writeText("preserved-$index") } }
            }
            for (folder in folders) Files.setAttribute(folder.toPath(), "unix:mode", 0x1C0)
            for (file in files) Files.setAttribute(file.toPath(), "unix:mode", 0x100)
            val managed = File(home, ".omp/agent/config.yml").apply { writeText("native settings") }
            val mounted = File(home, ".omp/agent/wawlink/mounted").apply { mkdirs() }
            val protected = File(mounted, "keep").apply { writeText("mounted"); Files.setAttribute(toPath(), "unix:mode", 0x100) }
            Files.setAttribute(workspace.toPath(), "unix:mode", 0x1E9)
            Files.setAttribute(managed.toPath(), "unix:mode", 0x1A0)
            val uid = ChrootHostIdentity.uid.toString()
            val gid = ChrootHostIdentity.gid.toString()
            run(chrootOwnershipScript(root, home, workspace, uid, gid, listOf(mounted)))
            assertEquals(0x1E9, (Files.getAttribute(workspace.toPath(), "unix:mode") as Number).toInt() and 0xFFF)
            assertEquals(0x1A0, (Files.getAttribute(managed.toPath(), "unix:mode") as Number).toInt() and 0x1FF)
            assertEquals("native settings", managed.readText())
            for (file in files + protected) assertEquals(0x100, (Files.getAttribute(file.toPath(), "unix:mode") as Number).toInt() and 0x1FF, file.path)
            for (folder in folders) assertEquals(0x1C0, (Files.getAttribute(folder.toPath(), "unix:mode") as Number).toInt() and 0x1FF, folder.path)
            assertEquals("preserved-600", files.last().readText())
            assertEquals("mounted", protected.readText())
        }
    }


    @Test fun replacingFileAfterIdentityCaptureCannotRepairAnotherInode() = fixture { fixture ->
        with(fixture) {
            val home = File(root, "home/alice").apply { mkdirs() }
            val configFile = File(home, ".wawlink/container.json").apply { parentFile.mkdirs(); writeText("original") }
            val original = File(directory, "original")
            val replacement = File(directory, "replacement").apply { writeText("replacement") }
            Files.setAttribute(configFile.toPath(), "unix:mode", 0x100)
            Files.setAttribute(replacement.toPath(), "unix:mode", 0x100)
            intercept("stat", """
                if [ "${'$'}1" = -c ] && [ "${'$'}2" = '%d %i %h %u %g %a' ] && [ "${'$'}3" = ./container.json ] && [ "${'$'}PWD" = ${shellQuote(configFile.parent)} ] && [ ! -e ${shellQuote(original.path)} ]; then
                    ${shellQuote(tool("stat"))} "${'$'}@" || exit
                    ${shellQuote(tool("mv"))} ${shellQuote(configFile.path)} ${shellQuote(original.path)}
                    ${shellQuote(tool("mv"))} ${shellQuote(replacement.path)} ${shellQuote(configFile.path)}
                else
                    exec ${shellQuote(tool("stat"))} "${'$'}@"
                fi
            """.trimIndent())
            val command = chrootOwnershipScript(root, home, null,
                ChrootHostIdentity.uid.toString(), ChrootHostIdentity.gid.toString(), emptyList())
            assertFailsWith<IllegalStateException> { run(command) }
            assertEquals("original", original.readText())
            assertEquals("replacement", configFile.readText())
            assertEquals(0x100, (Files.getAttribute(configFile.toPath(), "unix:mode") as Number).toInt() and 0x1FF)
            assertEquals(0x100, (Files.getAttribute(original.toPath(), "unix:mode") as Number).toInt() and 0x1FF)
        }
    }

    @Test fun replacingCheckedDirectoryWithOutsideLinkCannotRedirectPrivilegedChmod() = fixture { fixture ->
        with(fixture) {
            val home = File(root, "home/alice").apply { mkdirs() }
            val agent = File(home, ".omp/agent").apply { mkdirs() }
            Files.setAttribute(agent.toPath(), "unix:mode", 0x1E9)
            File(agent, "config.yml").writeText("original")
            val moved = File(home, ".omp/moved")
            val outside = File(directory, "outside").apply { mkdirs() }
            File(outside, "keep").writeText("untouched")
            Files.setAttribute(outside.toPath(), "unix:mode", 0x1C0)
            val prefix = """
                chmod() {
                    if [ "${'$'}PWD" = ${shellQuote(agent.path)} ] && [ ! -L ${shellQuote(agent.path)} ]; then
                        mv -- ${shellQuote(agent.path)} ${shellQuote(moved.path)}
                        ln -s -- ${shellQuote(outside.path)} ${shellQuote(agent.path)}
                    fi
                    command chmod "${'$'}@"
                }
            """.trimIndent()
            val command = prefix + "\n" + chrootOwnershipScript(root, home, null,
                "30000", "30002", emptyList())
            assertFailsWith<IllegalStateException> { run(command, privileged = true) }
            assertEquals(0x1C0, (Files.getAttribute(outside.toPath(), "unix:mode") as Number).toInt() and 0x1FF)
            assertEquals("untouched", File(outside, "keep").readText())
            assertEquals(0x1E9, (Files.getAttribute(moved.toPath(), "unix:mode") as Number).toInt() and 0xFFF)
        }
    }

    @Test fun systemRootHomeOverridesCannotTransferSystemTreeOwnership() = fixture { fixture ->
        with(fixture) {
            val file = File(root, "usr/keep").apply { parentFile.mkdirs(); writeText("system") }
            Files.setAttribute(file.toPath(), "unix:mode", 0x100)
            val mode = Files.getAttribute(file.toPath(), "unix:mode")
            for (home in listOf("/", "/usr", "/etc", "/home", "/usr/.", "/home/alice/..")) {
                assertFailsWith<IllegalArgumentException> { engines.prepare(config.copy(environment = mapOf("HOME" to home))) }
                assertEquals(mode, Files.getAttribute(file.toPath(), "unix:mode"))
                assertEquals("system", file.readText())
                assertTrue(mounts().isEmpty())
            }
        }
    }

    @Test fun helperRejectsLinkedAndMountedPathsWithoutChangingTheirTargets() = fixture { fixture ->
        with(fixture) {
            val outside = File(directory, "outside-dir").apply { mkdirs() }
            Files.createSymbolicLink(File(root, "home").toPath(), outside.toPath())
            for (engine in ContainerEngineType.entries) {
                val selected = config.copy(engine = engine)
                assertFails { prepareContainerHostDirectory(selected, File(root, "home/.omp/agent"), engines::rootCommand) }
                assertTrue(outside.listFiles().orEmpty().isEmpty())
                assertFails { prepareContainerHostDirectory(selected, target, engines::rootCommand) }
            }
            val home = File(root, "root").apply { mkdirs() }
            val alias = File(home, "hardlink")
            val sourceFile = File(directory, "file").apply { writeText("unchanged") }
            Files.createLink(alias.toPath(), sourceFile.toPath())
            for (engine in ContainerEngineType.entries) {
                assertFails { prepareContainerHostFile(config.copy(engine = engine), alias, engines::rootCommand) }
            }
            assertEquals("unchanged", sourceFile.readText())
        }
    }

    companion object {
        @JvmStatic fun main(arguments: Array<String>) {
            val config = ContainerConfig("root-output-probe", "Root output probe", arguments[1], engine = ContainerEngineType.CHROOT)
            val engines = RuntimeEngines(RuntimePaths(File(arguments[2])))
            when (arguments[0]) {
                "snapshot" -> {
                    val snapshot = RootfsOwnership.snapshot(config, engines::rootCommand).sortedBy { it.path }.joinToString("\n")
                    println(RootfsHostFiles.revision(snapshot.toByteArray()))
                }
                "size" -> println(RootfsArchives.privilegedSize(config, engines::rootCommand))
                "ordinary" -> println(engines.rootCommand("printf '%s\\n' '$OWNERSHIP_SCAN_MARKER'\ndd if=/dev/zero bs=1048576 count=5 2>/dev/null").length)
                else -> error("Unknown probe operation")
            }
        }
        private val MOCK_COMMANDS = """
            record() { printf '%s\000' "${'$'}@" >> "${'$'}MOCK_LOG"; printf '\n' >> "${'$'}MOCK_LOG"; }
            find_mount() {
                found=''
                for entry in "${'$'}MOCK_STATE"/mount-*; do
                    [ -d "${'$'}entry" ] || continue
                    IFS= read -r mounted < "${'$'}entry/target"
                    if [ "${'$'}mounted" = "${'$'}1" ]; then found="${'$'}entry"; return 0; fi
                done
                return 1
            }
            id() {
                if [ "${'$'}MOCK_FAILURE" = id ]; then echo 'su: permission denied' >&2; return 1; fi
                case "${'$'}{1-}" in
                    -u) printf '%s\n' "${'$'}MOCK_UID";;
                    -g) printf '0\n';;
                    '') printf 'uid=%s(mock) gid=0(mock)\n' "${'$'}MOCK_UID";;
                    *) command id "${'$'}@";;
                esac
            }
            mountpoint() { [ "${'$'}#" = 2 ] && [ "${'$'}1" = -q ] && find_mount "${'$'}2"; }
            chroot() { echo 'test must never execute chroot' >&2; return 99; }
            mount() {
                record mount "${'$'}@"
                if [ "${'$'}1" = --make-private ]; then
                    [ "${'$'}#" = 2 ] || return 64
                    if [ "${'$'}MOCK_FAILURE" = private ]; then
                        echo "mount: unknown option '--make-private'" >&2; return 32
                    fi
                    return 0
                fi
                [ "${'$'}#" = 4 ] || { echo 'mount requires source and target' >&2; return 64; }
                source="${'$'}3"; target="${'$'}4"
                case "${'$'}1:${'$'}2" in
                    -o:remount,bind,ro)
                        if [ "${'$'}MOCK_FAILURE" = remount ]; then
                            echo 'mount: read-only remount denied' >&2; return 32
                        fi
                        find_mount "${'$'}target" || return 32
                        printf '%s\n' "${'$'}MOCK_RO" > "${'$'}found/options"
                        return 0 ;;
                    -o:bind)
                        if [ "${'$'}MOCK_FAILURE" = bind ] && [ "${'$'}target" = "${'$'}MOCK_TARGET" ]; then
                            printf 'mount: bind denied for %s\n' "${'$'}source" >&2; return 32
                        fi ;;
                    -t:proc|-t:sysfs|-t:devpts) ;;
                    *) echo 'unexpected mount invocation' >&2; return 64 ;;
                esac
                entry="${'$'}MOCK_STATE/mount-${'$'}${'$'}"
                /bin/mkdir "${'$'}entry" || return
                printf '%s\n' "${'$'}target" > "${'$'}entry/target"
                printf 'rw\n' > "${'$'}entry/options"
            }
            umount() {
                record umount "${'$'}@"
                [ "${'$'}#" = 2 ] && [ "${'$'}1" = -l ] || return 64
                find_mount "${'$'}2" || return 32
                /bin/rm -r -- "${'$'}found"
            }
            cat() {
                record cat "${'$'}@"
                if [ "${'$'}#" = 1 ] && [ "${'$'}1" = /proc/self/mountinfo ]; then
                    printf '%s\n' "${'$'}MOCK_MOUNTINFO"
                else
                    command cat "${'$'}@"
                fi
            }
        """.trimIndent()
    }
}
