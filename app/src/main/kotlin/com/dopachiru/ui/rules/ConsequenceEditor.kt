package com.dopachiru.ui.rules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dopachiru.core.model.Consequence
import com.dopachiru.core.points.PointPolicy

/**
 * ルールを破った / 守ったときのポイントの増減。
 *
 * 「その場で何を出すか」(アクション)とは別の軸として並べている。
 * 封鎖(どこを・どれだけ閉めるか)はここには無い ── 押し切ることと破ることは
 * 同じ出来事なので、そのあとにもう一段閉める長さを別に決めさせても、
 * 二重に設定させるだけで分かりやすくならない。閉める長さそのものが要るなら、
 * 措置(「しばらく閉め出す」)の側で選ぶ。
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
    Column(Modifier.fillMaxWidth()) {
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
