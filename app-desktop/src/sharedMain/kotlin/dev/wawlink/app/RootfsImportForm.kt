@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.wawlink.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.wawlink.runtime.*
import dev.wawlink.ui.Symbol
import kotlinx.coroutines.*

@Composable
internal fun RootfsImportForm(app: AppCoordinator, files: HostFiles, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val leave by rememberUpdatedState(onBack)
    var nameHadFocus by remember { mutableStateOf(false) }
    val controller = remember(app.runtime) {
        RootfsImportController(app.runtime, scope) { config, generation ->
            // Import does not authorize a later intentional stop to launch OMP work.
            if (generation == null || !app.runtime.wasInterrupted(config.id, generation)) {
                try { app.checkOmpInstallation(config.id) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { app.showError(failure) }
            }
            leave()
        }
    }
    val state by controller.state.collectAsState()
    val draft = state.draft
    LaunchedEffect(controller) {
        try { awaitCancellation() }
        finally {
            withContext(NonCancellable) {
                try { controller.close() }
                catch (failure: Exception) { app.showError(failure) }
            }
        }
    }

    if (!state.committed) {
        SectionLabel("运行引擎", "deployed_code")
        HostChips(ContainerEngineType.entries, draft.engine, { it.name.lowercase() }, { value -> controller.edit { it.copy(engine = value) } }, "deployed_code", enabled = { !state.busy && (app.isAndroid || it != ContainerEngineType.PROROOT) })
        if (draft.engine == ContainerEngineType.CHROOT) Text("chroot 需要系统 Root 权限。无权限时会显示实际错误，请改用 proot。", color = MaterialTheme.colorScheme.error)
        if (!app.isAndroid) Text("proroot 仅支持 Android；桌面请使用 proot 或有 Root 权限的 chroot。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        SectionLabel("来源方式", "folder_zip")
        HostChips(listOf("本地文件", "网络地址"), draft.sourceType, { it }, { value -> controller.edit { it.copy(sourceType = value) } }, enabled = { !state.busy })
        if (draft.sourceType == "本地文件") {
            HostRow(if (draft.source.isBlank()) "选择 rootfs 归档" else java.io.File(draft.source).name, ".tar.gz / .tar.xz / .tar.zst", "folder_zip", {
                if (!state.busy) scope.launch { files.pickRootfs()?.let { path -> controller.edit { it.copy(source = path) } } }
            }) { Symbol("folder") }
        } else HostField("下载链接", draft.url, { value -> controller.edit { it.copy(url = value) } })
        HostField("容器名称", draft.name, { value -> controller.edit { it.copy(name = value) } }, Modifier.onFocusChanged { focus ->
            val hasFocus = focus.hasFocus
            if (nameHadFocus && !hasFocus) controller.checkNameConflict()
            nameHadFocus = hasFocus
        })
        HostField("SHA256（可选）", draft.checksum, { value -> controller.edit { it.copy(checksum = value) } })
    } else {
        Text("容器已导入；重试只会完成配置，不会重复导入归档。", Modifier.padding(vertical = 12.dp))
    }
    SectionLabel("系统类型（仅新建容器）", "deployed_code")
    HostChips(listOf("Ubuntu", "Debian", "Alpine", "Arch", "Fedora", "Kali"), draft.family, { it }, { value -> controller.edit { it.copy(family = value) } }, enabled = { !state.busy })
    SectionLabel("默认 Shell（仅新建容器）", "terminal")
    HostChips(listOf("/bin/bash", "/bin/zsh", "/bin/sh"), draft.shell, { it.substringAfterLast('/').replaceFirstChar(Char::uppercase) }, { value -> controller.edit { it.copy(shell = value) } }, "terminal", enabled = { !state.busy })
    if (state.busy) {
        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 20.dp))
        Text(when (state.phase) {
            RootfsImportPhase.COMMITTING -> "正在导入已校验的归档…"
            RootfsImportPhase.FINALIZING -> "正在完成容器配置…"
            RootfsImportPhase.CANCELLING -> "正在取消并清理导入缓存…"
            else -> "正在校验归档，请稍候…"
        }, Modifier.padding(vertical = 8.dp))
        if (state.phase == RootfsImportPhase.PREPARING || state.phase == RootfsImportPhase.COMMITTING) {
            TextButton(controller::cancel) { Text("取消导入") }
        }
    }
    state.error?.let { Text(it, Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.error) }
    HostAction(if (state.committed) "重试完成导入" else "校验并导入容器", "folder_zip", controller::submit, Modifier.fillMaxWidth().padding(top = 28.dp), enabled = draft.canSubmit && !state.busy && state.phase != RootfsImportPhase.COMPLETE)
    if (state.phase == RootfsImportPhase.DECISION) RootfsImportConflictDialog(state, controller)
}

@Composable
internal fun RootfsImportConflictDialog(state: RootfsImportUiState, controller: RootfsImportController) {
    val renameError = controller.renameError()
    val sourceReady = state.inspection != null || state.draft.archiveSource.isNotBlank()
    AlertDialog(
        onDismissRequest = controller::cancel,
        title = { Text("容器名称冲突") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("输入名称：${state.draft.name.trim()}")
                if (!sourceReady) Text("请取消此对话框，先选择归档或填写下载链接；当前不会读取归档或修改现有容器。", color = MaterialTheme.colorScheme.error)
                val inspection = state.inspection
                if (inspection == null) Text("尚未读取归档。选择操作后先校验归档，再确认归档标题和版本；此时不会修改现有容器。")
                else {
                    Text("归档标题：${inspection.metadata.title.ifBlank { inspection.name }}", fontWeight = FontWeight.SemiBold)
                    if (inspection.metadata.version.isNotBlank()) Text("归档容器版本：${inspection.metadata.version}")
                }
                Text(if (state.conflicts.size > 1) "名称和归档标题匹配了多个容器，请明确选择一个更新目标：" else "匹配的现有容器：")
                state.conflicts.forEach { config ->
                    Row(Modifier.fillMaxWidth().selectable(selected = state.selectedTargetId == config.id, role = Role.RadioButton) { controller.selectTarget(config.id) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = state.selectedTargetId == config.id, onClick = null)
                        Column(Modifier.padding(start = 8.dp)) {
                            Text(config.displayName, fontWeight = FontWeight.SemiBold)
                            if (config.name != config.displayName) Text("名称：${config.name}")
                            Text("发行版：${config.distro} ${config.version}")
                            if (config.metadata.version.isNotBlank()) Text("现有容器版本：${config.metadata.version}")
                        }
                    }
                }
                Text("覆盖更新保留工作区、会话、凭据及现有配置；归档中的普通文件会被覆盖，归档中没有的本地新增文件会保留。未受保护的文件仍可能包含个人数据。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(state.rename, controller::rename, Modifier.fillMaxWidth(), label = { Text("新容器名称") }, singleLine = true, isError = state.rename.isNotBlank() && renameError != null,
                    supportingText = { Text(renameError ?: "重命名会同时设置容器名称和归档标题") })
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                TextButton(controller::updateExisting, enabled = sourceReady && state.selectedTargetId != null) { Text("覆盖更新现有容器") }
                TextButton(controller::importRenamed, enabled = sourceReady && renameError == null) { Text("重命名并作为新容器导入") }
                TextButton(controller::cancel) { Text("取消") }
            }
        },
    )
}
