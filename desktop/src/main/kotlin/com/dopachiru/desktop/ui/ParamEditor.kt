package com.dopachiru.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dopachiru.core.param.ParamSpec
import com.dopachiru.core.param.Params
import com.dopachiru.core.time.ResetPolicy
import com.dopachiru.core.time.dayLabel
import com.dopachiru.core.time.formatMinuteOfDay
import com.dopachiru.desktop.platform.ForegroundApp
import com.dopachiru.desktop.platform.RunningApps

/**
 * [ParamSpec] の一覧から入力欄を組み立てる。Windows 版。
 *
 * Android 版と作りは同じだが、部品は素の増減ボタンとテキスト欄にしてある。
 * 指で回すホイールは、マウスとキーボードの上では速くも正確でもないため。
 */
@Composable
fun ParamEditor(
    specs: List<ParamSpec>,
    params: Params,
    onChange: (Params) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (specs.isEmpty()) return

    Column(modifier.fillMaxWidth()) {
        specs.forEach { spec ->
            ParamField(spec, params) { key, value -> onChange(params.with(key to value)) }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun ParamField(
    spec: ParamSpec,
    params: Params,
    onValueChange: (String, Any?) -> Unit,
) {
    if (spec is ParamSpec.BoolParam) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(spec.label, style = MaterialTheme.typography.bodyMedium)
                Help(spec.help)
            }
            Switch(
                checked = params.bool(spec.key, spec.default),
                onCheckedChange = { onValueChange(spec.key, it) },
            )
        }
        return
    }

    var showAppPicker by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        Text(spec.label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
        Help(spec.help)
        Spacer(Modifier.height(6.dp))

        when (spec) {
            is ParamSpec.IntParam -> NumberStepper(
                value = params.int(spec.key, spec.default),
                min = spec.min,
                max = spec.max,
                suffix = spec.unit,
                onChange = { onValueChange(spec.key, it) },
            )

            is ParamSpec.DurationParam -> NumberStepper(
                value = params.int(spec.key, spec.default).coerceIn(spec.min, spec.max),
                min = spec.min,
                max = spec.max,
                step = 5,
                suffix = "分",
                onChange = { onValueChange(spec.key, it) },
            )

            is ParamSpec.TimeOfDayParam -> {
                val minuteOfDay = params.int(spec.key, spec.default).coerceIn(0, 24 * 60 - 1)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        formatMinuteOfDay(minuteOfDay),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(72.dp),
                    )
                    NumberStepper(
                        value = minuteOfDay / 60,
                        min = 0,
                        max = 23,
                        suffix = "時",
                        onChange = { onValueChange(spec.key, it * 60 + minuteOfDay % 60) },
                    )
                    NumberStepper(
                        value = minuteOfDay % 60,
                        min = 0,
                        max = 55,
                        step = 5,
                        suffix = "分",
                        onChange = { onValueChange(spec.key, (minuteOfDay / 60) * 60 + it) },
                    )
                }
            }

            is ParamSpec.PackagesParam -> {
                val selected = params.stringSet(spec.key, spec.default)
                Column {
                    if (selected.isEmpty()) {
                        Text(
                            "まだ選ばれていません",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            selected.joinToString("、") { ForegroundApp.labelFor(it) },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(onClick = { showAppPicker = true }) { Text("アプリを選ぶ") }
                }
                if (showAppPicker) {
                    ConditionAppPickerDialog(
                        selected = selected,
                        onToggle = { process ->
                            val updated =
                                if (process in selected) selected - process else selected + process
                            onValueChange(spec.key, updated.toList())
                        },
                        onDismiss = { showAppPicker = false },
                    )
                }
            }

            is ParamSpec.DayOfWeekParam -> {
                val selected = params.intSet(spec.key, spec.default)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    (1..7).forEach { day ->
                        FilterChip(
                            selected = day in selected,
                            onClick = {
                                val updated =
                                    if (day in selected) selected - day else selected + day
                                onValueChange(spec.key, updated.toList())
                            },
                            label = { Text(dayLabel(day)) },
                        )
                    }
                }
            }

            is ParamSpec.TextParam -> OutlinedTextField(
                value = params.string(spec.key, spec.default),
                onValueChange = { onValueChange(spec.key, it) },
                modifier = Modifier.fillMaxWidth(),
                minLines = if (spec.multiline) 3 else 1,
                singleLine = !spec.multiline,
            )

            is ParamSpec.EnumParam -> {
                val current = params.string(spec.key, spec.default)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    spec.options.forEach { option ->
                        FilterChip(
                            selected = current == option.value,
                            onClick = { onValueChange(spec.key, option.value) },
                            label = { Text(option.label) },
                        )
                    }
                }
            }

            is ParamSpec.ResetPolicyParam -> ResetPolicyPicker(
                policy = params.resetPolicy(spec.key, spec.default),
                onChange = { onValueChange(spec.key, it) },
            )

            is ParamSpec.BoolParam -> Unit // 上で描画済み
        }
    }
}

@Composable
private fun ResetPolicyPicker(policy: ResetPolicy, onChange: (ResetPolicy) -> Unit) {
    val presets = listOf(24 * 60 to "1日", 12 * 60 to "半日", 8 * 60 to "8時間", 60 to "1時間")

    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            presets.forEach { (minutes, label) ->
                FilterChip(
                    selected = policy.periodMinutes == minutes,
                    onClick = { onChange(policy.copy(periodMinutes = minutes)) },
                    label = { Text(label) },
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "起点 ${formatMinuteOfDay(policy.anchorMinuteOfDay)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(96.dp),
            )
            NumberStepper(
                value = policy.anchorMinuteOfDay / 60,
                min = 0,
                max = 23,
                suffix = "時",
                onChange = { onChange(policy.copy(anchorMinuteOfDay = it * 60)) },
            )
        }
        Text(
            policy.describe(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 条件の中でアプリを選ぶ。ルールの「対象アプリ」とは別物。
 *
 * Windows には「インストール済みアプリ」の統一された一覧が無いので、
 * いま窓を持って動いているものから選ばせ、閉じているものは名前で足させる。
 */
@Composable
private fun ConditionAppPickerDialog(
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val running = remember { RunningApps.visible() }
    var manual by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("アプリを選ぶ") },
        text = {
            Column {
                LazyColumn(Modifier.heightIn(max = 300.dp)) {
                    items(running, key = { it.processName }) { app ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = app.processName in selected,
                                onCheckedChange = { onToggle(app.processName) },
                            )
                            Column(Modifier.weight(1f)) {
                                Text(app.label, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    app.processName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    items(selected.filter { s -> running.none { it.processName == s } }) { process ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = true, onCheckedChange = { onToggle(process) })
                            Text(process, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = manual,
                        onValueChange = { manual = it },
                        label = { Text("実行ファイル名 (例: chrome.exe)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        enabled = manual.isNotBlank(),
                        onClick = { onToggle(manual.trim().lowercase()); manual = "" },
                    ) { Text("足す") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
    )
}

@Composable
private fun Help(help: String) {
    if (help.isBlank()) return
    Text(
        help,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 数値の増減。条件のパラメータからも、罰とポイントの設定からも使う。 */
@Composable
fun NumberStepper(
    value: Int,
    min: Int,
    max: Int,
    step: Int = 1,
    suffix: String = "",
    onChange: (Int) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(
            onClick = { onChange((value - step).coerceAtLeast(min)) },
            enabled = value > min,
        ) { Text("−") }
        Text(
            "$value$suffix",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(80.dp).padding(horizontal = 8.dp),
        )
        OutlinedButton(
            onClick = { onChange((value + step).coerceAtMost(max)) },
            enabled = value < max,
        ) { Text("+") }
    }
}
