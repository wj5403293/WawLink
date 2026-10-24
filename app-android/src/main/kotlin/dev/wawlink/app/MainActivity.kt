package dev.wawlink.app

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
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
    private var installPermissionResult: CancellableContinuation<Unit>? = null
    private var mountDirectoryResult: CancellableContinuation<Uri?>? = null
    private var storageAccessResult: CancellableContinuation<Boolean>? = null
    private val pickHostDirectory = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        mountDirectoryResult?.let { continuation -> mountDirectoryResult = null; if (continuation.isActive) continuation.resume(uri) }
    }
    private val allowSharedStorage = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { finishStorageAccessRequest() }
    private val legacyStoragePermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { finishStorageAccessRequest() }
    private val allowPackageInstallation = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        installPermissionResult?.let { continuation -> installPermissionResult = null; if (continuation.isActive) continuation.resume(Unit) }
    }
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
            // Pages own the top inset so the chat app bar can paint behind the status bar.
            androidx.compose.foundation.layout.Box(
                androidx.compose.ui.Modifier.fillMaxSize().windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
                )
            ) { WawLinkApp(app, this@MainActivity, navigation) }
        }
        if (Build.VERSION.SDK_INT >= 33 && savedInstanceState == null) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    override suspend fun pickMountDirectory(): String? {
        requireSharedStorageAccess()
        val uri = withContext(Dispatchers.Main.immediate) {
            check(mountDirectoryResult == null) { "主机目录选择器已打开" }
            suspendCancellableCoroutine<Uri?> { continuation ->
                mountDirectoryResult = continuation
                continuation.invokeOnCancellation { mountDirectoryResult = null }
                try { pickHostDirectory.launch(null) }
                catch (failure: Exception) { mountDirectoryResult = null; throw failure }
            }
        } ?: return null
        return withContext(Dispatchers.IO) {
            require(uri.scheme == "content" && uri.authority == "com.android.externalstorage.documents" && DocumentsContract.isTreeUri(uri)) {
                "只能挂载本机共享储存中的实际目录；云盘或其他文档提供方不能直接挂载"
            }
            val source = storageTreeMountSource(DocumentsContract.getTreeDocumentId(uri), storageRoots())
            check(File(source).isDirectory) { "所选主机目录不存在或不可访问：$source" }
            validateHostMountSource(source, readWrite = false)
        }
    }
    override suspend fun prepareMountSource(source: String, readWrite: Boolean): String {
        if (withContext(Dispatchers.IO) { isSharedStorageSource(source) }) requireSharedStorageAccess()
        return withContext(Dispatchers.IO) { validateHostMountSource(source, readWrite) }
    }
    private fun hasSharedStorageAccess(): Boolean = if (Build.VERSION.SDK_INT >= 30) {
        Environment.isExternalStorageManager()
    } else {
        checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }
    private fun finishStorageAccessRequest() {
        storageAccessResult?.let { continuation ->
            storageAccessResult = null
            if (continuation.isActive) continuation.resume(hasSharedStorageAccess())
        }
    }
    private suspend fun requireSharedStorageAccess() = withContext(Dispatchers.Main.immediate) {
        if (hasSharedStorageAccess()) return@withContext
        check(storageAccessResult == null) { "储存权限授权页面已打开" }
        val allowed = suspendCancellableCoroutine<Boolean> { continuation ->
            storageAccessResult = continuation
            continuation.invokeOnCancellation { storageAccessResult = null }
            try {
                if (Build.VERSION.SDK_INT >= 30) {
                    try {
                        allowSharedStorage.launch(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
                    } catch (_: ActivityNotFoundException) {
                        allowSharedStorage.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
                } else {
                    legacyStoragePermissions.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
                }
            } catch (failure: Exception) { storageAccessResult = null; throw failure }
        }
        check(allowed) {
            if (Build.VERSION.SDK_INT >= 30) "尚未授予“所有文件访问权限”，无法挂载共享储存目录；请授权后重试"
            else "尚未授予储存读写权限，无法挂载共享储存目录；请授权后重试"
        }
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
    override suspend fun openFile(name: String, mimeType: String, write: suspend (java.io.OutputStream) -> Unit) {
        val apk = mimeType == "application/vnd.android.package-archive"
        if (apk && !packageManager.canRequestPackageInstalls()) {
            withContext(Dispatchers.Main.immediate) {
                check(installPermissionResult == null) { "安装来源授权页面已打开" }
                suspendCancellableCoroutine<Unit> { continuation ->
                    installPermissionResult = continuation
                    continuation.invokeOnCancellation { installPermissionResult = null }
                    try {
                        allowPackageInstallation.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                    } catch (error: Exception) { installPermissionResult = null; throw error }
                }
            }
            check(packageManager.canRequestPackageInstalls()) { "尚未允许 WawLink 安装 APK；文件未安装，可重试或下载" }
        }
        val directory = withContext(Dispatchers.IO) {
            val cache = File(cacheDir, "container-open").apply { check(isDirectory || mkdirs()) { "无法创建文件缓存" } }
            File(cache, UUID.randomUUID().toString()).apply { check(mkdir()) { "无法创建文件缓存" } }
        }
        val target = File(directory, hostFileName(name))
        try {
            withContext(Dispatchers.IO) { target.outputStream().use { write(it) } }
            withContext(Dispatchers.Main.immediate) {
                val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.fileprovider", target)
                val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeType).apply {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    clipData = ClipData.newRawUri(name, uri)
                    putExtra(Intent.EXTRA_TITLE, name)
                }
                // The system package installer always owns APK confirmation; no package is silently installed.
                startActivity(intent)
            }
            // Keep the cache alive after hand-off: the viewer/installer may read asynchronously.
        } catch (error: Exception) {
            withContext(NonCancellable + Dispatchers.IO) { target.delete(); directory.delete() }
            throw error
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
    private fun isSharedStorageSource(source: String): Boolean {
        val roots = listOf(File("/sdcard"), File("/mnt/sdcard"), File("/storage"), Environment.getExternalStorageDirectory()).map { it.toPath() }
        val file = File(source)
        val supplied = file.toPath().toAbsolutePath().normalize()
        if (roots.any { supplied.startsWith(it) }) return true
        val actual = file.canonicalFile.toPath()
        return roots.any { actual.startsWith(it) }
    }
    private fun storageRoots(): Map<String, File> {
        val roots = linkedMapOf("primary" to Environment.getExternalStorageDirectory())
        val manager = getSystemService(StorageManager::class.java) ?: error("系统未提供储存卷服务")
        if (Build.VERSION.SDK_INT >= 30) {
            manager.storageVolumes.forEach { volume ->
                val id = if (volume.isPrimary) "primary" else volume.uuid ?: return@forEach
                volume.directory?.let { roots[id] = it }
            }
        } else {
            getExternalFilesDirs(null).filterNotNull().forEach { directory ->
                val id = manager.getStorageVolume(directory)?.uuid ?: return@forEach
                val root = directory.parentFile?.parentFile?.parentFile?.parentFile ?: return@forEach
                roots[id] = root
            }
        }
        return roots
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
