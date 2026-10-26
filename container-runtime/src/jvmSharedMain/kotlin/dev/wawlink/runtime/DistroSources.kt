package dev.wawlink.runtime

import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

internal data class DistroDownload(val url: String, val sha256: String, val phase: String)

internal object DistroSources {
    val catalog = listOf(
        DistroSpec("ubuntu", "Ubuntu", "24.04"), DistroSpec("debian", "Debian", "12"),
        DistroSpec("arch", "Arch Linux ARM", "Rolling"), DistroSpec("alpine", "Alpine", "3.21"),
        DistroSpec("fedora", "Fedora", "41"), DistroSpec("kali", "Kali Linux", "Rolling"),
    )
    // Published by the rootfs distributor at the immutable proot-distro v4.18.0 tag.
    // https://github.com/termux/proot-distro/tree/v4.18.0/distro-plugins
    private val published = mapOf(
        "ubuntu" to ("ubuntu-noble" to "81ac0fb0d16ded12ab11cede62f67b875ff56f9fa1aa9eb786415c3ec5c477d2"),
        "debian" to ("debian-bookworm" to "3a841a794ae5999b33e33b329582ed0379d4f54ca62c6ce5a8eb9cff5ef8900b"),
        "arch" to ("archlinux" to "dc56b998ffa2663209417396c8d70caf87c8052acf41e9a2c6daf24cbd181533"),
        "fedora" to ("fedora" to "5f51f3f2da790732fc6b720eefe5ec44841cf8edb21dfa342005257c4665bb8c"),
    )
    private val snapshotLink = Regex("href=[\"']([0-9]{8}_[0-9]{2}(?::|%3[Aa])[0-9]{2})/[\"']")
    private val encodedColon = Regex("%3[Aa]")
    private val checksumColumns = Regex("\\s+")
    private val sha256Pattern = Regex("[0-9a-fA-F]{64}")
    private val domesticHosts = setOf("mirrors.tuna.tsinghua.edu.cn", "mirrors.aliyun.com", "mirrors.ustc.edu.cn")

    fun resolve(spec: DistroSpec, mirror: MirrorSource): DistroDownload = resolve(spec, mirror, ::text)

    internal fun resolve(spec: DistroSpec, mirror: MirrorSource, metadata: (String) -> String): DistroDownload {
        require(spec in catalog) { "不支持的发行版版本或架构：${spec.id} ${spec.version} ${spec.arch}" }
        require(spec.id != "fedora" || mirror == MirrorSource.OFFICIAL) {
            "Fedora 41 缺少可校验的国内 ARM64 rootfs 归档；国内现有 OCI/虚拟机镜像不能直接导入。请选择官方源，不会自动切换源或升级版本"
        }
        if (mirror != MirrorSource.OFFICIAL && spec.id == "ubuntu") {
            val base = "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release"
            val filename = "ubuntu-base-24.04.5-base-arm64.tar.gz"
            val pinned = "a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2"
            require(checksum(metadata("$base/SHA256SUMS"), filename) == pinned) { "清华 Ubuntu Base SHA256 与固定校验值不符" }
            return DistroDownload("$base/$filename", pinned, "下载清华 Ubuntu Base 24.04.5 rootfs（固定 SHA256，清华元数据）；${mirror.label}用于软件源")
        }
        if (mirror != MirrorSource.OFFICIAL && (spec.id == "debian" || spec.id == "arch" || spec.id == "kali")) {
            // Only the rootfs member is importable; disk.qcow2, metadata and OCI bundles are not root filesystems.
            val branch = when (spec.id) {
                "debian" -> "debian/bookworm"
                "arch" -> "archlinux/current"
                else -> "kali/current"
            }
            val base = "https://mirrors.tuna.tsinghua.edu.cn/lxc-images/images/$branch/arm64/default"
            val snapshot = snapshotLink.findAll(metadata("$base/"))
                .map { it.groupValues[1].replace(encodedColon, ":") }
                .maxOrNull() ?: error("清华 ${spec.name} 元数据缺少 ARM64 rootfs 快照")
            val directory = "$base/${snapshot.replace(":", "%3A")}"
            val filename = "rootfs.tar.xz"
            return DistroDownload("$directory/$filename", checksum(metadata("$directory/SHA256SUMS"), filename),
                "下载清华 ${spec.name} rootfs（快照 $snapshot，清华 SHA256）；${mirror.label}用于软件源")
        }
        published[spec.id]?.let { (prefix, checksum) ->
            return DistroDownload(
                "https://github.com/termux/proot-distro/releases/download/v4.17.3/$prefix-aarch64-pd-v4.17.3.tar.xz",
                checksum, "下载官方渠道 proot-distro 基础镜像（发行者固定 SHA256）；官方软件源",
            )
        }
        if (spec.id == "alpine") {
            val base = when (mirror) {
                MirrorSource.TSINGHUA -> "https://mirrors.tuna.tsinghua.edu.cn/alpine"
                MirrorSource.ALIYUN -> "https://mirrors.aliyun.com/alpine"
                MirrorSource.USTC -> "https://mirrors.ustc.edu.cn/alpine"
                MirrorSource.OFFICIAL -> "https://dl-cdn.alpinelinux.org/alpine"
            }
            val directory = "$base/v3.21/releases/aarch64"
            val filename = "alpine-minirootfs-3.21.9-aarch64.tar.gz"
            val pinned = "a389940ee42579ead202fb91607fb25698a58a3a5b55c15b4603e5d39cfa4492"
            require(checksum(metadata("$directory/$filename.sha256"), filename) == pinned) { "${mirror.label} Alpine minirootfs SHA256 与固定校验值不符" }
            return DistroDownload("$directory/$filename", pinned, "下载${mirror.label} Alpine 3.21.9 minirootfs（固定 SHA256，同源元数据）")
        }
        val base = "https://kali.download/nethunter-images/current/rootfs"
        val filename = "kali-nethunter-rootfs-minimal-arm64.tar.xz"
        return DistroDownload("$base/$filename", checksum(metadata("$base/SHA256SUMS"), filename), "下载 Kali 官方基础镜像（官方 SHA256）")
    }

    private fun checksum(metadata: String, filename: String): String {
        val records = metadata.lineSequence().map { it.trim().split(checksumColumns) }
            .filter { it.size == 2 && it[1].removePrefix("*") == filename }.toList()
        require(records.size == 1 && sha256Pattern.matches(records.single()[0])) {
            "SHA256 元数据缺少唯一有效的 $filename 记录"
        }
        return records.single()[0].lowercase()
    }

    internal fun redirectTarget(current: URI, location: String, domesticHost: String?): URI {
        val next = current.resolve(location)
        require((next.scheme == "http" || next.scheme == "https") && !(current.scheme == "https" && next.scheme != "https")) { "拒绝不安全的下载重定向" }
        require(domesticHost == null || next.host == domesticHost) { "国内镜像禁止跨站重定向：${next.host}" }
        return next
    }

    private fun connect(url: String): HttpURLConnection {
        var current = URI(url)
        // Keep domestic metadata and archives on their selected provider, including every redirect hop.
        val domesticHost = current.host?.takeIf { it in domesticHosts }
        require(current.scheme == "http" || current.scheme == "https") { "只支持 HTTP/HTTPS 下载地址" }
        repeat(8) {
            val connection = current.toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "WawLink/1.0")
            connection.setRequestProperty("Accept-Encoding", "identity")
            val code = connection.responseCode
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                current = redirectTarget(current, location ?: error("下载重定向缺少目标地址"), domesticHost)
            } else {
                if (code !in 200..299) { connection.disconnect(); error("下载失败：HTTP $code（${current.host}）") }
                return connection
            }
        }
        error("下载重定向次数过多")
    }

    private fun text(url: String): String {
        val connection = connect(url)
        return try {
            val bytes = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 4 * 1024 * 1024) { "镜像校验元数据过大" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            bytes.toString(Charsets.UTF_8)
        } finally { connection.disconnect() }
    }

    fun transfer(source: String, target: File, expectedSha256: String?, progress: (Long, Long) -> Unit) {
        val expected = expectedSha256?.trim()?.lowercase()
        require(expected == null || sha256Pattern.matches(expected)) { "SHA256 应为 64 位十六进制字符串" }
        var connection: HttpURLConnection? = null
        val local = if (source.startsWith("file:")) File(URI(source)) else File(source)
        val remote = source.startsWith("http://") || source.startsWith("https://")
        val input = if (remote) { connection = connect(source); connection.inputStream } else {
            require(local.isFile) { "找不到 rootfs 归档：$source" }; local.inputStream()
        }
        val total = if (remote) connection!!.contentLengthLong else local.length()
        val digest = expected?.let { MessageDigest.getInstance("SHA-256") }
        try {
            input.use { stream -> target.outputStream().use { output ->
                val buffer = ByteArray(128 * 1024)
                var written = 0L
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    digest?.update(buffer, 0, count)
                    written += count
                    progress(written, total)
                }
                require(total < 0 || written == total) { "归档下载不完整：$written / $total 字节" }
            } }
            if (digest != null) {
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                require(actual == expected) { "SHA256 校验失败：预期 $expected，实际 $actual" }
            }
        } finally { connection?.disconnect() }
    }

    fun configure(root: File, distro: String, mirror: MirrorSource) {
        writeGuest(root, "etc/resolv.conf", "nameserver 223.5.5.5\nnameserver 1.1.1.1\n")
        writeGuest(root, "etc/wawlink-install-source", if (mirror == MirrorSource.OFFICIAL) "official\n" else "domestic\n")
        val domain = when (mirror) {
            MirrorSource.TSINGHUA -> "mirrors.tuna.tsinghua.edu.cn"
            MirrorSource.ALIYUN -> "mirrors.aliyun.com"
            MirrorSource.USTC -> "mirrors.ustc.edu.cn"
            MirrorSource.OFFICIAL -> ""
        }
        when (distro) {
            "ubuntu" -> {
                val base = if (domain.isEmpty()) "https://ports.ubuntu.com/ubuntu-ports" else "https://$domain/ubuntu-ports"
                // Disable both deb822 and legacy entries to prevent a second, overseas source surviving import.
                clearGuestSources(root, "etc/apt/sources.list.d", setOf("sources", "list"))
                writeGuest(root, "etc/apt/sources.list", "deb $base noble main universe restricted multiverse\ndeb $base noble-updates main universe restricted multiverse\ndeb $base noble-security main universe restricted multiverse\n")
            }
            "debian" -> {
                val base = if (domain.isEmpty()) "https://deb.debian.org/debian" else "https://$domain/debian"
                val security = if (domain.isEmpty()) "https://security.debian.org/debian-security" else "https://$domain/debian-security"
                clearGuestSources(root, "etc/apt/sources.list.d", setOf("sources", "list"))
                writeGuest(root, "etc/apt/sources.list", "deb $base bookworm main contrib non-free non-free-firmware\ndeb $base bookworm-updates main contrib non-free non-free-firmware\ndeb $security bookworm-security main contrib non-free non-free-firmware\n")
            }
            "kali" -> {
                clearGuestSources(root, "etc/apt/sources.list.d", setOf("sources", "list"))
                writeGuest(root, "etc/apt/sources.list", "deb ${if (domain.isEmpty()) "https://http.kali.org/kali" else "https://$domain/kali"} kali-rolling main contrib non-free non-free-firmware\n")
            }
            "alpine" -> {
                val base = if (domain.isEmpty()) "https://dl-cdn.alpinelinux.org/alpine" else "https://$domain/alpine"
                writeGuest(root, "etc/apk/repositories", "$base/v3.21/main\n$base/v3.21/community\n")
            }
            "arch" -> {
                val base = if (domain.isEmpty()) "https://mirror.archlinuxarm.org" else "https://$domain/archlinuxarm"
                writeGuest(root, "etc/pacman.d/mirrorlist", "Server = $base/\$arch/\$repo\n")
            }
            "fedora" -> {
                // Fedora 41 is EOL; live mirrorlists no longer carry it. Use the actual archive.
                val base = if (domain.isEmpty()) "https://archives.fedoraproject.org/pub/archive/fedora/linux" else "https://$domain/fedora-archive"
                clearGuestSources(root, "etc/yum.repos.d", setOf("repo"))
                writeGuest(root, "etc/yum.repos.d/wawlink.repo", "[fedora]\nname=Fedora 41 archive\nbaseurl=$base/releases/41/Everything/aarch64/os/\nenabled=1\ngpgcheck=1\ngpgkey=file:///etc/pki/rpm-gpg/RPM-GPG-KEY-fedora-41-aarch64\n[updates]\nname=Fedora 41 updates archive\nbaseurl=$base/updates/41/Everything/aarch64/\nenabled=1\ngpgcheck=1\ngpgkey=file:///etc/pki/rpm-gpg/RPM-GPG-KEY-fedora-41-aarch64\n")
            }
        }
        File(root, "workspace").mkdirs()
        File(root, "root").mkdirs()
    }

    fun writeGuest(root: File, name: String, value: String) {
        val path = File(root, name)
        require(path.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())) { "rootfs 配置路径越界：$name" }
        path.parentFile.mkdirs()
        // resolv.conf often points to an absent /run resolver; replace the link, not its target.
        java.nio.file.Files.deleteIfExists(path.toPath())
        path.writeText(value)
    }

    private fun clearGuestSources(root: File, name: String, extensions: Set<String>) {
        val directory = File(root, name)
        require(directory.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())) { "软件源路径越界：$name" }
        directory.listFiles().orEmpty().filter { it.extension in extensions }.forEach { file ->
            java.nio.file.Files.deleteIfExists(file.toPath())
        }
    }
}
