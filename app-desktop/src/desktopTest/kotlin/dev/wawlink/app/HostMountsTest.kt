package dev.wawlink.app

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HostMountsTest {
    private fun fixture(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("wawlink-host-mounts-").toFile()
        try { block(directory) } finally { directory.deleteRecursively() }
    }

    @Test fun resolvesPrimaryAndRemovableVolumesWithoutChangingNames() = fixture { directory ->
        val primary = File(directory, "primary").apply { mkdir() }
        val removable = File(directory, "removable").apply { mkdir() }
        val roots = mapOf("primary" to primary, "ABCD-1234" to removable)
        assertEquals(File(primary, "项目/共享 files ").path, storageTreeMountSource("primary:项目/共享 files ", roots))
        assertEquals(removable.path, storageTreeMountSource("abcd-1234:", roots))
        assertEquals(File(removable, "Documents").path, storageTreeMountSource("ABCD-1234:Documents", roots))
        assertFailsWith<IllegalStateException> { storageTreeMountSource("unknown:Documents", roots) }
    }

    @Test fun rejectsMalformedAndEscapingDocumentIds() = fixture { directory ->
        val roots = mapOf("primary" to directory)
        listOf("primary", ":Documents", "primary:/absolute", "primary:../outside", "primary:folder/../../outside", "primary:./folder", "primary:folder\u0000name", "primary:folder:name").forEach { id ->
            assertFailsWith<IllegalArgumentException>(id) { storageTreeMountSource(id, roots) }
        }
    }

    @Test fun rejectsTreeSymlinksOutsideTheSelectedVolume() = fixture { directory ->
        val volume = File(directory, "volume").apply { mkdir() }
        val sibling = File(directory, "volume-other").apply { mkdir() }
        Files.createSymbolicLink(File(volume, "escape").toPath(), sibling.toPath())
        assertFailsWith<IllegalArgumentException> {
            storageTreeMountSource("primary:escape", mapOf("primary" to volume))
        }
    }

    @Test fun acceptsAccessibleDirectoriesAndExistingFileMountsByRealPath() = fixture { directory ->
        val source = File(directory, "shared files").apply { mkdir() }
        val alias = File(directory, "sdcard-alias")
        Files.createSymbolicLink(alias.toPath(), source.toPath())
        assertEquals(source.path, validateHostMountSource(alias.path, readWrite = true))
        val file = File(source, "existing.txt").apply { writeText("original") }
        assertEquals(file.path, validateHostMountSource(file.path, readWrite = false))
        assertEquals("original", file.readText())
        assertFailsWith<IllegalArgumentException> { validateHostMountSource(File(source, "missing").path, readWrite = true) }
        assertFailsWith<IllegalArgumentException> { validateHostMountSource("relative/path", readWrite = true) }
        assertFailsWith<IllegalArgumentException> { validateHostMountSource("content://provider/tree/primary:Documents", readWrite = true) }
    }
}
