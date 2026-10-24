package dev.wawlink.runtime

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuntimeEnginesTest {
    private fun fixture(block: (File, File, File, File) -> Unit) {
        val directory = Files.createTempDirectory("wawlink-engines-test").toFile()
        val data = File(directory, "app-data").apply { mkdirs() }
        val root = File(directory, "root").apply { mkdirs() }
        val native = File(directory, "native").apply { mkdirs() }
        File(root, "etc").mkdirs()
        File(root, "bin").mkdirs()
        File(root, "bin/sh").apply { writeText("fixture only; never executed\n"); setExecutable(true) }
        File(root, "etc/passwd").writeText("root:x:0:0::/root:/bin/sh\nalice:x:1001:1002::/home/alice:/bin/sh\n")
        // Argument-construction tests never execute these fixtures or any runtime command.
        listOf("libproroot.so", "libproroot-runtime.so", "libproroot-linker.so", "libproroot-bridge.so", "libproroot-stub-loader.so").forEach {
            File(native, it).apply { writeText("fixture only; never executed\n"); setExecutable(true) }
        }
        try { block(directory, data, root, native) } finally { RootfsArchives.deleteTree(directory) }
    }

    private fun binds(command: List<String>): List<String> = command.take(command.indexOf("/usr/bin/env"))
        .windowed(2).filter { it[0] == "-b" }.map { it[1] }

    @Test fun prorootUsesExplicitBindPairsAndPreservesRuntimeEnvironment() = fixture { directory, data, root, native ->
        val source = File(directory, "host files").apply { mkdirs() }
        val alias = File(directory, "mount-alias")
        Files.createSymbolicLink(alias.toPath(), source.toPath())
        val config = ContainerConfig(
            id = "proroot-test", name = "Proroot", rootfsPath = root.path,
            engine = ContainerEngineType.PROROOT, shell = "/bin/sh",
            mounts = listOf(ContainerMount(alias.path, "/mnt/shared files")),
            environment = mapOf("CUSTOM" to "configured", "CONFIG_ONLY" to "present"),
        )
        val arguments = listOf("/bin/sh", "-c", "printf '%s' \"\$CUSTOM\"", "quoted argument '")
        val cwd = "/workspace/it's here"
        val builder = RuntimeEngines(RuntimePaths(data, native, isAndroid = true))
            .builder(config, arguments, cwd, mapOf("CUSTOM" to "per-process"), mergeError = false)
        val command = builder.command()
        val guestIndex = command.indexOf("/usr/bin/env")
        val bindings = binds(command)
        listOf("/dev", "/proc", "/sys").filter { File(it).exists() }.forEach {
            assertTrue("$it:$it" in bindings)
        }
        assertTrue("${source.canonicalPath}:/mnt/shared files" in bindings)
        assertTrue(bindings.all { it.count { character -> character == ':' } == 1 && it.split(':').all { path -> path.startsWith('/') } })
        // Keep launcher-injected LD_PRELOAD/PROROOT_* across env -> shell -> guest execs.
        assertEquals("HOME=/root", command[guestIndex + 1])
        assertFalse("-i" in command.subList(guestIndex + 1, command.indexOf("/bin/sh")))
        assertTrue("CUSTOM=per-process" in command)
        assertTrue("CONFIG_ONLY=present" in command)
        assertFalse("CUSTOM=configured" in command)
        assertTrue("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin" in command)
        assertEquals(File(data, "tmp").path, builder.environment()["PROROOT_TMP_DIR"])
        assertTrue(File(builder.environment().getValue("PROROOT_TMP_DIR")).isDirectory)
        assertFalse("LD_LIBRARY_PATH" in builder.environment())
        assertFalse("LD_PRELOAD" in builder.environment())
    }

    @Test fun prootKeepsIdentityFlagsAndCleanGuestEnvironment() = fixture { _, data, root, _ ->
        val config = ContainerConfig(
            id = "proot-test", name = "PRoot", rootfsPath = root.path,
            arch = System.getProperty("os.arch"), user = "1001", shell = "/bin/sh",
            link2symlink = false, sysvipc = true, fakeKernel = true,
        )
        val command = RuntimeEngines(RuntimePaths(data, hostProot = "proot"))
            .builder(config, listOf("/bin/sh"), "/home/alice", emptyMap(), mergeError = true).command()
        val guestIndex = command.indexOf("/usr/bin/env")
        assertEquals("proot", command.first())
        assertEquals("1001:1002", command[command.indexOf("-i") + 1])
        assertTrue("--sysvipc" in command)
        assertEquals("6.8.0-generic", command[command.indexOf("-k") + 1])
        assertFalse("--link2symlink" in command)
        assertFalse("-q" in command)
        assertEquals("-i", command[guestIndex + 1])
        assertTrue("HOME=/home/alice" in command)
        assertTrue("USER=alice" in command)
        assertTrue("LOGNAME=alice" in command)
        listOf("/dev", "/proc", "/sys").filter { File(it).exists() }.forEach {
            assertTrue("$it:$it" in binds(command))
        }
        if (File("/etc/resolv.conf").isFile) assertTrue("/etc/resolv.conf:/etc/resolv.conf" in binds(command))
    }

    @Test fun prorootRejectsDesktopInsteadOfFallingBack() = fixture { _, data, root, _ ->
        val config = ContainerConfig(
            id = "unsupported", name = "Proroot", rootfsPath = root.path,
            engine = ContainerEngineType.PROROOT, shell = "/bin/sh",
        )
        assertFailsWith<IllegalArgumentException> {
            RuntimeEngines(RuntimePaths(data)).validate(config)
        }
    }
}
