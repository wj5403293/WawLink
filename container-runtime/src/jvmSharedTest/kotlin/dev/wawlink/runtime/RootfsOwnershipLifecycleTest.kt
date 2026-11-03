package dev.wawlink.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Production runtime, shell, files, modes and tar; Root/UID/GID and the engine probe are modelled. */
class RootfsOwnershipLifecycleTest {
    private class Fixture : AutoCloseable {
        val directory = Files.createTempDirectory("wawlink-owner-lifecycle-").toFile()
        val data = File(directory, "app")
        val tools = File(directory, "tools").apply { mkdir() }
        private val state = File(directory, "owners.json").apply { writeText("{}") }
        var mutations = 0
        var scans = 0
        var failMutation = false
        var runtime: LocalContainerRuntime
        init {
            for (name in listOf("stat", "chown")) {
                File(tools, name).apply {
                    writeText("#!/usr/bin/env node\n" + """
                        const fs = require('fs'), path = require('path'), cp = require('child_process');
                        const state = ${kotlinx.serialization.json.JsonPrimitive(state.path)};
                        const metadata = JSON.parse(fs.readFileSync(state, 'utf8'));
                        const args = process.argv.slice(2);
                        const targets = args.includes('--') ? args.slice(args.indexOf('--') + 1) : args.slice(args.indexOf('-c') + 2);
                        if (${kotlinx.serialization.json.JsonPrimitive(name)} === 'stat') {
                            const i = args.indexOf('-c') + 1;
                            args[i] = args[i].replace(/%u/g, '__UID__').replace(/%g/g, '__GID__');
                            const lines = cp.execFileSync('/usr/bin/stat', args, {encoding: 'utf8'}).trimEnd().split('\n');
                            targets.forEach((target, index) => {
                                const st = fs.lstatSync(target), owner = metadata[st.dev + ':' + st.ino] || [st.uid, st.gid];
                                lines[index] = lines[index].replace(/__UID__/g, String(owner[0])).replace(/__GID__/g, String(owner[1]));
                            });
                            process.stdout.write(lines.join('\n') + '\n');
                        } else {
                            const ownerIndex = args.findIndex(a => /^\d+:\d+${'$'}/.test(a));
                            const owner = args[ownerIndex].split(':').map(Number);
                            for (const target of args.slice(ownerIndex + 1)) {
                                const st = fs.lstatSync(target);
                                metadata[st.dev + ':' + st.ino] = owner;
                                fs.lchownSync(target, process.getuid(), process.getgid());
                            }
                            const temporary = state + '.' + process.pid;
                            fs.writeFileSync(temporary, JSON.stringify(metadata)); fs.renameSync(temporary, state);
                        }
                    """.trimIndent())
                    setExecutable(true)
                }
            }
            File(tools, "id").apply {
                writeText("#!/bin/sh\nif [ \"\$1\" = -u ]; then echo 0; else exec /usr/bin/id \"\$@\"; fi\n")
                setExecutable(true)
            }
            // These export cases use root-only trees. Model the producer's real Root identity,
            // not its payload: the system tar still reads the real files, links and modes.
            File(tools, "tar").apply {
                writeText("#!/bin/sh\nexec /usr/bin/tar --owner=0 --group=0 \"\$@\"\n")
                setExecutable(true)
            }
            val prootProbe = File(tools, "proot-probe").apply {
                writeText("#!/bin/sh\n[ \"\$#\" -eq 1 ] && [ \"\$1\" = --help ] && exit 0\necho 'Unexpected guest start in offline ownership fixture' >&2\nexit 125\n")
                setExecutable(true)
            }
            runtime = LocalContainerRuntime(RuntimePaths(data, hostProot = prootProbe.path), RootfsArchives::size,
                startRootProcess = ::start, runRootCommand = ::run)
        }
        fun start(command: String): Process = ProcessBuilder("/bin/sh", "-c", command).apply {
            environment()["PATH"] = tools.path + ":" + environment()["PATH"]
        }.start()
        fun run(command: String): String {
            if (command.contains(OWNERSHIP_SCAN_MARKER)) scans++
            if (command.contains("apply()")) {
                mutations++
                check(!failMutation) { "injected ownership batch failure" }
            }
            return HostCommands.collect(start(command), "ownership-fixture", 120)
        }
        fun put(root: File, name: String, content: String = "content") = File(root, name).apply {
            parentFile.mkdirs(); writeText(content)
        }
        fun root(id: String): ContainerConfig {
            val root = File(data, "rootfs/$id").apply { mkdirs() }
            put(root, "etc/passwd", "root:x:0:0::/root:/bin/sh\nalice:x:1001:1002::/home/alice:/bin/sh\n")
            put(root, "etc/os-release", "ID=debian\nVERSION_ID=13\n")
            Files.copy(File("/bin/sh").toPath(), put(root, "bin/sh").toPath(), StandardCopyOption.REPLACE_EXISTING)
            File(root, "bin/sh").setExecutable(true)
            return ContainerConfig(id, id, root.path, arch = System.getProperty("os.arch"), shell = "/bin/sh", link2symlink = false)
        }
        fun mode(file: File) = (Files.getAttribute(file.toPath(), "unix:mode", NOFOLLOW_LINKS) as Number).toInt() and 0xFFF
        fun owner(file: File) = run("stat -c '%u:%g:%a' -- ${shellQuote(file.path)}").trim()
        fun restart() {
            runtime.close()
            runtime = LocalContainerRuntime(RuntimePaths(data, hostProot = File(tools, "proot-probe").path), RootfsArchives::size,
                startRootProcess = ::start, runRootCommand = ::run)
        }
        override fun close() { runtime.close(); RootfsArchives.deleteTree(directory) }
    }

    private fun skippedSwitch(fromChroot: Boolean) = runBlocking {
        Fixture().use { f ->
            val initial = f.root("deferred")
            val root = File(initial.rootfsPath)
            val payload = f.put(root, "var/lib/payload", "retain bytes and inode")
            Files.setAttribute(payload.toPath(), "unix:mode", "4751".toInt(8))
            f.runtime.register(initial)
            if (fromChroot) f.runtime.update(initial.copy(engine = ContainerEngineType.CHROOT))
            val previous = f.runtime.containers.value.single().config
            val beforeManifest = RootfsOwnership.load(root)
            val owner = f.owner(payload)
            val inode = Files.getAttribute(payload.toPath(), "unix:ino", NOFOLLOW_LINKS)
            val scans = f.scans
            val mutations = f.mutations
            val targetEngine = if (fromChroot) ContainerEngineType.PROOT else ContainerEngineType.CHROOT
            f.runtime.update(previous.copy(engine = targetEngine), migrateOwnership = false)
            assertTrue(f.runtime.containers.value.single().config.ownershipMigrationDeferred)
            assertEquals(targetEngine, f.runtime.containers.value.single().config.engine)
            assertEquals(scans, f.scans)
            assertEquals(mutations, f.mutations)
            assertEquals(owner, f.owner(payload))
            assertEquals(inode, Files.getAttribute(payload.toPath(), "unix:ino", NOFOLLOW_LINKS))
            val retained = RootfsOwnership.load(root)!!
            assertEquals(RootfsOwnership.mapped(previous.engine), retained.physical)
            if (beforeManifest == null) {
                assertTrue(retained.entries.isEmpty())
                assertEquals("physical-only-v1", retained.origin)
            } else assertEquals(beforeManifest, retained)
            f.restart()
            assertTrue(f.runtime.containers.value.single().config.ownershipMigrationDeferred)
            f.runtime.update(f.runtime.containers.value.single().config.copy(autoSleep = false))
            assertTrue(f.runtime.containers.value.single().config.ownershipMigrationDeferred)
            assertFalse(f.runtime.containers.value.single().config.autoSleep)
            assertEquals(retained, RootfsOwnership.load(root))
            assertEquals(owner, f.owner(payload))
            assertEquals(scans, f.scans)
            assertEquals(mutations, f.mutations)
            val archive = File(f.directory, "deferred.tar.zst")
            f.runtime.exportRootfs(initial.id, archive.path)
            val restored = File(f.directory, "restored")
            RootfsArchives.extract(archive, restored)
            assertEquals(LogicalOwner(0, 0, "4751".toInt(8)), RootfsOwnership.load(restored)!!.entries["/var/lib/payload"])
            assertEquals("retain bytes and inode", File(restored, "var/lib/payload").readText())
            val beforeClone = f.mutations
            val clone = f.runtime.clone(initial.id, "Deferred clone")
            assertTrue(clone.ownershipMigrationDeferred)
            assertEquals(targetEngine, clone.engine)
            assertEquals(ContainerEngineType.PROOT, RootfsOwnership.load(File(clone.rootfsPath))!!.physical)
            assertEquals(beforeClone, f.mutations)
            val current = f.runtime.containers.value.first { it.config.id == initial.id }.config
            f.runtime.update(current, migrateOwnership = true)
            assertFalse(f.runtime.containers.value.first { it.config.id == initial.id }.config.ownershipMigrationDeferred)
            assertEquals(targetEngine, RootfsOwnership.load(root)!!.physical)
            assertEquals(null, RootfsOwnership.load(root)!!.pending)
            assertEquals("4751".toInt(8), f.mode(payload))
            val expectedOwner = if (fromChroot) "${ChrootHostIdentity.uid}:${ChrootHostIdentity.gid}" else "0:0"
            assertEquals(expectedOwner, f.owner(payload).substringBeforeLast(':'))
            f.restart()
            assertFalse(f.runtime.containers.value.first { it.config.id == initial.id }.config.ownershipMigrationDeferred)
        }
    }

    @Test fun mappedToChrootCanDeferWithoutScanningAndConvertLater() = skippedSwitch(fromChroot = false)

    @Test fun chrootToMappedCanDeferWithoutScanningAndConvertLater() = skippedSwitch(fromChroot = true)

    @Test fun returningToMappedAfterADeferredChrootChoiceKeepsPortableUserIdentity() = runBlocking {
        Fixture().use { f ->
            val config = f.root("return-mapped")
            val root = File(config.rootfsPath)
            val alice = f.put(root, "home/alice/private")
            Files.setAttribute(alice.toPath(), "unix:mode", 384)
            f.runtime.register(config)
            f.runtime.update(config.copy(engine = ContainerEngineType.CHROOT), migrateOwnership = false)
            f.runtime.update(f.runtime.containers.value.single().config.copy(engine = ContainerEngineType.PROOT), migrateOwnership = true)
            assertEquals(LogicalOwner(1001, 1002, 384), RootfsOwnership.load(root)!!.entries["/home/alice/private"])
            assertFalse(f.runtime.containers.value.single().config.ownershipMigrationDeferred)
        }
    }

    @Test fun failedConversionCannotBeBypassedBySkipAndExplicitConversionResumes() = runBlocking {
        Fixture().use { f ->
            val config = f.root("pending")
            val root = File(config.rootfsPath)
            f.put(root, "home/alice/private")
            f.runtime.register(config)
            f.runtime.update(config.copy(engine = ContainerEngineType.CHROOT), migrateOwnership = false)
            val deferred = f.runtime.containers.value.single().config
            f.failMutation = true
            assertFails { f.runtime.update(deferred, migrateOwnership = true) }
            val journal = RootfsOwnership.load(root)!!
            assertTrue(journal.pending != null)
            assertTrue(f.runtime.containers.value.single().config.ownershipMigrationDeferred)
            val before = f.runtime.containers.value.single()
            assertFails { f.runtime.update(deferred.copy(engine = ContainerEngineType.PROOT), migrateOwnership = false) }
            assertEquals(before.config, f.runtime.containers.value.single().config)
            assertEquals(before.stopGeneration, f.runtime.containers.value.single().stopGeneration)
            assertEquals(journal, RootfsOwnership.load(root))
            assertFails { f.runtime.start(config.id) }
            f.failMutation = false
            f.runtime.update(deferred, migrateOwnership = true)
            assertEquals(null, RootfsOwnership.load(root)!!.pending)
            assertFalse(f.runtime.containers.value.single().config.ownershipMigrationDeferred)
            assertEquals("1001:1002", f.owner(File(root, "home/alice/private")).substringBeforeLast(':'))
        }
    }

    @Test fun mappedPrepareDoesNotUndoDeferralAndStillRejectsPendingJournal() = runBlocking {
        Fixture().use { f ->
            val config = f.root("prepare-mapped")
            val root = File(config.rootfsPath)
            val payload = f.put(root, "var/lib/private")
            Files.setAttribute(payload.toPath(), "unix:mode", 384)
            f.runtime.register(config)
            f.runtime.update(config.copy(engine = ContainerEngineType.CHROOT))
            f.runtime.update(config, migrateOwnership = false)
            val deferred = f.runtime.containers.value.single().config
            val before = RootfsOwnership.load(root)!!
            val owner = f.owner(payload)
            val mutations = f.mutations
            val scans = f.scans
            val engines = RuntimeEngines(RuntimePaths(f.data, hostProot = File(f.tools, "proot-probe").path), f::run)
            engines.prepare(deferred)
            assertEquals(mutations, f.mutations)
            assertEquals(scans, f.scans)
            assertEquals(before.physical, RootfsOwnership.load(root)!!.physical)
            assertEquals(owner, f.owner(payload))
            RootfsOwnership.save(root, before.copy(pending = OwnershipMigration(ContainerEngineType.CHROOT, ContainerEngineType.PROOT)))
            assertFails { engines.prepare(deferred) }
            assertEquals(mutations, f.mutations)
            assertEquals(scans, f.scans)
        }
    }

    @Test fun chrootPreparePreservesDeferredMappedTreeAndPropagatesMountFailure() = runBlocking {
        Fixture().use { f ->
            val config = f.root("prepare-chroot")
            val root = File(config.rootfsPath)
            val payload = f.put(root, "var/lib/private")
            f.runtime.register(config)
            f.runtime.update(config.copy(engine = ContainerEngineType.CHROOT), migrateOwnership = false)
            val deferred = f.runtime.containers.value.single().config
            val before = RootfsOwnership.load(root)
            val owner = f.owner(payload)
            val mutations = f.mutations
            val scans = f.scans
            val mountFailure = IllegalStateException("mount refused by fixture")
            val engines = RuntimeEngines(RuntimePaths(f.data)) { command ->
                if (command.contains("mount -t proc")) throw mountFailure
                f.run(command)
            }
            val failure = assertFails { engines.prepare(deferred) }
            assertSame(mountFailure, failure.cause)
            assertEquals(before, RootfsOwnership.load(root))
            assertEquals(owner, f.owner(payload))
            assertEquals(mutations, f.mutations)
            assertEquals(scans, f.scans)
        }
    }

    @Test fun deferredChrootExportsMappedLogicalIdsAndModesAndWritesMetadataWithoutConverting() = runBlocking {
        Fixture().use { f ->
            val config = f.root("portable").copy(user = "alice")
            val root = File(config.rootfsPath)
            val payload = f.put(root, "home/alice/payload", "portable")
            Files.setAttribute(payload.toPath(), "unix:mode", "6751".toInt(8))
            RootfsOwnership.save(root, OwnershipManifest(mapOf("/home/alice/payload" to LogicalOwner(1001, 2001, "6751".toInt(8)))))
            f.runtime.register(config)
            val owner = f.owner(payload)
            f.runtime.update(config.copy(engine = ContainerEngineType.CHROOT), migrateOwnership = false)
            val deferred = f.runtime.containers.value.single().config
            val snapshot = f.runtime.readMetadata(config.id)
            f.runtime.saveMetadata(config.id, snapshot.value.copy(title = "Offline deferred edit"), snapshot.revision)
            assertEquals("Offline deferred edit", f.runtime.readMetadata(config.id).value.title)
            assertEquals(ContainerEngineType.PROOT, RootfsOwnership.load(root)!!.physical)
            val document = File(root, "home/alice/.wawlink/container.json")
            assertEquals(ChrootHostIdentity.uid.toLong(), (Files.getAttribute(document.toPath(), "unix:uid") as Number).toLong())
            assertEquals(0, f.mutations)
            assertEquals(0, f.scans)
            val archive = File(f.directory, "portable.tar.zst")
            f.runtime.exportRootfs(config.id, archive.path)
            val restored = File(f.directory, "restored-portable")
            RootfsArchives.extract(archive, restored)
            assertEquals(LogicalOwner(1001, 2001, "6751".toInt(8)), RootfsOwnership.load(restored)!!.entries["/home/alice/payload"])
            assertEquals(owner, f.owner(payload))
            assertTrue(deferred.ownershipMigrationDeferred)
            val clone = f.runtime.clone(config.id, "Portable clone")
            assertTrue(clone.ownershipMigrationDeferred)
            assertEquals(LogicalOwner(1001, 2001, "6751".toInt(8)), RootfsOwnership.load(File(clone.rootfsPath))!!.entries["/home/alice/payload"])
            assertEquals(0, f.mutations)
        }
    }

    @Test fun legacyChrootSkipRecordsOnlyActualPhysicalIdentityUntilExplicitConversion() = runBlocking {
        Fixture().use { f ->
            val config = f.root("legacy").copy(engine = ContainerEngineType.CHROOT, ownershipMigrationDeferred = true)
            val root = File(config.rootfsPath)
            val alice = f.put(root, "home/alice/private")
            f.run("chown -h 1001:1002 ${shellQuote(alice.path)}")
            f.runtime.register(config)
            assertEquals(null, RootfsOwnership.load(root))
            val owner = f.owner(alice)
            f.runtime.update(config.copy(engine = ContainerEngineType.PROOT), migrateOwnership = false)
            val marker = RootfsOwnership.load(root)!!
            assertEquals(ContainerEngineType.CHROOT, marker.physical)
            assertTrue(marker.entries.isEmpty())
            assertEquals(owner, f.owner(alice))
            assertEquals(0, f.scans)
            assertEquals(0, f.mutations)
            f.runtime.update(f.runtime.containers.value.single().config, migrateOwnership = true)
            val converted = RootfsOwnership.load(root)!!
            assertEquals(ContainerEngineType.PROOT, converted.physical)
            assertEquals(1001, converted.entries.getValue("/home/alice/private").uid.toInt())
            assertEquals(1002, converted.entries.getValue("/home/alice/private").gid.toInt())
        }
    }

    @Test fun switchingBothWaysRetainsNativeChmodAndEveryUsersIdentity() = runBlocking {
        Fixture().use { f ->
            val config = f.root("original")
            val root = File(config.rootfsPath)
            val secret = f.put(root, "root/.vnc/passwd", "test-password")
            val alice = f.put(root, "home/alice/.Xauthority", "cookie")
            Files.setAttribute(secret.toPath(), "unix:mode", 511)
            f.runtime.register(config)
            f.runtime.update(config.copy(engine = ContainerEngineType.CHROOT))
            assertEquals("0:0:600", f.owner(secret))
            assertEquals("1001:1002:600", f.owner(alice))
            Files.setAttribute(secret.toPath(), "unix:mode", 416) // A later guest chmod is authoritative.
            f.runtime.update(config)
            assertEquals(LogicalOwner(0, 0, 416), RootfsOwnership.load(root)!!.entries["/root/.vnc/passwd"])
            assertEquals("${ChrootHostIdentity.uid}:${ChrootHostIdentity.gid}:640", f.owner(secret))
            f.runtime.update(config.copy(engine = ContainerEngineType.CHROOT))
            assertEquals("0:0:640", f.owner(secret))
            assertFalse(f.runtime.containers.value.single().running)
        }
    }

    @Test fun rootOwnedPrivateStateCanExportCloneSanitizeAndDeleteWithoutOpeningPermissions() = runBlocking {
        Fixture().use { f ->
            val config = f.root("original")
            val root = File(config.rootfsPath)
            val secret = f.put(root, "root/.ssh/id_ed25519", "test-only-private")
            Files.setAttribute(secret.toPath(), "unix:mode", 384)
            f.put(root, "root/.vnc/passwd", "test-only-vnc")
            f.put(root, "root/.wawlink/container.json", "{\"title\":\"Original\"}")
            Files.createLink(File(root, "credential-alias").toPath(), secret.toPath())
            f.runtime.register(config)
            f.runtime.update(config.copy(engine = ContainerEngineType.CHROOT))
            val archive = File(f.directory, "full.tar.zst")
            f.runtime.exportRootfs(config.id, archive.path)
            val restored = File(f.directory, "restored")
            RootfsArchives.extract(archive, restored)
            assertEquals("test-only-private", File(restored, "root/.ssh/id_ed25519").readText())
            assertEquals(LogicalOwner(0, 0, 384), RootfsOwnership.load(restored)!!.entries["/root/.ssh/id_ed25519"])
            val sanitized = File(f.directory, "sanitized.tar.zst")
            f.runtime.exportRootfs(config.id, sanitized.path, RootfsExportSanitization(sshGitAndCaches = true))
            val safe = File(f.directory, "safe")
            RootfsArchives.extract(sanitized, safe)
            assertFalse(File(safe, "root/.ssh").exists())
            assertFalse(File(safe, "credential-alias").exists())
            assertEquals(384, f.mode(secret))
            assertEquals(448, f.mode(secret.parentFile))
            val clone = f.runtime.clone(config.id, "Cloned")
            assertEquals(ContainerEngineType.CHROOT, clone.engine)
            assertEquals("Cloned", f.runtime.readMetadata(clone.id).value.title)
            assertEquals("0:0:600", f.owner(File(clone.rootfsPath, "root/.vnc/passwd")))
            val sibling = RootfsOwnership.sidecar(root).readText()
            f.runtime.delete(clone.id)
            assertFalse(File(clone.rootfsPath).exists())
            assertFalse(RootfsOwnership.sidecar(File(clone.rootfsPath)).exists())
            assertEquals(sibling, RootfsOwnership.sidecar(root).readText())
            f.runtime.delete(config.id)
            assertFalse(root.exists())
            assertTrue(f.runtime.containers.value.isEmpty())
        }
    }

    @Test fun rootOverlayPreflightsBeforeMigrationThenMergesPortableOwnersAndProtectedData() = runBlocking {
        Fixture().use { f ->
            val target = f.root("target")
            val root = File(target.rootfsPath)
            f.put(root, "root/.ssh/private", "local-secret")
            f.put(root, "local-only", "retain")
            f.put(root, "local-directory/keep", "retain-alias-target")
            File(root, "opt").mkdir()
            Files.createSymbolicLink(File(root, "opt/package").toPath(), java.nio.file.Path.of("../local-directory"))
            f.runtime.register(target)
            f.runtime.update(target.copy(engine = ContainerEngineType.CHROOT))
            val source = f.root("source")
            val sourceRoot = File(source.rootfsPath)
            f.put(sourceRoot, "root/.ssh/private", "must-not-import")
            f.put(sourceRoot, "opt/package/item", "unsafe-parent")
            val invalid = File(f.directory, "invalid.tar.zst")
            RootfsArchives.export(sourceRoot, invalid)
            val inspection = f.runtime.inspectRootfsArchive(invalid.path, target.name)
            val before = RootfsOwnership.load(root)
            val mutations = f.mutations
            assertFails { f.runtime.importRootfs(inspection.id, target.name, RootfsImportMode.UPDATE, target.id) }
            assertEquals(mutations, f.mutations)
            assertEquals(before, RootfsOwnership.load(root))
            assertEquals("retain-alias-target", File(root, "local-directory/keep").readText())
            f.runtime.discardRootfsImport(inspection.id)
            RootfsArchives.deleteTree(File(sourceRoot, "opt"))
            val tool = f.put(sourceRoot, "usr/bin/sudo", "updated-tool")
            Files.setAttribute(tool.toPath(), "unix:mode", 2541) // 04755
            f.put(sourceRoot, "home/alice/private", "new-user-data")
            val archive = File(f.directory, "update.tar.zst")
            RootfsArchives.export(sourceRoot, archive)
            val update = f.runtime.inspectRootfsArchive(archive.path, target.name)
            val updated = f.runtime.importRootfs(update.id, target.name, RootfsImportMode.UPDATE, target.id)
            assertEquals(ContainerEngineType.CHROOT, updated.engine)
            assertEquals("local-secret", File(root, "root/.ssh/private").readText())
            assertEquals("retain", File(root, "local-only").readText())
            assertEquals("updated-tool", File(root, "usr/bin/sudo").readText())
            assertEquals("0:0:4755", f.owner(File(root, "usr/bin/sudo")))
            assertEquals("1001:1002", f.owner(File(root, "home/alice/private")).substringBeforeLast(':'))
            assertFalse(f.runtime.containers.value.single().running)
        }
    }

    @Test fun malformedRootArchiveStopsProducerBeforeWaitingForItsErrorPipe() = runBlocking {
        Fixture().use { f ->
            val config = f.root("original")
            f.runtime.register(config)
            val rootConfig = config.copy(engine = ContainerEngineType.CHROOT)
            f.runtime.update(rootConfig)
            lateinit var producer: Process
            withTimeout(5_000) {
                assertFails {
                    exportOwnedRootfs(rootConfig, File(f.directory, "broken.tar.zst"), f::run,
                        startRootProcess = {
                            f.start("printf '%s' ${shellQuote("x".repeat(512))}; exec sleep 30").also { producer = it }
                        })
                }
            }
            assertTrue(producer.waitFor(3, java.util.concurrent.TimeUnit.SECONDS))
            assertFalse(f.runtime.containers.value.single().running)
        }
    }
}
