package com.dopachiru.ui.rules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dopachiru.core.condition.ConditionGroup
import com.dopachiru.core.condition.ConditionRegistry
import com.dopachiru.core.model.ConditionNode
import com.dopachiru.core.model.ConditionTree
import com.dopachiru.core.model.NodePath
import com.dopachiru.core.param.Params

/**
 * 条件の木を編集する画面。かたまりを入れ子にして AND / OR / NOT を組める。
 *
 * ## 見せ方
 * 木をそのまま木として見せるのではなく、**かたまりの箱**として見せている。
 * 「すべて満たす / どれか満たす」を箱の見出しに置き、子は一段下げて並べる。
 * 括弧やインデントの深さを数えなくても、どこまでが一組かが分かる。
 *
 * 否定は箱を1つ増やさず、「〜でないとき」の札として各項目に付ける。
 * データの上では [ConditionNode.Not] という入れ子だが、
 * 使う側にとっては「この条件をひっくり返す」以上の意味が無いため。
 *
 * ## 状態を持たない
 * 編集用の別モデルを作らず、保存する木そのものを差し替えていく。
 * 画面に見えているものと保存されるものが常に同じで、変換のずれが起きない。
 */
@Composable
fun ConditionTreeEditor(
    root: ConditionNode,
    onChange: (ConditionNode) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pickerTarget by remember { mutableStateOf<NodePath?>(null) }

    Column(modifier) {
        GroupCard(
            root = root,
            path = emptyList(),
            depth = 0,
            onChange = onChange,
            onPickCondition = { pickerTarget = it },
        )
    }

    pickerTarget?.let { path ->
        ConditionPickerDialog(
            onPick = { typeId ->
                val type = ConditionRegistry[typeId]
                if (type != null) {
                    onChange(
                        ConditionTree.addChild(
                            root,
                            path,
                            ConditionNode.Leaf(typeId, Params.defaultsOf(type.params)),
                        )
                    )
                }
                pickerTarget = null
            },
            onDismiss = { pickerTarget = null },
        )
    }
}

/** かたまり1つ。中の項目を再帰的に並べる。 */
@Composable
private fun GroupCard(
    root: ConditionNode,
    path: NodePath,
    depth: Int,
    onChange: (ConditionNode) -> Unit,
    onPickCondition: (NodePath) -> Unit,
) {
    val node = ConditionTree.nodeAt(root, path) ?: return
    val children = ConditionTree.childrenOf(node) ?: return
    val isAll = ConditionTree.isAll(node)
    val negated = ConditionTree.isNegated(node)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            // 入れ子を1段ごとに明るくして、どこまでが一組かを目で追えるようにする
            containerColor = if (depth == 0) {
                MaterialTheme.colorScheme.surface
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = isAll,
                        onClick = { onChange(ConditionTree.setAll(root, path, true)) },
                        label = { Text("すべて満たす") },
                    )
                    FilterChip(
                        selected = !isAll,
                        onClick = { onChange(ConditionTree.setAll(root, path, false)) },
                        label = { Text("どれか満たす") },
                    )
                }
                if (depth > 0) {
                    TextButton(onClick = { onChange(ConditionTree.removeAt(root, path)) }) {
                        Text("削除", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            NegateRow(
                negated = negated,
                label = "このかたまりを反転する",
                onToggle = { onChange(ConditionTree.setNegated(root, path, it)) },
            )

            if (children.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    if (depth == 0) {
                        "条件なし = 対象アプリを常に制限します(完全封印)。"
                    } else {
                        "空のかたまりは無視されます。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            children.forEachIndexed { index, child ->
                Spacer(Modifier.height(8.dp))
                val childPath = path + index
                if (ConditionTree.isGroup(child)) {
                    GroupCard(
                        root = root,
                        path = childPath,
                        depth = depth + 1,
                        onChange = onChange,
                        onPickCondition = onPickCondition,
                    )
                } else {
                    LeafCard(root = root, path = childPath, onChange = onChange)
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onPickCondition(path) }) { Text("条件を足す") }
                // 入れ子は2段までにしてある。3段目からは画面の横幅が足りず、
                // 括弧の対応を目で追えなくなる。それより深い式が要るなら、
                // ルールを2つに分けたほうが後から読める
                if (depth < 2) {
                    TextButton(
                        onClick = {
                            onChange(ConditionTree.addChild(root, path, ConditionNode.AllOf()))
                        }
                    ) {
                        Text("かたまりを足す")
                    }
                }
            }
        }
    }
}

/** 条件1つ。 */
@Composable
private fun LeafCard(
    root: ConditionNode,
    path: NodePath,
    onChange: (ConditionNode) -> Unit,
) {
    val node = ConditionTree.nodeAt(root, path) ?: return
    val (inner, negated) = ConditionTree.stripNot(node)
    val leaf = inner as? ConditionNode.Leaf ?: return
    val type = ConditionRegistry[leaf.typeId]

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    type?.displayName ?: leaf.typeId,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                TextButton(onClick = { onChange(ConditionTree.removeAt(root, path)) }) {
                    Text("削除", style = MaterialTheme.typography.labelMedium)
                }
            }

            NegateRow(
                negated = negated,
                label = "この条件を反転する",
                onToggle = { onChange(ConditionTree.setNegated(root, path, it)) },
            )

            if (type != null) {
                if (!type.available) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "この条件はいま凍結中です。設定は残っていますが、成立しません。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(8.dp))
                ParamEditor(
                    specs = type.params,
                    params = leaf.params,
                    onChange = { onChange(ConditionTree.setParams(root, path, it)) },
                )
            } else {
                Spacer(Modifier.height(4.dp))
                Text(
                    "この端末では扱えない条件です。消さずに置いておけば、対応した版で元どおり動きます。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 「〜でないとき」の札。 */
@Composable
private fun NegateRow(
    negated: Boolean,
    label: String,
    onToggle: (Boolean) -> Unit,
) {
    Spacer(Modifier.height(4.dp))
    FilterChip(
        selected = negated,
        onClick = { onToggle(!negated) },
        label = {
            Text(
                if (negated) "でないとき" else label,
                style = MaterialTheme.typography.labelSmall,
            )
        },
    )
}

/**
 * 条件を選ぶ画面。
 *
 * ## まず仲間、そのあと中身
 *
 * 20種類を平らに並べていたのをやめた。名前だけ20個見せられても、
 * 何を選べばいいのかは文字から読み取れない ──「やりたいことはできるはずなのに、
 * やり方が複雑に見える」の大半がここだった。
 *
 * 一度に見る数を減らすのが狙いで、選択肢そのものは1つも減らしていない。
 * 探す欄に何か打てば、仲間をまたいで一気に絞る(仲間分けが邪魔になる瞬間があるので、
 * **飛び越える道**を必ず残す)。
 *
 * ## 例を必ず出す
 *
 * 名前と説明だけでは「で、これは何に使うの」が埋まらない。実際に書ける文を
 * 1行添えてある([ConditionType.example])。
 */
@Composable
private fun ConditionPickerDialog(
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    // 開いた直後は仲間だけを見せる。選ぶと中身が開く
    var opened by remember { mutableStateOf<ConditionGroup?>(null) }

    val searching = query.isNotBlank()
    val hits = remember(query) { if (searching) ConditionRegistry.search(query) else emptyList() }
    val groups = remember { ConditionRegistry.byGroup() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (searching) "条件を探す" else opened?.label ?: "どういう条件にしますか") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("探す(「平日」「ショート」など)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))

                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    when {
                        searching -> {
                            if (hits.isEmpty()) {
                                item {
                                    Text(
                                        "当たるものがありません。言い回しを変えてみてください。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            items(hits, key = { it.id }) { type ->
                                ConditionRow(type, showGroup = true) { onPick(type.id) }
                            }
                        }

                        opened == null -> {
                            items(groups, key = { it.first.name }) { (group, types) ->
                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    onClick = { opened = group },
                                ) {
                                    Column(Modifier.padding(14.dp)) {
                                        Text(
                                            group.label,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            group.summary,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            "${types.size}種類",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }

                        else -> {
                            val types = groups.firstOrNull { it.first == opened }?.second.orEmpty()
                            items(types, key = { it.id }) { type ->
                                ConditionRow(type, showGroup = false) { onPick(type.id) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("やめる") } },
        dismissButton = {
            // 中身を開いているときだけ、棚の一覧へ戻れるように
            if (opened != null && !searching) {
                TextButton(onClick = { opened = null }) { Text("ほかの種類") }
            }
        },
    )
}

/** 条件1つぶん。名前・説明・**実際に書ける例**の3行。 */
@Composable
private fun ConditionRow(
    type: com.dopachiru.core.condition.ConditionType,
    showGroup: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        onClick = onClick,
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    type.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                if (showGroup) {
                    Text(
                        type.group.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                type.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (type.example.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "例: " + type.example,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
