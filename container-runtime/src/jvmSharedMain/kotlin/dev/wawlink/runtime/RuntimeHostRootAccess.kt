package dev.wawlink.runtime

import java.io.File

/** Exact bytes are the ownership marker; never replace or remove a user's helper. */
internal fun synchronizeHostExec(
    config: ContainerConfig,
    root: File,
    enabled: Boolean,
    rootCommand: (String) -> String,
) {
    val file = File(root, "usr/local/bin/host-exec")
    val files = RootfsHostFiles(config, rootCommand)
    val expected = HOST_EXEC_SCRIPT.toByteArray(Charsets.UTF_8)
    val previous = files.read(file)
    if (previous != null && !previous.contentEquals(expected)) {
        check(!enabled) { "拒绝覆盖用户文件：${file.path}" }
        return
    }
    if (enabled) {
        if (previous == null) files.write(file, expected, null, defaultMode = 0x1ED)
    } else if (previous != null) {
        files.delete(file, RootfsHostFiles.revision(expected))
    }
}

internal val HOST_EXEC_SCRIPT: String = """
    #!/bin/sh
    unset LD_PRELOAD LD_LIBRARY_PATH LD_AUDIT LD_DEBUG LD_DEBUG_OUTPUT LD_PROFILE LD_PROFILE_OUTPUT LD_ORIGIN_PATH LD_ASSUME_KERNEL LD_BIND_NOW LD_BIND_NOT LD_DYNAMIC_WEAK LD_HWCAP_MASK LD_SHOW_AUXV LD_TRACE_LOADED_OBJECTS GLIBC_TUNABLES GCONV_PATH LOCPATH
    host_chroot=${'$'}(command -v chroot) || exit 127
    case "${'$'}host_chroot" in /*) ;; *) echo 'host-exec: chroot must be an absolute executable' >&2; exit 127 ;; esac
    exec "${'$'}host_chroot" /proc/1/root /system/bin/sh -c '
    cd / || exit
    PATH=/product/bin:/apex/com.android.runtime/bin:/apex/com.android.art/bin:/system_ext/bin:/system/bin:/system/xbin:/odm/bin:/vendor/bin
    ANDROID_ROOT=/system
    ANDROID_DATA=/data
    export PATH ANDROID_ROOT ANDROID_DATA
    if [ "${'$'}#" -gt 0 ]; then exec "${'$'}@"; fi
    exec /system/bin/sh -i
    ' host-exec "${'$'}@"
""".trimIndent() + "\n"
