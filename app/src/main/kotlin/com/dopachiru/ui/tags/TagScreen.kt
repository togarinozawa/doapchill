package com.dopachiru.ui.tags

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dopachiru.runtime.DopaRuntime
import com.dopachiru.ui.common.AppPickerList
import com.dopachiru.ui.rules.InstalledApps
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TagViewModel(app: Application) : AndroidViewModel(app) {

    /** タグ名 → そのタグが付いているパッケージ。 */
    val tagMembers: StateFlow<Map<String, Set<String>>> = DopaRuntime.rules.tagsByPackage
        .map { byPackage ->
            val result = mutableMapOf<String, MutableSet<String>>()
            byPackage.forEach { (pkg, tags) ->
                tags.forEach { tag -> result.getOrPut(tag) { mutableSetOf() }.add(pkg) }
            }
            result.toSortedMap().mapValues { it.value.toSet() }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun setMembers(tag: String, packages: Set<String>) {
        viewModelScope.launch {
            val current = tagMembers.value[tag] ?: emptySet()
            (packages - current).forEach { DopaRuntime.rules.addTag(it, tag) }
            (current - packages).forEach { DopaRuntime.rules.removeTag(it, tag) }
        }
    }

    fun deleteTag(tag: String) {
        viewModelScope.launch { DopaRuntime.rules.deleteTag(tag) }
    }
}

/**
 * アプリにタグを付けてまとめる画面。
 *
 * ルールの対象をタグで指定しておくと、あとから対象アプリを足すときに
 * ここに1つ足すだけで、そのタグを使っている全ルールに効く。
 */
@Composable
fun TagScreen(viewModel: TagViewModel = viewModel()) {
    val members by viewModel.tagMembers.collectAsState()
    val context = LocalContext.current

    var creatingTag by remember { mutableStateOf(false) }
    var editingTag by remember { mutableStateOf<String?>(null) }
    var draftPackages by remember { mutableStateOf(emptySet<String>()) }
    var confirmingDelete by remember { mutableStateOf<String?>(null) }

    Box(Modifier.fillMaxSize()) {
        if (members.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("まだタグがありません", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "「SNS」「動画」のようにアプリをまとめておくと、" +
                        "ルールの対象をタグで指定できます。あとからアプリを足すのが楽になります。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(members.keys.toList(), key = { it }) { tag ->
                    val packages = members[tag].orEmpty()
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            draftPackages = packages
                            editingTag = tag
                        },
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    "#$tag",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                TextButton(onClick = { confirmingDelete = tag }) { Text("削除") }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                if (packages.isEmpty()) {
                                    "アプリなし"
                                } else {
                                    packages.joinToString("、") { InstalledApps.labelOf(context, it) }
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = {
                draftPackages = emptySet()
                creatingTag = true
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = "タグを作る")
        }
    }

    if (creatingTag) {
        NewTagDialog(
            existing = members.keys,
            onCreate = { name ->
                creatingTag = false
                draftPackages = emptySet()
                editingTag = name
            },
            onDismiss = { creatingTag = false },
        )
    }

    editingTag?.let { tag ->
        TagMemberDialog(
            tag = tag,
            selected = draftPackages,
            onToggle = { pkg ->
                draftPackages =
                    if (pkg in draftPackages) draftPackages - pkg else draftPackages + pkg
            },
            onConfirm = {
                viewModel.setMembers(tag, draftPackages)
                editingTag = null
            },
            onDismiss = { editingTag = null },
        )
    }

    confirmingDelete?.let { tag ->
        AlertDialog(
            onDismissRequest = { confirmingDelete = null },
            title = { Text("#$tag を削除") },
            text = {
                Text(
                    "このタグをアプリから外します。タグを対象にしていたルールは、" +
                        "そのタグぶんの対象が無くなります。ルール自体は消えません。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteTag(tag)
                    confirmingDelete = null
                }) { Text("削除する") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = null }) { Text("やめる") }
            },
        )
    }
}

@Composable
private fun NewTagDialog(
    existing: Set<String>,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    val trimmed = name.trim()
    val duplicate = trimmed in existing
    val valid = trimmed.isNotEmpty() && !duplicate && !trimmed.contains(',')

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新しいタグ") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("タグ名") },
                    placeholder = { Text("SNS") },
                    singleLine = true,
                    isError = duplicate,
                    supportingText = if (duplicate) {
                        { Text("同じ名前のタグがあります") }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(trimmed) }, enabled = valid) { Text("次へ") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("やめる") } },
    )
}

@Composable
private fun TagMemberDialog(
    tag: String,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("#$tag に入れるアプリ") },
        text = {
            AppPickerList(
                selected = selected,
                onToggle = onToggle,
                maxHeight = 380.dp,
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("決める") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("やめる") } },
    )
}
