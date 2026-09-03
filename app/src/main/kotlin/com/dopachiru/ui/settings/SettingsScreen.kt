package com.dopachiru.ui.settings

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dopachiru.core.gate.Gate
import com.dopachiru.core.time.ALL_DAYS
import com.dopachiru.core.time.formatMinuteOfDay
import com.dopachiru.data.CalendarReader
import com.dopachiru.data.SettingsStore
import androidx.compose.runtime.rememberCoroutineScope
import com.dopachiru.core.action.types.BlockAction
import com.dopachiru.core.model.Focus
import com.dopachiru.core.model.FocusSettings
import com.dopachiru.focus.FocusShortcutActivity
import com.dopachiru.ui.rules.AppPickerDialog
import com.dopachiru.ui.rules.InstalledApps
import androidx.compose.ui.text.input.VisualTransformation
import com.dopachiru.core.sync.SyncSettings
import com.dopachiru.data.SyncManager
import com.dopachiru.runtime.DopaRuntime
import com.dopachiru.service.DopaAccessibilityService
import com.dopachiru.core.DopaFeatures
import com.dopachiru.core.points.PointPolicy
import com.dopachiru.ui.common.HourMinutePicker
import com.dopachiru.ui.rules.DayOfWeekPicker
import com.dopachiru.ui.rules.NumberStepper
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    val gates: StateFlow<List<Gate>> = DopaRuntime.settings.gates
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsStore.DEFAULT_GATES)

    val hasPassword: StateFlow<Boolean> = DopaRuntime.settings.hasPassword
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val blockHomeScreen: StateFlow<Boolean> = DopaRuntime.settings.blockHomeScreen
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val showOnUnlock: StateFlow<Boolean> = DopaRuntime.settings.showOnUnlock
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val unlockMessage: StateFlow<String> = DopaRuntime.settings.unlockMessage
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    val selfDefense: StateFlow<Boolean> = DopaRuntime.settings.selfDefense
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val batterySaver: StateFlow<Boolean> = DopaRuntime.settings.batterySaver
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setBatterySaver(enabled: Boolean) {
        viewModelScope.launch { DopaRuntime.settings.setBatterySaver(enabled) }
    }

    val studyPrepMinutes: StateFlow<Int> = DopaRuntime.settings.studyPrepMinutes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 30)

    fun setStudyPrepMinutes(minutes: Int) {
        viewModelScope.launch { DopaRuntime.settings.setStudyPrepMinutes(minutes) }
    }

    val pointPolicy: StateFlow<PointPolicy> = DopaRuntime.settings.pointPolicy
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PointPolicy.DEFAULT)

    fun setPointPolicy(policy: PointPolicy) {
        viewModelScope.launch { DopaRuntime.settings.setPointPolicy(policy) }
    }

    /** 有効化と、設定済みゲートの差し替えを兼ねる(キーが同じものを置き換える)。 */
    fun putGate(gate: Gate, enabled: Boolean) {
        viewModelScope.launch {
            val rest = gates.value.filterNot { it.key == gate.key }
            DopaRuntime.settings.setGates(if (enabled) rest + gate else rest)
        }
    }

    fun setPassword(raw: String) {
        viewModelScope.launch { DopaRuntime.settings.setPassword(raw) }
    }

    fun setBlockHomeScreen(enabled: Boolean) {
        viewModelScope.launch { DopaRuntime.settings.setBlockHomeScreen(enabled) }
    }

    fun setShowOnUnlock(enabled: Boolean) {
        viewModelScope.launch { DopaRuntime.settings.setShowOnUnlock(enabled) }
    }

    fun setUnlockMessage(message: String) {
        viewModelScope.launch { DopaRuntime.settings.setUnlockMessage(message) }
    }

    fun setSelfDefense(enabled: Boolean) {
        viewModelScope.launch { DopaRuntime.settings.setSelfDefense(enabled) }
    }

    fun refreshCalendar() {
        DopaRuntime.refreshCalendarNow()
    }

    fun upcomingEvents(): List<CalendarReader.Event> = DopaRuntime.calendarReader.upcoming(limit = 6)

    fun calendarGranted(): Boolean = DopaRuntime.calendarReader.hasPermission()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen(
    onOpenDevTools: () -> Unit = {},
    viewModel: SettingsViewModel = viewModel(),
) {
    val context = LocalContext.current
    val gates by viewModel.gates.collectAsState()
    val hasPassword by viewModel.hasPassword.collectAsState()
    val blockHome by viewModel.blockHomeScreen.collectAsState()
    val showOnUnlock by viewModel.showOnUnlock.collectAsState()
    val unlockMessage by viewModel.unlockMessage.collectAsState()
    val selfDefense by viewModel.selfDefense.collectAsState()
    val batterySaver by viewModel.batterySaver.collectAsState()
    val prepMinutes by viewModel.studyPrepMinutes.collectAsState()
    val pointPolicy by viewModel.pointPolicy.collectAsState()
    var showDevDialog by remember { mutableStateOf(false) }

    // 設定アプリから戻ってきたら権限の状態を見直す
    var refreshKey by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshKey++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val accessibilityOn = remember(refreshKey) { isAccessibilityEnabled(context) }
    val batteryExempt = remember(refreshKey) { isIgnoringBatteryOptimizations(context) }
    var calendarGranted by remember(refreshKey) { mutableStateOf(viewModel.calendarGranted()) }

    val calendarPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        calendarGranted = granted
        if (granted) viewModel.refreshCalendar()
    }

    var showPasswordDialog by remember { mutableStateOf(false) }
    var showMessageDialog by remember { mutableStateOf(false) }
    var editingTimeWindow by remember { mutableStateOf<Gate.TimeWindow?>(null) }
    var editingCalendarWindow by remember { mutableStateOf<Gate.CalendarWindow?>(null) }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SectionTitle("動作に必要な設定")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    CheckRow(
                        label = "ユーザー補助を有効にする",
                        done = accessibilityOn,
                        detail = "これが入っていないと何も検知できません",
                        onAction = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    )
                    HorizontalDivider(Modifier.padding(vertical = 10.dp))
                    CheckRow(
                        label = "電池の最適化から除外する",
                        done = batteryExempt,
                        detail = "常駐が落とされにくくなります",
                        onAction = { context.requestBatteryExemption() },
                    )
                    HorizontalDivider(Modifier.padding(vertical = 10.dp))
                    Text(
                        "ユーザー補助のスイッチが灰色で押せない場合",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Android 13 以降、ストア以外から入れたアプリはユーザー補助を有効にできません。" +
                            "一度スイッチを押してブロックされたあと、アプリ情報の右上「⋮」から" +
                            "「制限された設定を許可」を選ぶと解除できます。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { context.openAppDetails() }) {
                        Text("アプリ情報を開く")
                    }
                }
            }
        }

        item {
            SectionTitle("カレンダー連携")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    if (DopaFeatures.CALENDAR_ENABLED) {
                        Text(
                            "端末に同期済みのカレンダーを読みます。Google カレンダーを端末で同期していれば、" +
                                "そのまま使えます。ログインも API キーも要りません。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        CheckRow(
                            label = "カレンダーの読み取りを許可",
                            done = calendarGranted,
                            detail = "予定を条件やゲートに使えるようになります",
                            onAction = {
                                calendarPermission.launch(Manifest.permission.READ_CALENDAR)
                            },
                        )

                        if (calendarGranted) {
                            val events =
                                remember(refreshKey, calendarGranted) { viewModel.upcomingEvents() }
                            Spacer(Modifier.height(12.dp))
                            Text("これからの予定", style = MaterialTheme.typography.labelMedium)
                            Spacer(Modifier.height(4.dp))
                            if (events.isEmpty()) {
                                Text(
                                    "直近に予定はありません。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                events.forEach { event ->
                                    Text(
                                        "${formatTime(event.startMs)}  ${event.title}",
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(vertical = 1.dp),
                                    )
                                }
                            }
                        }
                    } else {
                        Text("凍結中", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "学習予定はスキマスから直接届くようになったので、カレンダーは読んでいません。" +
                                "読み取り権限そのものを外してあります。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "カレンダーを使っていたルールは残っていますが、凍結中は成立しません。" +
                                "「予定が入っているあいだだけ変更できる」の関門は、開いたままになります。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        item {
            SectionTitle("変更をしにくくする")
            Text(
                "ルールを緩める変更にだけ、ここで選んだ関門がかかります。厳しくする変更は素通しです。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    GateRow(
                        label = "考える時間を置く(30分)",
                        gate = Gate.Cooldown(30),
                        gates = gates,
                        onToggle = viewModel::putGate,
                    )
                    GateRow(
                        label = "理由を書かせる(30文字)",
                        gate = Gate.WriteReason(30),
                        gates = gates,
                        onToggle = viewModel::putGate,
                    )
                    GateRow(
                        label = "ミニゲームを解かせる(5問)",
                        gate = Gate.MiniGame("arithmetic", 5),
                        gates = gates,
                        onToggle = viewModel::putGate,
                    )
                    GateRow(
                        label = "パスワードを求める",
                        gate = Gate.Password,
                        gates = gates,
                        enabled = hasPassword,
                        disabledHint = "先にパスワードを設定してください",
                        onToggle = viewModel::putGate,
                    )

                    val timeWindow = gates.filterIsInstance<Gate.TimeWindow>().firstOrNull()
                    GateRow(
                        label = "変更できる曜日と時刻を絞る",
                        gate = timeWindow ?: Gate.TimeWindow(),
                        gates = gates,
                        currentDescription = timeWindow?.describe(),
                        onConfigure = { editingTimeWindow = timeWindow ?: Gate.TimeWindow() },
                        onToggle = viewModel::putGate,
                    )

                    val calendarWindow = gates.filterIsInstance<Gate.CalendarWindow>().firstOrNull()
                    // 凍結中は新しく掛けさせない。すでに掛けてあるものは、
                    // 外せるように行だけ残す(凍結中は開いたままなので実害は無いが、
                    // 「掛けたはずの関門が効いていない」ことは見えていたほうがよい)
                    if (DopaFeatures.CALENDAR_ENABLED || calendarWindow != null) {
                        GateRow(
                            label = "カレンダーの予定中だけ変更できる",
                            gate = calendarWindow ?: Gate.CalendarWindow(),
                            gates = gates,
                            enabled = DopaFeatures.CALENDAR_ENABLED && calendarGranted,
                            disabledHint = if (DopaFeatures.CALENDAR_ENABLED) {
                                "先にカレンダーの読み取りを許可してください"
                            } else {
                                "カレンダー連携は凍結中。この関門はいま開いたままです"
                            },
                            currentDescription = calendarWindow?.describe(),
                            onConfigure = {
                                editingCalendarWindow = calendarWindow ?: Gate.CalendarWindow()
                            },
                            onToggle = viewModel::putGate,
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { showPasswordDialog = true }) {
                        Text(if (hasPassword) "パスワードを変更する" else "パスワードを設定する")
                    }
                }
            }
        }

        item {
            SectionTitle("自分から守る")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    SwitchRow(
                        "設定を触ろうとしたら引き止める",
                        selfDefense,
                        viewModel::setSelfDefense,
                    )
                    Text(
                        "設定アプリでドパチルのページを開いたとき、連続日数を見せて10秒だけ引き止めます。" +
                            "無効化そのものは必ずできます。自分で入れたアプリを自分で止められなくなるのは、" +
                            "抑止ではなく事故なので。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            SectionTitle("待ち受け・ホーム画面")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "ロック画面そのものには重ねられないため(OSが最上位で保護しているため)、" +
                            "ロックを解除した直後に問いかけを出します。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    SwitchRow("ロック解除の直後に問いかける", showOnUnlock, viewModel::setShowOnUnlock)
                    SwitchRow("ホーム画面に戻ったときにも出す", blockHome, viewModel::setBlockHomeScreen)
                    Spacer(Modifier.height(8.dp))
                    Text("問いかけの文", style = MaterialTheme.typography.labelMedium)
                    Text(
                        unlockMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                    OutlinedButton(onClick = { showMessageDialog = true }) { Text("変える") }
                }
            }
        }

        item {
            SectionTitle("学習予定")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("助走枠", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "予定が始まる何分前から「直前」とみなすか。" +
                            "予定の時間帯だけ塞いでも、始まる前に沈んで予定ごと潰れることは防げません。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (prepMinutes == 0) "使わない" else "${prepMinutes} 分前から",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Slider(
                        value = prepMinutes.toFloat(),
                        onValueChange = { viewModel.setStudyPrepMinutes(it.toInt()) },
                        valueRange = 0f..120f,
                        steps = 23,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "雛形の「予定の前に沈まない」と組み合わせて使います。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            SectionTitle("端末間の同期")
            SyncCard()
        }

        item {
            SectionTitle("集中モード")
            FocusCard()
        }

        item {
            SectionTitle("ポイント")
            PointPolicyCard(
                policy = pointPolicy,
                onChange = viewModel::setPointPolicy,
            )
        }

        item {
            SectionTitle("電池")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    SwitchRow("電池を優先する", batterySaver, viewModel::setBatterySaver)
                    Text(
                        "判定を見に来る間隔とカレンダーの読み直しを伸ばします。" +
                            "ブロックが最大で2分ほど遅れることがある代わりに、常駐の消費が減ります。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "オフのままでも、次のときは自動的に止まります。",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "・画面が消えているあいだ\n" +
                            "・前面のアプリを狙っているルールが1つも無いとき\n" +
                            "・条件が「この時刻までは変わらない」と答えられるあいだ",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "端末側の省電力モードに合わせて制限を強めたい場合は、" +
                            "ルールの条件に「省電力モード」を足してください。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            SectionTitle("このアプリについて")
            Text(
                "判定はすべて端末の中で行われます。同期を切っていれば、何も外に出ません。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "同期を入れたときに出るのは、ルール・タグ・アプリ名・1日ごとの使用時間だけです。" +
                    "どの瞬間に何を見ていたかは出ません。ゲートと変更リクエストも出ません。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            // ここを長押しすると開発ツールへの入口が出る。
            // ふだん目に入らないところに置いてあるだけで、隠しているわけではない。
            //
            // 文字が小さいので、当たり判定は padding で広げてある。
            // 隠す意図はないのに「押せなくて見つからない」のはただの不便。
            Text(
                "ドパチル " + versionLabel(context) + "(長押しで開発ツール)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .combinedClickable(
                        onClick = {},
                        onLongClick = { showDevDialog = true },
                    )
                    .padding(vertical = 12.dp, horizontal = 8.dp),
            )
            Spacer(Modifier.height(32.dp))
        }
    }

    if (showDevDialog) {
        DevCodeDialog(
            onUnlock = { showDevDialog = false; onOpenDevTools() },
            onDismiss = { showDevDialog = false },
        )
    }

    if (showPasswordDialog) {
        PasswordDialog(
            onSet = { viewModel.setPassword(it); showPasswordDialog = false },
            onDismiss = { showPasswordDialog = false },
        )
    }

    if (showMessageDialog) {
        TextDialog(
            title = "問いかけの文",
            initial = unlockMessage,
            multiline = true,
            onConfirm = { viewModel.setUnlockMessage(it); showMessageDialog = false },
            onDismiss = { showMessageDialog = false },
        )
    }

    editingTimeWindow?.let { gate ->
        TimeWindowDialog(
            initial = gate,
            onConfirm = { viewModel.putGate(it, true); editingTimeWindow = null },
            onDismiss = { editingTimeWindow = null },
        )
    }

    editingCalendarWindow?.let { gate ->
        TextDialog(
            title = "変更を許す予定名",
            initial = gate.keyword,
            multiline = false,
            help = "この語をタイトルに含む予定が入っているあいだだけ、設定を変更できます。例: #可変",
            onConfirm = {
                if (it.isNotBlank()) viewModel.putGate(Gate.CalendarWindow(it.trim()), true)
                editingCalendarWindow = null
            },
            onDismiss = { editingCalendarWindow = null },
        )
    }
}

// ------------------------------------------------------------------

/**
 * 入っている版。`0.8.0 (14)` の形。
 *
 * gradle の値を焼き込むのではなく、**入っているパッケージから読みます** ──
 * 焼き込むと、渡した APK と端末に入っているものが食い違ったときに
 * 画面が嘘をつきます。「どの版を入れたつもりか」ではなく
 * 「いま何が入っているか」が知りたいので。
 *
 * 括弧の中は versionCode。表向きの版が同じでも、作り直したものかは
 * こちらで見分けられます。
 */
private fun versionLabel(context: android.content.Context): String = runCatching {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    val code = androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(info)
    "${info.versionName} ($code)"
}.getOrDefault("")

/**
 * 開発ツールへの入口。
 *
 * 鍵をかけたいわけではなく、うっかり触って判定が狂うのを防ぐだけなので、
 * 合言葉は1つで十分。忘れても困らないように、ヒントは画面に書いてある。
 */
@Composable
private fun DevCodeDialog(onUnlock: () -> Unit, onDismiss: () -> Unit) {
    var code by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("開発ツール") },
        text = {
            Column {
                Text(
                    "ルールを試すために、時刻をずらしたり学習予定をでっちあげたりできます。" +
                        "ふだんは使いません。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text("合言葉") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "ヒント: このアプリの名前(ひらがな)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = code.trim() == DEV_CODE,
                onClick = onUnlock,
            ) { Text("開く") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("やめる") } },
    )
}

private const val DEV_CODE = "どぱちる"

/**
 * ポイントの使い道と相場。
 *
 * 使い道を2つとも切ると「増減を数えるだけ」になる。切っても加点・減点は
 * 記録し続けるので、あとから使い道を入れたときに残高がゼロから始まらない。
 */
@Composable
private fun PointPolicyCard(policy: PointPolicy, onChange: (PointPolicy) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            SwitchRow("ポイントを使う", policy.enabled) { onChange(policy.copy(enabled = it)) }
            Text(
                "ルールを守ると貯まり、破ると減ります。切っても記録は残ります。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (!policy.enabled) return@Column

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
            Text("使い道", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                if (policy.recordOnly) {
                    "どちらも切ってあるので、いまは増減を数えるだけです。"
                } else {
                    "貯めたポイントで、逃げ道を買えるようにします。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))
            SwitchRow("押し切りに代金をとる", policy.chargeOverride) {
                onChange(policy.copy(chargeOverride = it))
            }
            Text(
                "ブロックを押し切るのにポイントが要ります。足りなければ押し切れません。" +
                    "値段はルールごとの「破ったときのポイント」です。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))
            SwitchRow("解禁券を買えるようにする", policy.passEnabled) {
                onChange(policy.copy(passEnabled = it))
            }
            Text(
                "記録の画面から買えます。買うと、その時間だけ制限が全部止まります。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (policy.passEnabled) {
                Spacer(Modifier.height(8.dp))
                PolicyNumber("解禁券の値段", policy.passCost, 1, 500, "pt") {
                    onChange(policy.copy(passCost = it))
                }
                PolicyNumber("解禁券1枚で止まる時間", policy.passMinutes, 5, 180, "分") {
                    onChange(policy.copy(passMinutes = it))
                }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
            Text("相場", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "ルール側で「設定どおり」にしてあるぶんに効きます。個別に変えたルールはそのままです。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            PolicyNumber("破ったとき", policy.defaultBreakPoints, -200, 0, "pt") {
                onChange(policy.copy(defaultBreakPoints = it))
            }
            PolicyNumber("引き返したとき", policy.defaultKeepPoints, 0, 50, "pt") {
                onChange(policy.copy(defaultKeepPoints = it))
            }
            PolicyNumber("違反ゼロで一日終えた", policy.cleanDayPoints, 0, 200, "pt") {
                onChange(policy.copy(cleanDayPoints = it))
            }
            PolicyNumber("学習予定を完走した", policy.studyDonePoints, 0, 200, "pt") {
                onChange(policy.copy(studyDonePoints = it))
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
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        NumberStepper(value = value, min = min, max = max, suffix = suffix, onChange = onChange)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun CheckRow(
    label: String,
    done: Boolean,
    detail: String,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (done) "✓" else "・",
            style = MaterialTheme.typography.titleMedium,
            color = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(end = 10.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!done) {
            TextButton(onClick = onAction) { Text("許可する") }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun GateRow(
    label: String,
    gate: Gate,
    gates: List<Gate>,
    enabled: Boolean = true,
    disabledHint: String = "",
    currentDescription: String? = null,
    onConfigure: (() -> Unit)? = null,
    onToggle: (Gate, Boolean) -> Unit,
) {
    val on = gates.any { it.key == gate.key }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyMedium)
                if (!enabled && disabledHint.isNotBlank()) {
                    Text(
                        disabledHint,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (on && currentDescription != null) {
                    Text(
                        currentDescription,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (on && onConfigure != null) {
                TextButton(onClick = onConfigure) { Text("設定") }
            }
            Switch(
                checked = on,
                enabled = enabled,
                onCheckedChange = { onToggle(gate, it) },
            )
        }
    }
}

@Composable
private fun TimeWindowDialog(
    initial: Gate.TimeWindow,
    onConfirm: (Gate.TimeWindow) -> Unit,
    onDismiss: () -> Unit,
) {
    var days by remember { mutableStateOf(initial.days.ifEmpty { ALL_DAYS }) }
    var start by remember { mutableIntStateOf(initial.startMinuteOfDay) }
    var end by remember { mutableIntStateOf(initial.endMinuteOfDay) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("変更できる曜日と時刻") },
        text = {
            Column {
                Text("曜日", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                DayOfWeekPicker(selected = days, onChange = { days = it })

                Spacer(Modifier.height(16.dp))
                Text(
                    "開始  ${formatMinuteOfDay(start)}",
                    style = MaterialTheme.typography.labelMedium,
                )
                HourMinutePicker(
                    hour = start / 60,
                    minute = start % 60,
                    onChange = { h, m -> start = h * 60 + m },
                )

                Spacer(Modifier.height(12.dp))
                Text(
                    "終了  ${formatMinuteOfDay(end)}",
                    style = MaterialTheme.typography.labelMedium,
                )
                HourMinutePicker(
                    hour = end / 60,
                    minute = end % 60,
                    onChange = { h, m -> end = h * 60 + m },
                )

                if (start > end) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "日をまたぐ範囲として扱います。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(Gate.TimeWindow(start, end, days)) },
                enabled = days.isNotEmpty() && start != end,
            ) { Text("決める") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("やめる") } },
    )
}

@Composable
private fun TextDialog(
    title: String,
    initial: String,
    multiline: Boolean,
    help: String = "",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (help.isNotBlank()) {
                    Text(
                        help,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = !multiline,
                    minLines = if (multiline) 2 else 1,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text("決める") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("やめる") } },
    )
}

@Composable
private fun PasswordDialog(onSet: (String) -> Unit, onDismiss: () -> Unit) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    val matches = first.isNotBlank() && first == second

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("パスワード") },
        text = {
            Column {
                OutlinedTextField(
                    value = first,
                    onValueChange = { first = it },
                    label = { Text("新しいパスワード") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = second,
                    onValueChange = { second = it },
                    label = { Text("もう一度") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    isError = second.isNotEmpty() && !matches,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSet(first) }, enabled = matches) { Text("決める") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("やめる") } },
    )
}

// ------------------------------------------------------------------

private fun formatTime(epochMs: Long): String {
    val dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault())
    return dt.format(DateTimeFormatter.ofPattern("M/d HH:mm"))
}

private fun isAccessibilityEnabled(context: Context): Boolean {
    val expected = "${context.packageName}/${DopaAccessibilityService::class.java.name}"
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ) ?: return false
    return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val pm = context.getSystemService(PowerManager::class.java) ?: return false
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

private fun Context.requestBatteryExemption() {
    runCatching {
        startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:$packageName"))
        )
    }.onFailure {
        runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }
}

private fun Context.openAppDetails() {
    runCatching {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:$packageName"))
        )
    }
}

/**
 * 集中モードの既定値。
 *
 * ここで決めるのは「始めるときに何も考えずに済むように」であって、
 * 長さはダッシュボードでも毎回変えられる。
 */
@Composable
private fun FocusCard() {
    val context = LocalContext.current
    var settings by remember { mutableStateOf(DopaRuntime.focusSettings) }
    var showAllowPicker by remember { mutableStateOf(false) }
    var pinned by remember { mutableStateOf<Boolean?>(null) }
    val scope = rememberCoroutineScope()

    fun update(next: FocusSettings) {
        settings = next
        scope.launch { DopaRuntime.settings.setFocusSettings(next) }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "その場かぎりで手を止めたいとき用。時間が来れば勝手に解けます。" +
                    "電話・ホーム・設定は集中中も開いたままです。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            MinuteStepper(
                label = "はじめの長さ",
                minutes = settings.defaultMinutes,
                onChange = { update(settings.copy(defaultMinutes = it)) },
            )
            Text(
                "短めにしておくのを勧めます。足りなければ足せますが、" +
                    "長すぎたぶんを切り上げるにはポイントが要ります。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            MinuteStepper(
                label = "ホーム画面のボタンの長さ",
                minutes = settings.shortcutMinutes,
                onChange = { update(settings.copy(shortcutMinutes = it)) },
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = {
                pinned = FocusShortcutActivity.requestPin(context, settings.shortcutMinutes)
            }) {
                Text("ホーム画面に置く")
            }
            pinned?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    if (it) {
                        "ランチャーに頼みました。確認が出たら許可してください。"
                    } else {
                        "このランチャーは自動で置けません。アプリのアイコンを長押しすると出る候補から、自分でドラッグしてください。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            Text("集中中も開けたままにするアプリ", style = MaterialTheme.typography.bodyLarge)
            Text(
                "音楽や時計など。電話とホームはここに入れなくても開きます。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            if (settings.allowPackages.isEmpty()) {
                Text(
                    "いまは何も逃がしていません",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                settings.allowPackages.forEach { pkg ->
                    Text("・${InstalledApps.labelOf(context, pkg)}", style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { showAllowPicker = true }) { Text("選ぶ") }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            Text("切り上げるときの手間", style = MaterialTheme.typography.bodyLarge)
            Text(
                "ポイントを払う前に、これを通します。ふと押してやめてしまうのを防ぐためです。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    BlockAction.Effort.TAP to "そのまま",
                    BlockAction.Effort.HOLD to "3秒長押し",
                    BlockAction.Effort.TYPE to "言葉を打つ",
                ).forEach { (value, label) ->
                    FilterChip(
                        selected = settings.abortEffort == value,
                        onClick = { update(settings.copy(abortEffort = value)) },
                        label = { Text(label) },
                    )
                }
            }
        }
    }

    if (showAllowPicker) {
        AppPickerDialog(
            title = "集中中も開けるアプリ",
            selected = settings.allowPackages,
            onToggle = { pkg ->
                val next = if (pkg in settings.allowPackages) {
                    settings.allowPackages - pkg
                } else {
                    settings.allowPackages + pkg
                }
                update(settings.copy(allowPackages = next))
            },
            onDismiss = { showAllowPicker = false },
        )
    }
}

/** 5分刻みの長さ。 */
@Composable
private fun MinuteStepper(label: String, minutes: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        TextButton(
            onClick = { onChange((minutes - Focus.STEP_MINUTES).coerceAtLeast(Focus.MIN_MINUTES)) },
            enabled = minutes > Focus.MIN_MINUTES,
        ) { Text("−") }
        Text("$minutes 分", style = MaterialTheme.typography.titleMedium)
        TextButton(
            onClick = { onChange((minutes + Focus.STEP_MINUTES).coerceAtMost(Focus.MAX_MINUTES)) },
            enabled = minutes < Focus.MAX_MINUTES,
        ) { Text("+") }
    }
}

/**
 * 端末間の同期。
 *
 * **既定で切ってあります。** 住所と合言葉を入れて初めて動きます。
 *
 * 何が出るかを画面に書いてあるのは、**権限の一覧を見ても分からない**ためです。
 * INTERNET を持っているアプリが「何を送っているか」は、外からは確かめられません。
 */
@Composable
private fun SyncCard() {
    val scope = rememberCoroutineScope()
    val settings by DopaRuntime.settings.syncSettings.collectAsState(initial = SyncSettings())
    var url by remember(settings.baseUrl) { mutableStateOf(settings.baseUrl) }
    var token by remember(settings.token) { mutableStateOf(settings.token) }
    var device by remember(settings.deviceId) { mutableStateOf(settings.deviceId) }
    var showToken by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }

    fun save(transform: (SyncSettings) -> SyncSettings) {
        scope.launch { DopaRuntime.settings.setSyncSettings(transform(settings)) }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "別の端末とルールを揃えます。制限そのものはここに依存しません ── " +
                    "圏外でもサーバーが落ちていても、縛りは効いたままです。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

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
                supportingText = {
                    Text(
                        "英数字と記号だけ。日本語は通信の見出しに載らないので使えません。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = device,
                onValueChange = { device = it },
                label = { Text("この端末の名前") },
                placeholder = { Text("pixel") },
                singleLine = true,
                supportingText = {
                    Text(
                        "実績を端末ごとに分けて見るときの見出しになります。端末ごとに違う名前を。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = {
                    save { it.copy(baseUrl = url.trim(), token = token.trim(), deviceId = device.trim()) }
                    result = "保存しました"
                },
            ) { Text("保存する") }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("同期する", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (settings.isConfigured) {
                            "オンにすると、開いたときと保存したときに揃えます。"
                        } else {
                            "住所・合言葉・端末名を入れて保存すると使えます。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.enabled,
                    enabled = settings.isConfigured,
                    onCheckedChange = { on -> save { it.copy(enabled = on) } },
                )
            }

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    busy = true
                    result = "同期しています…"
                    scope.launch {
                        result = when (val out = DopaRuntime.sync.syncNow()) {
                            is SyncManager.Outcome.Done ->
                                "受け取り ${out.pulled} 件 / 送り ${out.pushed} 件"
                            is SyncManager.Outcome.NotConfigured -> "まだ設定できていません"
                            is SyncManager.Outcome.Failed -> out.message
                        }
                        busy = false
                    }
                },
                enabled = settings.isConfigured && settings.enabled && !busy,
            ) { Text("いま同期する") }

            if (result.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(result, style = MaterialTheme.typography.bodySmall)
            }
            if (settings.lastError.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "前回: ${settings.lastError}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "出るもの: ルール・タグ・アプリ名・1日ごとの使用時間\n" +
                    "出ないもの: 反省文以外の記録、どの瞬間に何を見ていたか、ゲート、変更リクエスト",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
