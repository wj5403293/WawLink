package dev.wawlink.runtime

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

internal object HostCommands {
    fun run(arguments: List<String>, timeout: Long = 45): String = run(ProcessBuilder(arguments), timeout)
    fun run(builder: ProcessBuilder, timeout: Long = 45): String {
        val command = builder.command().firstOrNull().orEmpty()
        val process = try { builder.redirectErrorStream(true).start() }
        catch (failure: IOException) { throw IllegalStateException("无法启动系统命令 $command：${failure.message}", failure) }
        return collect(process, command, timeout)
    }

    internal fun collect(process: Process, command: String, timeout: Long): String {
        val output = ByteArrayOutputStream()
        val stopping = AtomicBoolean()
        val readFailure = AtomicReference<Throwable?>()
        val reader = Thread({
            try {
                process.inputStream.use { input ->
                    val buffer = ByteArray(4096)
                    while (!stopping.get()) {
                        // Do not block on a pipe inherited by a descendant after its parent exits.
                        val available = input.available()
                        if (available > 0) {
                            val count = input.read(buffer, 0, minOf(buffer.size, available))
                            if (count < 0) break
                            val retained = minOf(count, 4 * 1024 * 1024 - output.size())
                            if (retained > 0) output.write(buffer, 0, retained)
                        } else if (!process.isAlive) break
                        else Thread.sleep(5)
                    }
                }
            } catch (failure: Throwable) {
                val expected = stopping.get() || failure is IOException && runCatching { !process.isAlive }.getOrDefault(false)
                if (!expected) {
                    readFailure.set(failure)
                    runCatching { process.destroyForcibly() }
                }
            }
        }, "wawlink-host-command-reader").apply { isDaemon = true }
        try {
            reader.start()
            process.outputStream.close()
            val exited = process.waitFor(timeout, TimeUnit.SECONDS)
            if (!exited) {
                stopping.set(true)
                process.destroyForcibly()
                error("系统命令超时：$command")
            }
            reader.join(2000)
            check(!reader.isAlive) { "系统命令输出读取未结束：$command" }
            readFailure.get()?.let { throw IllegalStateException("系统命令输出读取失败：$command：${it.message}", it) }
            val text = output.toString(Charsets.UTF_8.name())
            check(process.exitValue() == 0) { "系统命令失败（${process.exitValue()}）：${text.trim()}" }
            return text
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("系统命令已中断：$command", interrupted)
        } finally {
            stopping.set(true)
            runCatching { if (process.isAlive) process.destroyForcibly() }
            reader.interrupt()
            try {
                // destroyForcibly sends a signal; wait for the actual exit before returning.
                if (process.isAlive) process.waitFor(2, TimeUnit.SECONDS)
                reader.join(2000)
            } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            runCatching { process.outputStream.close() }
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
        }
    }
}

private data class LoginIdentity(val uid: String, val gid: String, val name: String, val home: String)

private fun loginIdentity(config: ContainerConfig): LoginIdentity {
    if (config.user == "root" || config.user == "0") return LoginIdentity("0", "0", "root", "/root")
    val root = File(config.rootfsPath).canonicalFile.toPath()
    val passwd = File(config.rootfsPath, "etc/passwd")
    require(passwd.canonicalFile.toPath().startsWith(root)) { "用户数据库路径越界" }
    require(Files.isRegularFile(passwd.canonicalFile.toPath(), NOFOLLOW_LINKS)) { "用户数据库不是普通文件" }
    val parts = passwd.useLines { lines -> lines.map { it.split(':') }.firstOrNull {
        it.size >= 7 && (it[0] == config.user || it[2] == config.user)
    } } ?: error("容器用户“${config.user}”不存在，请先在容器内创建该用户")
    require(parts[2].matches(Regex("[0-9]+")) && parts[3].matches(Regex("[0-9]+"))) { "容器用户 UID/GID 无效" }
    require(parts[5].startsWith('/')) { "容器用户主目录无效" }
    return LoginIdentity(parts[2], parts[3], parts[0], parts[5])
}

private fun loginHome(config: ContainerConfig, user: LoginIdentity): String = (config.environment["HOME"] ?: user.home).also {
    require(it.startsWith('/') && !it.startsWith("//") && '\u0000' !in it && it.split('/').none { part -> part == "." || part == ".." }) { "容器 HOME 必须是有效的绝对路径" }
}

/** Resolves the configured account and HOME override offline, exactly as the engine launcher does. */
fun containerHome(config: ContainerConfig): String = loginHome(config, loginIdentity(config))

internal class RuntimeEngines(
    private val paths: RuntimePaths,
    private val runRootCommand: (String) -> String = { HostCommands.run(listOf("su", "-c", it)) },
) {
    private val ownedMounts = mutableMapOf<String, MutableList<File>>()
    private val prootCapabilities = mutableMapOf<String, String>()
    private val prootLibraryPath: String by lazy {
        val native = paths.nativeLibraryDir ?: error("缺少 Android 原生库目录")
        val directory = File(paths.dataDir, "proot-libs")
        require(!Files.isSymbolicLink(directory.toPath())) { "原生依赖目录不能是符号链接" }
        Files.createDirectories(directory.toPath())
        // APK extraction only accepts lib*.so names, but PRoot needs this versioned SONAME.
        // Resolve the installed library without preloading Android code into the first glibc guest.
        val library = File(nativeBinary("libtalloc.so")).toPath()
        val alias = File(directory, "libtalloc.so.2").toPath()
        if (Files.exists(alias, NOFOLLOW_LINKS)) {
            require(Files.isSymbolicLink(alias)) { "原生依赖别名已被其他文件占用" }
            if (Files.readSymbolicLink(alias) != library) Files.delete(alias)
        }
        if (!Files.exists(alias, NOFOLLOW_LINKS)) Files.createSymbolicLink(alias, library)
        "${directory.path}:${native.path}"
    }

    fun validateRoot(config: ContainerConfig): File {
        val root = File(config.rootfsPath)
        require(root.isAbsolute && root.isDirectory && !Files.isSymbolicLink(root.toPath())) { "rootfs 必须是存在的绝对目录，且不能是符号链接" }
        val canonical = root.canonicalFile
        require(canonical.path !in setOf("/", "/home", "/root", "/usr", "/etc", "/var", "/tmp", "/dev", "/proc", "/sys", "/data", "/data/data", "/data/user", "/data/user/0")) { "拒绝将宿主系统目录作为 rootfs" }
        require(!paths.dataDir.canonicalFile.toPath().startsWith(canonical.toPath())) { "rootfs 不能包含应用数据目录" }
        require(File(root, "etc").isDirectory && File(root, "bin/sh").exists()) { "rootfs 不完整：缺少 /etc 或 /bin/sh" }
        return canonical
    }

    /** Same HOME as builder(), without probing engines or launching a guest process. */
    fun home(config: ContainerConfig): String = containerHome(config)

    fun validate(config: ContainerConfig) {
        val root = validateRoot(config)
        require(config.id.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,127}"))) { "容器 ID 只能包含字母、数字、下划线和短横线" }
        require(config.name.isNotBlank()) { "容器名称不能为空" }
        require(config.shell.startsWith('/') && '\u0000' !in config.shell) { "Shell 必须使用容器内绝对路径" }
        val selectedShell = File(root, config.shell.trimStart('/')).canonicalFile
        require(selectedShell.toPath().startsWith(root.toPath()) && selectedShell.isFile && selectedShell.canExecute()) { "选定 Shell 不存在或不可执行：${config.shell}；请先安装它或改用 /bin/sh" }
        home(config)
        config.environment.forEach { (key, value) ->
            require(key.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) && '\u0000' !in value) { "环境变量格式无效：$key" }
        }
        require(config.mounts.map { it.target.trimEnd('/') }.distinct().size == config.mounts.size) { "不能重复挂载同一目标" }
        config.mounts.forEach { mount ->
            val source = File(mount.source)
            require(source.isAbsolute && source.exists()) { "挂载源不存在：${mount.source}" }
            require(!source.canonicalFile.toPath().startsWith(root.toPath())) { "挂载源不能来自同一个 rootfs" }
            require(mount.target.startsWith('/') && !mount.target.startsWith("//") && mount.target != "/" && mount.target.split('/').none { it == ".." || it == "." } && '\u0000' !in mount.target && ':' !in mount.target && ':' !in mount.source) { "挂载路径无效：${mount.target}" }
            require(mount.target.trimEnd('/') !in setOf("/dev", "/proc", "/sys") && !listOf("/dev/", "/proc/", "/sys/").any(mount.target::startsWith)) { "自定义挂载不能覆盖系统挂载点" }
            if (!mount.readWrite && config.engine != ContainerEngineType.CHROOT) error("PRoot/proroot 无法保证只读挂载；请改用已获 Root 权限的 chroot，或移除此挂载")
            guestTarget(root, mount.target, source.isDirectory)
        }
        when (config.engine) {
            ContainerEngineType.PROROOT -> {
                require(paths.isAndroid && hostArch() == "arm64") { "proroot 仅支持 Android arm64；本机请切换到 PRoot" }
                require(guestArch(config.arch) == "arm64") { "proroot 仅能运行 arm64 rootfs，请切换到 PRoot + QEMU" }
                require(config.distro != "alpine" && !File(root, "lib/ld-musl-aarch64.so.1").exists()) { "proroot 需要 glibc，不能运行 Alpine/musl；请切换到 PRoot" }
                require(!config.sysvipc && !config.fakeKernel) { "当前 proroot 不支持 sysvipc/伪装内核开关，请关闭开关或切换引擎" }
                listOf("libproroot.so", "libproroot-runtime.so", "libproroot-linker.so", "libproroot-bridge.so", "libproroot-stub-loader.so").forEach { nativeBinary(it) }
            }
            ContainerEngineType.PROOT -> {
                val executable = proot()
                val help = prootCapabilities.getOrPut(executable) {
                    val builder = ProcessBuilder(executable, "--help").redirectErrorStream(true)
                    hostEnvironment(builder, config.engine)
                    HostCommands.run(builder, 15)
                }
                require(paths.isAndroid || !config.link2symlink) { "桌面 PRoot 的 link2symlink 会生成不可迁移的宿主绝对链接；请关闭此开关并使用真实硬链接" }
                require(!config.link2symlink || help.contains("--link2symlink")) { "当前 PRoot 不支持 --link2symlink；桌面原生硬链接无需此开关，请关闭它" }
                require(!config.sysvipc || help.contains("--sysvipc")) { "当前 PRoot 不支持 --sysvipc（仅部分 Termux 构建提供）" }
                require(!config.fakeKernel || help.contains("kernel-release") || help.contains("-k")) { "当前 PRoot 不支持伪装内核版本" }
                if (guestArch(config.arch) != hostArch()) require(!paths.qemuExecutable.isNullOrBlank()) { "异构 rootfs 需要 qemu-aarch64-static，请先配置 QEMU 路径" }
            }
            ContainerEngineType.CHROOT -> {
                require(!config.sysvipc && !config.fakeKernel) { "chroot 使用真实系统 IPC/内核，不能启用伪装选项" }
                require(guestArch(config.arch) == hostArch()) { "chroot 不能直接运行异构架构；请改用 PRoot + QEMU" }
                val result = rootCommand("id")
                require(Regex("(?:^|\\s)uid=0(?:\\D|$)").containsMatchIn(result)) { "未获得 Root 权限，请授权 su 或切换其他引擎" }
                rootCommand("command -v mountpoint >/dev/null && command -v mount >/dev/null && command -v umount >/dev/null && command -v chroot >/dev/null || { echo 'Root 环境缺少 mountpoint/mount/umount/chroot 命令' >&2; exit 127; }")
            }
        }
    }

    private fun hostArch(): String = guestArch(System.getProperty("os.arch", ""))
    fun guestArch(value: String): String = when (value.lowercase()) {
        "aarch64", "arm64", "arm64-v8a" -> "arm64"
        "amd64", "x86_64" -> "amd64"
        "arm", "armv7l", "armeabi-v7a" -> "arm"
        else -> value.lowercase()
    }

    private fun nativeBinary(name: String): String {
        val file = paths.nativeLibraryDir?.resolve(name) ?: error("未配置 Android 原生库目录")
        require(file.isFile && file.canExecute()) { "缺少可执行原生库：$name，请重新安装完整 APK" }
        return file.absolutePath
    }
    private fun proot(): String = if (paths.isAndroid) nativeBinary("libproot.so") else paths.hostProot

    private fun guestTarget(root: File, target: String, directory: Boolean): File {
        val relative = target.trimStart('/')
        val file = File(root, relative)
        val normalized = file.toPath().toAbsolutePath().normalize()
        require(normalized.startsWith(root.toPath()) && normalized != root.toPath()) { "挂载目标越界：$target" }
        require(file.canonicalFile.toPath().startsWith(root.toPath())) { "挂载目标通过符号链接逃逸：$target" }
        val actual = file.canonicalFile
        if (directory) { require(actual.isDirectory || actual.mkdirs()) { "无法创建挂载目录：$target" } }
        else { actual.parentFile.mkdirs(); if (!actual.exists()) actual.createNewFile(); require(actual.isFile) { "文件挂载目标不是文件：$target" } }
        return actual
    }

    fun prepare(config: ContainerConfig) {
        validate(config)
        if (config.engine != ContainerEngineType.CHROOT) return
        val root = validateRoot(config)
        val owned = ownedMounts.getOrPut(config.id) { mutableListOf() }
        fun mount(target: File, command: String, readOnlySource: File? = null) {
            val quoted = shellQuote(target.path)
            if (target in owned) {
                if (rootCommand("if mountpoint -q $quoted; then echo mounted; fi").trim() == "mounted") return
                owned.remove(target)
            }
            rootCommand("if mountpoint -q $quoted; then echo '挂载点已被其他程序占用' >&2; exit 1; fi; $command")
            owned += target
            // Android mount implementations may not support propagation flags. Only this
            // optional step may fail there; bind/remount errors must still trigger rollback.
            rootCommand("mount --make-private $quoted" + if (paths.isAndroid) " || true" else "")
            if (readOnlySource != null) {
                rootCommand("mount -o remount,bind,ro ${shellQuote(readOnlySource.path)} $quoted")
                val mountpoint = target.canonicalPath.replace("\\", "\\134").replace(" ", "\\040")
                    .replace("\t", "\\011").replace("\n", "\\012")
                val info = rootCommand("cat /proc/self/mountinfo")
                require(info.lineSequence().any { line -> val fields = line.split(' '); fields.size > 5 && fields[4] == mountpoint && "ro" in fields[5].split(',') }) { "内核未确认只读绑定挂载：$target" }
            }
        }
        try {
            val proc = guestTarget(root, "/proc", true)
            mount(proc, "mount -t proc proc ${shellQuote(proc.path)}")
            val sys = guestTarget(root, "/sys", true)
            mount(sys, "mount -t sysfs sysfs ${shellQuote(sys.path)}")
            val dev = guestTarget(root, "/dev", true)
            mount(dev, "mount -o bind /dev ${shellQuote(dev.path)}")
            val pts = File(dev, "pts")
            require(pts.isDirectory) { "宿主 /dev/pts 不存在，无法挂载终端设备" }
            mount(pts, "mount -t devpts devpts ${shellQuote(pts.path)}")
            config.mounts.forEach { binding ->
                val source = File(binding.source).canonicalFile
                val target = guestTarget(root, binding.target, source.isDirectory)
                mount(target, "mount -o bind ${shellQuote(source.path)} ${shellQuote(target.path)}", source.takeUnless { binding.readWrite })
            }
            rootCommand(owned.joinToString(" && ") { "mountpoint -q ${shellQuote(it.path)}" } + " || { echo 'su 挂载命名空间不共享，无法安全运行 chroot' >&2; exit 1; }")
            val user = loginIdentity(config)
            prepareChrootOwnership(config, root, user.uid, user.gid, user.name, loginHome(config, user), ::rootCommand)
        } catch (failure: Throwable) {
            try { unmount(config.id) } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    fun unmount(id: String) {
        val owned = ownedMounts[id] ?: return
        val failures = mutableListOf<String>()
        owned.toList().asReversed().forEach { target ->
            try {
                rootCommand("if mountpoint -q ${shellQuote(target.path)}; then umount -l ${shellQuote(target.path)}; fi")
                owned.remove(target)
            } catch (failure: Throwable) { failures += failure.message.orEmpty() }
        }
        if (owned.isEmpty()) ownedMounts.remove(id)
        check(failures.isEmpty()) { "安全卸载未完成：${failures.joinToString("；")}" }
    }

    fun builder(config: ContainerConfig, arguments: List<String>, cwd: String, environment: Map<String, String>, mergeError: Boolean, rootPidFile: File? = null): ProcessBuilder {
        require(arguments.isNotEmpty() && arguments.none { '\u0000' in it }) { "容器启动参数无效" }
        require(cwd.startsWith('/') && '\u0000' !in cwd && !cwd.split('/').contains("..")) { "工作目录必须是容器内绝对路径" }
        val root = validateRoot(config)
        val user = loginIdentity(config)
        val env = linkedMapOf("HOME" to loginHome(config, user), "USER" to user.name, "LOGNAME" to user.name, "TERM" to "xterm-256color", "LANG" to "C.UTF-8", "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
        env.putAll(config.environment)
        environment.forEach { (key, value) -> require(key.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) && '\u0000' !in value) { "进程环境变量无效：$key" }; env[key] = value }
        env["PATH"] = env.getValue("HOME").trimEnd('/') + "/.wawlink/bin:" + env.getValue("PATH")
        // Proroot installs its path hooks in the guest environment; env -i would erase them
        // before the shell exec. PRoot/chroot do not depend on inherited guest LD_* state.
        val guest = listOf("/usr/bin/env") + (if (config.engine == ContainerEngineType.PROROOT) emptyList() else listOf("-i")) + env.map { "${it.key}=${it.value}" } + listOf("/bin/sh", "-c", "cd ${shellQuote(cwd)} || exit; exec \"\$@\"", "wawlink") + arguments
        val command = when (config.engine) {
            ContainerEngineType.PROOT, ContainerEngineType.PROROOT -> buildList {
                add(if (config.engine == ContainerEngineType.PROOT) proot() else nativeBinary("libproroot.so"))
                addAll(listOf("-r", root.path, "-w", cwd))
                if (user.uid == "0") add("-0") else addAll(listOf("-i", "${user.uid}:${user.gid}"))
                if (config.link2symlink) add("--link2symlink")
                if (config.sysvipc) add("--sysvipc")
                if (config.fakeKernel) addAll(listOf("-k", "6.8.0-generic"))
                if (config.engine == ContainerEngineType.PROOT && guestArch(config.arch) != hostArch()) addAll(listOf("-q", paths.qemuExecutable!!))
                // The pinned proroot launcher requires host:guest even for identity binds.
                // Explicit pairs are also accepted by PRoot; never emit its host-only shorthand.
                listOf("/dev", "/proc", "/sys").filter { File(it).exists() }.forEach { addAll(listOf("-b", "$it:$it")) }
                if (!paths.isAndroid && File("/etc/resolv.conf").isFile) addAll(listOf("-b", "/etc/resolv.conf:/etc/resolv.conf"))
                config.mounts.forEach { addAll(listOf("-b", "${File(it.source).canonicalPath}:${it.target}")) }
                addAll(guest)
            }
            ContainerEngineType.CHROOT -> {
                val sharedGroupCheck = if (user.uid == "0") "" else "case \" \$(id -G) \" in *\" ${ChrootHostIdentity.gid} \"*) ;; *) echo 'chroot 登录身份缺少宿主共享组' >&2; exit 125;; esac; "
                val identityGuest = listOf("/bin/sh", "-c", "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; export PATH; umask 0007; " + sharedGroupCheck + "exec \"\$@\"", "wawlink") + guest
                val inner = if (user.uid == "0") identityGuest else listOf("/bin/su", "-s", "/bin/sh", user.name, "-c", "exec " + identityGuest.joinToString(" ", transform = ::shellQuote))
                val identityFile = requireNotNull(rootPidFile) { "chroot 启动缺少安全 PID 握手文件" }
                val acknowledgement = shellQuote(identityFile.path + ".ack")
                val handshake = """
                    { printf '%s\n' "${'$'}${'$'}"; cat /proc/${'$'}${'$'}/stat; } > ${shellQuote(identityFile.path)} || exit
                    tries=0
                    while [ ! -f $acknowledgement ]; do
                        tries=${'$'}((tries + 1)); [ "${'$'}tries" -le 100 ] || exit 125
                        sleep 0.1
                    done
                    rm -f $acknowledgement
                """.trimIndent() + "\n"
                val mountCheck = ownedMounts[config.id].orEmpty().joinToString(" && ") { "mountpoint -q ${shellQuote(it.path)}" }
                require(mountCheck.isNotEmpty()) { "chroot 尚未完成系统挂载" }
                listOf("su", "-c", mountCheck + " || { echo 'su 挂载命名空间不共享，拒绝启动 chroot' >&2; exit 125; };\n" + handshake + "exec chroot ${shellQuote(root.path)} " + inner.joinToString(" ", transform = ::shellQuote))
            }
        }
        return ProcessBuilder(command).directory(paths.dataDir).redirectErrorStream(mergeError).also { hostEnvironment(it, config.engine) }
    }

    private fun hostEnvironment(builder: ProcessBuilder, engine: ContainerEngineType) {
        val env = builder.environment()
        env.remove("LD_PRELOAD")
        if (paths.isAndroid) {
            val native = paths.nativeLibraryDir ?: error("缺少 Android 原生库目录")
            // Keep Android's library search path out of the retained guest glibc hooks.
            if (engine == ContainerEngineType.PROROOT) env.remove("LD_LIBRARY_PATH")
            else env["LD_LIBRARY_PATH"] = if (engine == ContainerEngineType.PROOT) prootLibraryPath else native.path
            if (engine == ContainerEngineType.PROOT) {
                env["PROOT_LOADER"] = nativeBinary("libproot_loader.so")
            }
            if (engine == ContainerEngineType.PROROOT) {
                env["PROROOT_TMP_DIR"] = File(paths.dataDir, "tmp").apply { mkdirs() }.path
                env["PROROOT_LIB_PATH"] = nativeBinary("libproroot-runtime.so")
                env["PROROOT_LINKER_PATH"] = nativeBinary("libproroot-linker.so")
            }
            env["PROOT_TMP_DIR"] = File(paths.dataDir, "tmp").apply { mkdirs() }.path
        } else {
            env["QEMU_CPU"] = "max"
            env["PROOT_NO_SECCOMP"] = "1"
            val binary = File(paths.hostProot)
            if (binary.isAbsolute) {
                val lib = File(binary.parentFile.parentFile, "lib")
                val directories = lib.listFiles().orEmpty().filter { it.isDirectory && it.name.endsWith("-linux-gnu") }
                if (directories.isNotEmpty()) env["LD_LIBRARY_PATH"] = (directories.map { it.path } + listOfNotNull(env["LD_LIBRARY_PATH"])).joinToString(":")
            }
        }
    }

    fun rootSnapshot(pids: List<Long>): List<String> {
        if (pids.isEmpty()) return emptyList()
        require(pids.all { it > 0 }) { "进程 PID 无效" }
        return rootCommand("""
            queue="${pids.joinToString(" ")}"
            seen=" "
            while [ -n "${'$'}queue" ]; do
                pending="${'$'}queue"; queue=""
                for pid in ${'$'}pending; do
                    case "${'$'}seen" in *" ${'$'}pid "*) continue ;; esac
                    seen="${'$'}seen${'$'}pid "
                    stat=${'$'}(cat "/proc/${'$'}pid/stat" 2>/dev/null)
                    if [ -n "${'$'}stat" ]; then
                        rest=${'$'}{stat##*) }
                        printf '%s () %s\n' "${'$'}pid" "${'$'}rest"
                        for children in /proc/${'$'}pid/task/*/children; do
                            childpids=""
                            if [ -r "${'$'}children" ]; then
                                IFS= read -r childpids 2>/dev/null < "${'$'}children" || true
                                queue="${'$'}queue ${'$'}childpids"
                            elif [ -e "${'$'}children" ]; then
                                echo '无法安全读取 Root 子进程' >&2; exit 1
                            fi
                        done
                    elif [ -e "/proc/${'$'}pid/stat" ]; then
                        echo '无法安全读取已跟踪的 Root 进程' >&2; exit 1
                    fi
                done
            done
        """.trimIndent()).lineSequence().filter { it.isNotBlank() }.toList()
    }

    fun rootCommand(command: String): String = try { runRootCommand(command) }
    catch (failure: Throwable) { throw IllegalStateException("chroot Root 命令执行失败：${failure.message}", failure) }
}
