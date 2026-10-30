package dev.wawlink.runtime

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Runs production prepare in an ordinary shell; every privileged command is a shell function. */
class RuntimeChrootMountTest {
    private class Fixture(val directory: File, android: Boolean) {
        val data = File(directory, "app data").apply { mkdirs() }
        val root = File(directory, "root ' quoted \\ path").apply { mkdirs() }
        val source = File(directory, "host ' files \\ literal \$HOME").apply { mkdirs() }
        val target = File(root, "mnt/shared ' files \\ literal \$HOME")
        val state = File(directory, "mount state").apply { mkdirs() }
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
            val builder = ProcessBuilder("/bin/sh", "-c", MOCK_COMMANDS + "\n" + command)
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
            File(root, "bin").mkdirs()
            File(root, "bin/sh").apply { writeText("fixture only; never executed\n"); setExecutable(true) }
            // A real /dev bind exposes pts. The fake bind must not touch the host /dev.
            File(root, "dev/pts").mkdirs()
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
            assertEquals(listOf(listOf("cat", "/proc/self/mountinfo")), calls("cat"))
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
            val denied = assertFailsWith<IllegalArgumentException> { engines.prepare(config) }
            assertEquals("未获得 Root 权限，请授权 su 或切换其他引擎", denied.message)
            assertTrue(calls("mount").isEmpty())
            failure = "id"
            val command = assertFailsWith<IllegalStateException> { engines.prepare(config) }
            assertEquals("chroot Root 命令执行失败：系统命令失败（1）：su: permission denied", command.message)
            assertEquals("系统命令失败（1）：su: permission denied", command.cause?.message)
            assertTrue(calls("mount").isEmpty())
        }
    }

    companion object {
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
                printf 'uid=%s(mock) gid=0(mock)\n' "${'$'}MOCK_UID"
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
                    echo 'unexpected cat invocation' >&2; return 64
                fi
            }
        """.trimIndent()
    }
}
