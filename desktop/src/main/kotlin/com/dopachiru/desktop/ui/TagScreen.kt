package com.dopachiru.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
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
import com.dopachiru.desktop.DesktopRuntime
import com.dopachiru.desktop.platform.RunningApps

/**
 * アプリにタグを付ける。Windows 版。
 *
 * ## なぜ要るか
 *
 * タグは「まとめて狙う」ための唯一の手段で、ルールの対象にも集中モードの範囲にも
 * 罰の範囲にも出てくる。なのに Windows には**付ける手段が無く**、Android から
 * 同期で降ってくるのを待つしかなかった ── Windows しか持っていないアプリ
 * (ゲームなど)は、どうやってもタグに入れられなかった。
 *
 * ## 一覧に出すもの
 *
 * Windows には「インストール済みアプリ」の統一された一覧が無いので、
 * **いま窓を持って動いているもの**と、**すでにタグが付いているもの**を並べる。
 * 閉じているアプリは実行ファイル名を直接足す ── 止めたいゲームを起動しなくても
 * 設定できるように。
 */
@Composable
fun TagScreen() {
    val ruleFile by DesktopRuntime.ruleFile.collectAsState()
    val running = remember { RunningApps.visible() }
    var editing by remember { mutableStateOf<String?>(null) }
    var manual by remember { mutableStateOf("") }

    val tagged = ruleFile.tags.filterValues { it.isNotEmpty() }
    // 動いているもの + タグが付いているもの。重複は落とす
    val rows = remember(running, tagged) {
        (running.map { it.processName to it.label } +
            tagged.keys.map { it to it })
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("タグ", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            "アプリをまとめて狙うための名札です。ルールの対象・集中モードの範囲・罰の範囲で使えます。" +
                "あとからアプリを足しても、タグに入れれば同じルールがかかります。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val known = ruleFile.tags.values.flatten().distinct().sorted()
        if (known.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("いま在るタグ", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                known.forEach { tag ->
                    val count = ruleFile.tags.values.count { tag in it }
                    FilterChip(
                        selected = false,
                        onClick = { },
                        label = { Text("#$tag ($count)") },
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Text("閉じているアプリを足す", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = manual,
                onValueChange = { manual = it },
                label = { Text("実行ファイル名") },
                singleLine = true,
                supportingText = {
                    Text("例: aces.exe(War Thunder)", style = MaterialTheme.typography.bodySmall)
                },
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = { editing = manual.trim(); manual = "" },
                enabled = manual.isNotBlank(),
            ) { Text("タグを付ける") }
        }

        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))

        LazyColumn(Modifier.fillMaxWidth()) {
            items(rows, key = { it.first }) { (process, label) ->
                val tags = ruleFile.tags[process] ?: emptySet()
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(label, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                process,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (tags.isNotEmpty()) {
                                Spacer(Modifier.height(6.dp))
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    tags.sorted().forEach { tag ->
                                        FilterChip(
                                            selected = true,
                                            onClick = { DesktopRuntime.toggleTag(process, tag) },
                                            label = { Text("#$tag") },
                                        )
                                    }
                                }
                            }
                        }
                        OutlinedButton(onClick = { editing = process }) { Text("付け外し") }
                    }
                }
            }
        }
    }

    editing?.let { process ->
        TagPickerDialog(
            process = process,
            current = ruleFile.tags[process] ?: emptySet(),
            known = ruleFile.tags.values.flatten().distinct().sorted(),
            onToggle = { tag -> DesktopRuntime.toggleTag(process, tag) },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun TagPickerDialog(
    process: String,
    current: Set<String>,
    known: List<String>,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var fresh by remember { mutableStateOf("") }
    val trimmed = fresh.trim()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(process) },
        text = {
            Column(Modifier.heightIn(max = 360.dp)) {
                if (known.isEmpty()) {
                    Text(
                        "まだタグがありません。下で名前を決めると、最初のタグができます。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text("押すと付け外しできます", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        known.forEach { tag ->
                            FilterChip(
                                selected = tag in current,
                                onClick = { onToggle(tag) },
                                label = { Text("#$tag") },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                Text("新しいタグ", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = fresh,
                        onValueChange = { fresh = it },
                        label = { Text("名前") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { onToggle(trimmed); fresh = "" },
                        // 同じ名前を二度作らない。付け外しは上の一覧でできる
                        enabled = trimmed.isNotBlank() && trimmed !in known,
                    ) { Text("作って付ける") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
    )
}
