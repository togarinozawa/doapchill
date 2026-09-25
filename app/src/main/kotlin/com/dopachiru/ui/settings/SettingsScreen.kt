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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Stars
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.LaunchedEffect
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
import com.dopachiru.core.model.FocusScope
import com.dopachiru.core.model.FocusSettings
import com.dopachiru.core.model.FocusTemplate
import com.dopachiru.focus.FocusShortcutActivity
import com.dopachiru.ui.rules.AppPickerDialog
import com.dopachiru.ui.rules.InstalledApps
import com.dopachiru.core.sync.Joining
import com.dopachiru.core.sync.SyncDefaults
import com.dopachiru.core.sync.SyncSettings
import com.dopachiru.data.SyncManager
import com.dopachiru.runtime.DopaRuntime
import com.dopachiru.update.AppUpdater
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

/**
 * 設定の中のページ。
 *
 * 1枚に全部並べていたものを割った。縦に長い設定画面は、
 * **どこに何があるかを覚えている人にしか使えない** ── 探すのに
 * 全部読む必要があるということは、目当て以外の項目を毎回読まされるということでもある。
 *
 * 並び順は「無いと動かないもの → 毎日触るもの → めったに触らないもの」。
 * アルファベット順や機能の分類ではなく、**触る頻度**で並べてある。
 */
enum class SettingsPage(
    val id: String,
    val title: String,
    val summary: String,
    val icon: ImageVector,
) {
    Required("required", "動作に必要な設定", "ユーザー補助と電池の除外。ここが欠けると何も検知できない", Icons.Filled.Accessibility),
    Focus("focus", "集中モード", "その場で手を止める。ホーム画面に置くボタン", Icons.Filled.Timer),
    Guard("guard", "変更をしにくくする", "緩める変更にかける関門、パスワード、引き止め", Icons.Filled.Lock),
    Screen("screen", "待ち受け・ホーム画面", "ロックを解除した直後に出す問いかけ", Icons.Filled.Home),
    Sync("sync", "端末の連携", "予約や連動をほかの端末とつなぐ", Icons.Filled.Sync),
    Study("study", "学習予定・カレンダー", "予定の前後で強める。助走枠", Icons.Filled.Event),
    Points("points", "ポイント", "押し切りの相場と、解禁券の値段", Icons.Filled.Stars),
    Battery("battery", "電池", "判定を見に来る間隔", Icons.Filled.BatteryFull),
    About("about", "このアプリについて", "版と、外に出るもの", Icons.Filled.Info),
    ;

    companion object {
        /** 経路の文字列から引く。知らない ID は null(ルールの復元と同じ扱い)。 */
        fun of(id: String?): SettingsPage? = entries.firstOrNull { it.id == id }
    }
}

/**
 * 設定の入口。ここには項目そのものを置かず、行き先だけを並べる。
 */
@Composable
fun SettingsScreen(
    onOpen: (SettingsPage) -> Unit = {},
) {
    val context = LocalContext.current
    val refreshKey = rememberResumeKey()

    // 「動作に必要な設定」だけは、開かなくても足りているかが分かるようにする。
    // ここが欠けていると他の設定が全部無意味になるので、一覧の側に出す
    val accessibilityOn = remember(refreshKey) { isAccessibilityEnabled(context) }
    val batteryExempt = remember(refreshKey) { isIgnoringBatteryOptimizations(context) }
    val missing = listOfNotNull(
        "ユーザー補助".takeIf { !accessibilityOn },
        "電池の除外".takeIf { !batteryExempt },
    )

    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
        items(SettingsPage.entries) { page ->
            SettingsRow(
                page = page,
                warning = if (page == SettingsPage.Required && missing.isNotEmpty()) {
                    missing.joinToString("と") + "がまだです"
                } else {
                    null
                },
                onClick = { onOpen(page) },
            )
        }
    }
}

@Composable
private fun SettingsRow(page: SettingsPage, warning: String?, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            page.icon,
            contentDescription = null,
            tint = if (warning != null) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            },
        )
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Text(page.title, style = MaterialTheme.typography.bodyLarge)
            Text(
                warning ?: page.summary,
                style = MaterialTheme.typography.bodySmall,
                color = if (warning != null) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

/**
 * 設定の1ページ。
 *
 * 上の見出しで「いまどこにいるか」が分かるようにしてある。
 * 割ったぶん、戻る道が要る。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPageScreen(
    page: SettingsPage,
    onBack: () -> Unit,
    onOpenDevTools: () -> Unit = {},
    viewModel: SettingsViewModel = viewModel(),
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(page.title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                when (page) {
                    SettingsPage.Required -> RequiredSection()
                    SettingsPage.Focus -> {
                        FocusCard()
                        Spacer(Modifier.height(16.dp))
                        FocusScheduleCard()
                    }
                    SettingsPage.Guard -> GuardSection(viewModel)
                    SettingsPage.Screen -> ScreenSection(viewModel)
                    SettingsPage.Sync -> SyncCard()
                    SettingsPage.Study -> StudySection(viewModel)
                    SettingsPage.Points -> PointsSection(viewModel)
                    SettingsPage.Battery -> BatterySection(viewModel)
                    SettingsPage.About -> AboutSection(onOpenDevTools)
                }
            }
        }
    }
}

/**
 * 設定アプリから戻ってきたことを知るための印。
 *
 * 権限は外で変えられるので、戻ってきた時点で必ず見直す。
 * 見直さないと「許可したのに、まだ許可されていません」と出続ける。
 */
@Composable
private fun rememberResumeKey(): Int {
    var refreshKey by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshKey++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return refreshKey
}

// ---- 各ページの中身 ----------------------------------------------------

@Composable
private fun RequiredSection() {
    val context = LocalContext.current
    val refreshKey = rememberResumeKey()
    val accessibilityOn = remember(refreshKey) { isAccessibilityEnabled(context) }
    val batteryExempt = remember(refreshKey) { isIgnoringBatteryOptimizations(context) }

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

/**
 * 変更に摩擦をかける関門の設定。
 *
 * 設定タブだけでなく、**変更タブからも触れる**ようにしてある ── 申請が
 * 詰まっているのを見ている、まさにそのときが「関門をどう設定したか」を
 * 確かめたい瞬間で、別のタブまで探しに行かせる理由が無い。
 */
@Composable
internal fun GuardSection(viewModel: SettingsViewModel) {
    val gates by viewModel.gates.collectAsState()
    val hasPassword by viewModel.hasPassword.collectAsState()
    val selfDefense by viewModel.selfDefense.collectAsState()
    val refreshKey = rememberResumeKey()
    val calendarGranted = remember(refreshKey) { viewModel.calendarGranted() }

    var showPasswordDialog by remember { mutableStateOf(false) }
    var editingTimeWindow by remember { mutableStateOf<Gate.TimeWindow?>(null) }
    var editingCalendarWindow by remember { mutableStateOf<Gate.CalendarWindow?>(null) }

    Column {
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

        Spacer(Modifier.height(16.dp))
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

    if (showPasswordDialog) {
        PasswordDialog(
            onSet = { viewModel.setPassword(it); showPasswordDialog = false },
            onDismiss = { showPasswordDialog = false },
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

@Composable
private fun ScreenSection(viewModel: SettingsViewModel) {
    val blockHome by viewModel.blockHomeScreen.collectAsState()
    val showOnUnlock by viewModel.showOnUnlock.collectAsState()
    val unlockMessage by viewModel.unlockMessage.collectAsState()
    var showMessageDialog by remember { mutableStateOf(false) }

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

    if (showMessageDialog) {
        TextDialog(
            title = "問いかけの文",
            initial = unlockMessage,
            multiline = true,
            onConfirm = { viewModel.setUnlockMessage(it); showMessageDialog = false },
            onDismiss = { showMessageDialog = false },
        )
    }
}

@Composable
private fun StudySection(viewModel: SettingsViewModel) {
    val prepMinutes by viewModel.studyPrepMinutes.collectAsState()
    val refreshKey = rememberResumeKey()
    var calendarGranted by remember(refreshKey) { mutableStateOf(viewModel.calendarGranted()) }

    val calendarPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        calendarGranted = granted
        if (granted) viewModel.refreshCalendar()
    }

    Column {
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

        Spacer(Modifier.height(16.dp))
        SectionTitle("カレンダー連携")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                if (DopaFeatures.CALENDAR_ENABLED) {
                    CalendarBody(
                        granted = calendarGranted,
                        refreshKey = refreshKey,
                        viewModel = viewModel,
                        onRequest = { calendarPermission.launch(Manifest.permission.READ_CALENDAR) },
                    )
                } else {
                    CalendarFrozenBody()
                }
            }
        }
    }
}

@Composable
private fun CalendarBody(
    granted: Boolean,
    refreshKey: Int,
    viewModel: SettingsViewModel,
    onRequest: () -> Unit,
) {
    Text(
        "端末に同期済みのカレンダーを読みます。Google カレンダーを端末で同期していれば、" +
            "そのまま使えます。ログインも API キーも要りません。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))
    CheckRow(
        label = "カレンダーの読み取りを許可",
        done = granted,
        detail = "予定を条件やゲートに使えるようになります",
        onAction = onRequest,
    )
    if (!granted) return

    val events = remember(refreshKey, granted) { viewModel.upcomingEvents() }
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

@Composable
private fun CalendarFrozenBody() {
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

@Composable
private fun PointsSection(viewModel: SettingsViewModel) {
    val pointPolicy by viewModel.pointPolicy.collectAsState()
    PointPolicyCard(policy = pointPolicy, onChange = viewModel::setPointPolicy)
}

@Composable
private fun BatterySection(viewModel: SettingsViewModel) {
    val batterySaver by viewModel.batterySaver.collectAsState()

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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AboutSection(onOpenDevTools: () -> Unit) {
    val context = LocalContext.current
    var showDevDialog by remember { mutableStateOf(false) }

    Column {
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
        UpdateCard()
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
    }

    if (showDevDialog) {
        DevCodeDialog(
            onUnlock = { showDevDialog = false; onOpenDevTools() },
            onDismiss = { showDevDialog = false },
        )
    }
}

// ------------------------------------------------------------------

/**
 * 新しい版を入れ替える。
 *
 * ## なぜ押したときだけ聞きに行くのか
 *
 * 裏で毎日見に行くほうが親切ではあります。ただ、このアプリは
 * **取り締まりがネットに依存していない**ことを前提にしていて、
 * そこは「機内モードにしても何も変わらない」という形で守りたい。
 * 更新のために常時の通信を足すと、その形が崩れます。
 * 版を確かめたいのは「そういえば」と思ったときだけなので、ボタンで足ります。
 *
 * ## 落とすのと入れるのを分ける
 *
 * 落とし終わってからインストーラを開きます。まとめて一発にすると、
 * 回線が細いときに**何も起きていないように見える時間**が生まれ、
 * その間に押し直されます。
 */
@Composable
private fun UpdateCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by AppUpdater.state.collectAsState()

    Card {
        Column(Modifier.padding(16.dp)) {
            Text("アップデート", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "押したときだけサーバーに聞きます。ふだんは通信しません。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            when (val current = state) {
                is AppUpdater.State.Idle -> {
                    Button(onClick = { scope.launch { AppUpdater.check(context) } }) {
                        Text("アップデートを確認")
                    }
                }

                is AppUpdater.State.Checking -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("聞いています…", style = MaterialTheme.typography.bodyMedium)
                    }
                }

                is AppUpdater.State.UpToDate -> {
                    Text(
                        "いまの " + current.current + " が最新です。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { scope.launch { AppUpdater.check(context) } }) {
                        Text("もう一度確認")
                    }
                }

                is AppUpdater.State.Available -> {
                    Text(
                        current.build.version + " が出ています",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
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
                        Button(onClick = { scope.launch { AppUpdater.download(context, current.build) } }) {
                            Text("ダウンロード")
                        }
                        TextButton(onClick = { AppUpdater.reset() }) { Text("あとで") }
                    }
                }

                is AppUpdater.State.Downloading -> {
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
                    TextButton(onClick = { AppUpdater.cancel() }) { Text("やめる") }
                }

                is AppUpdater.State.Ready -> {
                    Text(
                        current.build.version + " を落とし終わりました",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        // 無音では入れられないので、ここだけは手で押してもらう。
                        // 「押したのに何も起きない」と思わせないために先に書いておく
                        "「インストール」を押すと入れ替わります。ルールも記録も残ります。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { AppUpdater.install(context, current.file) }) {
                            Text("インストール")
                        }
                        TextButton(onClick = { AppUpdater.cleanUp(context); AppUpdater.reset() }) {
                            Text("捨てる")
                        }
                    }
                    if (!AppUpdater.canInstall(context)) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "「不明なアプリのインストール」を許していないので、" +
                                "最初の1回だけ設定画面に飛びます。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                is AppUpdater.State.Failed -> {
                    Text(
                        current.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { scope.launch { AppUpdater.check(context) } }) {
                        Text("もう一度")
                    }
                }
            }
        }
    }
}

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
    val tags by DopaRuntime.rules.tags.collectAsState(initial = emptyList())
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

            Text("ホーム画面に置くボタン", style = MaterialTheme.typography.bodyLarge)
            Text(
                "ドパチルを開いてから始めるのでは遅い、という場面のためのものです。" +
                    "置き場所が近いことが、そのまま使う回数になります。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            OutlinedButton(onClick = { pinned = FocusShortcutActivity.requestPinPicker(context) }) {
                Text("「長さを選ぶ」を置く")
            }
            Text(
                "押すと${Focus.PICK_CHOICES.first()}分から${Focus.PICK_CHOICES.last()}分まで" +
                    "${Focus.STEP_MINUTES}分刻みで並ぶので、その場で選べます。ボタンは1つで足ります。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))

            MinuteStepper(
                label = "1タップで始まるボタンの長さ",
                minutes = settings.shortcutMinutes,
                onChange = { update(settings.copy(shortcutMinutes = it)) },
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = {
                pinned = FocusShortcutActivity.requestPin(context, settings.shortcutMinutes)
            }) {
                Text("${settings.shortcutMinutes}分のボタンを置く")
            }
            Text(
                "選ぶ手間すら惜しい長さが決まっているとき用。長さを変えて押せば、" +
                    "別のボタンとして何個でも置けます。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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

            FocusTemplatesSection(
                settings = settings,
                tags = tags,
                onChange = ::update,
                onPin = { pinned = FocusShortcutActivity.requestPinTemplate(context, it) },
            )

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

            // タグでも逃がせるようにする。アプリを1つずつ選び直さずに済むのと、
            // **あとから入れたアプリが自動で入る**のが効く ── 1つずつだと、
            // 新しく入れた音楽アプリが集中のたびに閉まって、そのたび設定を開くことになる
            Spacer(Modifier.height(16.dp))
            Text("タグで逃がす", style = MaterialTheme.typography.bodyLarge)
            Text(
                "そのタグが付いたアプリをまとめて逃がします。あとでタグに足したアプリも、" +
                    "設定を触らずに逃げるようになります。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            if (tags.isEmpty()) {
                Text(
                    "タグがまだありません。ルールのタブから作れます。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    tags.forEach { tag ->
                        FilterChip(
                            selected = tag in settings.allowTags,
                            onClick = {
                                val next = if (tag in settings.allowTags) {
                                    settings.allowTags - tag
                                } else {
                                    settings.allowTags + tag
                                }
                                update(settings.copy(allowTags = next))
                            },
                            label = { Text(tag) },
                        )
                    }
                }
            }

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

/**
 * タイマーロックの型。範囲(グループだけ / グループ以外 / 全部)と長さを決めておき、
 * ホーム画面のショートカット1つで呼び出せるようにする。
 */
@Composable
private fun FocusTemplatesSection(
    settings: FocusSettings,
    tags: List<String>,
    onChange: (FocusSettings) -> Unit,
    onPin: (FocusTemplate) -> Unit,
) {
    var editing by remember { mutableStateOf<FocusTemplate?>(null) }
    var isNew by remember { mutableStateOf(false) }

    Text("タイマーロックの型", style = MaterialTheme.typography.bodyLarge)
    Text(
        "止める範囲と長さを先に決めておくと、ホーム画面に置いた1つで呼び出せます。" +
            "「SNSだけ」「仕事以外を止める」「全部止める」のように使い分けられます。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))

    if (settings.templates.isEmpty()) {
        Text(
            "まだ型がありません。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        settings.templates.forEach { template ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(template.displayLabel(), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        template.scope.label +
                            (if (template.isOneTap) "・${template.minutes}分" else "・長さを選ぶ") +
                            (if (!template.isUsable) "・タグ未設定" else ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (template.isUsable) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
                TextButton(
                    onClick = { onPin(template) },
                    enabled = template.isUsable,
                ) { Text("ホームに置く") }
                TextButton(onClick = { editing = template; isNew = false }) { Text("直す") }
            }
        }
    }

    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = {
        editing = FocusTemplate(id = java.util.UUID.randomUUID().toString())
        isNew = true
    }) { Text("型を作る") }

    editing?.let { template ->
        FocusTemplateEditorDialog(
            template = template,
            tags = tags,
            onDismiss = { editing = null },
            onDelete = if (isNew) null else {
                {
                    onChange(settings.copy(templates = settings.templates.filterNot { it.id == template.id }))
                    editing = null
                }
            },
            onSave = { saved ->
                val next = if (settings.templates.any { it.id == saved.id }) {
                    settings.templates.map { if (it.id == saved.id) saved else it }
                } else {
                    settings.templates + saved
                }
                onChange(settings.copy(templates = next))
                editing = null
            },
        )
    }
}

@Composable
private fun FocusTemplateEditorDialog(
    template: FocusTemplate,
    tags: List<String>,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)?,
    onSave: (FocusTemplate) -> Unit,
) {
    var label by remember { mutableStateOf(template.label) }
    var scope by remember { mutableStateOf(template.scope) }
    var tag by remember { mutableStateOf(template.tag) }
    var pickMinutes by remember { mutableStateOf(template.minutes > 0) }
    var minutes by remember { mutableIntStateOf(if (template.minutes > 0) template.minutes else Focus.DEFAULT_MINUTES) }

    val needsTag = scope == FocusScope.GROUP || scope == FocusScope.EXCEPT_GROUP
    val canSave = !needsTag || tag.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("タイマーロックの型") },
        text = {
            Column {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("名前(空でもよい)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))

                Text("止める範囲", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                FocusScope.entries.forEach { option ->
                    Row(
                        Modifier.fillMaxWidth().clickable { scope = option }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.RadioButton(
                            selected = scope == option,
                            onClick = { scope = option },
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(option.label, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                if (needsTag) {
                    Spacer(Modifier.height(8.dp))
                    Text("どのグループ(タグ)", style = MaterialTheme.typography.labelLarge)
                    if (tags.isEmpty()) {
                        Text(
                            "タグがありません。先にアプリにタグを付けてください。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                            // タグは数がしれているので、そのまま並べて選ばせる
                            Column {
                                tags.chunked(3).forEach { rowTags ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        rowTags.forEach { t ->
                                            FilterChip(
                                                selected = tag == t,
                                                onClick = { tag = t },
                                                label = { Text(t) },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text("長さ", style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = pickMinutes,
                        onClick = { pickMinutes = true },
                        label = { Text("押すとき選ぶ") },
                    )
                    Spacer(Modifier.width(6.dp))
                    FilterChip(
                        selected = !pickMinutes,
                        onClick = { pickMinutes = false },
                        label = { Text("決め打ち") },
                    )
                }
                if (!pickMinutes) {
                    Spacer(Modifier.height(4.dp))
                    MinuteStepper(label = "1タップで始まる長さ", minutes = minutes, onChange = { minutes = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    onSave(
                        template.copy(
                            label = label.trim(),
                            scope = scope,
                            tag = if (needsTag) tag else "",
                            minutes = if (pickMinutes) 0 else Focus.clampMinutes(minutes),
                        )
                    )
                },
            ) { Text("保存") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) { Text("削除") }
                }
                TextButton(onClick = onDismiss) { Text("やめる") }
            }
        },
    )
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
 * 端末の連携。
 *
 * **押すまで何も送りません。** 「新しく始める」か「コードで参加」を押したときに
 * 初めてサーバーにつながり、区画は人ごとに分かれています(ほかの人の予約や
 * 頼みごとは見えないし、触れない)。1台だけで使う人は、押さなくて構いません。
 *
 * 何が出るかを画面に書いてあるのは、**権限の一覧を見ても分からない**ためです。
 * INTERNET を持っているアプリが「何を送っているか」は、外からは確かめられません。
 */
@Composable
private fun SyncCard() {
    val settings by DopaRuntime.settings.syncSettings.collectAsState(initial = SyncSettings())
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "ほかの端末とつなぐと、予約をほかの端末から取ったり、ルールを端末をまたいで" +
                    "効かせたりできます。制限そのものはつなぐかどうかに関係なく効きます ── " +
                    "圏外でもサーバーが落ちていても、縛りは効いたままです。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            if (settings.isConfigured) {
                ConnectedSection(settings, busy, onBusy = { busy = it }, onResult = { result = it })
            } else {
                JoinSection(settings, busy, onBusy = { busy = it }, onResult = { result = it })
            }

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
                "つないだら出るもの: 端末の名前・タグ・アプリ名・ルールの名札(名前と対象と予約の数字)・" +
                    "予約・ほかの端末への頼みごと・ルールがいま効いているか・1日ごとの使用時間\n" +
                    "出ないもの: ルールの条件や反省文、どの瞬間に何を見ていたか、関門、変更の申請",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** まだつないでいない端末。新しく始めるか、ほかの端末で出したコードで参加する。 */
@Composable
private fun JoinSection(
    settings: SyncSettings,
    busy: Boolean,
    onBusy: (Boolean) -> Unit,
    onResult: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var showAdvanced by remember { mutableStateOf(false) }
    var url by remember(settings.baseUrl) { mutableStateOf(settings.baseUrl) }
    LaunchedEffect(Unit) { if (name.isBlank()) name = DopaRuntime.defaultDeviceName() }

    fun connect(withCode: String) {
        onBusy(true)
        onResult(if (withCode.isBlank()) "始めています…" else "参加しています…")
        scope.launch {
            // 住所を変えていればそれを使う。空なら既定(dopa.togar.dev)
            if (url.trim() != settings.baseUrl) {
                DopaRuntime.settings.setSyncSettings(settings.copy(baseUrl = url.trim()))
            }
            onResult(
                when (val out = DopaRuntime.connect(name.trim(), withCode)) {
                    is Joining.Result.Ok -> "つながりました"
                    is Joining.Result.Failed -> out.message
                },
            )
            onBusy(false)
        }
    }

    OutlinedTextField(
        value = name,
        onValueChange = { name = it.take(32) },
        label = { Text("この端末の名前") },
        singleLine = true,
        supportingText = {
            Text("ほかの端末の画面に出ます。「スマホ」「しごとPC」など見分けのつく名前を。")
        },
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer(Modifier.height(12.dp))
    Text("はじめての端末なら", style = MaterialTheme.typography.labelLarge)
    Button(onClick = { connect("") }, enabled = !busy && name.isNotBlank()) { Text("新しく始める") }

    Spacer(Modifier.height(16.dp))
    Text("ほかの端末でもう使っているなら", style = MaterialTheme.typography.labelLarge)
    Text(
        "そちらの「端末の連携」→「コードを出す」で出た8文字を入れてください。2分で切れます。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(4.dp))
    OutlinedTextField(
        value = code,
        onValueChange = { code = it.uppercase().take(12) },
        label = { Text("コード") },
        singleLine = true,
        trailingIcon = {
            TextButton(
                onClick = { connect(code) },
                enabled = !busy && name.isNotBlank() && code.trim().length >= 6,
            ) { Text("参加する") }
        },
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer(Modifier.height(8.dp))
    TextButton(onClick = { showAdvanced = !showAdvanced }) {
        Text(if (showAdvanced) "詳しい設定を閉じる" else "詳しい設定")
    }
    if (showAdvanced) {
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("サーバーの住所") },
            placeholder = { Text(SyncDefaults.BASE_URL) },
            singleLine = true,
            supportingText = { Text("空なら既定のサーバー。自分でサーバーを立てたときだけ変えます。") },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** つながっている端末。同期・ほかの端末を足す・連携をやめる。 */
@Composable
private fun ConnectedSection(
    settings: SyncSettings,
    busy: Boolean,
    onBusy: (Boolean) -> Unit,
    onResult: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val name by DopaRuntime.settings.deviceName.collectAsState(initial = "")
    var invite by remember { mutableStateOf("") }
    var inviteLeft by remember { mutableIntStateOf(0) }
    var confirmLeave by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    // 残り時間を見せる。切れたことが見えないと、切れたコードを打ち込んで悩む
    LaunchedEffect(invite) {
        while (inviteLeft > 0) {
            kotlinx.coroutines.delay(1_000)
            inviteLeft -= 1
        }
    }

    Text(
        "つながっています" + if (name.isNotBlank()) "(この端末: $name)" else "",
        style = MaterialTheme.typography.bodyLarge,
    )

    Spacer(Modifier.height(12.dp))
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text("同期する", style = MaterialTheme.typography.bodyLarge)
            Text(
                "切っているあいだは何も送らず、何も受け取りません。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = settings.enabled,
            onCheckedChange = { on ->
                scope.launch { DopaRuntime.settings.setSyncSettings(settings.copy(enabled = on)) }
            },
        )
    }

    Spacer(Modifier.height(8.dp))
    Button(
        onClick = {
            onBusy(true)
            onResult("同期しています…")
            scope.launch {
                onResult(
                    when (val out = DopaRuntime.sync.syncNow()) {
                        is SyncManager.Outcome.Done -> "受け取り ${out.pulled} 件 / 送り ${out.pushed} 件"
                        is SyncManager.Outcome.NotConfigured -> "まだつないでいません"
                        is SyncManager.Outcome.Failed -> out.message
                    },
                )
                onBusy(false)
            }
        },
        enabled = settings.enabled && !busy,
    ) { Text("いま同期する") }

    Spacer(Modifier.height(16.dp))
    HorizontalDivider()
    Spacer(Modifier.height(16.dp))

    Text("ほかの端末をつなぐ", style = MaterialTheme.typography.bodyLarge)
    Text(
        "コードを出して、つなぎたい端末の「端末の連携」→「コードで参加」に入れてください。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (invite.isNotBlank()) {
        Spacer(Modifier.height(8.dp))
        Text(invite, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Medium)
        Text(
            if (inviteLeft > 0) "あと ${inviteLeft} 秒で切れます" else "切れました。出し直してください",
            style = MaterialTheme.typography.bodySmall,
            color = if (inviteLeft > 0) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error
            },
        )
    }
    Spacer(Modifier.height(4.dp))
    OutlinedButton(
        onClick = {
            onBusy(true)
            scope.launch {
                DopaRuntime.newInvite().fold(
                    onSuccess = { (code, seconds) ->
                        invite = code
                        inviteLeft = seconds
                        onResult("")
                    },
                    onFailure = { onResult(it.message ?: "コードを出せませんでした") },
                )
                onBusy(false)
            }
        },
        enabled = !busy,
    ) { Text(if (invite.isBlank()) "コードを出す" else "出し直す") }

    Spacer(Modifier.height(16.dp))
    HorizontalDivider()
    Spacer(Modifier.height(8.dp))
    TextButton(onClick = { confirmLeave = true }, enabled = !busy) { Text("この端末だけ連携をやめる") }
    TextButton(onClick = { confirmDelete = true }, enabled = !busy) {
        Text("すべての端末で連携をやめて、サーバーの記録を消す", color = MaterialTheme.colorScheme.error)
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("この端末だけ連携をやめますか") },
            text = {
                Text("ほかの端末はつながったままです。この端末のルールと記録はそのまま残ります。")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmLeave = false
                    scope.launch {
                        DopaRuntime.leave()
                        onResult("この端末の連携をやめました")
                    }
                }) { Text("やめる") }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("戻る") } },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("サーバーの記録を消しますか") },
            text = {
                Text(
                    "つないでいるすべての端末の連携が切れ、サーバーに置いた予約・名札・使用時間が" +
                        "消えます。元に戻せません。各端末のルールと記録はそのまま残ります。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onBusy(true)
                    scope.launch {
                        onResult(
                            DopaRuntime.deleteEverywhere().fold(
                                onSuccess = { "サーバーの記録を消しました" },
                                onFailure = { it.message ?: "消せませんでした" },
                            ),
                        )
                        onBusy(false)
                    }
                }) { Text("消す", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("戻る") } },
        )
    }
}
