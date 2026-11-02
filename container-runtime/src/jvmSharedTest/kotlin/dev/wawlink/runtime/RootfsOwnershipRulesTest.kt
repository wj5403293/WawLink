package dev.wawlink.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RootfsOwnershipRulesTest {
    private val passwd = """
        root:x:0:0:root:/root:/bin/sh
        alice:x:1000:100:Alice:/home/alice:/bin/sh
        bob:x:1001:101:Bob:/home/bob:/bin/sh
        postgres:x:120:125:Database:/var/lib/postgresql:/usr/sbin/nologin
        redis:x:121:126:Cache:/nonexistent:/usr/sbin/nologin
    """.trimIndent()

    @Test fun defaultsSystemTreeToRootAndPreservesAllPermissionBits() {
        val rules = RootfsOwnershipRules(passwd)
        for (path in listOf("/", "/etc/passwd", "/usr/bin/sh", "/home/alice-other/file", "/home/nobody/file")) {
            assertEquals(LogicalOwner(0, 0, 0xfff), rules.infer(path, 0x8fff, false), path)
        }
        assertEquals(LogicalOwner(0, 0, 0x1a4), RootfsOwnershipRules("").infer("/root/file", 0x1a4, false))
    }

    @Test fun infersEveryUserAndServiceHomeWithLongestPathFirst() {
        val rules = RootfsOwnershipRules(passwd + "\nworker:x:122:127::/home/alice/project:/bin/sh")
        assertEquals(LogicalOwner(1000, 100, 0x1ed), rules.infer("/home/alice", 0x1ed, true))
        assertEquals(LogicalOwner(1001, 101, 0x1a4), rules.infer("/home/bob/file", 0x1a4, false))
        assertEquals(LogicalOwner(120, 125, 0x1a4), rules.infer("/var/lib/postgresql/data/file", 0x1a4, false))
        assertEquals(LogicalOwner(122, 127, 0x180), rules.infer("/home/alice/project/.Xauthority", 0x1ff, false))
        assertEquals(LogicalOwner(1000, 100, 0x1a4), rules.infer("/home/alice/project-other/file", 0x1a4, false))
    }

    @Test fun matchesOnlyImmediateExactServiceAccountNames() {
        val rules = RootfsOwnershipRules(passwd)
        for (prefix in listOf("/var/lib", "/var/log", "/run")) {
            assertEquals(LogicalOwner(121, 126, 0x1ed), rules.infer("$prefix/redis", 0x1ed, true))
            assertEquals(LogicalOwner(121, 126, 0x1a4), rules.infer("$prefix/redis/cache/file", 0x1a4, false))
            assertEquals(LogicalOwner(0, 0, 0x1a4), rules.infer("$prefix/redis-other/file", 0x1a4, false))
            assertEquals(LogicalOwner(0, 0, 0x1a4), rules.infer("$prefix/other/redis/file", 0x1a4, false))
        }
    }

    @Test fun configuredHomeReplacesOnlyTheConfiguredAccountHome() {
        val rules = RootfsOwnershipRules(passwd, "alice", "/srv/alice/")
        assertEquals("/srv/alice", rules.accounts.single { it.name == "alice" }.home)
        assertEquals(LogicalOwner(1000, 100, 0x180), rules.infer("/srv/alice/.vnc/passwd", 0x1ff, false))
        assertEquals(LogicalOwner(0, 0, 0x1a4), rules.infer("/home/alice/file", 0x1a4, false))
        assertEquals(LogicalOwner(1001, 101, 0x1a4), rules.infer("/home/bob/file", 0x1a4, false))
        assertEquals(LogicalOwner(0, 0, 0x1a4), RootfsOwnershipRules(passwd, "missing", "/srv/missing").infer("/srv/missing/file", 0x1a4, false))
    }

    @Test fun numericConfiguredUserResolvesPasswdIdentityForHomeOverride() {
        val rules = RootfsOwnershipRules(passwd, "1001", "/srv/custom")
        assertEquals("/srv/custom", rules.accounts.single { it.name == "bob" }.home)
        assertEquals(LogicalOwner(1001, 101, 0x180), rules.infer("/srv/custom/.vnc/passwd", 0x1ff, false))
        assertEquals(LogicalOwner(0, 0, 0x1a4), rules.infer("/home/bob/file", 0x1a4, false))
        val ambiguous = RootfsOwnershipRules(passwd + "\nalias:x:1001:999::/home/alias:/bin/sh", "1001", "/srv/custom")
        assertEquals(LogicalOwner(0, 0, 0x1a4), ambiguous.infer("/srv/custom/file", 0x1a4, false))
    }

    @Test fun ignoresUnsafeConfiguredHomeWithoutLosingSafePasswdHome() {
        for (home in listOf("/", "/home", "/etc", "/usr/local", "/tmp", "/root", "../outside", "/srv/../etc", "//host/share")) {
            val rules = RootfsOwnershipRules(passwd, "alice", home)
            assertEquals(LogicalOwner(1000, 100, 0x1a4), rules.infer("/home/alice/file", 0x1a4, false), home)
        }
    }

    @Test fun ignoresReservedEscapingAndMalformedPasswdHomes() {
        val dangerous = listOf(
            "/", "/bin", "/usr", "/usr/local", "/etc", "/var", "/home", "/tmp", "/var/tmp", "/run",
            "/var/lib", "/var/log", "/opt", "/srv", "/proc/self", "/dev/null", "/etc/private", "/root",
            "/nonexistent", "relative", "/home/../etc", "/home/./alice", "//home/alice", "/home/a\u0000b",
        )
        for (home in dangerous) {
            val rules = RootfsOwnershipRules("alice:x:1000:100::$home:/bin/sh")
            assertEquals(LogicalOwner(0, 0, 0x1a4), rules.infer("$home/file", 0x1a4, false), home)
        }
    }

    @Test fun ignoresConflictingHomesIndependentlyOfPasswdOrder() {
        val lines = listOf("alice:x:1000:100::/home/shared:/bin/sh", "bob:x:1001:101::/home/shared/:/bin/sh")
        for (input in listOf(lines, lines.reversed())) {
            val rules = RootfsOwnershipRules(input.joinToString("\n"))
            assertEquals(LogicalOwner(0, 0, 0x1ff), rules.infer("/home/shared/.ssh/key", 0x1ff, false))
        }
        val overrideConflict = RootfsOwnershipRules(passwd, "alice", "/home/bob")
        assertEquals(LogicalOwner(0, 0, 0x1a4), overrideConflict.infer("/home/bob/file", 0x1a4, false))
    }

    @Test fun allowsUnambiguousIdentityAliasesButRejectsConflictingNames() {
        val rules = RootfsOwnershipRules("""
            alice:x:1000:100::/home/shared:/bin/sh
            alias:x:1000:100::/home/shared:/bin/sh
            duplicate:x:1002:102::/home/one:/bin/sh
            duplicate:x:1003:103::/home/two:/bin/sh
        """.trimIndent())
        assertEquals(LogicalOwner(1000, 100, 0x1a4), rules.infer("/home/shared/file", 0x1a4, false))
        assertTrue(rules.accounts.none { it.name == "duplicate" })
        assertEquals(LogicalOwner(0, 0, 0x1a4), rules.infer("/var/lib/duplicate/file", 0x1a4, false))
    }

    @Test fun rejectsInvalidAccountRecordsWithoutDiscardingValidServiceIdentities() {
        val rules = RootfsOwnershipRules(passwd + "\n" + """

            broken:x:-1:1::/home/broken:/bin/sh
            overflow:x:4294967295:1::/home/overflow:/bin/sh
            badgroup:x:1:garbage::/home/badgroup:/bin/sh
            bad/name:x:1:1::/home/bad:/bin/sh
            truncated:x:1:1
        """.trimIndent())
        assertEquals(setOf("root", "alice", "bob", "postgres", "redis"), rules.accounts.map { it.name }.toSet())
        assertEquals("/nonexistent", rules.accounts.single { it.name == "redis" }.home)
    }

    @Test fun hardensPrivateDirectoriesAndCredentialsForEveryHome() {
        val rules = RootfsOwnershipRules(passwd)
        for ((home, uid, gid) in listOf(Triple("/root", 0L, 0L), Triple("/home/alice", 1000L, 100L), Triple("/home/bob", 1001L, 101L), Triple("/var/lib/postgresql", 120L, 125L))) {
            for (directory in listOf(".ssh", ".ssh/nested", ".vnc", ".vnc/nested")) {
                assertEquals(LogicalOwner(uid, gid, 0x1c0), rules.infer("$home/$directory", 0xfff, true))
            }
            for (credential in listOf(".ssh/id_rsa", ".ssh/id_ed25519", ".ssh/authorized_keys", ".ssh/config", ".vnc/passwd", ".Xauthority", ".ICEauthority", ".netrc", ".git-credentials", ".cargo/credentials.toml")) {
                assertEquals(LogicalOwner(uid, gid, 0x180), rules.infer("$home/$credential", 0xfff, false), credential)
            }
        }
    }

    @Test fun preservesScriptsPublicKeysAndUnrelatedNames() {
        val rules = RootfsOwnershipRules(passwd)
        for (path in listOf(".vnc/xstartup", ".vnc/custom-script", ".ssh/id_ed25519.pub", "script.sh", ".ssh-other/id_rsa", "project/.Xauthority")) {
            assertEquals(LogicalOwner(1000, 100, 0x1ed), rules.infer("/home/alice/$path", 0x1ed, false), path)
        }
    }

    @Test fun appliesSpecialRulesWithoutTreatingSymlinksAsTheirTargets() {
        val rules = RootfsOwnershipRules(passwd)
        for (path in listOf("/tmp", "/var/tmp")) {
            assertEquals(LogicalOwner(0, 0, 0x3ff), rules.infer(path, 0x1ed, true))
            assertEquals(LogicalOwner(0, 0, 0x1a4), rules.infer(path, 0x1a4, false))
        }
        for (path in listOf("/bin/sudo", "/usr/bin/sudo")) {
            assertEquals(LogicalOwner(0, 0, 0x9ed), rules.infer(path, 0x1ed, false))
            assertEquals(LogicalOwner(0, 0, 0x1ff), rules.infer(path, 0x1ff, false, symbolicLink = true))
        }
        assertEquals(LogicalOwner(1000, 100, 0x1ff), rules.infer("/home/alice/.ssh", 0x1ff, true, symbolicLink = true))
        assertEquals(LogicalOwner(1000, 100, 0x1ff), rules.infer("/home/alice/.vnc/passwd", 0x1ff, false, symbolicLink = true))
        assertEquals(LogicalOwner(0, 0, 0x1ed), rules.infer("/tmp", 0x1ed, true, symbolicLink = true))
        assertEquals(LogicalOwner(1000, 100, 0x1ed), rules.infer("/home/alice/sudo", 0x1ed, false))
    }
}
