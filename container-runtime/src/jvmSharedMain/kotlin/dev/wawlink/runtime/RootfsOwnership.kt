package dev.wawlink.runtime

import java.io.File
import java.io.FileOutputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val OWNERSHIP_SCAN_COMPLETE = "WAWLINK_SNAPSHOT_COMPLETE"
private const val OWNERSHIP_LINKS = "WAWLINK_SNAPSHOT_LINKS"
private const val OWNERSHIP_LINKS_COMPLETE = "WAWLINK_SNAPSHOT_LINKS_COMPLETE"
private data class SnapshotLink(val inode: Long, val target: String)
private val OWNERSHIP_SCAN_WORKER = """
    set -eu
    emit() { printf '%s\000' "${'$'}@"; }
    # Android mksh has no builtin printf; its print -rN preserves bytes and NUL-separates arguments.
    case "${'$'}{KSH_VERSION:-}" in *MIRBSD*) emit() { print -rN -- "${'$'}@"; } ;; esac
    complete=
    link_marker=${'$'}(printf '\001')
    for p do
        shift
        if [ "${'$'}p" = '$OWNERSHIP_SCAN_COMPLETE' ]; then complete=1; else set -- "${'$'}@" "${'$'}p"; fi
    done
    if [ "${'$'}#" -gt 0 ]; then
        metadata=${'$'}(stat -c '%u:%g:%a:%d:%i:%h:%f:%s' -- "${'$'}@") || exit
        # Only numeric metadata is split; paths remain quoted positional arguments.
        set -f
        IFS='
    '
        for m in ${'$'}metadata; do
            [ "${'$'}#" -gt 0 ] || exit 1
            p=${'$'}1
            shift
            # stat's %f type nibble identifies links, avoiding a second lstat for links.
            mode=${'$'}{m%:*}
            mode=${'$'}{mode##*:}
            case "${'$'}mode" in
                [aA]*)
                    if [ "${'$'}{WAWLINK_SNAPSHOT_LINKS:-0}" = 1 ]; then
                        emit "${'$'}p" "${'$'}m" '' ''
                    else
                        # readlink also fails if a link was replaced after stat.
                        link=${'$'}(readlink -n -- "${'$'}p" && printf '\001') || exit
                        link=${'$'}{link%${'$'}link_marker}
                        emit "${'$'}p" "${'$'}m" "${'$'}link" "${'$'}link_marker"
                    fi
                    ;;
                *)
                    # Keep the non-link race check: a replaced path must not be accepted as regular.
                    [ ! -L "${'$'}p" ] || exit 1
                    emit "${'$'}p" "${'$'}m" '' '' ;;
            esac
        done
        [ "${'$'}#" -eq 0 ] || exit 1
    fi
    if [ -n "${'$'}complete" ]; then emit '$OWNERSHIP_SCAN_COMPLETE'; fi
""".trimIndent()

/** The sidecar is App-private and outside the guest tree; tar headers carry portable ownership. */
internal object RootfsOwnership {
    private val json = Json { ignoreUnknownKeys = true }
    private val storeLock = Any()
    private const val MAX_ID = 4294967294L
    private const val MODE_MASK = 4095 // 07777, including suid, sgid and sticky
    private val virtual = listOf("/dev", "/proc", "/sys", "/host-rootfs", "/sdcard", "/storage")

    fun sidecar(root: File): File = File(root.absoluteFile.normalize().path + ".wawlink-ownership.json")
    private fun validPath(path: String): Boolean = path.startsWith('/') && !path.startsWith("//") && '\u0000' !in path && '\n' !in path && '\r' !in path &&
        (path == "/" || (!path.endsWith('/') && path.split('/').drop(1).none { it.isEmpty() || it == "." || it == ".." }))
    private fun validate(manifest: OwnershipManifest) {
        require(manifest.version == 1) { "不支持的逻辑属主清单版本：${manifest.version}" }
        manifest.entries.forEach { (path, owner) ->
            require(validPath(path) && owner.uid in 0..MAX_ID && owner.gid in 0..MAX_ID && owner.mode in 0..MODE_MASK) { "逻辑属主清单包含无效路径、身份或权限位：$path" }
        }
    }
    private fun checkedSidecar(root: File): Path {
        val file = sidecar(root).toPath()
        require(!Files.isSymbolicLink(file)) { "逻辑属主清单不能是符号链接：$file" }
        if (Files.exists(file, NOFOLLOW_LINKS)) {
            require(Files.isRegularFile(file, NOFOLLOW_LINKS) && (Files.getAttribute(file, "unix:nlink", NOFOLLOW_LINKS) as Number).toLong() == 1L) { "逻辑属主清单必须是单硬链接普通文件：$file" }
        }
        return file
    }
    fun load(root: File): OwnershipManifest? = synchronized(storeLock) {
        val file = checkedSidecar(root)
        if (!Files.exists(file, NOFOLLOW_LINKS)) return@synchronized null
        json.decodeFromString<OwnershipManifest>(Files.readAllBytes(file).toString(Charsets.UTF_8)).also(::validate)
    }
    fun save(root: File, manifest: OwnershipManifest) = synchronized(storeLock) {
        validate(manifest)
        val file = checkedSidecar(root)
        val temporary = Files.createTempFile(file.parent, ".ownership-", ".tmp")
        try {
            Files.setAttribute(temporary, "unix:mode", 384, NOFOLLOW_LINKS) // 0600
            FileOutputStream(temporary.toFile()).use { output ->
                output.write(json.encodeToString(manifest).toByteArray(Charsets.UTF_8)); output.fd.sync()
            }
            checkedSidecar(root)
            Files.move(temporary, file, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
    }
    fun copyManifest(source: File, destination: File) { load(source)?.let { save(destination, it) } }
    fun moveManifest(source: File, destination: File) = synchronized(storeLock) {
        val original = checkedSidecar(source)
        if (Files.exists(original, NOFOLLOW_LINKS)) Files.move(original, checkedSidecar(destination), ATOMIC_MOVE, REPLACE_EXISTING)
    }
    fun deleteManifest(root: File) = synchronized(storeLock) { Files.deleteIfExists(checkedSidecar(root)); Unit }

    fun managedHome(config: ContainerConfig, rootCommand: ((String) -> String)? = null): String = containerHome(config, rootCommand)
    fun managedOwner(config: ContainerConfig, guestPath: String, mode: Int, directory: Boolean, rootCommand: ((String) -> String)? = null): LogicalOwner {
        require(validPath(guestPath)) { "容器逻辑路径无效：$guestPath" }
        load(File(config.rootfsPath))?.entries?.get(guestPath)?.let { return it.copy(mode = mode and MODE_MASK) }
        val text = if (rootCommand != null) passwd(config, rootCommand) else {
            val passwd = File(config.rootfsPath, "etc/passwd")
            containerHostPath(config, passwd)
            if (Files.isRegularFile(passwd.toPath(), NOFOLLOW_LINKS) && passwd.canRead()) passwd.readText() else ""
        }
        return RootfsOwnershipRules(text, config.user, managedHome(config, rootCommand)).infer(guestPath, mode and MODE_MASK, directory)
    }
    fun recordHostWrite(config: ContainerConfig, file: File) = synchronized(storeLock) {
        val root = File(config.rootfsPath)
        val manifest = load(root) ?: return@synchronized
        val (base, target) = containerHostPath(config, file)
        val path = "/" + base.relativize(target).toString()
        require(Files.exists(target, NOFOLLOW_LINKS) && !Files.isSymbolicLink(target)) { "宿主提交的文件不存在或为链接：$target" }
        val mode = (Files.getAttribute(target, "unix:mode", NOFOLLOW_LINKS) as Number).toInt() and MODE_MASK
        val committed = managedOwner(config, path, mode, Files.isDirectory(target, NOFOLLOW_LINKS))
        save(root, manifest.copy(entries = manifest.entries + (path to committed)))
    }

    /** The privileged pinned-parent commit already verified the inode; never probe it as App. */
    fun recordCommittedHostWrite(config: ContainerConfig, root: Path, target: Path, owner: LogicalOwner) = synchronized(storeLock) {
        require(root.isAbsolute && target == target.normalize() && target.startsWith(root) && target != root) { "容器管理路径越出 Rootfs" }
        val path = "/" + root.relativize(target).toString()
        require(validPath(path)) { "容器逻辑路径无效：$path" }
        val storageRoot = File(config.rootfsPath)
        val manifest = load(storageRoot) ?: return@synchronized
        save(storageRoot, manifest.copy(entries = manifest.entries + (path to owner)))
    }

    internal data class Entry(val path: String, val uid: Long, val gid: Long, val mode: Int, val device: Long, val inode: Long, val links: Long, val kind: Int, val linkTarget: String? = null, val size: Long = 0L) {
        val directory: Boolean get() = kind == 0x4000
        val symbolicLink: Boolean get() = kind == 0xA000
        val identity: String get() = "$device:$inode"
    }
    private fun root(config: ContainerConfig, rootCommand: ((String) -> String)? = null): File {
        if (rootCommand != null) return RootfsAccess.canonicalRoot(config, rootCommand)
        val requested = File(config.rootfsPath).absoluteFile.normalize()
        require(requested.isAbsolute && !Files.isSymbolicLink(requested.toPath()) && Files.isDirectory(requested.toPath(), NOFOLLOW_LINKS)) { "属主迁移的 Rootfs 必须是实际目录" }
        val real = requested.canonicalFile
        require(real.path !in setOf("/", "/home", "/root", "/data", "/data/data", "/data/user", "/data/user/0", "/etc", "/usr", "/var", "/tmp", "/dev", "/sys", "/proc")) { "拒绝迁移宿主系统目录" }
        return real
    }
    fun preflight(rootCommand: (String) -> String) {
        try {
            check(rootCommand("$ROOT_PERMISSION_CHECK_MARKER\nid -u").trim() == "0") { "su 未返回真实 UID 0" }
            rootCommand("$ROOT_PERMISSION_CHECK_MARKER\ncommand -v find >/dev/null && command -v xargs >/dev/null && command -v stat >/dev/null && command -v chown >/dev/null && command -v chmod >/dev/null && command -v readlink >/dev/null || { echo '缺少 find/xargs/stat/chown/chmod/readlink' >&2; exit 1; }")
        } catch (failure: Exception) { throw IllegalStateException("跨引擎属主迁移需要真实 Root 权限和系统文件工具，当前无法执行；原引擎配置未更改：${failure.message}", failure) }
    }
    fun exclusions(config: ContainerConfig, rootCommand: ((String) -> String)? = null): Set<String> {
        val base = root(config, rootCommand).toPath()
        val excluded = (virtual + config.mounts.map { it.target }).onEach { require(validPath(it.trimEnd('/').ifEmpty { "/" }) && it != "/") { "挂载目标无效：$it" } }.map { it.trimEnd('/') }.toMutableSet()
        val text = if (rootCommand != null) rootCommand("cat /proc/self/mountinfo") else File("/proc/self/mountinfo").readText()
        val escaped = Regex("""\\([0-7]{3})""")
        for (line in text.lineSequence().filter(String::isNotBlank)) {
            val fields = line.split(' ')
            require(fields.size >= 7) { "无法解析实际挂载边界" }
            val mount = Paths.get(escaped.replace(fields[4]) { it.groupValues[1].toInt(8).toChar().toString() }).normalize()
            require(mount != base) { "Rootfs 根本身是外部挂载，无法安全迁移" }
            if (mount.startsWith(base)) excluded += "/" + base.relativize(mount).toString()
        }
        return excluded
    }
    private fun excluded(path: String, exclusions: Set<String>): Boolean = exclusions.any { path.startsWith(it) && (path.length == it.length || path[it.length] == '/') }
    private fun prune(exclusions: Set<String>): String = exclusions.joinToString(" -o ") { "-path " + shellQuote("." + it.replace("\\", "\\\\").replace("*", "\\*").replace("?", "\\?").replace("[", "\\[")) }

    fun snapshot(config: ContainerConfig, rootCommand: ((String) -> String)? = null): List<Entry> {
        val base = root(config, rootCommand)
        val excluded = exclusions(config, rootCommand)
        if (rootCommand != null) {
            // Native find can collect link targets without one readlink process per link.
            // BusyBox lacks -printf; retain the same bounded stat/readlink worker there.
            val output = rootCommand("""
                $OWNERSHIP_SCAN_MARKER
                set -eu
                cd ${shellQuote(base.path)}
                worker_shell=${'$'}(command -v sh)
                scan_links=0
                if [ "${'$'}(find . -maxdepth 0 -printf 'WAWLINK%l_PRINTF' 2>/dev/null)" = WAWLINK_PRINTF ]; then
                    printf '%s\000' '$OWNERSHIP_LINKS'
                    find . -xdev \( ${prune(excluded)} \) -prune -o -type l -printf '%p\000%i\000%l\000' || exit
                    printf '%s\000' '$OWNERSHIP_LINKS_COMPLETE'
                    scan_links=1
                fi
                export WAWLINK_SNAPSHOT_LINKS=${'$'}scan_links
                { find . -xdev \( ${prune(excluded)} \) -prune -o -print0 || exit; printf '%s\000' '$OWNERSHIP_SCAN_COMPLETE'; } |
                    xargs -0 -r -n 512 -s 32768 "${'$'}worker_shell" -c ${shellQuote(OWNERSHIP_SCAN_WORKER)} ownership
            """.trimIndent())
            val fields = output.split('\u0000')
            val linkTargets = if (fields.firstOrNull() == OWNERSHIP_LINKS) HashMap<String, SnapshotLink>() else null
            var first = 0
            if (linkTargets != null) {
                first = 1
                while (fields.getOrNull(first) != OWNERSHIP_LINKS_COMPLETE) {
                    require(first + 2 < fields.size) { "属主扫描链接协议不完整" }
                    val path = fields[first]
                    val inode = fields[first + 1].toLongOrNull()
                    require(path.startsWith("./") && inode != null && inode >= 0 && fields[first + 2].isNotEmpty() &&
                        linkTargets.put(path, SnapshotLink(inode, fields[first + 2])) == null) { "属主扫描链接元数据无效" }
                    first += 3
                }
                first++
            }
            require(fields.size - first >= 6 && fields.last() == "" && fields[fields.lastIndex - 1] == OWNERSHIP_SCAN_COMPLETE && (fields.size - first - 2) % 4 == 0) { "属主扫描协议不完整" }
            val entries = (first until fields.size - 2 step 4).map { index ->
                require(fields[index] == "." || fields[index].startsWith("./")) { "属主扫描路径无效" }
                val path = if (fields[index] == ".") "/" else fields[index].removePrefix(".")
                require(validPath(path) && !excluded(path, excluded)) { "属主扫描越过保护边界：$path" }
                val values = fields[index + 1].split(':')
                require(values.size == 8) { "属主扫描元数据无效：$path" }
                val uid = values[0].toLongOrNull(); val gid = values[1].toLongOrNull(); val mode = values[2].toIntOrNull(8)
                val device = values[3].toLongOrNull(); val inode = values[4].toLongOrNull(); val links = values[5].toLongOrNull()
                val kind = values[6].toIntOrNull(16)?.and(0xF000); val size = values[7].toLongOrNull()
                require(uid != null && gid != null && uid in 0..MAX_ID && gid in 0..MAX_ID && mode != null && mode in 0..MODE_MASK &&
                    device != null && device >= 0 && inode != null && inode >= 0 && links != null && links > 0 && kind != null &&
                    when (kind) { 0x1000, 0x2000, 0x4000, 0x6000, 0x8000, 0xA000, 0xC000 -> true; else -> false } && size != null && size >= 0) { "属主扫描元数据无效：$path" }
                val target = if (kind == 0xA000) {
                    if (linkTargets != null) {
                        val link = linkTargets.remove(fields[index])
                        require(link != null && link.inode == inode && fields[index + 2].isEmpty() && fields[index + 3].isEmpty()) { "扫描期间符号链接发生变化：$path" }
                        link.target
                    } else {
                        require(fields[index + 3] == "\u0001") { "属主扫描链接类型不一致：$path" }
                        fields[index + 2]
                    }
                } else {
                    require(fields[index + 2].isEmpty() && fields[index + 3].isEmpty()) { "属主扫描链接类型不一致：$path" }
                    null
                }
                Entry(path, uid, gid, mode, device, inode, links, kind, target, size)
            }
            require(linkTargets.isNullOrEmpty()) { "扫描期间符号链接发生变化" }
            return entries
        }
        val entries = mutableListOf<Entry>()
        fun add(path: Path) {
            val guest = if (path == base.toPath()) "/" else "/" + base.toPath().relativize(path)
            require(validPath(guest)) { "属主扫描路径无效：$guest" }
            val values = Files.readAttributes(path, "unix:uid,gid,mode,dev,ino,nlink,size", NOFOLLOW_LINKS)
            val mode = (values.getValue("mode") as Number).toInt()
            val kind = mode and 0xF000
            val linkTarget = if (kind == 0xA000) Files.readSymbolicLink(path).toString() else null
            val size = (values.getValue("size") as Number).toLong()
            entries += Entry(guest, (values.getValue("uid") as Number).toLong(), (values.getValue("gid") as Number).toLong(), mode and MODE_MASK,
                (values.getValue("dev") as Number).toLong(), (values.getValue("ino") as Number).toLong(), (values.getValue("nlink") as Number).toLong(), kind, linkTarget, size)
        }
        Files.walkFileTree(base.toPath(), object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                val guest = if (dir == base.toPath()) "/" else "/" + base.toPath().relativize(dir)
                if (excluded(guest, excluded)) return FileVisitResult.SKIP_SUBTREE
                add(dir); return FileVisitResult.CONTINUE
            }
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val guest = "/" + base.toPath().relativize(file)
                if (!excluded(guest, excluded)) add(file)
                return FileVisitResult.CONTINUE
            }
        })
        return entries
    }

    fun resolveGuest(path: String, entries: Map<String, Entry>): String {
        require(validPath(path)) { "容器路径无效：$path" }
        val pending = java.util.ArrayDeque(path.trimStart('/').split('/').filter(String::isNotEmpty))
        val resolved = mutableListOf<String>()
        var followed = 0
        while (pending.isNotEmpty()) {
            when (val part = pending.removeFirst()) {
                "", "." -> Unit
                ".." -> { require(resolved.isNotEmpty()) { "链接越出 Rootfs：$path" }; resolved.removeAt(resolved.lastIndex) }
                else -> {
                    resolved += part
                    val link = entries["/" + resolved.joinToString("/")]?.linkTarget ?: continue
                    require(++followed <= 40) { "循环或过深的容器链接：$path" }
                    if (link.startsWith('/')) resolved.clear() else resolved.removeAt(resolved.lastIndex)
                    link.split('/').asReversed().forEach(pending::addFirst)
                }
            }
        }
        return "/" + resolved.joinToString("/")
    }

    fun exportHomes(config: ContainerConfig, entries: List<Entry>, rootCommand: (String) -> String): Set<String> {
        val index = entries.associateBy { it.path }
        val names = linkedSetOf("/root", managedHome(config, rootCommand))
        passwd(config, rootCommand).lineSequence().forEach { line ->
            val fields = line.split(':')
            if (fields.size == 7 && fields[2].toLongOrNull()?.let { it >= 0 } == true) {
                val home = fields[5]
                if (home.isNotEmpty() && home.startsWith('/')) {
                    require(validPath(home)) { "passwd 主目录越界或路径无效：$home" }
                    names += home
                }
            }
        }
        return names.flatMap { path -> listOf(path.trimStart('/'), resolveGuest(path, index).trimStart('/')) }.toSet()
    }
    private fun passwd(config: ContainerConfig, rootCommand: ((String) -> String)?): String {
        val base = root(config, rootCommand)
        val file = File(base, "etc/passwd")
        if (rootCommand == null) {
            containerHostPath(config, file)
            return if (Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) && !Files.isSymbolicLink(file.toPath())) file.readText() else ""
        }
        val privileged = config.copy(engine = ContainerEngineType.CHROOT)
        val bytes = RootfsHostFiles(privileged, rootCommand).read(file, 1024 * 1024)
            ?: error("Rootfs 缺少可读的 /etc/passwd")
        return bytes.toString(Charsets.UTF_8)
    }
    private fun plan(config: ContainerConfig, entries: List<Entry>, previous: OwnershipManifest?, rootCommand: ((String) -> String)?, real: Boolean): OwnershipManifest {
        val rules = RootfsOwnershipRules(passwd(config, rootCommand), config.user, managedHome(config, rootCommand))
        val appUid = ChrootHostIdentity.uid.toLong()
        val owners = LinkedHashMap<String, LogicalOwner>(entries.size)
        for (entry in entries) {
            val existing = previous?.entries?.get(entry.path)
            owners[entry.path] = when {
                real && previous != null -> LogicalOwner(entry.uid, entry.gid, entry.mode)
                existing != null -> existing.copy(mode = entry.mode)
                entry.uid != appUid -> LogicalOwner(entry.uid, entry.gid, entry.mode)
                else -> rules.infer(entry.path, entry.mode, entry.directory, entry.symbolicLink)
            }
        }
        return OwnershipManifest(owners, physical = previous?.physical ?: mapped(config.engine), origin = previous?.origin ?: "heuristic-v1", pending = previous?.pending)
    }
    private fun checkHardlinks(entries: List<Entry>, owners: Map<String, LogicalOwner>) {
        val inodes = entries.filter { !it.directory && it.links > 1 }.groupBy { it.identity }
        for (aliases in inodes.values) {
            check(aliases.size.toLong() == aliases.first().links) { "文件存在 Rootfs 外或受保护目录内的硬链接，无法安全迁移：${aliases.first().path}" }
            check(aliases.map { owners.getValue(it.path) }.distinct().size == 1) { "硬链接的逻辑属主或权限规则冲突：${aliases.joinToString { it.path }}" }
        }
    }
    private fun migrationIdentity(entry: Entry): String = "${entry.identity}:${entry.links}:${entry.kind}"
    fun mapped(engine: ContainerEngineType): ContainerEngineType = if (engine == ContainerEngineType.CHROOT) engine else ContainerEngineType.PROOT
    /** A physical-only legacy marker deliberately carries no reconstructed logical history. */
    fun retainPhysicalIdentity(config: ContainerConfig) {
        val root = File(config.rootfsPath)
        if (load(root) == null) save(root, OwnershipManifest(emptyMap(), physical = mapped(config.engine), origin = "physical-only-v1"))
    }
    fun physical(config: ContainerConfig): ContainerEngineType = load(File(config.rootfsPath))?.physical ?: mapped(config.engine)
    fun requireStable(config: ContainerConfig) {
        check(load(File(config.rootfsPath))?.pending == null) { "容器属主迁移尚未完成；请重新保存目标引擎设置以继续迁移，不能启动或归档半迁移的 Rootfs" }
    }
    fun refresh(config: ContainerConfig, rootCommand: ((String) -> String)? = null, scanned: List<Entry>? = null): OwnershipManifest {
        requireStable(config)
        val base = root(config, rootCommand)
        val previous = load(base)
        val entries = scanned ?: snapshot(config, rootCommand)
        val manifest = plan(config, entries, previous, rootCommand, real = previous?.physical == ContainerEngineType.CHROOT)
        checkHardlinks(entries, manifest.entries)
        if (previous == null && rootCommand == null) {
            // One-time legacy reconstruction; subsequent refreshes respect guest chmod rather than reapplying rules.
            for (entry in entries) {
                val owner = manifest.entries.getValue(entry.path)
                if (entry.symbolicLink || entry.mode == owner.mode || entry.uid != ChrootHostIdentity.uid.toLong()) continue
                val path = if (entry.path == "/") base.toPath() else containerHostPath(config, File(base, entry.path.trimStart('/'))).second
                check((Files.getAttribute(path, "unix:ino", NOFOLLOW_LINKS) as Number).toLong() == entry.inode) { "旧容器权限重建时文件已变化：${entry.path}" }
                Files.setAttribute(path, "unix:mode", owner.mode, NOFOLLOW_LINKS)
            }
        }
        save(base, manifest)
        return manifest
    }

    /** Entire plan is validated and journaled before the first mutation. Re-running resumes it. */
    fun migrate(previous: ContainerConfig, target: ContainerConfig, rootCommand: (String) -> String, onProgress: (String, Float?) -> Unit = { _, _ -> }, checkActive: () -> Unit = {}) {
        val base = root(target, rootCommand)
        onProgress("检查 Root 权限", null)
        preflight(rootCommand)
        require(root(previous, rootCommand) == base) { "属主迁移不能更换 Rootfs" }
        val stored = load(base)
        val from = stored?.pending?.from ?: stored?.physical ?: mapped(previous.engine)
        val to = mapped(target.engine)
        stored?.pending?.let { require(it.to == to) { "已有未完成的属主迁移，请先继续原目标引擎" } }
        val combined = target.copy(mounts = (previous.mounts + target.mounts).distinct())
        onProgress("扫描文件属主", null)
        val entries = snapshot(combined, rootCommand)
        onProgress("校验属主迁移计划 · ${entries.size} 项", null)
        val manifest = if (stored?.pending != null) {
            check(stored.pending.identities.isNotEmpty()) { "旧迁移日志缺少 inode 证据，拒绝猜测恢复；请删除并重新导入 Rootfs" }
            check(entries.size == stored.entries.size && entries.all { it.path in stored.entries }) { "迁移中断后 Rootfs 条目集合已改变，请停止外部写入后重试" }
            check(entries.all { entry -> stored.pending.identities[entry.path] == migrationIdentity(entry) }) { "迁移中断后 Rootfs inode、硬链接数或类型已改变，请停止外部写入后重试" }
            stored
        } else plan(target, entries, stored, rootCommand, real = from == ContainerEngineType.CHROOT)
        checkHardlinks(entries, manifest.entries)
        checkActive()
        save(base, manifest.copy(pending = OwnershipMigration(from, to, entries.associate { it.path to migrationIdentity(it) })))
        data class Batch(val parent: String, val uid: Long, val gid: Long, val mode: Int, val symbolic: Boolean)
        val index = entries.associateBy { it.path }
        val unique = HashSet<String>()
        val batches = linkedMapOf<Batch, MutableList<Entry>>()
        for (entry in entries.sortedByDescending { it.path.count { c -> c == '/' } }) {
            if (!entry.directory && !entry.symbolicLink && !unique.add(entry.identity)) continue
            val logical = manifest.entries.getValue(entry.path)
            val uid = if (to == ContainerEngineType.CHROOT) logical.uid else ChrootHostIdentity.uid.toLong()
            val gid = if (to == ContainerEngineType.CHROOT) logical.gid else ChrootHostIdentity.gid.toLong()
            if (entry.uid == uid && entry.gid == gid && (entry.symbolicLink || entry.mode == logical.mode)) continue
            val parent = entry.path.substringBeforeLast('/').ifEmpty { "/" }
            batches.getOrPut(Batch(parent, uid, gid, logical.mode, entry.symbolicLink)) { mutableListOf() }.add(entry)
        }
        val rootIdentity = index.getValue("/").identity
        val prefix = "$ROOTFS_OWNERSHIP_MIGRATION_MARKER\nset -eu\ncd ${shellQuote(base.path)}\n[ \"\$(stat -c '%d:%i' .)\" = ${shellQuote(rootIdentity)} ] || exit 1\n$APPLY_HELPER\n"
        val commands = StringBuilder()
        val pending = mutableListOf<Entry>()
        val total = batches.values.sumOf { it.size }
        var completed = 0
        onProgress("迁移文件属主 · 0 / $total 项", if (total > 0) 0f else null)
        fun flush() {
            if (pending.isEmpty()) return
            checkActive()
            val currentExclusions = exclusions(combined, rootCommand)
            check(pending.none { excluded(it.path, currentExclusions) }) { "迁移期间出现新的挂载，已停止且未提交引擎配置" }
            rootCommand(prefix + commands)
            completed += pending.size
            onProgress("迁移文件属主 · $completed / $total 项", completed.toFloat() / total)
            commands.setLength(0)
            pending.clear()
        }
        for ((batch, members) in batches) for (chunk in members.chunked(32)) {
            val before = chunk.joinToString("\n") { "${it.identity}:${it.links}:${it.uid}:${it.gid}:${it.mode.toString(8)}:${(it.kind or it.mode).toString(16)}" }
            val identities = chunk.joinToString("\n") { "${it.identity}:${it.links}" }
            val after = chunk.joinToString("\n") {
                val mode = if (it.symbolicLink) it.mode else batch.mode
                "${it.identity}:${it.links}:${batch.uid}:${batch.gid}:${mode.toString(8)}:${(it.kind or mode).toString(16)}"
            }
            val command = buildString {
                append("apply ").append(shellQuote(if (batch.parent == "/") "." else ".${batch.parent}"))
                    .append(' ').append(shellQuote(index.getValue(batch.parent).identity))
                    .append(' ').append(shellQuote(before)).append(' ').append(shellQuote(identities)).append(' ').append(shellQuote(after))
                    .append(' ').append(batch.uid).append(':').append(batch.gid).append(' ').append(batch.mode.toString(8))
                    .append(' ').append(if (batch.symbolic) '1' else '0')
                for (entry in chunk) append(' ').append(shellQuote(if (entry.path == "/") "." else "./${entry.path.substringAfterLast('/')}"))
                append('\n')
            }
            // Below Android's per-argument limit even with three-byte UTF-8 path characters.
            if (commands.length + command.length > 32 * 1024) flush()
            commands.append(command)
            pending.addAll(chunk)
        }
        flush()
        checkActive()
        onProgress("保存属主迁移结果", null)
        save(base, manifest.copy(physical = to, pending = null))
    }

    /** Deletes a stopped privileged tree only after rejecting every mount under its root. */
    fun deleteRootfs(config: ContainerConfig, rootCommand: (String) -> String) {
        val lexical = File(config.rootfsPath).absoluteFile.normalize()
        require(lexical.isAbsolute && !Files.isSymbolicLink(lexical.toPath())) { "Rootfs 路径无效或为符号链接" }
        require(lexical.path !in setOf("/", "/home", "/root", "/data", "/data/data", "/data/user", "/data/user/0", "/etc", "/usr", "/var", "/tmp", "/dev", "/sys", "/proc")) { "拒绝处理宿主系统目录" }
        val parentPath = lexical.parentFile.toPath()
        require(Files.isDirectory(parentPath, NOFOLLOW_LINKS) && parentPath == lexical.parentFile.canonicalFile.toPath()) { "Rootfs 父目录无效或为符号链接" }
        if (!Files.exists(lexical.toPath(), NOFOLLOW_LINKS)) {
            deleteManifest(lexical)
            return
        }
        val base = root(config, rootCommand)
        preflight(rootCommand)
        val rootPath = base.toPath()
        val escaped = Regex("""\\([0-7]{3})""")
        rootCommand("cat /proc/self/mountinfo").lineSequence().filter(String::isNotBlank).forEach { line ->
            val fields = line.split(' ')
            require(fields.size >= 7) { "无法解析实际挂载边界" }
            val mount = Paths.get(escaped.replace(fields[4]) { it.groupValues[1].toInt(8).toChar().toString() }).normalize()
            require(mount != rootPath && !mount.startsWith(rootPath)) { "Rootfs 或其子目录仍有实际挂载，拒绝删除" }
        }
        val parent = base.parentFile.canonicalFile
        val name = base.name
        require(name.isNotEmpty() && parent.toPath().startsWith(base.toPath().parent)) { "Rootfs 父目录无效" }
        val script = """
            set -eu
            root=${shellQuote(base.path)}
            parent=${shellQuote(parent.path)}
            name=${shellQuote(name)}
            [ ! -L "${'$'}root" ] && [ -d "${'$'}root" ] || { echo 'Rootfs 已变为链接或非目录' >&2; exit 1; }
            root_id=${'$'}(stat -c '%d:%i' -- "${'$'}root")
            parent_id=${'$'}(stat -c '%d:%i' -- "${'$'}parent")
            cd -P "${'$'}parent"
            [ "${'$'}(stat -c '%d:%i' -- "./${'$'}name")" = "${'$'}root_id" ] || exit 1
            [ ! -L "./${'$'}name" ] && [ -d "./${'$'}name" ] || { echo 'Rootfs 删除前边界已改变' >&2; exit 1; }
            rm -rf -- "./${'$'}name"
            [ ! -e "./${'$'}name" ] && [ ! -L "./${'$'}name" ] || { echo 'Rootfs 删除不完整' >&2; exit 1; }
            [ "${'$'}(stat -c '%d:%i' -- .)" = "${'$'}parent_id" ] || { echo 'Rootfs 父目录身份已改变' >&2; exit 1; }
        """.trimIndent()
        rootCommand(script)
        deleteManifest(base)
    }

    /** Bounded maintenance of a stopped private tree, not a sandbox for concurrent hostile Root writers. */
    private val APPLY_HELPER = """
        fail() { echo "属主迁移边界或 inode 已变化：${'$'}1" >&2; exit 1; }
        apply() (
            parent=${'$'}1; parent_id=${'$'}2; before=${'$'}3; identities=${'$'}4; after=${'$'}5
            owner=${'$'}6; mode=${'$'}7; symbolic=${'$'}8; shift 8
            # Check components without following links, then pin the shared parent once.
            current=.
            rest=${'$'}{parent#./}
            while [ "${'$'}rest" != . ] && [ -n "${'$'}rest" ]; do
                component=${'$'}{rest%%/*}; current=${'$'}current/${'$'}component
                [ ! -L "${'$'}current" ] && [ -d "${'$'}current" ] || fail "${'$'}parent"
                case "${'$'}rest" in */*) rest=${'$'}{rest#*/};; *) rest=;; esac
            done
            cd -P "${'$'}parent" || fail "${'$'}parent"
            [ "${'$'}(stat -c '%d:%i' .)" = "${'$'}parent_id" ] || fail "${'$'}parent"
            [ "${'$'}(stat -c '%d:%i:%h:%u:%g:%a:%f' -- "${'$'}@")" = "${'$'}before" ] || fail "${'$'}parent"
            chown -h "${'$'}owner" "${'$'}@"
            [ "${'$'}(stat -c '%d:%i:%h' -- "${'$'}@")" = "${'$'}identities" ] || fail "${'$'}parent"
            if [ "${'$'}symbolic" != 1 ]; then
                for name do [ ! -L "${'$'}name" ] || fail "${'$'}name"; done
                chmod "${'$'}mode" "${'$'}@"
            fi
            [ "${'$'}(stat -c '%d:%i:%h:%u:%g:%a:%f' -- "${'$'}@")" = "${'$'}after" ] || fail "${'$'}parent"
        )
    """.trimIndent()
}
