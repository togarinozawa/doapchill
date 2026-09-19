package com.dopachiru.ui.focus

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dopachiru.core.model.Focus

/**
 * 集中に時間を足すところ。**2段階にしてある。**
 *
 * ## なぜ1タップで足さないのか
 *
 * 誤タップで時間が伸びると悲惨だから。足したぶんは**切り上げないと戻せず**、
 * 切り上げには手間とポイントが要る([Focus] の出口は意図的に高い)。
 * つまり「押し間違えた」が、そのまま罰になってしまう。
 *
 * 縛りを**増やす**方向に摩擦をかけるのは普段やらない方針だが、ここだけは別。
 * 増やしたくて押したのか、指が滑ったのかを分ける必要があるのは、
 * **間違いの代償が高いとき**だけで、ここがちょうどそれにあたる。
 *
 * 選ぶのは何度でもやり直せる。押されるまで何も起きない。
 */
@Composable
fun ExtendFocusRow(
    onExtend: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var picked by remember { mutableStateOf<Int?>(null) }

    Column(modifier) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Focus.EXTEND_CHOICES.forEach { add ->
                FilterChip(
                    selected = picked == add,
                    // もう一度押したら選び直し(取り消し)。閉じるボタンを別に置くより素直
                    onClick = { picked = if (picked == add) null else add },
                    label = { Text("+${add}分") },
                )
            }
        }

        val choice = picked
        if (choice != null) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
                        onExtend(choice)
                        picked = null
                    },
                ) { Text("+${choice}分 足す") }
                TextButton(onClick = { picked = null }) { Text("やめる") }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                // 足したぶんは切り上げないと戻せない。押す前に言っておく
                "足したら戻せません。切り上げには手間とポイントが要ります。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
