package com.dopachiru.core.sync

import com.dopachiru.core.DopaCore
import com.dopachiru.core.model.Rule
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * 手元のものと、線の上を流れる包みとの変換。
 *
 * ## 何を配って、何を配らないか
 *
 * 配るのは **ルール・タグ・アプリの名札・使用実績**。
 *
 * **ゲートと変更リクエストは配りません。** ゲート(変更に摩擦をかける仕組み)は
 * 端末ごとの自分との約束で、変更リクエストはその端末で承認待ちのものです。
 * 承認待ちが別の端末に流れると、**片方で作った申請をもう片方で承認できてしまい**、
 * ゲートを置いた意味が消えます。
 *
 * ## 鍵の付けかた
 *
 * ルールは `uid`(端末をまたいで一意)。タグとアプリの名札は
 * **`platform:識別子`** にします ── `chrome.exe` のように両方の端末に
 * 存在しうる名前があるので、分けないと互いに上書きし合います。
 */
object SyncMapper {

    // ---- ルール -----------------------------------------------------------

    fun ruleEnvelope(rule: Rule, updatedAt: Long, deleted: Boolean = false): Envelope = Envelope(
        uid = rule.uid,
        updatedAt = updatedAt,
        deleted = deleted,
        // 端末ごとの番号(id)は運ばない。番号は端末の中の都合で、
        // 向こうの番号をこちらに持ち込むと既存のルールを踏む
        payload = if (deleted) {
            JsonObject(emptyMap())
        } else {
            DopaCore.json.encodeToJsonElement(Rule.serializer(), rule.copy(id = 0L)) as JsonObject
        },
    )

    /** 読めなければ null。知らない条件が入っていても、ここでは弾きません。 */
    fun ruleOf(envelope: Envelope): Rule? = runCatching {
        DopaCore.json.decodeFromJsonElement(Rule.serializer(), envelope.payload)
            .copy(uid = envelope.uid, id = 0L)
    }.getOrNull()

    // ---- タグ -------------------------------------------------------------

    /**
     * 1つのアプリに付いたタグ全部で1件。
     *
     * タグごとに分けないのは、「このアプリからタグを1つ外した」が
     * **消えたことの記録になれない**ため。まとめて置き換えれば、
     * 空の集合がそのまま「全部外した」になります。
     */
    fun tagsEnvelope(
        platform: String,
        packageName: String,
        tags: Set<String>,
        updatedAt: Long,
    ): Envelope = Envelope(
        uid = "$platform:$packageName",
        updatedAt = updatedAt,
        payload = buildJsonObject {
            put("tags", kotlinx.serialization.json.JsonArray(tags.sorted().map { JsonPrimitive(it) }))
        },
    )

    /** @return パッケージ名 → タグ。読めなければ null。 */
    fun tagsOf(envelope: Envelope, platform: String): Pair<String, Set<String>>? {
        val (owner, id) = AppInfo.idOf(envelope.uid) ?: return null
        // 別の端末のパッケージ名は、こちらでは使い道が無い
        if (owner != platform) return null
        val tags = runCatching {
            envelope.payload["tags"]?.jsonArray?.mapNotNull { it.jsonPrimitive.content }?.toSet()
        }.getOrNull() ?: emptySet()
        return id to tags
    }

    // ---- アプリの名札 -----------------------------------------------------

    fun appEnvelope(info: AppInfo, updatedAt: Long): Envelope = Envelope(
        uid = info.uid,
        updatedAt = updatedAt,
        payload = buildJsonObject {
            put("label", JsonPrimitive(info.label))
            put("platform", JsonPrimitive(info.platform))
        },
    )

    /** 名札は**どの端末のものでも受け取ります**。別の端末の実績を読むために要るので。 */
    fun appOf(envelope: Envelope): AppInfo? {
        val (platform, id) = AppInfo.idOf(envelope.uid) ?: return null
        val label = runCatching { envelope.payload["label"]?.jsonPrimitive?.content }.getOrNull()
        if (label.isNullOrBlank()) return null
        return AppInfo(id = id, label = label, platform = platform)
    }
}

/** 受け取った1件を、手元のものと比べてどうするか。 */
sealed interface MergeAction {
    /** 手元に入れる(新規または上書き)。 */
    data object Apply : MergeAction

    /** 手元のほうが新しいので何もしない。 */
    data object Skip : MergeAction

    /** 墓標が届いた。手元から消す。 */
    data object Delete : MergeAction
}

/**
 * 突き合わせの規則。**後に書かれたほうが勝ち、同値なら手元を残します。**
 *
 * 同値で手元を残すのは、**同期のたびに書き込みが起きるのを止める**ため。
 * 向こうを勝たせると、2台が同じ時刻の同じ内容を延々と押し付け合います。
 *
 * @param localUpdatedAt 手元にある同じ uid の更新時刻。無ければ null。
 */
fun decideMerge(incoming: Envelope, localUpdatedAt: Long?): MergeAction {
    if (localUpdatedAt != null && localUpdatedAt >= incoming.updatedAt) return MergeAction.Skip
    return if (incoming.deleted) MergeAction.Delete else MergeAction.Apply
}
