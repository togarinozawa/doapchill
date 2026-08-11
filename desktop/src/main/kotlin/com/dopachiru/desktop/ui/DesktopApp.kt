package com.dopachiru.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import com.dopachiru.core.action.ActionRegistry
import com.dopachiru.core.condition.ConditionRegistry
import com.dopachiru.core.model.ConditionNode
import com.dopachiru.core.model.Rule
import com.dopachiru.core.preset.RulePreset
import com.dopachiru.core.preset.RulePresets
import com.dopachiru.desktop.DesktopRuntime
import com.dopachiru.desktop.platform.BlockStrength
import com.dopachiru.desktop.platform.ForegroundApp
import com.dopachiru.desktop.platform.ProtectedProcesses
import com.dopachiru.desktop.platform.RunningApps

@Composable
fun DesktopApp() = DopaTheme {
    var tab by remember { mutableIntStateOf(0) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Scaffold(
            topBar = {
                TabRow(selectedTabIndex = tab) {
                    listOf("ルール", "今日", "設定").forEachIndexed { index, title ->
                        Tab(
                            selected = tab == index,
                            onClick = { tab = index },
                            text = { Text(title) },
                        )
                    }
                }
            }
        ) { padding ->
            Box(Modifier.padding(padding)) {
                when (tab) {
                    0 -> RulesTab()
                    1 -> TodayTab()
                    else -> SettingsTab()
                }
            }
        }
    }
}

// ----------------------------------------------------------------------

@Composable
private fun RulesTab() {
    val file by DesktopRuntime.ruleFile.collectAsState()
    var pickingPreset by remember { mutableStateOf(false) }
    var presetAwaitingApps by remember { mutableStateOf<RulePreset?>(null) }
    var presetProcesses by remember { mutableStateOf(emptySet<String>()) }
    var showAppPicker by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        OutlinedButton(onClick = { pickingPreset = true }, modifier = Modifier.fillMaxWidth()) {
            Text("雛形から足す")
        }
        Spacer(Modifier.height(12.dp))

        if (file.rules.isEmpty()) {
            Text(
                "まだルールがありません。雛形から始めるのが速いです。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(file.rules, key = { it.id }) { rule -> RuleCard(rule) }
        }
    }

    if (pickingPreset) {
        AlertDialog(
            onDismissRequest = { pickingPreset = false },
            title = { Text("雛形を選ぶ") },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(RulePresets.all, key = { it.id }) { preset ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            onClick = {
                                pickingPreset = false
                                presetProcesses = emptySet()
                                presetAwaitingApps = preset
                            },
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(preset.name, style = MaterialTheme.typography.titleSmall)
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    preset.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickingPreset = false }) { Text("やめる") } },
        )
    }

    presetAwaitingApps?.let { preset ->
        AlertDialog(
            onDismissRequest = { presetAwaitingApps = null },
            title = { Text(preset.name) },
            text = {
                Column {
                    Text(preset.description, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (presetProcesses.isEmpty()) preset.appPrompt
                        else presetProcesses.joinToString("、") { ForegroundApp.labelFor(it) },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { showAppPicker = true }) { Text("アプリを選ぶ") }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = presetProcesses.isNotEmpty() || preset.allowEmptyApps,
                    onClick = {
                        DesktopRuntime.addRule(preset.build(presetProcesses))
                        presetAwaitingApps = null
                    },
                ) { Text("作る") }
            },
            dismissButton = {
                TextButton(onClick = { presetAwaitingApps = null }) { Text("やめる") }
            },
        )
    }

    if (showAppPicker) {
        AppPickerDialog(
            selected = presetProcesses,
            onToggle = { process ->
                presetProcesses =
                    if (process in presetProcesses) presetProcesses - process
                    else presetProcesses + process
            },
            onDismiss = { showAppPicker = false },
        )
    }
}

@Composable
private fun RuleCard(rule: Rule) {
    var confirmDelete by remember { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(rule.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Switch(
                    checked = rule.enabled,
                    onCheckedChange = { DesktopRuntime.setRuleEnabled(rule.id, it) },
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                describeTarget(rule),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                describeRule(rule),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = { confirmDelete = true }) {
                Text("削除", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("「${rule.name}」を削除しますか?") },
            confirmButton = {
                TextButton(onClick = {
                    DesktopRuntime.removeRule(rule.id)
                    confirmDelete = false
                }) { Text("削除する") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("やめる") }
            },
        )
    }
}

private fun describeTarget(rule: Rule): String {
    if (rule.target.matchAll) {
        val excluded = rule.target.exceptPackages.size + rule.target.exceptTags.size
        return if (excluded == 0) "全アプリ" else "全アプリ(除外${excluded}件)"
    }
    val names = rule.target.packages.map { ForegroundApp.labelFor(it) } +
        rule.target.tags.map { "#$it" }
    return when {
        names.isEmpty() -> "対象なし"
        names.size <= 3 -> names.joinToString("、")
        else -> "${names.take(3).joinToString("、")} 他${names.size - 3}件"
    }
}

private fun describeRule(rule: Rule): String {
    val conditions = flatten(rule.condition).mapNotNull { leaf ->
        ConditionRegistry[leaf.typeId]?.summarize(leaf.params)
    }
    val action = ActionRegistry[rule.actionId]?.summarize(rule.actionParams) ?: rule.actionId
    val when_ = if (conditions.isEmpty()) "常に" else conditions.joinToString(" かつ ")
    return "$when_ → $action"
}

private fun flatten(node: ConditionNode): List<ConditionNode.Leaf> = when (node) {
    is ConditionNode.Leaf -> listOf(node)
    is ConditionNode.AllOf -> node.children.flatMap { flatten(it) }
    is ConditionNode.AnyOf -> node.children.flatMap { flatten(it) }
    is ConditionNode.Not -> flatten(node.child)
}

// ----------------------------------------------------------------------

@Composable
private fun TodayTab() {
    val foreground by DesktopRuntime.foreground.collectAsState()
    val breakdown = remember(foreground) { DesktopRuntime.todayBreakdown() }
    val total = remember(foreground) { DesktopRuntime.todayTotalMinutes() }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("今日の合計", style = MaterialTheme.typography.labelMedium)
                Text(
                    formatMinutes(total),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Light,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "いま前面: ${foreground?.label ?: "—"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("アプリごと", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        LazyColumn {
            items(breakdown) { (process, minutes) ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(ForegroundApp.labelFor(process), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        formatMinutes(minutes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ----------------------------------------------------------------------

@Composable
private fun SettingsTab() {
    val settings by DesktopRuntime.settings.collectAsState()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("ブロックのやり方", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            "Windows には Android のような統一された止め方がありません。どこまでやるか選べます。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        BlockStrength.entries.forEach { strength ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.Top,
            ) {
                RadioButton(
                    selected = settings.blockStrength == strength,
                    onClick = { DesktopRuntime.updateSettings { it.copy(blockStrength = strength) } },
                )
                Column(Modifier.padding(start = 4.dp)) {
                    Text(strength.displayName, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        strength.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(20.dp))

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text("一時停止", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "何も止めなくなります。トレイからも切り替えられます。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = settings.paused,
                onCheckedChange = { DesktopRuntime.updateSettings { s -> s.copy(paused = it) } },
            )
        }

        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(20.dp))

        Text("必ず止めないもの", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            "ルールより強く、ここからも外せません。タスクマネージャを入れてあるのは、" +
                "ドパチル自身を必ず止められるようにしておくためです。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            ProtectedProcesses.all().sorted().joinToString("、"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ----------------------------------------------------------------------

@Composable
private fun AppPickerDialog(
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // Windows には「インストール済みアプリ」の統一された一覧が無いので、
    // いま窓を持って動いているものから選ばせる。
    val running = remember { RunningApps.visible() }
    var manual by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("対象アプリ") },
        text = {
            Column {
                Text(
                    "いま起動しているアプリから選びます。閉じているアプリは、" +
                        "実行ファイル名を直接足してください。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text("${selected.size} 個選択中", style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(4.dp))

                LazyColumn(Modifier.heightIn(max = 360.dp)) {
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
                    if (selected.any { s -> running.none { it.processName == s } }) {
                        item {
                            Spacer(Modifier.height(8.dp))
                            Text("いま動いていない選択中のもの", style = MaterialTheme.typography.labelSmall)
                        }
                        items(selected.filter { s -> running.none { it.processName == s } }) { process ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = true, onCheckedChange = { onToggle(process) })
                                Text(process, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.OutlinedTextField(
                        value = manual,
                        onValueChange = { manual = it },
                        label = { Text("実行ファイル名 (例: notepad.exe)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        enabled = manual.isNotBlank(),
                        onClick = {
                            onToggle(manual.trim().lowercase())
                            manual = ""
                        },
                    ) { Text("足す") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
    )
}

private fun formatMinutes(minutes: Int): String {
    if (minutes < 60) return "${minutes}分"
    val h = minutes / 60
    val m = minutes % 60
    return if (m == 0) "${h}時間" else "${h}時間${m}分"
}
