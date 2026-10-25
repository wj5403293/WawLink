package dev.wawlink.app

import androidx.compose.runtime.*
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.window.AwtWindow
import androidx.compose.ui.window.application
import dev.wawlink.driver.OmpProcessPool
import dev.wawlink.protocol.AttachmentFile
import dev.wawlink.runtime.*
import java.io.File
import java.util.UUID
import java.awt.Dimension
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.JFrame
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun main() {
    // ComposeWindow blending is unavailable on Linux; SwingGraphics supports clipped WebKit interop.
    System.setProperty("compose.interop.blending", "true")
    System.setProperty("compose.swing.render.on.graphics", "true")
    application {
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
    AwtWindow(create = {
        JFrame("WawLink").apply {
            defaultCloseOperation = JFrame.DO_NOTHING_ON_CLOSE
            contentPane = ComposePanel().apply {
                preferredSize = Dimension(460, 920)
                setContent { WawLinkApp(app, files) }
            }
            addWindowListener(object : WindowAdapter() {
                override fun windowClosing(event: WindowEvent) { exitApplication() }
            })
            pack()
            setLocationRelativeTo(null)
        }
    }, dispose = { window ->
        app.close()
        (window.contentPane as ComposePanel).dispose()
        window.dispose()
    })
    }
}

private class DesktopHostFiles : HostFiles {
    override suspend fun pickMountDirectory(): String? = withContext(Dispatchers.Swing) {
        val chooser = JFileChooser().apply { dialogTitle = "选择主机挂载目录"; fileSelectionMode = JFileChooser.DIRECTORIES_ONLY }
        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.absolutePath else null
    }
    override suspend fun prepareMountSource(source: String, readWrite: Boolean): String = withContext(Dispatchers.IO) {
        validateHostMountSource(source, readWrite)
    }
    override suspend fun pickImportFiles(): List<HostImportFile> = withContext(Dispatchers.Swing) {
        val chooser = JFileChooser().apply { dialogTitle = "导入到容器"; isMultiSelectionEnabled = true; fileSelectionMode = JFileChooser.FILES_ONLY }
        if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) emptyList()
        else chooser.selectedFiles.map { file -> HostImportFile(file.name) { file.inputStream() } }
    }
    override suspend fun saveFile(name: String, mimeType: String, write: suspend (java.io.OutputStream) -> Unit): Boolean {
        val target = withContext(Dispatchers.Swing) {
            val chooser = JFileChooser().apply { dialogTitle = "保存文件"; selectedFile = File(name) }
            if (chooser.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) null
            else chooser.selectedFile.takeIf { !it.exists() || javax.swing.JOptionPane.showConfirmDialog(null, "文件已存在，是否替换？", "确认替换", javax.swing.JOptionPane.YES_NO_OPTION) == javax.swing.JOptionPane.YES_OPTION }
        } ?: return false
        return withContext(Dispatchers.IO) {
            val temporary = File.createTempFile(".wawlink-", ".part", target.absoluteFile.parentFile)
            try {
                temporary.outputStream().use { write(it) }
                java.nio.file.Files.move(temporary.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                true
            } finally { temporary.delete() }
        }
    }
    override suspend fun openFile(name: String, mimeType: String, write: suspend (java.io.OutputStream) -> Unit) = withContext(Dispatchers.IO) {
        check(mimeType != "application/vnd.android.package-archive") { "桌面平台不支持安装 Android APK，请下载后在 Android 设备上安装" }
        val directory = java.nio.file.Files.createTempDirectory("wawlink-open-").toFile()
        val target = File(directory, hostFileName(name))
        try {
            target.outputStream().use { write(it) }
            if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.OPEN)) {
                java.awt.Desktop.getDesktop().open(target)
            } else {
                val opener = when {
                    System.getProperty("os.name").startsWith("Mac", true) -> listOf("open", target.absolutePath)
                    System.getProperty("os.name").startsWith("Windows", true) -> listOf("rundll32", "url.dll,FileProtocolHandler", target.absolutePath)
                    else -> listOf("xdg-open", target.absolutePath)
                }
                val process = ProcessBuilder(opener).redirectErrorStream(true).start()
                val message = process.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                check(process.waitFor() == 0) { message.ifBlank { "系统无法打开 $name" } }
            }
            // Native viewers can read after hand-off. Keep this app-owned temporary file available.
            directory.deleteOnExit(); target.deleteOnExit()
        } catch (error: Exception) {
            target.delete(); directory.delete()
            throw error
        }
    }
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
