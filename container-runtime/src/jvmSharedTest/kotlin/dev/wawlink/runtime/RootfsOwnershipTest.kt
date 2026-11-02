package dev.wawlink.runtime

import java.io.File
import java.nio.file.Files
import kotlin.test.*
import kotlinx.coroutines.runBlocking

/** Real production shell/files/modes. Only privileged UID/GID changes are explicitly modelled. */
class RootfsOwnershipTest {
    private class Fixture : AutoCloseable {
        val directory = Files.createTempDirectory("wawlink-ownership-").toFile()
        val root = File(directory, "root ' space").apply { mkdir() }
        val tools = File(directory, "tools").apply { mkdir() }
        private val state = File(directory, "owners.json").apply { writeText("{}") }
        val config = ContainerConfig("owner", "Owner", root.path, arch = System.getProperty("os.arch"), shell = "/bin/sh")
        var mutations = 0
        var failMutation = false
        init {
            put("etc/passwd", "root:x:0:0:root:/root:/bin/sh\nalice:x:1001:1002::/home/alice:/bin/sh\npostgres:x:110:120::/var/lib/postgresql:/bin/sh\nredis:x:111:121::/nonexistent:/bin/false\n")
            for (name in listOf("stat", "chown")) {
                File(tools, name).apply {
                    writeText("#!/usr/bin/env node\n" + """
                        const fs = require('fs'), path = require('path'), cp = require('child_process');
                        const state = ${kotlinx.serialization.json.JsonPrimitive(state.path)};
                        const metadata = JSON.parse(fs.readFileSync(state, 'utf8'));
                        const args = process.argv.slice(2);
                        const target = path.resolve(args.at(-1));
                        const st = fs.lstatSync(target), key = st.dev + ':' + st.ino;
                        if (${kotlinx.serialization.json.JsonPrimitive(name)} === 'stat') {
                            const index = args.indexOf('-c') + 1;
                            const fmt = args[index];
                            args[index] = fmt.replace(/%u/g, '__OWNER_UID__').replace(/%g/g, '__OWNER_GID__');
                            const real = cp.execFileSync('/usr/bin/stat', args, {encoding:'utf8'});
                            const owner = metadata[key] || [st.uid, st.gid];
                            process.stdout.write(real.replace(/__OWNER_UID__/g, String(owner[0])).replace(/__OWNER_GID__/g, String(owner[1])));
                        } else {
                            const spec = args.find(a => /^\d+:\d+${'$'}/.test(a));
                            metadata[key] = spec.split(':').map(Number);
                            fs.writeFileSync(state, JSON.stringify(metadata));
                            fs.lchownSync(target, process.getuid(), process.getgid());
                        }
                    """.trimIndent())
                    setExecutable(true)
                }
            }
            File(tools, "id").apply {
                writeText("#!/bin/sh\nif [ \"\$1\" = -u ]; then echo 0; else exec /usr/bin/id \"\$@\"; fi\n")
                setExecutable(true)
            }
        }
        fun put(name: String, content: String = "content") = File(root, name).apply { parentFile.mkdirs(); writeText(content) }
        fun run(command: String): String {
            if (command.contains("apply()")) {
                mutations++
                if (failMutation) throw IllegalStateException("injected media I/O failure")
            }
            return HostCommands.run(ProcessBuilder("/bin/sh", "-c", command).apply { environment()["PATH"] = tools.path + ":" + environment()["PATH"] }, 90)
        }
        fun owner(path: String) = run("stat -c '%u:%g:%a' -- ${shellQuote(File(root, path).path)}").trim()
        override fun close() { RootfsArchives.deleteTree(directory) }
    }

    @Test fun rootSnapshotPreservesLiteralAndTrailingNewlineInSymlinkTarget() {
        Fixture().use { f ->
            val target = "relative \\ literal\n\n"
            Files.createSymbolicLink(File(f.root, "alias").toPath(), java.nio.file.Path.of(target))
            val entry = RootfsOwnership.snapshot(f.config, f::run).single { it.path == "/alias" }
            assertTrue(entry.symbolicLink)
            assertEquals(target, entry.linkTarget)
        }
    }

    @Test fun rootSnapshotRejectsProducerFailureEvenWhenConsumerExitsSuccessfully() {
        Fixture().use { f ->
            File(f.tools, "find").apply {
                writeText("#!/bin/sh\nexit 23\n")
                setExecutable(true)
            }
            assertFailsWith<IllegalArgumentException> { RootfsOwnership.snapshot(f.config, f::run) }
        }
    }

    @Test fun legacyMigrationRebuildsEveryUserAndPrivateServiceStateAndRoundTrips() {
        Fixture().use { f ->
            f.put("root/.vnc/passwd", "secret")
            f.put("root/.vnc/xstartup", "#!/bin/sh\n").setExecutable(true)
            f.put("home/alice/.ssh/id_ed25519", "key")
            f.put("home/alice/.Xauthority", "cookie")
            f.put("var/lib/postgresql/data", "db")
            f.put("var/log/redis/log", "log")
            f.put("usr/bin/sudo", "executable")
            File(f.root, "tmp").mkdir()
            File(f.root, "var/tmp").mkdirs()
            val target = f.config.copy(engine = ContainerEngineType.CHROOT)
            RootfsOwnership.migrate(f.config, target, f::run)
            assertEquals("0:0:700", f.owner("root/.vnc"))
            assertEquals("0:0:600", f.owner("root/.vnc/passwd"))
            assertEquals("1001:1002:600", f.owner("home/alice/.ssh/id_ed25519"))
            assertEquals("1001:1002:600", f.owner("home/alice/.Xauthority"))
            assertEquals("110:120", f.owner("var/lib/postgresql/data").substringBeforeLast(':'))
            assertEquals("111:121", f.owner("var/log/redis/log").substringBeforeLast(':'))
            assertEquals("0:0:1777", f.owner("tmp"))
            assertEquals("0:0:1777", f.owner("var/tmp"))
            assertEquals("0:0:4755", f.owner("usr/bin/sudo"))
            assertTrue(File(f.root, "root/.vnc/xstartup").canExecute())
            f.put("home/alice/new", "new data")
            Files.setAttribute(File(f.root, "home/alice/new").toPath(), "unix:mode", 416)
            f.run("chown -h 1001:1002 ${shellQuote(File(f.root, "home/alice/new").path)}")
            RootfsOwnership.migrate(target, f.config, f::run)
            assertEquals("${ChrootHostIdentity.uid}:${ChrootHostIdentity.gid}:600", f.owner("root/.vnc/passwd"))
            val manifest = RootfsOwnership.load(f.root)!!
            assertEquals(LogicalOwner(1001, 1002, 416), manifest.entries["/home/alice/new"])
            assertEquals(ContainerEngineType.PROOT, manifest.physical)
            RootfsOwnership.migrate(f.config, target, f::run)
            assertEquals("0:0:600", f.owner("root/.vnc/passwd"))
            assertEquals("1001:1002", f.owner("home/alice/new").substringBeforeLast(':'))
        }
    }

    @Test fun symlinksAndBindTargetsNeverModifyOutsideFiles() {
        Fixture().use { f ->
            val outside = File(f.directory, "outside").apply { mkdir() }
            val sentinel = File(outside, "secret").apply { writeText("outside") }
            Files.setAttribute(sentinel.toPath(), "unix:mode", 416)
            Files.createSymbolicLink(File(f.root, "alias").toPath(), outside.toPath())
            f.put("storage/secret", "emulated mount")
            f.put("mnt/custom/secret", "custom mount")
            val target = f.config.copy(engine = ContainerEngineType.CHROOT, mounts = listOf(ContainerMount(outside.path, "/mnt/custom")))
            RootfsOwnership.migrate(f.config, target, f::run)
            assertEquals("outside", sentinel.readText())
            assertEquals(416, (Files.getAttribute(sentinel.toPath(), "unix:mode") as Number).toInt() and 4095)
            val entries = RootfsOwnership.load(f.root)!!.entries
            assertTrue("/alias" in entries)
            assertFalse(entries.keys.any { it.startsWith("/alias/") || it.startsWith("/storage/") || it.startsWith("/mnt/custom/") })
        }
    }

    @Test fun externalHardlinkRejectsWholePlanBeforeMutating() {
        Fixture().use { f ->
            val outside = File(f.directory, "outside").apply { writeText("outside") }
            Files.createLink(File(f.root, "shared").toPath(), outside.toPath())
            assertFailsWith<IllegalStateException> { RootfsOwnership.migrate(f.config, f.config.copy(engine = ContainerEngineType.CHROOT), f::run) }
            assertEquals(0, f.mutations)
            assertNull(RootfsOwnership.load(f.root))
            assertEquals("outside", outside.readText())
        }
    }

    @Test fun interruptedMutationKeepsJournalAndCanResumeWithoutReinferringIds() {
        Fixture().use { f ->
            f.put("home/alice/file")
            val target = f.config.copy(engine = ContainerEngineType.CHROOT)
            f.failMutation = true
            assertFailsWith<IllegalStateException> { RootfsOwnership.migrate(f.config, target, f::run) }
            assertNotNull(RootfsOwnership.load(f.root)!!.pending)
            assertFailsWith<IllegalStateException> { RootfsOwnership.requireStable(f.config) }
            f.failMutation = false
            RootfsOwnership.migrate(f.config, target, f::run)
            assertNull(RootfsOwnership.load(f.root)!!.pending)
            assertEquals("1001:1002", f.owner("home/alice/file").substringBeforeLast(':'))
        }
    }

    @Test fun rootDenialLeavesConfigAndFilesystemUntouched() = runBlocking {
        Fixture().use { f ->
            val runtime = LocalContainerRuntime(RuntimePaths(File(f.directory, "app"), hostProot = "/missing"), RootfsArchives::size) {
                throw IllegalStateException("Root authorization revoked")
            }
            try {
                // Valid local ELF is used only for offline validation, never executed as a guest.
                Files.copy(File("/bin/sh").toPath(), f.put("bin/sh").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                File(f.root, "bin/sh").setExecutable(true)
                runtime.register(f.config)
                val shell = File(f.root, "bin/sh")
                val beforeShell = shell.readBytes()
                val beforeShellMode = (Files.getAttribute(shell.toPath(), "unix:mode", java.nio.file.LinkOption.NOFOLLOW_LINKS) as Number).toInt()
                val beforeEngine = runtime.containers.value.single().config.engine
                val failure = assertFailsWith<IllegalStateException> { runtime.update(f.config.copy(engine = ContainerEngineType.CHROOT)) }
                assertTrue(failure.message.orEmpty().contains("Root"))
                assertEquals(beforeEngine, runtime.containers.value.single().config.engine)
                assertContentEquals(beforeShell, shell.readBytes())
                assertEquals(beforeShellMode, (Files.getAttribute(shell.toPath(), "unix:mode", java.nio.file.LinkOption.NOFOLLOW_LINKS) as Number).toInt())
                assertNull(RootfsOwnership.load(f.root))
            } finally { runtime.close() }
        }
    }
}
