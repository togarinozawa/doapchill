package com.dopachiru.ui.changes

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dopachiru.core.gate.ChangeKind
import com.dopachiru.core.gate.ChangeStatus
import com.dopachiru.core.gate.Gate
import com.dopachiru.data.PendingChange
import com.dopachiru.runtime.DopaRuntime
import com.dopachiru.ui.minigame.ArithmeticGame
import com.dopachiru.ui.rules.describeRule
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class ChangeRequestViewModel(app: Application) : AndroidViewModel(app) {

    val changes: StateFlow<List<PendingChange>> = DopaRuntime.changes.all
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** クールダウンの残り表示を動かすためだけの時計。 */
    private val _tick = MutableStateFlow(0L)
    val tick: StateFlow<Long> = _tick.asStateFlow()

    init {
        viewModelScope.launch {
            while (true) {
                _tick.value = System.currentTimeMillis()
                delay(10_000)
            }
        }
    }

    fun clearGate(requestId: Long, gateKey: String) {
        viewModelScope.launch { DopaRuntime.changes.clearGate(requestId, gateKey) }
    }

    fun setReason(requestId: Long, reason: String) {
        viewModelScope.launch { DopaRuntime.changes.setReason(requestId, reason) }
    }

    fun apply(requestId: Long) {
        viewModelScope.launch { DopaRuntime.changes.applyIfReady(requestId) }
    }

    fun cancel(requestId: Long) {
        viewModelScope.launch { DopaRuntime.changes.cancel(requestId) }
    }

    suspend fun verifyPassword(raw: String): Boolean = DopaRuntime.settings.verifyPassword(raw)
}

@Composable
fun ChangeRequestScreen(viewModel: ChangeRequestViewModel = viewModel()) {
    val changes by viewModel.changes.collectAsState()
    // tick を購読して、クールダウンの残り時間を定期的に描き直す
    val now by viewModel.tick.collectAsState()

    val pending = changes.filter { it.entity.status == ChangeStatus.PENDING.name }
    val history = changes.filter { it.entity.status != ChangeStatus.PENDING.name }

    var activeGate by remember { mutableStateOf<Pair<Long, Gate>?>(null) }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("申請中", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }

        if (pending.isEmpty()) {
            item {
                Text(
                    "いま待っている変更はありません。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        items(pending, key = { it.entity.id }) { change ->
            PendingCard(
                change = change,
                nowMillis = now,
                onOpenGate = { gate -> activeGate = change.entity.id to gate },
                onApply = { viewModel.apply(change.entity.id) },
                onCancel = { viewModel.cancel(change.entity.id) },
            )
        }

        item {
            Spacer(Modifier.height(12.dp))
            Text("履歴", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "自分が何を、なぜ緩めたかの記録。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        items(history, key = { it.entity.id }) { change ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(kindLabel(change.kind), style = MaterialTheme.typography.titleSmall)
                        Text(
                            formatTime(change.entity.createdAtEpochSec),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    change.rule?.let {
                        Text(it.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            describeRule(it),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (change.entity.reason.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "理由: ${change.entity.reason}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text(
                        if (change.entity.status == ChangeStatus.APPLIED.name) "適用済み" else "取り下げ",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }

    activeGate?.let { (requestId, gate) ->
        GateDialog(
            gate = gate,
            onDismiss = { activeGate = null },
            onCleared = {
                viewModel.clearGate(requestId, gate.key)
                activeGate = null
            },
            onReason = { reason ->
                viewModel.setReason(requestId, reason)
                viewModel.clearGate(requestId, gate.key)
                activeGate = null
            },
            verifyPassword = { viewModel.verifyPassword(it) },
        )
    }
}

@Composable
private fun PendingCard(
    change: PendingChange,
    nowMillis: Long,
    onOpenGate: (Gate) -> Unit,
    onApply: () -> Unit,
    onCancel: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(kindLabel(change.kind), style = MaterialTheme.typography.labelMedium)
            change.rule?.let {
                Text(it.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    describeRule(it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            change.previousRule?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    "変更前: ${describeRule(it)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(12.dp))

            if (change.remaining.isEmpty()) {
                Text(
                    "すべての関門を通過しました。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = onApply, modifier = Modifier.fillMaxWidth()) {
                    Text("この変更を適用する")
                }
            } else {
                Text("残っている関門", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                change.remaining.forEach { gate ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            gateStatusText(gate, change, nowMillis),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        if (gate !is Gate.Cooldown && gate !is Gate.TimeWindow) {
                            TextButton(onClick = { onOpenGate(gate) }) { Text("挑む") }
                        }
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onCancel) {
                Text("申請を取り下げる", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun GateDialog(
    gate: Gate,
    onDismiss: () -> Unit,
    onCleared: () -> Unit,
    onReason: (String) -> Unit,
    verifyPassword: suspend (String) -> Boolean,
) {
    when (gate) {
        is Gate.MiniGame -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("ミニゲーム") },
            text = { ArithmeticGame(rounds = gate.rounds, onCleared = onCleared) },
            confirmButton = { TextButton(onClick = onDismiss) { Text("やめる") } },
        )

        is Gate.WriteReason -> {
            var reason by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("なぜ変えたい?") },
                text = {
                    Column {
                        OutlinedTextField(
                            value = reason,
                            onValueChange = { reason = it },
                            minLines = 4,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "${reason.length} / ${gate.minLength} 文字",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = { onReason(reason) },
                        enabled = reason.length >= gate.minLength,
                    ) { Text("書いた") }
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text("やめる") } },
            )
        }

        Gate.Password -> {
            var input by remember { mutableStateOf("") }
            var failed by remember { mutableStateOf(false) }
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("パスワード") },
                text = {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it; failed = false },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        isError = failed,
                        supportingText = if (failed) {
                            { Text("違います") }
                        } else {
                            null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        scope.launch {
                            if (verifyPassword(input)) onCleared() else failed = true
                        }
                    }) { Text("確認") }
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text("やめる") } },
            )
        }

        else -> onDismiss()
    }
}

private fun gateStatusText(gate: Gate, change: PendingChange, nowMillis: Long): String =
    when (gate) {
        is Gate.Cooldown -> {
            val elapsedMin = (nowMillis / 1000 - change.entity.createdAtEpochSec) / 60
            val left = (gate.minutes - elapsedMin).coerceAtLeast(0)
            "あと ${left} 分待つ"
        }
        else -> gate.describe()
    }

private fun kindLabel(kind: ChangeKind): String = when (kind) {
    ChangeKind.CREATE -> "新規作成"
    ChangeKind.UPDATE -> "内容の変更"
    ChangeKind.DELETE -> "削除"
    ChangeKind.ENABLE -> "有効化"
    ChangeKind.DISABLE -> "無効化"
}

private fun formatTime(epochSec: Long): String {
    val dt = LocalDateTime.ofInstant(Instant.ofEpochSecond(epochSec), ZoneId.systemDefault())
    return dt.format(DateTimeFormatter.ofPattern("M/d HH:mm"))
}
