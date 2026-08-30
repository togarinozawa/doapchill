package com.dopachiru.ui.rules

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
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
import java.time.format.DateTimeFormatter

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

    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = { exportLauncher.launch(defaultFileName()) }) { Text("書き出す") }
        // JSON を text/* で出す端末があるので、両方受ける
        TextButton(
            onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
        ) { Text("読み込む") }
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
