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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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
import com.dopachiru.core.model.DeviceScope
import com.dopachiru.core.model.Rule
import com.dopachiru.runtime.DopaRuntime
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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

    /**
     * まるごと写して1本増やす。少しだけ違うものを作るときの近道。
     * 番号と uid は新しく振ります ── 引き継ぐと、写した先が元を上書きします。
     *
     * 新規作成なので関門は通しません(縛りを増やす方向)。
     */
    fun duplicate(rule: Rule) {
        viewModelScope.launch {
            val copy = rule.copy(id = 0L, uid = "", name = rule.name + "(写し)")
            DopaRuntime.changes.request(ChangeKind.CREATE, copy, emptyList())
        }
    }

    /** 名簿。一覧に「どの端末で効くか」を出すため。 */
    val deviceNames: StateFlow<Map<String, String>> = DopaRuntime.devices
        .map { list -> list.associate { it.deviceId to it.displayName } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** 雛形から作る。新規作成なのでゲートは通さず即時反映。 */
    fun create(rule: Rule) {
        viewModelScope.launch {
            DopaRuntime.changes.request(ChangeKind.CREATE, rule, emptyList())
        }
    }
}

@Composable
fun RuleListScreen(
    onCreate: () -> Unit,
    onEdit: (Long) -> Unit,
    onOpenTags: () -> Unit = {},
    viewModel: RuleListViewModel = viewModel(),
) {
    val rules by viewModel.rules.collectAsState()
    val deviceNames by viewModel.deviceNames.collectAsState()
    val context = LocalContext.current

    var pickingPreset by remember { mutableStateOf(false) }

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
                TextButton(onClick = onOpenTags) { Text("タグを編集") }
            }
        } else {
            LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { pickingPreset = true },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("雛形から足す")
                        }
                        // タグはタブをやめてここに置いた。ルールを書くときにしか
                        // 触らないものに、下タブを1つ使う必要はない
                        OutlinedButton(onClick = onOpenTags, modifier = Modifier.weight(1f)) {
                            Text("タグを編集")
                        }
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
                                if (rule.devices.isNotEmpty()) {
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        "端末: " + DeviceScope.describe(rule.devices) { id ->
                                            deviceNames[id] ?: id
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                TextButton(onClick = { viewModel.duplicate(rule) }) { Text("複製") }
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
        PresetFlow(onBuilt = viewModel::create, onDismiss = { pickingPreset = false })
    }
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
    val consequence = rule.consequence.breakPoints
        ?.takeIf { it != 0 }
        ?.let { pt -> " / 破ったら${if (pt < 0) "${-pt}pt払う" else "+${pt}pt"}" }
        ?: ""
    // 2組目以降があることを隠さない。隠すと、一覧に出ていない組が黙って
    // 効いて「書いていないのに閉まる」になる
    val more = if (rule.extraClauses.isEmpty()) "" else " ほか" + rule.extraClauses.size + "組"
    return "$head → $action$consequence$more"
}
