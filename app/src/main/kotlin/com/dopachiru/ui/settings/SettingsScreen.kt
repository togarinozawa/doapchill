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
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.dopachiru.runtime.DopaRuntime
import com.dopachiru.service.DopaAccessibilityService
import com.dopachiru.ui.common.HourMinutePicker
import com.dopachiru.ui.rules.DayOfWeekPicker
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

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = viewModel()) {
    val context = LocalContext.current
    val gates by viewModel.gates.collectAsState()
    val hasPassword by viewModel.hasPassword.collectAsState()
    val blockHome by viewModel.blockHomeScreen.collectAsState()
    val showOnUnlock by viewModel.showOnUnlock.collectAsState()
    val unlockMessage by viewModel.unlockMessage.collectAsState()
    val selfDefense by viewModel.selfDefense.collectAsState()
    val batterySaver by viewModel.batterySaver.collectAsState()

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
                        onAction = { calendarPermission.launch(Manifest.permission.READ_CALENDAR) },
                    )

                    if (calendarGranted) {
                        val events = remember(refreshKey, calendarGranted) { viewModel.upcomingEvents() }
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
                    GateRow(
                        label = "カレンダーの予定中だけ変更できる",
                        gate = calendarWindow ?: Gate.CalendarWindow(),
                        gates = gates,
                        enabled = calendarGranted,
                        disabledHint = "先にカレンダーの読み取りを許可してください",
                        currentDescription = calendarWindow?.describe(),
                        onConfigure = { editingCalendarWindow = calendarWindow ?: Gate.CalendarWindow() },
                        onToggle = viewModel::putGate,
                    )

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
                "使用状況もカレンダーの予定も端末の中だけで処理され、外部には一切送信されません。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(32.dp))
        }
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
