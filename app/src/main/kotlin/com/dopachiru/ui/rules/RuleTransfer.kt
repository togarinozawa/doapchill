package com.dopachiru.ui.rules

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import java.time.LocalDate
import com.dopachiru.core.io.HistoryExport
import com.dopachiru.core.io.HistoryData
import com.dopachiru.core.io.ExportFormat
import com.dopachiru.core.io.DeclarationEvent
import com.dopachiru.core.io.DayEvent
import com.dopachiru.core.io.ChangeEvent
import com.dopachiru.core.io.BlockEvent
import androidx.compose.ui.Alignment
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.RadioButton
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.clickable
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dopachiru.core.gate.ChangeKind
import com.dopachiru.core.io.ImportPlan
import com.dopachiru.core.io.UsageReport
import com.dopachiru.core.io.UsageSpan
import com.dopachiru.core.io.RuleBundleIo
import com.dopachiru.runtime.DopaRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 使用実績を何日ぶん書き出すか。
 *
 * 記録そのものは90日残っているが、14日にしてある ── 古い癖まで混ぜると、
 * 「いまの自分」ではなく「半年前の自分」に合わせたルールが返ってくる。
 */
const val USAGE_REPORT_DAYS = 14

/**
 * ルールの持ち出しと取り込み。
 *
 * 込み入ったルールを小さな画面でこねるのは骨が折れる。書き出したものを
 * 手元の道具に渡して直してもらい、そのまま戻せるようにする。
 *
 * **取り込みも変更ゲートを通る。** 新しく増えるぶんは即時、既存の差し替えは
 * ゲートが設定してあれば申請になる ── ファイルを1つ読ませるだけで
 * 縛りを緩められるなら、ゲートを置いた意味が無くなる。
 */
class RuleTransferViewModel(app: Application) : AndroidViewModel(app) {

    sealed interface Stage {
        data object Idle : Stage
        data class Failed(val message: String) : Stage
        data class Ready(val plan: ImportPlan) : Stage
        data class Done(val message: String) : Stage
    }

    private val _stage = MutableStateFlow<Stage>(Stage.Idle)
    val stage: StateFlow<Stage> = _stage.asStateFlow()

    fun dismiss() {
        _stage.value = Stage.Idle
    }

    /**
     * 使用実績と、押し切り・申請・宣言の履歴を書き出す。
     *
     * ルールの書き出しと同じ口に置いてあるのは、**そこが輪になっている**から
     * ── 書き出す → AI に渡す → 返ってきたものを読み込む。
     * 記録タブに置くと、戻ってくる先が別のタブになる。
     *
     * 形式は2つ。[ExportFormat.ADVICE] は説明と頼みごと付きの Markdown、
     * [ExportFormat.DATA] は記録だけの JSON。端末の外へは、ここで選んだファイルにしか出ない。
     */
    fun exportHistory(context: Context, uri: Uri, format: ExportFormat, days: Int) {
        viewModelScope.launch {
            val now = LocalDateTime.now()
            val zone = ZoneId.systemDefault()
            val fromSec = now.atZone(zone).toEpochSecond() - days * 24L * 3600
            val db = DopaRuntime.db
            val rules = DopaRuntime.rules.getAll()
            val ruleNames = rules.associate { it.id to it.name }
            val label = { pkg: String -> InstalledApps.labelOf(context, pkg) }

            val history = HistoryData(
                blocks = db.blockLogDao().allSince(fromSec).map {
                    BlockEvent(it.atEpochSec, it.packageName, it.ruleName, it.actionId, it.overridden, it.insteadNote)
                },
                days = db.dayStatDao().since(now.toLocalDate().toEpochDay() - days).map {
                    DayEvent(LocalDate.ofEpochDay(it.epochDay), it.blockShownCount, it.overrideCount, it.totalScreenMinutes)
                },
                changes = db.changeRequestDao().allSince(fromSec).map {
                    ChangeEvent(
                        it.createdAtEpochSec,
                        it.kind,
                        it.targetRuleId?.let { id -> ruleNames[id] ?: "(消えたルール)" } ?: "(新規)",
                        it.reason,
                        it.status,
                    )
                },
                declarations = db.declarationDao().allSince(fromSec).map {
                    DeclarationEvent(it.declaredAtEpochSec, it.packageName, it.budgetMinutes, (it.consumedSec / 60).toInt(), it.reason)
                },
            )
            val spans = db.usageDao().allSince(fromSec)
                .map { UsageSpan(it.packageName, it.startEpochSec, it.endEpochSec) }
            val deviceName = DopaRuntime.settings.deviceName.first()

            val text = when (format) {
                ExportFormat.ADVICE -> UsageReport.build(
                    spans = spans,
                    labelOf = label,
                    rules = rules,
                    tags = DopaRuntime.rules.currentTagsByPackage(),
                    now = now,
                    days = days,
                    deviceName = deviceName,
                    history = history,
                )
                ExportFormat.DATA -> HistoryExport.buildData(
                    spans = spans,
                    labelOf = label,
                    rules = HistoryExport.summarize(rules),
                    history = history,
                    now = now,
                    days = days,
                    deviceName = deviceName,
                )
            }
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(text.toByteArray(Charsets.UTF_8))
                    } != null
                }.getOrDefault(false)
            }
            _stage.value = if (ok) {
                Stage.Done(
                    when (format) {
                        ExportFormat.ADVICE ->
                            "直近${days}日ぶんを書き出しました。中身を AI に貼れば、そのまま相談になります。" +
                                "個人の記録なので、渡す先に気をつけてください。"
                        ExportFormat.DATA ->
                            "直近${days}日ぶんの記録を JSON で書き出しました。説明や頼みごとは入っていません。"
                    },
                )
            } else {
                Stage.Failed("書き出せませんでした。")
            }
        }
    }

    fun export(context: Context, uri: Uri) {
        viewModelScope.launch {
            val rules = DopaRuntime.rules.getAll()
            val tags = DopaRuntime.rules.currentTagsByPackage()
            val text = RuleBundleIo.export(
                rules = rules,
                tags = tags,
                exportedAt = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            )
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(text.toByteArray(Charsets.UTF_8))
                    } ?: error("書き込み先を開けませんでした")
                }.isSuccess
            }
            _stage.value = if (ok) {
                Stage.Done("${rules.size}件を書き出しました。目録も入っているので、このファイルだけ渡せば直してもらえます。")
            } else {
                Stage.Failed("書き出せませんでした。")
            }
        }
    }

    fun preview(context: Context, uri: Uri) {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    }
                }.getOrNull()
            }
            if (text == null) {
                _stage.value = Stage.Failed("ファイルを読めませんでした。")
                return@launch
            }
            when (val parsed = RuleBundleIo.parse(text)) {
                is RuleBundleIo.ParseResult.Failed -> _stage.value = Stage.Failed(parsed.message)
                is RuleBundleIo.ParseResult.Ok ->
                    _stage.value = Stage.Ready(
                        RuleBundleIo.plan(parsed.bundle, DopaRuntime.rules.getAll()),
                    )
            }
        }
    }

    /** 見せた計画をそのまま実行する。ここで初めて中身が変わる。 */
    fun apply(plan: ImportPlan) {
        viewModelScope.launch {
            val gates = DopaRuntime.settings.gates.first()

            plan.added.forEach { rule ->
                DopaRuntime.changes.request(ChangeKind.CREATE, rule, emptyList())
            }
            plan.replaced.forEach { (_, incoming) ->
                DopaRuntime.changes.request(ChangeKind.UPDATE, incoming, gates)
            }
            plan.tags.forEach { (pkg, tags) ->
                tags.forEach { DopaRuntime.rules.addTag(pkg, it) }
            }

            val queued = plan.replaced.isNotEmpty() && gates.isNotEmpty()
            _stage.value = Stage.Done(
                buildString {
                    append("${plan.added.size}件を追加しました。")
                    if (plan.replaced.isNotEmpty()) {
                        append(
                            if (queued) {
                                "差し替えの${plan.replaced.size}件は「変更」タブで承認が要ります。"
                            } else {
                                "${plan.replaced.size}件を差し替えました。"
                            },
                        )
                    }
                },
            )
        }
    }
}

/**
 * 書き出す・読み込むの2つのボタンと、その結果を出すダイアログ。
 *
 * 取り込みは**押した瞬間には何も変えない**。何件増えて何件差し替わるかを
 * 先に見せて、そこで初めて決めさせる。ルールは自分を縛るものなので、
 * 気づかないうちに緩んでいるのがいちばん困る。
 */
@Composable
fun RuleTransferControls(viewModel: RuleTransferViewModel = viewModel()) {
    val context = LocalContext.current
    val stage by viewModel.stage.collectAsState()

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> if (uri != null) viewModel.export(context, uri) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) viewModel.preview(context, uri) }

    // 書き出し先の種類(MIME)は launcher を作る時に決まるので、形式ごとに1つずつ持つ
    var pending by remember { mutableStateOf(HistoryChoice(ExportFormat.ADVICE, USAGE_REPORT_DAYS)) }
    var choosing by remember { mutableStateOf(false) }
    val adviceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ExportFormat.ADVICE.mime),
    ) { uri -> if (uri != null) viewModel.exportHistory(context, uri, pending.format, pending.days) }
    val dataLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ExportFormat.DATA.mime),
    ) { uri -> if (uri != null) viewModel.exportHistory(context, uri, pending.format, pending.days) }

    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = { exportLauncher.launch(defaultFileName()) }) { Text("書き出す") }
        // JSON を text/* で出す端末があるので、両方受ける
        TextButton(
            onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
        ) { Text("読み込む") }
        TextButton(onClick = { choosing = true }) { Text("履歴") }
    }
    Text(
        "「履歴」は使用時間と、押し切り・申請・宣言の記録を書き出します。" +
            "AI への頼みごと付きか、記録だけかを選べます ── 個人の記録なので、渡す先に気をつけて。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (choosing) {
        HistoryExportDialog(
            initial = pending,
            onDismiss = { choosing = false },
            onConfirm = { choice ->
                pending = choice
                choosing = false
                when (choice.format) {
                    ExportFormat.ADVICE -> adviceLauncher.launch(historyFileName(choice.format))
                    ExportFormat.DATA -> dataLauncher.launch(historyFileName(choice.format))
                }
            },
        )
    }

    when (val s = stage) {
        is RuleTransferViewModel.Stage.Idle -> Unit

        is RuleTransferViewModel.Stage.Failed -> AlertDialog(
            onDismissRequest = viewModel::dismiss,
            title = { Text("読めませんでした") },
            text = { Text(s.message) },
            confirmButton = { TextButton(onClick = viewModel::dismiss) { Text("わかった") } },
        )

        is RuleTransferViewModel.Stage.Done -> AlertDialog(
            onDismissRequest = viewModel::dismiss,
            title = { Text("できました") },
            text = { Text(s.message) },
            confirmButton = { TextButton(onClick = viewModel::dismiss) { Text("わかった") } },
        )

        is RuleTransferViewModel.Stage.Ready -> ImportPreviewDialog(
            plan = s.plan,
            onConfirm = { viewModel.apply(s.plan) },
            onDismiss = viewModel::dismiss,
        )
    }
}

@Composable
private fun ImportPreviewDialog(plan: ImportPlan, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("取り込む前に") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(plan.summary(), style = MaterialTheme.typography.titleSmall)

                if (plan.added.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text("増えるもの", style = MaterialTheme.typography.labelMedium)
                    plan.added.forEach { Text("・${it.name}", style = MaterialTheme.typography.bodySmall) }
                }

                if (plan.replaced.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text("差し替わるもの", style = MaterialTheme.typography.labelMedium)
                    plan.replaced.forEach { (before, after) ->
                        val line = if (before.name == after.name) {
                            "・${after.name}"
                        } else {
                            "・${before.name} → ${after.name}"
                        }
                        Text(line, style = MaterialTheme.typography.bodySmall)
                    }
                }

                if (plan.problems.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "取り込めないもの",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    plan.problems.forEach {
                        Text(
                            "・${it.ruleName}: ${it.reason}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm() }, enabled = !plan.isEmpty) { Text("取り込む") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("やめる") } },
    )
}

private fun defaultFileName(): String {
    val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))
    return "dopachiru-rules-$stamp.json"
}

private fun historyFileName(format: ExportFormat): String {
    val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))
    val kind = if (format == ExportFormat.ADVICE) "advice" else "data"
    return "dopachiru-history-$kind-$stamp.${format.extension}"
}

private data class HistoryChoice(val format: ExportFormat, val days: Int)

/** 期間は、記録が残る90日まで。短いほど「いまの自分」に寄る。 */
private val EXPORT_PERIODS = listOf(14, 30, 90)

@Composable
private fun HistoryExportDialog(
    initial: HistoryChoice,
    onDismiss: () -> Unit,
    onConfirm: (HistoryChoice) -> Unit,
) {
    var format by remember { mutableStateOf(initial.format) }
    var days by remember { mutableStateOf(initial.days) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("履歴を書き出す") },
        text = {
            Column {
                ExportFormat.entries.forEach { f ->
                    Row(
                        Modifier.fillMaxWidth().clickable { format = f },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = format == f, onClick = { format = f })
                        Text(f.label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("期間", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EXPORT_PERIODS.forEach { d ->
                        FilterChip(selected = days == d, onClick = { days = d }, label = { Text("${d}日") })
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "含むもの: アプリの使用時間、いまのルール、閉じた記録と押し切り、日ごとの集計、" +
                        "ルールの変更申請、宣言。書き込んだ理由やメモもそのまま入ります。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(HistoryChoice(format, days)) }) { Text("保存先を選ぶ") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("やめる") } },
    )
}
