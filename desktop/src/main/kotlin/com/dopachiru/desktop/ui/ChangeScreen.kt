package com.dopachiru.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dopachiru.core.DopaCore
import com.dopachiru.core.gate.ChangeKind
import com.dopachiru.core.gate.ChangeRequest
import com.dopachiru.core.gate.Gate
import com.dopachiru.core.model.Rule
import com.dopachiru.desktop.DesktopRuntime

/**
 * 関門待ちのルール変更。Windows 版。
 *
 * ## なぜ要るか
 *
 * これが無いと、**開きたくなった瞬間にルールを消せる**。消すのに2秒しか要らない
 * 縛りは縛りではない。関門を挟むと、緩めようとした自分と、緩めたあとの自分のあいだに
 * 時間が入る ── 衝動はたいてい、その時間を越えられない。
 *
 * ## 新しく作るルールは通す
 *
 * 関門がかかるのは**変更と削除**だけで、新規作成は素通り。縛りを増やすほうを
 * 渋らせる理由が無いし、渋らせると「まず緩めてから作り直す」を覚えてしまう。
 */
@Composable
fun ChangeScreen() {
    val ruleFile by DesktopRuntime.ruleFile.collectAsState()
    val settings by DesktopRuntime.settings.collectAsState()
    val pending = ruleFile.changeRequests

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("変更", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))

        if (settings.gates.isEmpty()) {
            Text(
                "関門を設定していないので、ルールの変更はその場で反映されます。" +
                    "設定 → 関門 で関門を足すと、変更と削除がここに積まれるようになります。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        Text(
            "ルールの変更と削除は、ここで関門を全部通すまで効きません。新しく作るぶんは素通りです。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        if (pending.isEmpty()) {
            Text(
                "待っている変更はありません。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        LazyColumn {
            items(pending, key = { it.id }) { request ->
                ChangeCard(request)
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun ChangeCard(request: ChangeRequest) {
    val remaining = DesktopRuntime.remainingGates(request)
    val ruleName = remember(request.payloadJson, request.previousJson) {
        val json = request.payloadJson.ifBlank { request.previousJson }
        runCatching { DopaCore.json.decodeFromString(Rule.serializer(), json).name }
            .getOrDefault("(名前なし)")
    }
    val what = when (request.kind) {
        ChangeKind.DELETE -> "「$ruleName」を消す"
        ChangeKind.CREATE -> "「$ruleName」を作る"
        ChangeKind.DISABLE -> "「$ruleName」を止める"
        ChangeKind.ENABLE -> "「$ruleName」を戻す"
        ChangeKind.UPDATE -> "「$ruleName」を書き換える"
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(what, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))

            if (remaining.isEmpty()) {
                Text(
                    "関門は全部通りました。まもなく反映されます。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Text(
                    "残り${remaining.size}つ",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                remaining.forEach { gate ->
                    GateRow(request = request, gate = gate)
                    Spacer(Modifier.height(6.dp))
                }
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { DesktopRuntime.cancelChange(request.id) }) {
                    Text("取り下げる", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/**
 * 関門1つ。
 *
 * 待つだけで通るもの(クールダウン・時間帯)は、何をすれば通るかを書くだけで
 * ボタンを置かない ── 押せるボタンがあると「押せば通る」と思ってしまう。
 */
@Composable
private fun GateRow(request: ChangeRequest, gate: Gate) {
    Column {
        Text("・" + gate.describe(), style = MaterialTheme.typography.bodySmall)

        when (gate) {
            is Gate.Cooldown -> {
                val waited = (System.currentTimeMillis() / 1000 - request.createdAtEpochSeconds) / 60
                val left = (gate.minutes - waited).coerceAtLeast(0)
                Text(
                    "あと約${left}分",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            is Gate.WriteReason -> {
                var text by remember(request.id) { mutableStateOf(request.reason) }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("なぜ変えたいのか") },
                    supportingText = {
                        Text(
                            "${text.trim().length} / ${gate.minLength} 文字",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        enabled = text.trim().length >= gate.minLength,
                        onClick = { DesktopRuntime.clearGate(request.id, gate.key, text.trim()) },
                    ) { Text("これで出す") }
                }
            }

            else -> Unit
        }
    }
}
