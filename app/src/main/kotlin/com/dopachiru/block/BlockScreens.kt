package com.dopachiru.block

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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import com.dopachiru.ui.theme.DopaBlockTheme
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * 完全封印のブロック画面。
 *
 * [minSeconds] のあいだは閉じるボタンが出ない。すぐ閉じられないこと自体が抑止になる。
 *
 * [allowOverride] が false のときは「それでも使う」が出ない。学習予定の最中など、
 * 逃げ道を残さないと決めた場面で使う。ホームには戻れるので閉じ込めにはならない。
 */
@Composable
fun BlockScreen(
    appLabel: String,
    ruleName: String,
    reflection: String,
    minSeconds: Int,
    allowOverride: Boolean = true,
    onDismiss: () -> Unit,
    onOverride: () -> Unit,
) = DopaBlockTheme {
    var remaining by remember { mutableIntStateOf(minSeconds) }

    LaunchedEffect(minSeconds) {
        remaining = minSeconds
        while (remaining > 0) {
            delay(1000)
            remaining -= 1
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0B12)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .safeDrawingPadding()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = appLabel,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = ruleName,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )

            Spacer(Modifier.height(40.dp))

            Text(
                text = reflection.ifBlank { "いま開く必要はある?" },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(56.dp))

            if (remaining > 0) {
                Text(
                    text = "$remaining",
                    fontSize = 56.sp,
                    fontWeight = FontWeight.Light,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "秒",
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
                if (allowOverride) {
                    TextButton(onClick = onOverride) {
                        Text(
                            "それでも使う(連続記録が途切れます)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        }
    }
}

/**
 * 警告表示。下のアプリはそのまま操作できる。
 *
 * 半透明で重ねたうえで下を操作させるため、TYPE_ACCESSIBILITY_OVERLAY で出す必要がある
 * (TYPE_APPLICATION_OVERLAY だと Android 12 以降タッチが下に届かない)。
 */
@Composable
fun WarnScreen(message: String) = DopaBlockTheme {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Card(
            modifier = Modifier
                .safeDrawingPadding()
                .padding(16.dp)
                .fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xE61E1E2E)),
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
            )
        }
    }
}

/** 開く前に持ち時間を宣言させる画面。 */
@Composable
fun DeclareScreen(
    appLabel: String,
    maxMinutes: Int,
    defaultMinutes: Int,
    requireReason: Boolean,
    onDeclare: (minutes: Int, reason: String) -> Unit,
    onCancel: () -> Unit,
) = DopaBlockTheme {
    var minutes by remember { mutableIntStateOf(defaultMinutes.coerceIn(1, maxMinutes)) }
    var reason by remember { mutableStateOf("") }
    val canSubmit = !requireReason || reason.isNotBlank()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0B12)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .safeDrawingPadding()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = appLabel,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            Text(
                text = "今回は何分使う?",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground,
            )

            Spacer(Modifier.height(32.dp))

            Text(
                text = "$minutes 分",
                fontSize = 44.sp,
                fontWeight = FontWeight.Light,
                color = MaterialTheme.colorScheme.primary,
            )
            Slider(
                value = minutes.toFloat(),
                onValueChange = { minutes = it.roundToInt().coerceIn(1, maxMinutes) },
                valueRange = 1f..maxMinutes.toFloat(),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("1分", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${maxMinutes}分", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (requireReason) {
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
            TextButton(
                onClick = onCancel,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
            ) {
                Text("やっぱりやめる")
            }
        }
    }
}

/**
 * ドパチル自身の設定を触ろうとしたときに出す引き止め。
 *
 * ここは意図的に「止められない」設計にしていない。カウントダウンのあと必ず進める。
 * 自分で入れたアプリを自分で無効化できなくなるのは、抑止ではなく事故なので。
 */
@Composable
fun SelfDefenseScreen(
    streak: Int,
    minSeconds: Int,
    onProceed: () -> Unit,
    onGoBack: () -> Unit,
) = DopaBlockTheme {
    var remaining by remember { mutableIntStateOf(minSeconds) }

    LaunchedEffect(minSeconds) {
        remaining = minSeconds
        while (remaining > 0) {
            delay(1000)
            remaining -= 1
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFA0B0B12)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .safeDrawingPadding()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "ドパチルを止めようとしている",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(20.dp))
            if (streak > 0) {
                Text(
                    "$streak 日続いている記録が、ここで終わる。",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Spacer(Modifier.height(12.dp))
            }
            Text(
                "本当に必要なら止めていい。ただ、いま止めたい理由が\n" +
                    "「使いたいから」でないかどうかだけ確かめて。",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(44.dp))

            Button(
                onClick = onGoBack,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text("やめておく", modifier = Modifier.padding(vertical = 6.dp))
            }
            Spacer(Modifier.height(10.dp))
            if (remaining > 0) {
                Text(
                    "$remaining 秒後に進めます",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                TextButton(onClick = onProceed) {
                    Text(
                        "それでも設定を開く",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** ロック解除の直後に一瞬だけ出す問いかけ。 */
@Composable
fun UnlockPromptScreen(message: String, onDismiss: () -> Unit) = DopaBlockTheme {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xF20B0B12)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .safeDrawingPadding()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(40.dp))
            Row {
                Button(onClick = onDismiss, shape = RoundedCornerShape(14.dp)) {
                    Text("ある")
                }
                Spacer(Modifier.width(12.dp))
                TextButton(onClick = onDismiss) {
                    Text("ない", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
