package com.dopachiru.desktop.ui

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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dopachiru.core.action.ActionRegistry
import com.dopachiru.core.model.ConditionTree
import com.dopachiru.core.model.Lockout
import com.dopachiru.core.model.Rule
import com.dopachiru.core.points.PointEvent
import com.dopachiru.core.points.PointPolicy
import com.dopachiru.core.preset.RulePreset
import com.dopachiru.core.preset.RulePresets
import com.dopachiru.desktop.DesktopRuntime
import com.dopachiru.desktop.platform.BlockStrength
import com.dopachiru.desktop.platform.ForegroundApp
import com.dopachiru.desktop.platform.ProtectedProcesses
import com.dopachiru.desktop.platform.RunningApps

@Composable
fun DesktopApp() = DopaTheme {
    var tab by remember { mutableIntStateOf(0) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Scaffold(
            topBar = {
                TabRow(selectedTabIndex = tab) {
                    listOf("ルール", "今日", "設定").forEachIndexed { index, title ->
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
                    1 -> TodayTab()
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

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        OutlinedButton(onClick = { pickingPreset = true }, modifier = Modifier.fillMaxWidth()) {
            Text("雛形から足す")
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
}

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
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { editing = true }) { Text("条件と罰を編集") }
                TextButton(onClick = { confirmDelete = true }) {
                    Text("削除", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (editing) {
        RuleEditorDialog(
            rule = rule,
            policy = settings.pointPolicy,
            onSave = {
                DesktopRuntime.updateRule(it)
                editing = false
            },
            onDismiss = { editing = false },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("「${rule.name}」を削除しますか?") },
            confirmButton = {
                TextButton(onClick = {
                    DesktopRuntime.removeRule(rule.id)
                    confirmDelete = false
                }) { Text("削除する") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("やめる") }
            },
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
    return "$head → $action$consequence"
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

@Composable
private fun SettingsTab() {
    val settings by DesktopRuntime.settings.collectAsState()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("ブロックのやり方", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            "Windows には Android のような統一された止め方がありません。どこまでやるか選べます。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        BlockStrength.entries.forEach { strength ->
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

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text("一時停止", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "何も止めなくなります。トレイからも切り替えられます。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = settings.paused,
                onCheckedChange = { DesktopRuntime.updateSettings { s -> s.copy(paused = it) } },
            )
        }

        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(20.dp))

        BrowserBridgeSection()

        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(20.dp))

        PointPolicySection(
            policy = settings.pointPolicy,
            onChange = { policy -> DesktopRuntime.updateSettings { it.copy(pointPolicy = policy) } },
        )

        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(20.dp))

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
    }
}

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
private fun AppPickerDialog(
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // Windows には「インストール済みアプリ」の統一された一覧が無いので、
    // いま窓を持って動いているものから選ばせる。
    val running = remember { RunningApps.visible() }
    var manual by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("対象アプリ") },
        text = {
            Column {
                Text(
                    "いま起動しているアプリから選びます。閉じているアプリは、" +
                        "実行ファイル名を直接足してください。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text("${selected.size} 個選択中", style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(4.dp))

                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(running, key = { it.processName }) { app ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = app.processName in selected,
                                onCheckedChange = { onToggle(app.processName) },
                            )
                            Column(Modifier.weight(1f)) {
                                Text(app.label, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    app.processName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    if (selected.any { s -> running.none { it.processName == s } }) {
                        item {
                            Spacer(Modifier.height(8.dp))
                            Text("いま動いていない選択中のもの", style = MaterialTheme.typography.labelSmall)
                        }
                        items(selected.filter { s -> running.none { it.processName == s } }) { process ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = true, onCheckedChange = { onToggle(process) })
                                Text(process, style = MaterialTheme.typography.bodyMedium)
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
                    Spacer(Modifier.height(8.dp))
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
    )
}

private fun formatMinutes(minutes: Int): String {
    if (minutes < 60) return "${minutes}分"
    val h = minutes / 60
    val m = minutes % 60
    return if (m == 0) "${h}時間" else "${h}時間${m}分"
}
