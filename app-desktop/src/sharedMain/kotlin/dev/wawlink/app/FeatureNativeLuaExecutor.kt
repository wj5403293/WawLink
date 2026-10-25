package dev.wawlink.app

import kotlinx.serialization.json.JsonElement

/** Android LuaJ host access, independent of a rendered HTML document. */
internal interface FeatureNativeLuaExecutor : AutoCloseable {
    val available: Boolean
    suspend fun execute(source: String): JsonElement
}
