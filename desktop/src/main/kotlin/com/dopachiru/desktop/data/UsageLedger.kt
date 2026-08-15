package com.dopachiru.desktop.data

import com.dopachiru.core.engine.UsageSnapshot
import com.dopachiru.core.time.ResetPolicy
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * どのアプリをいつからいつまで前面に置いていたか。
 *
 * Android 版と同じ考え方で、開いている最中の区間も終端を持たせて伸ばしていく。
 * 終端を空のままにすると、プロセスが落ちたときに区間が閉じず、
 * 触っていない時間まで使用時間に化ける。
 *
 * 規模が小さいので全部メモリに置き、定期的に丸ごと JSON に書く。
 */
class UsageLedger(private val zone: ZoneId = ZoneId.systemDefault()) {

    private class Entry(val processName: String, val startSec: Long, var endSec: Long)

    private val lock = Any()
    private val entries = ArrayDeque<Entry>()
    private var open: Entry? = null

    fun restore(sessions: List<UsageSession>) = synchronized(lock) {
        if (entries.isNotEmpty()) return
        val cutoff = nowSec() - RETENTION_SEC
        sessions.filter { it.endSec >= cutoff }
            .sortedBy { it.startSec }
            .forEach { entries.addLast(Entry(it.processName, it.startSec, it.endSec)) }
    }

    fun snapshotForStorage(): List<UsageSession> = synchronized(lock) {
        entries.map { UsageSession(it.processName, it.startSec, it.endSec) }
    }

    /** 前面のアプリが変わった。null は「見るべきものが無い」(ロック中など)。 */
    fun onForegroundChanged(processName: String?, nowSec: Long = nowSec()) = synchronized(lock) {
        val current = open
        if (current != null && current.processName == processName) {
            current.endSec = nowSec
            return
        }
        if (current != null) {
            current.endSec = nowSec
            open = null
        }
        if (processName != null) {
            val entry = Entry(processName, nowSec, nowSec)
            entries.addLast(entry)
            open = entry
        }
        trim(nowSec)
    }

    /** 開きっぱなしの区間の終端を伸ばす。 */
    fun tick(nowSec: Long = nowSec()) = synchronized(lock) {
        open?.endSec = nowSec
    }

    fun snapshotFor(
        processName: String,
        now: LocalDateTime = LocalDateTime.now(zone),
    ): UsageSnapshot {
        val nowSeconds = now.atZone(zone).toEpochSecond()
        val history: List<Pair<Long, Long>>
        val openStart: Long?
        synchronized(lock) {
            history = entries.filter { it.processName == processName }.map { it.startSec to it.endSec }
            openStart = open?.takeIf { it.processName == processName }?.startSec
        }

        return object : UsageSnapshot {
            override val currentSessionMinutes: Int
                get() = openStart?.let { ((nowSeconds - it) / 60).toInt().coerceAtLeast(0) } ?: 0

            override fun usageMinutesIn(policy: ResetPolicy): Int {
                val from = policy.periodStart(now).atZone(zone).toEpochSecond()
                val total = history.sumOf { (start, end) ->
                    val effectiveEnd = if (openStart != null && start == openStart) nowSeconds else end
                    (effectiveEnd - maxOf(start, from)).coerceAtLeast(0)
                }
                return (total / 60).toInt()
            }

            override fun sessionCountIn(policy: ResetPolicy): Int {
                val from = policy.periodStart(now).atZone(zone).toEpochSecond()
                return history.count { (start, _) -> start >= from }
            }

            /**
             * いま開いている区間の手前に空いていた時間。
             * 現在時刻ではなく**開いた時刻を基準に**測る
             * (開いたまま経つと隙間が伸びて、途中で条件が外れる)。
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

    /** いま開いている区間を識別する種。開き直すと変わる。 */
    fun currentSessionSeed(): Long = synchronized(lock) { open?.startSec ?: 0L }

    /** いま前面にあるものの1つ前。無ければ null。 */
    fun previousProcess(): String? = synchronized(lock) {
        val current = open ?: return@synchronized null
        for (index in entries.indices.reversed()) {
            val entry = entries[index]
            if (entry === current) continue
            if (entry.startSec > current.startSec) continue
            return@synchronized entry.processName.takeIf { it != current.processName }
        }
        null
    }

    /** アプリごとの使用分数。多い順。 */
    fun breakdownIn(
        policy: ResetPolicy,
        now: LocalDateTime = LocalDateTime.now(zone),
    ): List<Pair<String, Int>> {
        val nowSeconds = now.atZone(zone).toEpochSecond()
        val from = policy.periodStart(now).atZone(zone).toEpochSecond()
        val perProcess = synchronized(lock) {
            val openStart = open?.startSec
            entries.groupBy { it.processName }.mapValues { (_, list) ->
                list.sumOf { entry ->
                    val end = if (openStart != null && entry.startSec == openStart) nowSeconds else entry.endSec
                    (end - maxOf(entry.startSec, from)).coerceAtLeast(0)
                }
            }
        }
        return perProcess.map { (process, sec) -> process to (sec / 60).toInt() }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
    }

    fun totalMinutesIn(
        policy: ResetPolicy,
        now: LocalDateTime = LocalDateTime.now(zone),
    ): Int = breakdownIn(policy, now).sumOf { it.second }

    private fun trim(nowSec: Long) {
        val cutoff = nowSec - RETENTION_SEC
        while (entries.isNotEmpty() && entries.first().endSec < cutoff && entries.first() !== open) {
            entries.removeFirst()
        }
    }

    private fun nowSec(): Long = System.currentTimeMillis() / 1000

    private companion object {
        /** 集計期間は最長でも1日なので、48時間あれば足りる。 */
        const val RETENTION_SEC = 48L * 3600
    }
}
