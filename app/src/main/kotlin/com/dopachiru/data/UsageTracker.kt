package com.dopachiru.data

import com.dopachiru.core.engine.UsageSnapshot
import com.dopachiru.core.time.ResetPolicy
import com.dopachiru.data.db.UsageDao
import com.dopachiru.data.db.UsageSessionEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * どのアプリをいつからいつまで開いていたかを記録する。
 *
 * ルール評価は AccessibilityService のコールバックから同期的に呼ばれるので、
 * 判定に要る範囲(直近48時間)はメモリに持ち、DB への書き込みは非同期に流す。
 *
 * 開いている最中のセッションも終端を持たせ、[tick] で伸ばしていく。
 * プロセスが強制終了されても、記録は最後の tick の時点で正しく閉じている。
 */
class UsageTracker(
    private val usageDao: UsageDao,
    private val scope: CoroutineScope,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private class Session(
        @Volatile var rowId: Long,
        val packageName: String,
        val startSec: Long,
        @Volatile var endSec: Long,
    )

    private val lock = Any()
    private val sessions = ArrayDeque<Session>()
    private var current: Session? = null

    /** フォアグラウンドのアプリが変わったときに呼ぶ。 */
    fun onForegroundChanged(packageName: String?, nowSec: Long = nowSeconds()) {
        synchronized(lock) {
            val open = current
            if (open != null && open.packageName == packageName) {
                open.endSec = nowSec
                return
            }

            if (open != null) {
                open.endSec = nowSec
                persistEnd(open, nowSec)
                current = null
            }

            if (packageName != null) {
                val session = Session(rowId = 0L, packageName = packageName, startSec = nowSec, endSec = nowSec)
                sessions.addLast(session)
                current = session
                scope.launch {
                    val id = usageDao.insert(
                        UsageSessionEntity(
                            packageName = packageName,
                            startEpochSec = nowSec,
                            endEpochSec = nowSec,
                        )
                    )
                    session.rowId = id
                }
            }
            trim(nowSec)
        }
    }

    /** 常駐サービスから定期的に呼び、開きっぱなしのセッションの終端を伸ばす。 */
    fun tick(nowSec: Long = nowSeconds()) {
        synchronized(lock) {
            val open = current ?: return
            open.endSec = nowSec
            persistEnd(open, nowSec)
        }
    }

    /** 起動直後にDBからメモリを温める。 */
    suspend fun warmUp() {
        val since = nowSeconds() - RETENTION_SEC
        val rows = usageDao.allSince(since)
        synchronized(lock) {
            if (sessions.isNotEmpty()) return
            rows.forEach {
                sessions.addLast(Session(it.id, it.packageName, it.startEpochSec, it.endEpochSec))
            }
        }
    }

    /** [packageName] の使用実績を、いまの時点で切り出す。 */
    fun snapshotFor(packageName: String, now: LocalDateTime = LocalDateTime.now(zone)): UsageSnapshot {
        val nowSec = now.atZone(zone).toEpochSecond()
        val history: List<Pair<Long, Long>>
        val openStart: Long?
        synchronized(lock) {
            history = sessions.filter { it.packageName == packageName }
                .map { it.startSec to it.endSec }
            openStart = current?.takeIf { it.packageName == packageName }?.startSec
        }

        return object : UsageSnapshot {
            override val currentSessionMinutes: Int
                get() = openStart?.let { ((nowSec - it) / 60).toInt().coerceAtLeast(0) } ?: 0

            override fun usageMinutesIn(policy: ResetPolicy): Int {
                val from = policy.periodStart(now).atZone(zone).toEpochSecond()
                val total = history.sumOf { (start, end) ->
                    // 開いている最中のセッションは現在時刻まで伸ばして数える
                    val effectiveEnd = if (openStart != null && start == openStart) nowSec else end
                    (effectiveEnd - maxOf(start, from)).coerceAtLeast(0)
                }
                return (total / 60).toInt()
            }

            override fun sessionCountIn(policy: ResetPolicy): Int {
                val from = policy.periodStart(now).atZone(zone).toEpochSecond()
                return history.count { (start, _) -> start >= from }
            }

            /**
             * いま開いているセッションの手前に空いていた時間。
             *
             * 「開き直し」の判定に使うので、開いている最中でも**開いた時刻を基準に**測る。
             * 現在時刻から測ると、開いたまま時間が経つほど隙間が伸びていき、
             * 途中で条件が外れてブロックが勝手に消える。
             */
            override val minutesSinceLastSession: Int?
                get() {
                    val start = openStart ?: return null
                    val previousEnd = history
                        .filter { (s, _) -> s < start }
                        .maxOfOrNull { (_, end) -> end }
                        ?: return null
                    return ((start - previousEnd) / 60).toInt().coerceAtLeast(0)
                }
        }
    }

    /** いま開いているセッションを識別する種。開くたびに変わる。 */
    fun currentSessionSeed(): Long = synchronized(lock) { current?.startSec ?: 0L }

    /**
     * いま開いているアプリの1つ前に前面にあったアプリ。
     * ホームやランチャーから開いたなら null(そこは記録していないため)。
     */
    fun previousPackage(): String? = synchronized(lock) {
        val open = current ?: return@synchronized null
        for (index in sessions.indices.reversed()) {
            val session = sessions[index]
            if (session === open) continue
            if (session.startSec > open.startSec) continue
            return@synchronized session.packageName.takeIf { it != open.packageName }
        }
        null
    }

    /** ダッシュボード用。全アプリ合計の使用分数。 */
    fun totalMinutesIn(policy: ResetPolicy, now: LocalDateTime = LocalDateTime.now(zone)): Int {
        val nowSec = now.atZone(zone).toEpochSecond()
        val from = policy.periodStart(now).atZone(zone).toEpochSecond()
        val total = synchronized(lock) {
            val openStart = current?.startSec
            sessions.sumOf { session ->
                val end = if (openStart != null && session.startSec == openStart) nowSec else session.endSec
                (end - maxOf(session.startSec, from)).coerceAtLeast(0)
            }
        }
        return (total / 60).toInt()
    }

    /** アプリごとの使用分数。多い順。 */
    fun breakdownIn(policy: ResetPolicy, now: LocalDateTime = LocalDateTime.now(zone)): List<Pair<String, Int>> {
        val nowSec = now.atZone(zone).toEpochSecond()
        val from = policy.periodStart(now).atZone(zone).toEpochSecond()
        val perPackage = synchronized(lock) {
            val openStart = current?.startSec
            sessions.groupBy { it.packageName }.mapValues { (_, list) ->
                list.sumOf { session ->
                    val end = if (openStart != null && session.startSec == openStart) nowSec else session.endSec
                    (end - maxOf(session.startSec, from)).coerceAtLeast(0)
                }
            }
        }
        return perPackage.map { (pkg, sec) -> pkg to (sec / 60).toInt() }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
    }

    suspend fun purgeOld() {
        usageDao.purgeBefore(nowSeconds() - PURGE_AFTER_SEC)
    }

    private fun persistEnd(session: Session, endSec: Long) {
        scope.launch {
            val id = session.rowId
            if (id != 0L) usageDao.updateEnd(id, endSec)
        }
    }

    private fun trim(nowSec: Long) {
        val cutoff = nowSec - RETENTION_SEC
        while (sessions.isNotEmpty() && sessions.first().endSec < cutoff && sessions.first() !== current) {
            sessions.removeFirst()
        }
    }

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000

    private companion object {
        /** メモリに載せておく範囲。集計期間は最長でも1日なので48時間あれば足りる。 */
        const val RETENTION_SEC = 48L * 3600

        /** DB から消す閾値。ダッシュボードの履歴表示ぶんは残す。 */
        const val PURGE_AFTER_SEC = 90L * 24 * 3600
    }
}
