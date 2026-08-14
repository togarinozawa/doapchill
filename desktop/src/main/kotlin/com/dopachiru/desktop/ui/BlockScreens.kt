package com.dopachiru.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dopachiru.desktop.Presentation
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * 完全封印の画面。Android 版と同じ文言・同じ間で出す。
 *
 * [Presentation.Block.minSeconds] のあいだは閉じるボタンが出ない。
 */
@Composable
fun BlockScreen(
    block: Presentation.Block,
    escapeHoldSeconds: Int = 0,
    onDismiss: () -> Unit,
    onOverride: () -> Unit,
) = DopaTheme {
    var remaining by remember(block.key) { mutableIntStateOf(block.minSeconds) }

    LaunchedEffect(block.key) {
        remaining = block.minSeconds
        while (remaining > 0) {
            delay(1000)
            remaining -= 1
        }
    }

    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xFF0B0B12)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 560.dp).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                block.label,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                block.ruleName,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )

            Spacer(Modifier.height(40.dp))

            Text(
                block.reflection.ifBlank { "いま開く必要はある?" },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(56.dp))

            if (remaining > 0) {
                Text(
                    "$remaining",
                    fontSize = 56.sp,
                    fontWeight = FontWeight.Light,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "秒",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text("わかった、やめる", modifier = Modifier.padding(vertical = 6.dp))
                }
                Spacer(Modifier.height(12.dp))
                if (block.allowOverride) {
                    TextButton(onClick = onOverride, enabled = block.canAfford) {
                        Text(
                            if (block.overrideCost > 0) {
                                "それでも使う(${block.overrideCost}ポイント)"
                            } else {
                                "それでも使う(連続記録が途切れます)"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // 払う額と閉まる範囲は押す前に見せる。後出しの罰は理不尽なだけで効かない
                    val note = when {
                        !block.canAfford -> "残り ${block.balance}ポイント。足りないので押し切れません"
                        block.overrideCost > 0 && block.penaltyNote.isNotBlank() ->
                            "残り ${block.balance}ポイント / ${block.penaltyNote}"
                        block.overrideCost > 0 -> "残り ${block.balance}ポイント"
                        else -> block.penaltyNote
                    }
                    if (note.isNotBlank()) {
                        Text(
                            note,
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.Center,
                            color = if (block.canAfford) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                    }
                } else {
                    Text(
                        "この時間は押し切れません",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(48.dp))

            // 最前面の全画面で出ている以上、こちらの不具合で閉じられなくなったときの
            // 逃げ道が必ず要る。抑止のためのものが端末を人質に取ってはいけない。
            Text(
                if (escapeHoldSeconds > 0) {
                    "Esc を押し続けています… あと ${ESCAPE_HOLD_SECONDS - escapeHoldSeconds} 秒で一時停止"
                } else {
                    "動かなくなったら Esc を ${ESCAPE_HOLD_SECONDS} 秒押し続けると一時停止します"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (escapeHoldSeconds > 0) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                },
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** これだけ押し続けたら一時停止。うっかりでは通らない程度に長く。 */
const val ESCAPE_HOLD_SECONDS = 3

/**
 * 罰で閉まっているときの画面。
 *
 * ブロック画面と違い、押し切る手立てが無い。あるのは「あと何分か」だけ。
 * 残り時間を隠さないのは、見えない拘束がいちばん人を追い詰めるため。
 *
 * Esc 長押しの逃げ道はここにも残してある。罰は自分で選んだものだが、
 * こちらの不具合で閉じられなくなる可能性はブロック画面と変わらない。
 */
@Composable
fun LockedScreen(
    locked: Presentation.Locked,
    escapeHoldSeconds: Int = 0,
    onMinimize: () -> Unit,
) = DopaTheme {
    var remainingSec by remember(locked.key) {
        mutableIntStateOf((locked.untilEpochSec - System.currentTimeMillis() / 1000).coerceAtLeast(0).toInt())
    }

    LaunchedEffect(locked.key) {
        while (remainingSec > 0) {
            delay(1000)
            remainingSec = (locked.untilEpochSec - System.currentTimeMillis() / 1000)
                .coerceAtLeast(0)
                .toInt()
        }
    }

    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xFF120B0B)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 560.dp).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                locked.label,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            Text(
                "お預け中",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (locked.reason.isBlank()) "ルールを破った罰" else "「${locked.reason}」を破った罰",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(48.dp))

            val minutes = remainingSec / 60
            val seconds = remainingSec % 60
            Text(
                if (minutes > 0) "$minutes" else "$seconds",
                fontSize = 64.sp,
                fontWeight = FontWeight.Light,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                if (minutes > 0) "分ほど残っています" else "秒",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(48.dp))

            Button(
                onClick = onMinimize,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text("このアプリを閉じる", modifier = Modifier.padding(vertical = 6.dp))
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "押し切る手段はありません。時間が過ぎれば自動で開きます。",
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(32.dp))
            Text(
                if (escapeHoldSeconds > 0) {
                    "Esc を押し続けています… あと ${ESCAPE_HOLD_SECONDS - escapeHoldSeconds} 秒で一時停止"
                } else {
                    "動かなくなったら Esc を ${ESCAPE_HOLD_SECONDS} 秒押し続けると一時停止します"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (escapeHoldSeconds > 0) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                },
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 警告。下のアプリはそのまま操作できる(ウィンドウ側で focusable を切る)。 */
@Composable
fun WarnScreen(message: String) = DopaTheme {
    Card(
        modifier = Modifier.fillMaxSize(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xE61E1E2E)),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                message,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(20.dp),
            )
        }
    }
}

/** 開く前に持ち時間を宣言させる画面。 */
@Composable
fun DeclareScreen(
    declare: Presentation.Declare,
    onDeclare: (minutes: Int, reason: String) -> Unit,
    onCancel: () -> Unit,
) = DopaTheme {
    var minutes by remember(declare.key) {
        mutableIntStateOf(declare.defaultMinutes.coerceIn(1, declare.maxMinutes))
    }
    var reason by remember(declare.key) { mutableStateOf("") }
    val canSubmit = !declare.requireReason || reason.isNotBlank()

    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xFF0B0B12)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 480.dp).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                declare.label,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            Text(
                "今回は何分使う?",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(32.dp))
            Text(
                "$minutes 分",
                fontSize = 44.sp,
                fontWeight = FontWeight.Light,
                color = MaterialTheme.colorScheme.primary,
            )
            Slider(
                value = minutes.toFloat(),
                onValueChange = { minutes = it.roundToInt().coerceIn(1, declare.maxMinutes) },
                valueRange = 1f..declare.maxMinutes.toFloat(),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "1分",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "${declare.maxMinutes}分",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (declare.requireReason) {
                Spacer(Modifier.height(20.dp))
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text("何のために使う?") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
            }

            Spacer(Modifier.height(28.dp))
            Button(
                onClick = { onDeclare(minutes, reason) },
                enabled = canSubmit,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text("${minutes}分で終わらせる", modifier = Modifier.padding(vertical = 6.dp))
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onCancel) {
                Text("やっぱりやめる", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
