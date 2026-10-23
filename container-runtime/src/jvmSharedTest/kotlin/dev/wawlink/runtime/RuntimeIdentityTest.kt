package dev.wawlink.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuntimeIdentityTest {
    private fun fixture(block: (File, File) -> Unit) {
        val directory = Files.createTempDirectory("wawlink-identity-test").toFile()
        val root = File(directory, "root").apply { mkdirs() }
        File(root, "etc").mkdirs()
        try { block(directory, root) } finally { RootfsArchives.deleteTree(directory) }
    }

    @Test fun readsRealSupplementaryIdsRatherThanTheAndroidUid() {
        assertEquals(setOf(3003, 9997, 20256), RuntimeIdentity.hostSupplementaryGroupIds(
            "Name:\twawlink\nUid:\t10256 10256 10256 10256\nGroups:\t9997 3003\t20256 3003 -1 invalid\n",
        ))
        assertTrue(RuntimeIdentity.hostSupplementaryGroupIds("Uid:\t10256\n").isEmpty())
    }

    @Test fun preservesGuestMappingsAndAddsOnlyMissingHostGroups() = fixture { _, root ->
        val group = File(root, "etc/group").apply { writeText("root:x:0:\ninet:x:3003:root\ncustom:x:42:alice") }
        val passwd = File(root, "etc/passwd").apply { writeText("alice:x:1000:42::/home/alice:/bin/sh\n") }
        RuntimeIdentity.update(root, setOf(0, 3003, 9997, 20256))
        assertEquals("root:x:0:\ninet:x:3003:root\ncustom:x:42:alice\nhost_gid_9997:x:9997:\nhost_gid_20256:x:20256:\n", group.readText())
        assertEquals("alice:x:1000:42::/home/alice:/bin/sh\n", passwd.readText())
        assertFalse(File(root, "etc/resolv.conf").exists())
    }

    @Test fun doesNotRewriteStableIdentityAndRepairsNewGroupsLater() = fixture { _, root ->
        val group = File(root, "etc/group").apply { writeText("root:x:0:\n") }
        RuntimeIdentity.update(root, setOf(3003))
        val timestamp = FileTime.fromMillis(1_600_000_000_000)
        Files.setLastModifiedTime(group.toPath(), timestamp)
        RuntimeIdentity.update(root, setOf(3003))
        assertEquals(timestamp, Files.getLastModifiedTime(group.toPath()))
        RuntimeIdentity.update(root, setOf(3003, 9997))
        assertEquals(1, group.readLines().count { it == "host_gid_3003:x:3003:" })
        assertTrue(group.readText().endsWith("host_gid_9997:x:9997:\n"))
    }

    @Test fun avoidsExistingNamesWithoutReplacingTheirIdentity() = fixture { _, root ->
        val group = File(root, "etc/group").apply { writeText("host_gid_3003:x:42:alice\n") }
        RuntimeIdentity.update(root, setOf(3003))
        assertEquals("host_gid_3003:x:42:alice\nhost_gid_3003_1:x:3003:\n", group.readText())
    }

    @Test fun preservesDatabasePermissions() = fixture { _, root ->
        val group = File(root, "etc/group").apply { writeText("root:x:0:\n") }
        val permissions = PosixFilePermissions.fromString("rw-r-----")
        Files.setPosixFilePermissions(group.toPath(), permissions)
        RuntimeIdentity.update(root, setOf(3003))
        assertEquals(permissions, Files.getPosixFilePermissions(group.toPath()))
    }

    @Test fun rejectsGroupDatabaseEscapingRoot() = fixture { directory, root ->
        val outside = File(directory, "outside-group").apply { writeText("untouched\n") }
        Files.createSymbolicLink(File(root, "etc/group").toPath(), outside.toPath())
        assertFailsWith<IllegalArgumentException> { RuntimeIdentity.update(root, setOf(3003)) }
        assertEquals("untouched\n", outside.readText())
    }

    @Test fun rejectsEtcDirectoryEscapingRoot() = fixture { directory, root ->
        File(root, "etc").delete()
        val outside = File(directory, "outside-etc").apply { mkdirs() }
        val group = File(outside, "group").apply { writeText("untouched\n") }
        Files.createSymbolicLink(File(root, "etc").toPath(), outside.toPath())
        assertFailsWith<IllegalArgumentException> { RuntimeIdentity.update(root, setOf(3003)) }
        assertEquals("untouched\n", group.readText())
    }

    @Test fun supportsAliasedApplicationStoragePaths() = fixture { directory, root ->
        val alias = File(directory, "user-0")
        Files.createSymbolicLink(alias.toPath(), directory.toPath())
        RuntimeIdentity.update(File(alias, "root"), setOf(3003))
        assertEquals("host_gid_3003:x:3003:\n", File(root, "etc/group").readText())
    }
}
