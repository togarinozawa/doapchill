package com.dopachiru.ui.focus

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dopachiru.core.model.Focus
import com.dopachiru.core.model.FocusSchedule
import com.dopachiru.core.model.Lockout
import com.dopachiru.runtime.DopaRuntime
import kotlinx.coroutines.delay

/**
 * 集中タブ。始める・足す・予定を見る・設定へ行く。
 *
 * ## なぜタブにしたか
 *
 * 集中は**いちばん使う操作**なのに、記録の中の札に埋もれていました。
 * 一方でタグはルールを書くときにしか触らないので、タブを1つ使うほどではない
 * ── 入れ替えて、タグはルールタブの中に移しました。
 */
@Composable
fun FocusScreen(onOpenSettings: () -> Unit) {
    var tick by remember { mutableIntStateOf(0) }
    // 残り時間と、時間切れで解けたことを拾うために見直す
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            tick++
        }
    }

    val running = remember(tick) { DopaRuntime.activeFocus() }
    val schedules by DopaRuntime.settings.focusSchedules.collectAsState(initial = emptyList())

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { if (running != null) RunningCard(running) else StartCard() }

        item { ScheduleCard(schedules, onOpenSettings) }

        item {
            OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                Text("集中モードの設定")
            }
        }
    }
}

@Composable
private fun StartCard() {
    var minutes by remember { mutableIntStateOf(Focus.DEFAULT_MINUTES) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("いま始める", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "選んだ時間だけ、逃がすもの以外が閉まります。電話とホームは開いたままです。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // よく使う長さは1タップで。刻みで合わせるのは、そこに無い長さのときだけ
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(15, 25, 45, 60, 90).forEach { preset ->
                    OutlinedButton(onClick = { DopaRuntime.startFocus(preset) }) { Text("${preset}分") }
                }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = { minutes = (minutes - Focus.STEP_MINUTES).coerceAtLeast(Focus.MIN_MINUTES) },
                    enabled = minutes > Focus.MIN_MINUTES,
                ) { Text("−") }
                Text(
                    "$minutes 分",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
                TextButton(
                    onClick = { minutes = (minutes + Focus.STEP_MINUTES).coerceAtMost(Focus.MAX_MINUTES) },
                    enabled = minutes < Focus.MAX_MINUTES,
                ) { Text("+") }
                Spacer(Modifier.weight(1f))
                Button(onClick = { DopaRuntime.startFocus(minutes) }) { Text("始める") }
            }
        }
    }
}

@Composable
private fun RunningCard(focus: Lockout) {
    val nowSec = System.currentTimeMillis() / 1000

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("集中中", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "あと ${focus.remainingMinutesAt(nowSec)} 分",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            // 「最初の一歩」が入っていればここに出る。塞いだだけでは行き先が無い
            if (focus.reason.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(focus.reason, style = MaterialTheme.typography.bodyLarge)
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Focus.EXTEND_CHOICES.forEach { add ->
                    TextButton(onClick = { DopaRuntime.extendFocus(add) }) { Text("+${add}分") }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                // 切り上げはここに置かない。1タップで消せるなら決めたことになっていない
                "切り上げるときは、塞がれた画面から。手間とポイントが要ります。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ScheduleCard(schedules: List<FocusSchedule>, onOpenSettings: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "自分で始めない集中",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            if (schedules.isEmpty()) {
                Text(
                    "決めた時間になったら、こちらから始まります。始めるのに手間がかかる道具は、" +
                        "手間を払えないときにちょうど効きません ── そこを埋めるためのものです。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = onOpenSettings) { Text("朝のぶんを作る") }
            } else {
                schedules.forEach { schedule ->
                    Text(
                        (if (schedule.enabled) "・" else "・(止めてある)") +
                            schedule.label + " ── " + schedule.describe(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
