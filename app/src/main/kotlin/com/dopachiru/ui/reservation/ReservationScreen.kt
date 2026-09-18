package com.dopachiru.ui.reservation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dopachiru.core.model.Reservation
import com.dopachiru.core.model.ReservationRules
import com.dopachiru.core.model.Target
import com.dopachiru.data.AppLabels
import com.dopachiru.runtime.DopaRuntime
import com.dopachiru.ui.rules.AppPickerDialog
import com.dopachiru.ui.rules.InstalledApps
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 予約を取る・見る・取り消す画面。
 *
 * 予約は「冷静なうちに、この時間だけ使うと先に決めておく」もの。だから
 * いまから [ReservationRules.MIN_LEAD_MINUTES] より手前には取れない。
 * 少し先にしか置けないことが、「よし、この時間で済ませる」という予定に変える。
 *
 * 時刻は日時ピッカーを出さず、30分刻みの前後ボタンで動かす ── 予約は
 * 「だいたいこの時間」で足り、細かく指定させると取るのが億劫になる。
 */
@Composable
fun ReservationScreen() {
    val context = LocalContext.current
    val reservations by DopaRuntime.reservations.reservations.collectAsState()
    val leadMinutes by DopaRuntime.settings.reservationLeadMinutes.collectAsState(
        initial = ReservationRules.MIN_LEAD_MINUTES,
    )

    val roster by DopaRuntime.devices.collectAsState(initial = emptyList())
    val me = DopaRuntime.myDeviceId

    var picked by remember { mutableStateOf(setOf<String>()) }
    var showPicker by remember { mutableStateOf(false) }

    /** どの端末の枠か。null = すべての端末。 */
    var forDevice by remember { mutableStateOf<String?>(null) }
    // 端末を変えたら選んだアプリは捨てる。PC のプロセス名をスマホの枠に
    // 持ち越しても、どこにも当たらないルールになるだけ
    val targetDevice = forDevice
    val foreignApps = remember(targetDevice, roster) {
        val platform = roster.firstOrNull { it.deviceId == targetDevice }?.platform
        if (targetDevice == null || targetDevice == me || platform.isNullOrBlank()) {
            emptyList()
        } else {
            AppLabels.of(context, platform)
        }
    }
    var startSec by remember { mutableLongStateOf(0L) }
    var durationMinutes by remember { mutableIntStateOf(30) }
    var refused by remember { mutableStateOf(false) }

    val now = System.currentTimeMillis() / 1000
    val earliest = roundUpToHalfHour(now + leadMinutes * 60L)
    // 初期値、または過去に流れた開始を、いま置ける最短に引き上げる
    if (startSec < earliest) startSec = earliest

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(
                "先に「この時間だけ使う」と決めておく枠です。" +
                    "いまから" + describeLead(leadMinutes) + "より手前には取れません ── " +
                    "少し先にしか置けないから、冷静に決められます。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("新しく予約する", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(12.dp))

                    // 端末が2台以上あるときだけ聞く。1台しかない人に端末の話をさせない
                    if (roster.size >= 2) {
                        Text("どの端末の枠", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.height(4.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = forDevice == null,
                                onClick = { forDevice = null; picked = emptySet() },
                                label = { Text("すべての端末") },
                            )
                            roster.forEach { device ->
                                FilterChip(
                                    selected = forDevice == device.deviceId,
                                    onClick = { forDevice = device.deviceId; picked = emptySet() },
                                    label = {
                                        Text(
                                            device.displayName +
                                                if (device.deviceId == me) "(この端末)" else "",
                                        )
                                    },
                                )
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                    }

                    Text("対象アプリ", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(4.dp))
                    if (picked.isEmpty()) {
                        Text(
                            "まだ選んでいません",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        picked.forEach { pkg ->
                            Text(
                                "・" + if (foreignApps.isEmpty()) {
                                    InstalledApps.labelOf(context, pkg)
                                } else {
                                    foreignApps.firstOrNull { it.first == pkg }?.second ?: pkg
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))

                    if (foreignApps.isEmpty()) {
                        OutlinedButton(onClick = { showPicker = true }) { Text("選ぶ") }
                        if (targetDevice != null && targetDevice != me) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "その端末のアプリがまだ届いていません。向こうで一度ルールかタグに使うと、" +
                                    "名前がこちらに届いて選べるようになります。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        // よその端末のアプリは、向こうが送ってきた名札から選ぶ。
                        // こちらには chrome.exe の一覧など無い
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            foreignApps.forEach { (id, label) ->
                                FilterChip(
                                    selected = id in picked,
                                    onClick = {
                                        picked = if (id in picked) picked - id else picked + id
                                    },
                                    label = { Text(label) },
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    Text("いつから", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        formatStart(startSec),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { startSec = (startSec - 1800L).coerceAtLeast(earliest) },
                            enabled = startSec - 1800L >= earliest,
                        ) { Text("−30分") }
                        OutlinedButton(onClick = { startSec += 1800L }) { Text("+30分") }
                        OutlinedButton(onClick = { startSec += 86400L }) { Text("+1日") }
                    }

                    Spacer(Modifier.height(16.dp))
                    Text("どれくらい", style = MaterialTheme.typography.labelLarge)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = {
                                durationMinutes = (durationMinutes - 5)
                                    .coerceAtLeast(ReservationRules.MIN_DURATION_MINUTES)
                            },
                            enabled = durationMinutes > ReservationRules.MIN_DURATION_MINUTES,
                        ) { Text("−") }
                        Text(durationMinutes.toString() + " 分", style = MaterialTheme.typography.titleMedium)
                        TextButton(
                            onClick = {
                                durationMinutes = (durationMinutes + 5)
                                    .coerceAtMost(ReservationRules.MAX_DURATION_MINUTES)
                            },
                            enabled = durationMinutes < ReservationRules.MAX_DURATION_MINUTES,
                        ) { Text("+") }
                    }

                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            val booked = DopaRuntime.reservations.book(
                                target = Target(packages = picked),
                                startEpochSec = startSec,
                                endEpochSec = startSec + durationMinutes * 60L,
                                minLeadMinutes = leadMinutes,
                                devices = setOfNotNull(forDevice),
                            )
                            refused = booked == null
                            if (booked != null) picked = emptySet()
                        },
                        enabled = picked.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("予約する") }

                    if (refused) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "その時刻には取れません。もう少し先にしてください。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        item {
            Text("これからの予約", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }

        if (reservations.isEmpty()) {
            item {
                Text(
                    "まだありません。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(reservations) { reservation ->
                ReservationRow(
                    reservation = reservation,
                    label = { pkg -> InstalledApps.labelOf(context, pkg) },
                    onCancel = { DopaRuntime.reservations.cancel(reservation.uid) },
                )
            }
        }
    }

    if (showPicker) {
        AppPickerDialog(
            selected = picked,
            onToggle = { pkg -> picked = if (pkg in picked) picked - pkg else picked + pkg },
            onDismiss = { showPicker = false },
        )
    }
}

@Composable
private fun ReservationRow(
    reservation: Reservation,
    label: (String) -> String,
    onCancel: () -> Unit,
) {
    val now = System.currentTimeMillis() / 1000
    val active = reservation.coversAt(now)
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    reservation.target.packages.joinToString("、") { label(it) }
                        .ifBlank { "対象なし" },
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    formatRange(reservation.startEpochSec, reservation.endEpochSec) +
                        if (active) "  ・いま使えます" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onCancel) { Text("取り消す") }
        }
    }
}

// ---- 時刻の見せ方 ------------------------------------------------------

private val dayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("M/d(E) HH:mm")
private val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private fun roundUpToHalfHour(sec: Long): Long {
    val half = 1800L
    return ((sec + half - 1) / half) * half
}

private fun formatStart(sec: Long): String =
    Instant.ofEpochSecond(sec).atZone(ZoneId.systemDefault()).format(dayFormat)

private fun formatRange(startSec: Long, endSec: Long): String {
    val start = Instant.ofEpochSecond(startSec).atZone(ZoneId.systemDefault())
    val end = Instant.ofEpochSecond(endSec).atZone(ZoneId.systemDefault())
    return start.format(dayFormat) + "〜" + end.format(timeFormat)
}

private fun describeLead(minutes: Int): String = when {
    minutes % 60 == 0 && minutes >= 60 -> (minutes / 60).toString() + "時間後"
    minutes == 0 -> "すぐ"
    else -> minutes.toString() + "分後"
}
