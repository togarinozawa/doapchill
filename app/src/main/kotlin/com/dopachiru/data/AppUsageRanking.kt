package com.dopachiru.data

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import com.dopachiru.core.time.ResetPolicy
import com.dopachiru.runtime.DopaRuntime

/**
 * アプリごとの使用時間(分)。多い順に並べるために使う。
 *
 * 出どころは2つ。
 *  1. 端末の使用状況 ── 直近1週間ぶん。**ドパチルを入れる前の実績も見える**ので、
 *     初めてルールを作るときに「自分が実際に何を触っているか」が出る。
 *     「使用状況へのアクセス」の許可が要る(既定では下りていない)。
 *  2. ドパチル自身の記録 ── 許可が無いときの代わり。記録はメモリ上48時間ぶん。
 *
 * どちらも取れなければ空を返す。呼び出し側は名前順に落ちるだけ。
 */
object AppUsageRanking {

    /** 「使用状況へのアクセス」が下りているか。 */
    fun hasUsageAccess(context: Context): Boolean = runCatching {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        ops.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        ) == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    /** パッケージ名 → 使用分数。 */
    fun load(context: Context): Map<String, Int> {
        val fromSystem = if (hasUsageAccess(context)) querySystem(context) else emptyMap()
        if (fromSystem.isNotEmpty()) return fromSystem
        return runCatching {
            DopaRuntime.usage.breakdownIn(ResetPolicy()).toMap()
        }.getOrDefault(emptyMap())
    }

    private fun querySystem(context: Context): Map<String, Int> = runCatching {
        val manager = context.getSystemService(UsageStatsManager::class.java)
            ?: return emptyMap()
        val now = System.currentTimeMillis()
        val stats = manager.queryUsageStats(
            UsageStatsManager.INTERVAL_BEST,
            now - WINDOW_MS,
            now,
        ) ?: return emptyMap()

        // 同じパッケージが複数の区間で返ることがあるので足し合わせる
        val perPackage = HashMap<String, Long>()
        for (stat in stats) {
            val ms = stat.totalTimeInForeground
            if (ms <= 0) continue
            perPackage[stat.packageName] = (perPackage[stat.packageName] ?: 0L) + ms
        }
        perPackage
            .mapValues { (_, ms) -> (ms / 60_000L).toInt() }
            .filterValues { it > 0 }
    }.getOrDefault(emptyMap())

    private const val WINDOW_MS = 7L * 24 * 60 * 60 * 1000
}
