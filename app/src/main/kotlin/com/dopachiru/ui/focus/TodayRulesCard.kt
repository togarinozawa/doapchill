package com.dopachiru.ui.focus

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dopachiru.core.gate.ChangeKind
import com.dopachiru.core.model.OneShotLimit
import com.dopachiru.core.model.Rule
import com.dopachiru.runtime.DopaRuntime
import com.dopachiru.ui.rules.InstalledApps
import com.dopachiru.ui.rules.PresetFlow
import com.dopachiru.ui.rules.describeRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 今日だけのルール。明日の朝4時に消える。
 *
 * ## なぜ集中タブなのか
 *
 * 「今日はこれで行く」は集中と同じ種類の決めごと ── その場で、期限つきで、
 * 自分から手を止める。記録の中にあると、決めたくなった瞬間に探すことになる。
 *
 * ## 作れるものはルールの画面と同じ
 *
 * 雛形からでも、ゼロから組んでもいい([PresetFlow] と同じ並び)。
 * 前は「◯時間使ったら◯分休憩」しか作れなかった。
 */
@Composable
fun TodayRulesCard(onCreateFromScratch: () -> Unit, onEdit: (Long) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rules by DopaRuntime.rules.rules.collectAsState(initial = emptyList())
    val temporary = remember(rules) { rules.filter { it.isTemporary } }
    var pickingPreset by remember { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("今日だけの枠", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "ルールの画面と同じものを、今日だけ作れます。明日の朝4時に消えます。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            temporary.forEach { rule ->
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                TemporaryRuleRow(
                    rule = rule,
                    label = { pkg -> InstalledApps.labelOf(context, pkg) },
                    onEdit = { onEdit(rule.id) },
                    onCancel = {
                        scope.launch {
                            // 消すのは縛りを緩める側。関門があるなら通す
                            val gates = DopaRuntime.settings.gates.first()
                            DopaRuntime.changes.request(ChangeKind.DELETE, rule, gates)
                        }
                    },
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickingPreset = true }) { Text("雛形から") }
                OutlinedButton(onClick = onCreateFromScratch) { Text("ゼロから組む") }
            }
        }
    }

    if (pickingPreset) {
        PresetFlow(
            onBuilt = { built ->
                scope.launch {
                    // 縛りを増やす側なので関門は通さない(ルールの画面と同じ)
                    DopaRuntime.changes.request(
                        ChangeKind.CREATE,
                        OneShotLimit.forToday(built, LocalDateTime.now()),
                        emptyList(),
                    )
                }
            },
            onDismiss = { pickingPreset = false },
        )
    }
}

/** いま効いている今日だけのルール1つぶん。 */
@Composable
private fun TemporaryRuleRow(
    rule: Rule,
    label: (String) -> String,
    onEdit: () -> Unit,
    onCancel: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(rule.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                rule.target.packages.joinToString("・") { label(it) }.ifBlank { describeRule(rule) },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                expiryLabel(rule.expiresAtSec) + "に消えます",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        TextButton(onClick = onEdit) { Text("直す") }
        TextButton(onClick = onCancel) { Text("やめる") }
    }
}

private val expiryFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("M/d HH:mm")

private fun expiryLabel(epochSec: Long): String =
    Instant.ofEpochSecond(epochSec).atZone(ZoneId.systemDefault()).format(expiryFormat)
