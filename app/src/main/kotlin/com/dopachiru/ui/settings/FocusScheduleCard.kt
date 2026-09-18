package com.dopachiru.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dopachiru.core.model.Focus
import com.dopachiru.core.model.FocusAnchor
import com.dopachiru.core.model.FocusSchedule
import com.dopachiru.core.model.FocusSchedules
import com.dopachiru.core.time.ALL_DAYS
import com.dopachiru.core.time.formatMinuteOfDay
import com.dopachiru.runtime.DopaRuntime
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 自分で始めなくても始まる集中の設定。
 *
 * ## なぜ別の札になっているのか
 *
 * 上の集中モードは「思い立った瞬間に始める」ものです。こちらは逆で、
 * **思い立てないときのため**にあります ── 朝起きてしばらく、何を見るでもなく
 * 眺めてしまって動き出せない、というときに、こちらから始まります。
 *
 * 起動の手間がかかる道具は、起動の手間を払えないときにちょうど効きません。
 *
 * ## 「最初の一歩」を書かせる理由
 *
 * 塞ぐのは麻酔を取り上げることで、始動をくれるわけではありません。だから
 * 始まった画面に、**前もって書いておいた小さな行動を1つだけ**出します。
 * 決めるのはやる気のある側の自分で、朝の自分は選ばずに済みます
 * ── 選択肢を出すと、選べないので固まります。
 */
@Composable
fun FocusScheduleCard() {
    val scope = rememberCoroutineScope()
    val schedules by DopaRuntime.settings.focusSchedules.collectAsState(initial = emptyList())

    fun save(next: List<FocusSchedule>) {
        scope.launch { DopaRuntime.settings.setFocusSchedules(next) }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "自分で始めない集中",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "決めた時間になったら、こちらから始まります。始めるのに手間がかかる道具は、" +
                    "手間を払えないときにちょうど効きません ── そこを埋めるためのものです。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            schedules.forEachIndexed { index, schedule ->
                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(16.dp))
                ScheduleEditor(
                    schedule = schedule,
                    onChange = { next ->
                        save(schedules.toMutableList().also { it[index] = next })
                    },
                    onRemove = { save(schedules.filterIndexed { i, _ -> i != index }) },
                )
            }

            if (schedules.size < FocusSchedules.MAX) {
                Spacer(Modifier.height(16.dp))
                OutlinedButton(
                    onClick = {
                        val fresh = FocusSchedules.morningDefault()
                            .copy(uid = UUID.randomUUID().toString())
                        save(schedules + fresh)
                    },
                ) { Text(if (schedules.isEmpty()) "朝のぶんを作る" else "もう1つ足す") }
            }

            if (schedules.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "既定では何も入っていません。作ると、翌朝から勝手に始まります。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ScheduleEditor(
    schedule: FocusSchedule,
    onChange: (FocusSchedule) -> Unit,
    onRemove: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = schedule.label,
            onValueChange = { onChange(schedule.copy(label = it.take(12))) },
            label = { Text("名前") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.height(0.dp))
        Switch(
            checked = schedule.enabled,
            onCheckedChange = { onChange(schedule.copy(enabled = it)) },
        )
    }

    Spacer(Modifier.height(12.dp))
    Text("いつ始めるか", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = schedule.anchor == FocusAnchor.AFTER_WAKE,
            onClick = {
                // 起点を変えると offset の意味が変わる。ちぐはぐな値が残らないよう入れ直す
                onChange(schedule.copy(anchor = FocusAnchor.AFTER_WAKE, offsetMinutes = 20))
            },
            label = { Text("起きてから") },
        )
        FilterChip(
            selected = schedule.anchor == FocusAnchor.AT_CLOCK,
            onClick = {
                onChange(schedule.copy(anchor = FocusAnchor.AT_CLOCK, offsetMinutes = 8 * 60))
            },
            label = { Text("時計で") },
        )
    }

    Spacer(Modifier.height(8.dp))
    when (schedule.anchor) {
        FocusAnchor.AFTER_WAKE -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("はじめて触ってから ", style = MaterialTheme.typography.bodySmall)
                Stepper(
                    value = schedule.offsetMinutes,
                    min = 0,
                    max = 180,
                    step = 5,
                    suffix = "分後",
                    onChange = { onChange(schedule.copy(offsetMinutes = it)) },
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "起床が7時でも10時でも同じように効きます。朝の無気力にはこちらが素直です。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        FocusAnchor.AT_CLOCK -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("毎日 ", style = MaterialTheme.typography.bodySmall)
                Stepper(
                    value = schedule.offsetMinutes,
                    min = 0,
                    max = 23 * 60 + 30,
                    step = 30,
                    format = ::formatMinuteOfDay,
                    onChange = { onChange(schedule.copy(offsetMinutes = it)) },
                )
                Text(" から", style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "「9時には机にいたいから8時半に塞ぐ」のような、予定の側から決まるぶんに。" +
                    "寝坊した日は空振りします。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("長さ ", style = MaterialTheme.typography.bodySmall)
        Stepper(
            value = schedule.minutes,
            min = Focus.MIN_MINUTES,
            max = 120,
            step = Focus.STEP_MINUTES,
            suffix = "分",
            onChange = { onChange(schedule.copy(minutes = it)) },
        )
    }

    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("この時刻を過ぎたらやめる ", style = MaterialTheme.typography.bodySmall)
        Stepper(
            value = schedule.byMinuteOfDay,
            min = 0,
            max = 23 * 60 + 30,
            step = 30,
            format = ::formatMinuteOfDay,
            onChange = { onChange(schedule.copy(byMinuteOfDay = it)) },
        )
    }
    Spacer(Modifier.height(4.dp))
    Text(
        // これが無いと、午後2時にはじめて端末を触った日に「朝の集中」が始まる
        "これが無いと、昼過ぎにはじめて端末を触った日にも「朝の集中」が始まります。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(12.dp))
    Text("曜日", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(6.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ALL_DAYS.sorted().forEach { day ->
            FilterChip(
                selected = day in schedule.days,
                onClick = {
                    onChange(
                        schedule.copy(
                            days = if (day in schedule.days) {
                                schedule.days - day
                            } else {
                                schedule.days + day
                            },
                        ),
                    )
                },
                label = { Text(DAY_LABELS[day] ?: day.toString()) },
            )
        }
    }

    Spacer(Modifier.height(12.dp))
    Text("最初の一歩", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(4.dp))
    OutlinedTextField(
        value = schedule.steps,
        onValueChange = { onChange(schedule.copy(steps = it)) },
        modifier = Modifier.fillMaxWidth(),
        minLines = 3,
        placeholder = { Text("顔を洗う\n机に3分だけ座る") },
    )
    Spacer(Modifier.height(4.dp))
    Text(
        "始まった画面に1つだけ出ます。改行で分けると日替わりで回ります。" +
            "空なら壁だけになります ── ただし、塞ぐのは麻酔を取り上げることであって、" +
            "始動をくれるわけではありません。1つは書いておくほうが効きます。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(8.dp))
    Text(
        schedule.describe(),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
    )

    TextButton(onClick = onRemove) {
        Text("この予定を消す", color = MaterialTheme.colorScheme.error)
    }
}

private val DAY_LABELS = mapOf(1 to "月", 2 to "火", 3 to "水", 4 to "木", 5 to "金", 6 to "土", 7 to "日")

/** 数を増減させる小さな道具。値の見せ方だけ差し替えられる。 */
@Composable
private fun Stepper(
    value: Int,
    min: Int,
    max: Int,
    step: Int,
    onChange: (Int) -> Unit,
    suffix: String = "",
    format: ((Int) -> String)? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            onClick = { onChange((value - step).coerceAtLeast(min)) },
            enabled = value > min,
        ) { Text("−") }
        Text(
            format?.invoke(value) ?: (value.toString() + suffix),
            style = MaterialTheme.typography.titleSmall,
        )
        TextButton(
            onClick = { onChange((value + step).coerceAtMost(max)) },
            enabled = value < max,
        ) { Text("+") }
    }
}
