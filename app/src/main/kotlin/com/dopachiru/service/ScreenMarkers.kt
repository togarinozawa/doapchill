package com.dopachiru.service

import com.dopachiru.core.model.ScreenSignals
import kotlinx.serialization.Serializable

/**
 * ショートを戻す相手。アプリと、そのアプリの中のどの画面か。
 *
 * TikTok だけはアプリそのものが縦スワイプ動画なので、画面ではなくアプリで見る([wholeApp])。
 * 戻るを押してもアプリの中で前の動画に戻るだけなので、ホームへ出す。
 */
enum class ShortsTarget(
    val id: String,
    val label: String,
    val packages: Set<String>,
    val signal: String,
    val wholeApp: Boolean = false,
) {
    YOUTUBE("youtube", "YouTube ショート", setOf("com.google.android.youtube"), ScreenSignals.SHORT_VIDEO),
    INSTAGRAM("instagram", "Instagram リール", setOf("com.instagram.android"), ScreenSignals.REELS),
    TIKTOK(
        "tiktok",
        "TikTok(アプリごと)",
        setOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill"),
        ScreenSignals.SHORT_VIDEO,
        wholeApp = true,
    ),
    X("x", "X の動画フィード", setOf("com.twitter.android"), ScreenSignals.SHORT_VIDEO),
    FACEBOOK("facebook", "Facebook リール", setOf("com.facebook.katana"), ScreenSignals.REELS),
    ;

    companion object {
        fun of(id: String): ShortsTarget? = entries.firstOrNull { it.id == id }
        fun forPackage(pkg: String): ShortsTarget? = entries.firstOrNull { pkg in it.packages }
    }
}

/**
 * ショートを戻す設定。ルールではなく常に効く見張りとして持つ。
 *
 * @param markers 自分で足した目印。`パッケージ → resource-id(完全な名前)`。
 *   組み込みの目印が外れたとき、アプリを更新しなくても直せるようにするためにある。
 */
@Serializable
data class ShortsGuardSettings(
    val targets: Set<String> = emptySet(),
    val markers: Map<String, Set<String>> = emptyMap(),
) {
    fun isOn(target: ShortsTarget): Boolean = target.id in targets

    fun withMarker(pkg: String, viewId: String): ShortsGuardSettings =
        copy(markers = markers + (pkg to (markers[pkg].orEmpty() + viewId)))

    fun withoutMarker(pkg: String, viewId: String): ShortsGuardSettings {
        val rest = markers[pkg].orEmpty() - viewId
        return copy(markers = if (rest.isEmpty()) markers - pkg else markers + (pkg to rest))
    }
}

/**
 * 「どのアプリの、どの resource-id が見えたら、どの画面か」の対応表。
 *
 * **組み込みの id はどれも手元で確かめていない推測値。** アプリの更新でも変わる。
 * 取れなければ目印が空になり、戻しも画面ルールも素通しに倒れる(閉じ込めない)。
 * 外れていたら、設定の「目印を調べる」で実際の id を拾って足す。
 *
 * タブのボタン(Instagram の clips_tab など)は**目印にしない**。下のタブ列は
 * どの画面にも出ているので、ホームを見ているだけでリール扱いになる。
 */
object ScreenMarkers {

    private val BUILT_IN: Map<String, List<Pair<String, String>>> = mapOf(
        // YouTube: ショートは専用の縦スワイプ Pager を持つ
        "com.google.android.youtube" to listOf(
            "com.google.android.youtube:id/reel_recycler" to ScreenSignals.SHORT_VIDEO,
            "com.google.android.youtube:id/reel_player_page_container" to ScreenSignals.SHORT_VIDEO,
        ),
        // Instagram: リールの縦スワイプ
        "com.instagram.android" to listOf(
            "com.instagram.android:id/clips_viewer_view_pager" to ScreenSignals.REELS,
        ),
    )

    /** そのアプリで探す目印。組み込み + 自分で足したもの。 */
    fun markersFor(pkg: String, guard: ShortsGuardSettings): List<Pair<String, String>> {
        val custom = guard.markers[pkg].orEmpty()
        if (custom.isEmpty()) return BUILT_IN[pkg].orEmpty()
        val signal = ShortsTarget.forPackage(pkg)?.signal ?: ScreenSignals.SHORT_VIDEO
        return BUILT_IN[pkg].orEmpty() + custom.map { it to signal }
    }

    /** 組み込みの目印。設定画面で「最初から入っているもの」として見せる。 */
    fun builtInFor(pkg: String): List<String> = BUILT_IN[pkg].orEmpty().map { it.first }

    fun hasMarkers(pkg: String, guard: ShortsGuardSettings): Boolean =
        BUILT_IN.containsKey(pkg) || guard.markers[pkg].orEmpty().isNotEmpty()
}
