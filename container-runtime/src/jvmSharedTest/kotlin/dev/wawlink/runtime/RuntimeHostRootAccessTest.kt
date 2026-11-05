package dev.wawlink.runtime

import java.io.File
import java.nio.file.Files
import kotlin.concurrent.thread
import kotlin.test.*

class RuntimeHostRootAccessTest {
    @Test fun disabledMissingHelperDoesNotCreateDirectories() = RootfsHostFilesTest.Fixture().use { f ->
        synchronizeHostExec(f.config, f.root, false) { f.run(it) }
        assertFalse(File(f.root, "usr").exists())
    }

    @Test fun installsSystemExecutableWithoutWideningDirectoriesAndRemovesOnlyManagedBytes() = RootfsHostFilesTest.Fixture().use { f ->
        RootfsOwnership.save(f.root, OwnershipManifest(emptyMap(), physical = ContainerEngineType.CHROOT))
        val bin = File(f.root, "usr/local/bin").apply { mkdirs() }
        Files.setAttribute(bin.toPath(), "unix:mode", 0x1C0)
        synchronizeHostExec(f.config, f.root, true) { f.run(it) }
        val helper = File(bin, "host-exec")
        assertEquals(0x1ED, f.mode(helper))
        assertEquals(0x1C0, f.mode(bin))
        assertEquals(LogicalOwner(0, 0, 0x1ED), RootfsOwnership.load(f.root)!!.entries["/usr/local/bin/host-exec"])
        synchronizeHostExec(f.config, f.root, true) { f.run(it) }
        synchronizeHostExec(f.config, f.root, false) { f.run(it) }
        assertFalse(helper.exists())
        helper.writeText("user helper")
        synchronizeHostExec(f.config, f.root, false) { f.run(it) }
        assertEquals("user helper", helper.readText())
        assertFails { synchronizeHostExec(f.config, f.root, true) { f.run(it) } }
        f.noTemporaryFiles()
    }

    @Test fun refusesLinksAndStaleDeletion() = RootfsHostFilesTest.Fixture().use { f ->
        val original = f.put("original")
        val helper = File(f.root, "usr/local/bin/host-exec").apply { parentFile.mkdirs() }
        Files.createSymbolicLink(helper.toPath(), original.toPath())
        assertFails { synchronizeHostExec(f.config, f.root, true) { f.run(it) } }
        Files.delete(helper.toPath())
        Files.createLink(helper.toPath(), original.toPath())
        assertFails { synchronizeHostExec(f.config, f.root, false) { f.run(it) } }
        Files.delete(helper.toPath())
        helper.writeText("changed")
        assertFails { f.store().delete(helper, RootfsHostFiles.revision("old".toByteArray())) }
        assertEquals("changed", helper.readText())
    }

    /** Root/chown are modelled by Fixture; the chroot substitute models only host path mapping. */
    @Test fun productionHelperPreservesArgumentsBinaryStreamsExitAndCleansEnvironment(): Unit = RootfsHostFilesTest.Fixture().use { f ->
        synchronizeHostExec(f.config, f.root, true) { f.run(it) }
        File(f.tools, "chroot").apply {
            writeText("#!/usr/bin/python3\n" + """
                import os, sys
                assert sys.argv[1:3] == ['/proc/1/root', '/system/bin/sh']
                assert sys.argv[3] == '-c'
                # The test host has no Android /system; map that executable only.
                args = sys.argv[3:]
                args[1] = args[1].replace('/system/bin/sh', '/bin/sh')
                os.execv('/bin/sh', ['/bin/sh'] + args)
            """.trimIndent())
            setExecutable(true)
        }
        val helper = File(f.root, "usr/local/bin/host-exec")
        val strange = File(f.directory, "space '\$;\nfile")
        strange.writeBytes(byteArrayOf(0, 1, 10, -1))
        val consumer = File(f.directory, "consumer.py").apply {
            writeText("""
                import os, sys
                assert sys.argv[1:] == ['', "a b'${'$'}\n", sys.argv[3]]
                assert os.getcwd() == '/'
                for key in ['LD_LIBRARY_PATH', 'LD_AUDIT', 'GLIBC_TUNABLES', 'GCONV_PATH']:
                    assert key not in os.environ, key
                assert '/system/bin' in os.environ['PATH']
                assert os.environ['ANDROID_ROOT'] == '/system'
                assert os.environ['ANDROID_DATA'] == '/data'
                with open(sys.argv[3], 'rb') as source:
                    assert source.read() == bytes([0, 1, 10, 255])
                data = sys.stdin.buffer.read()
                sys.stdout.buffer.write(data)
                sys.stderr.buffer.write(bytes([255, 0, 10, 2]))
                sys.exit(37)
            """.trimIndent())
        }
        val input = ByteArray(131072) { (it % 256).toByte() }
        val process = ProcessBuilder(helper.path, "/usr/bin/python3", consumer.path, "", "a b'\$\n", strange.path).apply {
            environment()["PATH"] = f.tools.path + ":/usr/bin:/bin"
            environment()["LD_LIBRARY_PATH"] = "/untrusted"
            environment()["LD_AUDIT"] = ""
            environment()["GLIBC_TUNABLES"] = ""
            environment()["GCONV_PATH"] = "/untrusted"
            environment()["ANDROID_ROOT"] = "/guest/system"
            environment()["ANDROID_DATA"] = "/guest/data"
        }.start()
        val (output, error) = communicate(process, input, 37)
        assertContentEquals(input, output)
        assertContentEquals(byteArrayOf(-1, 0, 10, 2), error)
        val interactive = ProcessBuilder(helper.path).apply {
            environment()["PATH"] = f.tools.path + ":/usr/bin:/bin"
        }.start()
        communicate(interactive, "exit 29\n".toByteArray(), 29)
    }

    private fun communicate(process: Process, input: ByteArray, expectedExit: Int): Pair<ByteArray, ByteArray> {
        var output: Result<ByteArray>? = null
        var error: Result<ByteArray>? = null
        var written: Result<Unit>? = null
        val stdout = thread(isDaemon = true) { output = runCatching { process.inputStream.readBytes() } }
        val stderr = thread(isDaemon = true) { error = runCatching { process.errorStream.readBytes() } }
        val stdin = thread(isDaemon = true) {
            written = runCatching { process.outputStream.use { it.write(input) } }
        }
        try {
            assertTrue(process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS), "host-exec process timed out")
            assertEquals(expectedExit, process.exitValue())
            for (worker in listOf(stdin, stdout, stderr)) {
                worker.join(5_000)
                assertFalse(worker.isAlive, "host-exec stream worker timed out")
            }
            checkNotNull(written).getOrThrow()
            return checkNotNull(output).getOrThrow() to checkNotNull(error).getOrThrow()
        } finally {
            process.destroyForcibly()
            process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            runCatching { process.outputStream.close() }
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            for (worker in listOf(stdin, stdout, stderr)) worker.join(1_000)
        }
    }
}
