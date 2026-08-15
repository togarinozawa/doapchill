package com.dopachiru.ui.rules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dopachiru.core.param.ParamSpec
import com.dopachiru.core.param.Params
import com.dopachiru.core.time.ResetPolicy
import com.dopachiru.core.time.dayLabel
import com.dopachiru.core.time.formatMinuteOfDay
import com.dopachiru.ui.common.HourMinutePicker

/**
 * [ParamSpec] の一覧から入力欄を組み立てる。
 *
 * 新しい条件を足しても、この関数が既存の ParamSpec だけで足りるかぎり
 * UI 側には一切手を入れずに設定画面へ出せる。
 * ParamSpec に新しい種類を増やしたときだけ、下の when に1本足すことになる。
 */
@Composable
fun ParamEditor(
    specs: List<ParamSpec>,
    params: Params,
    onChange: (Params) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (specs.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth()) {
        specs.forEach { spec ->
            ParamField(spec, params) { key, value -> onChange(params.with(key to value)) }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ParamField(
    spec: ParamSpec,
    params: Params,
    onValueChange: (String, Any?) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        when (spec) {
            is ParamSpec.BoolParam -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(spec.label, style = MaterialTheme.typography.bodyLarge)
                        HelpText(spec.help)
                    }
                    Switch(
                        checked = params.bool(spec.key, spec.default),
                        onCheckedChange = { onValueChange(spec.key, it) },
                    )
                }
                return@Column
            }

            else -> {
                Text(
                    spec.label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                )
                HelpText(spec.help)
                Spacer(Modifier.height(8.dp))
            }
        }

        when (spec) {
            is ParamSpec.IntParam -> NumberStepper(
                value = params.int(spec.key, spec.default),
                min = spec.min,
                max = spec.max,
                step = 1,
                suffix = spec.unit,
                onChange = { onValueChange(spec.key, it) },
            )

            is ParamSpec.DurationParam -> {
                val value = params.int(spec.key, spec.default).coerceIn(spec.min, spec.max)
                Text(
                    formatMinutes(value),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(4.dp))
                HourMinutePicker(
                    hour = value / 60,
                    minute = value % 60,
                    maxHour = spec.max / 60,
                    onChange = { h, m ->
                        onValueChange(spec.key, (h * 60 + m).coerceIn(spec.min, spec.max))
                    },
                )
            }

            is ParamSpec.TimeOfDayParam -> {
                val minuteOfDay = params.int(spec.key, spec.default).coerceIn(0, 24 * 60 - 1)
                Text(
                    formatMinuteOfDay(minuteOfDay),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(4.dp))
                HourMinutePicker(
                    hour = minuteOfDay / 60,
                    minute = minuteOfDay % 60,
                    onChange = { h, m -> onValueChange(spec.key, h * 60 + m) },
                )
            }

            is ParamSpec.DayOfWeekParam -> DayOfWeekPicker(
                selected = params.intSet(spec.key, spec.default),
                onChange = { onValueChange(spec.key, it.toList()) },
            )

            is ParamSpec.PackagesParam -> PackagesPicker(
                selected = params.stringSet(spec.key, spec.default),
                onChange = { onValueChange(spec.key, it.toList()) },
            )

            is ParamSpec.TextParam -> OutlinedTextField(
                value = params.string(spec.key, spec.default),
                onValueChange = { onValueChange(spec.key, it) },
                modifier = Modifier.fillMaxWidth(),
                minLines = if (spec.multiline) 3 else 1,
                singleLine = !spec.multiline,
            )

            is ParamSpec.EnumParam -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val current = params.string(spec.key, spec.default)
                spec.options.forEach { option ->
                    FilterChip(
                        selected = current == option.value,
                        onClick = { onValueChange(spec.key, option.value) },
                        label = { Text(option.label) },
                    )
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
private fun HelpText(help: String) {
    if (help.isBlank()) return
    Text(
        help,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 数値の増減。条件のパラメータからも、罰の設定からも使う。 */
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
        IconButton(
            onClick = { onChange((value - step).coerceAtLeast(min)) },
            enabled = value > min,
        ) {
            Icon(Icons.Filled.Remove, contentDescription = "減らす", modifier = Modifier.size(20.dp))
        }
        Text(
            text = "$value$suffix",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(80.dp),
        )
        IconButton(
            onClick = { onChange((value + step).coerceAtMost(max)) },
            enabled = value < max,
        ) {
            Icon(Icons.Filled.Add, contentDescription = "増やす", modifier = Modifier.size(20.dp))
        }
    }
}

/**
 * 条件の中でアプリを選ぶ。ルールの「対象アプリ」とは別物。
 * 「直前に使っていたアプリ」のように、条件がアプリを指すときに使う。
 */
@Composable
private fun PackagesPicker(selected: Set<String>, onChange: (Set<String>) -> Unit) {
    val context = LocalContext.current
    var showPicker by remember { mutableStateOf(false) }

    Column {
        if (selected.isEmpty()) {
            Text(
                "まだ選ばれていません",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            selected.forEach { pkg ->
                AssistChip(
                    onClick = { onChange(selected - pkg) },
                    label = { Text(InstalledApps.labelOf(context, pkg)) },
                    trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "外す") },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { showPicker = true }) { Text("アプリを選ぶ") }
    }

    if (showPicker) {
        AppPickerDialog(
            selected = selected,
            onToggle = { pkg ->
                onChange(if (pkg in selected) selected - pkg else selected + pkg)
            },
            onDismiss = { showPicker = false },
        )
    }
}

/** 曜日の選択。ルール条件からも、変更抑制のゲート設定からも使う。 */
@Composable
fun DayOfWeekPicker(selected: Set<Int>, onChange: (Set<Int>) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        (1..7).forEach { day ->
            FilterChip(
                selected = day in selected,
                onClick = {
                    onChange(if (day in selected) selected - day else selected + day)
                },
                label = { Text(dayLabel(day)) },
            )
        }
    }
}

@Composable
private fun ResetPolicyPicker(policy: ResetPolicy, onChange: (ResetPolicy) -> Unit) {
    val presets = listOf(
        24 * 60 to "1日",
        12 * 60 to "半日",
        8 * 60 to "8時間",
        6 * 60 to "6時間",
        60 to "1時間",
    )

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
        Spacer(Modifier.height(8.dp))
        Text(
            "リセット基準時刻  ${formatMinuteOfDay(policy.anchorMinuteOfDay)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HourMinutePicker(
            hour = policy.anchorMinuteOfDay / 60,
            minute = policy.anchorMinuteOfDay % 60,
            onChange = { h, m -> onChange(policy.copy(anchorMinuteOfDay = h * 60 + m)) },
        )
        Text(
            policy.describe(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

private fun formatMinutes(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return buildString {
        if (h > 0) append("${h}時間")
        if (m > 0 || h == 0) append("${m}分")
    }
}
