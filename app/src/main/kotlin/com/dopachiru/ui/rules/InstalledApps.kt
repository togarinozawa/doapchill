package com.dopachiru.ui.rules

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import java.text.Collator
import java.util.Locale

data class AppInfo(
    val packageName: String,
    val label: String,
)

/** 端末にある「ランチャーから開けるアプリ」の一覧。自分自身は除く。 */
object InstalledApps {

    /**
     * 日本語の並び順。
     *
     * 単純な文字コード順だと、カタカナと平仮名が離れたり濁点付きが妙な位置に来る。
     * Collator を通すと ABC → あいうえお の順に落ち着く。
     * ただし漢字は読みではなく文字コードで並ぶ(読みは端末からは分からない)。
     */
    private val collator: Collator = Collator.getInstance(Locale.JAPANESE).apply {
        strength = Collator.SECONDARY
    }

    private val byLabel: Comparator<AppInfo> =
        Comparator { a, b -> collator.compare(a.label, b.label) }

    @Volatile
    private var cache: List<AppInfo>? = null

    private val iconCache = LruCache<String, ImageBitmap>(160)

    fun load(context: Context): List<AppInfo> {
        cache?.let { return it }
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .mapNotNull { it.activityInfo?.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName != context.packageName }
            .map { AppInfo(it.packageName, pm.getApplicationLabel(it).toString()) }
            .sortedWith(byLabel)
        cache = list
        return list
    }

    fun labelOf(context: Context, packageName: String): String =
        load(context).firstOrNull { it.packageName == packageName }?.label ?: packageName

    fun sortedByName(apps: List<AppInfo>): List<AppInfo> = apps.sortedWith(byLabel)

    /** 使用時間の多い順。実績が無いものは名前順で後ろに続く。 */
    fun sortedByUsage(apps: List<AppInfo>, usage: Map<String, Int>): List<AppInfo> =
        apps.sortedWith(
            compareByDescending<AppInfo> { usage[it.packageName] ?: 0 }.then(byLabel)
        )

    /** 選択済みを先頭に集める。ずらっと並んだ中から選んだものを見失わないように。 */
    fun selectedFirst(apps: List<AppInfo>, selected: Set<String>): List<AppInfo> =
        apps.sortedWith(
            compareByDescending<AppInfo> { it.packageName in selected }.then(byLabel)
        )

    /** 読み込み済みのアイコン。まだなら null(呼び出し側が裏で [loadIcon] する)。 */
    fun cachedIcon(packageName: String): ImageBitmap? = iconCache.get(packageName)

    /**
     * アイコンを読む。デコードが要るので、UI スレッドから呼ばないこと。
     * 端末によっては数百件あるので、表示されたぶんだけ読んでキャッシュする。
     */
    fun loadIcon(context: Context, packageName: String): ImageBitmap? {
        iconCache.get(packageName)?.let { return it }
        val bitmap = runCatching {
            context.packageManager
                .getApplicationIcon(packageName)
                .toBitmap(width = ICON_PX, height = ICON_PX)
                .asImageBitmap()
        }.getOrNull() ?: return null
        iconCache.put(packageName, bitmap)
        return bitmap
    }

    /** 48dp を高解像度の端末でも粗く見せない程度。 */
    private const val ICON_PX = 144
}
