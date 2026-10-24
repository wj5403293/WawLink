package dev.wawlink.app

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wawlink.runtime.*
import dev.wawlink.ui.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun ContainerFeaturesScreen(app: AppCoordinator, service: ContainerFeatureService, containerId: String?, onBuiltin: (String) -> Unit, onExtension: (ContainerFeature) -> Unit) {
    val containers by app.runtime.containers.collectAsState()
    val states by service.states.collectAsState()
    val container = containers.firstOrNull { it.config.id == containerId }
    var catalog by remember(containerId) { mutableStateOf<ContainerFeatureCatalog?>(null) }
    var error by remember(containerId) { mutableStateOf<String?>(null) }
    var refresh by remember(containerId) { mutableStateOf(0) }
    var loading by remember(containerId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val css = containerFeatureThemeCss()
    SideEffect { service.setTheme(css) }
    ContainerFeatureIcons(service)
    LaunchedEffect(containerId, refresh) {
        if (containerId != null) {
            loading = true; error = null
            try { catalog = service.catalog(containerId) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "无法读取容器功能" }
            finally { loading = false }
        }
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        LazyVerticalGrid(columns = GridCells.Fixed(2), modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 28.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(span = { GridItemSpan(2) }) {
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(container?.config?.name ?: "尚未选择容器", Modifier.weight(1f), fontSize = 28.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (loading) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    AgentIconButton("refresh", "刷新功能", { refresh++ }, enabled = !loading && container != null)
                }
            }
            items(listOf(
                FeatureBuiltin("terminal", "终端", "进入当前容器的交互式终端", "terminal"),
                FeatureBuiltin("files", "文件", "浏览和管理容器文件", "folder"),
                FeatureBuiltin("rules", "规则", "查看与编辑容器用户规则", "rule"),
                FeatureBuiltin("plugins", "插件", "安装、配置与管理用户插件", "extension"),
                FeatureBuiltin("skills", "技能", "管理用户 Skill 与原生技能包", "psychology"),
            )) { builtin ->
                FeatureCard(builtin.title, builtin.description, builtin.icon, container != null, { onBuiltin(builtin.id) })
            }
            item(span = { GridItemSpan(2) }) {
                Text("自定义功能", Modifier.padding(top = 14.dp, bottom = 4.dp), fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            }
            items(catalog?.features.orEmpty(), key = { "extension/${it.id}" }) { feature ->
                val state = states[service.stateKey(containerId.orEmpty(), feature.id)] ?: ContainerFeatureState()
                FeatureCard(feature.title, feature.description, feature.icon, container != null, { onExtension(feature) },
                    status = when (state.status) {
                        ContainerFeatureStatus.RUNNING -> if (feature.lifecycle == ContainerFeatureLifecycle.MANUAL) "运行中 · 手动停止" else "运行中 · 离页停止"
                        ContainerFeatureStatus.STARTING -> "正在启动"
                        ContainerFeatureStatus.STOPPING -> "正在停止"
                        ContainerFeatureStatus.FAILED -> "启动或运行异常"
                        ContainerFeatureStatus.STOPPED -> if (feature.lifecycle == ContainerFeatureLifecycle.MANUAL) "手动生命周期" else "离页自动停止"
                    },
                    onStop = if (state.status == ContainerFeatureStatus.RUNNING) ({ scope.launch {
                        try { service.stop(requireNotNull(containerId), feature.id) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { error = failure.message }
                    }; Unit }) else null)
            }
            if (container != null && catalog != null && !loading && error == null && catalog?.features.isNullOrEmpty()) item(span = { GridItemSpan(2) }) {
                Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Symbol("widgets", size = 32.dp, tint = MaterialTheme.colorScheme.primary)
                        Text("还没有自定义功能", fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                        Text("告诉 Agent「添加 WawLink 功能页卡片」即可创建。", fontSize = 18.sp)
                    }
                }
            }
            (listOfNotNull(error) + catalog?.errors.orEmpty()).forEachIndexed { index, message ->
                item(key = "error/$index", span = { GridItemSpan(2) }) {
                    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.errorContainer) {
                        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Symbol("error", tint = MaterialTheme.colorScheme.onErrorContainer)
                            Text(message, fontSize = 18.sp, color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                }
            }
        }
    }
}

private data class FeatureBuiltin(val id: String, val title: String, val description: String, val icon: String)

@Composable
private fun FeatureCard(title: String, description: String, icon: String, enabled: Boolean, onClick: () -> Unit, status: String? = null, onStop: (() -> Unit)? = null) {
    Card(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 190.dp).animateContentSize(tween(450, easing = EmphEasing)), shape = RoundedCornerShape(26.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) { Symbol(icon, size = 28.dp, tint = MaterialTheme.colorScheme.onPrimaryContainer) }
            }
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text(description, fontSize = 18.sp, lineHeight = 26.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            status?.let { Text(it, fontSize = 18.sp, color = MaterialTheme.colorScheme.primary) }
            onStop?.let { TextButton(it, modifier = Modifier.heightIn(min = 48.dp)) { Symbol("stop_circle", size = 20.dp); Spacer(Modifier.width(6.dp)); Text("停止服务", fontSize = 18.sp) } }
        }
    }
}
