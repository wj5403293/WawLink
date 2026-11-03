package dev.wawlink.runtime

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.Base64

/** Offline host management: private guest data never needs App/world write permission. */
internal class RootfsHostFiles(
    private val config: ContainerConfig,
    private val rootCommand: (String) -> String = ::runHostRootCommand,
) {
    fun read(file: File, maxBytes: Int = MAX_BYTES, replaceSymlink: Boolean = false): ByteArray? {
        require(maxBytes in 1..MAX_BYTES)
        val (root, target) = path(file)
        if (RootfsAccess.requiresRoot(config)) {
            val output = rootCommand(script(root, target, create = false) + "\n" + """
                if [ "${'$'}missing" = 1 ]; then printf 'MISSING\n'; exit 0; fi
                entry=${shellQuote("./" + target.fileName)}
                ${verifyEntry(replaceSymlink)}
                if [ ! -e "${'$'}entry" ] || [ -L "${'$'}entry" ]; then printf 'MISSING\n'; exit 0; fi
                identity=${'$'}(stat -c '%d:%i:%h:%s:%y:%z' "${'$'}entry")
                [ "${'$'}(stat -c '%s' "${'$'}entry")" -le $maxBytes ] || fail '容器管理文件过大'
                ${temporaryDirectory()}
                (
                    trap - EXIT
                    cd -P "${'$'}temporary"
                    [ "${'$'}(stat -c '%d:%i' .)" = "${'$'}temporary_id" ] || fail '离线读取目录已改变'
                    # cp -P snapshots the link itself if the guest swaps a checked file to a link.
                    # Never hash or print bytes through the guest-controlled final pathname.
                    cp -P "../${'$'}{entry#./}" ./previous
                    [ ! -L ./previous ] && [ -f ./previous ] && [ "${'$'}(stat -c '%h' ./previous)" = 1 ] || fail '读取期间容器文件变为链接'
                    [ "${'$'}(stat -c '%s' ./previous)" -le $maxBytes ] || fail '容器管理文件过大'
                    printf 'DATA\n'
                    base64 ./previous
                )
                ${verifyEntry(false)}
                [ "${'$'}(stat -c '%d:%i:%h:%s:%y:%z' "${'$'}entry")" = "${'$'}identity" ] || fail '读取期间容器文件已改变'
                check_parent
            """.trimIndent())
            if (output.trim() == "MISSING") return null
            require(output.startsWith("DATA\n")) { "Root 离线读取返回无效结果" }
            val bytes = Base64.getMimeDecoder().decode(output.substringAfter('\n'))
            require(bytes.size <= maxBytes) { "容器管理文件过大" }
            return bytes
        }
        checkPath(root, target, replaceSymlink)
        if (Files.isSymbolicLink(target) && replaceSymlink) return null
        return try {
            Files.newByteChannel(target, StandardOpenOption.READ, NOFOLLOW_LINKS).use { channel ->
                require(channel.size() <= maxBytes) { "容器管理文件过大" }
                val buffer = ByteBuffer.allocate(channel.size().toInt())
                while (buffer.hasRemaining() && channel.read(buffer) >= 0) Unit
                require(!buffer.hasRemaining() && channel.read(ByteBuffer.allocate(1)) == -1) { "读取期间容器文件已改变" }
                checkPath(root, target, replaceSymlink)
                buffer.array()
            }
        } catch (_: NoSuchFileException) { null }
    }

    /** expectedRevision is SHA-256 of the exact old bytes; null means an absent document. */
    fun write(
        file: File, bytes: ByteArray, expectedRevision: String?, defaultMode: Int = 0x180,
        preserveOwner: Boolean = false, replaceSymlink: Boolean = false,
    ) {
        val (root, target) = path(file)
        withWriteLock(root) {
            writeLocked(root, target, file, bytes, expectedRevision, defaultMode, preserveOwner, replaceSymlink)
        }
    }

    private fun writeLocked(
        root: Path, target: Path, file: File, bytes: ByteArray, expectedRevision: String?, defaultMode: Int,
        preserveOwner: Boolean, replaceSymlink: Boolean,
    ) {
        require(bytes.size <= MAX_BYTES) { "容器管理文件过大" }
        require(expectedRevision == null || expectedRevision.matches(Regex("[0-9a-f]{64}")))
        var committedOwner: LogicalOwner? = null
        if (RootfsAccess.requiresRoot(config)) {
            val owner = owner(root, target, defaultMode, false)
            // A private staging directory works on Android and on ordinary JVM hosts, without
            // putting credentials into argv, a public temporary file, or an inherited descriptor.
            val staging = Files.createTempDirectory("wawlink-host-write-",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            var writeFailure: Throwable? = null
            try {
                val payload = staging.resolve("payload")
                Files.createFile(payload, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                FileOutputStream(payload.toFile()).use { it.write(bytes); it.fd.sync() }
                val identity = Files.readAttributes(payload, "unix:dev,ino,nlink", NOFOLLOW_LINKS)
                val payloadIdentity = "${identity["dev"]}:${identity["ino"]}:1"
                val output = rootCommand(script(root, target, create = true) + "\n" + """
                    entry=${shellQuote("./" + target.fileName)}
                    ${verifyEntry(replaceSymlink)}
                    entry_state() {
                        ${verifyEntry(replaceSymlink)}
                        if [ -L "${'$'}entry" ] || [ -e "${'$'}entry" ]; then
                            stat -c '%d:%i:%h:%s:%y:%z:%u:%g:%f' "${'$'}entry"
                        else printf absent; fi
                    }
                    original_state=${'$'}(entry_state)
                    ${temporaryDirectory()}
                    revision() (
                        trap - EXIT
                        ${verifyEntry(replaceSymlink)}
                        if [ -L "${'$'}entry" ] || [ ! -e "${'$'}entry" ]; then printf absent; exit 0; fi
                        before=${'$'}(stat -c '%d:%i:%h:%s:%y:%z' "${'$'}entry")
                        cd -P "${'$'}temporary"
                        [ "${'$'}(stat -c '%d:%i' .)" = "${'$'}temporary_id" ] || fail '离线暂存目录已改变'
                        rm -f ./previous
                        cp -P "../${'$'}{entry#./}" ./previous
                        [ ! -L ./previous ] && [ -f ./previous ] && [ "${'$'}(stat -c '%h' ./previous)" = 1 ] || fail '容器文件在提交前变为链接'
                        [ "${'$'}(stat -c '%d:%i:%h:%s:%y:%z' "../${'$'}{entry#./}")" = "${'$'}before" ] || fail '容器文件在提交前已改变'
                        value=${'$'}(sha256sum ./previous); printf '%s' "${'$'}{value%% *}"
                    )
                    expected=${shellQuote(expectedRevision ?: "absent")}
                    [ "${'$'}(revision)" = "${'$'}expected" ] || fail '$CONFLICT'
                    mode=${owner.mode.toString(8)}; uid=${owner.uid}; gid=${owner.gid}
                    if [ -e "${'$'}entry" ] && [ ! -L "${'$'}entry" ]; then
                        mode=${'$'}(stat -c '%a' "${'$'}entry")
                        ${if (preserveOwner) "uid=\$(stat -c '%u' \"\$entry\"); gid=\$(stat -c '%g' \"\$entry\")" else ""}
                    fi
                    payload=${shellQuote(payload.toString())}
                    [ ! -L "${'$'}payload" ] && [ "${'$'}(stat -c '%d:%i:%h' "${'$'}payload")" = ${shellQuote(payloadIdentity)} ] || fail '离线暂存文件已改变'
                    (
                        trap - EXIT
                        cd -P "${'$'}temporary"
                        [ "${'$'}(stat -c '%d:%i' .)" = "${'$'}temporary_id" ] || fail '离线暂存目录已改变'
                        umask 077
                        cat "${'$'}payload" > ./content
                        content_revision=${'$'}(sha256sum ./content)
                        [ "${'$'}{content_revision%% *}" = ${shellQuote(revision(bytes))} ] || fail '离线暂存内容已改变'
                        [ ! -L ./content ] && [ "${'$'}(stat -c '%h' ./content)" = 1 ] || fail '离线暂存文件不是独立普通文件'
                        chown -h "${'$'}uid:${'$'}gid" ./content
                        chmod "${'$'}mode" ./content
                    )
                    check_parent
                    [ ! -L "${'$'}temporary" ] && [ "${'$'}(stat -c '%d:%i' "${'$'}temporary")" = "${'$'}temporary_id" ] || fail '离线暂存目录已改变'
                    ${verifyEntry(replaceSymlink)}
                    [ "${'$'}(revision)" = "${'$'}expected" ] || fail '$CONFLICT'
                    [ "${'$'}(entry_state)" = "${'$'}original_state" ] || fail '$CONFLICT'
                    # Same pinned filesystem, a single rename; never fall back to copying a document.
                    mv -fT "${'$'}temporary/content" "${'$'}entry"
                    ${verifyEntry(false)}
                    check_parent
                    printf 'OWNER:%s:%s:%s\n' "${'$'}uid" "${'$'}gid" "${'$'}mode"
                """.trimIndent())
                committedOwner = parseOwner(output)
            } catch (failure: Throwable) {
                writeFailure = failure
                throw failure
            } finally {
                cleanupStaging(staging, writeFailure)
            }
        } else {
            prepareMappedHostDirectory(config, target.parent.toFile())
            checkPath(root, target, replaceSymlink)
            check(revisionOrNull(read(file, replaceSymlink = replaceSymlink)) == expectedRevision) { CONFLICT }
            val mode = if (Files.exists(target, NOFOLLOW_LINKS) && !Files.isSymbolicLink(target))
                (Files.getAttribute(target, "unix:mode", NOFOLLOW_LINKS) as Number).toInt() and 0xFFF else defaultMode
            val temporary = Files.createTempFile(target.parent, ".wawlink-write-", ".tmp")
            try {
                FileOutputStream(temporary.toFile()).use { it.write(bytes); it.fd.sync() }
                Files.setAttribute(temporary, "unix:mode", mode, NOFOLLOW_LINKS)
                checkPath(root, target, replaceSymlink)
                check(revisionOrNull(read(file, replaceSymlink = replaceSymlink)) == expectedRevision) { CONFLICT }
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally { Files.deleteIfExists(temporary) }
        }
        if (committedOwner != null) RootfsOwnership.recordCommittedHostWrite(config, root, target, committedOwner)
        else RootfsOwnership.recordHostWrite(config, target.toFile())
    }

    private fun cleanupStaging(staging: Path, failure: Throwable?) {
        var cleanupFailure: Throwable? = null
        try { Files.deleteIfExists(staging.resolve("payload")) }
        catch (cleanup: Throwable) { cleanupFailure = cleanup }
        try { Files.deleteIfExists(staging) }
        catch (cleanup: Throwable) {
            if (cleanupFailure == null) cleanupFailure = cleanup else cleanupFailure.addSuppressed(cleanup)
        }
        cleanupFailure?.let { cleanup ->
            if (failure == null) throw cleanup
            failure.addSuppressed(cleanup)
        }
    }

    fun prepareDirectory(directory: File) {
        val (root, target) = path(directory)
        withWriteLock(root) { prepareDirectoryLocked(root, target) }
    }

    private fun prepareDirectoryLocked(root: Path, target: Path) {
        val owner = owner(root, target, 0x1C0, true)
        val output = rootCommand(script(root, target.resolve(".wawlink-directory-probe"), create = true) + "\n" + """
            check_parent
            mode=${'$'}(stat -c '%a' .)
            chown -h '${owner.uid}:${owner.gid}' .
            chmod "${'$'}mode" .
            check_parent
            printf 'OWNER:%s:%s:%s\n' '${owner.uid}' '${owner.gid}' "${'$'}mode"
        """.trimIndent())
        RootfsOwnership.recordCommittedHostWrite(config, root, target, parseOwner(output))
    }

    /** Finalize an already fully written independent file, not a pre-write permission grant. */
    fun prepareFile(file: File) {
        val (root, target) = path(file)
        withWriteLock(root) { prepareFileLocked(root, target) }
    }

    private fun prepareFileLocked(root: Path, target: Path) {
        val owner = owner(root, target, 0x180, false)
        val output = rootCommand(script(root, target, create = false) + "\n" + """
            [ "${'$'}missing" = 0 ] || fail '容器管理文件不存在'
            entry=${shellQuote("./" + target.fileName)}
            ${verifyEntry(false)}
            [ -f "${'$'}entry" ] || fail '容器管理文件不存在'
            identity=${'$'}(stat -c '%d:%i:%h' "${'$'}entry")
            mode=${'$'}(stat -c '%a' "${'$'}entry")
            chown -h '${owner.uid}:${owner.gid}' "${'$'}entry"
            ${verifyEntry(false)}
            [ "${'$'}(stat -c '%d:%i:%h' "${'$'}entry")" = "${'$'}identity" ] || fail '容器管理文件已改变'
            chmod "${'$'}mode" "${'$'}entry"
            ${verifyEntry(false)}
            [ "${'$'}(stat -c '%d:%i:%h' "${'$'}entry")" = "${'$'}identity" ] || fail '容器管理文件已改变'
            check_parent
            printf 'OWNER:%s:%s:%s\n' '${owner.uid}' '${owner.gid}' "${'$'}mode"
        """.trimIndent())
        RootfsOwnership.recordCommittedHostWrite(config, root, target, parseOwner(output))
    }

    private fun parseOwner(output: String): LogicalOwner {
        val lines = output.trim().lines()
        val fields = lines.last().split(':')
        require(fields.size == 4 && fields[0] == "OWNER") { "Root 离线提交返回无效结果" }
        val owner = LogicalOwner(fields[1].toLong(), fields[2].toLong(), fields[3].toInt(8) and 0xFFF)
        for (line in lines.dropLast(1)) {
            val directory = line.split(':')
            require(directory.size == 5 && directory[0] == "DIRECTORY") { "Root 离线目录提交返回无效结果" }
            val file = File(Base64.getDecoder().decode(directory[1]).toString(Charsets.UTF_8))
            val (root, target) = path(file) // Keep lexical bounds after the privileged directory walk.
            RootfsOwnership.recordCommittedHostWrite(config, root, target,
                LogicalOwner(directory[2].toLong(), directory[3].toLong(), directory[4].toInt(8) and 0xFFF))
        }
        return owner
    }

    private fun temporaryDirectory(): String = """
        umask 077
        temporary=${'$'}(mktemp -d ./.wawlink-write.XXXXXXXX)
        temporary_id=${'$'}(stat -c '%d:%i' "${'$'}temporary")
        cleanup() {
            [ ! -L "${'$'}temporary" ] && [ "${'$'}(stat -c '%d:%i' "${'$'}temporary")" = "${'$'}temporary_id" ] || return 1
            (cd -P "${'$'}temporary" && [ "${'$'}(stat -c '%d:%i' .)" = "${'$'}temporary_id" ] && rm -f ./content ./previous) || return 1
            [ ! -L "${'$'}temporary" ] && [ "${'$'}(stat -c '%d:%i' "${'$'}temporary")" = "${'$'}temporary_id" ] || return 1
            rmdir "${'$'}temporary"
        }
        trap 'status=${'$'}?; trap - EXIT; if cleanup; then exit "${'$'}status"; else [ "${'$'}status" = 0 ] || exit "${'$'}status"; exit 1; fi' EXIT
    """.trimIndent()

    private fun owner(root: Path, path: Path, mode: Int, directory: Boolean): LogicalOwner =
        RootfsOwnership.managedOwner(config, "/" + root.relativize(path).joinToString("/"), mode, directory, rootCommand)

    private fun path(file: File): Pair<Path, Path> {
        val root = RootfsAccess.canonicalRoot(config, rootCommand).toPath()
        val lexical = File(config.rootfsPath).absoluteFile.normalize().toPath()
        val requested = file.absoluteFile.normalize().toPath()
        val target = if (requested.startsWith(lexical)) root.resolve(lexical.relativize(requested)) else requested
        require(target.startsWith(root) && target != root) { "容器管理路径越出 Rootfs" }
        val excluded = listOf("/dev", "/proc", "/sys", "/host-rootfs") + config.mounts.map { it.target }
        for (mount in excluded) {
            require(mount.startsWith('/') && '\u0000' !in mount && mount.split('/').none { it == "." || it == ".." }) { "挂载目标无效" }
            require(!target.startsWith(root.resolve(mount.trimStart('/')))) { "容器管理路径不能位于挂载内" }
        }
        return root to target
    }

    private fun checkPath(root: Path, target: Path, replaceSymlink: Boolean) {
        val mounts = File("/proc/self/mountinfo").readLines().map { line ->
            val fields = line.split(' ')
            require(fields.size > 6) { "无法解析内核挂载信息" }
            Path.of(Regex("""\\([0-7]{3})""").replace(fields[4]) { it.groupValues[1].toInt(8).toChar().toString() })
        }
        require(mounts.none { it.startsWith(root) && target.startsWith(it) }) { "容器管理路径位于内核挂载内" }
        var current = root
        for (part in root.relativize(target)) {
            current = current.resolve(part)
            val attributes = try { Files.readAttributes(current, "unix:mode,nlink", NOFOLLOW_LINKS) }
                catch (_: NoSuchFileException) { continue }
            if (current == target && replaceSymlink && Files.isSymbolicLink(current)) continue
            require(!Files.isSymbolicLink(current)) { "容器管理路径不能包含符号链接：$current" }
            if (current != target) require(Files.isDirectory(current, NOFOLLOW_LINKS)) { "容器管理父路径不是目录" }
            else {
                require(Files.isRegularFile(current, NOFOLLOW_LINKS)) { "容器管理文件不是普通文件" }
                require((attributes.getValue("nlink") as Number).toLong() == 1L) { "容器管理文件不能是硬链接" }
            }
        }
    }

    private fun script(root: Path, target: Path, create: Boolean): String {
        val steps = root.relativize(target.parent).map { it.toString() }
        val home = if (create) root.resolve(RootfsOwnership.managedHome(config, rootCommand).trimStart('/')).normalize() else null
        var current = root
        val walk = steps.joinToString("\n") { part ->
            current = current.resolve(part)
            // New system ancestors (e.g. /home or /srv) must remain traversable by the
            // configured account. Only the actual managed HOME subtree starts private.
            val mode = if (home != null && current.startsWith(home)) 0x1C0 else 0x1ED
            val owner = if (create) owner(root, current, mode, true) else LogicalOwner(0, 0, mode)
            "enter ${shellQuote(part)} ${owner.uid} ${owner.gid} ${owner.mode.toString(8)}"
        }
        return """
            set -eu
            [ "${'$'}(id -u)" = 0 ] || { echo '离线 chroot 文件管理需要真实 Root' >&2; exit 1; }
            fail() { echo "${'$'}*" >&2; exit 1; }
            root=${shellQuote(root.toString())}
            target=${shellQuote(target.toString())}
            parent=${shellQuote(target.parent.toString())}
            # Check actual kernel mounts as well as configured (possibly not yet mounted) bindings.
            check_mounts() {
                while IFS=' ' read -r mid pid device mount_root mount_path rest; do
                    mount_path=${'$'}(printf '%b' "${'$'}mount_path")
                    case "${'$'}mount_path/" in "${'$'}root/"*)
                        case "${'$'}target/" in "${'$'}mount_path/"*) fail '容器管理路径位于内核挂载内';; esac;;
                    esac
                done < /proc/self/mountinfo
            }
            check_mounts
            [ ! -L "${'$'}root" ] && [ "${'$'}(readlink -f "${'$'}root")" = "${'$'}root" ] || fail 'Rootfs 路径已改变'
            root_id=${'$'}(stat -c '%d:%i' "${'$'}root")
            cd -P "${'$'}root"
            [ "${'$'}(stat -c '%d:%i' .)" = "${'$'}root_id" ] || fail 'Rootfs 身份已改变'
            missing=0
            walk_path=${'$'}root
            enter() {
                [ "${'$'}missing" = 0 ] || return 0
                part="./${'$'}1"; created=0
                walk_path="${'$'}walk_path/${'$'}1"
                # Enumerating the pinned parent propagates EACCES/SELinux errors, rather than
                # interpreting an inaccessible child as an absent offline document.
                find . -maxdepth 1 -print > /dev/null
                [ ! -L "${'$'}part" ] || fail '容器管理路径不能包含符号链接'
                if [ ! -e "${'$'}part" ]; then
                    ${if (create) "umask 077; if mkdir_error=\$(mkdir \"\$part\" 2>&1); then created=1; elif [ ! -L \"\$part\" ] && [ -d \"\$part\" ]; then :; else fail \"\$mkdir_error\"; fi" else "missing=1; return 0"}
                fi
                [ -d "${'$'}part" ] && [ ! -L "${'$'}part" ] || fail '容器管理父路径不是普通目录'
                directory_id=${'$'}(stat -c '%d:%i' "${'$'}part")
                cd -P "${'$'}part"
                [ "${'$'}(stat -c '%d:%i' .)" = "${'$'}directory_id" ] || fail '容器管理父目录已改变'
                [ "${'$'}(pwd -P)" = "${'$'}walk_path" ] && [ "${'$'}(readlink -f "${'$'}walk_path")" = "${'$'}walk_path" ] || fail '容器管理目录路径已改变'
                if [ "${'$'}created" = 1 ]; then
                    chown -h "${'$'}2:${'$'}3" .; chmod "${'$'}4" .
                    encoded=${'$'}(printf '%s' "${'$'}walk_path" | base64 | tr -d '\n')
                    printf 'DIRECTORY:%s:%s:%s:%s\n' "${'$'}encoded" "${'$'}2" "${'$'}3" "${'$'}4"
                fi
            }
            $walk
            parent_id=${'$'}(stat -c '%d:%i' .)
            check_parent() {
                [ "${'$'}missing" = 0 ] || fail '容器管理父目录不存在'
                check_mounts
                [ ! -L "${'$'}root" ] && [ "${'$'}(stat -c '%d:%i' "${'$'}root")" = "${'$'}root_id" ] || fail 'Rootfs 身份已改变'
                [ "${'$'}(pwd -P)" = "${'$'}parent" ] && [ ! -L "${'$'}parent" ] &&
                    [ "${'$'}(readlink -f "${'$'}parent")" = "${'$'}parent" ] &&
                    [ "${'$'}(stat -c '%d:%i' "${'$'}parent")" = "${'$'}parent_id" ] &&
                    [ "${'$'}(stat -c '%d:%i' .)" = "${'$'}parent_id" ] || fail '容器管理父目录身份已改变'
            }
            if [ "${'$'}missing" = 0 ]; then check_parent; find . -maxdepth 1 -print > /dev/null; fi
        """.trimIndent()
    }

    private fun verifyEntry(replaceSymlink: Boolean): String = """
        if [ -L "${'$'}entry" ]; then ${if (replaceSymlink) ":" else "fail '容器管理文件不能是符号链接'"};
        elif [ -e "${'$'}entry" ]; then
            [ -f "${'$'}entry" ] && [ "${'$'}(stat -c '%h' "${'$'}entry")" = 1 ] || fail '容器管理文件不是独立普通文件';
        fi
    """.trimIndent()

    companion object {
        private class WriteLock(var users: Int = 0)
        private val writeLocks = mutableMapOf<Path, WriteLock>()

        private inline fun <T> withWriteLock(root: Path, action: () -> T): T {
            val lock = synchronized(writeLocks) {
                writeLocks.getOrPut(root) { WriteLock() }.also { it.users++ }
            }
            try {
                return synchronized(lock) { action() }
            } finally {
                synchronized(writeLocks) {
                    if (--lock.users == 0) writeLocks.remove(root)
                }
            }
        }

        const val MAX_BYTES = 1024 * 1024
        const val CONFLICT = "容器信息已被其他操作修改，请重新加载后再保存"
        fun revision(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        fun revisionOrNull(bytes: ByteArray?): String? = bytes?.let(::revision)
    }
}

internal fun runHostRootCommand(command: String): String = HostCommands.run(listOf("su", "-c", command))
