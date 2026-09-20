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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import com.dopachiru.core.action.ActionExtras
import com.dopachiru.core.action.ActionRegistry
import com.dopachiru.core.action.ActionType
import com.dopachiru.core.action.types.BlockAction
import com.dopachiru.core.action.types.DelayAction
import com.dopachiru.core.action.types.LockoutAction
import com.dopachiru.core.action.types.WarnAction
import com.dopachiru.core.model.ActionPlan
import com.dopachiru.core.model.MainAction
import com.dopachiru.core.gate.ChangeKind
import com.dopachiru.core.model.ActionSpec
import com.dopachiru.core.model.Clause
import com.dopachiru.core.model.ConditionNode
import com.dopachiru.core.model.ConditionTree
import com.dopachiru.core.model.RuleLinks
import com.dopachiru.core.model.Consequence
import com.dopachiru.core.model.RuleOverlap
import com.dopachiru.core.sync.DeviceInfo
import com.dopachiru.core.model.RuleCheck
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

    /** どの端末で効かせるか。空ならどの端末でも。 */
    val devices: Set<String> = emptySet(),
    /** 選べる端末の名簿。同期を設定していなければ空で、欄ごと出さない。 */
    val knownDevices: List<DeviceInfo> = emptyList(),
    /** この端末の deviceId。名簿に「この端末」と出すため。 */
    val myDeviceId: String = "",

    /** 1組目に重ねる覚え書き(経過表示・目的のチップ)。 */
    val extraActions: List<ActionSpec> = emptyList(),

    /** 2組目以降の「条件 → こうする」。1組目は [condition] と [actionId]。 */
    val extraClauses: List<Clause> = emptyList(),

    /** 手元のルール全部。同じアプリを狙う他のルールを出すために要る。 */
    val allRules: List<Rule> = emptyList(),
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
            val roster = DopaRuntime.devices.first()
            val me = DopaRuntime.myDeviceId
            val everything = DopaRuntime.rules.getAll()
            val rule = if (ruleId == 0L) null else DopaRuntime.rules.getById(ruleId)
            if (rule == null) {
                _state.update {
                    it.copy(
                        availableTags = tags,
                        pointPolicy = policy,
                        knownDevices = roster,
                        myDeviceId = me,
                        allRules = everything,
                        loaded = true,
                    )
                }
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
                extraActions = rule.extraActions,
                extraClauses = rule.extraClauses,
                actionId = rule.actionId,
                actionParams = rule.actionParams,
                consequence = rule.consequence,
                pointPolicy = policy,
                availableTags = tags,
                devices = rule.devices,
                knownDevices = roster,
                myDeviceId = me,
                allRules = everything,
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

    /** どの端末で効かせるか。空にすると「すべての端末」に戻る。 */
    fun toggleDevice(deviceId: String) = _state.update {
        it.copy(
            devices = if (deviceId in it.devices) it.devices - deviceId else it.devices + deviceId,
        )
    }

    fun setEverywhere() = _state.update { it.copy(devices = emptySet()) }

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

    fun setExtraActions(actions: List<ActionSpec>) = _state.update { it.copy(extraActions = actions) }

    fun setExtraClauses(clauses: List<Clause>) = _state.update { it.copy(extraClauses = clauses) }

    /**
     * 「ほかの端末で効いているあいだ、この端末でも効かせる」。
     *
     * 使いすぎを止めるルールは端末を替えれば逃げられる ── 持ち時間が端末ごとに
     * 1本ずつあるため。ルールを配っても直りません(配られるのは決まりごとであって、
     * 使った時間ではない)。効いているという事実のほうを見る条件を、
     * 元の条件との **OR** で足します。[RuleLinks]
     */
    fun setLinked(on: Boolean) = _state.update {
        it.copy(
            condition = if (on) RuleLinks.withLink(it.condition) else RuleLinks.withoutLink(it.condition),
        )
    }

    fun setConsequence(consequence: Consequence) =
        _state.update { it.copy(consequence = consequence) }

    fun setAction(actionId: String) {
        val action = ActionRegistry[actionId] ?: return
        _state.update { it.copy(actionId = actionId, actionParams = Params.defaultsOf(action.params)) }
    }

    fun setActionParams(params: Params) = _state.update { it.copy(actionParams = params) }

    /**
     * 「こうする」を、人が組んだ形([ActionPlan])から保存の形に落とす。
     *
     * 主な動作と重ねるを1つの計画として受け取り、actionId と params に翻訳する。
     * 完全封印か閉め出しかは、計画側の「閉じたあと開けない」の有無で決まる。
     */
    fun setPlan(plan: ActionPlan) = _state.update {
        val (id, params) = plan.resolve(it.actionId, it.actionParams)
        it.copy(actionId = id, actionParams = params)
    }

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
                extraActions = current.extraActions,
                extraClauses = current.extraClauses,
                actionId = current.actionId,
                actionParams = current.actionParams,
                consequence = current.consequence,
                devices = current.devices,
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
                else -> {
                    ActionStep(state, viewModel, ruleId, labelOf)
                    Spacer(Modifier.height(24.dp))
                    ClausesSection(state, viewModel)
                }
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

/**
 * 「どの端末で効かせるか」。
 *
 * 同期を設定していなければ**欄ごと出しません** ── 端末が1台しかない人に
 * 端末の話をさせない。名簿は同期で届くので、届いていなければ選びようもない。
 */
@Composable
private fun DeviceScopeSection(state: RuleEditState, viewModel: RuleEditViewModel) {
    if (state.knownDevices.size < 2) return

    Text("どの端末で", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(6.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = state.devices.isEmpty(),
            onClick = viewModel::setEverywhere,
            label = { Text("すべての端末") },
        )
        state.knownDevices.forEach { device ->
            FilterChip(
                selected = device.deviceId in state.devices,
                onClick = { viewModel.toggleDevice(device.deviceId) },
                label = {
                    Text(
                        device.displayName +
                            if (device.deviceId == state.myDeviceId) "(この端末)" else "",
                    )
                },
            )
        }
    }
    if (state.devices.isNotEmpty() && state.myDeviceId !in state.devices) {
        Spacer(Modifier.height(4.dp))
        Text(
            "このルールはこの端末では効きません。名簿に入れた端末にだけかかります。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Spacer(Modifier.height(12.dp))
    val linked = remember(state.condition) { RuleLinks.contains(state.condition) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("端末をまたいで効かせる", style = MaterialTheme.typography.bodyLarge)
            Text(
                // ルールを配っても「使った時間」は配られない。
                // 効いているという事実のほうを配って塞ぐ
                if (linked) {
                    "どれかの端末でこのルールが効いているあいだ、ほかの端末でも効きます。" +
                        "同期が届かないあいだは、それぞれの端末の中だけで判定します。"
                } else {
                    "いまは端末ごとに別々です。スマホで使い切っても、PC では数え直しになります。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = linked, onCheckedChange = viewModel::setLinked)
    }

    Spacer(Modifier.height(20.dp))
}

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

    DeviceScopeSection(state, viewModel)

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

            // タグは「こまかい指定」ではなく、ここに出す。アプリを1つずつ選ぶのと
            // 同じくらい普通の指し方なのに、畳んだ中に隠すと在ること自体に気づけない
            Spacer(Modifier.height(20.dp))
            TagSection(
                title = "タグで指定",
                help = "タグを付けたアプリをまとめて指せます。あとでアプリを足しても、" +
                    "タグに入れればこのルールが自動でかかります。",
                available = state.availableTags,
                selected = state.tags,
                onToggle = viewModel::toggleTag,
            )
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

            Spacer(Modifier.height(20.dp))
            TagSection(
                title = "タグごと残す",
                help = "このタグを付けたアプリは、全部止めるなかでも開いたままにします。",
                available = state.availableTags,
                selected = state.exceptTags,
                onToggle = viewModel::toggleExceptTag,
            )
        }
    }

    Disclosure("こまかい指定") {
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

/**
 * タグで対象を指す欄。
 *
 * **タグが1つも無いときも欄ごと消さない。** 前はここを
 * `if (availableTags.isNotEmpty())` で丸ごと隠していたので、タグを作っていない人には
 * 「タグで指定する」という手があること自体が見えず、作った人にも畳んだ中でしか
 * 見つからなかった。無いなら「無い」と、どこで作るかまで書く。
 */
@Composable
private fun TagSection(
    title: String,
    help: String,
    available: List<String>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
) {
    Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
    Text(
        help,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))

    if (available.isEmpty()) {
        Text(
            "タグがまだありません。下の「タグ」タブでアプリにタグを付けると、ここに出ます。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        available.forEach { tag ->
            FilterChip(
                selected = tag in selected,
                onClick = { onToggle(tag) },
                label = { Text("#$tag") },
            )
        }
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

/**
 * 同じアプリを狙っている他のルール。
 *
 * ## なぜ出すのか
 *
 * 「同じアプリでも条件ごとにアクションを変えたい」は**もともとできる**。
 * ルールを2本書けば、両方成立したときは強いほうが採られる。
 *
 * ところが画面のどこにもそう書いていないので、できると気づけない ──
 * 1本に全部を詰め込もうとして行き詰まる。足りないのは機能ではなく、
 * 「いま何本がこのアプリを見ているか」が見えることだった。
 *
 * ついでに**どれが勝つか**も出す。弱いほうも一緒に効くと思い込んだまま
 * 組まれるのがいちばん困る ── 効くのは1本だけ。
 */
@Composable
private fun SiblingRulesCard(state: RuleEditState, labelOf: (String) -> String) {
    val me = remember(state.id, state.target, state.actionId) {
        Rule(
            id = state.id,
            name = state.name,
            target = state.target,
            condition = state.condition,
            actionId = state.actionId,
            actionParams = state.actionParams,
        )
    }
    val siblings = remember(me, state.allRules) { RuleOverlap.siblingsOf(me, state.allRules) }
    if (siblings.isEmpty()) return

    val winner = remember(siblings, me) { RuleOverlap.winnerAmong(siblings + me) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(
                "同じアプリを見ているルールが、ほかに${siblings.size}本あります",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "条件ごとに違う動作をさせたいときは、1本に詰め込まずルールを分けます。" +
                    "同時に成立したら、いちばん強いものが1つだけ効きます。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            siblings.take(4).forEach { other ->
                Text(
                    "・" + other.name + " — " + describeRule(other) +
                        if (!other.enabled) "(止めてあります)" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (siblings.size > 4) {
                Text(
                    "ほか${siblings.size - 4}本",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (winner != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    if (winner.id == state.id && state.id != 0L) {
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
    Spacer(Modifier.height(20.dp))
}

@Composable
private fun ActionStep(
    state: RuleEditState,
    viewModel: RuleEditViewModel,
    ruleId: Long,
    labelOf: (String) -> String,
) {
    val plan = ActionPlan.from(state.actionId, state.actionParams)
    val breakable = RuleCheck.isBreakable(state.actionId, state.actionParams)

    SiblingRulesCard(state, labelOf)

    Text("条件を満たしたら", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(12.dp))

    // --- 主な動作(1つ選ぶ) ---
    EscapeCard(
        title = "使えなくする",
        body = "一度閉じて終わりではなく、開き直しても閉まったまま。いちばん基本。",
        selected = plan.main == MainAction.CLOSE,
        onClick = { viewModel.setPlan(plan.copy(main = MainAction.CLOSE)) },
    )
    Spacer(Modifier.height(8.dp))
    EscapeCard(
        title = "少し待たせて通す",
        body = "止めない。数秒の間だけ置いてから必ず通す。",
        selected = plan.main == MainAction.DELAY,
        onClick = { viewModel.setPlan(plan.copy(main = MainAction.DELAY)) },
    )
    Spacer(Modifier.height(8.dp))
    EscapeCard(
        title = "警告だけ",
        body = "止めない。気づかせるだけの、いちばん弱い動作。",
        selected = plan.main == MainAction.WARN,
        onClick = { viewModel.setPlan(plan.copy(main = MainAction.WARN)) },
    )

    // --- 使えなくするの中身 ---
    if (plan.main == MainAction.CLOSE) {
        // 「いつまで」が完全封印と閉め出しの分かれ目。ここを選ばせるのが要
        Spacer(Modifier.height(16.dp))
        Text("いつまで", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !plan.usesTimer,
                onClick = { viewModel.setPlan(plan.copy(lockMinutes = 0)) },
                label = { Text("条件を満たしているあいだ") },
            )
            FilterChip(
                selected = plan.usesTimer,
                onClick = { viewModel.setPlan(plan.copy(lockMinutes = plan.lockMinutes.coerceAtLeast(10))) },
                label = { Text("このあとしばらく") },
            )
        }
        Spacer(Modifier.height(4.dp))
        if (plan.usesTimer) {
            AmountStepper(
                value = plan.lockMinutes,
                min = 1,
                max = 12 * 60,
                step = 5,
                suffix = "分",
                onChange = { viewModel.setPlan(plan.copy(lockMinutes = it)) },
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "閉めてから${plan.lockMinutes}分は、条件が外れても開きません。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                "条件が続くかぎり、開き直しても閉まったまま。条件が外れたら開きます。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(16.dp))
        if (!plan.usesTimer) {
            Text("逃げ道", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !plan.soft,
                    onClick = { viewModel.setPlan(plan.copy(soft = false)) },
                    label = { Text("しっかり") },
                )
                FilterChip(
                    selected = plan.soft,
                    onClick = { viewModel.setPlan(plan.copy(soft = true)) },
                    label = { Text("やんわり") },
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                if (plan.soft) {
                    "手間をかければ押し切れます。押し切ると「破った」ことになります。"
                } else {
                    "押し切る口はありません。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                "時間で締め出すあいだは押し切れません。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // --- 重ねる ---
        Spacer(Modifier.height(16.dp))
        Text("重ねる", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)

        CheckRow(
            checked = plan.prewarnSeconds > 0,
            title = "閉じる前に、そっと知らせる",
            onToggle = { on ->
                viewModel.setPlan(
                    plan.copy(prewarnSeconds = if (on) ActionExtras.DEFAULT_PREWARN_SECONDS else 0)
                )
            },
        ) {
            AmountStepper(
                value = plan.prewarnSeconds,
                min = 1,
                max = ActionExtras.MAX_PREWARN_SECONDS,
                step = 1,
                suffix = "秒",
                onChange = { viewModel.setPlan(plan.copy(prewarnSeconds = it)) },
            )
        }

    }

    // --- くわしい動作 ---
    if (plan.main == MainAction.ADVANCED) {
        Spacer(Modifier.height(16.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            advancedActions().forEach { action ->
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
            Spacer(Modifier.height(8.dp))
            ParamEditor(specs = action.params, params = state.actionParams, onChange = viewModel::setActionParams)
        }
    } else {
        // 「くわしい動作(音だけ・目的を書く…)」への入口。ふだんは畳んでおく
        Disclosure("ほかの動作にする") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                advancedActions().forEach { action ->
                    FilterChip(
                        selected = false,
                        onClick = { viewModel.setAction(action.id) },
                        label = { Text(action.displayName) },
                    )
                }
            }
        }
    }

    // 文言など、こまかい調整。既定のままで困らないので畳んでおく
    ActionRegistry[state.actionId]?.let { action ->
        val hidden = setOf(BlockAction.KEY_ALLOW_OVERRIDE, ActionExtras.KEY_PREWARN_SECONDS, LockoutAction.KEY_MINUTES)
        val specs = action.params.filter { it.key !in hidden }
        if (plan.main != MainAction.ADVANCED && specs.isNotEmpty()) {
            Disclosure("文言・こまかい調整") {
                ParamEditor(specs = specs, params = state.actionParams, onChange = viewModel::setActionParams)
            }
        }
    }

    // 破れる動作のときだけ、ポイントの罰を重ねられる
    if (breakable) {
        CheckRow(
            checked = (state.consequence.breakPoints ?: 0) < 0,
            title = "破ったらポイントを引く",
            onToggle = { on ->
                viewModel.setConsequence(
                    state.consequence.copy(breakPoints = if (on) -state.pointPolicy.defaultBreakPoints.let { if (it != 0) it else 5 } else null)
                )
            },
        ) {
            Text(
                RuleCheck.breakMeans(state.actionId, state.actionParams),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    // 保存はできるが書いたとおりには効かない組み合わせを知らせる
    val warnings = RuleCheck.warnings(
        condition = state.condition,
        target = state.target,
        actionId = state.actionId,
        actionParams = state.actionParams,
    )
    if (warnings.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        warnings.forEach { warning ->
            Text(warning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(4.dp))
        }
    }

    // 罰の細かい調整(範囲を変える・段階的に強める等)は、破れる動作のときだけ奥に置く
    if (breakable) {
        Disclosure("破ったときの報い(くわしく)") {
            ConsequenceEditor(
                consequence = state.consequence,
                policy = state.pointPolicy,
                availableTags = state.availableTags,
                onChange = viewModel::setConsequence,
            )
        }
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

/**
 * 1組目に重ねる覚え書きと、2組目以降。
 *
 * 「どうする」の段の下に置いてある ── 1組しか要らない人が、組の話に
 * 出くわさずに済むように。
 */
@Composable
private fun ClausesSection(state: RuleEditState, viewModel: RuleEditViewModel) {
    HorizontalDivider()
    Spacer(Modifier.height(20.dp))

    StackedActionsRow(
        actions = state.extraActions,
        onChange = viewModel::setExtraActions,
    )

    Spacer(Modifier.height(24.dp))
    ExtraClausesSection(
        clauses = state.extraClauses,
        onChange = viewModel::setExtraClauses,
    )
    Spacer(Modifier.height(16.dp))
}


// ---- 部品 --------------------------------------------------------------

/** 主な動作の下に置く「くわしい動作」。閉じる・待たせる・警告 以外。 */
private fun advancedActions(): List<ActionType> =
    ActionRegistry.all().filter {
        it.id !in setOf(BlockAction.id, LockoutAction.id, DelayAction.id, WarnAction.id)
    }

/**
 * 「重ねる」1つ。チェックを入れると中身(細かい値)が出る。
 *
 * 主な動作の上に足す小さな振る舞いを、同じ形で並べるためのもの。
 */
@Composable
private fun CheckRow(
    checked: Boolean,
    title: String,
    onToggle: (Boolean) -> Unit,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = checked, onCheckedChange = onToggle)
            Text(title, style = MaterialTheme.typography.bodyLarge)
        }
        if (checked) {
            Column(Modifier.padding(start = 40.dp, bottom = 4.dp)) { content() }
        }
    }
}

/** −/+ で増減する数。秒でも分でも使う。 */
@Composable
private fun AmountStepper(
    value: Int,
    min: Int,
    max: Int,
    step: Int,
    suffix: String,
    onChange: (Int) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(
            onClick = { onChange((value - step).coerceAtLeast(min)) },
            enabled = value > min,
        ) { Text("−") }
        Text(
            "$value $suffix",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        OutlinedButton(
            onClick = { onChange((value + step).coerceAtMost(max)) },
            enabled = value < max,
        ) { Text("＋") }
    }
}

/** 逃げ道を残すかの二択。どちらを選んだかが一目で分かるよう、札で出す。 */
@Composable
private fun EscapeCard(title: String, body: String, selected: Boolean, onClick: () -> Unit) {
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
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

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
