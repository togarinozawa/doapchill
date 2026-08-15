package com.dopachiru.core.param

import com.dopachiru.core.time.ResetPolicy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * 条件・アクションのパラメータの実値。
 *
 * 型ごとにクラスを作らず JSON で持つことで、条件の種類が増えても
 * 永続化スキーマ(Room のテーブル定義)を変えずに済ませている。
 */
@Serializable
@JvmInline
value class Params(val json: JsonObject) {

    fun int(key: String, fallback: Int = 0): Int =
        json[key]?.jsonPrimitive?.intOrNull ?: fallback

    fun bool(key: String, fallback: Boolean = false): Boolean =
        json[key]?.jsonPrimitive?.booleanOrNull ?: fallback

    fun string(key: String, fallback: String = ""): String =
        json[key]?.jsonPrimitive?.contentOrNull ?: fallback

    fun intSet(key: String, fallback: Set<Int> = emptySet()): Set<Int> {
        val arr = json[key] as? JsonArray ?: return fallback
        return arr.mapNotNull { it.jsonPrimitive.intOrNull }.toSet()
    }

    fun stringSet(key: String, fallback: Set<String> = emptySet()): Set<String> {
        val arr = json[key] as? JsonArray ?: return fallback
        return arr.mapNotNull { it.jsonPrimitive.contentOrNull }.toSet()
    }

    fun resetPolicy(key: String, fallback: ResetPolicy = ResetPolicy()): ResetPolicy {
        val obj = json[key] as? JsonObject ?: return fallback
        return runCatching { JSON.decodeFromJsonElement(ResetPolicy.serializer(), obj) }
            .getOrDefault(fallback)
    }

    /** 一部のキーだけ差し替えた新しい Params を返す。 */
    fun with(vararg pairs: Pair<String, Any?>): Params =
        Params(JsonObject(json + pairs.associate { (k, v) -> k to toElement(v) }))

    fun encode(): String = JSON.encodeToString(JsonObject.serializer(), json)

    companion object {
        val EMPTY = Params(JsonObject(emptyMap()))

        internal val JSON = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun decode(text: String): Params =
            runCatching { Params(JSON.decodeFromString(JsonObject.serializer(), text)) }
                .getOrDefault(EMPTY)

        fun of(vararg pairs: Pair<String, Any?>): Params =
            Params(JsonObject(pairs.associate { (k, v) -> k to toElement(v) }))

        /** spec のデフォルト値だけを詰めた Params。新規ルール作成の初期値に使う。 */
        fun defaultsOf(specs: List<ParamSpec>): Params = Params(
            buildJsonObject {
                for (spec in specs) {
                    val value: JsonElement = when (spec) {
                        is ParamSpec.IntParam -> JsonPrimitive(spec.default)
                        is ParamSpec.TimeOfDayParam -> JsonPrimitive(spec.default)
                        is ParamSpec.DurationParam -> JsonPrimitive(spec.default)
                        is ParamSpec.BoolParam -> JsonPrimitive(spec.default)
                        is ParamSpec.TextParam -> JsonPrimitive(spec.default)
                        is ParamSpec.EnumParam -> JsonPrimitive(spec.default)
                        is ParamSpec.DayOfWeekParam -> JsonArray(spec.default.map { JsonPrimitive(it) })
                        is ParamSpec.PackagesParam -> JsonArray(spec.default.map { JsonPrimitive(it) })
                        is ParamSpec.ResetPolicyParam ->
                            JSON.encodeToJsonElement(ResetPolicy.serializer(), spec.default)
                    }
                    put(spec.key, value)
                }
            }
        )

        private fun toElement(value: Any?): JsonElement = when (value) {
            null -> JsonNull
            is JsonElement -> value
            is String -> JsonPrimitive(value)
            is Int -> JsonPrimitive(value)
            is Long -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is Double -> JsonPrimitive(value)
            is ResetPolicy -> JSON.encodeToJsonElement(ResetPolicy.serializer(), value)
            is Collection<*> -> JsonArray(value.map { toElement(it) })
            else -> JsonPrimitive(value.toString())
        }
    }
}
