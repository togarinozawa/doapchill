package com.dopachiru.core.update

import kotlinx.serialization.Serializable

/**
 * 配っている版ひとつぶん。サーバーの `/version` が返す形。
 *
 * 大きさまで貰っているのは、**落とし終わったかどうかを自分で確かめる**ため。
 * 途中で切れたファイルをそのままインストーラに渡すと、
 * 「壊れています」とだけ言われて、原因が回線だと分かりません。
 */
@Serializable
data class ReleaseBuild(
    val version: String = "",
    /** `dopachiru-0.24.0.apk` のような、置き場所に付ける名前。 */
    val fileName: String = "",
    val url: String = "",
    val sizeBytes: Long = 0L,
) {
    val isUsable: Boolean get() = version.isNotBlank() && url.startsWith("https://")

    /** `12.3 MB` の形。落とす前に「これくらい」と見せるため。 */
    fun sizeLabel(): String {
        if (sizeBytes <= 0L) return ""
        val mb = sizeBytes / 1024.0 / 1024.0
        return if (mb >= 1.0) String.format("%.1f MB", mb) else String.format("%d KB", sizeBytes / 1024)
    }
}

/**
 * いま配っているもの一式。
 *
 * 端末ごとに別の欄を見ます。**片方だけ出した版があっても道連れにしない**ため、
 * サーバー側で別々に探してあります。
 */
@Serializable
data class LatestRelease(
    val android: ReleaseBuild? = null,
    val windows: ReleaseBuild? = null,
    /** リリースに書いた説明。長いので端末側で畳んで出します。 */
    val notes: String = "",
    val publishedAt: String = "",
)

/** 更新を見に行く先。同期を繋いでいない端末でも見られるように、既定を持たせてある。 */
object UpdateEndpoint {
    /** 同期と同じ住所。2か所に書くと、片方だけ直したときに食い違う。 */
    val DEFAULT_BASE_URL: String = com.dopachiru.core.sync.SyncDefaults.BASE_URL

    /**
     * 同期の設定があればそれを使い、無ければ既定。
     *
     * 同期を切っている端末でも更新は知りたい ── 更新の確認は
     * **押したときだけ通信する**ので、切っているつもりが漏れる心配はありません。
     */
    fun resolve(configuredBaseUrl: String): String =
        configuredBaseUrl.trim().ifBlank { DEFAULT_BASE_URL }
}
