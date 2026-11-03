package dev.wawlink.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Production shell/files/modes; Root identity, cross-UID chown and opt-in DAC bypass are modelled. */
class RootfsHostFilesTest {
    internal class Fixture : AutoCloseable {
        val directory = Files.createTempDirectory("wawlink-host-files-").toFile()
        val root = File(directory, "root ' space").apply { mkdir() }
        val config = ContainerConfig("host", "Host", root.path, engine = ContainerEngineType.CHROOT, user = "alice")
        private val owners = File(directory, "owners.json").apply { writeText("{}") }
        val tools = File(directory, "tools").apply { mkdir() }
        init {
            put("etc/passwd", "root:x:0:0::/root:/bin/sh\nalice:x:1001:1002::/home/alice:/bin/sh\n")
            for (name in listOf("stat", "chown")) {
                File(tools, name).apply {
                    writeText("#!/usr/bin/env node\n" + """
                        const fs = require('fs'), path = require('path'), cp = require('child_process');
                        const state = ${kotlinx.serialization.json.JsonPrimitive(owners.path)};
                        const metadata = JSON.parse(fs.readFileSync(state, 'utf8'));
                        const args = process.argv.slice(2), target = path.resolve(args.at(-1));
                        const st = fs.lstatSync(target), key = st.dev + ':' + st.ino;
                        if (${kotlinx.serialization.json.JsonPrimitive(name)} === 'stat') {
                            const index = args.indexOf('-c') + 1;
                            args[index] = args[index].replace(/%u/g, '__UID__').replace(/%g/g, '__GID__');
                            const real = cp.execFileSync('/usr/bin/stat', args, {encoding:'utf8'});
                            const owner = metadata[key] || [st.uid, st.gid];
                            process.stdout.write(real.replace(/__UID__/g, String(owner[0])).replace(/__GID__/g, String(owner[1])));
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
        }
        fun put(path: String, bytes: String = "original") = File(root, path).apply { parentFile.mkdirs(); writeText(bytes) }
        fun run(command: String, prefix: String = ""): String = HostCommands.run(
            ProcessBuilder("/bin/sh", "-c", "id() { printf '0\\n'; };\n$prefix\n$command").apply {
                environment()["PATH"] = tools.path + ":" + environment()["PATH"]
            }, 90,
        )
        /** Only the injected Root call bypasses DAC; this is not real-Root acceptance. */
        fun runWithRootTraversal(command: String): String {
            val original = mode(root)
            Files.setAttribute(root.toPath(), "unix:mode", original or 0x140)
            return try { run(command) }
            finally { Files.setAttribute(root.toPath(), "unix:mode", original) }
        }
        fun store(prefix: String = "") = RootfsHostFiles(config) { run(it, prefix) }
        fun mode(file: File) = (Files.getAttribute(file.toPath(), "unix:mode", NOFOLLOW_LINKS) as Number).toInt() and 0xFFF
        fun noTemporaryFiles() = assertFalse(root.walkTopDown().any { it.name.startsWith(".wawlink-write") })
        override fun close() { RootfsArchives.deleteTree(directory) }
    }

    @Test fun mappedAtomicWritePreservesAllPermissionBitsAndRejectsStaleDraft() = Fixture().use { f ->
        val files = RootfsHostFiles(f.config.copy(engine = ContainerEngineType.PROOT))
        val file = f.put("etc/group")
        Files.setAttribute(file.toPath(), "unix:mode", 0xFA0, NOFOLLOW_LINKS)
        val original = files.read(file)!!
        files.write(file, "updated".toByteArray(), RootfsHostFiles.revision(original), preserveOwner = true)
        assertEquals(0xFA0, f.mode(file))
        assertEquals("updated", file.readText())
        assertEquals(RootfsHostFiles.CONFLICT, assertFailsWith<IllegalStateException> {
            files.write(file, "draft".toByteArray(), RootfsHostFiles.revision(original))
        }.message)
        assertEquals("updated", file.readText())
    }

    @Test fun rootReadDistinguishesMissingPrivateContentsAndRealFailures() = Fixture().use { f ->
        val file = File(f.root, "home/alice/.wawlink/container.json")
        assertNull(f.store().read(file))
        f.put("home/alice/.wawlink/container.json", "private bytes")
        Files.setAttribute(file.parentFile.toPath(), "unix:mode", 448)
        Files.setAttribute(file.toPath(), "unix:mode", 384)
        assertContentEquals("private bytes".toByteArray(), f.store().read(file))
        assertFailsWith<IllegalStateException> { f.store().read(file, 2) }
        val denied = RootfsHostFiles(f.config) { throw IllegalStateException("su: permission denied") }
        assertEquals("su: permission denied", assertFailsWith<IllegalStateException> { denied.read(file) }.message)
        assertFailsWith<IllegalStateException> { f.store("id() { printf '1000\\n'; };").read(file) }
        f.noTemporaryFiles()
    }

    @Test fun rootCommitCreatesPrivateParentsAndRecordsTheirActualCommittedOwnership() = Fixture().use { f ->
        RootfsOwnership.save(f.root, OwnershipManifest(emptyMap(), physical = ContainerEngineType.CHROOT))
        val file = File(f.root, "home/alice/.wawlink/container.json")
        val payload = "private credential must stay out of argv".toByteArray()
        val files = RootfsHostFiles(f.config) { command ->
            assertFalse(payload.toString(Charsets.UTF_8) in command)
            f.run(command)
        }
        files.write(file, payload, null)
        assertContentEquals(payload, files.read(file))
        assertEquals(0x180, f.mode(file))
        assertEquals(0x1C0, f.mode(file.parentFile))
        assertEquals(0x1C0, f.mode(file.parentFile.parentFile))
        val manifest = RootfsOwnership.load(f.root)!!
        assertEquals(LogicalOwner(1001, 1002, 0x180), manifest.entries["/home/alice/.wawlink/container.json"])
        assertEquals(LogicalOwner(1001, 1002, 0x1C0), manifest.entries["/home/alice/.wawlink"])
        assertTrue(manifest.entries.keys.none { ".wawlink-write" in it || "payload" in it })
        f.noTemporaryFiles()
    }

    @Test fun rootCommitPreservesGuestSpecialModesAndRejectsStaleOrAbsentRevisions() = Fixture().use { f ->
        val file = f.put("home/alice/.wawlink/container.json")
        Files.setAttribute(file.toPath(), "unix:mode", 0xFA0)
        val old = RootfsHostFiles.revision(file.readBytes())
        f.store().write(file, "updated".toByteArray(), old)
        assertEquals(0xFA0, f.mode(file))
        assertFailsWith<IllegalStateException> { f.store().write(file, "stale".toByteArray(), old) }
        assertFailsWith<IllegalStateException> { f.store().write(file, "absent".toByteArray(), null) }
        assertEquals("updated", file.readText())
        f.noTemporaryFiles()
    }

    @Test fun linksMountsAndParentAliasesNeverRedirectRootReadOrWrite() = Fixture().use { f ->
        val outside = File(f.directory, "outside").apply { writeText("untouched") }
        val file = f.put("home/alice/.wawlink/container.json")
        Files.delete(file.toPath())
        Files.createSymbolicLink(file.toPath(), outside.toPath())
        assertFailsWith<IllegalStateException> { f.store().read(file) }
        assertFailsWith<IllegalStateException> { f.store().write(file, byteArrayOf(1), null) }
        Files.delete(file.toPath())
        Files.createLink(file.toPath(), outside.toPath())
        assertFailsWith<IllegalStateException> { f.store().read(file) }
        assertFailsWith<IllegalStateException> { f.store().write(file, byteArrayOf(1), null) }
        val mounted = RootfsHostFiles(f.config.copy(mounts = listOf(ContainerMount(f.directory.path, "/home")))) { f.run(it) }
        assertFailsWith<IllegalArgumentException> { mounted.read(file) }
        assertFailsWith<IllegalArgumentException> { mounted.write(file, byteArrayOf(1), null) }
        assertEquals("untouched", outside.readText())
        f.noTemporaryFiles()
    }

    @Test fun externalUpdateDuringStagingFailsFinalCasAndCleansPrivateFiles() = Fixture().use { f ->
        val file = f.put("home/alice/.wawlink/container.json")
        val prefix = """
            cat() {
                command cat "${'$'}@"
                if [ "${'$'}{1##*/}" = payload ]; then printf changed > ${shellQuote(file.path)}; fi
            }
        """.trimIndent()
        assertFailsWith<IllegalStateException> {
            f.store(prefix).write(file, "draft".toByteArray(), RootfsHostFiles.revision(file.readBytes()))
        }
        assertEquals("changed", file.readText())
        f.noTemporaryFiles()
    }

    @Test fun replacedParentDuringStagingFailsPinnedParentCheck() = Fixture().use { f ->
        val file = f.put("home/alice/.wawlink/container.json")
        val moved = File(file.parentFile.parentFile, "moved")
        val prefix = """
            cat() {
                command cat "${'$'}@"
                if [ "${'$'}{1##*/}" = payload ]; then
                    command mv ${shellQuote(file.parent)} ${shellQuote(moved.path)}
                    command mkdir ${shellQuote(file.parent)}
                fi
            }
        """.trimIndent()
        assertFailsWith<IllegalStateException> {
            f.store(prefix).write(file, "draft".toByteArray(), RootfsHostFiles.revision(file.readBytes()))
        }
        assertEquals("original", File(moved, file.name).readText())
        assertFalse(file.exists())
        f.noTemporaryFiles()
    }

    @Test fun cleanupFailureIsNotAFalseSuccessfulCommit() = Fixture().use { f ->
        val file = f.put("home/alice/.wawlink/container.json")
        val failure = assertFailsWith<IllegalStateException> {
            f.store("""
                rmdir() {
                    if [ "${'$'}(pwd -P)" = ${shellQuote(file.parentFile.canonicalPath)} ]; then
                        echo 'injected cleanup I/O failure' >&2; return 1
                    fi
                    command rmdir "${'$'}@"
                }
            """.trimIndent())
                .write(file, "committed".toByteArray(), RootfsHostFiles.revision(file.readBytes()))
        }
        assertTrue(failure.message.orEmpty().contains("injected cleanup I/O failure"))
        assertEquals("committed", file.readText())
    }

    @Test fun privatePayloadAndRootTemporaryDirectoryStay700And600UntilCommit() = Fixture().use { f ->
        val file = f.put("home/alice/.wawlink/container.json")
        val observed = File(f.directory, "payload-path")
        val prefix = """
            cat() {
                if [ "${'$'}{1##*/}" = payload ]; then
                    [ "${'$'}(stat -c '%a' "${'$'}1")" = 600 ] || return 91
                    [ "${'$'}(stat -c '%a' "${'$'}{1%/*}")" = 700 ] || return 92
                    [ "${'$'}(stat -c '%a' .)" = 700 ] || return 93
                    printf '%s' "${'$'}1" > ${shellQuote(observed.path)}
                fi
                command cat "${'$'}@"
            }
        """.trimIndent()
        f.store(prefix).write(file, "new bytes".toByteArray(), RootfsHostFiles.revision(file.readBytes()))
        val payload = File(observed.readText())
        assertFalse(payload.exists())
        assertFalse(payload.parentFile.exists())
        assertEquals("new bytes", file.readText())
        f.noTemporaryFiles()
    }

    @Test fun concurrentCreationOfManagedParentsKeepsPrivateModes() = Fixture().use { f ->
        val executor = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        // UID/GID mutation alone is simulated to avoid competing fixture owner-journal writes.
        val prefix = "chown() { :; };"
        try {
            val work = (1..2).map { index -> executor.submit {
                ready.countDown()
                check(start.await(5, TimeUnit.SECONDS))
                f.store(prefix).write(File(f.root, "home/alice/.wawlink/file-$index"), "payload-$index".toByteArray(), null)
            } }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            work.forEach { it.get(90, TimeUnit.SECONDS) }
        } finally { start.countDown(); executor.shutdownNow() }
        assertEquals(0x1C0, f.mode(File(f.root, "home/alice/.wawlink")))
        for (index in 1..2) assertEquals("payload-$index", File(f.root, "home/alice/.wawlink/file-$index").readText())
        f.noTemporaryFiles()
    }

    @Test fun finalSymlinkReplacementNeverReadsOrChangesItsTarget() = Fixture().use { f ->
        val outside = File(f.directory, "outside").apply { writeText("outside secret") }
        val link = File(f.root, "home/alice/.wawlink/container.json").apply { parentFile.mkdirs() }
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        assertNull(f.store().read(link, replaceSymlink = true))
        f.store().write(link, "independent".toByteArray(), null, replaceSymlink = true)
        assertFalse(Files.isSymbolicLink(link.toPath()))
        assertEquals("independent", link.readText())
        assertEquals("outside secret", outside.readText())
        f.noTemporaryFiles()
    }

    @Test fun missingRootRetainsPrivilegedFilesystemErrorWithoutCreatingIt() = Fixture().use { f ->
        val config = f.config.copy(rootfsPath = File(f.directory, "missing-root").path)
        val files = RootfsHostFiles(config) { f.run(it) }
        val target = File(config.rootfsPath, "document")
        for (operation in listOf<() -> Unit>(
            { files.read(target); Unit },
            { files.write(target, byteArrayOf(1), null) },
        )) {
            val failure = assertFailsWith<IllegalStateException>(block = operation)
            assertTrue(failure.message.orEmpty().contains("missing-root"))
        }
        assertFalse(File(config.rootfsPath).exists())
    }

    @Test fun kernelMountBoundaryIsRejectedByProductionShell() = Fixture().use { f ->
        // /dev/shm is an actual kernel mount, not a reserved system root or a mocked mount.
        val files = RootfsHostFiles(f.config.copy(rootfsPath = "/dev/shm")) { f.run(it) }
        val failure = assertFailsWith<IllegalStateException> { files.read(File("/dev/shm/document")) }
        assertTrue(failure.message.orEmpty().contains("容器管理路径位于内核挂载内"))
    }

    @Test fun aliasedParentCannotRedirectOfflineStoreIntoOutsideDirectory() = Fixture().use { f ->
        val outside = File(f.directory, "outside-directory").apply { mkdir() }
        val sentinel = File(outside, "container.json").apply { writeText("outside") }
        val parent = File(f.root, "home/alice/.wawlink")
        parent.parentFile.mkdirs()
        Files.createSymbolicLink(parent.toPath(), outside.toPath())
        val file = File(parent, "container.json")
        assertFailsWith<IllegalStateException> { f.store().read(file) }
        assertFailsWith<IllegalStateException> { f.store().write(file, "blocked".toByteArray(), null) }
        assertEquals("outside", sentinel.readText())
        assertEquals(listOf("container.json"), outside.list()!!.toList())
    }

    @Test fun sameRootWritersSerializeCasAndKeepWinningManifest() = Fixture().use { f ->
        val file = f.put("home/alice/.wawlink/container.json")
        Files.setAttribute(file.toPath(), "unix:mode", 0x1A0)
        RootfsOwnership.save(f.root, OwnershipManifest(emptyMap(), physical = ContainerEngineType.CHROOT))
        val revision = RootfsHostFiles.revision(file.readBytes())
        val executor = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        try {
            val work = (1..2).map { index -> executor.submit<Throwable?> {
                ready.countDown()
                check(start.await(5, TimeUnit.SECONDS))
                try { f.store().write(file, "writer-$index".toByteArray(), revision); null }
                catch (failure: Throwable) { failure }
            } }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            val outcomes = work.map { it.get(90, TimeUnit.SECONDS) }
            assertEquals(1, outcomes.count { it == null })
            val failure = outcomes.filterNotNull().single()
            assertTrue(failure is IllegalStateException)
            assertTrue(failure.message.orEmpty().contains(RootfsHostFiles.CONFLICT))
            assertTrue(file.readText() in setOf("writer-1", "writer-2"))
            assertEquals(LogicalOwner(1001, 1002, 0x1A0), RootfsOwnership.load(f.root)!!.entries["/home/alice/.wawlink/container.json"])
            f.noTemporaryFiles()
        } finally { start.countDown(); executor.shutdownNow() }
    }

    @Test fun blockedRootWriterDoesNotHoldOtherContainerCommitLock() = Fixture().use { first ->
        Fixture().use { second ->
            val firstFile = first.put("home/alice/.wawlink/container.json")
            val secondFile = second.put("home/alice/.wawlink/container.json")
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val blockOnce = java.util.concurrent.atomic.AtomicBoolean(true)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val pending = executor.submit {
                    RootfsHostFiles(first.config) { command ->
                        if (blockOnce.getAndSet(false)) { entered.countDown(); check(release.await(90, TimeUnit.SECONDS)) }
                        first.run(command)
                    }.write(firstFile, "first".toByteArray(), RootfsHostFiles.revision(firstFile.readBytes()))
                }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                executor.submit {
                    second.store().write(secondFile, "second".toByteArray(), RootfsHostFiles.revision(secondFile.readBytes()))
                }.get(60, TimeUnit.SECONDS)
                assertEquals("second", secondFile.readText())
                release.countDown()
                pending.get(60, TimeUnit.SECONDS)
                assertEquals("first", firstFile.readText())
            } finally { release.countDown(); executor.shutdownNow() }
        }
    }

    @Test fun competingDirectoryCreatorIsRecheckedInsteadOfFailingValidParentCreation() = Fixture().use { f ->
        val file = File(f.root, "home/alice/.wawlink/container.json")
        // Both mkdir calls are real: the first models a competing creator, the second observes EEXIST.
        val prefix = "mkdir() { command mkdir \"${'$'}@\"; command mkdir \"${'$'}@\"; };"
        f.store(prefix).write(file, "committed".toByteArray(), null)
        assertEquals("committed", file.readText())
        assertEquals(0x1C0, f.mode(file.parentFile))
        f.noTemporaryFiles()
    }

    @Test fun guestChmodDuringStagingIsAConflictNotSilentlyReverted() = Fixture().use { f ->
        val file = f.put("home/alice/.wawlink/container.json")
        Files.setAttribute(file.toPath(), "unix:mode", 0x180)
        val prefix = """
            cat() {
                command cat "${'$'}@"
                if [ "${'$'}{1##*/}" = payload ]; then command chmod 640 ${shellQuote(file.path)}; fi
            }
        """.trimIndent()
        assertFailsWith<IllegalStateException> {
            f.store(prefix).write(file, "draft".toByteArray(), RootfsHostFiles.revision(file.readBytes()))
        }
        assertEquals("original", file.readText())
        assertEquals(0x1A0, f.mode(file))
        f.noTemporaryFiles()
    }

    @Test fun sameByteExternalReplacementIsStillAnInodeConflict() = Fixture().use { f ->
        val file = f.put("home/alice/.wawlink/container.json")
        val replacement = File(f.directory, "replacement").apply { writeText("original") }
        val prefix = """
            cat() {
                command cat "${'$'}@"
                if [ "${'$'}{1##*/}" = payload ]; then command mv ${shellQuote(replacement.path)} ${shellQuote(file.path)}; fi
            }
        """.trimIndent()
        assertFailsWith<IllegalStateException> {
            f.store(prefix).write(file, "draft".toByteArray(), RootfsHostFiles.revision(file.readBytes()))
        }
        assertEquals("original", file.readText())
        assertFalse(replacement.exists())
        f.noTemporaryFiles()
    }

    @Test fun inPlaceSameLengthChangeDuringReadIsRejected() = Fixture().use { f ->
        val file = f.put("home/alice/.wawlink/container.json", "original")
        val prefix = """
            cp() {
                command cp "${'$'}@"
                printf mutation > ${shellQuote(file.path)}
            }
        """.trimIndent()
        assertFailsWith<IllegalStateException> { f.store(prefix).read(file) }
        assertEquals("mutation", file.readText())
        f.noTemporaryFiles()
    }

    @Test fun byteReadFailureAndCleanupFailureBothRemainVisible() = Fixture().use { f ->
        val file = f.put("home/alice/.wawlink/container.json")
        val prefix = """
            base64() { echo 'injected byte read I/O failure' >&2; return 1; }
            rmdir() { echo 'injected cleanup I/O failure' >&2; return 1; }
        """.trimIndent()
        val failure = assertFailsWith<IllegalStateException> { f.store(prefix).read(file) }
        assertTrue(failure.message.orEmpty().contains("injected byte read I/O failure"))
        assertTrue(failure.message.orEmpty().contains("injected cleanup I/O failure"))
        assertEquals("original", file.readText())
    }

    @Test fun mappedTargetUsesPhysicalRootAndPendingJournalForPrivateAtomicWrites() = Fixture().use { f ->
        val config = f.config.copy(engine = ContainerEngineType.PROOT)
        val file = f.put("home/alice/.wawlink/container.json", "原始标题")
        Files.setAttribute(file.parentFile.toPath(), "unix:mode", 0x1C0, NOFOLLOW_LINKS)
        Files.setAttribute(file.toPath(), "unix:mode", 0xFA0, NOFOLLOW_LINKS)
        val journals = listOf(
            OwnershipManifest(emptyMap(), physical = ContainerEngineType.CHROOT),
            OwnershipManifest(emptyMap(), physical = ContainerEngineType.PROOT,
                pending = OwnershipMigration(ContainerEngineType.PROOT, ContainerEngineType.CHROOT)),
            OwnershipManifest(emptyMap(), physical = ContainerEngineType.CHROOT,
                pending = OwnershipMigration(ContainerEngineType.CHROOT, ContainerEngineType.PROOT)),
        )
        for ((index, journal) in journals.withIndex()) {
            RootfsOwnership.save(f.root, journal)
            val files = RootfsHostFiles(config) { f.run(it) }
            val previous = files.read(file)!!
            val replacement = "中文标题-$index".toByteArray()
            files.write(file, replacement, RootfsHostFiles.revision(previous))
            assertContentEquals(replacement, files.read(file))
            assertEquals(0xFA0, f.mode(file))
            assertEquals(0x1C0, f.mode(file.parentFile))
            val committed = RootfsOwnership.load(f.root)!!
            assertEquals(journal.physical, committed.physical)
            assertEquals(journal.pending, committed.pending)
            assertEquals(LogicalOwner(1001, 1002, 0xFA0), committed.entries["/home/alice/.wawlink/container.json"])
            assertFailsWith<IllegalStateException> { files.write(file, "stale".toByteArray(), RootfsHostFiles.revision(previous)) }
            assertContentEquals(replacement, file.readBytes())
        }
        f.noTemporaryFiles()
    }

    @Test fun inaccessibleMappedRootUsesRootWithoutRelaxingItsMode() = Fixture().use { f ->
        // A real UID 0 runner cannot model App EACCES with owner permission bits alone.
        if (ChrootHostIdentity.uid == 0) return@use
        val config = f.config.copy(engine = ContainerEngineType.PROOT)
        val file = f.put("home/alice/.wawlink/container.json", "不可穿透的原件")
        val previous = file.readBytes()
        Files.setAttribute(file.toPath(), "unix:mode", 0x180, NOFOLLOW_LINKS)
        val original = f.mode(f.root)
        Files.setAttribute(f.root.toPath(), "unix:mode", 0, NOFOLLOW_LINKS)
        try {
            assertFailsWith<java.nio.file.AccessDeniedException> { Files.readAllBytes(file.toPath()) }
            val files = RootfsHostFiles(config, f::runWithRootTraversal)
            assertContentEquals(previous, files.read(file))
            files.write(file, "Root 离线更新".toByteArray(), RootfsHostFiles.revision(previous))
            assertEquals("Root 离线更新", files.read(file)!!.toString(Charsets.UTF_8))
            assertEquals(0, f.mode(f.root))
            val denied = RootfsHostFiles(config) { throw IllegalStateException("su: denied by policy") }
            assertEquals("su: denied by policy", assertFailsWith<IllegalStateException> { denied.read(file) }.message)
        } finally { Files.setAttribute(f.root.toPath(), "unix:mode", original) }
        assertEquals(0x180, f.mode(file))
        assertEquals("Root 离线更新", file.readText())
        f.noTemporaryFiles()
    }

    @Test fun physicalRootFinalizersRecordOwnersBehindUntraversableRootWithoutChangingModes() = Fixture().use { f ->
        val config = f.config.copy(engine = ContainerEngineType.PROOT)
        RootfsOwnership.save(f.root, OwnershipManifest(emptyMap(), physical = ContainerEngineType.CHROOT))
        val file = f.put("home/alice/.wawlink/credential", "私有凭据")
        Files.setAttribute(file.parentFile.toPath(), "unix:mode", 0x1C0, NOFOLLOW_LINKS)
        Files.setAttribute(file.toPath(), "unix:mode", 0x1A0, NOFOLLOW_LINKS)
        val original = f.mode(f.root)
        Files.setAttribute(f.root.toPath(), "unix:mode", 0, NOFOLLOW_LINKS)
        try {
            val files = RootfsHostFiles(config, f::runWithRootTraversal)
            files.prepareDirectory(file.parentFile)
            files.prepareFile(file)
            assertEquals("私有凭据", files.read(file)!!.toString(Charsets.UTF_8))
            assertEquals(0, f.mode(f.root))
            val manifest = RootfsOwnership.load(f.root)!!
            assertEquals(LogicalOwner(1001, 1002, 0x1C0), manifest.entries["/home/alice/.wawlink"])
            assertEquals(LogicalOwner(1001, 1002, 0x1A0), manifest.entries["/home/alice/.wawlink/credential"])
        } finally { Files.setAttribute(f.root.toPath(), "unix:mode", original) }
        assertEquals(0x1C0, f.mode(file.parentFile))
        assertEquals(0x1A0, f.mode(file))
        f.noTemporaryFiles()
    }
}
