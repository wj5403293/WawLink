package dev.wawlink.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class RootfsExportSanitizationTest {
    private fun fixture(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("wawlink-sanitized-export").toFile()
        try { block(directory) } finally { RootfsArchives.deleteTree(directory) }
    }

    private fun put(root: File, path: String, content: String = "content: $path\n") {
        File(root, path).apply { parentFile.mkdirs(); writeText(content) }
    }

    @Test fun exportedArchivesRespectEachChoiceAndKeepSourceFiles() = fixture { directory -> runBlocking {
        val root = File(directory, "rootfs").apply { mkdirs() }
        val homes = listOf("root", "home/alice", "srv/custom", "srv/bob")
        val configFiles = listOf(
            "config.yml", "config.json", "models.yml", "models.json", "auth.json", "credentials.json",
            "oauth.json", "agent.db", "agent.db-wal", "agent.db-shm",
        )
        val sessionDirs = listOf("sessions", "logs", "wawlink")
        val historyFiles = listOf(
            ".bash_history", ".zsh_history", ".ash_history", ".sh_history", ".local/share/fish/fish_history",
            ".python_history", ".node_repl_history", ".lesshst", ".viminfo", ".wget-hsts",
        )
        val credentialsAndCaches = listOf(
            ".ssh/id_ed25519", ".gnupg/private-keys/key", ".gitconfig", ".git-credentials", ".netrc", ".npmrc", ".pypirc",
            ".cargo/credentials", ".cargo/credentials.toml", ".config/gh/hosts.yml", ".cache/entry", ".npm/cache",
            ".bun/install/cache/package",
        )
        val configPaths = mutableSetOf<String>()
        val sessionPaths = mutableSetOf<String>()
        val historyPaths = mutableSetOf<String>()
        val credentialPaths = mutableSetOf<String>()
        val preservedPaths = mutableSetOf<String>()
        homes.forEach { home ->
            configFiles.forEach { file ->
                configPaths += "$home/.omp/agent/$file"
                configPaths += "$home/.omp/profiles/work/agent/$file"
                configPaths += "$home/.omp/profiles/other/agent/$file"
            }
            sessionDirs.forEach { subdir ->
                sessionPaths += "$home/.omp/agent/$subdir/nested/entry"
                sessionPaths += "$home/.omp/profiles/work/agent/$subdir/nested/entry"
                sessionPaths += "$home/.omp/profiles/other/agent/$subdir/nested/entry"
            }
            sessionPaths += "$home/.wawlink/logs/nested/run.log"
            historyFiles.forEach { historyPaths += "$home/$it" }
            credentialsAndCaches.forEach { credentialPaths += "$home/$it" }
            preservedPaths += listOf(
                "$home/.omp/agent/skills/example/SKILL.md", "$home/.omp/agent/rules/rule.md",
                "$home/.omp/agent/plugins/plugin.js", "$home/.omp/agent/managed-skills/skill.md",
                "$home/.omp/agent/AGENTS.md", "$home/.omp/agent/SYSTEM.md",
                "$home/.omp/profiles/work/agent/skills/example/SKILL.md",
                "$home/.omp/profiles/work/agent/rules/rule.md",
                "$home/.omp/profiles/work/agent/plugins/plugin.js",
                "$home/.omp/profiles/work/agent/managed-skills/skill.md",
                "$home/.omp/profiles/work/agent/AGENTS.md", "$home/.omp/profiles/work/agent/SYSTEM.md",
                "$home/.omp/agent/config.yml.backup", "$home/.omp/agent/sessions-old/keep",
                "$home/.omp/profiles/work/agent/config.json.backup", "$home/.omp/profiles/work/agent2/auth.json",
                "$home/.ssh-other/keep", "$home/.bun/install/cache-other/keep", "$home/.wawlink/container.json",
            )
        }
        sessionPaths += "tmp/wawlink/channel/request.json"
        val cacheRoots = listOf("var/cache/apt/archives", "var/lib/apt/lists", "var/cache/apk", "var/cache/pacman/pkg")
        cacheRoots.forEach { credentialPaths += "$it/partial/package" }
        preservedPaths += "var/cache/apt/archives-old/package"
        val allFiles = configPaths + sessionPaths + historyPaths + credentialPaths + preservedPaths
        allFiles.forEach { path -> put(root, path, if (path.endsWith("/.wawlink/container.json")) "{\"title\":\"Image\"}" else "content: $path\n") }
        put(root, "bin/sh", "test shell")
        put(root, "etc/passwd", """
            root:x:0:0:root:/root:/bin/sh
            alice:x:1000:1000:Alice:/home/alice:/bin/sh
            bob:x:1001:1001:Bob:/home/bob:/bin/sh
            nobody:x:65534:65534:nobody:/nonexistent:/bin/sh
        """.trimIndent() + "\n")
        Files.createSymbolicLink(File(root, "home/bob").toPath(), java.nio.file.Path.of("../srv/bob"))
        val config = ContainerConfig("source", "source", root.canonicalPath, environment = mapOf("HOME" to "/srv/custom"))
        val sourceContents = allFiles.associateWith { File(root, it).readText() }
        val runtime = LocalContainerRuntime(RuntimePaths(File(directory, "data")), { _, _ -> 0L })
        try {
            runtime.register(config)
            val scenarios = listOf(
                "full" to RootfsExportSanitization(),
                "config" to RootfsExportSanitization(ompConfigAndCredentials = true),
                "sessions" to RootfsExportSanitization(ompSessionsAndLogs = true),
                "history" to RootfsExportSanitization(shellHistory = true),
                "credentials" to RootfsExportSanitization(sshGitAndCaches = true),
                "all" to RootfsExportSanitization(true, true, true, true),
            )
            for ((name, options) in scenarios) {
                val archive = File(directory, "$name.tar.zst")
                if (name == "full") runtime.exportRootfs(config.id, archive.path)
                else runtime.exportRootfs(config.id, archive.path, options)
                val restored = File(directory, "restored-$name")
                RootfsArchives.extract(archive, restored)
                val excluded = buildSet {
                    if (options.ompConfigAndCredentials) addAll(configPaths)
                    if (options.ompSessionsAndLogs) addAll(sessionPaths)
                    if (options.shellHistory) addAll(historyPaths)
                    if (options.sshGitAndCaches) addAll(credentialPaths)
                }
                allFiles.forEach { path ->
                    val exported = File(restored, path)
                    if (path in excluded) assertFalse(exported.exists(), "$name must exclude $path")
                    else assertEquals(sourceContents.getValue(path), exported.readText(), "$name must retain $path")
                }
                cacheRoots.forEach { assertTrue(File(restored, it).isDirectory, "$name must keep cache directory $it") }
                assertTrue(Files.isSymbolicLink(File(restored, "home/bob").toPath()))
            }
            sourceContents.forEach { (path, contents) -> assertEquals(contents, File(root, path).readText(), "source changed: $path") }
            assertTrue(Files.isSymbolicLink(File(root, "home/bob").toPath()))
            assertFalse(runtime.containers.value.single().running)
        } finally { runtime.close() }
    } }

    @Test fun escapingPasswdOrUserHomeRefusesSanitizedExportWithoutTouchingSource() = fixture { directory -> runBlocking {
        val root = File(directory, "rootfs").apply { mkdirs() }
        val outside = File(directory, "outside").apply { mkdirs() }
        put(root, "bin/sh", "test shell")
        put(root, "etc/passwd", "root:x:0:0::/root:/bin/sh\n")
        put(root, "root/.bash_history", "root history")
        put(outside, "passwd", "root:x:0:0::/root:/bin/sh\n")
        put(outside, "secret", "outside must remain untouched")
        val config = ContainerConfig("source", "source", root.canonicalPath)
        val runtime = LocalContainerRuntime(RuntimePaths(File(directory, "data")), { _, _ -> 0L })
        try {
            runtime.register(config)
            val passwd = File(root, "etc/passwd")
            assertTrue(passwd.delete())
            Files.createSymbolicLink(passwd.toPath(), File(outside, "passwd").toPath())
            val full = File(directory, "full.tar.zst")
            runtime.exportRootfs(config.id, full.path)
            assertTrue(full.isFile, "full export must not depend on passwd resolution")
            val badPasswd = File(directory, "bad-passwd.tar.zst")
            assertFailsWith<IllegalStateException> {
                runtime.exportRootfs(config.id, badPasswd.path, RootfsExportSanitization(shellHistory = true))
            }
            assertFalse(badPasswd.exists())
            Files.delete(passwd.toPath())
            passwd.writeText("root:x:0:0::/root:/bin/sh\nbob:x:1001:1001::/home/bob:/bin/sh\n")
            File(root, "home").mkdirs()
            Files.createSymbolicLink(File(root, "home/bob").toPath(), outside.toPath())
            val badHome = File(directory, "bad-home.tar.zst")
            assertFailsWith<IllegalStateException> {
                runtime.exportRootfs(config.id, badHome.path, RootfsExportSanitization(ompConfigAndCredentials = true))
            }
            assertFalse(badHome.exists())
            assertEquals("root history", File(root, "root/.bash_history").readText())
            assertEquals("outside must remain untouched", File(outside, "secret").readText())
            assertTrue(Files.exists(File(root, "home/bob").toPath(), NOFOLLOW_LINKS))
            assertFalse(directory.listFiles().orEmpty().any { it.name.endsWith(".part") })
        } finally { runtime.close() }
    } }
}
