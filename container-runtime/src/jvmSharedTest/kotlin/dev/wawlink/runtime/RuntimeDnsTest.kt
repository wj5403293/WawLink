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
import kotlin.test.assertSame
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

    private fun restrictedDns(engine: ContainerEngineType, physicalChroot: Boolean = false, accessDeniedOnly: Boolean = false) = fixture { directory, root ->
        File(root, "etc").mkdir()
        File(root, "etc/passwd").writeText("root:x:0:0::/root:/bin/sh\n")
        val resolver = File(root, "etc/resolv.conf").apply { writeText("nameserver 192.0.2.1\n") }
        Files.setPosixFilePermissions(resolver.parentFile.toPath(), PosixFilePermissions.fromString("rwxr-x--x"))
        Files.setPosixFilePermissions(resolver.toPath(), PosixFilePermissions.fromString("rw-r-----"))
        val config = ContainerConfig("restricted-dns", "restricted-dns", root.path, engine = engine)
        if (physicalChroot) RootfsOwnership.save(root, OwnershipManifest(emptyMap(), physical = ContainerEngineType.CHROOT))
        RestrictedRootShell(directory, root).use { shell ->
            if (accessDeniedOnly && !shell.appTraversalDenied) {
                // Root JVMs bypass mode 000: CHROOT/physical-CHROOT cases still run, but cannot
                // honestly exercise the App AccessDenied fallback under this host identity.
                assertEquals(0, ChrootHostIdentity.uid)
                return@fixture
            }
            RuntimeDns.update(root, "nameserver 2001:db8::53\n", config, shell::run)
            assertEquals(0, shell.mode(root))
            shell.inspect {
                assertEquals("nameserver 2001:db8::53\n", resolver.readText())
                assertEquals(PosixFilePermissions.fromString("rwxr-x--x"), Files.getPosixFilePermissions(resolver.parentFile.toPath()))
                assertEquals(PosixFilePermissions.fromString("rw-r-----"), Files.getPosixFilePermissions(resolver.toPath()))
            }
            val timestamp = FileTime.fromMillis(1_600_000_000_000)
            shell.inspect { Files.setLastModifiedTime(resolver.toPath(), timestamp) }
            RuntimeDns.update(root, "nameserver 2001:db8::53\n", config, shell::run)
            assertEquals(0, shell.mode(root))
            shell.inspect { assertEquals(timestamp, Files.getLastModifiedTime(resolver.toPath())) }
            if (physicalChroot) {
                val ownership = RootfsOwnership.load(root)!!
                assertEquals(ContainerEngineType.CHROOT, ownership.physical)
                assertEquals(416, ownership.entries.getValue("/etc/resolv.conf").mode)
            }
        }
    }

    @Test fun chrootDnsUpdatesTheRealResolverWithoutOpeningRootfsPermissions() = restrictedDns(ContainerEngineType.CHROOT)

    @Test fun prootDnsUsesPhysicalChrootAccessWithoutChangingModes() = restrictedDns(ContainerEngineType.PROOT, physicalChroot = true)

    @Test fun prootDnsFallsBackToRootWhenTheAppCannotTraverseTheRootfs() = restrictedDns(ContainerEngineType.PROOT, accessDeniedOnly = true)

    @Test fun rootAuthorizationFailureDoesNotTurnDnsUpdateIntoSuccess() = fixture { directory, root ->
        File(root, "etc").mkdir()
        val resolver = File(root, "etc/resolv.conf").apply { writeText("nameserver 192.0.2.1\n") }
        Files.setPosixFilePermissions(resolver.toPath(), PosixFilePermissions.fromString("rw-r-----"))
        val config = ContainerConfig("denied-dns", "denied-dns", root.path, engine = ContainerEngineType.CHROOT)
        RestrictedRootShell(directory, root).use { shell ->
            val refusal = IllegalStateException("Root authorization refused by test host")
            val failure = assertFailsWith<IllegalStateException> {
                RuntimeDns.update(root, "nameserver 2001:db8::53\n", config) { throw refusal }
            }
            assertSame(refusal, failure)
            assertEquals(0, shell.mode(root))
            shell.inspect {
                assertEquals("nameserver 192.0.2.1\n", resolver.readText())
                assertEquals(PosixFilePermissions.fromString("rw-r-----"), Files.getPosixFilePermissions(resolver.toPath()))
            }
        }
    }

    @Test fun aGenuinelyMissingRootfsDoesNotTurnDnsUpdateIntoSuccess() = fixture { directory, root ->
        val missing = File(directory, "actually-missing-rootfs")
        val config = ContainerConfig("missing-dns", "missing-dns", missing.path, engine = ContainerEngineType.CHROOT)
        RestrictedRootShell(directory, root).use { shell ->
            val failure = assertFailsWith<IllegalStateException> {
                RuntimeDns.update(missing, "nameserver 2001:db8::53\n", config, shell::run)
            }
            assertTrue(failure.message.orEmpty().contains("No such file"))
            assertTrue(failure.message.orEmpty().contains(missing.path))
            assertFalse(missing.exists())
            assertEquals(0, shell.mode(root))
        }
    }
}
