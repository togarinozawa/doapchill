package com.dopachiru.core.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 端末間で配るものの形。
 *
 * ## 配るだけで、取り締まりには関わりません
 *
 * 各端末はローカルのルールで判定します。サーバーが落ちていても、圏外でも、
 * 機内モードでも制限は効いたまま。ここが止まって起きるのは
 * **「別の端末で足したルールがまだ届かない」だけ**です。
 *
 * 逆に言えば、**判定をここに持たせてはいけません。**
 * ネットが切れれば外れる制限は、機内モードにするだけで抜けられる制限です。
 */
object SyncKinds {
    const val RULES = "rules"
    const val TAGS = "tags"
    const val GATES = "gates"
    const val CHANGE_REQUESTS = "changeRequests"

    /**
     * 識別子 → 人が読む名前の対応表。
     *
     * 実績には `com.twitter.android` や `chrome.exe` しか入っていないので、
     * **これが無いと別の端末の実績を読める形で出せません。**
     * 実績の中に名前を埋めないのは、同じ名前を日数ぶん繰り返すことになるため。
     */
    const val APPS = "apps"

    val ALL = listOf(RULES, TAGS, GATES, CHANGE_REQUESTS, APPS)
}

/**
 * 1件ぶんの包み。中身([payload])はサーバーが解釈しません。
 *
 * 削除は行を消さず [deleted] の墓標にします。消してしまうと、
 * オフラインだった端末が「まだある」と思って復活させてしまいます。
 */
@Serializable
data class Envelope(
    val uid: String,
    val updatedAt: Long,
    val deleted: Boolean = false,
    val payload: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class SyncRequest(
    val deviceId: String,
    val since: Long = 0L,
    val changes: Map<String, List<Envelope>> = emptyMap(),
)

@Serializable
data class SyncResponse(
    val rev: Long = 0L,
    val serverTime: Long = 0L,
    val changes: Map<String, List<Envelope>> = emptyMap(),
) {
    fun of(kind: String): List<Envelope> = changes[kind].orEmpty()
}

@Serializable
data class PingResponse(val ok: Boolean = false, val rev: Long = 0L, val serverTime: Long = 0L)

/**
 * アプリの名札。
 *
 * `id` は端末ごとの識別子(Android はパッケージ名、Windows は実行ファイル名)。
 * **同じ名前で違うものを指しうる**ので、同期の鍵は `platform:id` の形にします
 * ([uid])。ここを分けないと、両方の端末が同じ鍵に別の名前を書き合って、
 * 同期のたびに名前が入れ替わります。
 */
@Serializable
data class AppInfo(
    val id: String,
    val label: String,
    val platform: String,
) {
    val uid: String get() = "$platform:$id"

    companion object {
        const val ANDROID = "android"
        const val WINDOWS = "windows"

        /** `android:com.example.app` を元に戻す。読めなければ null。 */
        fun idOf(uid: String): Pair<String, String>? {
            val at = uid.indexOf(':')
            if (at <= 0 || at == uid.length - 1) return null
            return uid.substring(0, at) to uid.substring(at + 1)
        }
    }
}

/**
 * 1日ぶんの実績。**端末ごとに分けて持ちます。**
 *
 * 上書きで合算にすると「スマホ30分 + PC20分」の日が 20分になります。
 * 分けておけば、「全端末で1日30分」も「PC だけで1日2時間」も同じ記録から出せます。
 */
@Serializable
data class UsageDay(
    val date: String,
    val totalMinutes: Int = 0,
    /** 識別子 → 分。名前は [SyncKinds.APPS] 側に持つ。 */
    val perApp: Map<String, Int> = emptyMap(),
    val blockShownCount: Int = 0,
    val overrideCount: Int = 0,
)

@Serializable
data class UsageUpload(val deviceId: String, val days: List<UsageDay> = emptyList())

@Serializable
data class UsageReport(val days: List<UsageDate> = emptyList()) {
    @Serializable
    data class UsageDate(
        val date: String,
        @SerialName("byDevice") val byDevice: Map<String, UsageDay> = emptyMap(),
    )
}

/** 同期の設定。端末ごとに持つ。 */
@Serializable
data class SyncSettings(
    val enabled: Boolean = false,
    /** 例: `https://dopa.togar.dev` */
    val baseUrl: String = "",
    /** サーバーと分け合う合言葉。 */
    val token: String = "",
    /** この端末の名前。実績を端末ごとに分けて出すときの見出しになる。 */
    val deviceId: String = "",
    /** 最後に受け取った版数。次はこの続きから。 */
    val since: Long = 0L,
    val lastSyncedAtSec: Long = 0L,
    val lastError: String = "",
) {
    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && token.isNotBlank() && deviceId.isNotBlank()
}

/** 中身を見ない包みを作るための小道具。 */
fun envelopeOf(uid: String, updatedAt: Long, payload: JsonElement, deleted: Boolean = false): Envelope =
    Envelope(uid, updatedAt, deleted, payload as? JsonObject ?: JsonObject(emptyMap()))
