package dev.wawlink.app

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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

/** Disk is committed before publishing; navigation cannot interrupt an in-flight preference change. */
class FeatureCardPreferences(private val file: File) {
    private val mutex = Mutex()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _state = MutableStateFlow(load())
    val state: StateFlow<Map<String, FeatureCardLayout>> = _state.asStateFlow()

    private fun load(): Map<String, FeatureCardLayout> = try {
        if (!file.exists()) emptyMap() else Json.parseToJsonElement(file.readText()).jsonObject.mapValues { (_, element) ->
            val value = element.jsonObject
            FeatureCardLayout(
                value["order"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }.distinct(),
                value["hidden"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }.toSet(),
                value["colors"]?.jsonObject.orEmpty().mapValues { it.value.jsonPrimitive.content }.filterValues(::validColor),
            )
        }
    } catch (failure: Exception) {
        _error.value = "无法读取功能卡片设置：${failure.message}"
        emptyMap()
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

    private suspend fun change(containerId: String, transform: (FeatureCardLayout) -> FeatureCardLayout) = withContext(NonCancellable + Dispatchers.IO) {
        mutex.withLock {
            val current = _state.value
            val layout = transform(current[containerId] ?: FeatureCardLayout())
            if (current[containerId] == layout) return@withLock
            val next = current + (containerId to layout)
            val document = buildJsonObject {
                next.forEach { (id, entry) -> put(id, buildJsonObject {
                    put("order", JsonArray(entry.order.map(::JsonPrimitive)))
                    put("hidden", JsonArray(entry.hidden.map(::JsonPrimitive)))
                    put("colors", buildJsonObject { entry.colors.forEach { (key, color) -> put(key, color) } })
                }) }
            }
            file.parentFile?.mkdirs()
            val temporary = File.createTempFile("feature-cards-", ".tmp", file.absoluteFile.parentFile)
            try {
                FileOutputStream(temporary).use { stream ->
                    stream.write(document.toString().toByteArray(Charsets.UTF_8))
                    stream.fd.sync()
                }
                try { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
                catch (_: AtomicMoveNotSupportedException) { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
                _state.value = next
                _error.value = null
            } finally { temporary.delete() }
        }
    }

    companion object {
        private val colorPattern = Regex("#[0-9a-fA-F]{6}")
        fun validColor(value: String): Boolean = colorPattern.matches(value)
    }
}
