package dev.wawlink.runtime

import com.github.luben.zstd.ZstdInputStream
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream

class RootfsArchiveOwnershipTest {
    private data class Member(val entry: TarArchiveEntry, val text: String = "")
    private data class Stored(val owner: LogicalOwner, val text: String, val link: String?, val hardlink: Boolean)

    private fun fixture(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("wawlink-archive-ownership").toFile()
        try { block(directory) } finally { RootfsArchives.deleteTree(directory) }
    }

    private fun member(name: String, uid: Long, gid: Long, mode: String, text: String = ""): Member =
        Member(TarArchiveEntry(name, true).apply {
            setUserId(uid)
            setGroupId(gid)
            this.mode = mode.toInt(8)
        }, text)

    private fun hardlink(name: String, target: String, uid: Long = 0, gid: Long = 0, mode: String = "644"): Member =
        Member(TarArchiveEntry(name, TarConstants.LF_LINK).apply {
            linkName = target
            setUserId(uid)
            setGroupId(gid)
            this.mode = mode.toInt(8)
        })

    private fun symlink(name: String, target: String): Member =
        Member(TarArchiveEntry(name, TarConstants.LF_SYMLINK).apply { linkName = target; mode = 0x1FF })

    private fun writeTar(output: OutputStream, members: List<Member>) {
        TarArchiveOutputStream(output).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
            members.forEach { (entry, text) ->
                val bytes = text.toByteArray()
                if (entry.isFile) entry.size = bytes.size.toLong()
                tar.putArchiveEntry(entry)
                if (entry.isFile) tar.write(bytes)
                tar.closeArchiveEntry()
            }
            tar.finish()
        }
    }

    private fun archive(directory: File, members: List<Member>): File = File(directory, "input.tar.gz").also {
        writeTar(GzipCompressorOutputStream(it.outputStream()), members)
    }

    private fun snapshot(archive: File): Map<String, Stored> {
        val entries = linkedMapOf<String, Stored>()
        TarArchiveInputStream(ZstdInputStream(archive.inputStream())).use { tar ->
            while (true) {
                val entry = tar.nextTarEntry ?: break
                val name = entry.name.removePrefix("./").trimEnd('/').let { if (it.isEmpty() || it == ".") "/" else "/$it" }
                entries[name] = Stored(
                    LogicalOwner(entry.longUserId, entry.longGroupId, entry.mode and 0xFFF),
                    if (entry.isFile) tar.readBytes().toString(Charsets.UTF_8) else "",
                    if (entry.isLink || entry.isSymbolicLink) entry.linkName else null,
                    entry.isLink,
                )
                assertEquals("", entry.userName)
                assertEquals("", entry.groupName)
            }
        }
        return entries
    }

    private fun mode(file: File): Int = (Files.getAttribute(file.toPath(), "unix:mode", NOFOLLOW_LINKS) as Number).toInt() and 0xFFF

    @Test fun capturesRebasedMultiUserManifestAndRoundTripsAllPermissionBits() = fixture { directory ->
        val expected = linkedMapOf(
            "/" to LogicalOwner(0, 0, "755".toInt(8)),
            "/etc" to LogicalOwner(0, 0, "755".toInt(8)),
            "/usr/bin/sudo" to LogicalOwner(0, 0, "4755".toInt(8)),
            "/home/alice" to LogicalOwner(1001, 2001, "2770".toInt(8)),
            "/home/alice/private" to LogicalOwner(1001, 2001, "600".toInt(8)),
            "/home/bob/private" to LogicalOwner(1002, 2002, "640".toInt(8)),
            "/srv/unsigned" to LogicalOwner(3_000_000_001L, 3_000_000_002L, "600".toInt(8)),
            "/tmp" to LogicalOwner(0, 0, "1777".toInt(8)),
        )
        val members = expected.map { (path, owner) ->
            val name = if (path == "/") "wrapped/" else "wrapped$path" + if (path in setOf("/etc", "/home/alice", "/tmp")) "/" else ""
            member(name, owner.uid, owner.gid, owner.mode.toString(8), if (name.endsWith('/')) "" else "payload:$path")
        }
        val root = File(directory, "root")
        RootfsArchives.extract(archive(directory, members), root)
        assertFalse(File(root, "wrapped").exists())
        val manifest = assertNotNull(RootfsOwnership.load(root))
        assertEquals(expected, manifest.entries)
        assertEquals(ContainerEngineType.PROOT, manifest.physical)
        assertTrue(File(root.path + ".wawlink-ownership.json").isFile)
        assertFalse(File(root, ".wawlink-ownership.json").exists())
        expected.forEach { (path, owner) ->
            val file = if (path == "/") root else File(root, path.removePrefix("/"))
            assertEquals(owner.mode, mode(file))
            assertEquals(ChrootHostIdentity.uid.toLong(), (Files.getAttribute(file.toPath(), "unix:uid", NOFOLLOW_LINKS) as Number).toLong())
        }
        val output = File(directory, "roundtrip.tar.zst")
        RootfsArchives.export(root, output)
        val stored = snapshot(output)
        expected.forEach { (path, owner) -> assertEquals(owner, stored.getValue(path).owner) }
        assertFalse(stored.keys.any { "wawlink-ownership" in it || it.startsWith("/wrapped") })
        val restored = File(directory, "restored")
        RootfsArchives.extract(output, restored)
        val roundtrip = assertNotNull(RootfsOwnership.load(restored))
        expected.forEach { (path, owner) -> assertEquals(owner, roundtrip.entries[path]) }
        assertEquals("payload:/home/bob/private", File(restored, "home/bob/private").readText())
    }

    @Test fun hardlinkChainsShareContentOwnerRatherThanConflictingHeaderMetadata() = fixture { directory ->
        val source = LogicalOwner(1001, 2001, "4755".toInt(8))
        for (copy in listOf(false, true)) {
            val input = archive(directory, listOf(
                hardlink("first", "second", 777, 888, "600"),
                hardlink("second", "original", 999, 111, "644"),
                member("original", source.uid, source.gid, "4755", "shared"),
            ))
            val root = File(directory, "root-$copy")
            if (copy) RootfsArchives.extract(input, root, createHardLink = { _, _ -> throw UnsupportedOperationException("no link support") })
            else RootfsArchives.extract(input, root)
            val manifest = assertNotNull(RootfsOwnership.load(root))
            listOf("first", "second", "original").forEach { name ->
                assertEquals(source, manifest.entries["/$name"])
                assertEquals(source.mode, mode(File(root, name)))
            }
            assertEquals(!copy, Files.isSameFile(File(root, "first").toPath(), File(root, "original").toPath()))
            val output = File(directory, "hardlinks-$copy.tar.zst")
            RootfsArchives.export(root, output)
            val stored = snapshot(output)
            listOf("first", "second", "original").forEach { assertEquals(source, stored.getValue("/$it").owner) }
            assertEquals(if (copy) 0 else 2, stored.values.count { it.hardlink })
            val restored = File(directory, "restored-$copy")
            RootfsArchives.extract(output, restored)
            assertEquals("shared", File(restored, "first").readText())
            listOf("first", "second", "original").forEach {
                assertEquals(source, assertNotNull(RootfsOwnership.load(restored)).entries["/$it"])
            }
        }
    }

    @Test fun physicalChmodUpdatesModeWithoutReplacingManifestIdentity() = fixture { directory ->
        val root = File(directory, "root")
        RootfsArchives.extract(archive(directory, listOf(member("owned", 1001, 2001, "4755", "data"))), root)
        Files.setAttribute(File(root, "owned").toPath(), "unix:mode", "2750".toInt(8), NOFOLLOW_LINKS)
        val output = File(directory, "chmod.tar.zst")
        RootfsArchives.export(root, output)
        assertEquals(LogicalOwner(1001, 2001, "2750".toInt(8)), snapshot(output).getValue("/owned").owner)
    }

    @Test fun legacyExportInfersAccountsWithoutSerializingTheAppUid() = fixture { directory ->
        val root = File(directory, "root").apply { mkdirs() }
        File(root, "etc").mkdirs()
        File(root, "etc/passwd").writeText("root:x:0:0::/root:/bin/sh\nalice:x:1001:2001::/home/alice:/bin/sh\n")
        val system = File(root, "usr/share/file").apply { parentFile.mkdirs(); writeText("system") }
        val private = File(root, "home/alice/private").apply { parentFile.mkdirs(); writeText("alice") }
        Files.setAttribute(system.toPath(), "unix:mode", "644".toInt(8), NOFOLLOW_LINKS)
        Files.setAttribute(private.toPath(), "unix:mode", "600".toInt(8), NOFOLLOW_LINKS)
        val genuine = if (ChrootHostIdentity.uid == 0) File(root, "srv/genuine").apply {
            parentFile.mkdirs()
            writeText("genuine")
            Files.setAttribute(toPath(), "unix:uid", 30001, NOFOLLOW_LINKS)
            Files.setAttribute(toPath(), "unix:gid", 30002, NOFOLLOW_LINKS)
            Files.setAttribute(toPath(), "unix:mode", "640".toInt(8), NOFOLLOW_LINKS)
        } else null
        val output = File(directory, "legacy.tar.zst")
        RootfsArchives.export(root, output)
        val stored = snapshot(output)
        assertEquals(LogicalOwner(0, 0, "644".toInt(8)), stored.getValue("/usr/share/file").owner)
        assertEquals(LogicalOwner(1001, 2001, "600".toInt(8)), stored.getValue("/home/alice/private").owner)
        if (genuine != null) assertEquals(LogicalOwner(30001, 30002, "640".toInt(8)), stored.getValue("/srv/genuine").owner)
        assertNull(RootfsOwnership.load(root))
    }

    @Test fun symbolicLinkOwnershipDoesNotChangeOrFollowItsTarget() = fixture { directory ->
        val alias = symlink("alias", "/target").also {
            it.entry.setUserId(1002L)
            it.entry.setGroupId(2002L)
            it.entry.mode = "755".toInt(8)
        }
        val root = File(directory, "root")
        RootfsArchives.extract(archive(directory, listOf(member("target", 1001, 2001, "600", "private"), alias)), root)
        assertEquals("600".toInt(8), mode(File(root, "target")))
        val output = File(directory, "symlink.tar.zst")
        RootfsArchives.export(root, output)
        val stored = snapshot(output)
        assertEquals(LogicalOwner(1002, 2002, "755".toInt(8)), stored.getValue("/alias").owner)
        assertEquals(LogicalOwner(1001, 2001, "600".toInt(8)), stored.getValue("/target").owner)
        assertEquals("private", stored.getValue("/target").text)
        assertEquals("target", stored.getValue("/alias").link)
    }

    @Test fun streamsNativeTarWithPrivateOwnersAndSanitizesHardlinkAliases() = fixture { directory ->
        val payload = "private-data\n".repeat(30_000)
        val input = File(directory, "native.tar")
        writeTar(input.outputStream(), listOf(
            member("./", 0, 0, "755"),
            member("./etc/", 0, 0, "755"),
            hardlink("./usr/bin/sudo-copy", "./usr/bin/sudo", 123, 456, "644"),
            member("./usr/bin/sudo", 0, 0, "4755", "sudo"),
            member("./home/alice/", 1001, 2001, "2770"),
            member("./home/alice/private", 1001, 2001, "600", payload),
            member("./home/bob/private", 1002, 2002, "600", "bob"),
            member("./tmp/", 0, 0, "1777"),
            member("./home/alice/.ssh/key", 1001, 2001, "600", "secret"),
            hardlink("./public/credential-alias", "./home/alice/.ssh/key"),
            hardlink("./public/credential-chain", "./public/credential-alias"),
            member("./proc/leak", 0, 0, "644", "proc"),
            member("./sys/leak", 0, 0, "644", "sys"),
            member("./dev/leak", 0, 0, "644", "dev"),
            member("./host-rootfs/leak", 0, 0, "644", "host"),
            Member(TarArchiveEntry("./run/fifo", TarConstants.LF_FIFO)),
            symlink("./bin", "/usr/bin"),
        ))
        val output = File(directory, "native.tar.zst")
        RootfsArchives.exportFromTar(output, input.inputStream()) { it == "home/alice/.ssh" }
        val stored = snapshot(output)
        assertEquals(LogicalOwner(1001, 2001, "2770".toInt(8)), stored.getValue("/home/alice").owner)
        assertEquals(LogicalOwner(1002, 2002, "600".toInt(8)), stored.getValue("/home/bob/private").owner)
        assertEquals(LogicalOwner(0, 0, "4755".toInt(8)), stored.getValue("/usr/bin/sudo-copy").owner)
        assertEquals(LogicalOwner(0, 0, "1777".toInt(8)), stored.getValue("/tmp").owner)
        assertEquals(payload, stored.getValue("/home/alice/private").text)
        assertEquals("/usr/bin", stored.getValue("/bin").link)
        assertTrue(stored.getValue("/usr/bin/sudo-copy").hardlink)
        listOf("/proc", "/sys", "/dev", "/host-rootfs", "/home/alice/.ssh", "/public/credential", "/run/fifo").forEach { prefix ->
            assertFalse(stored.keys.any { it.startsWith(prefix) }, prefix)
        }
        val restored = File(directory, "restored")
        RootfsArchives.extract(output, restored)
        assertEquals(payload, File(restored, "home/alice/private").readText())
        assertTrue(Files.isSameFile(File(restored, "usr/bin/sudo").toPath(), File(restored, "usr/bin/sudo-copy").toPath()))
        assertEquals("sudo", File(restored, "bin/sudo-copy").readText())
    }

    @Test fun nativeTranscodingRejectsTraversalLinksConflictsAndMissingHardlinks() = fixture { directory ->
        val unsafe = listOf(
            listOf(member("../outside", 0, 0, "644", "bad")),
            listOf(member("/outside", 0, 0, "644", "bad")),
            listOf(symlink("escape", "../../outside")),
            listOf(symlink("a", "."), symlink("escape", "a/../outside")),
            listOf(symlink("alias", "safe"), member("alias/file", 0, 0, "644", "bad")),
            listOf(hardlink("alias", "../outside")),
            listOf(hardlink("alias", "/outside")),
            listOf(hardlink("a", "b"), hardlink("b", "a")),
            listOf(hardlink("alias", "missing")),
            listOf(member("directory/", 0, 0, "755"), hardlink("alias", "directory")),
            listOf(member("same", 0, 0, "644", "first"), member("same", 0, 0, "644", "second")),
        )
        unsafe.forEachIndexed { index, members ->
            val input = File(directory, "unsafe-$index.tar")
            writeTar(input.outputStream(), members)
            assertFailsWith<IllegalArgumentException> {
                RootfsArchives.exportFromTar(File(directory, "unsafe-$index.tar.zst"), input.inputStream())
            }
        }
        assertFalse(File(directory, "outside").exists())
    }

    @Test fun failedExtractionDoesNotCommitSidecarAndDeletionDoesNotTouchSiblingManifests() = fixture { directory ->
        val failed = File(directory, "failed")
        assertFailsWith<IllegalArgumentException> {
            RootfsArchives.extract(archive(directory, listOf(member("../outside", 1, 2, "644", "bad"))), failed)
        }
        assertNull(RootfsOwnership.load(failed))
        val root = File(directory, "root").apply { mkdirs() }
        val sibling = File(directory, "root-other").apply { mkdirs() }
        val manifest = OwnershipManifest(mapOf("/" to LogicalOwner(0, 0, "755".toInt(8))))
        RootfsOwnership.save(root, manifest)
        RootfsOwnership.save(sibling, manifest)
        RootfsArchives.deleteTree(root)
        assertNull(RootfsOwnership.load(root))
        assertEquals(manifest, RootfsOwnership.load(sibling))
        RootfsOwnership.save(root, manifest)
        RootfsArchives.deleteTree(root)
        assertNull(RootfsOwnership.load(root))
        assertEquals(manifest, RootfsOwnership.load(sibling))
    }
}
