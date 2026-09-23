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
import androidx.compose.material3.OutlinedButton
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
import com.dopachiru.core.action.ActionExtras
import com.dopachiru.core.action.ActionRegistry
import com.dopachiru.core.action.types.BlockAction
import com.dopachiru.core.action.types.DelayAction
import com.dopachiru.core.action.types.LockoutAction
import com.dopachiru.core.action.types.WarnAction
import com.dopachiru.core.condition.ConditionGroup
import com.dopachiru.core.condition.ConditionRegistry
import com.dopachiru.core.model.ActionPlan
import com.dopachiru.core.model.MainAction
import com.dopachiru.core.model.ConditionNode
import com.dopachiru.core.model.ConditionTree
import com.dopachiru.core.model.Consequence
import com.dopachiru.core.model.NodePath
import com.dopachiru.core.model.Rule
import com.dopachiru.core.sync.RuleCatalog
import com.dopachiru.core.action.types.DeclareAction
import com.dopachiru.core.action.types.IntentionAction
import com.dopachiru.core.action.types.RadioAction
import com.dopachiru.core.action.types.TimerAction
import com.dopachiru.core.model.ActionSpec
import com.dopachiru.core.model.Clause
import com.dopachiru.core.model.Clauses
import com.dopachiru.core.model.RuleLinks
import com.dopachiru.core.model.RuleCheck
import com.dopachiru.core.model.RuleOverlap
import com.dopachiru.core.param.Params
import com.dopachiru.core.points.PointPolicy
import com.dopachiru.desktop.DesktopRuntime

/**
 * ルールを編集する。Windows 版。
 *
 * 「何を(対象)・いつ(条件)・どうする(措置)・破ったら(報い)」の4つを全部ここで触る。
 * 対象を雛形任せにしていたころは、雛形に載っていない exe を狙いようが無かった。
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
                Text("何を", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                TargetEditor(
                    target = draft.target,
                    onChange = { draft = draft.copy(target = it) },
                )

                // どの端末で効くかは選ばせない ── ルールはこの端末にしか無いので。
                // 聞くのは連動の相手だけで、端末が2台以上あるときだけ出す
                val file = DesktopRuntime.ruleFile.collectAsState().value
                val roster = file.devices
                val me = DesktopRuntime.myDeviceId()
                // ルールを配っていた頃に「スマホだけ」と決めたものが、配られた先に残っている
                val stranded = draft.devices.isNotEmpty() && me !in draft.devices
                if (roster.size >= 2 || stranded) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "この端末" +
                            (roster.firstOrNull { it.deviceId == me }?.let { "(" + it.displayName + ")" } ?: "") +
                            "で効きます",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    if (stranded) {
                        Text(
                            "以前の端末の指定が残っていて、このルールはいまどこでも効いていません。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(onClick = { draft = draft.copy(devices = emptySet()) }) {
                            Text("この端末で効かせる")
                        }
                    }
                }
                if (roster.size >= 2) {
                    Spacer(Modifier.height(8.dp))
                    LinkPicker(
                        condition = draft.condition,
                        names = roster.associate { it.deviceId to it.displayName },
                        catalogs = file.ruleCatalogs.filter { it.deviceId != me },
                        onChange = { draft = draft.copy(condition = it) },
                    )
                }

                Spacer(Modifier.height(20.dp))
                HorizontalDivider()
                Spacer(Modifier.height(20.dp))

                Text("いつ", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
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

                // 同じアプリを見ている他のルール。**もともと複数書ける**のに、
                // 画面のどこにもそう書いていないので気づけなかった
                val everything = DesktopRuntime.ruleFile.collectAsState().value.rules
                val siblings = RuleOverlap.siblingsOf(draft, everything)
                if (siblings.isNotEmpty()) {
                    val winner = RuleOverlap.winnerAmong(siblings + draft)
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                "同じアプリを見ているルールが、ほかに${siblings.size}本あります",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "条件ごとに違う動作をさせたいときは、1本に詰め込まずルールを分けます。" +
                                    "同時に成立したら、いちばん強いものが1つだけ効きます。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(6.dp))
                            siblings.take(4).forEach { other ->
                                Text(
                                    "・" + other.name +
                                        if (!other.enabled) "(止めてあります)" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (winner != null) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    if (winner.uid == draft.uid && draft.uid.isNotBlank()) {
                                        "全部が同時に成立したら、いま編集しているこれが効きます。"
                                    } else {
                                        "全部が同時に成立したら「${winner.name}」が効きます。"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }

                Text("条件を満たしたら", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))

                val plan = ActionPlan.from(draft.actionId, draft.actionParams)
                fun setPlan(p: ActionPlan) {
                    val (id, params) = p.resolve(draft.actionId, draft.actionParams)
                    draft = draft.copy(actionId = id, actionParams = params)
                }
                val advanced = ActionRegistry.all().filter {
                    it.id !in setOf(BlockAction.id, DelayAction.id, WarnAction.id)
                }

                // 主な動作(1つ)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = plan.main == MainAction.CLOSE,
                        onClick = { setPlan(plan.copy(main = MainAction.CLOSE)) },
                        label = { Text("使えなくする") },
                    )
                    FilterChip(
                        selected = plan.main == MainAction.DELAY,
                        onClick = { setPlan(plan.copy(main = MainAction.DELAY)) },
                        label = { Text("少し待たせて通す") },
                    )
                    FilterChip(
                        selected = plan.main == MainAction.WARN,
                        onClick = { setPlan(plan.copy(main = MainAction.WARN)) },
                        label = { Text("警告だけ") },
                    )
                    advanced.forEach { action ->
                        FilterChip(
                            selected = plan.main == MainAction.ADVANCED && draft.actionId == action.id,
                            onClick = {
                                draft = draft.copy(
                                    actionId = action.id,
                                    actionParams = Params.defaultsOf(action.params),
                                )
                            },
                            label = { Text(action.displayName) },
                        )
                    }
                }

                // 使えなくするの中身
                if (plan.main == MainAction.CLOSE) {
                    // 条件が続くかぎり閉まったまま、条件が外れたら開く。時間で区切って
                    // 閉めたいなら、上の「ほかの動作」から「しばらく閉め出す」を選ぶ
                    Spacer(Modifier.height(12.dp))
                    Text("逃げ道", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = !plan.soft,
                            onClick = { setPlan(plan.copy(soft = false)) },
                            label = { Text("しっかり") },
                        )
                        FilterChip(
                            selected = plan.soft,
                            onClick = { setPlan(plan.copy(soft = true)) },
                            label = { Text("やんわり") },
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (plan.soft) "手間をかければ押し切れます。押し切ると「破った」ことに。"
                        else "押し切る口はありません。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(Modifier.height(12.dp))
                    Text("重ねる", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = plan.prewarnSeconds > 0,
                            onClick = {
                                setPlan(plan.copy(prewarnSeconds = if (plan.prewarnSeconds > 0) 0 else ActionExtras.DEFAULT_PREWARN_SECONDS))
                            },
                            label = { Text("閉じる前にそっと知らせる") },
                        )
                    }
                    if (plan.prewarnSeconds > 0) {
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("知らせる長さ ", style = MaterialTheme.typography.bodySmall)
                            NumberStepper(
                                value = plan.prewarnSeconds, min = 1, max = ActionExtras.MAX_PREWARN_SECONDS,
                                step = 1, suffix = "秒",
                                onChange = { setPlan(plan.copy(prewarnSeconds = it)) },
                            )
                        }
                    }
                }

                // 文言・こまかい調整(既定のままで困らないので下に)
                ActionRegistry[draft.actionId]?.let { action ->
                    val hidden = setOf(
                        BlockAction.KEY_ALLOW_OVERRIDE,
                        ActionExtras.KEY_PREWARN_SECONDS,
                    )
                    val specs = if (plan.main == MainAction.ADVANCED) action.params
                    else action.params.filter { it.key !in hidden }
                    if (specs.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text("文言・こまかい調整", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(6.dp))
                        ParamEditor(
                            specs = specs,
                            params = draft.actionParams,
                            onChange = { draft = draft.copy(actionParams = it) },
                        )
                    }
                }

                // 重ねる覚え書きと、2組目以降
                Spacer(Modifier.height(20.dp))
                HorizontalDivider()
                Spacer(Modifier.height(20.dp))
                StackedActionsRow(draft.extraActions) { draft = draft.copy(extraActions = it) }

                Spacer(Modifier.height(20.dp))
                ExtraClausesSection(draft.extraClauses) { draft = draft.copy(extraClauses = it) }

                // 保存はできるが書いたとおりには効かない組み合わせを知らせる
                RuleCheck.warnings(
                    condition = draft.condition,
                    target = draft.target,
                    actionId = draft.actionId,
                    actionParams = draft.actionParams,
                ).forEach { warning ->
                    Spacer(Modifier.height(8.dp))
                    Text(warning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }

                // 破れる動作のときだけ、あとに効く報い
                if (RuleCheck.isBreakable(draft.actionId, draft.actionParams)) {
                    Spacer(Modifier.height(20.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(20.dp))
                    Text("破ったときの報い", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "「破った」= " + RuleCheck.breakMeans(draft.actionId, draft.actionParams),
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
            }
        },
        confirmButton = { TextButton(onClick = { onSave(draft) }) { Text("保存する") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("やめる") } },
    )
}

/**
 * 連動の相手を選ぶ。相手はほかの端末の名札から。
 *
 * 使いすぎを止めるルールは端末を替えれば逃げられる ── 持ち時間が端末ごとに
 * 1本ずつあるため。効いているという事実のほうを配って塞ぐ。
 */
@Composable
private fun LinkPicker(
    condition: ConditionNode,
    names: Map<String, String>,
    catalogs: List<RuleCatalog>,
    onChange: (ConditionNode) -> Unit,
) {
    val link = remember(condition) { RuleLinks.linkOf(condition) }
    val candidates = remember(catalogs) {
        catalogs.flatMap { catalog -> catalog.rules.map { catalog.deviceId to it } }
    }

    Text("端末をまたいで効かせる", style = MaterialTheme.typography.bodyMedium)
    Text(
        when {
            link == null -> "いまはこの端末だけで数えます。PC で使い切っても、スマホでは数え直しになります。"
            link.first.isBlank() -> "以前の形の連動です(同じルールが両方の端末にあった頃のもの)。"
            else -> "選んだルールが向こうで効いているあいだ、ここでも効きます。"
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(4.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = link == null,
            onClick = { onChange(RuleLinks.withoutLink(condition)) },
            label = { Text("連動しない") },
        )
        if (link != null && link.first.isBlank()) {
            FilterChip(selected = true, onClick = {}, label = { Text("以前の連動") })
        }
        candidates.forEach { (deviceId, card) ->
            FilterChip(
                selected = link?.first == card.uid,
                onClick = { onChange(RuleLinks.linkTo(condition, card.uid, deviceId)) },
                label = { Text((names[deviceId] ?: deviceId) + "・" + card.name) },
            )
        }
    }
    if (candidates.isEmpty()) {
        Text(
            "ほかの端末のルールがまだ届いていません。向こうで同期すると出てきます。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
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
        // 仲間分けと探し方は Android と同じ約束。core の ConditionRegistry が
        // 束ね方も探し方も持っているので、ここは並べるだけ
        var query by remember(path) { mutableStateOf("") }
        var opened by remember(path) { mutableStateOf<ConditionGroup?>(null) }
        val searching = query.isNotBlank()
        val hits = if (searching) ConditionRegistry.search(query) else emptyList()
        val groups = remember { ConditionRegistry.byGroup() }

        fun pick(type: com.dopachiru.core.condition.ConditionType) {
            onChange(
                ConditionTree.addChild(
                    root,
                    path,
                    ConditionNode.Leaf(type.id, Params.defaultsOf(type.params)),
                )
            )
            pickerTarget = null
        }

        AlertDialog(
            onDismissRequest = { pickerTarget = null },
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
                                    ConditionRow(type, showGroup = true) { pick(type) }
                                }
                            }

                            opened == null -> {
                                items(groups, key = { it.first.name }) { (group, types) ->
                                    Card(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                        // 中身が1つしか無い棚は開かずにそのまま選ぶ。
                                        // 1つを見せるためだけにもう1回押させる意味が無い
                                        onClick = {
                                            val only = types.singleOrNull()
                                            if (only != null) pick(only) else opened = group
                                        },
                                    ) {
                                        Column(Modifier.padding(14.dp)) {
                                            Text(
                                                group.label,
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                            Text(
                                                group.summary,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            if (types.size > 1) {
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
                            }

                            else -> {
                                val types = groups.firstOrNull { it.first == opened }?.second.orEmpty()
                                items(types, key = { it.id }) { type ->
                                    ConditionRow(type, showGroup = false) { pick(type) }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickerTarget = null }) { Text("やめる") } },
            dismissButton = {
                if (opened != null && !searching) {
                    TextButton(onClick = { opened = null }) { Text("ほかの種類") }
                }
            },
        )
    }
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

/**
 * ルールを破った / 守ったときのポイントの増減。
 *
 * 封鎖(どこを・どれだけ閉めるか)はここには無い ── 押し切ることと破ることは
 * 同じ出来事なので、そのあとにもう一段閉める長さを別に決めさせても、
 * 二重に設定させるだけで分かりやすくならない。閉める長さそのものが要るなら、
 * 措置(「しばらく閉め出す」)の側で選ぶ。
 */
@Composable
fun ConsequenceEditor(
    consequence: Consequence,
    policy: PointPolicy,
    onChange: (Consequence) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
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

// ---- 重ねる覚え書きと、2組目以降 ---------------------------------------

/** 2組目以降で選べる主の措置。既定値で足せるものだけ。 */
private val MAIN_CHOICES = listOf(
    BlockAction.id to "閉じる",
    DelayAction.id to "少し待たせる",
    WarnAction.id to "警告だけ",
    RadioAction.id to "音だけにする",
    DeclareAction.id to "開く前に宣言させる",
)

/** 重ねられる覚え書き。画面を覆わないものだけ。 */
private val NOTE_CHOICES = listOf(
    TimerAction.id to "経過時間を出す",
    IntentionAction.id to "目的を書かせる",
)

/**
 * 重ねる覚え書き。主の措置と一緒に出しっぱなしになる。
 *
 * 覆うもの(閉じる・音だけ)と一緒に選んだときは、覆いが引っ込んだあとに残る
 * ── 覆いの裏に隠れて見えないので、同時には出さない。
 */
@Composable
internal fun StackedActionsRow(actions: List<ActionSpec>, onChange: (List<ActionSpec>) -> Unit) {
    Text("一緒に出しておく", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(4.dp))
    Text(
        "画面を覆わないものだけ重ねられます。覆うものと一緒に選ぶと、覆いが引っ込んだあとに残ります。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(6.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NOTE_CHOICES.forEach { (id, label) ->
            val on = actions.any { it.actionId == id }
            FilterChip(
                selected = on,
                onClick = {
                    onChange(
                        if (on) actions.filterNot { it.actionId == id }
                        else actions + ActionSpec(id, defaultParamsOf(id)),
                    )
                },
                label = { Text(label) },
            )
        }
    }
}

/** 2組目以降の一覧と、足す・消す。 */
@Composable
internal fun ExtraClausesSection(clauses: List<Clause>, onChange: (List<Clause>) -> Unit) {
    Text("ほかの組", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(4.dp))
    Text(
        "同じアプリに、別の条件で別のことをさせたいときに足します。" +
            "複数が同時に成立したら、いちばん強いものだけが出ます。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))

    clauses.forEach { clause ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("組 " + clause.id, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    TextButton(
                        // 番号は使い回さない。消した組の番号を再利用すると、
                        // 消す前に数えていた持ち時間を新しい組が引き継ぐ
                        onClick = { onChange(clauses.filterNot { it.id == clause.id }) },
                    ) { Text("消す", color = MaterialTheme.colorScheme.error) }
                }

                Spacer(Modifier.height(8.dp))
                Text("いつ", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
                ConditionTreeEditor(
                    root = clause.condition,
                    onChange = { next ->
                        onChange(clauses.map { if (it.id == clause.id) it.copy(condition = next) else it })
                    },
                )

                Spacer(Modifier.height(12.dp))
                Text("どうする", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MAIN_CHOICES.forEach { (id, label) ->
                        FilterChip(
                            selected = clause.mainAction?.actionId == id,
                            onClick = {
                                val next = clause.copy(
                                    actions = listOf(ActionSpec(id, defaultParamsOf(id))) + clause.overlays,
                                )
                                onChange(clauses.map { if (it.id == clause.id) next else it })
                            },
                            label = { Text(label) },
                        )
                    }
                }

                val main = clause.mainAction
                val type = main?.let { ActionRegistry[it.actionId] }
                if (main != null && type != null && type.params.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    ParamEditor(
                        specs = type.params,
                        params = main.params,
                        onChange = { next ->
                            val updated = clause.copy(
                                actions = listOf(main.copy(params = next)) + clause.overlays,
                            )
                            onChange(clauses.map { if (it.id == clause.id) updated else it })
                        },
                    )
                }

                Spacer(Modifier.height(12.dp))
                StackedActionsRow(clause.overlays) { notes ->
                    val head = clause.mainAction
                    val updated = clause.copy(actions = listOfNotNull(head) + notes)
                    onChange(clauses.map { if (it.id == clause.id) updated else it })
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }

    OutlinedButton(
        onClick = {
            val id = Clauses.nextId(clauses + Clause(Clauses.FIRST_ID))
            onChange(clauses + Clause(id, actions = listOf(ActionSpec(BlockAction.id, defaultParamsOf(BlockAction.id)))))
        },
    ) { Text("組を足す") }
}

private fun defaultParamsOf(actionId: String): Params =
    Params.defaultsOf(ActionRegistry[actionId]?.params ?: emptyList())
