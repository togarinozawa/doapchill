package com.dopachiru.ui.rules

import android.app.Application
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import com.dopachiru.core.gate.ChangeKind
import com.dopachiru.core.model.ConditionNode
import com.dopachiru.core.model.ConditionTree
import com.dopachiru.core.model.Consequence
import com.dopachiru.core.model.Rule
import com.dopachiru.core.model.RulePhrase
import com.dopachiru.core.model.SiteCatalog
import com.dopachiru.core.model.SitePattern
import com.dopachiru.core.model.Target
import com.dopachiru.core.param.Params
import com.dopachiru.core.points.PointPolicy
import com.dopachiru.runtime.DopaRuntime
import com.dopachiru.ui.common.AppPickerList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 対象の指し方。3つは**排他ではなく入口**で、
 * 選んだあとに出す欄を絞るためだけに使う。
 *
 * 全部の欄を同時に出すと、アプリを止めたいだけの人が
 * URL 欄とタグ欄と除外欄を読まされる。
 */
enum class TargetMode(val label: String, val help: String) {
    APPS("アプリ", "端末に入っているアプリから選ぶ"),
    SITES("サイト", "ブラウザで開くページを URL で指す"),
    ALL("全部", "選んだもの以外の全アプリを止める"),
}

data class RuleEditState(
    val id: Long = 0L,
    val name: String = "",
    val packages: Set<String> = emptySet(),
    val tags: Set<String> = emptySet(),
    val sites: Set<String> = emptySet(),
    /** 全アプリを対象にして、例外だけ挙げる(許可リスト型)。 */
    val matchAll: Boolean = false,
    val exceptPackages: Set<String> = emptySet(),
    val exceptTags: Set<String> = emptySet(),
    val exceptSites: Set<String> = emptySet(),
    /** 条件の木。AND / OR / NOT の入れ子をそのまま保持する。 */
    val condition: ConditionNode = ConditionTree.EMPTY,
    val actionId: String = BlockAction.id,
    val actionParams: Params = Params.defaultsOf(BlockAction.params),
    val consequence: Consequence = Consequence.NONE,
    val pointPolicy: PointPolicy = PointPolicy.DEFAULT,
    val availableTags: List<String> = emptyList(),
    val mode: TargetMode = TargetMode.APPS,
    /** いま何番目の段にいるか。0=何を 1=いつ 2=どうする */
    val step: Int = 0,
    val loaded: Boolean = false,
) {
    val target: Target
        get() = Target(
            packages = packages,
            tags = tags,
            sites = sites,
            matchAll = matchAll,
            exceptPackages = exceptPackages,
            exceptTags = exceptTags,
            exceptSites = exceptSites,
        )

    /** 対象が決まっているか。ここが空のルールは何にも当たらない。 */
    val hasTarget: Boolean get() = !target.isEmpty

    /**
     * 保存できるか。
     *
     * **名前は要求しない。** 名前を必須にすると、中身より先に名前を
     * 考えさせることになって手が止まる。空なら中身から作る。
     */
    val canSave: Boolean get() = hasTarget
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
            val policy = DopaRuntime.settings.pointPolicy.first()
            val rule = if (ruleId == 0L) null else DopaRuntime.rules.getById(ruleId)
            if (rule == null) {
                _state.update { it.copy(availableTags = tags, pointPolicy = policy, loaded = true) }
                return@launch
            }
            _state.value = RuleEditState(
                id = rule.id,
                name = rule.name,
                packages = rule.target.packages,
                tags = rule.target.tags,
                sites = rule.target.sites,
                matchAll = rule.target.matchAll,
                exceptPackages = rule.target.exceptPackages,
                exceptTags = rule.target.exceptTags,
                exceptSites = rule.target.exceptSites,
                condition = rule.condition,
                actionId = rule.actionId,
                actionParams = rule.actionParams,
                consequence = rule.consequence,
                pointPolicy = policy,
                availableTags = tags,
                mode = when {
                    rule.target.matchAll -> TargetMode.ALL
                    rule.target.sites.isNotEmpty() && rule.target.packages.isEmpty() -> TargetMode.SITES
                    else -> TargetMode.APPS
                },
                loaded = true,
            )
        }
    }

    fun setName(value: String) = _state.update { it.copy(name = value) }

    fun setStep(step: Int) = _state.update { it.copy(step = step.coerceIn(0, LAST_STEP)) }

    fun setMode(mode: TargetMode) = _state.update {
        // 入口を変えても入力は消さない。行き来しただけで消えると、
        // 「戻ったら選び直し」になって触るのが怖くなる
        it.copy(mode = mode, matchAll = mode == TargetMode.ALL)
    }

    fun togglePackage(pkg: String) = _state.update {
        it.copy(packages = if (pkg in it.packages) it.packages - pkg else it.packages + pkg)
    }

    fun toggleTag(tag: String) = _state.update {
        it.copy(tags = if (tag in it.tags) it.tags - tag else it.tags + tag)
    }

    /** URL を1つ足す。書き方が違えば何もしない(呼び出し側が先に弾く)。 */
    fun addSite(raw: String) = _state.update {
        val normalized = SitePattern.normalize(raw)
        if (!SitePattern.isValid(normalized)) it else it.copy(sites = it.sites + normalized)
    }

    fun removeSite(site: String) = _state.update { it.copy(sites = it.sites - site) }

    fun toggleSiteGroup(groupId: String) = _state.update { state ->
        val group = SiteCatalog.byId(groupId) ?: return@update state
        val all = group.patterns.toSet()
        val on = state.sites.containsAll(all)
        state.copy(sites = if (on) state.sites - all else state.sites + all)
    }

    fun toggleExceptPackage(pkg: String) = _state.update {
        it.copy(
            exceptPackages =
                if (pkg in it.exceptPackages) it.exceptPackages - pkg else it.exceptPackages + pkg
        )
    }

    fun toggleExceptTag(tag: String) = _state.update {
        it.copy(exceptTags = if (tag in it.exceptTags) it.exceptTags - tag else it.exceptTags + tag)
    }

    fun addExceptSite(raw: String) = _state.update {
        val normalized = SitePattern.normalize(raw)
        if (!SitePattern.isValid(normalized)) it else it.copy(exceptSites = it.exceptSites + normalized)
    }

    fun removeExceptSite(site: String) =
        _state.update { it.copy(exceptSites = it.exceptSites - site) }

    fun setCondition(condition: ConditionNode) = _state.update { it.copy(condition = condition) }

    fun setConsequence(consequence: Consequence) =
        _state.update { it.copy(consequence = consequence) }

    fun setAction(actionId: String) {
        val action = ActionRegistry[actionId] ?: return
        _state.update { it.copy(actionId = actionId, actionParams = Params.defaultsOf(action.params)) }
    }

    fun setActionParams(params: Params) = _state.update { it.copy(actionParams = params) }

    /**
     * 保存する。
     * 新規作成は即時反映、既存ルールの変更は変更リクエストとして起票される。
     */
    fun save(labelOf: (String) -> String, onDone: (queued: Boolean) -> Unit) {
        val current = _state.value
        if (!current.canSave) return
        viewModelScope.launch {
            val name = current.name.trim().ifBlank {
                RulePhrase.suggestName(current.target, current.condition, labelOf)
            }.ifBlank { "名前のないルール" }

            val rule = Rule(
                id = current.id,
                name = name,
                enabled = true,
                target = current.target,
                condition = current.condition,
                actionId = current.actionId,
                actionParams = current.actionParams,
                consequence = current.consequence,
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

    companion object {
        const val LAST_STEP = 2
    }
}

/**
 * ルールを作る画面。
 *
 * ## なぜ段に分けたか
 *
 * 前は「名前・対象・条件・措置・罰」を1本の縦スクロールに並べていた。
 * 全部が同時に見えるのは一見親切だが、**作っている最中はどれも半端なので、
 * 画面のどこを見ても未完成の欄しか無い**状態になる。
 * 一番よく効く対処は、一度に決めることを減らすこと(progressive disclosure)。
 *
 * ここでは3つの段に割って、上に**組み上がった結果を1文で**出している。
 * 段を進むことより、その文が読んで正しいことのほうが大事なので、
 * 段の見出しはいつでも押して行き来できる。
 *
 * 詳しい欄(タグ・除外・罰)は畳んである。既定のままで困らないものを
 * 開いた状態で見せると、決めなくてよいことを決めさせることになる。
 */
@Composable
fun RuleEditScreen(
    ruleId: Long,
    onDone: () -> Unit,
    viewModel: RuleEditViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    var queuedNotice by remember { mutableStateOf(false) }
    val labelOf: (String) -> String = { InstalledApps.labelOf(context, it) }

    androidx.compose.runtime.LaunchedEffect(ruleId) { viewModel.load(ruleId) }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                if (ruleId == 0L) "新しいルール" else "ルールを編集",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(10.dp))
            RuleSentence(state, labelOf)
            Spacer(Modifier.height(12.dp))
            StepBar(step = state.step, onStep = viewModel::setStep, canLeave = state.hasTarget)
        }
        HorizontalDivider()

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            when (state.step) {
                0 -> TargetStep(state, viewModel, labelOf)
                1 -> ConditionStep(state, viewModel)
                else -> ActionStep(state, viewModel, ruleId, labelOf)
            }
            Spacer(Modifier.height(24.dp))
        }

        HorizontalDivider()
        BottomBar(
            state = state,
            isNew = ruleId == 0L,
            onBack = { viewModel.setStep(state.step - 1) },
            onNext = { viewModel.setStep(state.step + 1) },
            onSave = {
                viewModel.save(labelOf) { queued -> if (queued) queuedNotice = true else onDone() }
            },
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

/** いま何を作っているのかを1文で。編集中ずっと画面の上に残る。 */
@Composable
private fun RuleSentence(state: RuleEditState, labelOf: (String) -> String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            RulePhrase.of(state.target, state.condition, state.actionId, state.actionParams, labelOf),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun StepBar(step: Int, onStep: (Int) -> Unit, canLeave: Boolean) {
    val titles = listOf("何を", "いつ", "どうする")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        titles.forEachIndexed { index, title ->
            FilterChip(
                selected = index == step,
                // 対象が空のまま先へ行っても、決めることが何も無い
                enabled = index == 0 || canLeave,
                onClick = { onStep(index) },
                label = { Text("${index + 1}. $title") },
            )
        }
    }
}

@Composable
private fun BottomBar(
    state: RuleEditState,
    isNew: Boolean,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onSave: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.step > 0) {
            OutlinedButton(onClick = onBack) { Text("戻る") }
        }
        Spacer(Modifier.weight(1f))
        if (state.step < RuleEditViewModel.LAST_STEP) {
            Button(onClick = onNext, enabled = state.hasTarget) { Text("次へ") }
        } else {
            Button(onClick = onSave, enabled = state.canSave) {
                Text(if (isNew) "作成する" else "変更を申請する")
            }
        }
    }
}

// ---- 1. 何を -----------------------------------------------------------

@Composable
private fun TargetStep(
    state: RuleEditState,
    viewModel: RuleEditViewModel,
    labelOf: (String) -> String,
) {
    var showAppPicker by remember { mutableStateOf(false) }
    var showExceptPicker by remember { mutableStateOf(false) }

    Text("何を止めますか", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(12.dp))

    TargetMode.entries.forEach { mode ->
        ModeCard(mode = mode, selected = state.mode == mode, onClick = { viewModel.setMode(mode) })
        Spacer(Modifier.height(8.dp))
    }

    Spacer(Modifier.height(12.dp))

    when (state.mode) {
        TargetMode.APPS -> {
            ChipRow(
                items = state.packages.toList(),
                label = labelOf,
                onRemove = viewModel::togglePackage,
                emptyText = "まだ選ばれていません",
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { showAppPicker = true }) { Text("アプリを選ぶ") }
        }

        TargetMode.SITES -> SitesEditor(
            sites = state.sites,
            onAdd = viewModel::addSite,
            onRemove = viewModel::removeSite,
            onToggleGroup = viewModel::toggleSiteGroup,
        )

        TargetMode.ALL -> {
            Text(
                "電話・ホーム・設定・キーボード・ドパチル自身は、除外に入れなくても止まりません。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Text("残すアプリ", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(6.dp))
            ChipRow(
                items = state.exceptPackages.toList(),
                label = labelOf,
                onRemove = viewModel::toggleExceptPackage,
                emptyText = "まだありません",
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { showExceptPicker = true }) { Text("残すアプリを選ぶ") }
        }
    }

    Disclosure("こまかい指定") {
        if (state.availableTags.isNotEmpty()) {
            Text(
                if (state.mode == TargetMode.ALL) "タグごと残す" else "タグで指定",
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                state.availableTags.forEach { tag ->
                    val selected =
                        if (state.mode == TargetMode.ALL) tag in state.exceptTags else tag in state.tags
                    FilterChip(
                        selected = selected,
                        onClick = {
                            if (state.mode == TargetMode.ALL) {
                                viewModel.toggleExceptTag(tag)
                            } else {
                                viewModel.toggleTag(tag)
                            }
                        },
                        label = { Text("#$tag") },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        Text("止めない URL", style = MaterialTheme.typography.labelMedium)
        Text(
            "ここに書いたページは、上の指定に当たっていても通ります。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        SiteInput(onAdd = viewModel::addExceptSite)
        Spacer(Modifier.height(8.dp))
        ChipRow(
            items = state.exceptSites.toList(),
            label = { it },
            onRemove = viewModel::removeExceptSite,
            emptyText = "",
        )
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
            title = "残すアプリ",
            selected = state.exceptPackages,
            onToggle = viewModel::toggleExceptPackage,
            onDismiss = { showExceptPicker = false },
        )
    }
}

@Composable
private fun ModeCard(mode: TargetMode, selected: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                mode.label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
            Text(
                mode.help,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** URL の指定。束から選ぶのと、手で足すのと両方。 */
@Composable
private fun SitesEditor(
    sites: Set<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onToggleGroup: (String) -> Unit,
) {
    Text(
        "URL で止めるには Chrome の拡張が要ります(設定 → ブラウザ拡張)。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))

    Text("よく挙がるところ", style = MaterialTheme.typography.labelMedium)
    Spacer(Modifier.height(6.dp))
    SiteCatalog.all.forEach { group ->
        Column(Modifier.padding(vertical = 3.dp)) {
            FilterChip(
                selected = sites.containsAll(group.patterns.toSet()),
                onClick = { onToggleGroup(group.id) },
                label = { Text(group.label) },
            )
            Text(
                group.help,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    Spacer(Modifier.height(16.dp))
    Text("自分で足す", style = MaterialTheme.typography.labelMedium)
    Spacer(Modifier.height(6.dp))
    SiteInput(onAdd = onAdd)

    Spacer(Modifier.height(12.dp))
    ChipRow(items = sites.toList(), label = { it }, onRemove = onRemove, emptyText = "まだありません")
}

/**
 * URL を1つ足す欄。
 *
 * 打っている最中に「こう解釈します」を出すのは、この書き方が
 * **間違っていても静かに通ってしまう**ため。www を付けたか、
 * https を付けたかで結果が変わらないことは、見せないと分からない。
 */
@Composable
private fun SiteInput(onAdd: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val normalized = SitePattern.normalize(text)
    val ok = text.isNotBlank() && SitePattern.isValid(normalized)

    Row(verticalAlignment = Alignment.Top) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("URL") },
            placeholder = { Text("youtube.com/shorts") },
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
        TextButton(onClick = { onAdd(text); text = "" }, enabled = ok) { Text("追加") }
    }
}

// ---- 2. いつ -----------------------------------------------------------

@Composable
private fun ConditionStep(state: RuleEditState, viewModel: RuleEditViewModel) {
    Text("いつ止めますか", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(4.dp))
    Text(
        "何も足さなければ「いつでも」です。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(16.dp))

    ConditionTreeEditor(root = state.condition, onChange = viewModel::setCondition)
}

// ---- 3. どうする -------------------------------------------------------

@Composable
private fun ActionStep(
    state: RuleEditState,
    viewModel: RuleEditViewModel,
    ruleId: Long,
    labelOf: (String) -> String,
) {
    Text("どうしますか", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(12.dp))

    // 弱い順に並べる。強いものを先頭に出すと、そこから選んでしまう
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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

    Spacer(Modifier.height(20.dp))

    Disclosure("破ったら / 守ったら") {
        Text(
            "その場の措置とは別に、あとから効く報い。既定では封鎖なし・ポイントだけ動きます。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        ConsequenceEditor(
            consequence = state.consequence,
            policy = state.pointPolicy,
            onChange = viewModel::setConsequence,
        )
    }

    Disclosure("名前") {
        OutlinedTextField(
            value = state.name,
            onValueChange = viewModel::setName,
            label = { Text("ルール名") },
            placeholder = {
                Text(RulePhrase.suggestName(state.target, state.condition, labelOf))
            },
            singleLine = true,
            supportingText = { Text("空のままなら中身から作ります", style = MaterialTheme.typography.bodySmall) },
            modifier = Modifier.fillMaxWidth(),
        )
    }

    if (ruleId != 0L) {
        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { viewModel.delete { } }, modifier = Modifier.fillMaxWidth()) {
            Text("このルールを削除する", color = MaterialTheme.colorScheme.error)
        }
    }
}

// ---- 部品 --------------------------------------------------------------

/**
 * 畳んである欄。
 *
 * 既定のままで困らないものは閉じておく。開いた状態で見せると、
 * 決めなくてよいことを決めさせることになる。
 */
@Composable
private fun Disclosure(title: String, content: @Composable () -> Unit) {
    var open by remember { mutableStateOf(false) }

    Spacer(Modifier.height(16.dp))
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = { open = !open }) {
            Text(title)
            Icon(
                if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = if (open) "閉じる" else "開く",
            )
        }
    }
    AnimatedVisibility(visible = open) {
        Column(Modifier.padding(start = 4.dp, top = 4.dp)) { content() }
    }
}

/** 選ばれているものを並べる。押すと外れる。 */
@Composable
private fun ChipRow(
    items: List<String>,
    label: (String) -> String,
    onRemove: (String) -> Unit,
    emptyText: String,
) {
    if (items.isEmpty()) {
        if (emptyText.isNotBlank()) {
            Text(
                emptyText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { item ->
            AssistChip(
                onClick = { onRemove(item) },
                label = { Text(label(item)) },
                trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "外す") },
            )
        }
    }
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
