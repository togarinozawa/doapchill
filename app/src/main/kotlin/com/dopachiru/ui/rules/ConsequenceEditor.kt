package com.dopachiru.ui.rules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dopachiru.core.model.Consequence
import com.dopachiru.core.model.LockScope
import com.dopachiru.core.points.PointPolicy

/**
 * ルールを破った / 守ったときに起きることの設定。
 *
 * 「その場で何を出すか」(アクション)とは別の軸として並べている。
 * 罰は押し切ったあとに効くもので、ブロック画面そのものの強さとは関係が無い ──
 * 分けておかないと「封印を強くする」と「破ったら重くする」が混ざる。
 *
 * [policy] は既定値の表示にしか使わない。ここで「設定どおり」を選んだルールは
 * 値を持たず、設定を変えれば全ルールにまとめて効く。
 */
@Composable
fun ConsequenceEditor(
    consequence: Consequence,
    policy: PointPolicy,
    onChange: (Consequence) -> Unit,
) {
    val context = LocalContext.current
    var showAllowPicker by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        Text("破ったら何が閉まるか", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LockScope.entries.forEach { scope ->
                FilterChip(
                    selected = consequence.lockScope == scope,
                    onClick = {
                        onChange(
                            consequence.copy(
                                lockScope = scope,
                                // 封鎖を選んだのに長さが 0 のままでは何も起きない。
                                // 既定を入れておいて、そこから調整させる
                                lockMinutes = when {
                                    scope == LockScope.NONE -> 0
                                    consequence.lockMinutes > 0 -> consequence.lockMinutes
                                    else -> 30
                                },
                            )
                        )
                    },
                    label = { Text(scope.label) },
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            consequence.lockScope.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (consequence.lockScope != LockScope.NONE) {
            Spacer(Modifier.height(14.dp))
            Text("どれくらい閉めるか", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            NumberStepper(
                value = consequence.lockMinutes,
                min = 0,
                max = Consequence.MAX_LOCK_MINUTES,
                step = 5,
                suffix = "分",
                onChange = { onChange(consequence.copy(lockMinutes = it)) },
            )
            Text(
                "上限は${Consequence.MAX_LOCK_MINUTES / 60}時間。桁を間違えて一日詰むことがないようにしてあります。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (consequence.lockScope == LockScope.EVERYTHING) {
            Spacer(Modifier.height(14.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "閉めないでおくアプリ",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        "電話・ホーム・設定・キーボード・ドパチル自身は、選ばなくても必ず使えます。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        consequence.lockAllowPackages.forEach { pkg ->
                            AssistChip(
                                onClick = {
                                    onChange(
                                        consequence.copy(
                                            lockAllowPackages = consequence.lockAllowPackages - pkg
                                        )
                                    )
                                },
                                label = { Text(InstalledApps.labelOf(context, pkg)) },
                                trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "外す") },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { showAllowPicker = true }) { Text("アプリを選ぶ") }
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        PointRow(
            title = "破ったときのポイント",
            help = "押し切る・宣言を超える・警告を無視する のいずれか",
            value = consequence.breakPoints,
            fallback = policy.defaultBreakPoints,
            onChange = { onChange(consequence.copy(breakPoints = it)) },
        )

        Spacer(Modifier.height(14.dp))
        PointRow(
            title = "引き返したときのポイント",
            help = "ブロック画面で「わかった、やめる」を押した",
            value = consequence.keepPoints,
            fallback = policy.defaultKeepPoints,
            onChange = { onChange(consequence.copy(keepPoints = it)) },
        )

        if (policy.enabled && policy.chargeOverride) {
            Spacer(Modifier.height(8.dp))
            val cost = policy.overrideCost(consequence.breakPoints)
            Text(
                if (cost > 0) {
                    "いまの設定では、このルールを押し切るのに ${cost}ポイント要ります。"
                } else {
                    "いまの設定では、このルールはポイント無しで押し切れます。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }

    if (showAllowPicker) {
        AppPickerDialog(
            title = "閉めないでおくアプリ",
            selected = consequence.lockAllowPackages,
            onToggle = { pkg ->
                val current = consequence.lockAllowPackages
                onChange(
                    consequence.copy(
                        lockAllowPackages = if (pkg in current) current - pkg else current + pkg
                    )
                )
            },
            onDismiss = { showAllowPicker = false },
        )
    }
}

/** ポイントの増減1つ。「設定どおり」を選ぶと値を持たない。 */
@Composable
private fun PointRow(
    title: String,
    help: String,
    value: Int?,
    fallback: Int,
    onChange: (Int?) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
        Text(
            help,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(
                selected = value == null,
                onClick = { onChange(null) },
                label = { Text("設定どおり($fallback)") },
            )
            FilterChip(
                selected = value != null,
                onClick = { if (value == null) onChange(fallback) },
                label = { Text("このルールだけ変える") },
            )
        }
        if (value != null) {
            Spacer(Modifier.height(4.dp))
            NumberStepper(
                value = value,
                min = -200,
                max = 200,
                step = 1,
                suffix = "pt",
                onChange = { onChange(it) },
            )
        }
    }
}
