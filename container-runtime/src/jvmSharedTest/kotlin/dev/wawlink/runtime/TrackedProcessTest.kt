package dev.wawlink.runtime

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrackedProcessTest {
    private fun stat(pid: Long): String? = runCatching { File("/proc/$pid/stat").readText() }.getOrNull()
    private fun start(text: String): String = text.substringAfterLast(") ").split(' ')[19]
    private fun reused(text: String): String {
        val fields = text.substringAfterLast(") ").split(' ').toMutableList()
        fields[19] = (fields[19].toLong() + 1).toString()
        return text.substringBeforeLast(") ") + ") " + fields.joinToString(" ")
    }
    private fun awaitExit(process: Process) {
        assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Real process did not exit")
        assertFalse(process.isAlive)
    }
    private fun withProcess(command: List<String> = listOf("sleep", "60"), block: (Process) -> Unit) {
        val process = ProcessBuilder(command).start()
        try { block(process) }
        finally {
            if (process.isAlive) process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
            process.inputStream.close()
            process.errorStream.close()
            process.outputStream.close()
        }
    }
    private fun signal(pid: Long, force: Boolean) {
        val kill = ProcessBuilder("kill", if (force) "-KILL" else "-TERM", "--", "-$pid").start()
        assertTrue(kill.waitFor(5, TimeUnit.SECONDS))
        assertEquals(0, kill.exitValue(), kill.errorStream.bufferedReader().readText())
    }

    @Test fun unreadableStatAllowsARealChildAndOnlyItsProcessAuthorizesTermination() {
        for (force in listOf(false, true)) withProcess { process ->
            val tracked = TrackedProcess(process, keeper = false, statReader = { throw SecurityException("restricted proc") },
                groupSignal = { _, _ -> error("An ordinary process does not own its group") })
            assertEquals(listOf(process.pid()), tracked.livePids())
            assertTrue(tracked.liveIdentities().isEmpty())
            assertTrue(tracked.hasLiveProcesses())
            tracked.terminateFallback(force)
            awaitExit(process)
            assertTrue(tracked.livePids().isEmpty())
            assertFalse(tracked.hasLiveProcesses())
        }
    }
    @Test fun opaqueRootReportedAsZombieIsNotCountedAsALivePid() = withProcess { process ->
        val pid = process.pid()
        val original = requireNotNull(stat(pid))
        var visible: String? = null
        val tracked = TrackedProcess(process, keeper = false, statReader = { if (it == pid) visible else stat(it) })
        assertEquals(listOf(pid), tracked.livePids())
        visible = original.substringBeforeLast(") ") + ") Z " + original.substringAfterLast(") ").substringAfter(' ')
        assertTrue(tracked.livePids().isEmpty())
        assertTrue(tracked.liveIdentities().isEmpty())
        visible = original
        assertEquals(listOf(pid to start(original)), tracked.liveIdentities())
    }


    @Test fun recoveryPinsStarttimeAndDoesNotAcceptAReplacementIdentity() = withProcess { process ->
        val pid = process.pid()
        val original = requireNotNull(stat(pid))
        var visible: String? = null
        val tracked = TrackedProcess(process, keeper = false, statReader = { if (it == pid) visible else stat(it) })
        assertTrue(tracked.liveIdentities().isEmpty())
        visible = original
        tracked.refreshDescendants()
        assertEquals(listOf(pid to start(original)), tracked.liveIdentities())
        visible = reused(original)
        assertTrue(tracked.livePids().isEmpty())
        tracked.terminateFallback(force = true)
        assertTrue(process.isAlive, "A mismatched verified identity must not authorize fallback")
        visible = null
        tracked.terminateFallback(force = true)
        awaitExit(process)
    }

    @Test fun exitDuringStatRecoveryCannotUpgradeTheReusedPid() = withProcess { process ->
        val pid = process.pid()
        val replacement = reused(requireNotNull(stat(pid)))
        var recovering = false
        var groupSignals = 0
        val tracked = TrackedProcess(process, keeper = false, ownsProcessGroup = true,
            statReader = {
                if (recovering && it == pid) {
                    process.destroyForcibly()
                    awaitExit(process)
                    replacement
                } else null
            }, groupSignal = { _, _ -> groupSignals++ })
        recovering = true
        assertTrue(tracked.livePids().isEmpty())
        assertTrue(tracked.liveIdentities().isEmpty())
        tracked.terminateFallback(force = false)
        tracked.terminateFallback(force = true)
        assertEquals(0, groupSignals)
    }

    @Test fun exitedUnverifiedAndVerifiedRootsNeverSignalANewPidOccupant() {
        for (initiallyReadable in listOf(false, true)) withProcess { process ->
            val pid = process.pid()
            val original = requireNotNull(stat(pid))
            var visible = if (initiallyReadable) original else null
            var groupSignals = 0
            val tracked = TrackedProcess(process, keeper = false, ownsProcessGroup = true,
                statReader = { if (it == pid) visible else stat(it) }, groupSignal = { _, _ -> groupSignals++ })
            process.destroyForcibly()
            awaitExit(process)
            // A readable /proc row appearing after exit belongs to a different lifetime.
            visible = reused(original)
            tracked.refreshDescendants()
            assertTrue(tracked.livePids().isEmpty())
            assertTrue(tracked.liveIdentities().isEmpty())
            tracked.terminateFallback(force = true)
            assertEquals(0, groupSignals)
        }
    }

    @Test fun readableDescendantsRemainPinnedAfterAnOpaqueRootExits() = withProcess(
        listOf("sh", "-c", "sleep 60 & echo \$!; wait"),
    ) { process ->
        val child = process.inputStream.bufferedReader().readLine().toLong()
        val pid = process.pid()
        var replacement: String? = null
        try {
            val tracked = TrackedProcess(process, keeper = false, statReader = {
                when (it) { pid -> null; child -> replacement ?: stat(it); else -> stat(it) }
            })
            assertTrue(child in tracked.livePids())
            val identity = tracked.liveIdentities().single { it.first == child }
            tracked.terminateFallback(force = true)
            awaitExit(process)
            assertTrue(child in tracked.livePids(), "A verified descendant survives its parent's exit in tracking")
            assertEquals(identity, tracked.liveIdentities().single { it.first == child })
            replacement = reused(requireNotNull(stat(child)))
            assertFalse(child in tracked.livePids(), "A recycled descendant PID must not be adopted")
        } finally {
            val kill = ProcessBuilder("kill", "-KILL", child.toString()).start()
            kill.waitFor(5, TimeUnit.SECONDS)
        }
    }

    @Test fun onlyAnExplicitSetsidOwnerMaySignalItsRealProcessGroupWithOpaqueStat() = withProcess(
        listOf("setsid", "sh", "-c", "trap 'wait; exit 0' TERM; sleep 60 & echo \$!; wait"),
    ) { process ->
        val child = process.inputStream.bufferedReader().readLine().toLong()
        val signals = mutableListOf<Pair<Long, Boolean>>()
        val tracked = TrackedProcess(process, keeper = false, ownsProcessGroup = true, statReader = { null },
            groupSignal = { pid, force -> signals += pid to force; signal(pid, force) })
        try {
            tracked.terminateFallback(force = false)
            awaitExit(process)
            assertEquals(listOf(process.pid() to false), signals)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (stat(child)?.substringAfterLast(") ")?.startsWith("Z ") == false && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue(stat(child).let { it == null || it.substringAfterLast(") ").startsWith("Z ") }, "Group child did not exit")
            tracked.terminateFallback(force = true)
            assertEquals(1, signals.size, "Never signal a group after its held leader exits")
        } finally {
            val kill = ProcessBuilder("kill", "-KILL", child.toString()).start()
            kill.waitFor(5, TimeUnit.SECONDS)
        }
    }

    @Test fun readableStatMustConfirmTheClaimedSessionAndGroup() = withProcess { process ->
        var signals = 0
        val tracked = TrackedProcess(process, keeper = false, ownsProcessGroup = true,
            groupSignal = { _, _ -> signals++ })
        tracked.terminateFallback(force = true)
        assertEquals(0, signals, "A process in the test runner's group must not authorize group signaling")
        assertTrue(process.isAlive)
    }

    @Test fun groupSignalFailuresAreNotReportedAsSuccessfulCleanup() = withProcess { process ->
        val tracked = TrackedProcess(process, keeper = false, ownsProcessGroup = true, statReader = { null },
            groupSignal = { _, _ -> throw IllegalStateException("permission denied") })
        assertEquals("permission denied", assertFailsWith<IllegalStateException> { tracked.terminateFallback(force = true) }.message)
        assertTrue(process.isAlive)
    }

    @Test fun privilegedIdentityDoesNotUseTheOrdinaryProcessFallback() = withProcess { process ->
        val original = requireNotNull(stat(process.pid()))
        var snapshot = listOf(original)
        var fallbackSignals = 0
        val tracked = TrackedProcess(process, keeper = false, rootIdentity = process.pid() to start(original),
            privilegedSnapshot = { snapshot }, ownsProcessGroup = true, statReader = { null },
            groupSignal = { _, _ -> fallbackSignals++ })
        assertEquals(listOf(process.pid() to start(original)), tracked.liveIdentities())
        snapshot = listOf(reused(original))
        tracked.refreshDescendants()
        assertTrue(tracked.livePids().isEmpty())
        tracked.terminateFallback(force = true)
        assertTrue(process.isAlive)
        assertEquals(0, fallbackSignals)
        assertFailsWith<IllegalArgumentException> {
            TrackedProcess(process, keeper = false, privilegedSnapshot = { emptyList() }, statReader = { null })
        }
    }
}
