package dev.wawlink.driver

import dev.wawlink.protocol.SettingCategory
import dev.wawlink.protocol.SettingGroup
import dev.wawlink.protocol.SettingItem
import dev.wawlink.protocol.SettingOption
import dev.wawlink.protocol.SettingType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Pinned upstream defaults, not the prototype's machine-specific configured snapshot. */
internal object OmpSettingsSchema {
    private val document = Json.parseToJsonElement(
        requireNotNull(javaClass.getResourceAsStream("/dev/wawlink/driver/omp-settings-schema.json")) {
            "Missing OMP 18.12.6 settings schema resource"
        }.bufferedReader(Charsets.UTF_8).use { it.readText() },
    ).jsonObject

    val categories: List<SettingCategory> = document.getValue("categories").jsonArray.map { category ->
        val c = category.jsonObject
        SettingCategory(c.schemaString("id"), c.schemaString("name"), c.schemaString("icon"), c.getValue("groups").jsonArray.map { group ->
            val g = group.jsonObject
            SettingGroup(g.schemaString("id"), g.schemaString("name"), g.schemaString("icon"), g.getValue("items").jsonArray.map { item ->
                val i = item.jsonObject
                val default = i.getValue("sourceDefault").toConfigValue()
                SettingItem(
                    id = i.schemaString("id"), title = i.schemaString("name"), icon = i.schemaString("icon"),
                    type = when (i.schemaString("type")) {
                        "bool" -> SettingType.BOOLEAN
                        "enum" -> SettingType.ENUM
                        "num" -> SettingType.NUMBER
                        "str" -> SettingType.STRING
                        "arr" -> SettingType.ARRAY
                        "rec" -> SettingType.RECORD
                        else -> error("Unknown setting type for ${i.schemaString("id")}")
                    },
                    value = default, defaultValue = default,
                    options = i["wireOptions"]?.jsonArray?.mapIndexed { index, value ->
                        SettingOption(value.jsonPrimitive.content, i.getValue("opts").jsonArray[index].jsonPrimitive.content)
                    }.orEmpty(),
                    step = if (default is Double && default % 1.0 != 0.0) 0.01 else 1.0,
                )
            })
        })
    }
    val items: Map<String, SettingItem> = categories.flatMap { it.groups }.flatMap { it.items }.associateBy { it.id }

    fun effective(read: (String) -> Any?): List<SettingCategory> = categories.map { c ->
        c.copy(groups = c.groups.map { g ->
            g.copy(items = g.items.map { item -> item.copy(value = normalizeConfigValue(read(item.id) ?: item.defaultValue)) })
        })
    }

    fun validate(id: String, value: Any?): Any? {
        val item = requireNotNull(items[id]) { "Unknown or excluded OMP setting: $id" }
        if (value == null) return null
        val normalized = normalizeConfigValue(value)
        val valid = when (item.type) {
            SettingType.BOOLEAN -> normalized is Boolean
            SettingType.ENUM -> normalized is String && item.options.any { it.value == normalized }
            SettingType.NUMBER -> normalized is Number && normalized.toDouble().isFinite()
            SettingType.STRING -> normalized is String
            SettingType.ARRAY -> normalized is List<*>
            SettingType.RECORD -> normalized is Map<*, *> && normalized.keys.all { it is String }
        }
        require(valid) { "Invalid value for $id (${item.type})" }
        // This upstream setting consumes structured interception rules, not the prototype's summary labels.
        if (id == "bashInterceptor.patterns") {
            require((normalized as List<*>).all { it is Map<*, *> && it["pattern"] is String && it["tool"] is String && it["message"] is String }) {
                "bashInterceptor.patterns requires objects with pattern, tool, and message strings"
            }
        }
        return normalized
    }
}

private fun JsonObject.schemaString(key: String): String = getValue(key).jsonPrimitive.content

internal fun JsonElement.toConfigValue(): Any? = when (this) {
    JsonNull -> null
    is JsonObject -> entries.associate { it.key to it.value.toConfigValue() }
    is JsonArray -> map { it.toConfigValue() }
    is JsonPrimitive -> if (isString) content else booleanOrNull ?: longOrNull ?: doubleOrNull ?: contentOrNull
}

internal fun normalizeConfigValue(value: Any?): Any? = when (value) {
    is JsonElement -> value.toConfigValue()
    is Map<*, *> -> value.entries.associate { require(it.key is String); it.key as String to normalizeConfigValue(it.value) }
    is List<*> -> value.map(::normalizeConfigValue)
    is Byte, is Short, is Int, is Long -> (value as Number).toLong()
    is Float, is Double -> (value as Number).toDouble().let { if (it.isFinite() && it % 1.0 == 0.0 && it >= Long.MIN_VALUE && it < Long.MAX_VALUE) it.toLong() else it }
    else -> value
}
