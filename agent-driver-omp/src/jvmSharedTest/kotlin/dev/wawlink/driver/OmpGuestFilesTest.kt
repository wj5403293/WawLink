package dev.wawlink.driver

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.Base64
import kotlinx.coroutines.*
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.test.*


class OmpGuestFilesTest {
    @Test fun crashedWorkerReportsFailureRatherThanIntentionalStop() = runBlocking<Unit> {
        TestOmpGuestFiles().use { fixture ->
            val session = fixture.files.session()
            session.writeText("${fixture.guestDirectory}/before", "persisted")
            fixture.crashWorker()
            withTimeout(2_000) { while (session.alive) delay(10) }
            assertFailsWith<java.io.IOException> { session.readText("${fixture.guestDirectory}/before") }
            val recovered = fixture.files.session()
            assertEquals("persisted", recovered.readText("${fixture.guestDirectory}/before"))
        }
    }

    @Test fun cancelledWriteOpenRemovesStageWithoutClosingSharedSession() = runBlocking<Unit> {
        TestOmpGuestFiles(holdWriteOpen = true).use { fixture ->
            val session = fixture.files.session()
            File(fixture.directory, "existing.yml").writeText("unchanged")
            val writing = async { session.writeText("${fixture.guestDirectory}/cancelled.yml", "discarded") }
            try {
                fixture.awaitWriteOpened()
                assertEquals("unchanged", session.readText("${fixture.guestDirectory}/existing.yml"))
                writing.cancel()
                fixture.releaseWrite()
                withTimeout(5_000) { writing.join() }
                assertTrue(writing.isCancelled)
                assertEquals(listOf("existing.yml"), fixture.directory.list()!!.sorted())
                session.writeText("${fixture.guestDirectory}/next.yml", "next operation")
                assertEquals("next operation", session.readText("${fixture.guestDirectory}/next.yml"))
                assertEquals("unchanged", session.readText("${fixture.guestDirectory}/existing.yml"))
            } finally {
                fixture.releaseWrite()
                writing.cancelAndJoin()
            }
        }
    }

    @Test fun directoryListingPreservesEveryPageAndUnsupportedEntryType() = runBlocking<Unit> {
        TestOmpGuestFiles().use { fixture ->
            val names = (0..512).map { "session-$it.jsonl" }
            names.forEach { File(fixture.directory, it).writeText(it) }
            Files.createSymbolicLink(File(fixture.directory, "link").toPath(), fixture.directory.toPath())
            val entries = fixture.files.session().list(fixture.guestDirectory)
            assertEquals(names.sorted(), entries.filter { it.regular }.map { it.name }.sorted())
            assertFalse(entries.single { it.name == "link" }.directory)
            assertFalse(entries.single { it.name == "link" }.regular)
        }
    }
    @Test fun privateFilesRoundTripWithoutWideningExistingAncestors() = runBlocking<Unit> {
        TestOmpGuestFiles("/home/alice").use { fixture ->
            val session = fixture.files.session()
            val privateMode = PosixFilePermissions.fromString("rwx------")
            Files.setPosixFilePermissions(fixture.directory.toPath(), privateMode)
            val path = "${fixture.guestDirectory}/private.bin"
            val bytes = ByteArray(4 * 1024 * 1024 + 37) { (it % 251).toByte() }
            session.writeBytes(path, bytes, createOnly = true)
            assertContentEquals(bytes, session.readBytes(path))
            assertEquals(privateMode, Files.getPosixFilePermissions(fixture.directory.toPath()))
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(File(fixture.directory, "private.bin").toPath()))
            assertNull(session.readText("${fixture.guestDirectory}/absent"))
            assertFailsWith<java.nio.charset.CharacterCodingException> { session.readText(path) }
        }
    }

    @Test fun commitRejectsExternalMutationAndCleansStageWithoutOverwriting() = runBlocking<Unit> {
        TestOmpGuestFiles().use { fixture ->
            val session = fixture.files.session()
            val path = "${fixture.guestDirectory}/settings.yml"
            session.writeText(path, "original\n")
            val token = session.request("writeOpen", mapOf("path" to path, "expectedVersion" to session.version(path))).jsonPrimitive.contentOrNull!!
            session.request("writeChunk", mapOf("token" to token, "offset" to 0, "data" to Base64.getEncoder().encodeToString("draft\n".toByteArray())))
            File(fixture.directory, "settings.yml").writeText("external change\n")
            assertEquals("CONFLICT", assertFailsWith<OmpGuestFileException> { session.request("writeCommit", mapOf("token" to token)) }.code)
            assertEquals("external change\n", session.readText(path))
            assertEquals(listOf("settings.yml"), fixture.directory.list()!!.sorted())
        }
    }

    @Test fun linkAndHardlinkTargetsCannotEscapeOrChangeAliases() = runBlocking<Unit> {
        TestOmpGuestFiles().use { fixture ->
            val session = fixture.files.session()
            val outside = File(fixture.root, "outside").apply { mkdir() }
            val secret = File(outside, "secret").apply { writeText("keep") }
            Files.createSymbolicLink(File(fixture.directory, "linked").toPath(), outside.toPath())
            assertEquals("ELOOP", assertFailsWith<OmpGuestFileException> { session.writeText("${fixture.guestDirectory}/linked/secret", "changed") }.code)
            Files.createLink(File(fixture.directory, "alias").toPath(), secret.toPath())
            assertEquals("EPERM", assertFailsWith<OmpGuestFileException> { session.writeText("${fixture.guestDirectory}/alias", "changed") }.code)
            assertEquals("keep", secret.readText())
        }
    }

    @Test fun deniedReadIsNotAbsenceAndCancelledWaitLeavesSessionUsable() = runBlocking<Unit> {
        TestOmpGuestFiles().use { fixture ->
            val session = fixture.files.session()
            val denied = File(fixture.directory, "denied").apply { mkdir() }
            File(denied, "settings.yml").writeText("not empty")
            Files.setPosixFilePermissions(denied.toPath(), emptySet())
            try {
                assertEquals("EACCES", assertFailsWith<OmpGuestFileException> { session.readText("${fixture.guestDirectory}/denied/settings.yml") }.code)
            } finally { Files.setPosixFilePermissions(denied.toPath(), PosixFilePermissions.fromString("rwx------")) }
            val waiting = async { session.awaitResponse(fixture.guestDirectory, "abc", "instance", 60_000) }
            delay(100)
            waiting.cancelAndJoin()
            session.writeText("${fixture.guestDirectory}/after.yml", "usable")
            assertEquals("usable", session.readText("${fixture.guestDirectory}/after.yml"))
        }
    }

    @Test fun stoppedAndRestartedContainersRejectOldSessionsAndActivationTokens() = runBlocking<Unit> {
        TestOmpGuestFiles().use { fixture ->
            val old = fixture.files.session()
            fixture.stop()
            assertFailsWith<OmpProcessStoppedException> { fixture.files.session(expectedStopGeneration = old.stopGeneration) }
            assertEquals(0, fixture.starts)
            fixture.restart()
            assertFailsWith<OmpProcessStoppedException> { old.writeText("${fixture.guestDirectory}/stale", "old") }
            assertFalse(File(fixture.directory, "stale").exists())
            val current = fixture.files.session()
            current.writeText("${fixture.guestDirectory}/current", "new")
            assertEquals("new", current.readText("${fixture.guestDirectory}/current"))
        }
    }
}
