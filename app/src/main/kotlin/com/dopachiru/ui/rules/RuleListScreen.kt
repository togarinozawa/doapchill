package com.dopachiru.ui.rules

import android.app.Application
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dopachiru.core.action.ActionRegistry
import com.dopachiru.core.gate.ChangeKind
import com.dopachiru.core.model.ConditionTree
import com.dopachiru.core.model.Rule
import com.dopachiru.core.preset.PresetGroup
import com.dopachiru.core.preset.RulePreset
import com.dopachiru.core.preset.RulePresets
import com.dopachiru.runtime.DopaRuntime
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class RuleListViewModel(app: Application) : AndroidViewModel(app) {
    val rules: StateFlow<List<Rule>> = DopaRuntime.rules.rules
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 有効/無効の切り替え。
     * 有効化は即時、無効化はゲートを通す(縛りを緩める方向にだけ摩擦をかける)。
     */
    fun toggle(rule: Rule) {
        viewModelScope.launch {
            if (rule.enabled) {
                val gates = DopaRuntime.settings.gates.first()
                DopaRuntime.changes.request(ChangeKind.DISABLE, rule, gates)
            } else {
                DopaRuntime.changes.request(ChangeKind.ENABLE, rule, emptyList())
            }
        }
    }

    /** 雛形から作る。新規作成なのでゲートは通さず即時反映。 */
    fun createFromPreset(preset: RulePreset, packages: Set<String>) {
        if (packages.isEmpty() && !preset.allowEmptyApps) return
        viewModelScope.launch {
            DopaRuntime.changes.request(ChangeKind.CREATE, preset.build(packages), emptyList())
        }
    }
}

@Composable
fun RuleListScreen(
    onCreate: () -> Unit,
    onEdit: (Long) -> Unit,
    viewModel: RuleListViewModel = viewModel(),
) {
    val rules by viewModel.rules.collectAsState()
    val context = LocalContext.current

    var pickingPreset by remember { mutableStateOf(false) }
    var presetAwaitingApps by remember { mutableStateOf<RulePreset?>(null) }
    var presetPackages by remember { mutableStateOf(emptySet<String>()) }
    var showPresetAppPicker by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        if (rules.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("まだルールがありません", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "雛形から始めるのが速いです。細部はあとから直せます。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(20.dp))
                Button(onClick = { pickingPreset = true }) { Text("雛形から作る") }
                RuleTransferControls()
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onCreate) { Text("ゼロから組む") }
            }
        } else {
            LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    OutlinedButton(
                        onClick = { pickingPreset = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("雛形から足す")
                    }
                    // 込み入ったルールは、書き出して手元の道具に直してもらうほうが早い
                    RuleTransferControls()
                }

                items(rules, key = { it.id }) { rule ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onEdit(rule.id) },
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    rule.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    describeTarget(context, rule),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    describeRule(rule),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            Switch(
                                checked = rule.enabled,
                                onCheckedChange = { viewModel.toggle(rule) },
                            )
                        }
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = onCreate,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = "ルールを作る")
        }
    }

    if (pickingPreset) {
        PresetPickerDialog(
            onPick = {
                pickingPreset = false
                presetPackages = emptySet()
                presetAwaitingApps = it
            },
            onDismiss = { pickingPreset = false },
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
                        if (presetPackages.isEmpty()) {
                            preset.appPrompt
                        } else {
                            presetPackages.joinToString("、") { InstalledApps.labelOf(context, it) }
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { showPresetAppPicker = true }) { Text("アプリを選ぶ") }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.createFromPreset(preset, presetPackages)
                        presetAwaitingApps = null
                    },
                    enabled = presetPackages.isNotEmpty() || preset.allowEmptyApps,
                ) { Text("作る") }
            },
            dismissButton = {
                TextButton(onClick = { presetAwaitingApps = null }) { Text("やめる") }
            },
        )
    }

    if (showPresetAppPicker) {
        AppPickerDialog(
            selected = presetPackages,
            onToggle = { pkg ->
                presetPackages =
                    if (pkg in presetPackages) presetPackages - pkg else presetPackages + pkg
            },
            onDismiss = { showPresetAppPicker = false },
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

private fun describeTarget(context: android.content.Context, rule: Rule): String {
    if (rule.target.matchAll) {
        val excluded = rule.target.exceptPackages.size + rule.target.exceptTags.size
        return if (excluded == 0) "全アプリ" else "全アプリ(除外${excluded}件)"
    }
    val apps = rule.target.packages.map { InstalledApps.labelOf(context, it) }
    val tags = rule.target.tags.map { "#$it" }
    val all = apps + tags
    return when {
        all.isEmpty() -> "対象なし"
        all.size <= 3 -> all.joinToString("、")
        else -> "${all.take(3).joinToString("、")} 他${all.size - 3}件"
    }
}

/**
 * 条件・アクション・罰を1行に畳む。
 *
 * 入れ子や OR も [ConditionTree.describe] が括弧付きで畳んでくれるので、
 * 一覧を見ただけで中身の見当がつく。
 */
fun describeRule(rule: Rule): String {
    val condition = ConditionTree.describe(rule.condition)
    val action = ActionRegistry[rule.actionId]?.summarize(rule.actionParams) ?: rule.actionId
    val head = if (ConditionTree.leafCount(rule.condition) == 0) "常に" else condition
    val consequence = if (rule.consequence.locksNothing) {
        ""
    } else {
        " / 破ったら${rule.consequence.lockScope.label}を${rule.consequence.lockMinutes}分"
    }
    return "$head → $action$consequence"
}
