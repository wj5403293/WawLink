package dev.wawlink.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.wawlink.protocol.ModelConfig

/** Remove only explicit model configuration; native catalog entries are never deletion targets. */
internal fun removeProviderModels(draft: Map<String, Any?>, selectedIds: Set<String>): Map<String, Any?> {
    if (selectedIds.isEmpty()) return draft
    var next = draft
    val models = draft["models"] as? List<*>
    if (models != null) {
        val remaining = models.filterNot { model ->
            val id = (model as? Map<*, *>)?.get("id") as? String
            id != null && id in selectedIds
        }
        if (remaining.size != models.size) next = if (remaining.isEmpty()) next - "models" else next + ("models" to remaining)
    }
    val overrides = configurationMap(draft["modelOverrides"])
    if (overrides.keys.any { it in selectedIds }) {
        val remaining = overrides - selectedIds
        next = if (remaining.isEmpty()) next - "modelOverrides" else next + ("modelOverrides" to remaining)
    }
    return next
}

/** Reapply the current IDs at confirmation time so a refresh cannot create duplicates. */
internal fun newProviderModels(models: List<ModelConfig>, knownIds: Set<String>, selectedIds: Set<String>? = null): List<ModelConfig> {
    val seen = HashSet<String>()
    return models.filter { model ->
        model.id.isNotBlank() && model.id !in knownIds && (selectedIds == null || model.id in selectedIds) && seen.add(model.id)
    }
}

internal fun filterProviderModels(models: List<ModelConfig>, query: String): List<ModelConfig> {
    val search = query.trim()
    return if (search.isEmpty()) models else models.filter { it.id.contains(search, ignoreCase = true) || it.name.contains(search, ignoreCase = true) }
}

internal fun toggleProviderModelSelection(selectedIds: Set<String>, targetIds: Set<String>): Set<String> = when {
    targetIds.isEmpty() -> selectedIds
    selectedIds.containsAll(targetIds) -> selectedIds - targetIds
    else -> selectedIds + targetIds
}

internal fun fetchedModelFields(model: ModelConfig): Map<String, Any?> = buildMap {
    put("id", model.id)
    put("name", model.name)
    put("contextWindow", model.contextWindow)
    put("maxTokens", model.maxTokens)
    put("input", model.input)
    model.reasoning?.let { put("reasoning", it) }
    model.cost?.let { cost -> put("cost", mapOf("input" to cost.input, "output" to cost.output, "cacheRead" to cost.cacheRead, "cacheWrite" to cost.cacheWrite)) }
}

@Composable
internal fun ProviderModelSelectionActions(
    selectedCount: Int,
    onSelectAll: () -> Unit,
    onDelete: () -> Unit,
    onExit: () -> Unit,
    enabled: Boolean = true,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AgentIconButton("select_all", "全选", onSelectAll, enabled = enabled)
        IconButton(onDelete, enabled = enabled && selectedCount > 0, modifier = Modifier.size(48.dp)) {
            Symbol("delete", tint = if (enabled && selectedCount > 0) LocalAgentColors.current.error else LocalAgentColors.current.outline, contentDescription = "批量删除")
        }
        AgentIconButton("close", "退出多选", onExit)
    }
}

@Composable
internal fun FetchedProviderModelsSheet(models: List<ModelConfig>, onDismiss: () -> Unit, onAdd: (Set<String>) -> Unit) {
    val allIds = remember(models) { models.map { it.id }.toSet() }
    var search by remember(models) { mutableStateOf("") }
    var selectedIds by remember(models) { mutableStateOf(allIds) }
    val visible = remember(models, search) { filterProviderModels(models, search) }
    val visibleIds = remember(visible) { visible.map { it.id }.toSet() }
    AgentSheet("获取到的模型", onDismiss) {
        SettingsSearch(search, { search = it }, "搜索模型名称或 ID")
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("已选 ${selectedIds.size} / ${allIds.size}", Modifier.weight(1f))
            AgentIconButton("select_all", "全选", { selectedIds = toggleProviderModelSelection(selectedIds, visibleIds) }, enabled = visibleIds.isNotEmpty())
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(min = 72.dp, max = 440.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            itemsIndexed(visible, key = { _, model -> model.id }) { index, model ->
                val selected = model.id in selectedIds
                ManagementRow(model.name.ifBlank { model.id }, if (model.name != model.id) model.id else "", icon = "",
                    onClick = { selectedIds = if (selected) selectedIds - model.id else selectedIds + model.id }, index = index, count = visible.size,
                    lead = { ModelIcon(model.id, model.name, Modifier.size(44.dp)) }, selected = selected,
                    contentDescription = if (model.name.isBlank() || model.name == model.id) model.id else "${model.name}，${model.id}",
                    trailing = { Symbol(if (selected) "check_box" else "check_box_outline_blank", tint = LocalAgentColors.current.primary) })
            }
            if (visible.isEmpty()) item { SettingsEmpty("没有匹配的模型", "search_off") }
        }
        Button({ onAdd(selectedIds) }, enabled = selectedIds.isNotEmpty(), modifier = Modifier.fillMaxWidth().padding(top = 16.dp).heightIn(min = 48.dp)) {
            Text("添加所选 (${selectedIds.size})")
        }
    }
}
