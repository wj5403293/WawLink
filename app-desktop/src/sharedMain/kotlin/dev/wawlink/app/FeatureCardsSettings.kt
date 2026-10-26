package dev.wawlink.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.wawlink.runtime.ContainerFeatureCatalog
import dev.wawlink.ui.*
import kotlinx.coroutines.CancellationException

/** Recovery reads the catalog only; it never enters a feature or starts its script. */
@Composable
internal fun FeatureCardsSettings(app: AppCoordinator) {
    val containerId by app.selectedContainerId.collectAsState()
    val containers by app.runtime.containers.collectAsState()
    val preferences by app.featureCards.state.collectAsState()
    val preferenceError by app.featureCards.error.collectAsState()
    val container = containers.firstOrNull { it.config.id == containerId }
    var catalog by remember(containerId) { mutableStateOf<ContainerFeatureCatalog?>(null) }
    var error by remember(containerId) { mutableStateOf<String?>(null) }
    var loading by remember(containerId) { mutableStateOf(false) }
    var restoring by remember(containerId) { mutableStateOf(false) }
    var refresh by remember(containerId) { mutableStateOf(0) }
    val layout = preferences[containerId] ?: FeatureCardLayout()
    val titles = featureCardTitles(catalog)
    val hidden = layout.order.filter { it in layout.hidden } + layout.hidden.filterNot { it in layout.order }.sorted()
    LaunchedEffect(containerId, refresh) {
        val id = containerId ?: return@LaunchedEffect
        loading = true; error = null
        try {
            app.featureCards.load(id)
            catalog = app.features.catalog(id)
            app.featureCards.reconcile(id, featureCardTitles(catalog).keys.toList())
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "无法读取功能目录" }
        finally { loading = false }
    }
    fun restore(keys: Set<String>?) {
        val id = containerId ?: return
        restoring = true
        app.launch {
            try { app.featureCards.restore(id, keys) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "无法恢复功能卡片"; app.showError(failure) }
            finally { restoring = false }
        }
    }
    SectionLabel("功能卡片", "dashboard_customize")
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        HostRow("隐藏卡片", container?.config?.name?.let { "当前容器：$it · 已隐藏 ${hidden.size} 项" } ?: "尚未选择容器", "visibility_off") {
            if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            AgentIconButton("refresh", "刷新隐藏功能目录", { refresh++ }, enabled = containerId != null && !loading)
        }
        if (hidden.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
                TextButton({ restore(null) }, enabled = !restoring) { Symbol("visibility", size = 20.dp); Spacer(Modifier.width(6.dp)); Text("恢复全部") }
            }
            hidden.forEach { key ->
                key(key) {
                    HostRow(titles[key] ?: "不可用功能 · ${key.substringAfter('/')}",
                        if (key.startsWith("builtin/")) "内置功能" else if (key in titles) "自定义功能" else "目录中暂未找到，仍可恢复显示设置", "visibility_off") {
                        TextButton({ restore(setOf(key)) }, enabled = !restoring) { Text("恢复") }
                    }
                }
            }
        } else if (container != null) {
            Text("没有隐藏的卡片。长按功能页卡片可选择、隐藏、调色；选择后再次长按可拖动排序。", Modifier.padding(horizontal = 16.dp, vertical = 10.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (restoring) LinearProgressIndicator(Modifier.fillMaxWidth())
        (listOfNotNull(error, preferenceError) + catalog?.errors.orEmpty()).distinct().forEach { FeatureCardError(it) }
    }
}
