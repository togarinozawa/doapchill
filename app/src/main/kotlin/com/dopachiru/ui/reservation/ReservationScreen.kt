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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import com.dopachiru.core.model.Rule
import com.dopachiru.core.model.Target
import com.dopachiru.core.sync.AppInfo
import com.dopachiru.core.sync.DeviceInfo
import com.dopachiru.core.sync.RuleCatalog
import com.dopachiru.data.AppLabels
import com.dopachiru.runtime.DopaRuntime
import com.dopachiru.ui.rules.InstalledApps
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 予約を取る・見る・取り消す画面。
 *
 * ## 並ぶのは「予約すれば使えるようになるルール」だけ
 *
 * 予約が効くのは**本来ダメな時間の中**でだけです。しかも、塞いであれば何でも
 * 開くわけではない ── 開くのは条件に「予約した時間の外」を持つルールだけです。
 * それ以外のルールは、枠を取っても素通りしません。
 *
 * だから並べる相手は「塞いでいるルール」ではなく
 * **[ReservationRules.unlockableOn] が返すルール**です。手で対象を選ばせるのを
 * やめたのはこのため ── 選べてしまうと、取っても何も起きない枠を作れて、
 * 「予約したのに開かない」という一番効く失望を自分で仕込むことになります。
 *
 * ## 端末ごとに並べる
 *
 * ルールは端末ごとに直接作るので、**この端末の見出しには手元のルール、
 * ほかの端末の見出しにはその端末の名札([RuleCatalog])**を並べます。
 * 「PC で Steam を開ける枠」をスマホから取れるのが予約の使いどころで、
 * 取った枠は同期で PC に届き、PC のルールの穴になります。
 *
 * ほかの端末の枠の数字(何分前から・長さ・間隔・回数)は**持ち主の端末で決めた
 * ものをそのまま使い、ここでは直せません。** 取る側で直せると、スマホから取る
 * ときだけ緩くなります。
 *
 * ## 数字は型([ReservationPolicy])で持つ
 *
 * 何分前から・1回どれくらい・間隔・1日何回。触っていなければ既定値で、
 * 触ったぶんだけルールに紐づいて残ります。
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
    val rules by DopaRuntime.rules.rules.collectAsState(initial = emptyList())
    val catalogs by DopaRuntime.settings.ruleCatalogs.collectAsState(initial = emptyList())
    val me = DopaRuntime.myDeviceId
    val scope = rememberCoroutineScope()

    var editing by remember { mutableStateOf<ReservationPolicy?>(null) }
    var booking by remember { mutableStateOf<Pair<ReservationPolicy, String>?>(null) }

    // この端末が名簿に無いこともある(同期する前)。自分だけは必ず出す
    val devices = remember(roster, me) { deviceSlots(roster, me) }
    val groups = remember(rules, policies, catalogs, devices) {
        devices.map { slot -> slot to bookablesOn(slot, me, rules, policies, catalogs) }
    }
    val nothingToBook = groups.all { it.second.isEmpty() }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                "予約は、本来ダメな時間に穴を開けるものです。" +
                    "ここに並ぶのは「予約すれば使えるようになるルール」だけ ── " +
                    "条件に「予約した時間の外」が入っているものです。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (nothingToBook) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "予約で開くルールがまだありません。",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            // 「塞ぐルール」ではなく「予約の条件を持つルール」。
                            // ここを曖昧にすると、塞ぐだけのルールを作って
                            // 「予約が効かない」と思うことになる
                            "ルールを1つ作って、条件に「予約した時間の外」を入れてください。" +
                                "そのルールがここに出てきます。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        groups.forEach { (slot, unlockable) ->
            if (unlockable.isNotEmpty()) {
                if (devices.size >= 2) {
                    item(key = "head-" + slot.deviceId) {
                        Text(
                            slot.label,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }

                items(unlockable, key = { slot.deviceId + "/" + it.key }) { bookable ->
                    RuleSlotCard(
                        bookable = bookable,
                        label = { pkg ->
                            if (bookable.mine) {
                                InstalledApps.labelOf(context, pkg)
                            } else {
                                AppLabels.labelOf(context, slot.platform + ":" + pkg)
                            }
                        },
                        onBook = { booking = bookable.policy to slot.deviceId },
                        onTune = if (bookable.mine) ({ editing = bookable.policy }) else null,
                    )
                }
            }
        }

        item {
            Spacer(Modifier.height(4.dp))
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
                // PC の枠は PC のアプリ名で。こちらに入っていないので名札から引く
                val owner = devices.firstOrNull { it.deviceId == reservation.devices.singleOrNull() }
                ReservationRow(
                    reservation = reservation,
                    label = { pkg ->
                        if (owner == null || owner.deviceId == me) {
                            InstalledApps.labelOf(context, pkg)
                        } else {
                            AppLabels.labelOf(context, owner.platform + ":" + pkg)
                        }
                    },
                    deviceLabel = { id -> devices.firstOrNull { it.deviceId == id }?.label ?: id },
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
            onReset = {
                scope.launch {
                    DopaRuntime.settings.setReservationPolicies(policies.filterNot { it.id == draft.id })
                }
                editing = null
            },
            onDismiss = { editing = null },
        )
    }

    booking?.let { (policy, deviceId) ->
        BookDialog(
            policy = policy,
            deviceId = deviceId,
            deviceLabel = devices.firstOrNull { it.deviceId == deviceId }?.label.orEmpty(),
            showDevice = devices.size >= 2,
            onDismiss = { booking = null },
            onBooked = { booking = null },
        )
    }
}

/** 一覧の見出しに使う端末1つぶん。 */
private data class DeviceSlot(val deviceId: String, val label: String, val platform: String)

/**
 * 並べる端末。**この端末を必ず先頭に**置く。
 *
 * 同期する前は名簿が空なので、自分すら出てこないと「予約できないアプリ」に見える。
 * 同期を切っていても予約は使えるべきなので、名簿が無くても自分だけは出す。
 */
private fun deviceSlots(roster: List<DeviceInfo>, myDeviceId: String): List<DeviceSlot> {
    val mine = DeviceSlot(
        myDeviceId,
        (roster.firstOrNull { it.deviceId == myDeviceId }?.displayName ?: "この端末") +
            if (myDeviceId.isNotBlank()) "(この端末)" else "",
        AppInfo.ANDROID,
    )
    val others = roster
        .filter { it.deviceId != myDeviceId && it.deviceId.isNotBlank() }
        .map { DeviceSlot(it.deviceId, it.displayName, it.platform) }
    return listOf(mine) + others
}

/** 予約を取れる枠1つぶん。手元のルールでも、ほかの端末の名札でも同じ形にそろえる。 */
private data class Bookable(
    val key: String,
    val name: String,
    val target: Target,
    val policy: ReservationPolicy,
    /** この端末のルールか。数字を直せるのは持ち主の端末だけ。 */
    val mine: Boolean,
)

/** その端末で予約すれば開くもの。この端末は手元のルール、ほかは向こうの名札から。 */
private fun bookablesOn(
    slot: DeviceSlot,
    me: String,
    rules: List<Rule>,
    policies: List<ReservationPolicy>,
    catalogs: List<RuleCatalog>,
): List<Bookable> =
    if (slot.deviceId == me) {
        ReservationRules.unlockableOn(rules, me).map { rule ->
            Bookable(
                key = rule.uid.ifBlank { rule.id.toString() },
                name = rule.name,
                target = rule.target,
                policy = ReservationRules.policyFor(rule, policies),
                mine = true,
            )
        }
    } else {
        catalogs.firstOrNull { it.deviceId == slot.deviceId }?.rules.orEmpty()
            .filter { it.enabled }
            .mapNotNull { card ->
                val policy = card.reservation ?: return@mapNotNull null
                Bookable(key = card.uid, name = card.name, target = card.target, policy = policy, mine = false)
            }
    }

/**
 * 予約で開くルール1つぶん。
 *
 * 型を持っていなくても並びます ── 数字を触っていないだけで、既定値で取れます。
 *
 * @param onTune 数字を直す。ほかの端末の枠では null(持ち主の端末で直す)。
 */
@Composable
private fun RuleSlotCard(
    bookable: Bookable,
    label: (String) -> String,
    onBook: () -> Unit,
    onTune: (() -> Unit)?,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(bookable.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(
                describeTarget(bookable.target, label),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                bookable.policy.describe(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onBook, enabled = !bookable.target.isEmpty) { Text("この枠で予約する") }
                if (onTune != null) TextButton(onClick = onTune) { Text("条件を直す") }
            }
        }
    }
}

private fun describeTarget(target: Target, label: (String) -> String): String {
    val apps = target.packages.joinToString("・") { label(it) }
    val tags = target.tags.joinToString("・") { "#" + it }
    return listOf(apps, tags).filter { it.isNotBlank() }.joinToString("・").ifBlank { "(対象なし)" }
}

/**
 * 予約の条件を直す。
 *
 * **何を通すかはここで選べません。** 通る相手はルールの対象そのもので、
 * ここで別に選べると「塞いでいない相手の枠」を作れてしまいます。
 * 取っても何も起きない枠は、作れないほうがいい。
 */
@Composable
private fun PolicyEditorDialog(
    policy: ReservationPolicy,
    onSave: (ReservationPolicy) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var draft by remember(policy.id) { mutableStateOf(policy) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(policy.label) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "通るのは " + describeTarget(draft.target) { InstalledApps.labelOf(context, it) } +
                        "。ルールの対象と同じです。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

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
            TextButton(onClick = { onSave(draft) }) { Text("保存") }
        },
        dismissButton = {
            Row {
                // 消すのではなく戻す。ルールが残っている以上、枠そのものは残る
                TextButton(onClick = onReset) { Text("既定に戻す") }
                TextButton(onClick = onDismiss) { Text("やめる") }
            }
        },
    )
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
    deviceId: String,
    deviceLabel: String,
    showDevice: Boolean,
    onDismiss: () -> Unit,
    onBooked: () -> Unit,
) {
    val now = System.currentTimeMillis() / 1000
    val earliest = roundUpToHalfHour(now + policy.minLeadMinutes * 60L)
    var startSec by remember { mutableLongStateOf(earliest) }
    var minutes by remember { mutableIntStateOf(minOf(30, policy.maxDurationMinutes)) }
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

                if (showDevice) {
                    Spacer(Modifier.height(12.dp))
                    // 端末は一覧で選んである。ここで選び直せると、
                    // 見出しと違う端末の枠を取れてしまう
                    Text(
                        deviceLabel + "の枠として取ります。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
                    devices = setOfNotNull(deviceId.takeIf { it.isNotBlank() }),
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
    deviceLabel: (String) -> String,
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
                        // どの端末の枠かを出さないと、PC の枠をスマホで見て
                        // 「効いていない」と思うことになる
                        reservation.devices.joinToString("") { "  ・" + deviceLabel(it) } +
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
