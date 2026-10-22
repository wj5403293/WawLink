package dev.wawlink.app

import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.wawlink.driver.OmpProcessPool
import dev.wawlink.protocol.AttachmentFile
import dev.wawlink.runtime.*
import java.io.File
import java.util.UUID
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext

fun main() = application {
    val app = remember {
        fun override(property: String, environment: String): String? = System.getProperty(property)?.takeIf { it.isNotBlank() } ?: System.getenv(environment)?.takeIf { it.isNotBlank() }
        val project = File(System.getProperty("wawlink.projectDir", System.getProperty("user.dir")))
        val data = override("wawlink.dataDir", "WAWLINK_DATA_DIR")?.let(::File) ?: File(project, "build/user-data")
        val tools = override("wawlink.toolsDir", "WAWLINK_TOOLS_DIR")?.let(::File) ?: File(project, "build/host-tools/usr/bin")
        val rootfs = override("wawlink.rootfs", "WAWLINK_ROOTFS")?.let(::File) ?: File(project, "build/debug-rootfs/ubuntu-main")
        val proot = override("wawlink.proot", "WAWLINK_PROOT") ?: File(tools, "proot").takeIf { it.canExecute() }?.absolutePath ?: "proot"
        val qemu = override("wawlink.qemu", "WAWLINK_QEMU") ?: if (System.getProperty("os.arch") in listOf("aarch64", "arm64")) null else File(tools, "qemu-aarch64-static").takeIf { it.canExecute() }?.absolutePath ?: "qemu-aarch64-static"
        val runtime = LocalContainerRuntime(RuntimePaths(data, hostProot = proot, qemuExecutable = qemu, debugRootfs = rootfs))
        AppCoordinator(runtime, OmpProcessPool(runtime, data), data)
    }
    val files = remember { DesktopHostFiles() }
    Window(onCloseRequest = { app.close(); exitApplication() }, title = "WawLink", state = rememberWindowState(width = 460.dp, height = 920.dp)) { WawLinkApp(app, files) }
}

private class DesktopHostFiles : HostFiles {
    override suspend fun pickAttachments(): List<AttachmentFile> = withContext(Dispatchers.Swing) {
        val chooser = JFileChooser().apply { dialogTitle = "选择附件"; isMultiSelectionEnabled = true; fileSelectionMode = JFileChooser.FILES_ONLY }
        if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) emptyList()
        else chooser.selectedFiles.map { file -> AttachmentFile(UUID.randomUUID().toString(), file.name, file.absolutePath, java.nio.file.Files.probeContentType(file.toPath()) ?: "application/octet-stream", file.length()) }
    }
    override suspend fun pickRootfs(): String? = withContext(Dispatchers.Swing) {
        val chooser = JFileChooser().apply { dialogTitle = "选择 rootfs 归档"; fileSelectionMode = JFileChooser.FILES_ONLY; fileFilter = FileNameExtensionFilter("Rootfs (*.gz, *.xz, *.zst, *.tar)", "gz", "xz", "zst", "tar") }
        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.absolutePath else null
    }
    override suspend fun exportRootfs(runtime: ContainerRuntime, container: ContainerConfig): Boolean {
        val destination = withContext(Dispatchers.Swing) {
            val chooser = JFileChooser().apply { dialogTitle = "导出容器归档"; selectedFile = File("${container.name}.tar.zst") }
            if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
                val file = chooser.selectedFile
                if (file.exists() && javax.swing.JOptionPane.showConfirmDialog(null, "文件已存在，是否替换？", "确认替换", javax.swing.JOptionPane.YES_NO_OPTION) != javax.swing.JOptionPane.YES_OPTION) null else file
            } else null
        } ?: return false
        withContext(Dispatchers.IO) { runtime.exportRootfs(container.id, destination.absolutePath) }
        return true
    }
}
