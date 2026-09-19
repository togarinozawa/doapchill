package com.dopachiru.ui.reservation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dopachiru.core.model.BookingCheck
import com.dopachiru.core.model.Reservation
import com.dopachiru.core.model.ReservationPolicy
import com.dopachiru.core.model.ReservationRules
import com.dopachiru.core.model.Target
import com.dopachiru.core.sync.DeviceInfo
import com.dopachiru.runtime.DopaRuntime
import com.dopachiru.ui.rules.AppPickerDialog
import com.dopachiru.ui.rules.InstalledApps
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * 予約を取る・見る・取り消す画面。
 *
 * ## 予約は「例外」であって「予定」ではない
 *
 * 予約が効くのは**本来ダメな時間の中**でだけです。時間帯ルールや常時ブロックで
 * 塞いである相手に、「この枠だけは通す」と穴を開けるのが予約の役目
 * (`outside_reservation` 条件と組む)。塞いでいない相手を予約しても
 * いつでも開くので、何も起きません。だから型ごとに**塞ぐルールがあるか**を見て、
 * 無ければそこを真っ先に言います。
 *
 * ## 型を先に決めてから取る
 *
 * 好きなアプリを好きなだけ予約できるなら、それは制限ではありません。
 * 欲しくなってから条件を決めると、欲しい側に有利な条件になります。
 *
 * だから**枠の型**(何を・1回どれくらい・どの間隔で・1日何回)を冷静なうちに
 * 1回だけ決めておき、取るときはそこから選ぶだけにしてあります。
 * 選ぶ瞬間には、長さも間隔も回数もすでに決まっている。
 *
 * 時刻は日時ピッカーを出さず、30分刻みの前後ボタンで動かす ── 予約は
 * 「だいたいこの時間」で足り、細かく指定させると取るのが億劫になる。
 */
@Composable
fun ReservationScreen() {
    val context = LocalContext.current
    val reservations by DopaRuntime.reservations.reservations.collectAsState()
    val policies by DopaRuntime.settings.reservationPolicies.collectAsState(initial = emptyList())
    val roster by DopaRuntime.devices.collectAsState(initial = emptyList())
    val me = DopaRuntime.myDeviceId
    val scope = rememberCoroutineScope()

    var editing by remember { mutableStateOf<ReservationPolicy?>(null) }
    var booking by remember { mutableStateOf<ReservationPolicy?>(null) }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(
                "予約は、本来ダメな時間に穴を開けるものです。塞ぐルールがあって初めて意味があります。" +
                    "枠の型を先に決めておき、取るときはそこから選びます。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            Text("枠の型", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }

        if (policies.isEmpty()) {
            item {
                Text(
                    "まだありません。まず「何を・どれくらい・どの間隔で」予約してよいかを決めます。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        items(policies, key = { it.id }) { policy ->
            PolicyCard(
                policy = policy,
                onBook = { booking = policy },
                onEdit = { editing = policy },
            )
        }

        item {
            OutlinedButton(onClick = { editing = newPolicy() }) { Text("枠の型を作る") }
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
            items(reservations, key = { it.uid }) { reservation ->
                ReservationRow(
                    reservation = reservation,
                    label = { pkg -> InstalledApps.labelOf(context, pkg) },
                    onCancel = { DopaRuntime.reservations.cancel(reservation.uid) },
                )
            }
        }
    }

    editing?.let { draft ->
        PolicyEditorDialog(
            policy = draft,
            onSave = { saved ->
                scope.launch {
                    val rest = policies.filterNot { it.id == saved.id }
                    DopaRuntime.settings.setReservationPolicies(rest + saved)
                }
                editing = null
            },
            onDelete = {
                scope.launch {
                    DopaRuntime.settings.setReservationPolicies(policies.filterNot { it.id == draft.id })
                }
                editing = null
            },
            onDismiss = { editing = null },
        )
    }

    booking?.let { policy ->
        BookDialog(
            policy = policy,
            roster = roster,
            myDeviceId = me,
            onDismiss = { booking = null },
            onBooked = { booking = null },
        )
    }
}

private fun newPolicy(): ReservationPolicy = ReservationPolicy(
    id = UUID.randomUUID().toString(),
    label = "新しい枠",
    target = Target(),
)

/** 型1つぶん。塞ぐルールが無ければ、そこを真っ先に言う。 */
@Composable
private fun PolicyCard(
    policy: ReservationPolicy,
    onBook: () -> Unit,
    onEdit: () -> Unit,
) {
    val context = LocalContext.current
    val rules by DopaRuntime.rules.rules.collectAsState(initial = emptyList())
    val guarded = remember(rules, policy) { ReservationRules.isGuarded(policy, rules) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(policy.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(
                describeTarget(context, policy.target),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                policy.describe(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )

            if (!guarded) {
                Spacer(Modifier.height(6.dp))
                Text(
                    // 塞いでいない相手を予約しても、いつでも開くので何も起きない
                    "この対象を塞ぐルールがありません。予約しても何も変わりません。" +
                        "先に「時間帯で塞ぐ」か「条件なしで塞ぐ」ルールを作ってください。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onBook, enabled = !policy.target.isEmpty) { Text("この枠で予約する") }
                TextButton(onClick = onEdit) { Text("型を直す") }
            }
        }
    }
}

private fun describeTarget(context: android.content.Context, target: Target): String {
    val apps = target.packages.joinToString("・") { InstalledApps.labelOf(context, it) }
    val tags = target.tags.joinToString("・") { "#" + it }
    return listOf(apps, tags).filter { it.isNotBlank() }.joinToString("・").ifBlank { "(対象なし)" }
}

/** 枠の型を作る・直す。 */
@Composable
private fun PolicyEditorDialog(
    policy: ReservationPolicy,
    onSave: (ReservationPolicy) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var draft by remember(policy.id) { mutableStateOf(policy) }
    var showPicker by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("枠の型") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = draft.label,
                    onValueChange = { draft = draft.copy(label = it.take(20)) },
                    label = { Text("名前") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(12.dp))
                Text("何を通すか", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                Text(
                    describeTarget(context, draft.target),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                OutlinedButton(onClick = { showPicker = true }) { Text("アプリを選ぶ") }

                PolicyStepper(
                    label = "何分前から予約できるか",
                    value = draft.minLeadMinutes,
                    min = 0,
                    max = 24 * 60,
                    step = 30,
                    help = "直前予約を封じる待ち。0 にすると「いま開きたいから今すぐ予約」ができます",
                    onChange = { draft = draft.copy(minLeadMinutes = it) },
                )
                PolicyStepper(
                    label = "1回の最大の長さ",
                    value = draft.maxDurationMinutes,
                    min = ReservationRules.MIN_DURATION_MINUTES,
                    max = ReservationRules.MAX_DURATION_MINUTES,
                    step = 15,
                    onChange = { draft = draft.copy(maxDurationMinutes = it) },
                )
                PolicyStepper(
                    label = "枠と枠のあいだ",
                    value = draft.minGapMinutes,
                    min = 0,
                    max = 24 * 60,
                    step = 30,
                    help = "これが無いと、最大の枠を数珠つなぎに並べて一日中使えます",
                    onChange = { draft = draft.copy(minGapMinutes = it) },
                )
                PolicyStepper(
                    label = "1日に取れる数(0 で無制限)",
                    value = draft.maxPerDay,
                    min = 0,
                    max = 10,
                    step = 1,
                    suffix = "回",
                    onChange = { draft = draft.copy(maxPerDay = it) },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(draft) },
                enabled = draft.label.isNotBlank() && !draft.target.isEmpty,
            ) { Text("保存") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) {
                    Text("消す", color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onDismiss) { Text("やめる") }
            }
        },
    )

    if (showPicker) {
        AppPickerDialog(
            selected = draft.target.packages,
            onToggle = { pkg ->
                val next = if (pkg in draft.target.packages) {
                    draft.target.packages - pkg
                } else {
                    draft.target.packages + pkg
                }
                draft = draft.copy(target = draft.target.copy(packages = next))
            },
            onDismiss = { showPicker = false },
        )
    }
}

@Composable
private fun PolicyStepper(
    label: String,
    value: Int,
    min: Int,
    max: Int,
    step: Int,
    onChange: (Int) -> Unit,
    help: String = "",
    suffix: String = "分",
) {
    Spacer(Modifier.height(10.dp))
    Text(label, style = MaterialTheme.typography.labelLarge)
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            onClick = { onChange((value - step).coerceAtLeast(min)) },
            enabled = value > min,
        ) { Text("−") }
        Text(value.toString() + suffix, style = MaterialTheme.typography.titleSmall)
        TextButton(
            onClick = { onChange((value + step).coerceAtMost(max)) },
            enabled = value < max,
        ) { Text("+") }
    }
    if (help.isNotBlank()) {
        Text(
            help,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 枠を取る。**断られた理由をそのまま出す。**
 *
 * 「取れません」とだけ言われても、時刻を直せばいいのか長さを直せばいいのか
 * 分からない。判定は core が理由つきで返してくる。
 */
@Composable
private fun BookDialog(
    policy: ReservationPolicy,
    roster: List<DeviceInfo>,
    myDeviceId: String,
    onDismiss: () -> Unit,
    onBooked: () -> Unit,
) {
    val now = System.currentTimeMillis() / 1000
    val earliest = roundUpToHalfHour(now + policy.minLeadMinutes * 60L)
    var startSec by remember { mutableLongStateOf(earliest) }
    var minutes by remember { mutableIntStateOf(minOf(30, policy.maxDurationMinutes)) }
    var forDevice by remember { mutableStateOf<String?>(null) }
    var refused by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(policy.label) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(policy.describe(), style = MaterialTheme.typography.bodySmall)

                Spacer(Modifier.height(12.dp))
                Text("いつから", style = MaterialTheme.typography.labelLarge)
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

                Spacer(Modifier.height(12.dp))
                Text("どれくらい", style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = {
                            minutes = (minutes - 5).coerceAtLeast(ReservationRules.MIN_DURATION_MINUTES)
                        },
                        enabled = minutes > ReservationRules.MIN_DURATION_MINUTES,
                    ) { Text("−") }
                    Text(minutes.toString() + "分", style = MaterialTheme.typography.titleMedium)
                    TextButton(
                        onClick = { minutes = (minutes + 5).coerceAtMost(policy.maxDurationMinutes) },
                        enabled = minutes < policy.maxDurationMinutes,
                    ) { Text("+") }
                }

                if (roster.size >= 2) {
                    Spacer(Modifier.height(12.dp))
                    Text("どの端末の枠", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = forDevice == null,
                            onClick = { forDevice = null },
                            label = { Text("すべて") },
                        )
                        roster.forEach { device ->
                            FilterChip(
                                selected = forDevice == device.deviceId,
                                onClick = { forDevice = device.deviceId },
                                label = {
                                    Text(
                                        device.displayName +
                                            if (device.deviceId == myDeviceId) "(この端末)" else "",
                                    )
                                },
                            )
                        }
                    }
                }

                if (refused.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        refused,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val result = DopaRuntime.reservations.bookUnder(
                    policy = policy,
                    startEpochSec = startSec,
                    endEpochSec = startSec + minutes * 60L,
                    devices = setOfNotNull(forDevice),
                )
                when (result) {
                    is BookingCheck.Ok -> onBooked()
                    is BookingCheck.Refused -> refused = result.reason
                }
            }) { Text("予約する") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("やめる") } },
    )
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
                    color = if (active) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
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
