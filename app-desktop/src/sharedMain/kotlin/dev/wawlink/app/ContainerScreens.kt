@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.wawlink.app

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wawlink.runtime.*
import dev.wawlink.ui.*
import kotlinx.coroutines.*

internal fun bytesLabel(bytes: Long): String = when {
    bytes >= (1L shl 30) -> "%.1f GB".format(bytes.toDouble() / (1L shl 30))
    bytes >= (1L shl 20) -> "%.1f MB".format(bytes.toDouble() / (1L shl 20))
    bytes >= 1024 -> "%.1f KB".format(bytes.toDouble() / 1024)
    else -> "$bytes B"
}
@Composable internal fun DistroAvatar(distro: String, round: Boolean = false) {
    val color = when (distro.lowercase()) { "ubuntu" -> Color(0xFFD9460F); "debian" -> Color(0xFFA80030); "alpine" -> Color(0xFF0D597F); "arch" -> Color(0xFF1793D1); "fedora" -> Color(0xFF294172); "kali" -> Color(0xFF268BEE); else -> MaterialTheme.colorScheme.primary }
    Box(Modifier.size(48.dp).background(color, RoundedCornerShape(if (round) 24.dp else 16.dp)), contentAlignment = Alignment.Center) { Text(distro.take(1).uppercase(), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
}
@Composable internal fun ContainersHome(app: AppCoordinator, onDetail: (String) -> Unit) {
    val containers by app.runtime.containers.collectAsState()
    val deployments by app.runtime.deployments.collectAsState()
    val sessions by app.sessions.collectAsState()
    val selectedContainerId by app.selectedContainerId.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("全部容器") }
    var filterMenu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 96.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("${containers.count { it.running }} / ${containers.size}", "运行中容器", "play_circle", Modifier.weight(1f)) { filter = if (filter == "运行中") "全部容器" else "运行中" }
            StatTile(bytesLabel(containers.sumOf { it.sizeBytes }), "清理包缓存", "hard_drive", Modifier.weight(1f)) { app.launch { val released = app.runtime.cleanPackageCaches(); app.report("已清理包缓存，释放 ${bytesLabel(released)}") } }
        }
        Row(Modifier.padding(top = 14.dp).fillMaxWidth().height(56.dp).background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(28.dp)).padding(start = 18.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Symbol("search")
            androidx.compose.foundation.text.BasicTextField(query, { query = it }, Modifier.weight(1f), singleLine = true, textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface, fontSize = 18.sp), decorationBox = { inner -> if (query.isBlank()) Text("搜索容器或发行版", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 18.sp); inner() })
            Box { AgentIconButton("filter_list", "筛选状态", { filterMenu = true }, filled = filter != "全部容器"); DropdownMenu(filterMenu, { filterMenu = false }) { listOf("全部容器", "运行中", "已停止").forEach { label -> DropdownMenuItem({ Text(label) }, { filter = label; filterMenu = false }, trailingIcon = { if (label == filter) Symbol("check") }) } } }
        }
        if (deployments.isNotEmpty()) {
            SectionLabel("正在部署", "deployed_code")
            deployments.forEach { task ->
                key(task.id) {
                    Column(Modifier.fillMaxWidth().padding(bottom = 3.dp).background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(20.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(task.name, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                        Text(task.error ?: task.phase, color = if (task.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        LinearProgressIndicator(progress = { task.progress.coerceIn(0f, 1f) }, Modifier.fillMaxWidth().height(6.dp))
                        Text("${(task.progress * 100).toInt()}%", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        val visible = containers.filter { c -> (filter != "运行中" || c.running) && (filter != "已停止" || !c.running) && "${c.config.name} ${c.config.distro} ${c.config.version}".contains(query.trim(), true) }
        SectionLabel("已安装容器", "dns", "${visible.size}")
        if (visible.isEmpty()) EmptyHost("没有匹配的容器", "deployed_code")
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            visible.forEach { c ->
                val selected = c.config.id == selectedContainerId
                val shape = RoundedCornerShape(20.dp)
                Column(Modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .border(if (selected) 3.dp else 0.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, shape)) {
                    Row(Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton) { app.launch { app.selectContainer(c.config.id) } }.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        DistroAvatar(c.config.distro, c.running)
                        Column(Modifier.weight(1f)) {
                            Text(c.config.name, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row(Modifier.padding(top = 4.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) { Meta("terminal", "${c.config.distro} ${c.config.version}"); Meta("hard_drive", bytesLabel(c.sizeBytes)); Meta("forum", "${sessions.count { it.record.containerId == c.config.id && !it.record.archived }}") }
                        }
                        AgentIconButton("more_vert", "${c.config.name} 容器设置", { onDetail(c.config.id) })
                        Switch(c.running, { run -> app.launch { if (run) app.runtime.start(c.config.id) else app.runtime.stop(c.config.id) } })
                    }
                    c.error?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}
@Composable private fun StatTile(value: String, label: String, icon: String, modifier: Modifier, action: () -> Unit) {
    Row(modifier.background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(24.dp)).clickable(onClick = action).padding(horizontal = 14.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(44.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) { Symbol(icon, tint = MaterialTheme.colorScheme.onPrimaryContainer) }
        Column { Text(value, fontSize = 21.sp, fontWeight = FontWeight.Bold); Text(label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable internal fun InstallContainerScreen(app: AppCoordinator, files: HostFiles, rootfs: Boolean, onBack: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(if (rootfs) "导入 rootfs" else "发行版库") }
    var query by rememberSaveable { mutableStateOf("") }
    var mirror by rememberSaveable { mutableStateOf(MirrorSource.TSINGHUA) }
    var engine by rememberSaveable { mutableStateOf(ContainerEngineType.PROOT) }
    var expanded by rememberSaveable { mutableStateOf<String?>(app.runtime.catalog.firstOrNull()?.id) }
    var sourceType by rememberSaveable { mutableStateOf("本地文件") }
    var source by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("custom-rootfs") }
    var family by rememberSaveable { mutableStateOf("Ubuntu") }
    var shell by rememberSaveable { mutableStateOf("/bin/bash") }
    var checksum by rememberSaveable { mutableStateOf("") }
    Column {
        AgentTopBar("添加容器", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 40.dp)) {
            HostChips(listOf("发行版库", "导入 rootfs"), tab, { it }, { tab = it })
            SectionLabel("运行引擎", "deployed_code")
            EngineSelector(engine, app.isAndroid) { engine = it }
            if (engine == ContainerEngineType.CHROOT) Text("chroot 需要系统 Root 权限。无权限时会显示实际错误，请改用 proot。", Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error, fontSize = 15.sp)
            if (tab == "发行版库") {
                HostField("搜索 Linux 发行版", query, { query = it })
                HostChips(MirrorSource.entries, mirror, { it.label }, { mirror = it }, "language", enabled = { expanded != "fedora" || it == MirrorSource.ALIYUN || it == MirrorSource.OFFICIAL })
                SectionLabel("可安装发行版", "explore")
                app.runtime.catalog.filter { "${it.name} ${it.version}".contains(query.trim(), true) }.forEach { distro ->
                    key(distro.id) {
                    var alias by rememberSaveable { mutableStateOf("${distro.id}-${distro.version.lowercase().replace(".", "")}") }
                    val open = expanded == distro.id
                    Column(Modifier.fillMaxWidth().padding(bottom = 3.dp).background(if (open) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(if (open) 26.dp else 20.dp)).animateContentSize()) {
                        Row(Modifier.fillMaxWidth().clickable {
                            expanded = if (open) null else distro.id
                            if (!open && distro.id == "fedora" && mirror != MirrorSource.ALIYUN && mirror != MirrorSource.OFFICIAL) {
                                mirror = MirrorSource.OFFICIAL
                                app.report("Fedora 41 不支持清华/中科大归档源，已明确改选官方源，也可选择阿里镜像。")
                            }
                        }.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            DistroAvatar(distro.name, open)
                            Column(Modifier.weight(1f)) { Text("${distro.name} ${distro.version}", fontSize = 19.sp, fontWeight = FontWeight.SemiBold); Text("${distro.arch} · ${mirror.label}", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            Symbol(if (open) "unfold_less" else "chevron_right")
                        }
                        androidx.compose.animation.AnimatedVisibility(open) { Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
                            HostField("容器名称", alias, { alias = it })
                            HostAction("下载并创建容器", "download", { app.launch { app.installDistro(distro, alias, mirror, engine) }; onBack() }, Modifier.fillMaxWidth(), enabled = alias.isNotBlank())
                        } }
                    }
                    }
                }
            } else {
                SectionLabel("来源方式", "folder_zip")
                HostChips(listOf("本地文件", "网络地址"), sourceType, { it }, { sourceType = it })
                if (sourceType == "本地文件") {
                    HostRow(if (source.isBlank()) "选择 rootfs 归档" else java.io.File(source).name, ".tar.gz / .tar.xz / .tar.zst", "folder_zip", { app.launch { files.pickRootfs()?.let { source = it } } }) { Symbol("folder") }
                } else HostField("下载链接", url, { url = it })
                HostField("容器名称", name, { name = it })
                HostField("SHA256（可选）", checksum, { checksum = it })
                SectionLabel("系统类型", "deployed_code")
                HostChips(listOf("Ubuntu", "Debian", "Alpine", "Arch", "Fedora", "Kali"), family, { it }, { family = it })
                SectionLabel("默认 Shell", "terminal")
                ShellSelector(shell) { shell = it }
                HostAction("校验并导入容器", "folder_zip", {
                    app.launch {
                        val config = app.runtime.importRootfs(if (sourceType == "本地文件") source else url.trim(), name.trim(), engine, checksum.trim().ifBlank { null })
                        app.runtime.update(config.copy(distro = family.lowercase(), shell = shell))
                        app.checkOmpInstallation(config.id)
                    }; onBack()
                }, Modifier.fillMaxWidth().padding(top = 28.dp), enabled = name.isNotBlank() && (if (sourceType == "本地文件") source.isNotBlank() else url.isNotBlank()))
            }
        }
    }
}
@Composable private fun EngineSelector(value: ContainerEngineType, isAndroid: Boolean, onChange: (ContainerEngineType) -> Unit) {
    HostChips(ContainerEngineType.entries, value, { it.name.lowercase() }, onChange, "deployed_code", enabled = { isAndroid || it != ContainerEngineType.PROROOT })
    if (!isAndroid) Text("proroot 仅支持 Android；桌面请使用 proot 或有 Root 权限的 chroot。", Modifier.padding(top = 8.dp), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
@Composable private fun ShellSelector(value: String, onChange: (String) -> Unit) { HostChips(listOf("/bin/bash", "/bin/zsh", "/bin/sh"), value, { it.substringAfterLast('/').replaceFirstChar(Char::uppercase) }, onChange, "terminal") }

@Composable internal fun ContainerDetailScreen(app: AppCoordinator, files: HostFiles, info: ContainerInfo, onBack: () -> Unit, onTerminal: (String) -> Unit, onFiles: (String) -> Unit) {
    val c = info.config
    var tools by remember(c.id) { mutableStateOf<List<ToolchainInfo>?>(null) }
    var probeError by remember(c.id) { mutableStateOf<String?>(null) }
    var toolBusy by remember { mutableStateOf<String?>(null) }
    var pendingTool by remember(c.id) { mutableStateOf<ToolchainInfo?>(null) }
    var mountEditor by remember { mutableStateOf<ContainerMount?>(null) }
    var addMount by remember { mutableStateOf(false) }
    var envEditor by remember { mutableStateOf<Pair<String, String>?>(null) }
    var addEnv by remember { mutableStateOf(false) }
    var userEditor by remember { mutableStateOf(false) }
    var userName by remember(c.user) { mutableStateOf(c.user) }
    var clone by remember { mutableStateOf(false) }
    var cloneName by remember { mutableStateOf("${c.name}-copy") }
    var delete by remember { mutableStateOf(false) }
    val uiScope = rememberCoroutineScope()
    suspend fun probe() { probeError = null; try { tools = withContext(Dispatchers.IO) { app.runtime.probeToolchains(c.id) } } catch (e: Exception) { probeError = e.message ?: e.toString() } }
    LaunchedEffect(c.id, c.engine, c.user) { probe() }
    fun update(next: ContainerConfig) { app.launch { app.runtime.update(next) } }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        AgentTopBar(c.name, onBack, actions = {
            AgentIconButton("delete", "删除容器", { delete = true })
            Switch(info.running, { run -> app.launch { if (run) app.runtime.start(c.id) else app.runtime.stop(c.id) } })
            Spacer(Modifier.width(8.dp))
        })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 4.dp, bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(26.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) { DistroAvatar(c.distro, info.running); Column { Text("${c.distro} ${c.version}", fontSize = 19.sp, fontWeight = FontWeight.SemiBold); Text("${c.arch} · ${bytesLabel(info.sizeBytes)} · ${if (info.running) "运行中" else "已休眠"}", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DetailAction(if (c.isDefault) "当前容器" else "选择容器", "check_circle", c.isDefault) { app.launch { app.selectContainer(c.id) } }
                    DetailAction("文件", "folder_open") { onFiles(c.id) }
                    DetailAction("终端", "terminal") { onTerminal(c.id) }
                    DetailAction("安装 / 修复 OMP", "build") { app.requestOmpInstallation(c.id) }
                    DetailAction("克隆", "content_copy") { clone = true }
                    DetailAction("导出", "ios_share") { app.launch { if (files.exportRootfs(app.runtime, c)) app.report("容器归档已导出") } }
                }
            }
            info.error?.let { Text(it, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error) }
            SectionLabel("运行引擎", "deployed_code")
            DetailSegments(ContainerEngineType.entries, c.engine, { it.name.lowercase() }, { update(c.copy(engine = it)) }, enabled = { app.isAndroid || it != ContainerEngineType.PROROOT })
            Text("切换引擎或配置会停止相关进程并应用新配置。chroot 需要 Root 权限。${if (!app.isAndroid) "proroot 仅支持 Android。" else "proroot 仅支持 root 登录身份。"}", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            SectionLabel("预装工具链", "construction", action = { TextButton({ uiScope.launch { probe() } }, enabled = toolBusy == null) { Text("重新探测") } })
            if (tools == null && probeError == null) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在容器内探测实际工具版本", Modifier.padding(12.dp)) }
            probeError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            tools?.chunked(2)?.forEach { row -> Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { tool ->
                    Row(Modifier.weight(1f).fillMaxHeight().heightIn(min = 76.dp).animateContentSize(spring()).background(if (tool.installed) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(if (tool.installed) 24.dp else 18.dp)).clickable(enabled = toolBusy == null) {
                        pendingTool = tool
                    }.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.size(44.dp).background(if (tool.installed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(if (tool.installed) 22.dp else 14.dp)), contentAlignment = Alignment.Center) {
                            if (toolBusy == tool.id) CircularProgressIndicator(Modifier.size(28.dp)) else Symbol(tool.icon, tint = if (tool.installed) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(tool.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                            Text(if (toolBusy == tool.id) "正在执行…" else if (tool.installed) tool.version else "未安装", fontSize = 15.sp, fontFamily = LocalAgentMono.current, softWrap = true, color = if (tool.installed) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            } }
            SectionLabel("挂载目录", "folder_shared", action = { TextButton({ mountEditor = null; addMount = true }) { Text("添加挂载") } })
            if (c.mounts.isEmpty()) Text("尚未添加自定义挂载", Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            c.mounts.forEach { mount -> DetailRow(mount.target, "↘ ${mount.source}", "folder_shared", { mountEditor = mount; addMount = true }) {
                TextButton({ update(c.copy(mounts = c.mounts.map { if (it == mount) it.copy(readWrite = !it.readWrite) else it })) }, enabled = c.engine == ContainerEngineType.CHROOT || !mount.readWrite) { Text(if (mount.readWrite) "读写" else "只读") }
                AgentIconButton("close", "移除挂载", { update(c.copy(mounts = c.mounts - mount)) })
            } }
            SectionLabel("登录身份", "verified_user")
            DetailSegments(listOf(true, false), c.user == "root" || c.user == "0", { if (it) "Root" else "普通用户" }, { root ->
                if (root) update(c.copy(user = "root")) else { userName = if (c.user == "root" || c.user == "0") "" else c.user; userEditor = true }
            }, enabled = { c.engine != ContainerEngineType.PROROOT || it })
            DetailRow("自定义登录用户", if (c.engine == ContainerEngineType.PROROOT) "proroot 不支持切换登录身份" else c.user, "verified_user", if (c.engine != ContainerEngineType.PROROOT) ({ userName = c.user; userEditor = true }) else null) { Symbol("edit") }
            SectionLabel("默认 Shell", "terminal")
            DetailSegments(listOf("/bin/bash", "/bin/zsh", "/bin/sh"), c.shell, { it.substringAfterLast('/').replaceFirstChar(Char::uppercase) }, { update(c.copy(shell = it)) })
            SectionLabel("PRoot 内核特性", "tune")
            DetailRow("软硬链接模拟", if (app.isAndroid && c.engine == ContainerEngineType.PROOT) "link2symlink" else "仅 Android PRoot 支持此选项", "link") { Switch(c.link2symlink, { update(c.copy(link2symlink = it)) }, enabled = app.isAndroid && c.engine == ContainerEngineType.PROOT) }
            DetailRow("进程间共享内存", if (app.isAndroid && c.engine == ContainerEngineType.PROOT) "sysvipc" else "仅 Android PRoot 支持 sysvipc", "hub") { Switch(c.sysvipc, { update(c.copy(sysvipc = it)) }, enabled = app.isAndroid && c.engine == ContainerEngineType.PROOT) }
            DetailRow("伪造高版本内核标识", if (c.engine == ContainerEngineType.PROOT) "6.8.0-generic" else "此选项仅适用于 proot", "verified_user") { Switch(c.fakeKernel, { update(c.copy(fakeKernel = it)) }, enabled = c.engine == ContainerEngineType.PROOT) }
            DetailRow("无活动自动休眠", "节省后台内存", "bedtime") { Switch(c.autoSleep, { update(c.copy(autoSleep = it)) }) }
            SectionLabel("环境变量", "data_object", action = { TextButton({ envEditor = null; addEnv = true }) { Text("添加变量") } })
            if (c.environment.isEmpty()) Text("尚未添加环境变量", Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            c.environment.forEach { (key, value) -> DetailRow(key, value, "data_object", { envEditor = key to value; addEnv = true }) { AgentIconButton("close", "删除变量", { update(c.copy(environment = c.environment - key)) }) } }
        }
    }
    pendingTool?.let { tool ->
        val operation = if (tool.installed) "卸载" else "安装"
        AlertDialog(
            onDismissRequest = { if (toolBusy == null) pendingTool = null },
            title = { Text("确认${operation} ${tool.title}？") },
            text = { Text("将在容器「${c.name}」内${operation}工具链「${tool.title}」。${if (tool.installed) "卸载可能同时移除不再需要的依赖软件包。" else "安装将下载软件包并占用容器存储空间。"}") },
            confirmButton = {
                TextButton({
                    if (toolBusy == null && pendingTool != null) {
                        pendingTool = null
                        toolBusy = tool.id
                        uiScope.launch {
                            try {
                                val output = withContext(Dispatchers.IO) { app.runtime.changeToolchain(c.id, tool.id, !tool.installed) }
                                app.report(output.ifBlank { "${tool.title} ${operation}已完成" })
                                probe()
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) { app.showError(failure) }
                            finally { toolBusy = null }
                        }
                    }
                }, enabled = toolBusy == null) { Text("确认$operation") }
            },
            dismissButton = { TextButton({ pendingTool = null }, enabled = toolBusy == null) { Text("取消") } },
        )
    }
    if (clone) AgentSheet("克隆容器", { clone = false }) { HostField("新容器名称", cloneName, { cloneName = it }); HostAction("开始克隆", "content_copy", { app.launch { app.runtime.clone(c.id, cloneName); clone = false } }, Modifier.fillMaxWidth(), enabled = cloneName.isNotBlank()) }
    if (delete) AlertDialog(onDismissRequest = { delete = false }, title = { Text("删除容器 ${c.name}？") }, text = { Text("将停止相关进程并删除此容器。受管导入的 rootfs 会永久删除；外部注册的开发 rootfs 将保留。存在关联会话时请先切换或删除会话。") }, confirmButton = { TextButton({ app.launch { app.deleteContainer(c.id); delete = false; onBack() } }) { Text("删除", color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton({ delete = false }) { Text("取消") } })
    if (addMount) MountSheet(mountEditor, c.engine == ContainerEngineType.CHROOT, files, { addMount = false }) { mount ->
        app.runtime.update(c.copy(mounts = if (mountEditor == null) c.mounts + mount else c.mounts.map { if (it == mountEditor) mount else it }))
        addMount = false
    }
    if (addEnv) EnvironmentSheet(envEditor, { addEnv = false }) { key, value ->
        app.launch {
            app.runtime.update(c.copy(environment = c.environment - listOfNotNull(envEditor?.first).toSet() + (key to value)))
            addEnv = false
        }
    }
    if (userEditor) ExistingUserSheet(userName, { userName = it }, { userEditor = false }) { name ->
        withContext(Dispatchers.IO) { app.runtime.update(c.copy(user = name)) }
        userEditor = false
    }
}

@Composable private fun MountSheet(existing: ContainerMount?, allowReadonly: Boolean, files: HostFiles, dismiss: () -> Unit, save: suspend (ContainerMount) -> Unit) {
    var source by rememberSaveable { mutableStateOf(existing?.source ?: "") }
    var target by rememberSaveable { mutableStateOf(existing?.target ?: "/mnt/docs") }
    var rw by rememberSaveable { mutableStateOf(existing?.readWrite ?: true) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun run(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "无法访问主机挂载目录" }
            finally { busy = false }
        }
    }
    AgentSheet(if (existing == null) "添加挂载目录" else "编辑挂载目录", dismiss) {
        DetailField("主机路径（有访问权限的实际目录）", source, enabled = !busy, trailing = {
            AgentIconButton("folder_open", "选择主机目录", { run { files.pickMountDirectory()?.let { source = it } } }, enabled = !busy)
        }) { source = it; error = null }
        DetailField("容器内路径", target, enabled = !busy) { target = it; error = null }
        Text("访问权限", Modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp), fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        DetailSegments(listOf(true, false), rw, { if (it) "读写模式" else "只读保护" }, { rw = it; error = null }, enabled = { !busy && (it || allowReadonly) })
        if (!allowReadonly) Text("只读挂载需要 chroot；proot / proroot 不支持真正只读绑定。", Modifier.padding(top = 12.dp), fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 16.dp))
        error?.let { Text(it, Modifier.padding(top = 16.dp), color = MaterialTheme.colorScheme.error) }
        HostAction("确认挂载", "check", { run {
            val actual = files.prepareMountSource(source, rw)
            save(ContainerMount(actual, target.trim(), rw))
        } }, Modifier.fillMaxWidth().padding(top = 24.dp), enabled = !busy && source.startsWith('/') && target.trim().startsWith('/'))
    }
}
@Composable private fun EnvironmentSheet(existing: Pair<String, String>?, dismiss: () -> Unit, save: (String, String) -> Unit) {
    var key by remember { mutableStateOf(existing?.first.orEmpty()) }
    var value by remember { mutableStateOf(existing?.second.orEmpty()) }
    AgentSheet(if (existing == null) "添加环境变量" else "编辑环境变量", dismiss) {
        DetailField("变量名", key) { key = it }
        DetailField("变量值", value) { value = it }
        HostAction("保存", "check", { save(key, value) }, Modifier.fillMaxWidth().padding(top = 20.dp), enabled = Regex("[A-Za-z_][A-Za-z0-9_]*").matches(key))
    }
}


@Composable private fun DetailAction(label: String, icon: String, selected: Boolean = false, action: () -> Unit) {
    Button(action, colors = ButtonDefaults.buttonColors(containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface, contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary), modifier = Modifier.heightIn(min = 48.dp)) {
        Symbol(icon, size = 21.dp); Spacer(Modifier.width(8.dp)); Text(label, fontSize = 17.sp)
    }
}

@Composable private fun <T> DetailSegments(items: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, enabled: (T) -> Boolean = { true }) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        items.forEachIndexed { index, item ->
            SegmentedButton(selected = item == selected, onClick = { onSelect(item) }, enabled = enabled(item), shape = SegmentedButtonDefaults.itemShape(index, items.size), modifier = Modifier.heightIn(min = 52.dp), colors = SegmentedButtonDefaults.colors(activeContainerColor = MaterialTheme.colorScheme.primaryContainer, activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer), icon = { if (item == selected) Symbol("check", size = 20.dp) }) { Text(label(item), fontSize = 17.sp) }
        }
    }
}

@Composable private fun DetailRow(title: String, subtitle: String = "", icon: String = "tune", onClick: (() -> Unit)? = null, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().heightIn(min = 68.dp).background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(16.dp)).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Symbol(icon, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            if (subtitle.isNotEmpty()) Text(subtitle, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = LocalAgentMono.current, softWrap = true)
        }
        trailing()
    }
}

@Composable private fun DetailField(label: String, value: String, enabled: Boolean = true, trailing: (@Composable () -> Unit)? = null, onChange: (String) -> Unit) {
    Text(label, Modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp), fontSize = 16.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    TextField(value, onChange, Modifier.fillMaxWidth(), enabled = enabled, trailingIcon = trailing, singleLine = true, shape = RoundedCornerShape(16.dp), textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = LocalAgentMono.current), colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surface, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
}

@Composable private fun ExistingUserSheet(value: String, onChange: (String) -> Unit, dismiss: () -> Unit, save: suspend (String) -> Unit) {
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AgentSheet("登录用户", dismiss) {
        DetailField("已存在的用户名称或 UID", value) { onChange(it); error = null }
        Text("必须存在于容器 /etc/passwd，不会自动创建用户或回退到 root。", Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 12.dp)) }
        HostAction(if (busy) "正在验证…" else "验证并保存", "check", {
            busy = true
            scope.launch {
                try { save(value.trim()) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { error = failure.message ?: "无法验证登录用户" }
                finally { busy = false }
            }
        }, Modifier.fillMaxWidth(), enabled = value.isNotBlank() && !busy)
    }
}
