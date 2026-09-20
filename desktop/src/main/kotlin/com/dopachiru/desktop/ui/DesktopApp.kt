package com.dopachiru.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.VerticalDivider
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import com.dopachiru.core.gate.Gate
import com.dopachiru.core.gate.ChangeKind
import com.dopachiru.core.model.Focus
import com.dopachiru.core.model.FocusScope
import com.dopachiru.core.model.FocusTemplate
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.dopachiru.desktop.data.DesktopSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.dopachiru.core.io.ImportPlan
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dopachiru.core.action.ActionRegistry
import com.dopachiru.core.action.types.BlockAction
import com.dopachiru.core.model.ConditionNode
import com.dopachiru.core.model.ConditionTree
import com.dopachiru.core.param.Params
import com.dopachiru.core.model.DeviceScope
import com.dopachiru.core.model.Lockout
import com.dopachiru.core.model.Rule
import com.dopachiru.core.points.PointEvent
import com.dopachiru.core.points.PointPolicy
import com.dopachiru.core.preset.RulePreset
import com.dopachiru.core.preset.RulePresets
import com.dopachiru.core.model.Target
import com.dopachiru.core.model.ReservationRules
import com.dopachiru.desktop.DesktopRuntime
import com.dopachiru.desktop.update.DesktopUpdater
import com.dopachiru.desktop.platform.BlockStrength
import com.dopachiru.desktop.platform.ForegroundApp
import com.dopachiru.desktop.platform.InstalledApps
import com.dopachiru.desktop.platform.ProtectedProcesses
import com.dopachiru.desktop.platform.WindowsAutoStart
import com.dopachiru.desktop.platform.RunningApps

@Composable
fun DesktopApp() = DopaTheme {
    var tab by remember { mutableIntStateOf(0) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Scaffold(
            topBar = {
                TabRow(selectedTabIndex = tab) {
                    listOf("ルール", "タグ", "変更", "端末", "今日", "設定").forEachIndexed { index, title ->
                        Tab(
                            selected = tab == index,
                            onClick = { tab = index },
                            text = { Text(title) },
                        )
                    }
                }
            }
        ) { padding ->
            Box(Modifier.padding(padding)) {
                when (tab) {
                    0 -> RulesTab()
                    1 -> TagScreen()
                    2 -> ChangeScreen()
                    3 -> DeviceScreen()
                    4 -> TodayTab()
                    else -> SettingsTab()
                }
            }
        }
    }
}

// ----------------------------------------------------------------------

@Composable
private fun RulesTab() {
    val file by DesktopRuntime.ruleFile.collectAsState()
    var pickingPreset by remember { mutableStateOf(false) }
    var presetAwaitingApps by remember { mutableStateOf<RulePreset?>(null) }
    var presetProcesses by remember { mutableStateOf(emptySet<String>()) }
    var showAppPicker by remember { mutableStateOf(false) }

    // ゼロから組むときの下書き。null なら編集していない
    var drafting by remember { mutableStateOf<Rule?>(null) }
    val settings by DesktopRuntime.settings.collectAsState()

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pickingPreset = true }, modifier = Modifier.weight(1f)) {
                Text("雛形から足す")
            }
            // 雛形からしか作れないのは Windows 側だけの制限だった。
            // 中身(RuleEditor)は最初から全部書けるので、入口を1つ足すだけで済む
            OutlinedButton(onClick = { drafting = blankRule() }, modifier = Modifier.weight(1f)) {
                Text("ゼロから組む")
            }
        }
        Spacer(Modifier.height(12.dp))

        if (file.rules.isEmpty()) {
            Text(
                "まだルールがありません。雛形から始めるのが速いです。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(file.rules, key = { it.id }) { rule -> RuleCard(rule) }
        }
    }

    if (pickingPreset) {
        AlertDialog(
            onDismissRequest = { pickingPreset = false },
            title = { Text("雛形を選ぶ") },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(RulePresets.all, key = { it.id }) { preset ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            onClick = {
                                pickingPreset = false
                                presetProcesses = emptySet()
                                presetAwaitingApps = preset
                            },
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(preset.name, style = MaterialTheme.typography.titleSmall)
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    preset.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickingPreset = false }) { Text("やめる") } },
        )
    }

    presetAwaitingApps?.let { preset ->
        AlertDialog(
            onDismissRequest = { presetAwaitingApps = null },
            title = { Text(preset.name) },
            text = {
                Column {
                    Text(preset.description, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (presetProcesses.isEmpty()) preset.appPrompt
                        else presetProcesses.joinToString("、") { ForegroundApp.labelFor(it) },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { showAppPicker = true }) { Text("アプリを選ぶ") }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = presetProcesses.isNotEmpty() || preset.allowEmptyApps,
                    onClick = {
                        DesktopRuntime.addRule(preset.build(presetProcesses))
                        presetAwaitingApps = null
                    },
                ) { Text("作る") }
            },
            dismissButton = {
                TextButton(onClick = { presetAwaitingApps = null }) { Text("やめる") }
            },
        )
    }

    if (showAppPicker) {
        AppPickerDialog(
            selected = presetProcesses,
            onToggle = { process ->
                presetProcesses =
                    if (process in presetProcesses) presetProcesses - process
                    else presetProcesses + process
            },
            onDismiss = { showAppPicker = false },
        )
    }

    drafting?.let { draft ->
        RuleEditorDialog(
            rule = draft,
            policy = settings.pointPolicy,
            onSave = { built ->
                // 新規は関門を通さない。縛りを増やす方向に摩擦をかける理由が無い
                DesktopRuntime.addRule(built)
                drafting = null
            },
            onDismiss = { drafting = null },
        )
    }
}

/**
 * ゼロから組むときの下書き。
 *
 * 対象も条件も空。**空の対象は何にも当たらない**ので、保存しても事故にはならない
 * (当たらないルールが1本増えるだけ)。既定の措置だけ「使えなくする」に寄せてある。
 */
private fun blankRule(): Rule = Rule(
    id = 0L,
    name = "新しいルール",
    target = Target(),
    condition = ConditionNode.AllOf(emptyList()),
    actionId = BlockAction.id,
    actionParams = Params.defaultsOf(BlockAction.params),
)

@Composable
private fun RuleCard(rule: Rule) {
    var confirmDelete by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    val settings by DesktopRuntime.settings.collectAsState()

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(rule.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Switch(
                    checked = rule.enabled,
                    onCheckedChange = { DesktopRuntime.setRuleEnabled(rule.id, it) },
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                describeTarget(rule),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                describeRule(rule),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (rule.devices.isNotEmpty()) {
                val names = DesktopRuntime.ruleFile.collectAsState().value.devices
                    .associate { it.deviceId to it.displayName }
                Text(
                    "端末: " + DeviceScope.describe(rule.devices) { names[it] ?: it },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { editing = true }) { Text("条件と罰を編集") }
                // 端末ごとに違う中身にしたいときは、2本に分けてそれぞれ宛先を変える
                TextButton(onClick = { DesktopRuntime.duplicateRule(rule) }) { Text("複製") }
                TextButton(onClick = { confirmDelete = true }) {
                    Text("削除", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    // 関門があるときは、変えるのも消すのもいったん申請になる
    var queued by remember { mutableStateOf(false) }

    if (editing) {
        RuleEditorDialog(
            rule = rule,
            policy = settings.pointPolicy,
            onSave = {
                queued = DesktopRuntime.requestChange(ChangeKind.UPDATE, it)
                editing = false
            },
            onDismiss = { editing = false },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("「${rule.name}」を削除しますか?") },
            text = if (settings.gates.isEmpty()) null else {
                { Text("関門を設定しているので、すぐには消えません。「変更」タブで通してください。") }
            },
            confirmButton = {
                TextButton(onClick = {
                    queued = DesktopRuntime.requestChange(ChangeKind.DELETE, rule)
                    confirmDelete = false
                }) { Text("削除する") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("やめる") }
            },
        )
    }

    if (queued) {
        AlertDialog(
            onDismissRequest = { queued = false },
            title = { Text("変更を申請しました") },
            text = { Text("この変更はすぐには反映されません。「変更」タブで関門を通すと適用されます。") },
            confirmButton = { TextButton(onClick = { queued = false }) { Text("わかった") } },
        )
    }
}

private fun describeTarget(rule: Rule): String {
    if (rule.target.matchAll) {
        val excluded = rule.target.exceptPackages.size + rule.target.exceptTags.size
        return if (excluded == 0) "全アプリ" else "全アプリ(除外${excluded}件)"
    }
    val names = rule.target.packages.map { ForegroundApp.labelFor(it) } +
        rule.target.tags.map { "#$it" }
    return when {
        names.isEmpty() -> "対象なし"
        names.size <= 3 -> names.joinToString("、")
        else -> "${names.take(3).joinToString("、")} 他${names.size - 3}件"
    }
}

private fun describeRule(rule: Rule): String {
    val action = ActionRegistry[rule.actionId]?.summarize(rule.actionParams) ?: rule.actionId
    val head = if (ConditionTree.leafCount(rule.condition) == 0) {
        "常に"
    } else {
        ConditionTree.describe(rule.condition)
    }
    val consequence = if (rule.consequence.locksNothing) {
        ""
    } else {
        " / 破ったら${rule.consequence.lockScope.label}を${rule.consequence.lockMinutes}分"
    }
    // 2組目以降があることを隠さない。隠すと、一覧に出ていない組が黙って
    // 効いて「書いていないのに閉まる」になる
    val more = if (rule.extraClauses.isEmpty()) "" else " ほか" + rule.extraClauses.size + "組"
    return "$head → $action$consequence$more"
}

// ----------------------------------------------------------------------

@Composable
private fun TodayTab() {
    val foreground by DesktopRuntime.foreground.collectAsState()
    val breakdown = remember(foreground) { DesktopRuntime.todayBreakdown() }
    val total = remember(foreground) { DesktopRuntime.todayTotalMinutes() }
    val settings by DesktopRuntime.settings.collectAsState()
    val lockouts by DesktopRuntime.lockouts.collectAsState()
    val balance by DesktopRuntime.balance.collectAsState()
    val points by DesktopRuntime.points.collectAsState()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        // 罰は一番上に出す。「なぜ開かないのか」が分からないまま塞がれるのが
        // いちばん堪えるので、閉まっているものと残り時間は常に見えるようにしておく
        if (lockouts.isNotEmpty()) {
            LockoutCard(lockouts)
            Spacer(Modifier.height(16.dp))
        }

        if (settings.pointPolicy.enabled) {
            PointCard(
                balance = balance,
                policy = settings.pointPolicy,
                events = points.reversed(),
                passUntil = settings.passUntilSec,
            )
            Spacer(Modifier.height(16.dp))
        }

        // 決めたくなるのは使う直前。設定まで行かせると、
        // 行き着くころには決める気が消えている
        OneShotCard()
        Spacer(Modifier.height(16.dp))

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("今日の合計", style = MaterialTheme.typography.labelMedium)
                Text(
                    formatMinutes(total),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Light,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "いま前面: ${foreground?.label ?: "—"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("アプリごと", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        // 上のスクロールに乗せるので、ここは LazyColumn ではなく素直に並べる
        breakdown.forEach { (process, minutes) ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(ForegroundApp.labelFor(process), style = MaterialTheme.typography.bodyMedium)
                Text(
                    formatMinutes(minutes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 罰で閉まっているものと、その残り時間。
 *
 * 解除ボタンは無い ── あったらそれは罰ではない。
 * 残り時間を隠さないのは、見えない拘束がいちばん人を追い詰めるため。
 */
@Composable
private fun LockoutCard(lockouts: List<Lockout>) {
    val nowSec = System.currentTimeMillis() / 1000
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "お預け中",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            lockouts.forEach { lockout ->
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        lockout.reason.ifBlank { "ルールを破った罰" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        "あと${lockout.remainingMinutesAt(nowSec)}分",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "時間が過ぎれば自動で開きます。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

/** ポイントの残高と直近の増減。解禁券もここから買う。 */
@Composable
private fun PointCard(
    balance: Int,
    policy: PointPolicy,
    events: List<PointEvent>,
    passUntil: Long,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text("ポイント", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (policy.recordOnly) "いまは数えているだけ" else "守れば貯まる",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "$balance",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Light,
                    color = if (balance < 0) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }

            if (policy.passEnabled) {
                Spacer(Modifier.height(12.dp))
                val nowSec = System.currentTimeMillis() / 1000
                if (passUntil > nowSec) {
                    Text(
                        "解禁券が効いています(あと${(passUntil - nowSec + 59) / 60}分)。" +
                            "いまは制限が全部止まっています。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    TextButton(
                        onClick = { DesktopRuntime.buyPass() },
                        enabled = balance >= policy.passCost,
                    ) {
                        Text("解禁券を買う(${policy.passCost}pt で${policy.passMinutes}分)")
                    }
                    if (balance < policy.passCost) {
                        Text(
                            "あと${policy.passCost - balance}pt 足りません。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (events.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                events.take(5).forEach { event ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            listOf(event.reason.label, event.note)
                                .filter { it.isNotBlank() }
                                .joinToString(" / "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            if (event.delta > 0) "+${event.delta}" else "${event.delta}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (event.delta > 0) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                    }
                }
            }
        }
    }
}

// ----------------------------------------------------------------------

/**
 * その場かぎりの集中。
 *
 * ルールが「いつ・どの条件で」を先に決めておくものなのに対して、
 * これは思い立った瞬間に始めて、時間が来たら勝手に解ける。
 *
 * 既定を短くしてあるのは、長く始めすぎたときの逃げ方が高くつくため。
 * 短く始めて足すほうが余計な代金を払わずに済む。
 */
@Composable
private fun FocusSection() {
    val settings by DesktopRuntime.settings.collectAsState()
    val lockouts by DesktopRuntime.lockouts.collectAsState()
    val ruleFile by DesktopRuntime.ruleFile.collectAsState()
    var minutes by remember { mutableStateOf(settings.focus.defaultMinutes) }
    var scope by remember { mutableStateOf(FocusScope.EVERYTHING) }
    var tag by remember { mutableStateOf("") }

    val tags = remember(ruleFile) { ruleFile.tags.values.flatten().distinct().sorted() }
    val running = Focus.activeIn(lockouts, System.currentTimeMillis() / 1000)

    Text("集中モード", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(4.dp))
    Text(
        "選んだ時間だけ閉まります。時間が来れば勝手に解けます。" +
            "エクスプローラやタスクマネージャは集中中も開いたままです。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))

    if (running != null) {
        Text(
            "あと ${running.remainingMinutesAt(System.currentTimeMillis() / 1000)} 分",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Focus.EXTEND_CHOICES.forEach { add ->
                OutlinedButton(onClick = { DesktopRuntime.extendFocus(add) }) { Text("+${add}分") }
            }
        }
        return
    }

    Text("止める範囲", style = MaterialTheme.typography.labelLarge)
    Spacer(Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FocusScope.entries.forEach { option ->
            val label = when (option) {
                FocusScope.EVERYTHING -> "全部"
                FocusScope.GROUP -> "グループだけ"
                FocusScope.EXCEPT_GROUP -> "グループ以外"
            }
            if (scope == option) {
                Button(onClick = { scope = option }) { Text(label) }
            } else {
                OutlinedButton(onClick = { scope = option }) { Text(label) }
            }
        }
    }

    if (scope != FocusScope.EVERYTHING) {
        Spacer(Modifier.height(8.dp))
        if (tags.isEmpty()) {
            Text(
                "タグがありません。先にアプリにタグを付けてください。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                tags.forEach { t ->
                    if (tag == t) {
                        Button(onClick = { tag = t }) { Text(t) }
                    } else {
                        OutlinedButton(onClick = { tag = t }) { Text(t) }
                    }
                }
            }
        }
    }

    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            onClick = { minutes = (minutes - Focus.STEP_MINUTES).coerceAtLeast(Focus.MIN_MINUTES) },
            enabled = minutes > Focus.MIN_MINUTES,
        ) { Text("−") }
        Text("$minutes 分", style = MaterialTheme.typography.titleMedium)
        TextButton(
            onClick = { minutes = (minutes + Focus.STEP_MINUTES).coerceAtMost(Focus.MAX_MINUTES) },
            enabled = minutes < Focus.MAX_MINUTES,
        ) { Text("+") }
        Spacer(Modifier.width(12.dp))
        val ready = scope == FocusScope.EVERYTHING || tag.isNotBlank()
        Button(
            enabled = ready,
            onClick = {
                if (scope == FocusScope.EVERYTHING) {
                    DesktopRuntime.startFocus(minutes)
                } else {
                    DesktopRuntime.startFocus(
                        FocusTemplate(id = "", scope = scope, tag = tag),
                        minutes,
                    )
                }
            },
        ) { Text("始める") }
    }
    Spacer(Modifier.height(8.dp))
    Text(
        "短めに始めるのを勧めます。足りなければ足せますが、" +
            "長すぎたぶんを切り上げるにはポイントが要ります。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 端末間の同期。
 *
 * **既定で切ってあります。** 住所と合言葉を入れて初めて動きます。
 * 何が出るかを画面に書いてあるのは、外から確かめようが無いためです。
 */
@Composable
private fun SyncSection() {
    val settings by DesktopRuntime.settings.collectAsState()
    val sync = settings.sync
    var url by remember(sync.baseUrl) { mutableStateOf(sync.baseUrl) }
    var token by remember(sync.token) { mutableStateOf(sync.token) }
    var device by remember(sync.deviceId) { mutableStateOf(sync.deviceId) }
    var showToken by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }

    // 出した短い合言葉と、その残り秒。切れたことが見えないと、
    // 打ち込んで「合わない」と悩むことになる
    var invite by remember { mutableStateOf("") }
    var inviteLeft by remember { mutableIntStateOf(0) }
    LaunchedEffect(invite) {
        while (inviteLeft > 0) {
            kotlinx.coroutines.delay(1_000)
            inviteLeft -= 1
        }
    }
    val scope = rememberCoroutineScope()

    Text("端末間の同期", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(4.dp))
    Text(
        "別の端末とルールを揃えます。制限そのものはここに依存しません ── " +
            "サーバーが落ちていても、縛りは効いたままです。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))

    OutlinedTextField(
        value = url,
        onValueChange = { url = it },
        label = { Text("サーバーの住所") },
        placeholder = { Text("https://dopa.togar.dev") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = token,
        onValueChange = { token = it },
        label = { Text("合言葉") },
        singleLine = true,
        visualTransformation = if (showToken) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        trailingIcon = {
            TextButton(onClick = { showToken = !showToken }) {
                Text(if (showToken) "隠す" else "見る")
            }
        },
        supportingText = { Text("英数字と記号だけ。日本語は通信の見出しに載りません。") },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = device,
        onValueChange = { device = it },
        label = { Text("この端末の名前") },
        placeholder = { Text("pc") },
        singleLine = true,
        supportingText = { Text("実績を端末ごとに分けて見るときの見出しになります。") },
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = {
            DesktopRuntime.updateSettings {
                it.copy(
                    sync = it.sync.copy(
                        baseUrl = url.trim(),
                        token = token.trim(),
                        deviceId = device.trim(),
                    ),
                )
            }
            result = "保存しました"
        }) { Text("保存する") }

        Switch(
            checked = sync.enabled,
            enabled = sync.isConfigured,
            onCheckedChange = { on ->
                DesktopRuntime.updateSettings { it.copy(sync = it.sync.copy(enabled = on)) }
            },
        )
        Text(if (sync.enabled) "同期する" else "同期しない", style = MaterialTheme.typography.bodySmall)

        Button(
            onClick = {
                busy = true
                result = "同期しています…"
                scope.launch {
                    // 通信を待つあいだ画面を止めない
                    val out = withContext(Dispatchers.IO) { DesktopRuntime.syncNow() }
                    result = when (out) {
                        is DesktopSync.Outcome.Done -> "受け取り ${out.pulled} 件 / 送り ${out.pushed} 件"
                        is DesktopSync.Outcome.NotConfigured -> "まだ設定できていません"
                        is DesktopSync.Outcome.Failed -> out.message
                    }
                    busy = false
                }
            },
            enabled = sync.isConfigured && sync.enabled && !busy,
        ) { Text("いま同期する") }
    }

    // ここから、もう1台を繋ぐための短い合言葉。
    //
    // 本物の合言葉は48文字あって、スマホに打ち込むのは現実的でない。
    // カメラも権限も要らない代わりに**2分で死ぬ・1回しか使えない**ので、
    // 残り時間を出しておく ── 出さないと、切れたコードを打ち込んで悩むことになる。
    if (sync.isConfigured) {
        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(20.dp))

        Text("もう1台を繋ぐ", style = MaterialTheme.typography.bodyLarge)
        Text(
            "短い合言葉を出します。スマホの「同期」で住所を入れて、そこに打ち込んでください。" +
                "48文字のほうを写す必要はありません。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        if (invite.isNotBlank()) {
            Text(
                invite,
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Medium,
                letterSpacing = 4.sp,
            )
            Text(
                if (inviteLeft > 0) "あと ${inviteLeft} 秒で切れます" else "切れました。出し直してください",
                style = MaterialTheme.typography.bodySmall,
                color = if (inviteLeft > 0) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
            Spacer(Modifier.height(8.dp))
        }

        OutlinedButton(
            onClick = {
                busy = true
                result = "合言葉を出しています…"
                scope.launch {
                    val out = withContext(Dispatchers.IO) { DesktopRuntime.newInvite() }
                    when (out) {
                        is DesktopRuntime.InviteResult.Ok -> {
                            invite = out.code
                            inviteLeft = out.seconds
                            result = ""
                        }

                        is DesktopRuntime.InviteResult.Failed -> {
                            invite = ""
                            result = out.message
                        }
                    }
                    busy = false
                }
            },
            enabled = !busy,
        ) { Text(if (invite.isBlank()) "合言葉を出す" else "出し直す") }
    }

    if (result.isNotBlank()) {
        Spacer(Modifier.height(8.dp))
        Text(result, style = MaterialTheme.typography.bodySmall)
    }
    if (sync.lastError.isNotBlank()) {
        Spacer(Modifier.height(4.dp))
        Text(
            "前回: " + sync.lastError,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }

    Spacer(Modifier.height(12.dp))
    Text(
        "出るもの: ルール・タグ・アプリ名・今日の使用時間\n" +
            "出ないもの: どの瞬間に何を見ていたか、ゲート、変更リクエスト",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 拡張から最後に連絡が来てから、これを過ぎたら「止まっているかも」と出す(秒)。 */
private const val FRESH_SEC = 180L

/**
 * ブラウザ拡張とのつなぎ。
 *
 * URL は本体が判定するので、ここで繋がっていないと
 * 「youtube.com/shorts を止める」ルールは一切効かない。
 * 効いていないことに気づけるよう、状態をそのまま出す。
 */
@Composable
private fun BrowserBridgeSection() {
    val settings by DesktopRuntime.settings.collectAsState()
    val status by DesktopRuntime.bridgeStatus.collectAsState()

    Text("ブラウザ拡張", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(4.dp))
    Text(
        "URL でページを止めるには、Chrome の拡張が要ります。判定はこちら側で行うので、" +
            "時間帯・連続時間・ポイント・罰は、アプリのときとまったく同じに効きます。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))

    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text("受け口を開ける", style = MaterialTheme.typography.bodyLarge)
            Text(
                "127.0.0.1 だけで待ち受けます。同じ機械の中からしか触れません。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = settings.bridgeEnabled,
            onCheckedChange = { DesktopRuntime.setBridgeEnabled(it) },
        )
    }

    if (settings.bridgeEnabled) {
        Spacer(Modifier.height(12.dp))

        val seenSecAgo = status.lastSeenSecAgo
        val line = when {
            !status.running -> "ポートを掴めませんでした"
            status.pairing -> "つなぐのを待っています(2分)。Chrome の拡張の設定で「本体につなぐ」を押してください"
            !status.paired -> "まだ繋いでいません"
            seenSecAgo == null -> "繋いであります。まだ拡張から連絡はありません"
            seenSecAgo < FRESH_SEC -> "つながっています"
            else -> "繋いでありますが、しばらく連絡がありません(拡張が止まっているかもしれません)"
        }
        val good = status.running && status.paired && (seenSecAgo ?: Long.MAX_VALUE) < FRESH_SEC

        Text(
            line,
            style = MaterialTheme.typography.bodySmall,
            color = if (good) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            },
        )

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { DesktopRuntime.startPairing() }, enabled = status.running) {
                Text(if (status.paired) "つなぎ直す" else "ブラウザ拡張とつなぐ")
            }
            if (status.paired) {
                OutlinedButton(onClick = { DesktopRuntime.unpairBridge() }) { Text("縁を切る") }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "拡張が黙ってから2分半で、URL の規則は自動的に外れます(= 通ります)。" +
                "拡張が落ちただけでブラウザが使えなくなるほうが、取り返しがつかないためです。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * ルールの持ち出しと取り込み。
 *
 * 込み入ったルールを画面でこねるより、書き出して手元の道具に直してもらうほうが早い。
 * 書き出しには条件とアクションの目録が入るので、**このファイル1つ渡せば**
 * 正しい形のルールを書いてもらえる。
 *
 * 取り込みは押した瞬間には何も変えない。何件増えて何件差し替わるかを先に見せる。
 */
@Composable
private fun RuleTransferSection() {
    var plan by remember { mutableStateOf<ImportPlan?>(null) }
    var message by remember { mutableStateOf("") }

    Text("ルールの持ち出し", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(4.dp))
    Text(
        "書き出した JSON には、使える条件・措置・サイトの目録が入っています。" +
            "そのファイルを渡せば、外の道具にルールを書いてもらえます。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = {
            val path = chooseFile(save = true) ?: return@Button
            message = runCatching {
                File(path).writeText(DesktopRuntime.exportRules(), Charsets.UTF_8)
                "書き出しました: " + path
            }.getOrElse { "書き出せませんでした: " + (it.message ?: "") }
        }) { Text("書き出す") }

        OutlinedButton(onClick = {
            val path = chooseFile(save = false) ?: return@OutlinedButton
            val text = runCatching { File(path).readText(Charsets.UTF_8) }.getOrNull()
            if (text == null) {
                message = "ファイルを読めませんでした"
                return@OutlinedButton
            }
            DesktopRuntime.planImport(text)
                .onSuccess { plan = it; message = "" }
                .onFailure { message = it.message ?: "読めませんでした" }
        }) { Text("読み込む") }

        // 使用状況 → Claude → .rules → 読み込む、の輪。書き出しの隣に置く
        OutlinedButton(onClick = {
            val path = chooseFile(save = true) ?: return@OutlinedButton
            message = runCatching {
                File(path).writeText(DesktopRuntime.exportUsage(), Charsets.UTF_8)
                "書き出しました: " + path + " ── 個人の記録なので渡す先に気をつけて"
            }.getOrElse { "書き出せませんでした: " + (it.message ?: "") }
        }) { Text("使用状況") }
    }

    if (message.isNotBlank()) {
        Spacer(Modifier.height(8.dp))
        Text(message, style = MaterialTheme.typography.bodySmall)
    }

    plan?.let { p ->
        AlertDialog(
            onDismissRequest = { plan = null },
            title = { Text("取り込む前に") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(p.summary(), style = MaterialTheme.typography.titleSmall)
                    if (p.added.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text("増えるもの", style = MaterialTheme.typography.labelMedium)
                        p.added.forEach { Text("・" + it.name, style = MaterialTheme.typography.bodySmall) }
                    }
                    if (p.replaced.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text("差し替わるもの", style = MaterialTheme.typography.labelMedium)
                        p.replaced.forEach { (before, after) ->
                            Text("・" + before.name + " → " + after.name, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (p.problems.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "取り込めないもの",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        p.problems.forEach {
                            Text(
                                "・" + it.ruleName + ": " + it.reason,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        DesktopRuntime.applyImport(p)
                        message = p.summary()
                        plan = null
                    },
                    enabled = !p.isEmpty,
                ) { Text("取り込む") }
            },
            dismissButton = { TextButton(onClick = { plan = null }) { Text("やめる") } },
        )
    }
}

/** Windows の素のファイル選択。Compose Desktop には無いので AWT を借りる。 */
private fun chooseFile(save: Boolean): String? {
    val dialog = FileDialog(null as Frame?, if (save) "書き出し先" else "読み込むファイル",
        if (save) FileDialog.SAVE else FileDialog.LOAD)
    if (save) dialog.file = "dopachiru-rules.json"
    dialog.isVisible = true
    val dir = dialog.directory ?: return null
    val name = dialog.file ?: return null
    return dir + name
}

/**
 * Windows と一緒に立ち上げる。
 *
 * レジストリを正として読む ── 設定ファイルに覚えた値だけを見せると、
 * タスクマネージャから切られたあとも「入っています」と嘘をつく。
 */
@Composable
private fun LaunchAtLoginSection() {
    val settings by DesktopRuntime.settings.collectAsState()

    // 画面を開くたびに実際の状態を読み直す。トグルを押したときも読み直す
    var registered by remember { mutableStateOf(WindowsAutoStart.isEnabled()) }
    var blocked by remember { mutableStateOf(WindowsAutoStart.blockedByWindows()) }
    val supported = remember { WindowsAutoStart.supported }

    Text(
        "Windows と一緒に立ち上げる",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        "ログインしたらトレイに常駐します。窓は開きません。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))

    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text("ログイン時に起動する", style = MaterialTheme.typography.bodyLarge)
            Text(
                when {
                    !supported ->
                        "配布した版でだけ使えます(いまは Gradle から動かしています)。"
                    blocked ->
                        "登録してありますが、Windows 側で切られています。" +
                            "タスクマネージャの「スタートアップ アプリ」から戻してください。"
                    registered ->
                        "登録済み。いまの置き場所を指しています ── フォルダを動かしたら、" +
                            "動かした先で一度起動すれば直ります。" +
                            "タスクマネージャの「スタートアップ アプリ」からも切れます。"
                    else -> "まだ登録していません。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (blocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = registered || settings.launchAtLogin,
            enabled = supported,
            onCheckedChange = { wanted ->
                registered = DesktopRuntime.setLaunchAtLogin(wanted)
                blocked = WindowsAutoStart.blockedByWindows()
            },
        )
    }
}

/**
 * 設定の中のページ。
 *
 * 1枚に全部並べていたものを割った。縦に長い設定画面は、
 * **どこに何があるかを覚えている人にしか使えない**。
 *
 * Android 版は1枚ずつ潜る形だが、こちらは窓が横に広いので左右に割る ──
 * 潜らせると、いま何を見ているのかが分かりにくくなる。
 */
/**
 * ルールを変えにくくする関門。
 *
 * ここに1つでも置くと、ルールの**変更と削除**が申請になり、全部通るまで効かなくなる。
 * 新しく作るぶんは素通り ── 縛りを増やすほうを渋らせる理由が無いし、渋らせると
 * 「まず緩めてから作り直す」を覚えてしまう。
 *
 * Windows で出すのは、Windows だけで通せる3つに絞ってある。パスワードとミニゲームは
 * 通す手立てがこちらに無いので置かない ── 通せない関門は、出口の無い檻になる。
 */
@Composable
private fun GatesSection() {
    val settings by DesktopRuntime.settings.collectAsState()
    val gates = settings.gates

    fun replace(next: List<Gate>) = DesktopRuntime.updateSettings { it.copy(gates = next) }
    fun without(key: String) = gates.filterNot { it.key == key }

    val cooldown = gates.filterIsInstance<Gate.Cooldown>().firstOrNull()
    val reason = gates.filterIsInstance<Gate.WriteReason>().firstOrNull()
    val window = gates.filterIsInstance<Gate.TimeWindow>().firstOrNull()

    Text("関門", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(4.dp))
    Text(
        "ルールの変更と削除を通しにくくします。1つでも置くと、変えるにも消すにも" +
            "「変更」タブで関門を通すことになります。新しく作るぶんは素通りです。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        "これが無いと、開きたくなった瞬間にルールを消せます。2秒で外せる縛りは縛りになりません。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
    )

    Spacer(Modifier.height(16.dp))
    HorizontalDivider()
    Spacer(Modifier.height(16.dp))

    // ---- 待つ ----
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("起票から待つ", style = MaterialTheme.typography.bodyLarge)
            Text(
                "いちばん効きます。衝動はたいてい、この時間を越えられません。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = cooldown != null,
            onCheckedChange = { on ->
                replace(if (on) without("cooldown") + Gate.Cooldown() else without("cooldown"))
            },
        )
    }
    if (cooldown != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = {
                    replace(without("cooldown") + Gate.Cooldown((cooldown.minutes - 30).coerceAtLeast(5)))
                },
                enabled = cooldown.minutes > 5,
            ) { Text("−30分") }
            Text(cooldown.describe(), style = MaterialTheme.typography.bodyMedium)
            TextButton(
                onClick = { replace(without("cooldown") + Gate.Cooldown(cooldown.minutes + 30)) },
            ) { Text("+30分") }
        }
    }

    Spacer(Modifier.height(16.dp))

    // ---- 理由を書く ----
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("理由を書かせる", style = MaterialTheme.typography.bodyLarge)
            Text(
                "書いた文は履歴に残ります。あとで読み返すと、だいたい大した理由ではありません。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = reason != null,
            onCheckedChange = { on ->
                replace(if (on) without("writeReason") + Gate.WriteReason() else without("writeReason"))
            },
        )
    }
    if (reason != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = {
                    replace(without("writeReason") + Gate.WriteReason((reason.minLength - 10).coerceAtLeast(10)))
                },
                enabled = reason.minLength > 10,
            ) { Text("−10字") }
            Text("${reason.minLength} 文字以上", style = MaterialTheme.typography.bodyMedium)
            TextButton(
                onClick = { replace(without("writeReason") + Gate.WriteReason(reason.minLength + 10)) },
            ) { Text("+10字") }
        }
    }

    Spacer(Modifier.height(16.dp))

    // ---- 時間帯 ----
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("決まった時間帯だけ変えられる", style = MaterialTheme.typography.bodyLarge)
            Text(
                "既定は 8:00〜21:00。夜中に緩めるのを塞ぐためのものです。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = window != null,
            onCheckedChange = { on ->
                replace(if (on) without("timeWindow") + Gate.TimeWindow() else without("timeWindow"))
            },
        )
    }
    if (window != null) {
        Text(
            window.describe(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (gates.isEmpty()) {
        Spacer(Modifier.height(16.dp))
        Text(
            "いまは関門がありません。ルールの変更も削除も、その場で効きます。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

private enum class DesktopSettingsPage(val title: String, val summary: String) {
    Behaviour("動かしかた", "ブロックの強さと一時停止"),
    Startup("起動", "Windows と一緒に立ち上げる"),
    Gates("関門", "ルールを変えにくくする"),
    Focus("集中モード", "その場で手を止める"),
    Sync("端末間の同期", "スマホと同じルールを使う"),
    Reservation("予約", "使う時間を先に決めておく"),
    Bridge("ブラウザ拡張", "URL でも止めるための受け口"),
    Transfer("ルールの持ち出し", "書き出しと取り込み"),
    Points("ポイント", "押し切りの相場と使い道"),
    About("このアプリについて", "必ず止めないもの・版"),
}

@Composable
private fun SettingsTab() {
    var page by remember { mutableStateOf(DesktopSettingsPage.Behaviour) }

    Row(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .width(190.dp)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
        ) {
            DesktopSettingsPage.entries.forEach { entry ->
                SettingsNavRow(
                    title = entry.title,
                    selected = page == entry,
                    onClick = { page = entry },
                )
            }
        }

        VerticalDivider()

        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text(page.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(
                page.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            when (page) {
                DesktopSettingsPage.Behaviour -> BehaviourSection()
                DesktopSettingsPage.Startup -> LaunchAtLoginSection()
                DesktopSettingsPage.Gates -> GatesSection()
                DesktopSettingsPage.Focus -> FocusSection()
                DesktopSettingsPage.Sync -> SyncSection()
                DesktopSettingsPage.Reservation -> ReservationSection()
                DesktopSettingsPage.Bridge -> BrowserBridgeSection()
                DesktopSettingsPage.Transfer -> RuleTransferSection()
                DesktopSettingsPage.Points -> PointsSection()
                DesktopSettingsPage.About -> AboutSection()
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SettingsNavRow(title: String, selected: Boolean, onClick: () -> Unit) {
    val background = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(MaterialTheme.shapes.small)
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

@Composable
private fun BehaviourSection() {
    val settings by DesktopRuntime.settings.collectAsState()

    Text(
        "Windows には Android のような統一された止め方がありません。どこまでやるか選べます。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))

    // プロセスの一時停止は開発者向けの奥に隠す。編集中のものを壊しうるうえ、
    // 選ぶとオーバーレイが出なくなるので「効いていない」と見える
    BlockStrength.entries
        .filter { settings.developerMode || it != BlockStrength.SUSPEND }
        .forEach { strength ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.Top,
            ) {
                RadioButton(
                    selected = settings.blockStrength == strength,
                    onClick = { DesktopRuntime.updateSettings { it.copy(blockStrength = strength) } },
                )
                Column(Modifier.padding(start = 4.dp)) {
                    Text(strength.displayName, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        strength.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

    Spacer(Modifier.height(20.dp))
    HorizontalDivider()
    Spacer(Modifier.height(20.dp))

    if (settings.developerMode) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "一時停止(" + DesktopRuntime.PAUSE_MINUTES + "分)",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    // 真偽値だと再起動をまたいで残り、自動で立ち上げた朝に
                    // 「一時停止中」で待っていることになる。必ず明けるようにした
                    if (settings.pausedUntilSec > 0L) {
                        "あと" + settings.pauseRemainingMinutes(System.currentTimeMillis() / 1000) +
                            "分で勝手に戻ります。"
                    } else {
                        "何も止めなくなります。" + DesktopRuntime.PAUSE_MINUTES +
                            "分で勝手に戻るので、切ったまま忘れることはありません。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = settings.pausedUntilSec > 0L,
                onCheckedChange = { DesktopRuntime.pauseFor(if (it) DesktopRuntime.PAUSE_MINUTES else 0) },
            )
        }
        Spacer(Modifier.height(20.dp))
    }

    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text("開発者向けの操作を出す", style = MaterialTheme.typography.bodyLarge)
            Text(
                "一時停止と、プロセスの一時停止。**縛りを丸ごと無効にできるもの**なので、" +
                    "普段は隠してあります ── 手の届くところにあると、詰まったときにまず" +
                    "それを押してしまうので。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = settings.developerMode,
            onCheckedChange = { on ->
                DesktopRuntime.updateSettings { s ->
                    // 隠すときは、隠れる設定に居座らせない。SUSPEND のまま隠すと
                    // 「オーバーレイが出ない」まま直せなくなる。一時停止も同じ
                    if (on) {
                        s.copy(developerMode = true)
                    } else {
                        s.copy(
                            developerMode = false,
                            pausedUntilSec = 0L,
                            blockStrength = if (s.blockStrength == BlockStrength.SUSPEND) {
                                BlockStrength.MINIMIZE
                            } else {
                                s.blockStrength
                            },
                        )
                    }
                }
            },
        )
    }
}

@Composable
private fun PointsSection() {
    val settings by DesktopRuntime.settings.collectAsState()
    PointPolicySection(
        policy = settings.pointPolicy,
        onChange = { policy -> DesktopRuntime.updateSettings { it.copy(pointPolicy = policy) } },
    )
}

@Composable
private fun AboutSection() {
    Text("必ず止めないもの", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(4.dp))
    Text(
        "ルールより強く、ここからも外せません。タスクマネージャを入れてあるのは、" +
            "ドパチル自身を必ず止められるようにしておくためです。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        ProtectedProcesses.all().sorted().joinToString("、"),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(20.dp))
    HorizontalDivider()
    Spacer(Modifier.height(12.dp))
    UpdateSection()
    Spacer(Modifier.height(16.dp))
    Text(
        "ドパチル " + desktopVersion(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 新しい版を入れ替える。
 *
 * ## なぜ押したときだけ聞きに行くのか
 *
 * このアプリは**取り締まりがネットに依存していない**ことを前提にしていて、
 * そこは「機内モードにしても何も変わらない」という形で守りたい。
 * 更新のために常時の通信を足すと、その形が崩れます。
 *
 * ## 落とすのと入れるのを分ける
 *
 * 落とし終えてからインストーラを開きます。まとめて一発にすると、
 * 回線が細いときに何も起きていないように見える時間が生まれます。
 */
@Composable
private fun UpdateSection() {
    val state by DesktopUpdater.state.collectAsState()

    Text("アップデート", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(4.dp))
    Text(
        "押したときだけサーバーに聞きます。ふだんは通信しません。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))

    when (val current = state) {
        is DesktopUpdater.State.Idle ->
            Button(onClick = { DesktopUpdater.check() }) { Text("アップデートを確認") }

        is DesktopUpdater.State.Checking ->
            Text("聞いています…", style = MaterialTheme.typography.bodyMedium)

        is DesktopUpdater.State.UpToDate -> {
            Text("いまの " + current.current + " が最新です。", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { DesktopUpdater.check() }) { Text("もう一度確認") }
        }

        is DesktopUpdater.State.Available -> {
            Text(
                current.build.version + " が出ています",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            val size = current.build.sizeLabel()
            if (size.isNotBlank()) {
                Text(
                    size,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (current.notes.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    current.notes.trim().lines().take(8).joinToString(System.lineSeparator()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { DesktopUpdater.download(current.build) }) { Text("ダウンロード") }
                TextButton(onClick = { DesktopUpdater.reset() }) { Text("あとで") }
            }
        }

        is DesktopUpdater.State.Downloading -> {
            Text(
                current.build.version + " を落としています " + current.percent + "%",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { current.percent / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { DesktopUpdater.cancel() }) { Text("やめる") }
        }

        is DesktopUpdater.State.Ready -> {
            Text(
                current.build.version + " を落とし終わりました",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                // 自分で自分を閉じてから渡すと、断ったときに
                // 「閉じただけで何も入っていない」になる
                "インストーラが開きます。ドパチルは自分で閉じてください。" +
                    "ルールも記録も残ります。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { DesktopUpdater.openInstaller(current.file) }) {
                    Text("インストーラを開く")
                }
                TextButton(onClick = { DesktopUpdater.cleanUp(); DesktopUpdater.reset() }) {
                    Text("捨てる")
                }
            }
        }

        is DesktopUpdater.State.Failed -> {
            Text(
                current.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { DesktopUpdater.check() }) { Text("もう一度") }
        }
    }
}

/**
 * 動いている版。
 *
 * jpackage が起動時に渡してくる値を読みます。gradle の値を焼き込むより、
 * **いま動いているものから読む**ほうが嘘になりません。
 * `gradlew :desktop:run` で直接動かしたときは渡ってこないので、そのときは
 * 「開発中」と出ます ── 版を名乗れないことと、間違った版を名乗ることは違います。
 */
private fun desktopVersion(): String =
    System.getProperty("jpackage.app-version")?.takeIf { it.isNotBlank() } ?: "(開発中)"

/**
 * ポイントの使い道と相場。
 *
 * 使い道を2つとも切ると「増減を数えるだけ」になる。切っても加点・減点は
 * 記録し続けるので、あとから使い道を入れたときに残高がゼロから始まらない。
 */
@Composable
private fun PointPolicySection(policy: PointPolicy, onChange: (PointPolicy) -> Unit) {
    Text("ポイント", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))
    SettingSwitch(
        "ポイントを使う",
        "ルールを守ると貯まり、破ると減ります。切っても記録は残ります。",
        policy.enabled,
    ) { onChange(policy.copy(enabled = it)) }

    if (!policy.enabled) return

    Spacer(Modifier.height(12.dp))
    SettingSwitch(
        "押し切りに代金をとる",
        "ブロックを押し切るのにポイントが要ります。足りなければ押し切れません。",
        policy.chargeOverride,
    ) { onChange(policy.copy(chargeOverride = it)) }

    Spacer(Modifier.height(12.dp))
    SettingSwitch(
        "解禁券を買えるようにする",
        "「今日」の画面から買えます。買うと、その時間だけ制限が全部止まります。",
        policy.passEnabled,
    ) { onChange(policy.copy(passEnabled = it)) }

    if (policy.passEnabled) {
        PolicyNumber("解禁券の値段", policy.passCost, 1, 500, "pt") {
            onChange(policy.copy(passCost = it))
        }
        PolicyNumber("解禁券1枚で止まる時間", policy.passMinutes, 5, 180, "分") {
            onChange(policy.copy(passMinutes = it))
        }
    }

    Spacer(Modifier.height(12.dp))
    Text(
        "相場(ルール側で「設定どおり」にしてあるぶんに効きます)",
        style = MaterialTheme.typography.labelMedium,
    )
    PolicyNumber("破ったとき", policy.defaultBreakPoints, -200, 0, "pt") {
        onChange(policy.copy(defaultBreakPoints = it))
    }
    PolicyNumber("引き返したとき", policy.defaultKeepPoints, 0, 50, "pt") {
        onChange(policy.copy(defaultKeepPoints = it))
    }
    PolicyNumber("これ以上は減らない下限", policy.floor, -1000, 0, "pt") {
        onChange(policy.copy(floor = it))
    }
    Text(
        "下限があるのは、際限なく沈むと「もうどうにでもなれ」に振り切ってしまうからです。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SettingSwitch(
    title: String,
    help: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                help,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun PolicyNumber(
    label: String,
    value: Int,
    min: Int,
    max: Int,
    suffix: String,
    onChange: (Int) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        NumberStepper(value = value, min = min, max = max, suffix = suffix, onChange = onChange)
    }
}

// ----------------------------------------------------------------------

@Composable
internal fun AppPickerDialog(
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // 入っているアプリはスタートメニューから、取りこぼしはいま動いているものから。
    // どちらか一方だけだと必ず足りない ── スタートメニューに出ないアプリもあれば、
    // いま閉じているアプリもある
    var installed by remember { mutableStateOf(emptyList<InstalledApps.Entry>()) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        // 何百個も .lnk を開くので、画面を止めない
        installed = withContext(Dispatchers.IO) { InstalledApps.all() }
        loading = false
    }
    val running = remember { RunningApps.visible() }
    var query by remember { mutableStateOf("") }
    var manual by remember { mutableStateOf("") }

    // 動いているものを先に、次に入っているもの。選択済みは必ず残す
    val rows = remember(installed, running, selected) {
        val byProcess = LinkedHashMap<String, String>()
        running.forEach { byProcess.putIfAbsent(it.processName, it.label) }
        installed.forEach { byProcess.putIfAbsent(it.processName, it.label) }
        selected.forEach { byProcess.putIfAbsent(it, it) }
        byProcess.map { (process, label) -> process to label }
    }
    val shown = remember(rows, query) {
        val q = query.trim().lowercase()
        if (q.isBlank()) rows else rows.filter {
            it.first.contains(q) || it.second.lowercase().contains(q)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("対象アプリ") },
        text = {
            Column {
                Text(
                    "スタートメニューに出るアプリと、いま動いているアプリから選べます。" +
                        "どちらにも無いものは、実行ファイル名を直接足してください。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))

                androidx.compose.material3.OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("探す") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (loading) "${selected.size} 個選択中 ・ 一覧を読んでいます…"
                    else "${selected.size} 個選択中 ・ ${shown.size} 件",
                    style = MaterialTheme.typography.labelSmall,
                )
                Spacer(Modifier.height(4.dp))

                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(shown, key = { it.first }) { (process, label) ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = process in selected,
                                onCheckedChange = { onToggle(process) },
                            )
                            Column(Modifier.weight(1f)) {
                                Text(label, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    process + if (running.any { it.processName == process }) " ・ 動作中" else "",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.OutlinedTextField(
                        value = manual,
                        onValueChange = { manual = it },
                        label = { Text("実行ファイル名 (例: notepad.exe)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        enabled = manual.isNotBlank(),
                        onClick = {
                            onToggle(manual.trim().lowercase())
                            manual = ""
                        },
                    ) { Text("足す") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
        dismissButton = {
            // 入れ直した直後は一覧に出ない。作り直す道を残しておく
            TextButton(onClick = {
                loading = true
                installed = emptyList()
                query = ""
            }) { Text("読み直す") }
        },
    )
}

private fun formatMinutes(minutes: Int): String {
    if (minutes < 60) return "${minutes}分"
    val h = minutes / 60
    val m = minutes % 60
    return if (m == 0) "${h}時間" else "${h}時間${m}分"
}

@Composable
private fun ReservationSection() {
    val reservations by DesktopRuntime.reservations.collectAsState()
    val settings by DesktopRuntime.settings.collectAsState()
    val leadMinutes = settings.reservationLeadMinutes

    // 手でアプリを選ばせない。予約で開くのは条件に「予約した時間の外」を
    // 持つルールだけで、それ以外を選べると「取ったのに開かない」が起きる
    val ruleFile by DesktopRuntime.ruleFile.collectAsState()
    val me = DesktopRuntime.myDeviceId()
    val unlockable = remember(ruleFile, me) {
        ReservationRules.unlockableOn(ruleFile.rules, me)
    }
    var pickedRuleUid by remember { mutableStateOf("") }
    val picked = remember(unlockable, pickedRuleUid) {
        unlockable.firstOrNull { it.uid == pickedRuleUid }
    }
    var startSec by remember { mutableStateOf(0L) }
    var durationMinutes by remember { mutableStateOf(30) }
    var refused by remember { mutableStateOf(false) }

    val now = System.currentTimeMillis() / 1000
    val earliest = ((now + leadMinutes * 60L + 1799L) / 1800L) * 1800L
    if (startSec < earliest) startSec = earliest

    Text(
        "先に「この時間だけ使う」と決めておく枠です。開くのは条件に「予約した時間の外」を" +
            "持つルールだけ。いまから" + describeLead(leadMinutes) +
            "より手前には取れません ── 少し先にしか置けないから、冷静に決められます。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(16.dp))
    Text("いつから取れるようにするか", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            onClick = {
                DesktopRuntime.updateSettings { it.copy(reservationLeadMinutes = (leadMinutes - 30).coerceAtLeast(0)) }
            },
            enabled = leadMinutes > 0,
        ) { Text("−30分") }
        Text(describeLead(leadMinutes) + "から", style = MaterialTheme.typography.bodyLarge)
        TextButton(
            onClick = {
                DesktopRuntime.updateSettings { it.copy(reservationLeadMinutes = leadMinutes + 30) }
            },
        ) { Text("+30分") }
    }

    Spacer(Modifier.height(16.dp))
    HorizontalDivider()
    Spacer(Modifier.height(16.dp))

    Text("新しく予約する", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))
    Text("どのルールに穴を開けるか", style = MaterialTheme.typography.labelLarge)
    if (unlockable.isEmpty()) {
        Text(
            // 「塞ぐルール」ではなく「予約の条件を持つルール」。ここを曖昧にすると、
            // 塞ぐだけのルールを作って「予約が効かない」と思うことになる
            "この端末には、予約で開くルールがありません。" +
                "ルールを1つ作って、条件に「予約した時間の外」を入れてください。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            unlockable.forEach { rule ->
                FilterChip(
                    selected = rule.uid == pickedRuleUid,
                    onClick = { pickedRuleUid = if (rule.uid == pickedRuleUid) "" else rule.uid },
                    label = { Text(rule.name) },
                )
            }
        }
        picked?.let {
            Text(
                "通るのは " + it.target.packages.joinToString("・").ifBlank { "(対象なし)" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    Spacer(Modifier.height(12.dp))
    Text("いつから", style = MaterialTheme.typography.labelLarge)
    Text(formatStart(startSec), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = { startSec = (startSec - 1800L).coerceAtLeast(earliest) },
            enabled = startSec - 1800L >= earliest,
        ) { Text("−30分") }
        OutlinedButton(onClick = { startSec += 1800L }) { Text("+30分") }
        OutlinedButton(onClick = { startSec += 86400L }) { Text("+1日") }
    }

    Spacer(Modifier.height(12.dp))
    Text("どれくらい", style = MaterialTheme.typography.labelLarge)
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            onClick = { durationMinutes = (durationMinutes - 5).coerceAtLeast(5) },
            enabled = durationMinutes > 5,
        ) { Text("−") }
        Text(durationMinutes.toString() + " 分", style = MaterialTheme.typography.titleMedium)
        TextButton(
            onClick = { durationMinutes = (durationMinutes + 5).coerceAtMost(8 * 60) },
            enabled = durationMinutes < 8 * 60,
        ) { Text("+") }
    }

    Spacer(Modifier.height(12.dp))
    Button(
        onClick = {
            val rule = picked ?: return@Button
            val booked = DesktopRuntime.book(
                target = rule.target,
                startEpochSec = startSec,
                endEpochSec = startSec + durationMinutes * 60L,
                minLeadMinutes = leadMinutes,
            )
            refused = booked == null
            if (booked != null) pickedRuleUid = ""
        },
        enabled = picked != null,
    ) { Text("予約する") }
    if (refused) {
        Text(
            "その時刻には取れません。もう少し先にしてください。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }

    Spacer(Modifier.height(20.dp))
    HorizontalDivider()
    Spacer(Modifier.height(16.dp))
    Text("これからの予約", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))
    if (reservations.isEmpty()) {
        Text("まだありません。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        reservations.sortedBy { it.startEpochSec }.forEach { reservation ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        reservation.target.packages.joinToString("、").ifBlank { "対象なし" },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        formatReservationRange(reservation.startEpochSec, reservation.endEpochSec) +
                            if (reservation.coversAt(System.currentTimeMillis() / 1000)) "  ・いま使えます" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { DesktopRuntime.cancelReservation(reservation.uid) }) { Text("取り消す") }
            }
        }
    }

}

private val reservationDayFormat: java.time.format.DateTimeFormatter =
    java.time.format.DateTimeFormatter.ofPattern("M/d(E) HH:mm")
private val reservationTimeFormat: java.time.format.DateTimeFormatter =
    java.time.format.DateTimeFormatter.ofPattern("HH:mm")

private fun formatStart(sec: Long): String =
    java.time.Instant.ofEpochSecond(sec).atZone(java.time.ZoneId.systemDefault()).format(reservationDayFormat)

private fun formatReservationRange(startSec: Long, endSec: Long): String {
    val start = java.time.Instant.ofEpochSecond(startSec).atZone(java.time.ZoneId.systemDefault())
    val end = java.time.Instant.ofEpochSecond(endSec).atZone(java.time.ZoneId.systemDefault())
    return start.format(reservationDayFormat) + "〜" + end.format(reservationTimeFormat)
}

private fun describeLead(minutes: Int): String = when {
    minutes % 60 == 0 && minutes >= 60 -> (minutes / 60).toString() + "時間後"
    minutes == 0 -> "すぐ"
    else -> minutes.toString() + "分後"
}
