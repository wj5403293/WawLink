package dev.wawlink.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.serialization.json.JsonElement

@Composable
internal fun rememberFeatureNativeLuaExecutor(key: String?): FeatureNativeLuaExecutor =
    remember(key) { DesktopFeatureNativeLuaExecutor }

private object DesktopFeatureNativeLuaExecutor : FeatureNativeLuaExecutor {
    override val available: Boolean = false

    override suspend fun execute(source: String): JsonElement =
        throw UnsupportedOperationException("Android LuaJ actions are unavailable on desktop")

    override fun close() = Unit
}
