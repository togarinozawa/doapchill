package com.dopachiru.block

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dopachiru.core.model.ReminderKind
import com.dopachiru.ui.theme.DopaBlockTheme

/** 「あと3分」「あと40秒」。閉まった後は「まもなく」。 */
fun remainingLabel(seconds: Long): String = when {
    seconds <= 0 -> "まもなく"
    seconds >= 90 -> "あと${(seconds + 30) / 60}分"
    else -> "あと${seconds}秒"
}

/**
 * 閉じる前の知らせ。下のアプリは操作できる(PASS_THROUGH)ので、見えるが邪魔にはならない作りにする。
 *
 * 数字は出す時に固定せず、閉じる時刻から毎秒数え直す。出しっぱなしの「あと3分」が
 * 本当は2分半だった、が起きない。
 */
@Composable
fun ReminderScreen(kind: ReminderKind, closesAtMillis: Long, words: String) = DopaBlockTheme {
    var left by remember { mutableLongStateOf(secondsLeft(closesAtMillis)) }
    LaunchedEffect(closesAtMillis) {
        while (true) {
            left = secondsLeft(closesAtMillis)
            kotlinx.coroutines.delay(1000)
        }
    }
    val label = remainingLabel(left)
    when (kind) {
        ReminderKind.BAND -> Band("$label で閉じます", 0x99_1E1E2E)
        ReminderKind.WORDS -> Band(words.ifBlank { "そろそろ区切りをつけよう" } + "  ($label)", 0xCC_1E1E2E, big = true)
        ReminderKind.BIG -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = label,
                fontSize = 96.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White.copy(alpha = 0.55f),
                textAlign = TextAlign.Center,
            )
        }
        ReminderKind.EDGE -> {
            val pulse by rememberInfiniteTransition(label = "edge").animateFloat(
                initialValue = 0.35f,
                targetValue = 0.9f,
                animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
                label = "edgeAlpha",
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .alpha(pulse)
                    .border(10.dp, Color(0xFFFFB74D)),
            )
        }
        ReminderKind.VIBRATE -> Unit
    }
}

/** 前回の「次に開いたらやること」を、開いた直後にそっと見せる。下は操作できる。 */
@Composable
fun MemoNoticeScreen(memo: String) = DopaBlockTheme {
    Band("前回のメモ: $memo", 0xCC_1E1E2E, big = true)
}

@Composable
private fun Band(text: String, argb: Long, big: Boolean = false) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Text(
            text = text,
            style = if (big) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .safeDrawingPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .fillMaxWidth()
                .background(Color(argb), RoundedCornerShape(12.dp))
                .padding(vertical = if (big) 18.dp else 10.dp, horizontal = 12.dp),
        )
    }
}

private fun secondsLeft(closesAtMillis: Long): Long =
    ((closesAtMillis - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
