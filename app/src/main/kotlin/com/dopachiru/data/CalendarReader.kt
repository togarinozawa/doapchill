package com.dopachiru.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.dopachiru.core.engine.CalendarState
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * 端末のカレンダーから予定を読む。
 *
 * Google カレンダーは端末に同期された時点で標準のカレンダー Provider に入るので、
 * OAuth も Google Cloud のプロジェクトも API キーも要らない。
 * READ_CALENDAR のランタイム権限を1つ取るだけで、同期済みの全アカウントの予定が見える。
 *
 * 判定はブロック判定と同じ頻度で呼ばれるため、Provider への問い合わせは
 * [refresh] のときだけ行い、結果はメモリに置く。
 */
class CalendarReader(private val context: Context) {

    data class Event(
        val title: String,
        val startMs: Long,
        val endMs: Long,
        val calendarName: String,
    )

    @Volatile
    private var events: List<Event> = emptyList()

    @Volatile
    var permissionGranted: Boolean = false
        private set

    fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.READ_CALENDAR,
    ) == PackageManager.PERMISSION_GRANTED

    /** 前後の予定を読み直す。常駐サービスから毎分呼ばれる。 */
    fun refresh(nowMs: Long = System.currentTimeMillis()) {
        if (!hasPermission()) {
            permissionGranted = false
            events = emptyList()
            return
        }
        permissionGranted = true
        events = runCatching { query(nowMs - PAST_WINDOW_MS, nowMs + FUTURE_WINDOW_MS) }
            .getOrDefault(emptyList())
    }

    /** いまの時点のカレンダー状況。 */
    fun state(nowMs: Long = System.currentTimeMillis()): CalendarState {
        val current = events.filter { nowMs >= it.startMs && nowMs < it.endMs }
        if (current.isEmpty() && !permissionGranted) return CalendarState.NONE

        return object : CalendarState {
            override val busy: Boolean = current.isNotEmpty()

            override fun inEventMatching(keyword: String): Boolean {
                if (keyword.isBlank()) return current.isNotEmpty()
                return current.any { it.title.contains(keyword, ignoreCase = true) }
            }

            override val currentTitles: List<String> = current.map { it.title }
        }
    }

    /** 設定画面のプレビュー用。これから24時間ぶんの予定。 */
    fun upcoming(nowMs: Long = System.currentTimeMillis(), limit: Int = 10): List<Event> =
        events.filter { it.endMs > nowMs }.sortedBy { it.startMs }.take(limit)

    private fun query(fromMs: Long, toMs: Long): List<Event> {
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().apply {
            ContentUris.appendId(this, fromMs)
            ContentUris.appendId(this, toMs)
        }.build()

        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
            CalendarContract.Instances.STATUS,
        )

        val zone = ZoneId.systemDefault()
        val result = ArrayList<Event>()

        context.contentResolver.query(
            uri,
            projection,
            null,
            null,
            "${CalendarContract.Instances.BEGIN} ASC",
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                // 取り消された予定は数えない
                val status = if (cursor.isNull(5)) null else cursor.getInt(5)
                if (status == CalendarContract.Instances.STATUS_CANCELED) continue

                val title = cursor.getString(0)?.trim().orEmpty()
                if (title.isEmpty()) continue

                val beginRaw = cursor.getLong(1)
                val endRaw = cursor.getLong(2)
                val allDay = cursor.getInt(3) == 1

                val (start, end) = if (allDay) {
                    // 終日予定は UTC の 0:00 で入っているので、端末のローカル日に読み替える
                    val startDate = Instant.ofEpochMilli(beginRaw).atZone(ZoneOffset.UTC).toLocalDate()
                    val endDate = Instant.ofEpochMilli(endRaw).atZone(ZoneOffset.UTC).toLocalDate()
                    startDate.atStartOfDay(zone).toInstant().toEpochMilli() to
                        endDate.atStartOfDay(zone).toInstant().toEpochMilli()
                } else {
                    beginRaw to endRaw
                }
                if (end <= start) continue

                result += Event(
                    title = title,
                    startMs = start,
                    endMs = end,
                    calendarName = cursor.getString(4)?.trim().orEmpty(),
                )
            }
        }
        return result
    }

    private companion object {
        const val PAST_WINDOW_MS = 24L * 60 * 60 * 1000
        const val FUTURE_WINDOW_MS = 36L * 60 * 60 * 1000
    }
}
