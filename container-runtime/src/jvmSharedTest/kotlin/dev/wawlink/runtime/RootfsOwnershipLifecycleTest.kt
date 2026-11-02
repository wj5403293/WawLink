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
import kotlin.test.assertTrue

/** Production runtime, shell, files, modes and tar; Root/UID/GID and the engine probe are modelled. */
class RootfsOwnershipLifecycleTest {
    private class Fixture : AutoCloseable {
        val directory = Files.createTempDirectory("wawlink-owner-lifecycle-").toFile()
        val data = File(directory, "app")
        val tools = File(directory, "tools").apply { mkdir() }
        private val state = File(directory, "owners.json").apply { writeText("{}") }
        var mutations = 0
        val runtime: LocalContainerRuntime
        init {
            for (name in listOf("stat", "chown")) {
                File(tools, name).apply {
                    writeText("#!/usr/bin/env node\n" + """
                        const fs = require('fs'), path = require('path'), cp = require('child_process');
                        const state = ${kotlinx.serialization.json.JsonPrimitive(state.path)};
                        const metadata = JSON.parse(fs.readFileSync(state, 'utf8'));
                        const args = process.argv.slice(2), target = path.resolve(args.at(-1));
                        const st = fs.lstatSync(target), key = st.dev + ':' + st.ino;
                        if (${kotlinx.serialization.json.JsonPrimitive(name)} === 'stat') {
                            const i = args.indexOf('-c') + 1;
                            args[i] = args[i].replace(/%u/g, '__UID__').replace(/%g/g, '__GID__');
                            const actual = cp.execFileSync('/usr/bin/stat', args, {encoding: 'utf8'});
                            const owner = metadata[key] || [st.uid, st.gid];
                            process.stdout.write(actual.replace(/__UID__/g, String(owner[0])).replace(/__GID__/g, String(owner[1])));
                        } else {
                            metadata[key] = args.find(a => /^\d+:\d+${'$'}/.test(a)).split(':').map(Number);
                            const temporary = state + '.' + process.pid;
                            fs.writeFileSync(temporary, JSON.stringify(metadata)); fs.renameSync(temporary, state);
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
            if (command.contains("apply()")) mutations++
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
        override fun close() { runtime.close(); RootfsArchives.deleteTree(directory) }
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
