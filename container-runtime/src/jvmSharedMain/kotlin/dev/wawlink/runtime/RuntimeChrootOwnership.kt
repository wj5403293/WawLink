package dev.wawlink.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/** One common group, not world access: the App keeps offline access and su initializes this group. */
internal object ChrootHostIdentity {
    private val status by lazy { File("/proc/self/status").readLines() }
    val uid: Int by lazy { effective("Uid:") }
    val gid: Int by lazy { effective("Gid:") }
    private fun effective(field: String): Int = status.first { it.startsWith(field) }
        .substringAfter(':').trim().split(Regex("\\s+"))[1].toInt()
}

/** Host-created shared directories must also work when created after chroot prepare (e.g. send). */
fun prepareContainerHostDirectory(config: ContainerConfig, directory: File) {
    if (config.engine != ContainerEngineType.CHROOT) return
    val (root, target) = chrootHostPath(config, directory)
    var current = root
    for (part in root.relativize(target)) {
        current = current.resolve(part)
        val created = !Files.exists(current, NOFOLLOW_LINKS)
        if (created) Files.createDirectory(current)
        require(Files.isDirectory(current, NOFOLLOW_LINKS) && !Files.isSymbolicLink(current)) { "chroot 共享目录不能是链接或特殊文件：$current" }
        if (created || current == target) {
            val uid = (Files.getAttribute(current, "unix:uid", NOFOLLOW_LINKS) as Number).toInt()
            if (uid == ChrootHostIdentity.uid) {
                Files.setAttribute(current, "unix:gid", ChrootHostIdentity.gid, NOFOLLOW_LINKS)
                Files.setAttribute(current, "unix:mode", 0x5F8, NOFOLLOW_LINKS) // 02770
            }
            val gid = (Files.getAttribute(current, "unix:gid", NOFOLLOW_LINKS) as Number).toInt()
            val mode = (Files.getAttribute(current, "unix:mode", NOFOLLOW_LINKS) as Number).toInt()
            check(gid == ChrootHostIdentity.gid && mode and 0x5F8 == 0x5F8) { "chroot 共享目录尚未准备，请停止后重新启动容器：$current" }
        }
    }
}

/** Call on an exclusive temporary file before writing data, not on an unchecked destination. */
fun prepareContainerHostFile(config: ContainerConfig, file: File) {
    if (config.engine != ContainerEngineType.CHROOT) return
    val (_, target) = chrootHostPath(config, file)
    require(Files.isRegularFile(target, NOFOLLOW_LINKS) && !Files.isSymbolicLink(target)) { "chroot 共享文件不是普通文件：$target" }
    require((Files.getAttribute(target, "unix:nlink", NOFOLLOW_LINKS) as Number).toLong() == 1L) { "chroot 共享文件不能是硬链接：$target" }
    val uid = (Files.getAttribute(target, "unix:uid", NOFOLLOW_LINKS) as Number).toInt()
    val mode = (Files.getAttribute(target, "unix:mode", NOFOLLOW_LINKS) as Number).toInt()
    if (uid == ChrootHostIdentity.uid) {
        Files.setAttribute(target, "unix:gid", ChrootHostIdentity.gid, NOFOLLOW_LINKS)
        Files.setAttribute(target, "unix:mode", 0x1B0 or (mode and 0x49), NOFOLLOW_LINKS) // 0660 + executable bits
    }
    check((Files.getAttribute(target, "unix:gid", NOFOLLOW_LINKS) as Number).toInt() == ChrootHostIdentity.gid &&
        (Files.getAttribute(target, "unix:mode", NOFOLLOW_LINKS) as Number).toInt() and 0x1B0 == 0x1B0) { "chroot 共享文件权限无效：$target" }
}

private fun chrootHostPath(config: ContainerConfig, requested: File): Pair<Path, Path> {
    val lexicalRoot = File(config.rootfsPath).absoluteFile.normalize().toPath()
    require(!Files.isSymbolicLink(lexicalRoot) && Files.isDirectory(lexicalRoot, NOFOLLOW_LINKS)) { "chroot Rootfs 无效" }
    val root = lexicalRoot.toRealPath()
    val path = requested.absoluteFile.normalize().toPath()
    val target = if (path.startsWith(lexicalRoot)) root.resolve(lexicalRoot.relativize(path)) else path
    require(target.startsWith(root) && target != root) { "chroot 共享路径越出 Rootfs" }
    val excluded = listOf("/dev", "/proc", "/sys", "/host-rootfs") + config.mounts.map { it.target }
    for (name in excluded) {
        val boundary = root.resolve(name.trimStart('/')).normalize()
        require(!target.startsWith(boundary)) { "chroot 共享路径不能位于宿主或系统挂载内：$requested" }
    }
    var current = root
    for (part in root.relativize(target)) {
        current = current.resolve(part)
        require(!Files.isSymbolicLink(current)) { "chroot 共享路径不能包含符号链接：$current" }
    }
    return root to target
}

private val chrootReservedHomes = setOf("/", "/home", "/etc", "/usr", "/bin", "/sbin", "/lib", "/lib64", "/var", "/tmp", "/opt", "/run", "/boot")

internal fun prepareChrootOwnership(
    config: ContainerConfig, root: File, uid: String, gid: String, account: String, homeName: String,
    rootCommand: (String) -> String,
) {
    require(File(homeName).toPath().normalize().toString() !in chrootReservedHomes) { "chroot HOME 不能使用系统目录根，拒绝接管其所有权" }
    val home = File(root, homeName.trimStart('/'))
    chrootHostPath(config, home)
    val workspace = File(root, "workspace").takeUnless { candidate ->
        config.mounts.any { candidate.toPath().startsWith(root.toPath().resolve(it.target.trimStart('/'))) }
    }
    if (workspace != null) chrootHostPath(config, workspace)
    val escapedMount = Regex("""\\([0-7]{3})""")
    val mounts = rootCommand("cat /proc/self/mountinfo").lineSequence().filter(String::isNotBlank).map { line ->
        val fields = line.split(' ')
        require(fields.size > 6) { "无法解析 chroot 内核挂载信息" }
        File(escapedMount.replace(fields[4]) { it.groupValues[1].toInt(8).toChar().toString() })
    }.filter { it.toPath().startsWith(root.toPath()) }.toList()
    require(mounts.none { home.toPath().startsWith(it.toPath()) }) { "chroot HOME 不能位于已有挂载内" }
    val writableWorkspace = workspace?.takeUnless { candidate -> mounts.any { candidate.toPath().startsWith(it.toPath()) } }
    if (uid != "0" && gid != ChrootHostIdentity.gid.toString()) {
        chrootHostPath(config, File(root, "etc/group"))
        require(mounts.none { File(root, "etc/group").toPath().startsWith(it.toPath()) }) { "chroot 用户组数据库不能位于挂载内" }
        RuntimeIdentity.update(root, setOf(ChrootHostIdentity.gid), member = account)
    }
    rootCommand(chrootOwnershipScript(root, home, writableWorkspace, uid, gid, mounts +
        listOf("dev", "proc", "sys", "host-rootfs").map { File(root, it) } +
        config.mounts.map { File(root, it.target.trimStart('/')).canonicalFile }))
}

/** Operates only on HOME/workspace, never the rootfs tree, links, hardlink aliases or mounted data. */
internal fun chrootOwnershipScript(
    root: File, home: File, workspace: File?, uid: String, gid: String,
    exclusions: List<File>, hostUid: Int = ChrootHostIdentity.uid, hostGid: Int = ChrootHostIdentity.gid,
): String {
    val boundaries = exclusions.distinct().joinToString("|") { shellQuote(it.path.trimEnd('/') + "/") + "*" }
    return """
        set -eu
        root=${shellQuote(root.path)}
        home=${shellQuote(home.path)}
        workspace=${shellQuote(workspace?.path.orEmpty())}
        fail() { printf '%s\n' "${'$'}*" >&2; exit 1; }
        excluded() { ${if (boundaries.isEmpty()) "return 1" else "case \"\$1/\" in $boundaries) return 0;; *) return 1;; esac"}; }
        checked() {
            [ ! -L "${'$'}1" ] || fail "chroot 权限路径不能是符号链接：${'$'}1"
            actual=${'$'}(readlink -f -- "${'$'}1")
            [ "${'$'}actual" = "${'$'}1" ] || fail "chroot 权限路径已改变：${'$'}1"
            case "${'$'}1/" in "${'$'}root/"*) ;; *) fail 'chroot 权限路径越出 Rootfs';; esac
            excluded "${'$'}1" && fail "chroot 权限路径位于挂载内：${'$'}1"
            return 0
        }
        pin() {
            checked "${'$'}1"
            exec 3< "${'$'}1"
            [ "${'$'}(readlink -f -- /proc/self/fd/3)" = "${'$'}1" ] || fail 'chroot 权限路径在打开时已改变'
            [ "${'$'}(stat -c '%d:%i' -- "${'$'}1")" = "${'$'}(stat -Lc '%d:%i' -- /proc/self/fd/3)" ] || fail 'chroot 权限文件身份已改变'
        }
        repair() (
            location=${'$'}1
            [ ! -L "${'$'}location" ] || exit 0
            excluded "${'$'}location" && exit 0
            checked "${'$'}location"
            # Skip special files before opening; mutate only the pinned, rechecked inode.
            if [ -d "${'$'}location" ]; then
                kind=directory
            elif [ -f "${'$'}location" ]; then
                [ "${'$'}(stat -c %h -- "${'$'}location")" = 1 ] || exit 0
                kind=file
            else
                exit 0
            fi
            pin "${'$'}location"
            [ "${'$'}kind" != file ] || [ "${'$'}(stat -Lc %h -- /proc/self/fd/3)" = 1 ] || exit 0
            owner=${'$'}(stat -Lc %u -- /proc/self/fd/3)
            # Do not take another account's private files. The selected roots are intentional.
            if [ "${'$'}owner" = '$hostUid' ] || [ "${'$'}owner" = '$uid' ] || [ "${'$'}location" = "${'$'}home" ] || [ "${'$'}location" = "${'$'}workspace" ]; then
                case "${'$'}location" in
                    "${'$'}home"|"${'$'}home/.wawlink"|"${'$'}home/.wawlink/container.json"|"${'$'}home/.omp"|"${'$'}home/.omp/"*) shared=1;;
                    *) shared=0;;
                esac
                if [ -n "${'$'}workspace" ]; then
                    case "${'$'}location/" in "${'$'}workspace/"*) shared=1;; esac
                fi
                if [ "${'$'}shared" = 1 ]; then
                    chown '$uid:$hostGid' -- /proc/self/fd/3
                    if [ "${'$'}kind" = directory ]; then chmod 2770 -- /proc/self/fd/3; else chmod ug+rw,o-rwx -- /proc/self/fd/3; fi
                else
                    chown '$uid:$gid' -- /proc/self/fd/3
                    if [ "${'$'}kind" = directory ]; then chmod u+rwx -- /proc/self/fd/3; else chmod u+rw -- /proc/self/fd/3; fi
                fi
            fi
            exec 3<&-
            if [ "${'$'}kind" = directory ]; then
                for child in "${'$'}location"/* "${'$'}location"/.[!.]* "${'$'}location"/..?*; do
                    [ -e "${'$'}child" ] || [ -L "${'$'}child" ] || continue
                    repair "${'$'}child"
                done
            fi
        )
        ensure_root() (
            parent=${'$'}{1%/*}
            if [ "${'$'}parent" != "${'$'}root" ]; then
                [ -d "${'$'}parent" ] || ensure_root "${'$'}parent"
            fi
            pin "${'$'}parent"
            if [ "${'$'}parent" != "${'$'}root" ]; then
                # Preserve system ownership; add traversal only to the common private App group.
                chgrp '$hostGid' -- /proc/self/fd/3
                chmod g+rx -- /proc/self/fd/3
            fi
            [ -e "${'$'}1" ] || mkdir -- "/proc/self/fd/3/${'$'}{1##*/}"
            exec 3<&-
            checked "${'$'}1"
            [ -d "${'$'}1" ] || fail 'chroot HOME/工作区不是目录'
        )
        ensure_root "${'$'}home"
        ensure_root "${'$'}home/.wawlink"
        ensure_root "${'$'}home/.omp"
        repair "${'$'}home"
        if [ -n "${'$'}workspace" ]; then ensure_root "${'$'}workspace"; repair "${'$'}workspace"; fi
    """.trimIndent()
}
