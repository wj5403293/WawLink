package dev.wawlink.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import kotlin.test.*

/** Execute the production guest installer with real files, including release-to-release transitions. */
class ContainerFeatureSkillTest {
    private class Fixture : AutoCloseable {
        val home = Files.createTempDirectory("wawlink-skill-中文 '").toFile()
        val directory = File(home, ".omp/agent/skills/wawlink-functions")
        val file = File(directory, "SKILL.md")
        val record = File(directory, ".wawlink-builtin.sha256")
        fun install(content: String = FEATURE_SKILL) {
            val process = ProcessBuilder("/bin/sh", "-c", featureSkillInstallScript(content)).apply {
                environment()["HOME"] = home.path
                redirectErrorStream(true)
            }.start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            assertEquals(0, process.waitFor(), output)
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
        Fixture().use { f ->
            val outside = File(f.home, "unrelated").apply { mkdir() }
            val kept = File(outside, "SKILL.md").apply { writeText("保留") }
            f.directory.parentFile.mkdirs()
            Files.createSymbolicLink(f.directory.toPath(), outside.toPath())
            f.install()
            assertEquals("保留", kept.readText())
            assertFalse(File(outside, ".wawlink-builtin.sha256").exists())
            Files.delete(f.directory.toPath())
            f.directory.mkdir()
            Files.createSymbolicLink(f.file.toPath(), kept.toPath())
            f.install()
            assertTrue(Files.isSymbolicLink(f.file.toPath()))
            assertFalse(f.record.exists())
            Files.delete(f.file.toPath())
            f.seed(FEATURE_SKILL)
            Files.createSymbolicLink(f.record.toPath(), kept.toPath())
            f.install("新版")
            assertEquals(FEATURE_SKILL, f.file.readText())
            assertEquals("保留", kept.readText())
            Files.delete(f.record.toPath())
            for (bad in listOf("", "bad", "f".repeat(65), hash(FEATURE_SKILL) + "\n" + "x".repeat(64))) {
                f.record.writeText(bad)
                f.install("新版")
                assertEquals(FEATURE_SKILL, f.file.readText())
                assertEquals(bad, f.record.readText())
            }
        }
    }

    private fun hash(content: String): String = MessageDigest.getInstance("SHA-256")
        .digest(content.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
