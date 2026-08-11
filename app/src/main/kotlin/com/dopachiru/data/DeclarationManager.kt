package com.dopachiru.data

import com.dopachiru.data.db.DeclarationDao
import com.dopachiru.data.db.DeclarationEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * 「開く前に宣言させる」で申告した持ち時間の管理。
 *
 * 残り時間の判定はブロック判定と同じ頻度で呼ばれるのでメモリ上で行い、
 * 復帰できるように DB にも書き戻す。
 *
 * 宣言は「その回の使用ぶん」なので、対象アプリから一定時間離れたら破棄する。
 * 開き直すたびに宣言し直させるのが、この機能の狙いそのものであるため。
 * 総量そのものを縛りたい場合は「合計使用時間」の条件と組み合わせる。
 */
class DeclarationManager(
    private val dao: DeclarationDao,
    private val scope: CoroutineScope,
) {
    private class Active(
        @Volatile var rowId: Long,
        val budgetSec: Long,
        @Volatile var consumedSec: Long,
        @Volatile var lastTickSec: Long,
        /** 最後に対象アプリが前面にいた時刻。離れすぎたら破棄する。 */
        @Volatile var lastForegroundSec: Long,
    )

    private val active = ConcurrentHashMap<String, Active>()

    suspend fun warmUp() {
        val now = nowSeconds()
        dao.allActive().forEach {
            active[it.packageName] = Active(
                rowId = it.id,
                budgetSec = it.budgetMinutes * 60L,
                consumedSec = it.consumedSec,
                lastTickSec = now,
                lastForegroundSec = now,
            )
        }
    }

    /** 宣言していなければ null、していれば残り分数(使い切っていれば 0 以下)。 */
    fun remainingMinutes(packageName: String): Int? {
        val entry = active[packageName] ?: return null
        // 残り30秒は「残り0分」として扱う(切り上げない)
        return ((entry.budgetSec - entry.consumedSec) / 60).toInt()
    }

    fun hasDeclaration(packageName: String): Boolean = active.containsKey(packageName)

    fun declare(packageName: String, minutes: Int, reason: String) {
        val now = nowSeconds()
        val entry = Active(
            rowId = 0L,
            budgetSec = minutes * 60L,
            consumedSec = 0L,
            lastTickSec = now,
            lastForegroundSec = now,
        )
        // DB 書き込みを待たずに効かせる
        active[packageName] = entry

        scope.launch {
            dao.deactivateAllFor(packageName)
            val id = dao.insert(
                DeclarationEntity(
                    packageName = packageName,
                    budgetMinutes = minutes,
                    reason = reason,
                    declaredAtEpochSec = now,
                )
            )
            // まだ同じ宣言が生きていれば行を結びつける。
            // 差し替わっていたら、いま作った行は使わないので落としておく。
            if (active[packageName] === entry) entry.rowId = id else dao.deactivate(id)
        }
    }

    /**
     * フォアグラウンドにいるあいだ、宣言ぶんを消費させる。
     * 常駐サービスから毎分呼ばれる。
     */
    fun tick(foregroundPackage: String?, nowSec: Long = nowSeconds()) {
        for ((pkg, entry) in active) {
            if (pkg == foregroundPackage) {
                val delta = (nowSec - entry.lastTickSec).coerceIn(0, MAX_TICK_DELTA_SEC)
                entry.lastTickSec = nowSec
                entry.lastForegroundSec = nowSec
                if (delta > 0) {
                    entry.consumedSec += delta
                    val rowId = entry.rowId
                    val consumed = entry.consumedSec
                    if (rowId != 0L) scope.launch { dao.updateConsumed(rowId, consumed) }
                }
            } else {
                entry.lastTickSec = nowSec
                if (nowSec - entry.lastForegroundSec >= AWAY_EXPIRY_SEC) clear(pkg)
            }
        }
    }

    /** 宣言を破棄する。次に開いたときは、また宣言から始まる。 */
    fun clear(packageName: String) {
        val entry = active.remove(packageName) ?: return
        val rowId = entry.rowId
        if (rowId != 0L) scope.launch { dao.deactivate(rowId) }
    }

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000

    private companion object {
        /** 端末がスリープしていた時間を丸ごと消費に計上しないための上限。 */
        const val MAX_TICK_DELTA_SEC = 300L

        /** これだけ対象アプリから離れたら、その回の宣言は終わりとみなす。 */
        const val AWAY_EXPIRY_SEC = 10 * 60L
    }
}
