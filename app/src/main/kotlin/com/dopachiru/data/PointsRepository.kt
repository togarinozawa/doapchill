package com.dopachiru.data

import com.dopachiru.core.points.PointEvent
import com.dopachiru.core.points.PointReason
import com.dopachiru.data.db.PointEventDao
import com.dopachiru.data.db.PointEventEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * ポイントの出納。
 *
 * 残高そのものは持たず、増減の履歴だけを残して合計を残高とする。
 * 「なぜ減ったのか」を後から辿れないと、身に覚えのない減り方をしたときに
 * 仕組みごと信用できなくなるため。
 *
 * 押し切りの可否を判断する場面は同期的に呼ばれるので、残高はメモリにも持つ。
 */
class PointsRepository(
    private val dao: PointEventDao,
    private val scope: CoroutineScope,
) {
    /** 判定から同期的に読む残高。DB の合計と同じ値に保つ。 */
    private val cachedBalance = AtomicInteger(0)

    val balance: Flow<Int> = dao.observeBalance()

    val recent: Flow<List<PointEvent>> =
        dao.observeRecent(100).map { rows -> rows.map { it.toEvent() } }

    suspend fun warmUp() {
        cachedBalance.set(dao.balance())
    }

    /** いまの残高。ブロック画面から同期的に読む。 */
    fun currentBalance(): Int = cachedBalance.get()

    /**
     * ポイントを動かす。
     *
     * @param dedupKey 一度きりにしたい出来事の鍵。同じ鍵の2件目は黙って捨てられる。
     * @param floor 残高の下限。際限なく沈むと、そこから何をしても押し切れなくなる。
     */
    fun record(
        delta: Int,
        reason: PointReason,
        note: String = "",
        dedupKey: String? = null,
        floor: Int = Int.MIN_VALUE,
    ) {
        if (delta == 0) return

        // 下限に当たっているぶんは差し引く。0 になったら何も起きない
        val effective = if (delta < 0) {
            val room = cachedBalance.get() - floor
            if (room <= 0) return
            maxOf(delta, -room)
        } else {
            delta
        }
        if (effective == 0) return

        // 鍵つきは重複しうるので、確定してからキャッシュに足す。
        // 鍵なしは必ず入るので、先に足して押し切り判定に間に合わせる
        if (dedupKey == null) cachedBalance.addAndGet(effective)

        scope.launch {
            val rowId = dao.insert(
                PointEventEntity(
                    delta = effective,
                    reason = reason.name,
                    note = note,
                    atEpochSec = System.currentTimeMillis() / 1000,
                    dedupKey = dedupKey,
                )
            )
            // 捨てられていたら -1 が返る
            if (dedupKey != null && rowId != -1L) cachedBalance.addAndGet(effective)
        }
    }

    /** 開発ツール専用。 */
    fun clearAll() {
        cachedBalance.set(0)
        scope.launch { dao.deleteAll() }
    }
}

private fun PointEventEntity.toEvent(): PointEvent = PointEvent(
    id = id,
    delta = delta,
    reason = runCatching { PointReason.valueOf(reason) }.getOrDefault(PointReason.MANUAL),
    note = note,
    atEpochSec = atEpochSec,
)
