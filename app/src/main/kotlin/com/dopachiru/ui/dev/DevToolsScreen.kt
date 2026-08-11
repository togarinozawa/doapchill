package com.dopachiru.ui.dev

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dopachiru.runtime.DopaRuntime
import com.dopachiru.ui.rules.InstalledApps
import java.time.format.DateTimeFormatter

/**
 * 開発用。ふだんは隠してある。
 *
 * ルールを作るたびに夜まで待ったり、30分アプリを触り続けたりしていては
 * 確かめようがないので、条件が成立する状況を人工的に作れるようにしてある。
 *
 * ここでいじった状態は**アプリを再起動すると消える**(保存していない)。
 * 戻し忘れて本番の判定が狂ったままになるのを防ぐため。
 */
@Composable
fun DevToolsScreen() {
    val context = LocalContext.current
    var offset by remember { mutableIntStateOf(DopaRuntime.devClockOffsetMinutes) }
    var target by remember { mutableStateOf(DopaRuntime.currentForegroundPackage ?: "") }
    var verdicts by remember { mutableStateOf(emptyList<DopaRuntime.RuleVerdict>()) }
    var studyNote by remember { mutableStateOf("") }

    fun shift(minutes: Int) {
        offset += minutes
        DopaRuntime.devClockOffsetMinutes = offset
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
    ) {
        Text("開発ツール", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            "ここでいじった状態はアプリを再起動すると元に戻ります。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ------------------------------------------------------------------
        Spacer(Modifier.height(20.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("時計をずらす", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "「22時以降は封印」を昼間に試すため。判定に使う時刻だけが動きます。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    DopaRuntime.now().format(DateTimeFormatter.ofPattern("M/d(E) HH:mm")),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    if (offset == 0) "ずらしていません" else "${if (offset > 0) "+" else ""}$offset 分",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(-60, -10, 10, 60, 240).forEach { minutes ->
                        OutlinedButton(onClick = { shift(minutes) }) {
                            Text(if (minutes > 0) "+${minutes}分" else "${minutes}分")
                        }
                    }
                    TextButton(onClick = { offset = 0; DopaRuntime.devClockOffsetMinutes = 0 }) {
                        Text("戻す")
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "使用時間の条件は記録の実時刻で数えるので、大きくずらすと噛み合いません。" +
                        "そちらは下の「学習予定」と同じ要領で、実際に触って試してください。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ------------------------------------------------------------------
        Spacer(Modifier.height(16.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("学習予定をでっちあげる", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "スキマスを入れずに、学習中・助走枠・中断を試せます。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = {
                        DopaRuntime.devFakeStudyWindow(startsInMinutes = 0, lengthMinutes = 30)
                        studyNote = "いまから30分を学習中にしました"
                    }) { Text("いますぐ学習中に") }

                    OutlinedButton(onClick = {
                        DopaRuntime.devFakeStudyWindow(startsInMinutes = 10, lengthMinutes = 30)
                        studyNote = "10分後開始 = いまは助走枠のはず"
                    }) { Text("10分後に予定を置く") }

                    TextButton(onClick = {
                        DopaRuntime.devClearStudyWindows()
                        studyNote = "予定を全部消しました"
                    }) { Text("消す") }
                }
                if (studyNote.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        studyNote,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.height(8.dp))
                val study = DopaRuntime.studyWindows.state()
                Text(
                    "いま: " + when {
                        study.inSession -> "学習中(${study.currentTitle ?: "名前なし"})"
                        study.inPrep -> "助走枠"
                        else -> "予定なし"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        // ------------------------------------------------------------------
        Spacer(Modifier.height(16.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("いま何が起きるか", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "ブロックが出ない・出すぎるときに、どのルールがどこで落ちているかを見ます。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "対象: " + if (target.isBlank()) "—" else InstalledApps.labelOf(context, target),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    target.ifBlank { "直前に前面にあったアプリを見ます" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        target = DopaRuntime.currentForegroundPackage ?: target
                        verdicts = DopaRuntime.explain(target)
                    }) { Text("いま調べる") }
                }

                if (verdicts.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    verdicts.forEach { verdict ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(verdict.ruleName, style = MaterialTheme.typography.bodySmall)
                            Text(
                                verdict.reason,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (verdict.fires) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                    if (verdicts.none { it.fires }) {
                        Text(
                            "成立しているルールはありません = 何も起きません。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}
