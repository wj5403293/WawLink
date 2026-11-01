package dev.wawlink.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import kotlin.test.*

/** Execute the production guest installer with real files, including release-to-release transitions. */
class ContainerFeatureSkillTest {
    private class Fixture(busybox: File? = System.getenv("WAWLINK_TEST_BUSYBOX")?.let(::File)) : AutoCloseable {
        val home = Files.createTempDirectory("wawlink-skill-中文 '").toFile()
        val directory = File(home, ".omp/agent/skills/wawlink-functions")
        val file = File(directory, "SKILL.md")
        val record = File(directory, ".wawlink-builtin.sha256")
        private val shell = if (busybox == null) listOf("/bin/sh") else {
            val binary = busybox.canonicalFile
            require(binary.canExecute()) { "WAWLINK_TEST_BUSYBOX must name an executable BusyBox binary" }
            val tools = File(home, ".busybox-bin").apply { mkdir() }
            // No host utilities on PATH: unsupported applets/options must fail, not use GNU tools.
            for (applet in listOf("readlink", "mkdir", "wc", "cat", "sha256sum", "mktemp", "rm", "rmdir", "base64", "stat", "chmod", "mv")) {
                Files.createSymbolicLink(File(tools, applet).toPath(), binary.toPath())
            }
            listOf(binary.path, "sh")
        }
        fun install(content: String = FEATURE_SKILL, succeeds: Boolean = true, sharedGroup: Boolean = false) {
            val process = ProcessBuilder(shell + listOf("-c", featureSkillInstallScript(content, sharedGroup))).apply {
                environment()["HOME"] = home.path
                if (shell.size > 1) environment()["PATH"] = File(home, ".busybox-bin").path
                redirectErrorStream(true)
            }.start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exit = process.waitFor()
            if (succeeds) assertEquals(0, exit, output) else assertNotEquals(0, exit, output)
            assertFalse(directory.listFiles().orEmpty().any { it.name.startsWith(".wawlink-skill.") })
        }
        fun seed(content: String) { directory.mkdirs(); file.writeText(content) }
        override fun close() { home.deleteRecursively() }
    }

    @Test fun installsUpdatesAcrossUnlistedVersionsAndLeavesCurrentFilesUntouched() {
        Fixture().use { f ->
            val first = "---\nname: wawlink-functions\n---\n第一版\n"
            val skipped = first + "跨过中间软件版本\n"
            f.install(first)
            assertEquals(first, f.file.readText())
            assertEquals(hash(first) + "\n", f.record.readText())
            f.file.setExecutable(true)
            f.install(skipped)
            assertEquals(skipped, f.file.readText())
            assertTrue(f.file.canExecute())
            assertEquals(hash(skipped) + "\n", f.record.readText())
            val stamp = FileTime.fromMillis(1_000_000)
            Files.setLastModifiedTime(f.file.toPath(), stamp)
            Files.setLastModifiedTime(f.record.toPath(), stamp)
            f.install(skipped)
            assertEquals(stamp, Files.getLastModifiedTime(f.file.toPath()))
            assertEquals(stamp, Files.getLastModifiedTime(f.record.toPath()))
            f.install()
            assertEquals(FEATURE_SKILL, f.file.readText())
            assertEquals(hash(FEATURE_SKILL) + "\n", f.record.readText())
        }
    }

    @Test fun chrootPristineSkillIsSharedButRecordAndUserEditsStayPrivate() {
        Fixture().use { f ->
            f.install()
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(f.file.toPath()))
            f.install(sharedGroup = true)
            assertEquals(PosixFilePermissions.fromString("rw-rw----"), Files.getPosixFilePermissions(f.file.toPath()))
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(f.record.toPath()))
            f.install("升级的内置内容\n", sharedGroup = true)
            assertEquals(PosixFilePermissions.fromString("rw-rw----"), Files.getPosixFilePermissions(f.file.toPath()))
            f.file.appendText("用户修改\n")
            Files.setPosixFilePermissions(f.file.toPath(), PosixFilePermissions.fromString("rw-------"))
            val edited = f.file.readText()
            f.install(sharedGroup = true)
            assertEquals(edited, f.file.readText())
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(f.file.toPath()))
        }
        Fixture().use { f ->
            f.install(sharedGroup = true)
            assertEquals(PosixFilePermissions.fromString("rw-rw----"), Files.getPosixFilePermissions(f.file.toPath()))
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(f.record.toPath()))
        }
        Fixture().use { f ->
            f.install()
            val privateAlias = File(f.home, "private-copy")
            Files.createLink(privateAlias.toPath(), f.file.toPath())
            f.install(sharedGroup = true)
            assertEquals(PosixFilePermissions.fromString("rw-rw----"), Files.getPosixFilePermissions(f.file.toPath()))
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(privateAlias.toPath()))
            assertEquals(FEATURE_SKILL, privateAlias.readText())
        }
    }

    @Test fun repairsOnlyStrictManagementDirectoriesAndCreatesUsableDescendants() {
        for (shared in listOf(false, true)) Fixture().use { f ->
            val imported = File(f.home, ".omp").apply { mkdir() }
            val unrelated = File(imported, "third-party").apply { mkdir() }
            Files.setPosixFilePermissions(unrelated.toPath(), PosixFilePermissions.fromString("r-x------"))
            Files.setPosixFilePermissions(imported.toPath(), PosixFilePermissions.fromString("r-x------"))
            Files.setAttribute(f.home.toPath(), "unix:mode", 0x5f8) // 02770: inherit the App shared group.
            val group = Files.getAttribute(f.home.toPath(), "unix:gid")
            f.install(sharedGroup = shared)
            for (path in listOf(".omp", ".omp/agent", ".omp/agent/skills", ".omp/agent/skills/wawlink-functions")) {
                val directory = File(f.home, path).toPath()
                assertEquals(PosixFilePermissions.fromString(if (shared) "rwxrwx---" else "rwx------"), Files.getPosixFilePermissions(directory))
                if (shared) {
                    assertEquals(0x5f8, (Files.getAttribute(directory, "unix:mode") as Int) and 0xfff)
                    assertEquals(group, Files.getAttribute(directory, "unix:gid"))
                }
            }
            assertEquals(PosixFilePermissions.fromString("r-x------"), Files.getPosixFilePermissions(unrelated.toPath()))
            assertEquals(PosixFilePermissions.fromString(if (shared) "rw-rw----" else "rw-------"), Files.getPosixFilePermissions(f.file.toPath()))
            if (shared) assertEquals(group, Files.getAttribute(f.file.toPath(), "unix:gid"))
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(f.record.toPath()))
            Files.setPosixFilePermissions(unrelated.toPath(), PosixFilePermissions.fromString("rwx------"))
        }
    }

    @Test fun repairsManagedOwnerModesByReplacementAndPreservesHardlinkedUserEdits() {
        for (shared in listOf(false, true)) Fixture().use { f ->
            f.install()
            Files.setPosixFilePermissions(f.file.toPath(), PosixFilePermissions.fromString("r--------"))
            val alias = File(f.home, "private-alias")
            Files.createLink(alias.toPath(), f.file.toPath())
            val oldRecord = File(f.home, "record-alias")
            Files.createLink(oldRecord.toPath(), f.record.toPath())
            f.install(sharedGroup = shared)
            assertEquals(PosixFilePermissions.fromString(if (shared) "rw-rw----" else "rw-------"), Files.getPosixFilePermissions(f.file.toPath()))
            assertEquals(PosixFilePermissions.fromString("r--------"), Files.getPosixFilePermissions(alias.toPath()))
            f.install("新版内置 Skill\n", sharedGroup = shared)
            assertEquals(FEATURE_SKILL, alias.readText())
            assertEquals(hash(FEATURE_SKILL) + "\n", oldRecord.readText())
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(oldRecord.toPath()))
            f.file.appendText("用户修改\n")
            Files.setPosixFilePermissions(f.file.toPath(), PosixFilePermissions.fromString("r--------"))
            val edited = f.file.readText()
            f.install(sharedGroup = shared)
            assertEquals(edited, f.file.readText())
            assertEquals(PosixFilePermissions.fromString("r--------"), Files.getPosixFilePermissions(f.file.toPath()))
        }
    }

    @Test fun adoptsKnownUntrackedBundleButPreservesUnknownAndEditedSkills() {
        Fixture().use { f ->
            f.seed(FEATURE_SKILL)
            f.install("新版内置内容\n")
            assertEquals("新版内置内容\n", f.file.readText())
            assertEquals(hash("新版内置内容\n") + "\n", f.record.readText())
            f.file.appendText("用户修改\n")
            val edited = f.file.readBytes()
            val recorded = f.record.readBytes()
            f.install("更新的软件版本\n")
            assertContentEquals(edited, f.file.readBytes())
            assertContentEquals(recorded, f.record.readBytes())
        }
        Fixture().use { f ->
            f.seed("用户同名 Skill\n")
            f.install()
            assertEquals("用户同名 Skill\n", f.file.readText())
            assertFalse(f.record.exists())
        }
    }

    @Test fun interruptedReplacementAcceptsEitherRecordedSideButNotSubsequentUserEdits() {
        for (replaced in listOf(false, true)) Fixture().use { f ->
            val old = "已安装的旧版本\n"
            val next = "上次中断的新版本\n"
            f.seed(if (replaced) next else old)
            f.record.writeText(hash(next) + "\n" + hash(old) + "\n")
            f.install()
            assertEquals(FEATURE_SKILL, f.file.readText())
            assertEquals(hash(FEATURE_SKILL) + "\n", f.record.readText())
        }
        Fixture().use { f ->
            f.seed("中断期间的用户修改\n")
            f.record.writeText(hash("新") + "\n" + hash("旧") + "\n")
            val recorded = f.record.readBytes()
            f.install()
            assertEquals("中断期间的用户修改\n", f.file.readText())
            assertContentEquals(recorded, f.record.readBytes())
        }
    }

    @Test fun refusesLinkedDirectoriesFilesRecordsAndInvalidRecords() {
        for (shared in listOf(false, true)) Fixture().use { f ->
            val outside = File(f.home, "unrelated").apply { mkdir() }
            val kept = File(outside, "SKILL.md").apply { writeText("保留") }
            f.directory.parentFile.mkdirs()
            Files.createSymbolicLink(f.directory.toPath(), outside.toPath())
            f.install(sharedGroup = shared)
            assertEquals("保留", kept.readText())
            assertFalse(File(outside, ".wawlink-builtin.sha256").exists())
            Files.delete(f.directory.toPath())
            f.directory.mkdir()
            Files.createSymbolicLink(f.file.toPath(), kept.toPath())
            f.install(sharedGroup = shared)
            assertTrue(Files.isSymbolicLink(f.file.toPath()))
            assertFalse(f.record.exists())
            Files.delete(f.file.toPath())
            f.seed(FEATURE_SKILL)
            Files.createSymbolicLink(f.record.toPath(), kept.toPath())
            f.install("新版", sharedGroup = shared)
            assertEquals(FEATURE_SKILL, f.file.readText())
            assertEquals("保留", kept.readText())
            Files.delete(f.record.toPath())
            for (bad in listOf("", "bad", "f".repeat(65), hash(FEATURE_SKILL) + "\n" + "x".repeat(64))) {
                f.record.writeText(bad)
                f.install("新版", sharedGroup = shared)
                assertEquals(FEATURE_SKILL, f.file.readText())
                assertEquals(bad, f.record.readText())
            }
        }
    }

    @Test fun validatesEveryAncestorBeforeCreatingMissingSkillDirectories() {
        for (ancestor in listOf(".omp", ".omp/agent", ".omp/agent/skills")) {
            val outside = Files.createTempDirectory("wawlink-skill-outside-").toFile()
            try {
                Fixture().use { f ->
                    val link = File(f.home, ancestor)
                    link.parentFile.mkdirs()
                    Files.createSymbolicLink(link.toPath(), outside.toPath())
                    f.install(succeeds = false)
                    assertTrue(outside.listFiles().orEmpty().isEmpty(), "Escaping ancestor must not create directories outside HOME")
                }
            } finally {
                outside.deleteRecursively()
            }
        }
        Fixture().use { f ->
            // Existing in-HOME aliases remain supported, even when descendants do not exist.
            val inside = File(f.home, "agent-data").apply { mkdir() }
            Files.createSymbolicLink(File(f.home, ".omp").toPath(), inside.toPath())
            f.install()
            assertEquals(FEATURE_SKILL, File(inside, "agent/skills/wawlink-functions/SKILL.md").readText())
        }
        Fixture().use { f ->
            val absent = File(f.home, "absent")
            Files.createSymbolicLink(File(f.home, ".omp").toPath(), absent.toPath())
            f.install(succeeds = false)
            assertFalse(absent.exists(), "Dangling ancestor must not be created through a symlink")
        }
    }

    @Test fun actualBusyBoxInstallsBeforeCoreutilsAndPreservesUpgradeProtection() {
        val busybox = System.getenv("WAWLINK_TEST_BUSYBOX")?.let(::File)
            ?: listOf(File("/bin/busybox"), File("/usr/bin/busybox")).firstOrNull { it.canExecute() }
        if (busybox == null) {
            System.err.println("BusyBox regression skipped: set WAWLINK_TEST_BUSYBOX to run with an actual BusyBox binary")
            return
        }
        Fixture(busybox).use { f ->
            assertFalse(File(f.home, ".omp").exists())
            val old = "BusyBox 初次安装\n"
            val next = "BusyBox 原子升级\n"
            f.install(old)
            val mode = PosixFilePermissions.fromString("rwxr-x---")
            Files.setPosixFilePermissions(f.file.toPath(), mode)
            f.install(next)
            assertEquals(next, f.file.readText())
            assertEquals(mode, Files.getPosixFilePermissions(f.file.toPath()))
            assertEquals(hash(next) + "\n", f.record.readText())
            // Recover a publication interrupted after the two-sided record, before content.
            f.record.writeText(hash(FEATURE_SKILL) + "\n" + hash(next) + "\n")
            f.install()
            assertEquals(FEATURE_SKILL, f.file.readText())
            assertEquals(hash(FEATURE_SKILL) + "\n", f.record.readText())
            f.file.appendText("用户修改\n")
            val edited = f.file.readText()
            f.install("不可覆盖\n")
            assertEquals(edited, f.file.readText())
            assertEquals(hash(FEATURE_SKILL) + "\n", f.record.readText())
        }
    }

    private fun hash(content: String): String = MessageDigest.getInstance("SHA-256")
        .digest(content.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
