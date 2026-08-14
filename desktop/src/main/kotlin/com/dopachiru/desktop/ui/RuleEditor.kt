package com.dopachiru.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import com.dopachiru.core.condition.ConditionRegistry
import com.dopachiru.core.model.ConditionNode
import com.dopachiru.core.model.ConditionTree
import com.dopachiru.core.model.Consequence
import com.dopachiru.core.model.LockScope
import com.dopachiru.core.model.NodePath
import com.dopachiru.core.model.Rule
import com.dopachiru.core.param.Params
import com.dopachiru.core.points.PointPolicy

/**
 * ルールの条件と罰を編集する。Windows 版。
 *
 * 対象アプリとアクションは雛形から入ったものをそのまま使う。ここで触れるのは
 * 「いつ効くか(条件)」と「破ったらどうなるか(罰)」の2つ ──
 * ルールの意味を決めているのはこの2つで、残りは雛形で十分に足りるため。
 */
@Composable
fun RuleEditorDialog(
    rule: Rule,
    policy: PointPolicy,
    onSave: (Rule) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember(rule.id) { mutableStateOf(rule) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(rule.name) },
        text = {
            Column(
                Modifier.widthIn(min = 520.dp)
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text("条件", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    ConditionTree.describe(draft.condition),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(10.dp))
                ConditionTreeEditor(
                    root = draft.condition,
                    onChange = { draft = draft.copy(condition = it) },
                )

                Spacer(Modifier.height(20.dp))
                HorizontalDivider()
                Spacer(Modifier.height(20.dp))

                Text(
                    "破ったら / 守ったら",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "その場の措置とは別に、あとから効く報い。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                ConsequenceEditor(
                    consequence = draft.consequence,
                    policy = policy,
                    onChange = { draft = draft.copy(consequence = it) },
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(draft) }) { Text("保存する") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("やめる") } },
    )
}

// ----------------------------------------------------------------------

/**
 * 条件の木を編集する。かたまりを入れ子にして AND / OR / NOT を組める。
 *
 * 木をそのまま木として見せるのではなく、**かたまりの箱**として見せている。
 * 否定は箱を増やさず「〜でないとき」の札として各項目に付ける ──
 * データの上では入れ子だが、使う側にとっては「ひっくり返す」以上の意味が無い。
 */
@Composable
fun ConditionTreeEditor(
    root: ConditionNode,
    onChange: (ConditionNode) -> Unit,
) {
    var pickerTarget by remember { mutableStateOf<NodePath?>(null) }

    GroupCard(
        root = root,
        path = emptyList(),
        depth = 0,
        onChange = onChange,
        onPickCondition = { pickerTarget = it },
    )

    pickerTarget?.let { path ->
        AlertDialog(
            onDismissRequest = { pickerTarget = null },
            title = { Text("条件を選ぶ") },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(ConditionRegistry.all(), key = { it.id }) { type ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            onClick = {
                                onChange(
                                    ConditionTree.addChild(
                                        root,
                                        path,
                                        ConditionNode.Leaf(type.id, Params.defaultsOf(type.params)),
                                    )
                                )
                                pickerTarget = null
                            },
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(type.displayName, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    type.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickerTarget = null }) { Text("やめる") } },
        )
    }
}

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
                Modifier.fillMaxWidth(),
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
                        Text("削除")
                    }
                }
            }

            NegateChip(
                negated = ConditionTree.isNegated(node),
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
                    GroupCard(root, childPath, depth + 1, onChange, onPickCondition)
                } else {
                    LeafCard(root, childPath, onChange)
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onPickCondition(path) }) { Text("条件を足す") }
                // 入れ子は2段まで。3段目からは括弧の対応を目で追えなくなるので、
                // それより深い式が要るならルールを分けたほうが後から読める
                if (depth < 2) {
                    TextButton(
                        onClick = { onChange(ConditionTree.addChild(root, path, ConditionNode.AllOf())) }
                    ) { Text("かたまりを足す") }
                }
            }
        }
    }
}

@Composable
private fun LeafCard(root: ConditionNode, path: NodePath, onChange: (ConditionNode) -> Unit) {
    val node = ConditionTree.nodeAt(root, path) ?: return
    val (inner, negated) = ConditionTree.stripNot(node)
    val leaf = inner as? ConditionNode.Leaf ?: return
    val type = ConditionRegistry[leaf.typeId]

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    type?.displayName ?: leaf.typeId,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                TextButton(onClick = { onChange(ConditionTree.removeAt(root, path)) }) {
                    Text("削除")
                }
            }

            NegateChip(
                negated = negated,
                label = "この条件を反転する",
                onToggle = { onChange(ConditionTree.setNegated(root, path, it)) },
            )

            if (type != null) {
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

@Composable
private fun NegateChip(negated: Boolean, label: String, onToggle: (Boolean) -> Unit) {
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

// ----------------------------------------------------------------------

/** 破った / 守ったときに起きることの設定。 */
@Composable
fun ConsequenceEditor(
    consequence: Consequence,
    policy: PointPolicy,
    onChange: (Consequence) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Text("破ったら何が閉まるか", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LockScope.entries.forEach { scope ->
                FilterChip(
                    selected = consequence.lockScope == scope,
                    onClick = {
                        onChange(
                            consequence.copy(
                                lockScope = scope,
                                // 封鎖を選んだのに長さが 0 のままでは何も起きない
                                lockMinutes = when {
                                    scope == LockScope.NONE -> 0
                                    consequence.lockMinutes > 0 -> consequence.lockMinutes
                                    else -> 30
                                },
                            )
                        )
                    },
                    label = { Text(scope.label) },
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            consequence.lockScope.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (consequence.lockScope != LockScope.NONE) {
            Spacer(Modifier.height(12.dp))
            Text("どれくらい閉めるか", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            NumberStepper(
                value = consequence.lockMinutes,
                min = 0,
                max = Consequence.MAX_LOCK_MINUTES,
                step = 5,
                suffix = "分",
                onChange = { onChange(consequence.copy(lockMinutes = it)) },
            )
            Text(
                "上限は${Consequence.MAX_LOCK_MINUTES / 60}時間。桁を間違えて一日詰むことがないようにしてあります。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (consequence.lockScope == LockScope.EVERYTHING) {
            Spacer(Modifier.height(8.dp))
            Text(
                "エクスプローラ・タスクマネージャ・ドパチル自身は、閉めない一覧に入っています。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(16.dp))
        PointRow(
            title = "破ったときのポイント",
            help = "押し切る・宣言を超える・警告を無視する のいずれか",
            value = consequence.breakPoints,
            fallback = policy.defaultBreakPoints,
            onChange = { onChange(consequence.copy(breakPoints = it)) },
        )
        Spacer(Modifier.height(12.dp))
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
            NumberStepper(value = value, min = -200, max = 200, suffix = "pt", onChange = { onChange(it) })
        }
    }
}
