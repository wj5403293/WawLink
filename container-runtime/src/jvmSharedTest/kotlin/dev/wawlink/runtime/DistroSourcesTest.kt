package dev.wawlink.runtime

import java.io.File
import java.net.URI
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DistroSourcesTest {
    private val domestic = listOf(MirrorSource.TSINGHUA, MirrorSource.ALIYUN, MirrorSource.USTC)
    private val hash = "a".repeat(64)
    private val ubuntuHash = "a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2"
    private val alpineHash = "a389940ee42579ead202fb91607fb25698a58a3a5b55c15b4603e5d39cfa4492"
    private val tuna = "https://mirrors.tuna.tsinghua.edu.cn"
    private fun distro(id: String) = DistroSources.catalog.single { it.id == id }
    private fun alpineMetadata(filename: String = "alpine-minirootfs-3.21.9-aarch64.tar.gz") = "$alpineHash  $filename"

    @Test fun domesticUbuntuUsesDomesticBaseArchiveAndMetadataWithoutChangingRelease() {
        domestic.forEach { mirror ->
            val requests = mutableListOf<String>()
            val source = DistroSources.resolve(distro("ubuntu"), mirror) { url ->
                requests += url
                """
                    $ubuntuHash *ubuntu-base-24.04.5-base-arm64.tar.gz
                    ${"b".repeat(64)} *ubuntu-base-24.04.10-base-arm64.tar.gz
                    ${"c".repeat(64)} *ubuntu-base-26.04-base-arm64.tar.gz
                    ${"d".repeat(64)} *ubuntu-base-24.04.11-base-amd64.tar.gz
                """.trimIndent()
            }
            val base = "$tuna/ubuntu-cdimage/ubuntu-base/releases/24.04/release"
            assertEquals(listOf("$base/SHA256SUMS"), requests)
            assertEquals("$base/ubuntu-base-24.04.5-base-arm64.tar.gz", source.url)
            assertEquals(ubuntuHash, source.sha256)
        }
    }

    @Test fun domesticAlpineGetsMetadataAndArchiveFromSelectedMirror() {
        domestic.forEach { mirror ->
            val host = when (mirror) {
                MirrorSource.TSINGHUA -> "mirrors.tuna.tsinghua.edu.cn"
                MirrorSource.ALIYUN -> "mirrors.aliyun.com"
                else -> "mirrors.ustc.edu.cn"
            }
            val base = "https://$host/alpine/v3.21/releases/aarch64"
            val requests = mutableListOf<String>()
            val source = DistroSources.resolve(distro("alpine"), mirror) { url ->
                requests += url
                alpineMetadata()
            }
            assertEquals(listOf("$base/alpine-minirootfs-3.21.9-aarch64.tar.gz.sha256"), requests)
            assertEquals("$base/alpine-minirootfs-3.21.9-aarch64.tar.gz", source.url)
            assertEquals(alpineHash, source.sha256)
        }
    }

    @Test fun domesticLxcSelectsOnlyLatestArm64RootfsAndItsMatchingChecksum() {
        val branches = mapOf("debian" to "debian/bookworm", "arch" to "archlinux/current", "kali" to "kali/current")
        domestic.forEach { mirror -> branches.forEach { (id, branch) ->
            val base = "$tuna/lxc-images/images/$branch/arm64/default"
            val requests = mutableListOf<String>()
            val source = DistroSources.resolve(distro(id), mirror) { url ->
                requests += url
                when (url) {
                    "$base/" -> """
                        <a href="20261020_05%3A24/">old</a>
                        <a href="20261022_05:24/">latest</a>
                        <a href="https://overseas.invalid/20990101_01:01/">not a local snapshot</a>
                        <a href="../20990101_01:01/">not a child</a>
                        <a href="20261021_06%3a02/">middle</a>
                    """.trimIndent()
                    "$base/20261022_05%3A24/SHA256SUMS" -> """
                        ${"b".repeat(64)}  disk.qcow2
                        ${"c".repeat(64)}  meta.tar.xz
                        ${hash.uppercase()}  rootfs.tar.xz
                    """.trimIndent()
                    else -> error("Unexpected metadata URL: $url")
                }
            }
            assertEquals(listOf("$base/", "$base/20261022_05%3A24/SHA256SUMS"), requests)
            assertEquals("$base/20261022_05%3A24/rootfs.tar.xz", source.url)
            assertEquals(hash, source.sha256)
        } }
    }

    @Test fun domesticFedoraFailsBeforeAnyRequestInsteadOfFallbackOrVersionUpgrade() {
        domestic.forEach { mirror ->
            assertFailsWith<IllegalArgumentException> {
                DistroSources.resolve(distro("fedora"), mirror) { error("Must not contact any server") }
            }
        }
    }

    @Test fun missingOrInvalidChecksumsNeverProduceAnUnverifiedDownload() {
        val invalid = listOf("", "not-a-hash  rootfs.tar.xz", "$hash  disk.qcow2", "$hash  rootfs.tar.xz\n$hash  rootfs.tar.xz")
        invalid.forEach { sums ->
            assertFailsWith<IllegalArgumentException> {
                DistroSources.resolve(distro("debian"), MirrorSource.ALIYUN) { url ->
                    if (url.endsWith("SHA256SUMS")) sums else "<a href=\"20261022_05%3A24/\">snapshot</a>"
                }
            }
        }
        assertFailsWith<IllegalArgumentException> {
            DistroSources.resolve(distro("ubuntu"), MirrorSource.USTC) { "$hash *ubuntu-base-26.04-base-arm64.tar.gz" }
        }
        assertFailsWith<IllegalStateException> {
            DistroSources.resolve(distro("kali"), MirrorSource.TSINGHUA) { "<a href=\"https://overseas.invalid/image/\">image</a>" }
        }
        assertFailsWith<IllegalArgumentException> {
            DistroSources.resolve(distro("alpine"), MirrorSource.TSINGHUA) {
                alpineMetadata("alpine-minirootfs-3.22.0-aarch64.tar.gz")
            }
        }
        assertFailsWith<IllegalArgumentException> {
            DistroSources.resolve(distro("ubuntu"), MirrorSource.TSINGHUA) { "$hash *ubuntu-base-24.04.5-base-arm64.tar.gz" }
        }
        assertFailsWith<IllegalArgumentException> {
            DistroSources.resolve(distro("alpine"), MirrorSource.ALIYUN) { "$hash  alpine-minirootfs-3.21.9-aarch64.tar.gz" }
        }
    }

    @Test fun failedDomesticMetadataNeverRetriesOfficialServers() {
        domestic.forEach { mirror ->
            listOf("ubuntu", "debian", "arch", "alpine", "kali").forEach { id ->
                val requests = mutableListOf<String>()
                assertFailsWith<java.io.IOException> {
                    DistroSources.resolve(distro(id), mirror) { url ->
                        requests += url
                        throw java.io.IOException("Domestic metadata unavailable")
                    }
                }
                assertEquals(1, requests.size)
                assertTrue(URI(requests.single()).host in setOf("mirrors.tuna.tsinghua.edu.cn", "mirrors.aliyun.com", "mirrors.ustc.edu.cn"))
            }
        }
    }

    @Test fun officialAlpineAndKaliUseTheirOfficialMetadata() {
        val alpine = DistroSources.resolve(distro("alpine"), MirrorSource.OFFICIAL) { url ->
            assertEquals("https://dl-cdn.alpinelinux.org/alpine/v3.21/releases/aarch64/alpine-minirootfs-3.21.9-aarch64.tar.gz.sha256", url)
            alpineMetadata()
        }
        assertEquals("https://dl-cdn.alpinelinux.org/alpine/v3.21/releases/aarch64/alpine-minirootfs-3.21.9-aarch64.tar.gz", alpine.url)
        val kali = DistroSources.resolve(distro("kali"), MirrorSource.OFFICIAL) { url ->
            assertEquals("https://kali.download/nethunter-images/current/rootfs/SHA256SUMS", url)
            "$hash *kali-nethunter-rootfs-minimal-arm64.tar.xz"
        }
        assertEquals("https://kali.download/nethunter-images/current/rootfs/kali-nethunter-rootfs-minimal-arm64.tar.xz", kali.url)
        assertEquals(hash, kali.sha256)
    }

    @Test fun rejectsUncataloguedVersionsAndArchitectures() {
        assertFailsWith<IllegalArgumentException> {
            DistroSources.resolve(distro("debian").copy(version = "13"), MirrorSource.TSINGHUA) { error("Unsupported version") }
        }
        assertFailsWith<IllegalArgumentException> {
            DistroSources.resolve(distro("ubuntu").copy(arch = "amd64"), MirrorSource.TSINGHUA) { error("Unsupported architecture") }
        }
    }

    @Test fun domesticRedirectsCannotEscapeProviderAndHttpsCannotDowngrade() {
        val current = URI("$tuna/alpine/archive")
        assertEquals(URI("$tuna/alpine/next"), DistroSources.redirectTarget(current, "next", current.host))
        listOf("https://github.com/archive", "https://mirrors.tuna.tsinghua.edu.cn.evil.invalid/archive", "http://${current.host}/archive", "file:///archive").forEach { target ->
            assertFailsWith<IllegalArgumentException> { DistroSources.redirectTarget(current, target, current.host) }
        }
        assertEquals(URI("https://release-assets.githubusercontent.com/archive"),
            DistroSources.redirectTarget(URI("https://github.com/archive"), "https://release-assets.githubusercontent.com/archive", null))
    }

    @Test fun configuringAptDistrosRemovesOldSourcesWithoutDisablingSignatureChecks() = fixture { root ->
        listOf("ubuntu", "debian", "kali").forEach { id ->
            val entries = File(root, "etc/apt/sources.list.d").apply { mkdirs() }
            File(entries, "overseas.sources").writeText("URIs: https://overseas.invalid")
            File(entries, "overseas.list").writeText("deb https://overseas.invalid stable main")
            File(entries, "keep.disabled").writeText("disabled")
            val key = File(root, "etc/apt/trusted.gpg.d/archive.gpg").apply { parentFile.mkdirs(); writeText("trusted-key") }
            DistroSources.configure(root, id, MirrorSource.ALIYUN)
            assertFalse(File(entries, "overseas.sources").exists())
            assertFalse(File(entries, "overseas.list").exists())
            assertTrue(File(entries, "keep.disabled").exists())
            assertEquals("trusted-key", key.readText())
            val sources = File(root, "etc/apt/sources.list").readText()
            assertTrue(sources.lineSequence().filter { it.isNotBlank() }.all { it.startsWith("deb https://mirrors.aliyun.com/") })
            assertFalse(sources.contains("trusted=yes"))
        }
    }

    @Test fun configuringSourcesCannotDeleteThroughAnExternalDirectoryLink() = fixture { directory ->
        val root = File(directory, "root").apply { mkdirs() }
        val outside = File(directory, "outside").apply { mkdirs() }
        val sentinel = File(outside, "keep.sources").apply { writeText("unchanged") }
        File(root, "etc/apt").mkdirs()
        Files.createSymbolicLink(File(root, "etc/apt/sources.list.d").toPath(), outside.toPath())
        assertFailsWith<IllegalArgumentException> { DistroSources.configure(root, "kali", MirrorSource.TSINGHUA) }
        assertEquals("unchanged", sentinel.readText())
    }

    @Test fun configuringArchAndFedoraRetainsPackageSignatureEnforcement() = fixture { root ->
        val pacman = File(root, "etc/pacman.conf").apply {
            parentFile.mkdirs()
            writeText("[options]\nSigLevel = Required DatabaseOptional\n")
        }
        DistroSources.configure(root, "arch", MirrorSource.USTC)
        assertEquals("[options]\nSigLevel = Required DatabaseOptional\n", pacman.readText())
        assertEquals("Server = https://mirrors.ustc.edu.cn/archlinuxarm/\$arch/\$repo\n", File(root, "etc/pacman.d/mirrorlist").readText())
        DistroSources.configure(root, "fedora", MirrorSource.OFFICIAL)
        val fedora = File(root, "etc/yum.repos.d/wawlink.repo").readText()
        assertEquals(2, fedora.lineSequence().count { it == "gpgcheck=1" })
        assertTrue(fedora.contains("RPM-GPG-KEY-fedora-41-aarch64"))
        assertFalse(fedora.contains("gpgcheck=0"))
    }

    @Test fun archiveTransferStillChecksSha256() = fixture { directory ->
        val archive = File(directory, "source.tar.xz").apply { writeText("archive bytes") }
        val expected = MessageDigest.getInstance("SHA-256").digest(archive.readBytes()).joinToString("") { "%02x".format(it) }
        val target = File(directory, "target.tar.xz")
        DistroSources.transfer(archive.toURI().toString(), target, expected) { _, _ -> }
        assertEquals(archive.readText(), target.readText())
        assertFailsWith<IllegalArgumentException> { DistroSources.transfer(archive.toURI().toString(), target, hash) { _, _ -> } }
    }

    @Test fun configureRecordsPortableInstallationSourceInsideGuest() = fixture { root ->
        domestic.forEach { mirror ->
            DistroSources.configure(root, "alpine", mirror)
            assertEquals("domestic\n", File(root, "etc/wawlink-install-source").readText())
        }
        DistroSources.configure(root, "alpine", MirrorSource.OFFICIAL)
        assertEquals("official\n", File(root, "etc/wawlink-install-source").readText())
    }

    private fun fixture(block: (File) -> Unit) {
        val root = Files.createTempDirectory("wawlink-distro-sources").toFile()
        try { block(root) } finally { RootfsArchives.deleteTree(root) }
    }
}
