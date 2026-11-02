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

/** Prepare managed host writes both before and after guest startup; never traverse bindings. */
fun prepareContainerHostDirectory(config: ContainerConfig, directory: File) {
    if (config.engine != ContainerEngineType.CHROOT) {
        prepareMappedHostDirectory(config, directory)
        return
    }
    val (root, target) = containerHostPath(config, directory)
    var current = root
    for (part in root.relativize(target)) {
        current = current.resolve(part)
        var created = false
        if (!Files.exists(current, NOFOLLOW_LINKS)) {
            try { Files.createDirectory(current); created = true }
            catch (_: java.nio.file.FileAlreadyExistsException) { /* Another host management writer won. */ }
        }
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
    if (config.engine != ContainerEngineType.CHROOT) {
        prepareMappedHostFile(config, file)
        return
    }
    val (_, target) = containerHostPath(config, file)
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

internal fun containerHostPath(config: ContainerConfig, requested: File): Pair<Path, Path> {
    val lexicalRoot = File(config.rootfsPath).absoluteFile.normalize().toPath()
    require(!Files.isSymbolicLink(lexicalRoot) && Files.isDirectory(lexicalRoot, NOFOLLOW_LINKS)) { "容器 Rootfs 无效" }
    val root = lexicalRoot.toRealPath()
    val path = requested.absoluteFile.normalize().toPath()
    val target = if (path.startsWith(lexicalRoot)) root.resolve(lexicalRoot.relativize(path)) else path
    require(target.startsWith(root) && target != root) { "容器管理路径越出 Rootfs" }
    val excluded = listOf("/dev", "/proc", "/sys", "/host-rootfs") + config.mounts.map { it.target }
    for (name in excluded) {
        val boundary = root.resolve(name.trimStart('/')).normalize()
        require(!target.startsWith(boundary)) { "容器管理路径不能位于宿主或系统挂载内：$requested" }
    }
    var current = root
    for (part in root.relativize(target)) {
        current = current.resolve(part)
        require(!Files.isSymbolicLink(current)) { "容器管理路径不能包含符号链接：$current" }
    }
    return root to target
}

internal val reservedContainerHomes = setOf("/", "/home", "/etc", "/usr", "/bin", "/sbin", "/lib", "/lib64", "/var", "/tmp", "/opt", "/run", "/boot")

internal fun prepareChrootOwnership(
    config: ContainerConfig, root: File, uid: String, gid: String, account: String, homeName: String,
    rootCommand: (String) -> String,
) {
    require(File(homeName).toPath().normalize().toString() !in reservedContainerHomes) { "chroot HOME 不能使用系统目录根，拒绝接管其所有权" }
    val home = File(root, homeName.trimStart('/'))
    containerHostPath(config, home)
    val workspace = File(root, "workspace").takeUnless { candidate ->
        config.mounts.any { candidate.toPath().startsWith(root.toPath().resolve(it.target.trimStart('/'))) }
    }
    if (workspace != null) containerHostPath(config, workspace)
    val escapedMount = Regex("""\\([0-7]{3})""")
    val mounts = rootCommand("cat /proc/self/mountinfo").lineSequence().filter(String::isNotBlank).map { line ->
        val fields = line.split(' ')
        require(fields.size > 6) { "无法解析 chroot 内核挂载信息" }
        File(escapedMount.replace(fields[4]) { it.groupValues[1].toInt(8).toChar().toString() })
    }.filter { it.toPath().startsWith(root.toPath()) }.toList()
    require(mounts.none { home.toPath().startsWith(it.toPath()) }) { "chroot HOME 不能位于已有挂载内" }
    val writableWorkspace = workspace?.takeUnless { candidate -> mounts.any { candidate.toPath().startsWith(it.toPath()) } }
    if (uid != "0" && gid != ChrootHostIdentity.gid.toString()) {
        containerHostPath(config, File(root, "etc/group"))
        require(mounts.none { File(root, "etc/group").toPath().startsWith(it.toPath()) }) { "chroot 用户组数据库不能位于挂载内" }
        RuntimeIdentity.update(root, setOf(ChrootHostIdentity.gid), member = account)
    }
    rootCommand(chrootOwnershipScript(root, home, writableWorkspace, uid, gid, mounts +
        listOf("dev", "proc", "sys", "host-rootfs").map { File(root, it) } +
        config.mounts.map { File(root, it.target.trimStart('/')).canonicalFile }))
}

/** Only managed roots and files: never walk HOME or project/dependency trees on startup. */
internal fun chrootOwnershipScript(
    root: File, home: File, workspace: File?, uid: String, gid: String,
    exclusions: List<File>, hostGid: Int = ChrootHostIdentity.gid,
): String {
    val boundaries = exclusions.distinct().joinToString("|") { shellQuote(it.path.trimEnd('/') + "/") + "*" }
    val mountPrunes = exclusions.distinct().joinToString("") { file ->
        val literal = file.path.replace("\\", "\\\\").replace("*", "\\*").replace("?", "\\?").replace("[", "\\[")
        " -o -path " + shellQuote(literal)
    }
    val worker = """
        set -eu
        root=${shellQuote(root.path)}
        home=${shellQuote(home.path)}
        workspace=${shellQuote(workspace?.path.orEmpty())}
        fail() { echo "${'$'}*" >&2; exit 1; }
        excluded() { ${if (boundaries.isEmpty()) "return 1" else "case \"\$1/\" in $boundaries) return 0;; *) return 1;; esac"}; }
        checked() {
            case "${'$'}1/" in "${'$'}root/"*) ;; *) fail 'chroot 权限路径越出 Rootfs';; esac
            excluded "${'$'}1" && fail "chroot 权限路径位于挂载内：${'$'}1"
            [ ! -L "${'$'}1" ] && [ "${'$'}(readlink -f "${'$'}1")" = "${'$'}1" ] || fail "chroot 权限路径已改变：${'$'}1"
        }
        pin_directory() {
            checked "${'$'}1"
            identity=${'$'}(stat -c '%d:%i' "${'$'}1")
            cd -P "${'$'}1"
            [ "${'$'}(stat -c '%d:%i' .)" = "${'$'}identity" ] || fail 'chroot 权限目录身份已改变'
            checked "${'$'}1"
        }
        verify_file() {
            [ ! -L "${'$'}entry" ] && [ -f "${'$'}entry" ] &&
                [ "${'$'}(stat -c '%d:%i:%h' "${'$'}entry")" = "${'$'}identity:1" ] || fail 'chroot 权限文件身份已改变'
        }
        repair() (
            location=${'$'}1; policy=${'$'}2
            [ ! -L "${'$'}location" ] || exit 0
            excluded "${'$'}location" && exit 0
            if [ -d "${'$'}location" ]; then
                pin_directory "${'$'}location"
                entry=.; kind=directory
            elif [ -f "${'$'}location" ]; then
                pin_directory "${'$'}{location%/*}"
                entry="./${'$'}{location##*/}"; kind=file
            else
                exit 0
            fi
            [ ! -L "${'$'}entry" ] || fail 'chroot 权限条目已变为链接'
            set -- ${'$'}(stat -c '%d %i %h %u %g %a' "${'$'}entry")
            identity="${'$'}1:${'$'}2"; links=${'$'}3; owner=${'$'}4; group=${'$'}5; mode=${'$'}6
            [ "${'$'}kind" != file ] || [ "${'$'}links" = 1 ] || exit 0
            target_group='$gid'
            [ "${'$'}policy" != shared ] || target_group='$hostGid'
            if [ "${'$'}kind" = file ]; then verify_file; fi
            if [ "${'$'}owner" != '$uid' ] || [ "${'$'}group" != "${'$'}target_group" ]; then
                # Never dereference a file swapped to a symlink during ownership correction.
                chown -h "$uid:${'$'}target_group" "${'$'}entry"
                # chown can clear setgid even when the captured mode was already 2770.
                mode=${'$'}(stat -c '%a' "${'$'}entry")
            fi
            if [ "${'$'}kind" = directory ]; then
                if [ "${'$'}policy" = shared ]; then
                    [ "${'$'}mode" = 2770 ] || chmod 2770 .
                else
                    [ "${'$'}mode" = 700 ] || chmod 700 .
                fi
                checked "${'$'}location"
            else
                verify_file
                case "${'$'}policy:${'$'}mode" in
                    shared:660|shared:670|shared:760|shared:770|private:600|executable:700) ;;
                    shared:*) chmod ug+rw,o-rwx "${'$'}entry";;
                    executable:*) chmod 700 "${'$'}entry";;
                    *) chmod 600 "${'$'}entry";;
                esac
                verify_file
            fi
        )
        ensure_root() (
            if [ -d "${'$'}1" ] && [ ! -L "${'$'}1" ]; then checked "${'$'}1"; exit 0; fi
            parent=${'$'}{1%/*}
            [ "${'$'}parent" = "${'$'}root" ] || ensure_root "${'$'}parent"
            pin_directory "${'$'}parent"
            [ -e "./${'$'}{1##*/}" ] || mkdir "./${'$'}{1##*/}"
            checked "${'$'}1"
            [ -d "${'$'}1" ] || fail 'chroot HOME/工作区不是目录'
        )
    """.trimIndent()
    return worker + "\n" + """
        worker=${shellQuote(worker + "\nfor location do repair \"\$location\" private; done")}
        # BusyBox find can run an unqualified sh as its own ash applet, ignoring PATH.
        worker_shell=${'$'}(command -v sh)
        scan_private() {
            [ -d "${'$'}1" ] && [ ! -L "${'$'}1" ] || return 0
            checked "${'$'}1"
            scan_root=${'$'}1; file_filter=
            if [ "${'$'}2" = files ]; then
                file_filter='-type f'; set -- -maxdepth 1
            else
                set --
            fi
            # Native find tests metadata once, batching only entries requiring actual changes.
            find "${'$'}scan_root" -xdev "${'$'}@" \
                \( -name node_modules -o -name .git -o -name .bun -o -name .cache -o -name .venv$mountPrunes \) -prune -o \
                \( ${'$'}file_filter \
                    \( -type d \( ! -user '$uid' -o ! -group '$gid' -o ! -perm 0700 \) \
                    -o -type f -links 1 \( ! -user '$uid' -o ! -group '$gid' -o ! -perm 0600 \) \) \) \
                -exec "${'$'}worker_shell" -c "${'$'}worker" wawlink-chroot-permissions {} +
        }
        ensure_root "${'$'}home"
        # Only ancestors need traversal; do not change their owner or walk their children.
        parent=${'$'}{home%/*}
        while [ "${'$'}parent" != "${'$'}root" ]; do
            (pin_directory "${'$'}parent"; chgrp '$hostGid' .; chmod g+rx .)
            parent=${'$'}{parent%/*}
        done
        repair "${'$'}home" shared
        ensure_root "${'$'}home/.wawlink"
        repair "${'$'}home/.wawlink" shared
        for dir in .omp .omp/agent; do
            ensure_root "${'$'}home/${'$'}dir"
            repair "${'$'}home/${'$'}dir" private
        done
        repair "${'$'}home/.wawlink/container.json" shared
        repair "${'$'}home/.wawlink/feature-cards.json" shared
        for dir in bin feature-debug; do repair "${'$'}home/.wawlink/${'$'}dir" private; done
        repair "${'$'}home/.wawlink/bin/wawlink-feature" executable
        for file in owner installation.json endpoint.json; do repair "${'$'}home/.wawlink/feature-debug/${'$'}file" private; done
        repair "${'$'}home/.wawlink/features" shared
        scan_private "${'$'}home/.omp" files
        scan_private "${'$'}home/.omp/agent" files
        # Adopt offline/imported managed data for the guest, never widen native privacy.
        for dir in wawlink sessions rules prompts agents advisors; do scan_private "${'$'}home/.omp/agent/${'$'}dir" tree; done
        repair "${'$'}home/.omp/agent/skills" private
        repair "${'$'}home/.omp/agent/skills/wawlink-functions" private
        repair "${'$'}home/.omp/agent/skills/wawlink-functions/SKILL.md" private
        repair "${'$'}home/.omp/agent/skills/wawlink-functions/.wawlink-builtin.sha256" private
        if [ -n "${'$'}workspace" ]; then
            ensure_root "${'$'}workspace"
            repair "${'$'}workspace" shared
        fi
    """.trimIndent()
}
