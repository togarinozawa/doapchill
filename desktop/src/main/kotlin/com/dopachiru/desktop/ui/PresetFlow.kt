package com.dopachiru.desktop.ui

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
import androidx.compose.ui.unit.dp
import com.dopachiru.core.model.Rule
import com.dopachiru.core.preset.RulePreset
import com.dopachiru.core.preset.RulePresets
import com.dopachiru.desktop.platform.ForegroundApp

/**
 * 雛形から1本作る流れ。雛形を選ぶ → アプリを選ぶ → できたルールを渡す。
 *
 * ルールのタブと「今日だけ」の両方から使う。**同じ並びから選べる**ことが大事で、
 * 入口ごとに選べるものが違うと、今日だけのほうでは作りたいものが作れない。
 */
@Composable
internal fun PresetFlow(onBuilt: (Rule) -> Unit, onDismiss: () -> Unit) {
    var awaiting by remember { mutableStateOf<RulePreset?>(null) }
    var processes by remember { mutableStateOf(emptySet<String>()) }
    var showAppPicker by remember { mutableStateOf(false) }

    val preset = awaiting
    if (preset == null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("雛形を選ぶ") },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(RulePresets.all, key = { it.id }) { item ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            onClick = {
                                processes = emptySet()
                                awaiting = item
                            },
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(item.name, style = MaterialTheme.typography.titleSmall)
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    item.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("やめる") } },
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
                        if (processes.isEmpty()) preset.appPrompt
                        else processes.joinToString("、") { ForegroundApp.labelFor(it) },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { showAppPicker = true }) { Text("アプリを選ぶ") }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = processes.isNotEmpty() || preset.allowEmptyApps,
                    onClick = {
                        onBuilt(preset.build(processes))
                        onDismiss()
                    },
                ) { Text("作る") }
            },
            dismissButton = {
                TextButton(onClick = { awaiting = null }) { Text("ほかの雛形") }
            },
        )
    }

    if (showAppPicker) {
        AppPickerDialog(
            selected = processes,
            onToggle = { process ->
                processes = if (process in processes) processes - process else processes + process
            },
            onDismiss = { showAppPicker = false },
        )
    }
}
