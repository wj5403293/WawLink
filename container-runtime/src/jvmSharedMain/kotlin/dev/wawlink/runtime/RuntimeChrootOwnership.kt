package dev.wawlink.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/** Real host identity is used only by mapped-engine access repair and legacy inference. */
internal object ChrootHostIdentity {
    private val status by lazy { File("/proc/self/status").readLines() }
    val uid: Int by lazy { effective("Uid:") }
    val gid: Int by lazy { effective("Gid:") }
    private fun effective(field: String): Int = status.first { it.startsWith(field) }
        .substringAfter(':').trim().split(Regex("\\s+"))[1].toInt()
}

/** Prepare target-owned managed directories offline; never traverse bindings. */
fun prepareContainerHostDirectory(
    config: ContainerConfig, directory: File,
    rootCommand: (String) -> String = ::runHostRootCommand,
) {
    if (config.engine == ContainerEngineType.CHROOT) RootfsHostFiles(config, rootCommand).prepareDirectory(directory)
    else {
        prepareMappedHostDirectory(config, directory)
        RootfsOwnership.recordHostWrite(config, directory)
    }
}

/** Finalize ownership after writing bytes. New writers should use the atomic offline host store. */
fun prepareContainerHostFile(
    config: ContainerConfig, file: File,
    rootCommand: (String) -> String = ::runHostRootCommand,
) {
    if (config.engine == ContainerEngineType.CHROOT) RootfsHostFiles(config, rootCommand).prepareFile(file)
    else {
        prepareMappedHostFile(config, file)
        RootfsOwnership.recordHostWrite(config, file)
    }
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
    config: ContainerConfig, root: File, uid: String, gid: String, homeName: String,
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
    rootCommand(chrootOwnershipScript(root, home, writableWorkspace, uid, gid, mounts +
        listOf("dev", "proc", "sys", "host-rootfs").map { File(root, it) } +
        config.mounts.map { File(root, it.target.trimStart('/')).canonicalFile }))
}

/** Only managed roots and files: never walk HOME or project/dependency trees on startup. */
internal fun chrootOwnershipScript(
    root: File, home: File, workspace: File?, uid: String, gid: String,
    exclusions: List<File>,
): String {
    val boundaries = exclusions.distinct().joinToString("|") { shellQuote(it.path.trimEnd('/') + "/") + "*" }
    val worker = """
        set -eu
        root=${shellQuote(root.path)}
        home=${shellQuote(home.path)}
        workspace=${shellQuote(workspace?.path.orEmpty())}
        uid=${shellQuote(uid)}
        gid=${shellQuote(gid)}
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
            [ "${'$'}(pwd -P)" = "${'$'}1" ] && [ "${'$'}(stat -c '%d:%i' "${'$'}1")" = "${'$'}identity" ] || fail 'chroot 权限目录路径已改变'
        }
        verify_file() {
            [ ! -L "${'$'}entry" ] && [ -f "${'$'}entry" ] &&
                [ "${'$'}(pwd -P)" = "${'$'}{location%/*}" ] &&
                [ "${'$'}(stat -c '%d:%i' .)" = "${'$'}(stat -c '%d:%i' "${'$'}{location%/*}")" ] &&
                [ "${'$'}(stat -c '%d:%i:%h' "${'$'}entry")" = "${'$'}identity:1" ] || fail 'chroot 权限文件身份已改变'
        }
        repair() (
            location=${'$'}1
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
            if [ "${'$'}kind" = file ]; then verify_file; fi
            if [ "${'$'}owner" != "${'$'}uid" ] || [ "${'$'}group" != "${'$'}gid" ]; then
                # Never dereference a file swapped to a symlink during ownership correction.
                chown -h "${'$'}uid:${'$'}gid" "${'$'}entry"
                if [ "${'$'}kind" = file ]; then verify_file; fi
                # chown may clear setgid; restore exactly the guest-selected mode, but do not
                # impose a new policy on an existing entry after the ownership manifest exists.
                chmod "${'$'}mode" "${'$'}entry"
                if [ "${'$'}kind" = directory ]; then
                    checked "${'$'}location"
                    [ "${'$'}(pwd -P)" = "${'$'}location" ] && [ "${'$'}(stat -c '%d:%i' "${'$'}location")" = "${'$'}identity" ] || fail 'chroot 权限目录身份已改变'
                else verify_file; fi
            fi
        )
        ensure_root() (
            location=${'$'}1
            if [ -d "${'$'}location" ] && [ ! -L "${'$'}location" ]; then checked "${'$'}location"; exit 0; fi
            parent=${'$'}{location%/*}
            [ "${'$'}parent" = "${'$'}root" ] || ensure_root "${'$'}parent"
            pin_directory "${'$'}parent"
            entry="./${'$'}{location##*/}"; created=0
            [ ! -L "${'$'}entry" ] || fail 'chroot HOME/工作区不能是符号链接'
            if [ ! -e "${'$'}entry" ]; then
                umask 077
                if error=${'$'}(mkdir "${'$'}entry" 2>&1); then created=1
                elif [ ! -L "${'$'}entry" ] && [ -d "${'$'}entry" ]; then :
                else fail "${'$'}error"; fi
            fi
            checked "${'$'}location"
            [ -d "${'$'}entry" ] || fail 'chroot HOME/工作区不是目录'
            pin_directory "${'$'}location"
            if [ "${'$'}created" = 1 ]; then
                case "${'$'}location/" in
                    "${'$'}home/"*) chown -h "${'$'}uid:${'$'}gid" .; chmod 700 .;;
                    *) if [ -n "${'$'}workspace" ] && [ "${'$'}location" = "${'$'}workspace" ]; then
                        chown -h "${'$'}uid:${'$'}gid" .; chmod 700 .
                       else chown -h 0:0 .; chmod 755 .; fi;;
                esac
                checked "${'$'}location"
                [ "${'$'}(pwd -P)" = "${'$'}location" ] && [ "${'$'}(stat -c '%d:%i' "${'$'}location")" = "${'$'}identity" ] || fail 'chroot 权限目录身份已改变'
            fi
        )
    """.trimIndent()
    return worker + "\n" + """
        ensure_root "${'$'}home"
        repair "${'$'}home"
        ensure_root "${'$'}home/.wawlink"
        repair "${'$'}home/.wawlink"
        ensure_root "${'$'}home/.omp"
        repair "${'$'}home/.omp"
        ensure_root "${'$'}home/.omp/agent"
        repair "${'$'}home/.omp/agent"
        for dir in bin feature-debug features; do
            repair "${'$'}home/.wawlink/${'$'}dir"
        done
        repair "${'$'}home/.wawlink/container.json"
        repair "${'$'}home/.wawlink/feature-cards.json"
        repair "${'$'}home/.wawlink/bin/wawlink-feature"
        for file in owner installation.json endpoint.json; do
            repair "${'$'}home/.wawlink/feature-debug/${'$'}file"
        done
        for dir in skills wawlink sessions rules prompts agents advisors; do
            repair "${'$'}home/.omp/agent/${'$'}dir"
        done
        repair "${'$'}home/.omp/agent/skills/wawlink-functions"
        repair "${'$'}home/.omp/agent/skills/wawlink-functions/SKILL.md"
        repair "${'$'}home/.omp/agent/skills/wawlink-functions/.wawlink-builtin.sha256"
        if [ -n "${'$'}workspace" ]; then
            ensure_root "${'$'}workspace"
            repair "${'$'}workspace"
        fi
    """.trimIndent()
}
