package com.dopachiru.ui.rules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dopachiru.core.action.ActionRegistry
import com.dopachiru.core.action.types.BlockAction
import com.dopachiru.core.action.types.DeclareAction
import com.dopachiru.core.action.types.DelayAction
import com.dopachiru.core.action.types.IntentionAction
import com.dopachiru.core.action.types.RadioAction
import com.dopachiru.core.action.types.TimerAction
import com.dopachiru.core.action.types.WarnAction
import com.dopachiru.core.model.ActionSpec
import com.dopachiru.core.model.Clause
import com.dopachiru.core.model.Clauses
import com.dopachiru.core.param.Params

/**
 * 2組目以降の「条件 → こうする」と、重ねる覚え書き。
 *
 * ## なぜ組を足せるのか
 *
 * 同じアプリに対して、条件ごとに別のことをしたいから。
 *
 *     [22時以降]                 → 2分待たせる
 *     [前回から3時間あいていない] → 閉じる
 *
 * ルールを2本に分けても動きますが、一覧に同じアプリが並んで何が効いているのか
 * 読めなくなるうえ、**持ち時間の財布が組ごとに分けられません**
 * (「午前は30分・夜は60分」が書けない)。
 *
 * ## 1組目はここに出ません
 *
 * 1組目は「いつ」と「どうする」の段そのものです。ここに並ぶのは2組目から。
 * 見た目が非対称なのは承知のうえで、**1組しか要らない人に組の話をさせない**
 * ほうを取っています。
 */

/** 2組目以降で選べる主の措置。既定値で足せるものだけを並べる。 */
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
 * 重ねる覚え書きの選び手。主の措置と一緒に出しっぱなしになる。
 *
 * 覆うもの(閉じる・音だけ)と一緒に選んだときは、**覆いが引っ込んだあと**に
 * 残ります ── 覆いの裏に隠れて見えないので、同時には出しません。
 */
@Composable
fun StackedActionsRow(
    actions: List<ActionSpec>,
    onChange: (List<ActionSpec>) -> Unit,
) {
    Text("一緒に出しておく", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(2.dp))
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
                        if (on) {
                            actions.filterNot { it.actionId == id }
                        } else {
                            actions + ActionSpec(id, defaultsOf(id))
                        },
                    )
                },
                label = { Text(label) },
            )
        }
    }
}

/** 2組目以降の一覧と、足す・消す。 */
@Composable
fun ExtraClausesSection(
    clauses: List<Clause>,
    onChange: (List<Clause>) -> Unit,
) {
    Text("ほかの組", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(4.dp))
    Text(
        "同じアプリに、別の条件で別のことをさせたいときに足します。" +
            "複数が同時に成立したら、いちばん強いものだけが出ます。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))

    clauses.forEach { clause ->
        ClauseCard(
            clause = clause,
            onChange = { next -> onChange(clauses.map { if (it.id == clause.id) next else it }) },
            // 番号は使い回さない。消した組の番号を再利用すると、
            // 消す前に数えていた持ち時間を新しい組が引き継ぐ
            onRemove = { onChange(clauses.filterNot { it.id == clause.id }) },
        )
        Spacer(Modifier.height(12.dp))
    }

    OutlinedButton(
        onClick = {
            val id = Clauses.nextId(clauses + Clause(Clauses.FIRST_ID))
            onChange(
                clauses + Clause(
                    id = id,
                    actions = listOf(ActionSpec(BlockAction.id, defaultsOf(BlockAction.id))),
                ),
            )
        },
    ) { Text("組を足す") }
}

@Composable
private fun ClauseCard(
    clause: Clause,
    onChange: (Clause) -> Unit,
    onRemove: () -> Unit,
) {
    val main = clause.mainAction
    val spec = main?.let { ActionRegistry[it.actionId] }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "組 " + clause.id,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRemove) {
                    Text("消す", color = MaterialTheme.colorScheme.error)
                }
            }

            Spacer(Modifier.height(8.dp))
            Text("いつ", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
            ConditionTreeEditor(
                root = clause.condition,
                onChange = { onChange(clause.copy(condition = it)) },
            )

            Spacer(Modifier.height(12.dp))
            Text("どうする", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MAIN_CHOICES.forEach { (id, label) ->
                    FilterChip(
                        selected = main?.actionId == id,
                        onClick = { onChange(clause.withMain(id)) },
                        label = { Text(label) },
                    )
                }
            }

            if (main != null && spec != null && spec.params.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                ParamEditor(
                    specs = spec.params,
                    params = main.params,
                    onChange = { next -> onChange(clause.withMainParams(next)) },
                )
            }

            Spacer(Modifier.height(12.dp))
            StackedActionsRow(
                actions = clause.overlays,
                onChange = { notes ->
                    val head = clause.mainAction
                    onChange(clause.copy(actions = listOfNotNull(head) + notes))
                },
            )
        }
    }
}

private fun Clause.withMain(actionId: String): Clause {
    val notes = overlays
    return copy(actions = listOf(ActionSpec(actionId, defaultsOf(actionId))) + notes)
}

private fun Clause.withMainParams(params: Params): Clause {
    val head = mainAction ?: return this
    return copy(actions = listOf(head.copy(params = params)) + overlays)
}

private fun defaultsOf(actionId: String): Params =
    Params.defaultsOf(ActionRegistry[actionId]?.params ?: emptyList())
