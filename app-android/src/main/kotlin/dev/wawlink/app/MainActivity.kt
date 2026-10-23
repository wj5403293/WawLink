package dev.wawlink.app

import android.Manifest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.wawlink.protocol.AttachmentFile
import dev.wawlink.runtime.ContainerConfig
import dev.wawlink.runtime.ContainerRuntime
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.*

class MainActivity : ComponentActivity(), HostFiles {
    private val navigation = HostNavigation()
    private var attachmentResult: CancellableContinuation<List<Uri>>? = null
    private var importResult: CancellableContinuation<Uri?>? = null
    private var exportResult: CancellableContinuation<Uri?>? = null
    private var fileImportResult: CancellableContinuation<List<Uri>>? = null
    private var fileExportResult: CancellableContinuation<Uri?>? = null
    private val pickContainerFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> fileImportResult?.let { continuation -> fileImportResult = null; if (continuation.isActive) continuation.resume(uris) } }
    private val saveContainerFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri -> fileExportResult?.let { continuation -> fileExportResult = null; if (continuation.isActive) continuation.resume(uri) } }
    private val pickAttachments = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> attachmentResult?.let { continuation -> attachmentResult = null; if (continuation.isActive) continuation.resume(uris) } }
    private val pickArchive = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> importResult?.let { continuation -> importResult = null; if (continuation.isActive) continuation.resume(uri) } }
    private val saveArchive = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zstd")) { uri -> exportResult?.let { continuation -> exportResult = null; if (continuation.isActive) continuation.resume(uri) } }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (!allowed) (application as WawLinkApplication).coordinator.report("通知权限未开启。开发环境仍可运行，但系统可能不在通知栏显示后台任务状态。")
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val app = (application as WawLinkApplication).coordinator
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (navigation.onBack?.invoke() != true) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
        setContent {
            val appearance by app.appearance.collectAsState()
            androidx.compose.runtime.SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !appearance.darkTheme
                    isAppearanceLightNavigationBars = !appearance.darkTheme
                }
            }
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.safeDrawing)) { WawLinkApp(app, this@MainActivity, navigation) }
        }
        if (Build.VERSION.SDK_INT >= 33 && savedInstanceState == null) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    override suspend fun pickImportFiles(): List<HostImportFile> {
        val uris = withContext(Dispatchers.Main.immediate) {
            check(fileImportResult == null) { "导入选择器已打开" }
            suspendCancellableCoroutine<List<Uri>> { continuation ->
                fileImportResult = continuation
                continuation.invokeOnCancellation { fileImportResult = null }
                pickContainerFiles.launch(arrayOf("*/*"))
            }
        }
        return withContext(Dispatchers.IO) { uris.map { uri ->
            HostImportFile(displayName(uri, preserveWhitespace = true)) { contentResolver.openInputStream(uri) ?: error("无法读取所选文档") }
        } }
    }
    override suspend fun saveFile(name: String, mimeType: String, write: suspend (java.io.OutputStream) -> Unit): Boolean {
        val uri = withContext(Dispatchers.Main.immediate) {
            check(fileExportResult == null) { "导出选择器已打开" }
            suspendCancellableCoroutine<Uri?> { continuation ->
                fileExportResult = continuation
                continuation.invokeOnCancellation { fileExportResult = null }
                saveContainerFile.launch(name)
            }
        } ?: return false
        return withContext(Dispatchers.IO) {
            try {
                val output = contentResolver.openOutputStream(uri, "wt") ?: error("无法写入所选文档")
                output.use { write(it) }
                true
            } catch (failure: Exception) { runCatching { contentResolver.delete(uri, null, null) }; throw failure }
        }
    }
    override suspend fun pickAttachments(): List<AttachmentFile> {
        val uris = withContext(Dispatchers.Main.immediate) {
            check(attachmentResult == null) { "文件选择器已打开" }
            suspendCancellableCoroutine { continuation ->
                attachmentResult = continuation
                continuation.invokeOnCancellation { attachmentResult = null }
                pickAttachments.launch(arrayOf("*/*"))
            }
        }
        return withContext(Dispatchers.IO) { uris.map { uri ->
            val original = displayName(uri)
            val copy = copyPrivate(uri, "attachments", original)
            AttachmentFile(UUID.randomUUID().toString(), original, copy.absolutePath, contentResolver.getType(uri) ?: "application/octet-stream", copy.length())
        } }
    }
    override suspend fun pickRootfs(): String? {
        val uri = withContext(Dispatchers.Main.immediate) {
            check(importResult == null) { "导入选择器已打开" }
            suspendCancellableCoroutine<Uri?> { continuation ->
                importResult = continuation
                continuation.invokeOnCancellation { importResult = null }
                pickArchive.launch(arrayOf("*/*"))
            }
        } ?: return null
        return withContext(Dispatchers.IO) { copyPrivate(uri, "imports", displayName(uri)).absolutePath }
    }
    override suspend fun exportRootfs(runtime: ContainerRuntime, container: ContainerConfig): Boolean {
        val uri = withContext(Dispatchers.Main.immediate) {
            check(exportResult == null) { "导出选择器已打开" }
            suspendCancellableCoroutine<Uri?> { continuation ->
                exportResult = continuation
                continuation.invokeOnCancellation { exportResult = null }
                saveArchive.launch("${container.name}.tar.zst")
            }
        } ?: return false
        return withContext(Dispatchers.IO) {
            val temporary = File(cacheDir, "${UUID.randomUUID()}.tar.zst")
            try {
                runtime.exportRootfs(container.id, temporary.absolutePath)
                val output = contentResolver.openOutputStream(uri, "wt") ?: error("无法写入所选文档")
                output.use { destination -> temporary.inputStream().use { it.copyTo(destination) } }
                true
            } finally { temporary.delete() }
        }
    }
    private fun displayName(uri: Uri, preserveWhitespace: Boolean = false): String = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }?.takeIf { if (preserveWhitespace) it.isNotEmpty() else it.isNotBlank() } ?: "document"
    private fun copyPrivate(uri: Uri, directory: String, name: String): File {
        val target = File(File((application as WawLinkApplication).coordinator.dataDir, directory).apply { mkdirs() }, "${UUID.randomUUID()}-${name.substringAfterLast('/').replace(Regex("[^\\p{L}\\p{N}._-]"), "_")}")
        try {
            val input = contentResolver.openInputStream(uri) ?: error("无法读取所选文档 $name")
            input.use { source -> target.outputStream().use { source.copyTo(it) } }
            return target
        } catch (e: Exception) { target.delete(); throw e }
    }
}
