package com.dopachiru.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
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
import com.dopachiru.core.model.SiteCatalog
import com.dopachiru.core.model.SitePattern
import com.dopachiru.core.model.Target
import com.dopachiru.desktop.DesktopRuntime

/**
 * ルールが何を狙うかを決める。Windows 版。
 *
 * ## なぜ足したか
 *
 * これまで Windows のルール編集は**条件と罰だけ**で、対象は雛形から入ったものを
 * そのまま使うしかなかった。つまり「この exe を止める」が書けない ──
 * 雛形に載っていないゲームや、手元にしか無いアプリは**狙いようが無かった**。
 * ルールの意味の半分は「何を」なので、ここが無いと道具として成り立たない。
 *
 * ## 入口を3つに割る
 *
 * Android 版と同じく、アプリ / サイト / 全部 の3つは**排他ではなく入口**で、
 * 出す欄を絞るためだけに使う。全部の欄を同時に出すと、exe を1つ止めたいだけの人が
 * URL 欄と除外欄を読まされる。
 */
@Composable
fun TargetEditor(target: Target, onChange: (Target) -> Unit) {
    var mode by remember {
        mutableStateOf(
            when {
                target.matchAll -> TargetMode.ALL
                target.sites.isNotEmpty() && target.packages.isEmpty() -> TargetMode.SITES
                else -> TargetMode.APPS
            }
        )
    }
    var showPicker by remember { mutableStateOf(false) }
    var showExceptPicker by remember { mutableStateOf(false) }

    val ruleFile by DesktopRuntime.ruleFile.collectAsState()
    val knownTags = remember(ruleFile) { ruleFile.tags.values.flatten().distinct().sorted() }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TargetMode.entries.forEach { option ->
            FilterChip(
                selected = mode == option,
                onClick = {
                    mode = option
                    // 入口を変えても入力は消さない。行き来しただけで消えると、
                    // 「戻ったら選び直し」になって触るのが怖くなる
                    onChange(target.copy(matchAll = option == TargetMode.ALL))
                },
                label = { Text(option.label) },
            )
        }
    }
    Spacer(Modifier.height(4.dp))
    Text(
        mode.help,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))

    when (mode) {
        TargetMode.APPS -> {
            Text("止めるアプリ", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            ProcessList(
                processes = target.packages,
                empty = "まだ選んでいません",
                onRemove = { onChange(target.copy(packages = target.packages - it)) },
            )
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = { showPicker = true }) { Text("アプリを選ぶ") }
        }

        TargetMode.SITES -> SitesEditor(
            sites = target.sites,
            onChange = { onChange(target.copy(sites = it)) },
        )

        TargetMode.ALL -> {
            Text(
                "エクスプローラ・タスクマネージャ・ドパチル自身は、残す指定に入れなくても止まりません。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Text("残すアプリ", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            ProcessList(
                processes = target.exceptPackages,
                empty = "まだありません",
                onRemove = { onChange(target.copy(exceptPackages = target.exceptPackages - it)) },
            )
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = { showExceptPicker = true }) { Text("残すアプリを選ぶ") }
        }
    }

    // タグは畳まない。exe を1つずつ選ぶのと同じくらい普通の指し方なのに、
    // 隠すと在ること自体に気づけない
    Spacer(Modifier.height(16.dp))
    val tagTitle = if (mode == TargetMode.ALL) "タグごと残す" else "タグで指定"
    Text(tagTitle, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
    Text(
        if (mode == TargetMode.ALL) {
            "このタグを付けたアプリは、全部止めるなかでも開いたままにします。"
        } else {
            "タグを付けたアプリをまとめて指せます。あとでアプリを足しても、タグに入れればこのルールがかかります。"
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(6.dp))
    if (knownTags.isEmpty()) {
        Text(
            "タグがまだありません。「タグ」タブでアプリにタグを付けると、ここに出ます。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        val chosen = if (mode == TargetMode.ALL) target.exceptTags else target.tags
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            knownTags.forEach { tag ->
                FilterChip(
                    selected = tag in chosen,
                    onClick = {
                        val next = if (tag in chosen) chosen - tag else chosen + tag
                        onChange(
                            if (mode == TargetMode.ALL) {
                                target.copy(exceptTags = next)
                            } else {
                                target.copy(tags = next)
                            }
                        )
                    },
                    label = { Text("#$tag") },
                )
            }
        }
    }

    if (target.isEmpty) {
        Spacer(Modifier.height(10.dp))
        Text(
            "対象が空です。このままだと、どのアプリにも当たりません。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }

    if (showPicker) {
        AppPickerDialog(
            selected = target.packages,
            onToggle = { process ->
                val next = if (process in target.packages) {
                    target.packages - process
                } else {
                    target.packages + process
                }
                onChange(target.copy(packages = next))
            },
            onDismiss = { showPicker = false },
        )
    }
    if (showExceptPicker) {
        AppPickerDialog(
            selected = target.exceptPackages,
            onToggle = { process ->
                val next = if (process in target.exceptPackages) {
                    target.exceptPackages - process
                } else {
                    target.exceptPackages + process
                }
                onChange(target.copy(exceptPackages = next))
            },
            onDismiss = { showExceptPicker = false },
        )
    }
}

/** 対象の指し方。3つは排他ではなく、出す欄を絞るための入口。 */
enum class TargetMode(val label: String, val help: String) {
    APPS("アプリ", "動いている実行ファイルから選ぶ"),
    SITES("サイト", "ブラウザで開くページを URL で指す(拡張が要ります)"),
    ALL("全部", "選んだもの以外の全アプリを止める"),
}

@Composable
private fun ProcessList(processes: Set<String>, empty: String, onRemove: (String) -> Unit) {
    if (processes.isEmpty()) {
        Text(empty, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        processes.forEach { process ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(process, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { onRemove(process) }) { Text("外す") }
            }
        }
    }
}

/** URL の指定。束から選ぶのと、手で足すのと両方。 */
@Composable
private fun SitesEditor(sites: Set<String>, onChange: (Set<String>) -> Unit) {
    var text by remember { mutableStateOf("") }
    val normalized = SitePattern.normalize(text)
    val ok = text.isNotBlank() && SitePattern.isValid(normalized)

    Text("よく挙がるところ", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(6.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SiteCatalog.all.forEach { group ->
            val all = group.patterns.toSet()
            val on = sites.containsAll(all)
            FilterChip(
                selected = on,
                onClick = { onChange(if (on) sites - all else sites + all) },
                label = { Text(group.label) },
            )
        }
    }

    Spacer(Modifier.height(12.dp))
    Text("自分で足す", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(4.dp))
    Row(verticalAlignment = Alignment.Top) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("URL") },
            singleLine = true,
            isError = text.isNotBlank() && !ok,
            supportingText = {
                Text(
                    when {
                        text.isBlank() -> "ホスト、または ホスト/パス の先頭だけ"
                        !ok -> "この書き方では当たりません"
                        else -> "$normalized として追加します"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = { onChange(sites + normalized); text = "" },
            enabled = ok,
        ) { Text("追加") }
    }

    Spacer(Modifier.height(8.dp))
    if (sites.isEmpty()) {
        Text("まだありません", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            sites.forEach { site ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(site, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { onChange(sites - site) }) { Text("外す") }
                }
            }
        }
    }
}
