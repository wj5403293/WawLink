package dev.wawlink.runtime

/**
 * Commands shared by the guest apt callers. Repairing dpkg before entering the
 * block makes an interrupted transaction fail with its real repair error and
 * prevents apt from hiding it behind exit status 100.
 */
internal object PackageManagerCommands {
    fun apt(command: String): String {
        require(command.isNotBlank()) { "apt 命令不能为空" }
        return """
            export DEBIAN_FRONTEND=noninteractive
            dpkg --configure -a && {
            ${command.trimIndent()}
            }
        """.trimIndent()
    }

    fun toolchain(manager: String, packages: String, install: Boolean): String = when (manager) {
        "apt" -> apt(if (install) "apt-get update && apt-get install -y $packages" else """
            selected=''
            for package in $packages; do
                if dpkg-query -W -f='${'$'}{Status}' "${'$'}package" 2>/dev/null | grep -q '^install ok installed${'$'}'; then selected="${'$'}selected ${'$'}package"; fi
            done
            if [ -n "${'$'}selected" ]; then DEBIAN_FRONTEND=noninteractive apt-get purge -y ${'$'}selected && DEBIAN_FRONTEND=noninteractive apt-get autoremove -y; fi
        """.trimIndent())
        "apk" -> "apk ${if (install) "add --no-cache" else "del"} $packages"
        "pacman" -> "pacman ${if (install) "-Syu --needed" else "-Rns"} --noconfirm $packages"
        "dnf" -> "dnf ${if (install) "install" else "remove"} -y $packages"
        else -> error("不支持的软件包管理器")
    }

    fun clean(manager: String): String = when (manager) {
        "apt" -> apt("apt-get clean")
        "apk" -> "apk cache clean"
        "pacman" -> "pacman -Scc --noconfirm"
        "dnf" -> "dnf clean all"
        else -> error("不支持的软件包管理器")
    }
}
