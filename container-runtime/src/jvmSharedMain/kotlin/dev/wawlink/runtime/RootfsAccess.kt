package dev.wawlink.runtime

import java.io.File
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** Access follows the physical tree, not just a possibly uncommitted engine selection. */
internal object RootfsAccess {
    private val reservedRoots = setOf("/", "/home", "/root", "/usr", "/etc", "/var", "/tmp", "/dev", "/proc", "/sys", "/data", "/data/data", "/data/user", "/data/user/0")

    fun requiresRoot(config: ContainerConfig): Boolean {
        val root = File(config.rootfsPath)
        val ownership = RootfsOwnership.load(root)
        if (ownership?.physical == ContainerEngineType.CHROOT || ownership?.pending != null) return true
        if (ownership == null && config.engine == ContainerEngineType.CHROOT) return true
        return try {
            for (file in listOf(root, File(root, "etc"), File(root, "bin/sh"))) {
                Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
            }
            false
        } catch (_: AccessDeniedException) {
            true
        } catch (_: NoSuchFileException) {
            false
        } catch (_: NotDirectoryException) {
            false
        }
    }

    fun canonicalRoot(config: ContainerConfig, rootCommand: (String) -> String): File {
        val requested = File(config.rootfsPath)
        require(requested.isAbsolute && '\u0000' !in requested.path) { "rootfs 必须是绝对目录路径" }
        val lexical = requested.absoluteFile.normalize()
        require(lexical.path !in reservedRoots) { "拒绝将宿主系统目录作为 rootfs" }
        val canonical = if (requiresRoot(config)) privilegedRoot(lexical, rootCommand) else try {
            localRoot(lexical)
        } catch (_: AccessDeniedException) {
            privilegedRoot(lexical, rootCommand)
        } catch (failure: FileSystemException) {
            throw filesystemFailure("Rootfs 根目录 ${lexical.path}", failure)
        }
        require(canonical.isAbsolute && canonical.path !in reservedRoots) { "拒绝将宿主系统目录作为 rootfs" }
        return canonical
    }

    fun validateRoot(config: ContainerConfig, rootCommand: (String) -> String): File {
        val root = canonicalRoot(config, rootCommand)
        if (requiresRoot(config)) {
            validatePrivilegedStructure(root, rootCommand)
        } else try {
            validateLocalEntry(root.toPath(), "etc", directory = true)
            validateLocalEntry(root.toPath(), "bin/sh", directory = false)
        } catch (_: AccessDeniedException) {
            validatePrivilegedStructure(root, rootCommand)
        } catch (failure: FileSystemException) {
            throw filesystemFailure(failure.file ?: root.path, failure)
        }
        return root
    }

    private fun localRoot(root: File): File {
        val attributes = Files.readAttributes(root.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        require(!attributes.isSymbolicLink && attributes.isDirectory) { "rootfs 必须是实际目录，不能是符号链接：${root.path}" }
        return root.toPath().toRealPath().toFile()
    }

    private fun validateLocalEntry(root: Path, name: String, directory: Boolean) {
        val file = root.resolve(name)
        val attributes = Files.readAttributes(file, BasicFileAttributes::class.java)
        require(if (directory) attributes.isDirectory else attributes.isRegularFile) {
            "rootfs 校验失败：/$name ${if (directory) "不是目录" else "不是普通文件"}"
        }
        require(file.toRealPath().startsWith(root)) { "rootfs 校验失败：/$name 通过链接越出 Rootfs" }
    }

    private fun privilegedRoot(root: File, rootCommand: (String) -> String): File {
        val output = rootCommand("""
            set -eu
            $ROOT_ID_CHECK
            root=${shellQuote(root.path)}
            if mode=${'$'}(stat -c '%f' -- "${'$'}root" 2>&1); then :
            else printf 'rootfs 根目录校验失败：%s\n' "${'$'}mode" >&2; exit 1; fi
            case "${'$'}mode" in 4*) ;; *) echo 'rootfs 必须是实际目录，不能是符号链接' >&2; exit 1;; esac
            readlink -f -- "${'$'}root"
        """.trimIndent())
        val path = output.removeSuffix("\n")
        require(path.startsWith('/') && '\u0000' !in path) { "Root 返回了无效的 Rootfs 路径" }
        return File(path)
    }

    private fun validatePrivilegedStructure(root: File, rootCommand: (String) -> String) {
        rootCommand("""
            set -eu
            $ROOT_ID_CHECK
            root=${shellQuote(root.path)}
            check_entry() {
                name=${'$'}1; expected=${'$'}2
                if mode=${'$'}(stat -L -c '%f' -- "${'$'}root/${'$'}name" 2>&1); then :
                else printf 'rootfs 校验失败：/%s：%s\n' "${'$'}name" "${'$'}mode" >&2; exit 1; fi
                case "${'$'}mode" in "${'$'}expected"*) ;; *) printf 'rootfs 校验失败：/%s 类型错误\n' "${'$'}name" >&2; exit 1;; esac
                if resolved=${'$'}(readlink -f -- "${'$'}root/${'$'}name" 2>&1); then :
                else printf 'rootfs 校验失败：/%s：%s\n' "${'$'}name" "${'$'}resolved" >&2; exit 1; fi
                case "${'$'}resolved" in "${'$'}root"/*) ;; *) printf 'rootfs 校验失败：/%s 通过链接越出 Rootfs\n' "${'$'}name" >&2; exit 1;; esac
            }
            check_entry etc 4
            check_entry bin/sh 8
        """.trimIndent())
    }

    private fun filesystemFailure(path: String, failure: FileSystemException): IllegalArgumentException {
        val reason = when (failure) {
            is AccessDeniedException -> "权限拒绝"
            is NoSuchFileException -> "路径不存在或链接目标缺失"
            is NotDirectoryException -> "父路径不是目录"
            else -> "文件系统访问失败"
        }
        return IllegalArgumentException("rootfs 校验失败：$path：$reason：${failure.reason ?: failure.message}", failure)
    }

    private const val ROOT_ID_CHECK = "uid=\$(id -u); [ \"\$uid\" = 0 ] || { printf 'rootfs 校验需要真实 Root，实际 UID=%s\\n' \"\$uid\" >&2; exit 1; }"
}
