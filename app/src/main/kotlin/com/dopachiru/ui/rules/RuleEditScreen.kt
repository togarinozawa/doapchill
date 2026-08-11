package com.dopachiru.ui.rules

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.dopachiru.core.action.types.BlockAction
import com.dopachiru.core.condition.ConditionRegistry
import com.dopachiru.core.gate.ChangeKind
import com.dopachiru.core.model.ConditionNode
import com.dopachiru.core.model.Rule
import com.dopachiru.core.model.Target
import com.dopachiru.core.param.Params
import com.dopachiru.runtime.DopaRuntime
import com.dopachiru.ui.common.AppPickerList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ConditionDraft(val typeId: String, val params: Params)

data class RuleEditState(
    val id: Long = 0L,
    val name: String = "",
    val packages: Set<String> = emptySet(),
    val tags: Set<String> = emptySet(),
    /** 全アプリを対象にして、例外だけ挙げる(許可リスト型)。 */
    val matchAll: Boolean = false,
    val exceptPackages: Set<String> = emptySet(),
    val exceptTags: Set<String> = emptySet(),
    val conditions: List<ConditionDraft> = emptyList(),
    val actionId: String = BlockAction.id,
    val actionParams: Params = Params.defaultsOf(BlockAction.params),
    val availableTags: List<String> = emptyList(),
    /** 既存ルールの条件が入れ子や OR を含み、この画面では編集しきれない。 */
    val tooComplexToEdit: Boolean = false,
    val loaded: Boolean = false,
) {
    val canSave: Boolean
        get() = name.isNotBlank() &&
            (matchAll || packages.isNotEmpty() || tags.isNotEmpty()) &&
            !tooComplexToEdit
}

class RuleEditViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(RuleEditState())
    val state: StateFlow<RuleEditState> = _state.asStateFlow()

    /** 既存ルールの編集は起票扱いになるので、保存後に伝えるためのフラグ。 */
    var lastSaveWasQueued: Boolean = false
        private set

    fun load(ruleId: Long) {
        if (_state.value.loaded) return
        viewModelScope.launch {
            val tags = DopaRuntime.rules.tags.first()
            if (ruleId == 0L) {
                _state.update { it.copy(availableTags = tags, loaded = true) }
                return@launch
            }
            val rule = DopaRuntime.rules.getById(ruleId)
            if (rule == null) {
                _state.update { it.copy(availableTags = tags, loaded = true) }
                return@launch
            }
            val leaves = flattenLeaves(rule.condition)
            val simple = isSimpleTree(rule.condition)
            _state.value = RuleEditState(
                id = rule.id,
                name = rule.name,
                packages = rule.target.packages,
                tags = rule.target.tags,
                matchAll = rule.target.matchAll,
                exceptPackages = rule.target.exceptPackages,
                exceptTags = rule.target.exceptTags,
                conditions = leaves.map { ConditionDraft(it.typeId, it.params) },
                actionId = rule.actionId,
                actionParams = rule.actionParams,
                availableTags = tags,
                tooComplexToEdit = !simple,
                loaded = true,
            )
        }
    }

    fun setName(value: String) = _state.update { it.copy(name = value) }

    fun togglePackage(pkg: String) = _state.update {
        it.copy(packages = if (pkg in it.packages) it.packages - pkg else it.packages + pkg)
    }

    fun toggleTag(tag: String) = _state.update {
        it.copy(tags = if (tag in it.tags) it.tags - tag else it.tags + tag)
    }

    fun setMatchAll(value: Boolean) = _state.update { it.copy(matchAll = value) }

    fun toggleExceptPackage(pkg: String) = _state.update {
        it.copy(
            exceptPackages =
                if (pkg in it.exceptPackages) it.exceptPackages - pkg else it.exceptPackages + pkg
        )
    }

    fun toggleExceptTag(tag: String) = _state.update {
        it.copy(exceptTags = if (tag in it.exceptTags) it.exceptTags - tag else it.exceptTags + tag)
    }

    fun addCondition(typeId: String) {
        val type = ConditionRegistry[typeId] ?: return
        _state.update {
            it.copy(conditions = it.conditions + ConditionDraft(typeId, Params.defaultsOf(type.params)))
        }
    }

    fun removeCondition(index: Int) = _state.update {
        it.copy(conditions = it.conditions.filterIndexed { i, _ -> i != index })
    }

    fun updateCondition(index: Int, params: Params) = _state.update { state ->
        state.copy(
            conditions = state.conditions.mapIndexed { i, draft ->
                if (i == index) draft.copy(params = params) else draft
            }
        )
    }

    fun setAction(actionId: String) {
        val action = ActionRegistry[actionId] ?: return
        _state.update { it.copy(actionId = actionId, actionParams = Params.defaultsOf(action.params)) }
    }

    fun setActionParams(params: Params) = _state.update { it.copy(actionParams = params) }

    /**
     * 保存する。
     * 新規作成は即時反映、既存ルールの変更は変更リクエストとして起票される。
     */
    fun save(onDone: (queued: Boolean) -> Unit) {
        val current = _state.value
        if (!current.canSave) return
        viewModelScope.launch {
            val rule = Rule(
                id = current.id,
                name = current.name.trim(),
                enabled = true,
                target = Target(
                    packages = current.packages,
                    tags = current.tags,
                    matchAll = current.matchAll,
                    exceptPackages = current.exceptPackages,
                    exceptTags = current.exceptTags,
                ),
                condition = ConditionNode.AllOf(
                    current.conditions.map { ConditionNode.Leaf(it.typeId, it.params) }
                ),
                actionId = current.actionId,
                actionParams = current.actionParams,
            )
            val isNew = current.id == 0L
            val gates = if (isNew) emptyList() else DopaRuntime.settings.gates.first()
            DopaRuntime.changes.request(
                kind = if (isNew) ChangeKind.CREATE else ChangeKind.UPDATE,
                rule = rule,
                gates = gates,
            )
            lastSaveWasQueued = !isNew && gates.isNotEmpty()
            onDone(lastSaveWasQueued)
        }
    }

    fun delete(onDone: () -> Unit) {
        val current = _state.value
        if (current.id == 0L) return
        viewModelScope.launch {
            val rule = DopaRuntime.rules.getById(current.id) ?: return@launch
            val gates = DopaRuntime.settings.gates.first()
            DopaRuntime.changes.request(ChangeKind.DELETE, rule, gates)
            onDone()
        }
    }
}

@Composable
fun RuleEditScreen(
    ruleId: Long,
    onDone: () -> Unit,
    viewModel: RuleEditViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    var showAppPicker by remember { mutableStateOf(false) }
    var showExceptPicker by remember { mutableStateOf(false) }
    var showConditionPicker by remember { mutableStateOf(false) }
    var queuedNotice by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(ruleId) { viewModel.load(ruleId) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(
            if (ruleId == 0L) "新しいルール" else "ルールを編集",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(16.dp))

        if (state.tooComplexToEdit) {
            Card(Modifier.fillMaxWidth()) {
                Text(
                    "このルールは入れ子や OR を含んでいて、この画面では編集できません。" +
                        "条件エディタを載せるまでは、作り直すか無効化してください。",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
        }

        OutlinedTextField(
            value = state.name,
            onValueChange = viewModel::setName,
            label = { Text("ルール名") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        SectionHeader("対象アプリ")

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text("全アプリを対象にする", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "選んだアプリ「以外」を制限します。必要なものを下で除外してください。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = state.matchAll, onCheckedChange = viewModel::setMatchAll)
        }

        if (state.matchAll) {
            Spacer(Modifier.height(8.dp))
            Text(
                "電話・ホーム・設定・キーボード・ドパチル自身は、除外に入れなくても制限されません。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Text("除外するアプリ", style = MaterialTheme.typography.labelMedium)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                state.exceptPackages.forEach { pkg ->
                    AssistChip(
                        onClick = { viewModel.toggleExceptPackage(pkg) },
                        label = { Text(InstalledApps.labelOf(context, pkg)) },
                        trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "外す") },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { showExceptPicker = true }) { Text("除外するアプリを選ぶ") }

            if (state.availableTags.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("タグごと除外", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    state.availableTags.forEach { tag ->
                        FilterChip(
                            selected = tag in state.exceptTags,
                            onClick = { viewModel.toggleExceptTag(tag) },
                            label = { Text("#$tag") },
                        )
                    }
                }
            }
        } else {
            Spacer(Modifier.height(12.dp))
            if (state.packages.isEmpty() && state.tags.isEmpty()) {
                Text(
                    "まだ選ばれていません",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                state.packages.forEach { pkg ->
                    AssistChip(
                        onClick = { viewModel.togglePackage(pkg) },
                        label = { Text(InstalledApps.labelOf(context, pkg)) },
                        trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "外す") },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { showAppPicker = true }) { Text("アプリを選ぶ") }

            if (state.availableTags.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("タグで指定", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    state.availableTags.forEach { tag ->
                        FilterChip(
                            selected = tag in state.tags,
                            onClick = { viewModel.toggleTag(tag) },
                            label = { Text("#$tag") },
                        )
                    }
                }
            }
        }

        SectionHeader("条件(すべて満たしたとき)")
        if (state.conditions.isEmpty()) {
            Text(
                "条件なし = 対象アプリを常に制限します(完全封印)。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.conditions.forEachIndexed { index, draft ->
            val type = ConditionRegistry[draft.typeId]
            Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            type?.displayName ?: draft.typeId,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        TextButton(onClick = { viewModel.removeCondition(index) }) { Text("削除") }
                    }
                    if (type != null) {
                        Spacer(Modifier.height(8.dp))
                        ParamEditor(
                            specs = type.params,
                            params = draft.params,
                            onChange = { viewModel.updateCondition(index, it) },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { showConditionPicker = true }) { Text("条件を足す") }

        SectionHeader("どうする")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionRegistry.all().forEach { action ->
                FilterChip(
                    selected = state.actionId == action.id,
                    onClick = { viewModel.setAction(action.id) },
                    label = { Text(action.displayName) },
                )
            }
        }
        ActionRegistry[state.actionId]?.let { action ->
            Spacer(Modifier.height(8.dp))
            Text(
                action.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            ParamEditor(
                specs = action.params,
                params = state.actionParams,
                onChange = viewModel::setActionParams,
            )
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        Button(
            onClick = { viewModel.save { queued -> if (queued) queuedNotice = true else onDone() } },
            enabled = state.canSave,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (ruleId == 0L) "作成する" else "変更を申請する")
        }

        if (ruleId != 0L) {
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = { viewModel.delete { onDone() } },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("このルールを削除する", color = MaterialTheme.colorScheme.error)
            }
        }
        Spacer(Modifier.height(32.dp))
    }

    if (showAppPicker) {
        AppPickerDialog(
            selected = state.packages,
            onToggle = viewModel::togglePackage,
            onDismiss = { showAppPicker = false },
        )
    }

    if (showExceptPicker) {
        AppPickerDialog(
            title = "除外するアプリ",
            selected = state.exceptPackages,
            onToggle = viewModel::toggleExceptPackage,
            onDismiss = { showExceptPicker = false },
        )
    }

    if (showConditionPicker) {
        ConditionPickerDialog(
            onPick = {
                viewModel.addCondition(it)
                showConditionPicker = false
            },
            onDismiss = { showConditionPicker = false },
        )
    }

    if (queuedNotice) {
        AlertDialog(
            onDismissRequest = { queuedNotice = false; onDone() },
            title = { Text("変更を申請しました") },
            text = {
                Text("この変更はすぐには反映されません。「変更」タブでゲートを通すと適用されます。")
            },
            confirmButton = {
                TextButton(onClick = { queuedNotice = false; onDone() }) { Text("わかった") }
            },
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Spacer(Modifier.height(24.dp))
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))
}

@Composable
fun AppPickerDialog(
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit,
    title: String = "対象アプリ",
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { AppPickerList(selected = selected, onToggle = onToggle) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
    )
}

@Composable
private fun ConditionPickerDialog(
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("条件を選ぶ") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(ConditionRegistry.all(), key = { it.id }) { type ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        onClick = { onPick(type.id) },
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(type.displayName, style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(2.dp))
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
        confirmButton = { TextButton(onClick = onDismiss) { Text("やめる") } },
    )
}
