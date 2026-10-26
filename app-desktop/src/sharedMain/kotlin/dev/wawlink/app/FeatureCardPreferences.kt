package dev.wawlink.app

import dev.wawlink.runtime.ContainerFeaturePreferences
import dev.wawlink.runtime.ContainerRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Keys are namespaced so a guest feature cannot collide with a built-in card. */
data class FeatureCardLayout(
    val order: List<String> = emptyList(),
    val hidden: Set<String> = emptySet(),
    val colors: Map<String, String> = emptyMap(),
)

/** Guest storage is committed before publishing; navigation cannot interrupt an in-flight change. */
class FeatureCardPreferences(runtime: ContainerRuntime) {
    private val storage = ContainerFeaturePreferences(runtime)
    private val mutex = Mutex()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _state = MutableStateFlow<Map<String, FeatureCardLayout>>(emptyMap())
    val state: StateFlow<Map<String, FeatureCardLayout>> = _state.asStateFlow()

    suspend fun load(containerId: String) = access(containerId)

    private fun parse(document: String?): FeatureCardLayout {
        if (document == null) return FeatureCardLayout()
        val value = Json.parseToJsonElement(document).jsonObject
        fun string(element: JsonElement): String {
            require(element is JsonPrimitive && element.isString) { "功能卡片设置包含非字符串值" }
            return element.content
        }
        val colors = value["colors"]?.jsonObject.orEmpty().mapValues { string(it.value) }
        require(colors.values.all(::validColor)) { "功能卡片颜色必须为 #RRGGBB" }
        return FeatureCardLayout(
            value["order"]?.jsonArray.orEmpty().map(::string).distinct(),
            value["hidden"]?.jsonArray.orEmpty().map(::string).toSet(),
            colors,
        )
    }

    suspend fun reconcile(containerId: String, keys: List<String>) = change(containerId) { current ->
        current.copy(order = current.order + keys.filterNot { it in current.order }.distinct())
    }

    suspend fun reorder(containerId: String, keys: List<String>) = change(containerId) { current ->
        current.copy(order = keys.distinct() + current.order.filterNot { it in keys })
    }

    suspend fun hide(containerId: String, keys: Set<String>) = change(containerId) { it.copy(hidden = it.hidden + keys) }

    suspend fun restore(containerId: String, keys: Set<String>? = null) = change(containerId) {
        it.copy(hidden = if (keys == null) emptySet() else it.hidden - keys)
    }

    suspend fun setColor(containerId: String, keys: Set<String>, color: String?) {
        require(color == null || validColor(color)) { "颜色必须为 #RRGGBB" }
        change(containerId) { current ->
            current.copy(colors = current.colors - keys + if (color == null) emptyMap() else keys.associateWith { color.uppercase() })
        }
    }

    /** Called only after the runtime confirms that uninstall succeeded. */
    suspend fun remove(containerId: String, key: String) = change(containerId) {
        it.copy(order = it.order - key, hidden = it.hidden - key, colors = it.colors - key)
    }

    private suspend fun change(containerId: String, transform: (FeatureCardLayout) -> FeatureCardLayout) =
        withContext(NonCancellable) { access(containerId, transform) }

    private suspend fun access(containerId: String, transform: (FeatureCardLayout) -> FeatureCardLayout = { it }) = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val document = storage.read(containerId)
                val current = parse(document)
                val layout = transform(current)
                if (layout != current) {
                    val next = buildJsonObject {
                        put("order", JsonArray(layout.order.map(::JsonPrimitive)))
                        put("hidden", JsonArray(layout.hidden.map(::JsonPrimitive)))
                        put("colors", buildJsonObject { layout.colors.forEach { (key, color) -> put(key, color) } })
                    }
                    storage.write(containerId, next.toString(), document)
                }
                _state.value = _state.value + (containerId to layout)
                _error.value = null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                _error.value = "无法读取或保存功能卡片设置：${failure.message}"
                throw failure
            }
        }
    }

    companion object {
        private val colorPattern = Regex("#[0-9a-fA-F]{6}")
        fun validColor(value: String): Boolean = colorPattern.matches(value)
    }
}
