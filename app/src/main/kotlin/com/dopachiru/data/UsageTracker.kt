package com.dopachiru.data

import com.dopachiru.core.engine.UsageSnapshot
import com.dopachiru.core.engine.UsageSpans
import com.dopachiru.core.engine.UsageWindows
import com.dopachiru.core.engine.WindowUsage
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

    /**
     * 休憩をはさむまでの使用時間(分)。
     *
     * [matches] に当たるアプリをまとめて数えるので、ルールがタグで括ってあれば
     * グループ合計になる。アプリを渡り歩いても切れない。
     *
     * @param breakMinutes これだけ対象を触っていない時間があれば、そこから数え直す。
     */
    fun minutesSinceBreak(
        breakMinutes: Int,
        nowSec: Long = nowSeconds(),
        matches: (String) -> Boolean,
    ): Int = UsageSpans.minutesSinceBreak(spansOf(matches, nowSec), breakMinutes, nowSec)

    /**
     * いま張られている「持ち時間の窓」。
     *
     * [minutesSinceBreak] と同じく [matches] に当たるアプリをまとめて見るが、
     * こちらは離れても数え直さない ── 窓は最初に触った時刻に張られ、
     * 閉じても消えない。詳しくは [UsageWindows]。
     */
    fun windowUsage(
        windowMinutes: Int,
        nowSec: Long = nowSeconds(),
        matches: (String) -> Boolean,
    ): WindowUsage = UsageWindows.current(spansOf(matches, nowSec), windowMinutes, nowSec)

    /** 対象に当たる区間。開いている最中のものは現在時刻まで伸ばす。 */
    private fun spansOf(matches: (String) -> Boolean, nowSec: Long): List<Pair<Long, Long>> =
        synchronized(lock) {
            val openStart = current?.takeIf { matches(it.packageName) }?.startSec
            sessions.filter { matches(it.packageName) }.map { session ->
                val end = if (openStart != null && session.startSec == openStart) nowSec else session.endSec
                session.startSec to end
            }
        }

    /**
     * 対象アプリを前回いつまで使っていたか ── いまから何分前に終わったか。
     * 一度も使っていなければ null。
     *
     * 「前回からN時間あける」の判定用。[matches] に当たるものをまとめて見るので、
     * タグで括ってあればグループ全体で最後に触った時刻になる。
     *
     * いま開いている一続きは「前回」に数えない。しかも[minutesSinceLastSession]と同じく
     * **開いた時刻を基準に**測る ── 現在時刻から測ると、開きっぱなしで間隔が育って、
     * 使っている途中で条件が外れてしまう。
     */
    fun minutesSinceLastUse(nowSec: Long = nowSeconds(), matches: (String) -> Boolean): Int? {
        synchronized(lock) {
            val open = current?.takeIf { matches(it.packageName) }
            // 基準時刻: いま対象を開いていればその開始、開いていなければ現在時刻
            val reference = open?.startSec ?: nowSec
            val previousEnd = sessions
                .filter { matches(it.packageName) && it !== open && it.startSec < reference }
                .maxOfOrNull { it.endSec }
                ?: return null
            return ((reference - previousEnd) / 60).toInt().coerceAtLeast(0)
        }
    }

    /**
     * その日はじめて端末に触った時刻。まだ触っていなければ null。
     *
     * 「起きてから◯分後に集中を始める」の起点。**起床時刻そのものは測れません**が、
     * 寝ているあいだは何も開かないので、日付が変わってから最初に開いたアプリが
     * 実用上そこに当たります。画面を点けただけで何も開かなかったぶんは数えませんが、
     * それは**眺めてもいない**ということなので、取り上げるものが無くて構いません。
     *
     * @param fromSec その日の始まり([com.dopachiru.core.time.ResetPolicy] の区切り)。
     */
    fun firstUseSecSince(fromSec: Long): Long? = synchronized(lock) {
        sessions.filter { it.startSec >= fromSec }.minOfOrNull { it.startSec }
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
