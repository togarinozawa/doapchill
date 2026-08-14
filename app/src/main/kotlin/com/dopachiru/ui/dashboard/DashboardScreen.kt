package com.dopachiru.ui.dashboard

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dopachiru.core.model.Lockout
import com.dopachiru.core.points.PointEvent
import com.dopachiru.core.points.PointPolicy
import com.dopachiru.core.time.ResetPolicy
import com.dopachiru.data.GrowthStage
import com.dopachiru.data.db.BlockLogEntity
import com.dopachiru.data.db.DayStatEntity
import com.dopachiru.runtime.DopaRuntime
import com.dopachiru.ui.rules.InstalledApps
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class DashboardViewModel(app: Application) : AndroidViewModel(app) {

    val streak: StateFlow<Int> = DopaRuntime.stats.streak
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val days: StateFlow<List<DayStatEntity>> = DopaRuntime.stats.recentDays
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val logs: StateFlow<List<BlockLogEntity>> = DopaRuntime.stats.recentLogs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _todayMinutes = MutableStateFlow(0)
    val todayMinutes: StateFlow<Int> = _todayMinutes.asStateFlow()

    private val _breakdown = MutableStateFlow<List<Pair<String, Int>>>(emptyList())
    val breakdown: StateFlow<List<Pair<String, Int>>> = _breakdown.asStateFlow()

    init {
        viewModelScope.launch {
            while (true) {
                val policy = ResetPolicy()
                _todayMinutes.value = DopaRuntime.usage.totalMinutesIn(policy)
                _breakdown.value = DopaRuntime.usage.breakdownIn(policy).take(8)
                _passUntil.value = DopaRuntime.passUntil()
                delay(15_000)
            }
        }
    }

    val balance: StateFlow<Int> = DopaRuntime.points.balance
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val pointEvents: StateFlow<List<PointEvent>> = DopaRuntime.points.recent
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val pointPolicy: StateFlow<PointPolicy> = DopaRuntime.settings.pointPolicy
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PointPolicy.DEFAULT)

    val lockouts: StateFlow<List<Lockout>> = DopaRuntime.lockouts.active
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 解禁券で制限が止まっている期限(秒)。0 なら効いていない。 */
    private val _passUntil = MutableStateFlow(0L)
    val passUntil: StateFlow<Long> = _passUntil.asStateFlow()

    fun buyPass(onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val bought = DopaRuntime.buyPass()
            _passUntil.value = DopaRuntime.passUntil()
            onResult(bought)
        }
    }

    fun writeNote(logId: Long, note: String) {
        viewModelScope.launch { DopaRuntime.stats.writeNote(logId, note) }
    }
}

@Composable
fun DashboardScreen(viewModel: DashboardViewModel = viewModel()) {
    val streak by viewModel.streak.collectAsState()
    val days by viewModel.days.collectAsState()
    val logs by viewModel.logs.collectAsState()
    val todayMinutes by viewModel.todayMinutes.collectAsState()
    val breakdown by viewModel.breakdown.collectAsState()
    val balance by viewModel.balance.collectAsState()
    val pointEvents by viewModel.pointEvents.collectAsState()
    val pointPolicy by viewModel.pointPolicy.collectAsState()
    val lockouts by viewModel.lockouts.collectAsState()
    val passUntil by viewModel.passUntil.collectAsState()
    val context = LocalContext.current

    var noteTarget by remember { mutableStateOf<BlockLogEntity?>(null) }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { GrowthCard(streak) }

        // 罰は一番上に出す。「なぜ開かないのか」が分からないまま塞がれるのが
        // いちばん堪えるので、閉まっているものと残り時間は常に見えるようにしておく
        if (lockouts.isNotEmpty()) {
            item { LockoutCard(lockouts) }
        }

        if (pointPolicy.enabled) {
            item {
                PointCard(
                    balance = balance,
                    policy = pointPolicy,
                    events = pointEvents,
                    passUntil = passUntil,
                    onBuyPass = { viewModel.buyPass { } },
                )
            }
        }

        item { TodayCard(todayMinutes, breakdown.map { InstalledApps.labelOf(context, it.first) to it.second }) }
        item { CalendarCard(days) }

        item {
            Text(
                "ブロックの記録",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }

        if (logs.isEmpty()) {
            item {
                Text(
                    "まだ記録がありません。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        items(logs, key = { it.id }) { log ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                onClick = { noteTarget = log },
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            InstalledApps.labelOf(context, log.packageName),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            formatTime(log.atEpochSec),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        log.ruleName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (log.overridden) {
                        Text(
                            "押し切って使った",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (log.insteadNote.isBlank()) "代わりに何をした? (タップして書く)" else log.insteadNote,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (log.insteadNote.isBlank()) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
        }
    }

    noteTarget?.let { target ->
        var text by remember(target.id) { mutableStateOf(target.insteadNote) }
        AlertDialog(
            onDismissRequest = { noteTarget = null },
            title = { Text("代わりに何をした?") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.writeNote(target.id, text)
                    noteTarget = null
                }) { Text("記録する") }
            },
            dismissButton = {
                TextButton(onClick = { noteTarget = null }) { Text("やめる") }
            },
        )
    }
}

/**
 * 罰で閉まっているものと、その残り時間。
 *
 * 残り時間を隠さないのは、見えない拘束がいちばん人を追い詰めるため。
 * 解除ボタンは無い ── あったらそれは罰ではない。
 */
@Composable
private fun LockoutCard(lockouts: List<Lockout>) {
    val nowSec = System.currentTimeMillis() / 1000
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "お預け中",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            lockouts.forEach { lockout ->
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        lockout.reason.ifBlank { "ルールを破った罰" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        "あと${lockout.remainingMinutesAt(nowSec)}分",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "時間が過ぎれば自動で開きます。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

/** ポイントの残高と、直近の増減。解禁券もここから買う。 */
@Composable
private fun PointCard(
    balance: Int,
    policy: PointPolicy,
    events: List<PointEvent>,
    passUntil: Long,
    onBuyPass: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text("ポイント", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (policy.recordOnly) "いまは数えているだけ" else "守れば貯まる",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "$balance",
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Light,
                    color = if (balance < 0) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }

            if (policy.passEnabled) {
                Spacer(Modifier.height(14.dp))
                val nowSec = System.currentTimeMillis() / 1000
                if (passUntil > nowSec) {
                    Text(
                        "解禁券が効いています(あと${(passUntil - nowSec + 59) / 60}分)。" +
                            "いまは制限が全部止まっています。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    TextButton(
                        onClick = onBuyPass,
                        enabled = balance >= policy.passCost,
                    ) {
                        Text("解禁券を買う(${policy.passCost}pt で${policy.passMinutes}分)")
                    }
                    if (balance < policy.passCost) {
                        Text(
                            "あと${policy.passCost - balance}pt 足りません。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (events.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                HorizontalDivider()
                events.take(5).forEach { event ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            listOf(event.reason.label, event.note)
                                .filter { it.isNotBlank() }
                                .joinToString(" / "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            if (event.delta > 0) "+${event.delta}" else "${event.delta}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (event.delta > 0) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GrowthCard(streak: Int) {
    val stage = GrowthStage.of(streak)
    val nextIn = GrowthStage.nextIn(streak)

    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stage.art, fontSize = 44.sp)
            Spacer(Modifier.height(8.dp))
            Text(stage.label, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "$streak",
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Light,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    " 日連続",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            if (nextIn != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "あと ${nextIn} 日で次の段階",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TodayCard(minutes: Int, breakdown: List<Pair<String, Int>>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("今日の使用時間", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "${minutes / 60}時間 ${minutes % 60}分",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            if (breakdown.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                val max = breakdown.first().second.coerceAtLeast(1)
                breakdown.forEach { (label, mins) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(0.4f),
                            maxLines = 1,
                        )
                        Box(
                            Modifier
                                .weight(0.45f * mins / max + 0.001f)
                                .height(8.dp)
                                .background(
                                    MaterialTheme.colorScheme.primary,
                                    RoundedCornerShape(4.dp),
                                )
                        )
                        Spacer(Modifier.weight(0.45f * (max - mins) / max + 0.001f))
                        Text(
                            "${mins}分",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.weight(0.15f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarCard(days: List<DayStatEntity>) {
    val byDay = remember(days) { days.associateBy { it.epochDay } }
    val today = LocalDate.now().toEpochDay()

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("直近5週間", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(12.dp))
            // 7列 × 5行。左上が35日前。
            for (row in 0 until 5) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (col in 0 until 7) {
                        val offset = 34 - (row * 7 + col)
                        val day = today - offset
                        val stat = byDay[day]
                        val color = when {
                            day > today -> MaterialTheme.colorScheme.surfaceVariant
                            stat == null -> MaterialTheme.colorScheme.surfaceVariant
                            !stat.kept -> MaterialTheme.colorScheme.error
                            stat.blockShownCount > 0 -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.secondary
                        }
                        Box(
                            Modifier
                                .size(28.dp)
                                .background(color, RoundedCornerShape(6.dp))
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "紫: 誘惑を止めた日 / 黄: ブロックが不要だった日 / 赤: 押し切った日",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatTime(epochSec: Long): String {
    val dt = LocalDateTime.ofInstant(Instant.ofEpochSecond(epochSec), ZoneId.systemDefault())
    return dt.format(DateTimeFormatter.ofPattern("M/d HH:mm"))
}
