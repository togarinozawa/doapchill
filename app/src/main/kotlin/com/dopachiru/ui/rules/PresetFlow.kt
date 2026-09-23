package com.dopachiru.ui.rules

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dopachiru.core.model.Rule
import com.dopachiru.core.preset.PresetGroup
import com.dopachiru.core.preset.RulePreset
import com.dopachiru.core.preset.RulePresets

/**
 * 雛形から1本作る流れ。雛形を選ぶ → アプリを選ぶ → できたルールを渡す。
 *
 * ルールの画面と「今日だけ」の両方から使う。**同じ並びから選べる**ことが大事で、
 * 入口ごとに選べるものが違うと、今日だけのほうでは作りたいものが作れない。
 */
@Composable
fun PresetFlow(onBuilt: (Rule) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var awaiting by remember { mutableStateOf<RulePreset?>(null) }
    var packages by remember { mutableStateOf(emptySet<String>()) }
    var showAppPicker by remember { mutableStateOf(false) }

    val preset = awaiting
    if (preset == null) {
        PresetPickerDialog(
            onPick = {
                packages = emptySet()
                awaiting = it
            },
            onDismiss = onDismiss,
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(preset.name) },
            text = {
                Column {
                    Text(preset.description, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (packages.isEmpty()) {
                            preset.appPrompt
                        } else {
                            packages.joinToString("、") { InstalledApps.labelOf(context, it) }
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { showAppPicker = true }) { Text("アプリを選ぶ") }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onBuilt(preset.build(packages))
                        onDismiss()
                    },
                    enabled = packages.isNotEmpty() || preset.allowEmptyApps,
                ) { Text("作る") }
            },
            dismissButton = {
                TextButton(onClick = { awaiting = null }) { Text("ほかの雛形") }
            },
        )
    }

    if (showAppPicker) {
        AppPickerDialog(
            selected = packages,
            onToggle = { pkg -> packages = if (pkg in packages) packages - pkg else packages + pkg },
            onDismiss = { showAppPicker = false },
        )
    }
}

@Composable
private fun PresetPickerDialog(
    onPick: (RulePreset) -> Unit,
    onDismiss: () -> Unit,
) {
    // 弱いものから順に並べる。強い介入ほど効くが、いちばん助けが要る人ほど拒む
    // (依存傾向が高い群の41.7%が最弱を選好した)。上から目に入る順番が既定になる。
    val grouped = remember { RulePresets.all.groupBy { it.group } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("雛形を選ぶ") },
        text = {
            LazyColumn(Modifier.heightIn(max = 440.dp)) {
                item {
                    Text(
                        "上ほど軽く、下ほど強い措置です。強いものから始めると、" +
                            "だいたい続かないか、目標のほうを緩めることになります。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                }

                PresetGroup.entries.forEach { group ->
                    val presets = grouped[group].orEmpty()
                    if (presets.isEmpty()) return@forEach

                    item(key = "header-${group.name}") {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            group.label,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            group.help,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                    }

                    items(presets, key = { it.id }) { preset ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            onClick = { onPick(preset) },
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(preset.name, style = MaterialTheme.typography.titleSmall)
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    preset.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                // なぜ効くのかを添える。理由の分かる縛りのほうが守られる
                                if (preset.evidence.isNotBlank()) {
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        preset.evidence,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                            .copy(alpha = 0.75f),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("やめる") } },
    )
}
