package com.dopachiru.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random

/** 閉じる前の知らせかた。 */
enum class ReminderKind(val label: String) {
    BAND("薄い帯"),
    EDGE("縁の光"),
    BIG("大きな文字"),
    VIBRATE("振動"),
    WORDS("自分の文"),
}

/**
 * 閉じる前の知らせ1件。
 *
 * [beforeSeconds] 〜 [randomUntilSeconds] のあいだのどこかで出す(後者が前者以下なら固定)。
 * 決まった時刻に出ると、そこだけ身構えて慣れてしまうので、ばらつかせられるようにしてある。
 * 閉じる時刻そのものは動かさない ── 知らせは「延ばす窓口」ではない。
 */
data class Reminder(
    val beforeSeconds: Int,
    val randomUntilSeconds: Int = 0,
    val kind: ReminderKind = ReminderKind.BAND,
    val text: String = "",
) {
    val isRandom: Boolean get() = randomUntilSeconds > beforeSeconds

    /** 何秒前に出すか。ランダムのときは [seed] で決まる(同じ一続きでは同じ値)。 */
    fun resolveSeconds(seed: Long): Int =
        if (!isRandom) beforeSeconds
        else Random(seed xor (beforeSeconds * 31L + randomUntilSeconds)).nextInt(beforeSeconds, randomUntilSeconds + 1)
}

object Reminders {
    const val KEY = "reminders"
    const val MAX_COUNT = 8
    const val MAX_SECONDS = 60 * 60
    const val MAX_TEXT = 60

    fun encode(list: List<Reminder>): String {
        val arr = JsonArray(list.take(MAX_COUNT).map {
            JsonObject(
                mapOf(
                    "before" to JsonPrimitive(it.beforeSeconds),
                    "until" to JsonPrimitive(it.randomUntilSeconds),
                    "kind" to JsonPrimitive(it.kind.name),
                    "text" to JsonPrimitive(it.text),
                ),
            )
        })
        return arr.toString()
    }

    /** 壊れた文字列や知らない種類は読み飛ばす。読めないせいで閉じる動作まで落ちないように。 */
    fun decode(raw: String): List<Reminder> {
        if (raw.isBlank()) return emptyList()
        val arr = runCatching { Json.parseToJsonElement(raw) as? JsonArray }.getOrNull() ?: return emptyList()
        return arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val before = o["before"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            val kind = runCatching { ReminderKind.valueOf(o["kind"]?.jsonPrimitive?.contentOrNull ?: "") }
                .getOrNull() ?: return@mapNotNull null
            Reminder(
                beforeSeconds = before.coerceIn(1, MAX_SECONDS),
                randomUntilSeconds = (o["until"]?.jsonPrimitive?.intOrNull ?: 0).coerceIn(0, MAX_SECONDS),
                kind = kind,
                text = (o["text"]?.jsonPrimitive?.contentOrNull ?: "").take(MAX_TEXT),
            )
        }.take(MAX_COUNT)
    }
}
