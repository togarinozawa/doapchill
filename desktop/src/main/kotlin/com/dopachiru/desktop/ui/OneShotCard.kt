package com.dopachiru.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.dopachiru.core.gate.ChangeKind
import com.dopachiru.core.model.OneShotLimit
import com.dopachiru.core.model.Rule
import com.dopachiru.core.model.Target
import com.dopachiru.desktop.DesktopRuntime
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * その場で決める「◯時間使ったら◯分休憩」。Android 側と同じもの。
 *
 * 「今日」のタブに置いてあるのは、決めたくなるのが**使う直前**だからです。
 * ルールのタブで雛形を選んで条件を組んで……とやるうちに、決める気は消えます。
 *
 * 明日の朝4時に消えます。残り続けると分かっているものは、作る前に
 * 「これを一生守れるか」を考えることになり、その場では作られません。
 */
@Composable
internal fun OneShotCard() {
    val file by DesktopRuntime.ruleFile.collectAsState()
    val temporary = remember(file) { file.rules.filter { it.isTemporary } }

    var expanded by remember { mutableStateOf(false) }
    var processes by remember { mutableStateOf(emptySet<String>()) }
    var useMinutes by remember { mutableIntStateOf(OneShotLimit.DEFAULT_USE_MINUTES) }
    var restMinutes by remember { mutableIntStateOf(OneShotLimit.DEFAULT_REST_MINUTES) }
    var showPicker by remember { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("今日だけの枠", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "「2時間使ったら1時間休憩」をその場で決めます。この端末だけ・明日の朝4時までです。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            temporary.forEach { rule ->
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                TemporaryRuleRow(rule) {
                    // 消すのは縛りを緩める側。関門があるなら通す
                    DesktopRuntime.requestChange(ChangeKind.DELETE, rule)
                }
            }

            Spacer(Modifier.height(12.dp))

            if (!expanded) {
                OutlinedButton(onClick = { expanded = true }) { Text("枠を決める") }
                return@Column
            }

            Text("何を", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(2.dp))
            Text(
                processes.joinToString("・").ifBlank { "まだ選んでいません" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = { showPicker = true }) { Text("アプリを選ぶ") }

            MinuteStepper(
                label = "使っていい時間",
                value = useMinutes,
                min = OneShotLimit.MIN_USE_MINUTES,
                max = OneShotLimit.MAX_USE_MINUTES,
                onChange = { useMinutes = it },
            )
            MinuteStepper(
                label = "使い切ったあと休む時間",
                value = restMinutes,
                min = OneShotLimit.MIN_REST_MINUTES,
                max = OneShotLimit.MAX_REST_MINUTES,
                onChange = { restMinutes = it },
                help = "最初に触った時刻から数えます。途中で閉じても数え直しにはなりません",
            )

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = processes.isNotEmpty(),
                    onClick = {
                        DesktopRuntime.addRule(
                            OneShotLimit.build(
                                target = Target(packages = processes),
                                useMinutes = useMinutes,
                                restMinutes = restMinutes,
                                expiresAtSec = OneShotLimit.endOfDay(LocalDateTime.now()),
                                // この端末だけ。同じ名前のアプリがスマホにもあると巻き添えになる
                                devices = setOfNotNull(
                                    DesktopRuntime.myDeviceId().takeIf { it.isNotBlank() },
                                ),
                            ),
                        )
                        processes = emptySet()
                        expanded = false
                    },
                ) { Text(OneShotLimit.label(useMinutes, restMinutes) + "にする") }
                TextButton(onClick = { expanded = false }) { Text("やめる") }
            }
        }
    }

    if (showPicker) {
        AppPickerDialog(
            selected = processes,
            onToggle = { name -> processes = if (name in processes) processes - name else processes + name },
            onDismiss = { showPicker = false },
        )
    }
}

@Composable
private fun TemporaryRuleRow(rule: Rule, onCancel: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                rule.target.packages.joinToString("・").ifBlank { rule.name },
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                rule.name + "  ・" + expiryLabel(rule.expiresAtSec) + "に消えます",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        TextButton(onClick = onCancel) { Text("やめる") }
    }
}

@Composable
private fun MinuteStepper(
    label: String,
    value: Int,
    min: Int,
    max: Int,
    onChange: (Int) -> Unit,
    help: String = "",
    step: Int = 15,
) {
    Spacer(Modifier.height(10.dp))
    Text(label, style = MaterialTheme.typography.labelLarge)
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            onClick = { onChange((value - step).coerceAtLeast(min)) },
            enabled = value > min,
        ) { Text("−") }
        Text(spanLabel(value), style = MaterialTheme.typography.titleSmall)
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

private fun spanLabel(minutes: Int): String = when {
    minutes >= 60 && minutes % 60 == 0 -> (minutes / 60).toString() + "時間"
    minutes >= 60 -> (minutes / 60).toString() + "時間" + (minutes % 60) + "分"
    else -> minutes.toString() + "分"
}

private val expiryFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("M/d HH:mm")

private fun expiryLabel(epochSec: Long): String =
    Instant.ofEpochSecond(epochSec).atZone(ZoneId.systemDefault()).format(expiryFormat)
