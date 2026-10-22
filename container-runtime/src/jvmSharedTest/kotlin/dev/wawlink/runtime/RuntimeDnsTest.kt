package dev.wawlink.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RuntimeDnsTest {
    private fun fixture(block: (File, File) -> Unit) {
        val directory = Files.createTempDirectory("wawlink-dns-test").toFile()
        val root = File(directory, "root").apply { mkdirs() }
        try { block(directory, root) } finally { RootfsArchives.deleteTree(directory) }
    }

    @Test fun replacesFinalResolverSymlinkWithoutModifyingItsExternalTarget() = fixture { directory, root ->
        File(root, "etc").mkdirs()
        val outside = File(directory, "outside.conf").apply { writeText("untouched\n") }
        val resolver = File(root, "etc/resolv.conf")
        Files.createSymbolicLink(resolver.toPath(), outside.toPath())
        RuntimeDns.update(root, "nameserver 192.0.2.1\n")
        assertFalse(Files.isSymbolicLink(resolver.toPath()))
        assertEquals("nameserver 192.0.2.1\n", resolver.readText())
        assertEquals("untouched\n", outside.readText())
    }

    @Test fun replacesDanglingSystemResolverWithAGuestReadableFile() = fixture { _, root ->
        File(root, "etc").mkdirs()
        val resolver = File(root, "etc/resolv.conf")
        Files.createSymbolicLink(resolver.toPath(), File("../run/systemd/resolve/stub-resolv.conf").toPath())
        RuntimeDns.update(root, "nameserver 2001:db8::1\n")
        assertFalse(Files.isSymbolicLink(resolver.toPath()))
        assertEquals("nameserver 2001:db8::1\n", resolver.readText())
        assertEquals(PosixFilePermissions.fromString("rw-r--r--"), Files.getPosixFilePermissions(resolver.toPath()))
        assertFalse(File(root, "run").exists())
    }

    @Test fun rejectsAnEtcDirectoryEscapingTheContainer() = fixture { directory, root ->
        val outside = File(directory, "outside").apply { mkdirs() }
        val resolver = File(outside, "resolv.conf").apply { writeText("untouched\n") }
        Files.createSymbolicLink(File(root, "etc").toPath(), outside.toPath())
        assertFailsWith<IllegalArgumentException> { RuntimeDns.update(root, "nameserver 192.0.2.1\n") }
        assertEquals("untouched\n", resolver.readText())
    }

    @Test fun stableDnsDoesNotRewriteAndAnInterfaceScopeChangeDoes() = fixture { _, root ->
        File(root, "etc").mkdirs()
        val resolver = File(root, "etc/resolv.conf")
        val initial = "nameserver fe80::1%wlan0\n"
        RuntimeDns.update(root, initial)
        val timestamp = FileTime.fromMillis(1_600_000_000_000)
        Files.setLastModifiedTime(resolver.toPath(), timestamp)
        RuntimeDns.update(root, initial)
        assertEquals(timestamp, Files.getLastModifiedTime(resolver.toPath()))
        RuntimeDns.update(root, "nameserver fe80::1%rmnet0\n")
        assertEquals("nameserver fe80::1%rmnet0\n", resolver.readText())
        assertNotEquals(timestamp, Files.getLastModifiedTime(resolver.toPath()))
    }

    @Test fun aliasedStorageParentsDoNotLookLikeAnEscapingEtcDirectory() = fixture { directory, root ->
        File(root, "etc").mkdirs()
        val alias = File(directory, "user-0")
        Files.createSymbolicLink(alias.toPath(), directory.toPath())
        RuntimeDns.update(File(alias, "root"), "nameserver 192.0.2.1\n")
        assertTrue(File(root, "etc/resolv.conf").isFile)
        assertEquals("nameserver 192.0.2.1\n", File(root, "etc/resolv.conf").readText())
    }
}
