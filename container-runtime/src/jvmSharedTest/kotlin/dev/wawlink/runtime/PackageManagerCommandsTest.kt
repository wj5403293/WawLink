package dev.wawlink.runtime

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class PackageManagerCommandsTest {
    private fun fixture(block: (File, File) -> Unit) {
        val directory = Files.createTempDirectory("wawlink-package-manager-").toFile()
        val tools = File(directory, "tools").apply { mkdirs() }
        try { block(directory, tools) } finally { RootfsArchives.deleteTree(directory) }
    }

    private fun executable(directory: File, name: String, body: String): File = File(directory, name).apply {
        writeText("#!/bin/sh\n$body\n")
        setExecutable(true)
    }

    private fun run(command: String, tools: File, log: File, dpkgStatus: Int = 0): Int {
        val process = ProcessBuilder("/bin/sh", "-c", command).apply {
            environment()["PATH"] = tools.path + ":/usr/bin:/bin"
            environment()["WAWLINK_PACKAGE_LOG"] = log.path
            environment()["WAWLINK_DPKG_STATUS"] = dpkgStatus.toString()
        }.start()
        process.inputStream.use { it.readBytes() }
        process.errorStream.use { it.readBytes() }
        process.waitFor()
        return process.exitValue()
    }

    @Test fun aptToolchainInstallRepairsDpkgBeforeUpdateAndInstall() = fixture { _, tools ->
        val log = File(tools.parentFile, "commands.log")
        executable(tools, "dpkg", "printf 'dpkg:%s:%s\\n' \"${'$'}DEBIAN_FRONTEND\" \"${'$'}*\" >> \"${'$'}WAWLINK_PACKAGE_LOG\"\nexit \"${'$'}WAWLINK_DPKG_STATUS\"")
        executable(tools, "apt-get", "printf 'apt:%s:%s\\n' \"${'$'}DEBIAN_FRONTEND\" \"${'$'}*\" >> \"${'$'}WAWLINK_PACKAGE_LOG\"")

        val exit = run(PackageManagerCommands.toolchain("apt", "git", install = true), tools, log)

        assertEquals(0, exit)
        assertEquals(
            listOf(
                "dpkg:noninteractive:--configure -a",
                "apt:noninteractive:update",
                "apt:noninteractive:install -y git",
            ),
            log.readLines(),
        )
    }

    @Test fun failedDpkgRepairStopsAptWithoutMaskingItsExitStatus() = fixture { _, tools ->
        val log = File(tools.parentFile, "commands.log")
        executable(tools, "dpkg", "printf 'dpkg\\n' >> \"${'$'}WAWLINK_PACKAGE_LOG\"\nexit \"${'$'}WAWLINK_DPKG_STATUS\"")
        executable(tools, "apt-get", "printf 'apt\\n' >> \"${'$'}WAWLINK_PACKAGE_LOG\"")

        val exit = run(PackageManagerCommands.toolchain("apt", "git", install = true), tools, log, dpkgStatus = 42)

        assertEquals(42, exit)
        assertEquals(listOf("dpkg"), log.readLines())
    }

    @Test fun aptRemovalAndCacheCleanAlsoRepairDpkgFirst() = fixture { _, tools ->
        val log = File(tools.parentFile, "commands.log")
        executable(tools, "dpkg", "printf 'dpkg:%s\\n' \"${'$'}*\" >> \"${'$'}WAWLINK_PACKAGE_LOG\"")
        executable(tools, "dpkg-query", "printf 'install ok installed\\n'")
        executable(tools, "apt-get", "printf 'apt:%s\\n' \"${'$'}*\" >> \"${'$'}WAWLINK_PACKAGE_LOG\"")

        assertEquals(0, run(PackageManagerCommands.toolchain("apt", "git", install = false), tools, log))
        assertEquals(
            listOf(
                "dpkg:--configure -a",
                "apt:purge -y git",
                "apt:autoremove -y",
            ),
            log.readLines(),
        )

        log.writeText("")
        assertEquals(0, run(PackageManagerCommands.clean("apt"), tools, log))
        assertEquals(listOf("dpkg:--configure -a", "apt:clean"), log.readLines())
    }
}
