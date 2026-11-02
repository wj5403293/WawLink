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
        val statOutput = File(directory, "stat-output")
        val config = ContainerConfig("owner", "Owner", root.path, arch = System.getProperty("os.arch"), shell = "/bin/sh")
        var mutations = 0
        var failMutation = false
        var beforeMutation: (() -> Unit)? = null
        init {
            put("etc/passwd", "root:x:0:0:root:/root:/bin/sh\nalice:x:1001:1002::/home/alice:/bin/sh\npostgres:x:110:120::/var/lib/postgresql:/bin/sh\nredis:x:111:121::/nonexistent:/bin/false\n")
            for (name in listOf("stat", "chown")) {
                File(tools, name).apply {
                    writeText("#!/usr/bin/env node\n" + """
                        const fs = require('fs'), path = require('path'), cp = require('child_process');
                        const state = ${kotlinx.serialization.json.JsonPrimitive(state.path)};
                        const metadata = JSON.parse(fs.readFileSync(state, 'utf8'));
                        const args = process.argv.slice(2);
                        const targets = args.slice(args.indexOf('--') >= 0 ? args.indexOf('--') + 1 : args.indexOf('-c') + 2);
                        if (${kotlinx.serialization.json.JsonPrimitive(name)} === 'stat') {
                            const index = args.indexOf('-c') + 1;
                            const fmt = args[index];
                            args[index] = fmt.replace(/%u/g, '__OWNER_UID__').replace(/%g/g, '__OWNER_GID__');
                            const lines = cp.execFileSync('/usr/bin/stat', args, {encoding:'utf8'}).trimEnd().split('\n');
                            targets.forEach((target, i) => {
                                const st = fs.lstatSync(path.resolve(target)), key = st.dev + ':' + st.ino;
                                const owner = metadata[key] || [st.uid, st.gid];
                                lines[i] = lines[i].replace(/__OWNER_UID__/g, String(owner[0])).replace(/__OWNER_GID__/g, String(owner[1]));
                            });
                            const override = ${kotlinx.serialization.json.JsonPrimitive(statOutput.path)};
                            process.stdout.write(fs.existsSync(override) ? fs.readFileSync(override, 'utf8') : lines.join('\n') + '\n');
                        } else {
                            const index = args.findIndex(a => /^\d+:\d+${'$'}/.test(a));
                            const owner = args[index].split(':').map(Number);
                            for (const target of args.slice(index + 1).filter(a => a !== '--')) {
                                const st = fs.lstatSync(path.resolve(target)), key = st.dev + ':' + st.ino;
                                metadata[key] = owner;
                                fs.lchownSync(target, process.getuid(), process.getgid());
                            }
                            fs.writeFileSync(state, JSON.stringify(metadata));
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
                beforeMutation?.also { beforeMutation = null }?.invoke()
            }
            return HostCommands.run(ProcessBuilder("/bin/sh", "-c", command).apply { environment()["PATH"] = tools.path + ":" + environment()["PATH"] }, 90)
        }
        fun owner(path: String) = run("stat -c '%u:%g:%a' -- ${shellQuote(File(root, path).path)}").trim()
        override fun close() { RootfsArchives.deleteTree(directory) }
    }

    @Test fun rootSnapshotAcceptsCompletionAfterAnExactFullBatch() {
        Fixture().use { f ->
            val initial = RootfsOwnership.snapshot(f.config).size
            repeat(128 - initial) { f.put("file-$it") }
            val entries = RootfsOwnership.snapshot(f.config, f::run)
            assertEquals(128, entries.size)
        }
    }

    @Test fun rootSnapshotPreservesCompleteEntriesAcrossBatches() {
        Fixture().use { f ->
            repeat(300) { f.put("batch/file $it") }
            val unusual = f.put("batch/quote ' and \\ literal [*] ?")
            f.put("WAWLINK_SNAPSHOT_COMPLETE")
            Files.createLink(File(f.root, "hard alias").toPath(), unusual.toPath())
            Files.setAttribute(unusual.toPath(), "unix:mode", 0b111111111111)
            Files.createSymbolicLink(File(f.root, "symbolic alias").toPath(), java.nio.file.Path.of("batch/quote ' and \\ literal [*] ?"))
            val expected = RootfsOwnership.snapshot(f.config).associateBy { it.path }
            val actual = RootfsOwnership.snapshot(f.config, f::run).associateBy { it.path }
            assertEquals(expected, actual)
        }
    }

    @Test fun rootSnapshotKeepsDifferentOwnersInTheSameNativeBatch() {
        Fixture().use { f ->
            f.put("first")
            f.put("second")
            f.run("chown -h 1001:1002 -- ${shellQuote(File(f.root, "first").path)}")
            f.run("chown -h 110:120 -- ${shellQuote(File(f.root, "second").path)}")
            val entries = RootfsOwnership.snapshot(f.config, f::run).associateBy { it.path }
            assertEquals(1001L, entries.getValue("/first").uid)
            assertEquals(1002L, entries.getValue("/first").gid)
            assertEquals(110L, entries.getValue("/second").uid)
            assertEquals(120L, entries.getValue("/second").gid)
        }
    }

    @Test fun snapshotRejectsNewlinePathsWithoutSplittingOtherLiteralCharacters() {
        Fixture().use { f ->
            f.put("bad\nname")
            assertFailsWith<IllegalArgumentException> { RootfsOwnership.snapshot(f.config) }
            assertFailsWith<IllegalArgumentException> { RootfsOwnership.snapshot(f.config, f::run) }
        }
    }

    @Test fun rootSnapshotRejectsMissingAndSurplusNativeMetadataRows() {
        Fixture().use { f ->
            val count = RootfsOwnership.snapshot(f.config).size
            val row = "0:0:755:1:1:1:41ed:0\n"
            for (rows in listOf(count - 1, count + 1)) {
                f.statOutput.writeText(row.repeat(rows))
                assertFailsWith<IllegalStateException> { RootfsOwnership.snapshot(f.config, f::run) }
            }
        }
    }

    @Test fun rootSnapshotRejectsInvalidNativeMetadataAndLinkTypeDisagreement() {
        Fixture().use { f ->
            val count = RootfsOwnership.snapshot(f.config).size
            val valid = listOf("0", "0", "755", "1", "1", "1", "41ed", "0")
            val invalid = listOf(0 to "-1", 0 to "4294967295", 1 to "-1", 1 to "4294967295", 2 to "10000",
                3 to "-1", 4 to "-1", 5 to "0", 6 to "0", 6 to "e000", 6 to "a1ff", 7 to "-1")
            for ((index, value) in invalid) {
                val row = valid.toMutableList().apply { this[index] = value }.joinToString(":") + "\n"
                f.statOutput.writeText(row.repeat(count))
                assertFailsWith<IllegalArgumentException>("metadata field $index = $value") { RootfsOwnership.snapshot(f.config, f::run) }
            }
        }
    }

    @Test fun rootSnapshotRejectsNativeStatFailureAfterItWritesRows() {
        Fixture().use { f ->
            File(f.tools, "stat").writeText("#!/bin/sh\n/usr/bin/stat \"\$@\"\nexit 23\n")
            assertFailsWith<IllegalStateException> { RootfsOwnership.snapshot(f.config, f::run) }
        }
    }

    @Test fun rootSnapshotPrunesConfiguredVirtualAndActualMountsWithoutFollowingLinks() {
        Fixture().use { f ->
            val outside = File(f.directory, "outside").apply { mkdir() }
            File(outside, "secret").writeText("outside")
            Files.createSymbolicLink(File(f.root, "alias").toPath(), outside.toPath())
            f.put("storage/secret")
            val configured = "/mnt/quote ' \\ [*] ?"
            f.put("${configured.removePrefix("/")}/secret")
            val mounted = f.put("actual bound/secret").parentFile
            val encoded = mounted.path.replace("\\", "\\134").replace(" ", "\\040")
            File(f.tools, "cat").apply {
                writeText("#!/bin/sh\nif [ \"\$1\" = /proc/self/mountinfo ]; then /bin/cat \"\$@\"; printf '%s\\n' ${shellQuote("991 1 0:1 / $encoded rw - tmpfs tmpfs rw")}; else exec /bin/cat \"\$@\"; fi\n")
                setExecutable(true)
            }
            val config = f.config.copy(mounts = listOf(ContainerMount(outside.path, configured)))
            val entries = RootfsOwnership.snapshot(config, f::run)
            assertEquals(outside.path, entries.single { it.path == "/alias" }.linkTarget)
            assertFalse(entries.any { it.path == "/storage" || it.path.startsWith("/storage/") ||
                it.path == configured || it.path.startsWith("$configured/") ||
                it.path == "/actual bound" || it.path.startsWith("/actual bound/") || it.path.startsWith("/alias/") })
        }
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
                writeText("#!/bin/sh\nprintf '.\\000./etc\\000'\nexit 23\n")
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

    @Test fun replacedBatchMemberRejectsBeforeChangingItsSiblingOrLinkTarget() {
        Fixture().use { f ->
            val sibling = f.put("home/alice/.ssh/first", "keep sibling")
            val replaced = f.put("home/alice/.ssh/last", "original")
            val outside = File(f.directory, "outside").apply { writeText("keep outside") }
            Files.setAttribute(outside.toPath(), "unix:mode", 0x1A0)
            val ownerBefore = f.owner("home/alice/.ssh/first")
            f.beforeMutation = {
                Files.delete(replaced.toPath())
                Files.createSymbolicLink(replaced.toPath(), outside.toPath())
            }
            assertFailsWith<IllegalStateException> {
                RootfsOwnership.migrate(f.config, f.config.copy(engine = ContainerEngineType.CHROOT), f::run)
            }
            assertEquals(ownerBefore, f.owner("home/alice/.ssh/first"))
            assertEquals("keep sibling", sibling.readText())
            assertEquals("keep outside", outside.readText())
            assertEquals(0x1A0, (Files.getAttribute(outside.toPath(), "unix:mode") as Number).toInt() and 0xFFF)
            assertNotNull(RootfsOwnership.load(f.root)!!.pending)
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
