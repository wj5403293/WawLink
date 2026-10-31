package dev.wawlink.runtime

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream

class RootfsImportTest {
    private fun fixture(block: (File, LocalContainerRuntime) -> Unit) {
        val directory = Files.createTempDirectory("wawlink-import-test").toFile()
        val runtime = LocalContainerRuntime(RuntimePaths(File(directory, "data"), hostProot = "/must-not-launch-an-engine")) { root, excluded -> RootfsArchives.size(root, excluded) }
        try { block(directory, runtime) } finally { runtime.close(); RootfsArchives.deleteTree(directory) }
    }

    private fun put(root: File, name: String, text: String) = File(root, name).apply { parentFile.mkdirs(); writeText(text) }
    private fun root(directory: File, name: String, title: String = ""): ContainerConfig {
        val root = File(directory, name).apply { mkdirs() }
        put(root, "etc/passwd", "root:x:0:0::/root:/bin/sh\nalice:x:1000:1000::/home/alice:/bin/sh\n")
        put(root, "etc/os-release", "ID=debian\nVERSION_ID=13\n")
        put(root, "bin/sh", "").apply {
            writeBytes(ByteArray(20).apply {
                this[0] = 0x7f; this[1] = 'E'.code.toByte(); this[2] = 'L'.code.toByte(); this[3] = 'F'.code.toByte()
                this[5] = 1; this[18] = 62
            })
            setExecutable(true)
        }
        if (title.isNotEmpty()) put(root, "root/.wawlink/container.json", "{\"title\":\"$title\",\"version\":\"release-2\"}")
        return ContainerConfig(name, name, root.canonicalPath, arch = "amd64", shell = "/bin/sh", link2symlink = false)
    }
    private fun archive(directory: File, config: ContainerConfig): File = File(directory, "${config.id}.tar.zst").also { RootfsArchives.export(File(config.rootfsPath), it) }
    private fun tree(root: File): Map<String, String> = Files.walk(root.toPath()).use { paths ->
        paths.filter { !Files.isDirectory(it, NOFOLLOW_LINKS) }.iterator().asSequence().associate { path ->
            root.toPath().relativize(path).toString() to if (Files.isSymbolicLink(path)) "link:${Files.readSymbolicLink(path)}" else path.toFile().readBytes().joinToString(",")
        }
    }
    private fun staging(directory: File) = File(directory, "data/downloads").listFiles().orEmpty().filter { it.name.startsWith("import-") }

    @Test fun conflictMatchingTreatsInputAndArchiveTitleAsSeparateIdentities() {
        val first = ContainerConfig("one", "internal-one", "/unused/one", metadata = ContainerMetadata("Display One"))
        val second = ContainerConfig("two", "internal-two", "/unused/two", metadata = ContainerMetadata("Display Two"))
        val unrelated = ContainerConfig("three", "third", "/unused/three")
        assertEquals(listOf(first), rootfsImportConflicts(" internal-one ", "", listOf(first, second, unrelated)))
        assertEquals(listOf(second), rootfsImportConflicts("new", " Display Two ", listOf(first, second, unrelated)))
        assertEquals(listOf(first, second), rootfsImportConflicts(" Display One ", "internal-two", listOf(first, second, unrelated)))
        assertTrue(rootfsImportConflicts("display one", "", listOf(first)).isEmpty())
        assertTrue(rootfsImportConflicts(" ", " ", listOf(first)).isEmpty())
    }

    @Test fun inspectionIsUnregisteredAndConflictRetryRenamesBothNamesWithoutReadingSourceAgain() = fixture { directory, runtime -> runBlocking {
        val existing = root(directory, "existing", "Archive Title")
        runtime.register(existing)
        val source = root(directory, "source", "Archive Title")
        val image = archive(directory, source)
        val inspection = runtime.inspectRootfsArchive(image.path, "existing")
        assertEquals("Archive Title", inspection.metadata.title)
        assertEquals(listOf(existing.id), runtime.containers.value.map { it.config.id })
        assertEquals(1, staging(directory).size)
        image.delete()
        assertFails { runtime.importRootfs(inspection.id, "existing") }
        assertFails { runtime.importRootfs(inspection.id, "existing", RootfsImportMode.RENAME) }
        val imported = runtime.importRootfs(inspection.id, " Renamed ", RootfsImportMode.RENAME)
        assertEquals("Renamed", imported.name)
        assertEquals("Renamed", imported.metadata.title)
        assertEquals("Renamed", runtime.readMetadata(imported.id).value.title)
        assertEquals("release-2", imported.metadata.version)
        assertNotEquals(existing.id, imported.id)
        assertTrue(staging(directory).isEmpty())
        assertFails { runtime.importRootfs(inspection.id, "Another") }
    } }

    @Test fun titleConflictAndMultipleTargetsRequireAnActuallyConflictingUpdateChoice() = fixture { directory, runtime -> runBlocking {
        val one = root(directory, "one", "First")
        val two = root(directory, "two", "Second")
        val third = root(directory, "third", "Unrelated")
        listOf(one, two, third).forEach { runtime.register(it) }
        val source = root(directory, "source", "Second")
        put(File(source.rootfsPath), "payload", "new")
        val inspection = runtime.inspectRootfsArchive(archive(directory, source).path, "First")
        assertFails { runtime.importRootfs(inspection.id, "First") }
        assertFails { runtime.importRootfs(inspection.id, "First", RootfsImportMode.UPDATE) }
        assertFails { runtime.importRootfs(inspection.id, "First", RootfsImportMode.UPDATE, third.id) }
        assertFalse(File(third.rootfsPath, "payload").exists())
        val updated = runtime.importRootfs(inspection.id, "First", RootfsImportMode.UPDATE, two.id)
        assertEquals(two.id, updated.id)
        assertEquals("new", File(two.rootfsPath, "payload").readText())
        assertFalse(File(one.rootfsPath, "payload").exists())
        assertEquals(3, runtime.containers.value.size)
    } }

    @Test fun changedImplicitHomeCannotHideRetainedCredentialsAndSessions() = fixture { directory, runtime -> runBlocking {
        val target = root(directory, "existing").copy(user = "alice")
        val targetRoot = File(target.rootfsPath)
        put(targetRoot, "home/alice/.omp/agent/auth.json", "local-auth")
        put(targetRoot, "home/alice/.omp/agent/sessions/current.jsonl", "local-session")
        put(targetRoot, "usr/bin/tool", "local-tool")
        runtime.register(target)
        val source = root(directory, "source", "Updated")
        put(File(source.rootfsPath), "etc/passwd", "root:x:0:0::/root:/bin/sh\nalice:x:1000:1000::/home/changed:/bin/sh\n")
        put(File(source.rootfsPath), "usr/bin/tool", "new-tool")
        val inspection = runtime.inspectRootfsArchive(archive(directory, source).path, target.name)
        val before = tree(targetRoot)
        val failure = assertFails { runtime.importRootfs(inspection.id, target.name, RootfsImportMode.UPDATE, target.id) }
        assertTrue(failure.message.orEmpty().contains("HOME"))
        assertEquals(before, tree(targetRoot))
        assertEquals("/home/alice", containerHome(runtime.containers.value.single().config))
    } }

    @Test fun updateProtectsExactUserRangesAcrossBothHomeSetsAndKeepsLocalAdditions() = fixture { directory, runtime -> runBlocking {
        val hostMount = File(directory, "host-mount").apply { mkdirs() }
        val initial = root(directory, "existing", "Existing")
        val target = initial.copy(user = "alice", shell = "/bin/sh", engine = ContainerEngineType.CHROOT,
            distro = "local-distro", version = "local-version", arch = "amd64", autoSleep = false,
            mounts = listOf(ContainerMount(hostMount.path, "/mnt/host")), environment = mapOf("HOME" to "/srv/custom", "KEEP" to "value"))
        val targetRoot = File(target.rootfsPath)
        put(targetRoot, "etc/passwd", "root:x:0:0::/root:/bin/sh\nalice:x:1000:1000::/home/alice:/bin/sh\nbob:x:1001:1001::/srv/bob:/bin/sh\n")
        val source = root(directory, "source", "Archive")
        val sourceRoot = File(source.rootfsPath)
        put(sourceRoot, "etc/passwd", "root:x:0:0::/root:/bin/sh\nalice:x:1000:1000::/home/new-alice:/bin/sh\nshared:x:1002:1002::/home/shared:/bin/sh\n")
        val homes = listOf("root", "home/alice", "home/new-alice", "srv/custom", "srv/bob", "home/shared")
        val protected = mutableListOf("workspace/project/file", "mnt/host/file", "tmp/wawlink/request")
        val retained = mutableListOf<String>()
        homes.forEach { home ->
            protected += listOf(
                "$home/.omp/agent/config.yml", "$home/.omp/agent/config.json", "$home/.omp/agent/models.yml", "$home/.omp/agent/models.json",
                "$home/.omp/agent/auth.json", "$home/.omp/agent/credentials.json", "$home/.omp/agent/oauth.json",
                "$home/.omp/agent/agent.db", "$home/.omp/agent/agent.db-wal", "$home/.omp/agent/agent.db-shm",
                "$home/.omp/agent/sessions/session", "$home/.omp/agent/logs/log", "$home/.omp/agent/wawlink/socket",
                "$home/.omp/profiles/work/agent/credentials.json", "$home/.omp/profiles/other/agent/sessions/session",
                "$home/.wawlink/logs/run.log", "$home/.bash_history", "$home/.zsh_history", "$home/.ash_history", "$home/.sh_history",
                "$home/.local/share/fish/fish_history", "$home/.python_history", "$home/.node_repl_history", "$home/.lesshst", "$home/.viminfo", "$home/.wget-hsts",
                "$home/.ssh/id_ed25519", "$home/.gnupg/key", "$home/.gitconfig", "$home/.git-credentials", "$home/.netrc", "$home/.npmrc", "$home/.pypirc",
                "$home/.cargo/credentials", "$home/.cargo/credentials.toml", "$home/.config/gh/hosts.yml",
            )
            retained += listOf("$home/.omp/agent/skills/skill", "$home/.omp/agent/extensions/extension", "$home/.omp/agent/rules/rule", "$home/.omp/agent/prompts/prompt",
                "$home/.omp/profiles/work/agent/skills/skill", "$home/.wawlink/features/feature/run", "$home/.wawlink/feature-cards.json",
                "$home/.cache/cache", "$home/.npm/cache", "$home/.bun/install/cache/package")
        }
        protected.forEachIndexed { index, path ->
            if (index % 2 == 0) put(targetRoot, path, "private:$path") // Absent protected paths must stay absent, too.
            put(sourceRoot, path, "author:$path")
        }
        retained.forEach { path -> put(targetRoot, path, "old"); put(sourceRoot, path, "new") }
        put(targetRoot, "local-only/addition", "keep local")
        put(sourceRoot, "srv/custom/.wawlink/container.json", "{\"title\":\"Updated Distribution\",\"version\":\"2026\"}")
        put(targetRoot, "srv/custom/.wawlink/container.json", "{\"title\":\"Existing\"}")
        runtime.register(target)
        val original = runtime.containers.value.single().config
        val inspection = runtime.inspectRootfsArchive(archive(directory, source).path, "existing")
        val updated = runtime.importRootfs(inspection.id, "existing", RootfsImportMode.UPDATE, target.id)
        assertEquals(original.copy(metadata = updated.metadata), updated)
        assertEquals("Updated Distribution", updated.metadata.title)
        assertEquals("2026", updated.metadata.version)
        protected.forEachIndexed { index, path ->
            if (index % 2 == 0) assertEquals("private:$path", File(targetRoot, path).readText(), path)
            else assertFalse(File(targetRoot, path).exists(), path)
        }
        retained.forEach { path -> assertEquals("new", File(targetRoot, path).readText(), path) }
        assertEquals("keep local", File(targetRoot, "local-only/addition").readText())
        assertTrue(runtime.containers.value.single().sizeBytes > 0)
        assertFalse(runtime.containers.value.single().running)
    } }

    @Test fun ordinaryFilesLinksAndMergedUsrUpdateWithoutTruncatingProtectedHardlinkAliases() = fixture { directory, runtime -> runBlocking {
        val target = root(directory, "existing")
        val targetRoot = File(target.rootfsPath)
        val source = root(directory, "source")
        val sourceRoot = File(source.rootfsPath)
        val time = FileTime.fromMillis(1_700_000_000_000)
        put(targetRoot, "root/.ssh/private", "private")
        Files.createLink(put(targetRoot, "program", "old").toPath().also { Files.delete(it) }, File(targetRoot, "root/.ssh/private").toPath())
        put(targetRoot, "local-alias-target", "old-link-content")
        Files.createSymbolicLink(File(targetRoot, "link").toPath(), Path.of("local-alias-target"))
        put(sourceRoot, "program", "updated")
        val mode = PosixFilePermissions.fromString("rwxr-x---")
        Files.setPosixFilePermissions(File(sourceRoot, "program").toPath(), mode)
        Files.setLastModifiedTime(File(sourceRoot, "program").toPath(), time)
        Files.createLink(File(sourceRoot, "hardlink").toPath(), File(sourceRoot, "program").toPath())
        put(sourceRoot, "link-target", "new-link-content")
        Files.createSymbolicLink(File(sourceRoot, "link").toPath(), Path.of("link-target"))
        // Both roots use the legitimate bin -> usr/bin layout.
        for (root in listOf(sourceRoot, targetRoot)) {
            File(root, "usr").mkdirs()
            Files.move(File(root, "bin").toPath(), File(root, "usr/bin").toPath())
            Files.createSymbolicLink(File(root, "bin").toPath(), Path.of("usr/bin"))
        }
        runtime.register(target)
        val inspection = runtime.inspectRootfsArchive(archive(directory, source).path, "existing")
        runtime.importRootfs(inspection.id, "existing", RootfsImportMode.UPDATE, target.id)
        assertEquals("private", File(targetRoot, "root/.ssh/private").readText())
        assertEquals("updated", File(targetRoot, "program").readText())
        assertFalse(Files.isSameFile(File(targetRoot, "program").toPath(), File(targetRoot, "root/.ssh/private").toPath()))
        assertEquals(mode, Files.getPosixFilePermissions(File(targetRoot, "program").toPath()))
        assertEquals(time, Files.getLastModifiedTime(File(targetRoot, "program").toPath()))
        assertEquals("updated", File(targetRoot, "hardlink").readText())
        assertEquals("old-link-content", File(targetRoot, "local-alias-target").readText())
        assertEquals("new-link-content", File(targetRoot, "link").readText())
        assertTrue(Files.isSymbolicLink(File(targetRoot, "bin").toPath()))
    } }

    @Test fun corruptChecksumAndInvalidElfNeverTouchAnExistingTarget() = fixture { directory, runtime -> runBlocking {
        val target = root(directory, "existing", "Existing")
        put(File(target.rootfsPath), "payload", "untouched")
        runtime.register(target)
        val before = tree(File(target.rootfsPath))
        val source = root(directory, "source")
        val good = archive(directory, source)
        assertFails { runtime.inspectRootfsArchive(good.path, "existing", expectedSha256 = "0".repeat(64)) }
        assertFails { runtime.inspectRootfsArchive(put(directory, "corrupt.tar.zst", "not an archive").path, "existing") }
        File(source.rootfsPath, "bin/sh").writeText("not an ELF")
        val invalid = File(directory, "invalid.tar.zst").also { RootfsArchives.export(File(source.rootfsPath), it) }
        assertFails { runtime.inspectRootfsArchive(invalid.path, "existing") }
        assertEquals(before, tree(File(target.rootfsPath)))
        assertEquals(listOf(target.id), runtime.containers.value.map { it.config.id })
        assertTrue(staging(directory).isEmpty())
    } }

    @Test fun entireOverlayPreflightsMaliciousParentsAndNonemptyDirectoryTypeConflicts() = fixture { directory, runtime -> runBlocking {
        for (case in listOf("outside", "inside", "directory", "ancestor", "ancestor-link", "missing-user", "shell", "architecture")) {
            val target = root(directory, "target-$case")
            val targetRoot = File(target.rootfsPath)
            val source = root(directory, "source-$case")
            val sourceRoot = File(source.rootfsPath)
            put(targetRoot, "aaa-ordinary", "old")
            put(sourceRoot, "aaa-ordinary", "new")
            when (case) {
                "outside" -> {
                    val outside = File(directory, "outside").apply { mkdirs() }
                    put(outside, "file", "outside")
                    Files.createSymbolicLink(File(targetRoot, "zzz").toPath(), outside.toPath())
                    put(sourceRoot, "zzz/file", "bad")
                }
                "inside" -> {
                    put(targetRoot, "root/.ssh/private", "private")
                    Files.createSymbolicLink(File(targetRoot, "zzz").toPath(), Path.of("root/.ssh"))
                    put(sourceRoot, "zzz/private", "bad")
                }
                "directory" -> { put(targetRoot, "zzz/local", "local"); put(sourceRoot, "zzz", "must not remove local tree") }
                "ancestor" -> { put(targetRoot, "root/.omp/agent/sessions/local", "private"); put(sourceRoot, "root/.omp", "cannot replace ancestor") }
                "ancestor-link" -> {
                    put(targetRoot, "root/.omp/agent/sessions/local", "private")
                    File(sourceRoot, "root").mkdirs()
                    Files.createSymbolicLink(File(sourceRoot, "root/.omp").toPath(), Path.of("ordinary"))
                }
                "missing-user" -> File(sourceRoot, "etc/passwd").writeText("root:x:0:0::/root:/bin/sh\n")
                "shell" -> {
                    File(targetRoot, "bin/bash").apply { writeBytes(File(targetRoot, "bin/sh").readBytes()); setExecutable(true) }
                    File(sourceRoot, "bin/bash").apply { writeBytes(File(sourceRoot, "bin/sh").readBytes()); setExecutable(false, false) }
                }
                "architecture" -> File(sourceRoot, "bin/sh").apply { writeBytes(readBytes().also { it[18] = 183.toByte() }) }
            }
            val actualTarget = when (case) {
                "missing-user" -> target.copy(user = "alice")
                "shell" -> target.copy(shell = "/bin/bash")
                else -> target
            }
            runtime.register(actualTarget)
            val before = tree(targetRoot)
            val inspection = runtime.inspectRootfsArchive(archive(directory, source).path, target.name)
            assertFails(case) { runtime.importRootfs(inspection.id, target.name, RootfsImportMode.UPDATE, target.id) }
            assertEquals(before, tree(targetRoot), case)
            if (case == "outside") assertEquals("outside", File(directory, "outside/file").readText())
            runtime.discardRootfsImport(inspection.id)
        }
    } }

    @Test fun protectedAncestorDirectoryModesCannotRemoveAccess() = fixture { directory, runtime -> runBlocking {
        val target = root(directory, "existing")
        val source = root(directory, "source")
        val targetRoot = File(target.rootfsPath)
        val sourceRoot = File(source.rootfsPath)
        put(targetRoot, "root/.omp/agent/sessions/session", "private")
        put(sourceRoot, "root/.omp/agent/skills/skill", "new")
        val ancestors = listOf("root", "root/.omp", "root/.omp/agent")
        val previous = ancestors.associateWith { Files.getPosixFilePermissions(File(targetRoot, it).toPath()) }
        ancestors.forEach { Files.setPosixFilePermissions(File(sourceRoot, it).toPath(), PosixFilePermissions.fromString("r-x------")) }
        runtime.register(target)
        val inspection = runtime.inspectRootfsArchive(archive(directory, source).path, target.name)
        runtime.importRootfs(inspection.id, target.name, RootfsImportMode.UPDATE, target.id)
        previous.forEach { (name, mode) -> assertEquals(mode, Files.getPosixFilePermissions(File(targetRoot, name).toPath()), name) }
        assertEquals("private", File(targetRoot, "root/.omp/agent/sessions/session").readText())
        assertEquals("new", File(targetRoot, "root/.omp/agent/skills/skill").readText())
        ancestors.forEach { Files.setPosixFilePermissions(File(sourceRoot, it).toPath(), PosixFilePermissions.fromString("rwx------")) }
    } }

    @Test fun discardAndCloseReleaseIdleInspections() = fixture { directory, runtime -> runBlocking {
        val source = root(directory, "source")
        val image = archive(directory, source)
        val discarded = runtime.inspectRootfsArchive(image.path, "first")
        runtime.discardRootfsImport(discarded.id)
        runtime.discardRootfsImport(discarded.id)
        assertTrue(staging(directory).isEmpty())
        assertFails { runtime.importRootfs(discarded.id, "first") }
        runtime.inspectRootfsArchive(image.path, "second")
        assertEquals(1, staging(directory).size)
        runtime.close()
        assertTrue(staging(directory).isEmpty())
        assertTrue(runtime.containers.value.isEmpty())
    } }

    @Test fun newImportRechecksNamesAtRegistrationAndRetainsStagingAfterTheRace() {
        val directory = Files.createTempDirectory("wawlink-import-race").toFile()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val block = AtomicBoolean(true)
        val runtime = LocalContainerRuntime(RuntimePaths(File(directory, "data"))) { root, excluded ->
            if (root.path.contains("/downloads/import-") && block.compareAndSet(true, false)) {
                entered.countDown(); check(release.await(10, TimeUnit.SECONDS))
            }
            RootfsArchives.size(root, excluded)
        }
        try { runBlocking {
            val source = root(directory, "source")
            val inspection = runtime.inspectRootfsArchive(archive(directory, source).path, "Race")
            val importing = async(Dispatchers.IO) { runCatching { runtime.importRootfs(inspection.id, "Race") } }
            withContext(Dispatchers.IO) { check(entered.await(10, TimeUnit.SECONDS)) }
            val other = root(directory, "other", "Race")
            withTimeout(10_000) { runtime.register(other) }
            release.countDown()
            assertTrue(importing.await().isFailure)
            assertEquals(listOf(other.id), runtime.containers.value.map { it.config.id })
            assertTrue(File(directory, "data/rootfs").listFiles().orEmpty().isEmpty())
            assertEquals(1, staging(directory).size)
            val renamed = runtime.importRootfs(inspection.id, "Race repaired", RootfsImportMode.RENAME)
            assertEquals("Race repaired", renamed.displayName)
        } } finally { release.countDown(); runtime.close(); RootfsArchives.deleteTree(directory) }
    }

    private fun interruptedUpdate(close: Boolean, cancel: Boolean = true) {
        val directory = Files.createTempDirectory("wawlink-update-interrupted").toFile()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val block = AtomicBoolean(false)
        val runtime = LocalContainerRuntime(RuntimePaths(File(directory, "data"), hostProot = processEngine(directory).path)) { root, excluded ->
            if (root.name == "existing" && block.compareAndSet(true, false)) {
                entered.countDown(); check(release.await(10, TimeUnit.SECONDS))
            }
            RootfsArchives.size(root, excluded)
        }
        try { runBlocking {
            val target = root(directory, "existing")
            val other = root(directory, "other")
            runtime.register(target); runtime.register(other)
            val source = root(directory, "source")
            put(File(source.rootfsPath), "payload", "new")
            val inspection = runtime.inspectRootfsArchive(archive(directory, source).path, target.name)
            withTimeout(10_000) { runtime.containers.first { values -> values.size == 2 && values.all { it.sizeBytes > 0 } } }
            runtime.start(target.id)
            assertTrue(runtime.containers.value.first { it.config.id == target.id }.running)
            block.set(true)
            val updating = async(Dispatchers.IO) { runCatching { runtime.importRootfs(inspection.id, target.name, RootfsImportMode.UPDATE, target.id) } }
            withContext(Dispatchers.IO) { check(entered.await(10, TimeUnit.SECONDS)) }
            withTimeout(10_000) { runtime.saveMetadata(other.id, ContainerMetadata("Independent edit"), null) }
            assertFails { runtime.readMetadata(target.id) }
            if (close) withContext(Dispatchers.IO) { runtime.close() }
            else { runtime.stop(target.id); if (cancel) updating.cancel() }
            release.countDown()
            if (close) assertTrue(updating.await().isFailure)
            else if (cancel) updating.cancelAndJoin()
            else assertTrue(updating.await().isSuccess)
            assertTrue(runtime.containers.value.all { !it.running && it.runGeneration == 0L })
            assertTrue(staging(directory).isEmpty())
            if (!close) {
                assertEquals("Independent edit", runtime.readMetadata(other.id).value.title)
                runtime.readMetadata(target.id) // Lease released after cancellation.
            }
        } } finally { release.countDown(); runtime.close(); RootfsArchives.deleteTree(directory) }
    }

    @Test fun cancellingUpdateReleasesLeaseAndDoesNotRestartAfterExplicitStop() = interruptedUpdate(close = false)
    @Test fun closeDuringUpdateDoesNotWaitForOverlayIoOrRestart() = interruptedUpdate(close = true)
    @Test fun explicitStopDuringSuccessfulUpdateSuppressesRunningRestoration() = interruptedUpdate(close = false, cancel = false)

    @Test fun hardlinkArchiveMembersReplaceInodesInsteadOfTruncatingLocalCredentialAliases() = fixture { directory, runtime -> runBlocking {
        val target = root(directory, "existing")
        val targetRoot = File(target.rootfsPath)
        val source = root(directory, "source")
        put(targetRoot, "root/.ssh/key", "private")
        Files.createLink(File(targetRoot, "original").toPath(), File(targetRoot, "root/.ssh/key").toPath())
        Files.createLink(File(targetRoot, "alias").toPath(), File(targetRoot, "root/.ssh/key").toPath())
        val image = File(directory, "hardlinks.tar.gz")
        TarArchiveOutputStream(GzipCompressorOutputStream(image.outputStream())).use { tar ->
            for (name in listOf("etc/passwd", "etc/os-release", "bin/sh", "original")) {
                val bytes = if (name == "original") "new".toByteArray() else File(source.rootfsPath, name).readBytes()
                val entry = TarArchiveEntry(name).apply { size = bytes.size.toLong(); mode = 493 }
                tar.putArchiveEntry(entry); tar.write(bytes); tar.closeArchiveEntry()
            }
            tar.putArchiveEntry(TarArchiveEntry("alias", TarConstants.LF_LINK).apply { linkName = "original" })
            tar.closeArchiveEntry(); tar.finish()
        }
        runtime.register(target)
        val inspection = runtime.inspectRootfsArchive(image.path, target.name)
        runtime.importRootfs(inspection.id, target.name, RootfsImportMode.UPDATE, target.id)
        assertEquals("private", File(targetRoot, "root/.ssh/key").readText())
        for (name in listOf("original", "alias")) {
            assertEquals("new", File(targetRoot, name).readText())
            assertFalse(Files.isSameFile(File(targetRoot, name).toPath(), File(targetRoot, "root/.ssh/key").toPath()))
        }
    } }

    @Test fun failedRegistrationRollsBackPublicationAndKeepsTheSameInspectionForRetry() = fixture { directory, runtime -> runBlocking {
        val source = root(directory, "source", "Portable")
        val inspection = runtime.inspectRootfsArchive(archive(directory, source).path, "new")
        val state = File(directory, "data/containers.json").apply { mkdir() }
        val sentinel = File(state, "keep").apply { writeText("cannot replace a nonempty directory") }
        assertFails { runtime.importRootfs(inspection.id, "renamed", RootfsImportMode.RENAME) }
        assertTrue(runtime.containers.value.isEmpty())
        assertTrue(File(directory, "data/rootfs").listFiles().orEmpty().isEmpty())
        assertEquals(1, staging(directory).size)
        sentinel.delete(); state.delete()
        val imported = runtime.importRootfs(inspection.id, "new")
        assertEquals("new", imported.name)
        assertEquals("Portable", imported.metadata.title)
        assertTrue(staging(directory).isEmpty())
    } }

    /** Command-boundary fixture: real host health/keeper processes, no guest filesystem execution. */
    private fun processEngine(directory: File): File = File(directory, "process-engine").apply {
        writeText("""
            #!/bin/sh
            if [ "${'$'}1" = --help ]; then exit 0; fi
            while [ "${'$'}#" -gt 0 ]; do
                case "${'$'}1" in
                    -r|-w|-i|-b|-k|-q) shift 2 ;;
                    -0|--link2symlink|--sysvipc) shift ;;
                    *) exec "${'$'}@" ;;
                esac
            done
            exit 2
        """.trimIndent() + "\n")
        setExecutable(true)
    }

    @Test fun runningUpdateRestoresANewGenerationDespiteRefreshingTheConfigurationIdentity() {
        val directory = Files.createTempDirectory("wawlink-running-update").toFile()
        val runtime = LocalContainerRuntime(RuntimePaths(File(directory, "data"), hostProot = processEngine(directory).path))
        try { runBlocking {
            val target = root(directory, "existing", "Existing")
            runtime.register(target)
            runtime.start(target.id)
            val before = runtime.containers.value.single()
            assertTrue(before.running)
            val source = root(directory, "source", "Updated")
            val inspection = runtime.inspectRootfsArchive(archive(directory, source).path, target.name)
            runtime.importRootfs(inspection.id, target.name, RootfsImportMode.UPDATE, target.id)
            val restored = runtime.containers.value.single()
            assertTrue(restored.running)
            assertTrue(restored.runGeneration > before.runGeneration)
            assertEquals(before.stopGeneration + 1, restored.stopGeneration)
            assertEquals("Updated", restored.config.metadata.title)
        } } finally { runtime.close(); RootfsArchives.deleteTree(directory) }
    }

    @Test fun cancellingInspectionCleansDownloadedAndExtractedStagingWithoutStoppingTargets() = fixture { directory, runtime -> runBlocking {
        val target = root(directory, "existing")
        runtime.register(target)
        val stopGeneration = runtime.containers.value.single().stopGeneration
        val source = root(directory, "source")
        val random = ByteArray(64 * 1024).also { java.util.Random(1).nextBytes(it) }
        File(source.rootfsPath, "payload").writeBytes(random)
        val bytes = archive(directory, source).readBytes()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/image") { exchange ->
            exchange.use {
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.write(bytes, 0, 1024)
                exchange.responseBody.flush()
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                exchange.responseBody.write(bytes, 1024, bytes.size - 1024)
            }
        }
        server.start()
        try {
            val inspection = async(Dispatchers.IO) { runtime.inspectRootfsArchive("http://127.0.0.1:${server.address.port}/image", "existing") }
            withContext(Dispatchers.IO) { check(entered.await(10, TimeUnit.SECONDS)) }
            inspection.cancel()
            release.countDown()
            inspection.cancelAndJoin()
            assertTrue(staging(directory).isEmpty())
            assertEquals(listOf(target.id), runtime.containers.value.map { it.config.id })
            assertEquals(stopGeneration, runtime.containers.value.single().stopGeneration)
        } finally { release.countDown(); server.stop(0) }
    } }
}
