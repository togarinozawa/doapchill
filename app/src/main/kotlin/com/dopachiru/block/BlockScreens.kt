package com.dopachiru.block

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dopachiru.core.action.types.BlockAction
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
 *
 * 代わりに [onAbortStudy] があるときは「予定を中断する」を出す。押し切りと違い、
 * 中断した予定は連携アプリ側で後の空き時間に再配置される ── 逃げても総量は減らない。
 *
 * [overrideCost] が 0 より大きいと、押し切りにポイントが要る。[balance] が足りなければ
 * ボタンは出るが押せない ── 消してしまうと「なぜ押せないのか」が分からなくなる。
 * [penaltyNote] には、押し切ったあとに何が閉まるかを先に書いておく。
 * 払う額と閉まる範囲を押す前に見せるのが肝で、後出しの罰は理不尽なだけで効かない。
 */
@Composable
fun BlockScreen(
    appLabel: String,
    ruleName: String,
    reflection: String,
    minSeconds: Int,
    allowOverride: Boolean = true,
    overrideCost: Int = 0,
    balance: Int = 0,
    penaltyNote: String = "",
    releaseEffort: String = BlockAction.Effort.TAP,
    rotationNote: String = "",
    onAbortStudy: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    onOverride: () -> Unit,
) = DopaBlockTheme {
    val canAfford = overrideCost <= 0 || balance >= overrideCost
    var showEffortGate by remember(reflection) { mutableStateOf(false) }
    var remaining by remember { mutableIntStateOf(minSeconds) }
    var confirmingAbort by remember { mutableStateOf(false) }

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
                when {
                    allowOverride -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        TextButton(
                            // 1タップで通れる警告は92%が無視される。手を動かさせる
                            onClick = {
                                if (releaseEffort == BlockAction.Effort.TAP) {
                                    onOverride()
                                } else {
                                    showEffortGate = true
                                }
                            },
                            enabled = canAfford,
                        ) {
                            Text(
                                when {
                                    overrideCost > 0 -> "それでも使う(${overrideCost}ポイント)"
                                    else -> "それでも使う(連続記録が途切れます)"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        val note = when {
                            !canAfford -> "残り ${balance}ポイント。足りないので押し切れません"
                            overrideCost > 0 && penaltyNote.isNotBlank() ->
                                "残り ${balance}ポイント / $penaltyNote"
                            overrideCost > 0 -> "残り ${balance}ポイント"
                            else -> penaltyNote
                        }
                        if (note.isNotBlank()) {
                            Text(
                                note,
                                style = MaterialTheme.typography.labelSmall,
                                textAlign = TextAlign.Center,
                                color = if (canAfford) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                            )
                        }
                    }

                    onAbortStudy != null && confirmingAbort -> Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            "中断した予定は、あとの空き時間に組み直されます。\n" +
                                "やる量は減りません。それでも中断しますか?",
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row {
                            TextButton(onClick = { confirmingAbort = false }) {
                                Text("やめておく", style = MaterialTheme.typography.bodySmall)
                            }
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = onAbortStudy) {
                                Text(
                                    "中断する",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }

                    onAbortStudy != null -> TextButton(onClick = { confirmingAbort = true }) {
                        Text(
                            "予定を中断する",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    else -> Text(
                        "この時間は押し切れません",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (rotationNote.isNotBlank()) {
                Spacer(Modifier.height(28.dp))
                Text(
                    rotationNote,
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
        }
    }

    if (showEffortGate) {
        ReleaseEffortGate(
            effort = releaseEffort,
            onCancel = { showEffortGate = false },
            onPass = { showEffortGate = false; onOverride() },
        )
    }
}

/**
 * 押し切る前にひと手間かけさせる関門。
 *
 * 確認ダイアログを1つ増やすだけでは意味がない ── GoalKeeper では
 * **警告ダイアログの92%が「使い続ける」で無視された**。効くのはタップ数ではなく、
 * 「意識的な操作をさせること」のほうなので、押し続けるか打ち込ませる。
 *
 * 手間で諦めさせるのが狙いではない。**System 2 を一度起こすこと**が狙い。
 */
@Composable
fun ReleaseEffortGate(
    effort: String,
    onCancel: () -> Unit,
    onPass: () -> Unit,
) {
    val phrase = "いま見なくていい"
    var typed by remember { mutableStateOf("") }
    var holding by remember { mutableStateOf(false) }
    var held by remember { mutableIntStateOf(0) }

    LaunchedEffect(holding) {
        if (!holding) {
            held = 0
            return@LaunchedEffect
        }
        while (held < HOLD_SECONDS) {
            delay(100)
            held += 1
            if (held >= HOLD_SECONDS) onPass()
        }
    }

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
                "本当に使う?",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(28.dp))

            if (effort == BlockAction.Effort.TYPE) {
                Text(
                    "「$phrase」と打ち込むと進めます",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onPass,
                    enabled = typed.trim() == phrase,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text("それでも使う", modifier = Modifier.padding(vertical = 6.dp))
                }
            } else {
                Text(
                    if (holding) "そのまま押し続ける…" else "3秒押し続けると進めます",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {},
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onPress = {
                                    holding = true
                                    tryAwaitRelease()
                                    holding = false
                                },
                            )
                        },
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text(
                        if (holding) "${(HOLD_SECONDS - held) / 10 + 1}…" else "長押しして使う",
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onCancel) {
                Text("やっぱりやめる", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** 長押しの必要時間。100ms 刻みで数えるので 30 = 3秒。 */
private const val HOLD_SECONDS = 30

/**
 * 数秒待たせてから、必ず通す画面。
 *
 * 押し切りボタンが無いのがこの画面の肝。拒まれないので反発が起きにくく、
 * それでいて反射で掴んだ手を一度止められる。
 * 「閉じる」は出さない ── 待てば勝手に消えるので、閉じる操作そのものが要らない。
 */
@Composable
fun DelayScreen(
    appLabel: String,
    message: String,
    seconds: Int,
    rotationNote: String,
    onDone: () -> Unit,
) = DopaBlockTheme {
    var remaining by remember(message) { mutableIntStateOf(seconds) }

    LaunchedEffect(message) {
        remaining = seconds
        while (remaining > 0) {
            delay(1000)
            remaining -= 1
        }
        onDone()
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
                appLabel,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(40.dp))
            Text(
                message,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(48.dp))
            Text(
                "$remaining",
                fontSize = 56.sp,
                fontWeight = FontWeight.Light,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "秒たったら開きます",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (rotationNote.isNotBlank()) {
                Spacer(Modifier.height(32.dp))
                Text(
                    rotationNote,
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
        }
    }
}

/**
 * 経過時間だけを隅に出す。下のアプリはそのまま操作できる。
 *
 * 無限スクロールや自動再生が効くのは、いま何分経ったかを分からなくさせるから
 * (ACDP #10 "Time Fog")。その直接の対抗が、時計を画面に出し続けること。
 * 止めに来る画面ではなく、自分でやめる材料を渡すだけの表示。
 */
@Composable
fun SessionTimerScreen(minutes: Int, todayMinutes: Int?) = DopaBlockTheme {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopEnd,
    ) {
        Card(
            modifier = Modifier
                .safeDrawingPadding()
                .padding(12.dp),
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xCC1E1E2E)),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.End,
            ) {
                Text(
                    "${minutes}分",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (todayMinutes != null) {
                    Text(
                        "今日 ${todayMinutes}分",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * 罰で閉まっているときの画面。
 *
 * ブロック画面と違い、押し切る手段が無い。あるのは「あと何分か」だけ。
 * 自分で選んだ罰なので、時間が過ぎるのを待つ以外に道は無い ──
 * けれど**必ず過ぎる**。ホームには戻れるし、ドパチルの設定も開ける。
 * 残り時間を隠さないのは、見えない拘束がいちばん人を追い詰めるため。
 */
@Composable
fun LockoutScreen(
    appLabel: String,
    reason: String,
    untilEpochSec: Long,
    onGoHome: () -> Unit,
    /**
     * 自分で始めた集中のときだけ渡す。罰では null。
     *
     * 罰と集中は同じ封鎖の仕組みで動くが、**画面に出す約束が正反対**になる。
     * 罰は「押し切る手段はありません」、集中は「足せる・切り上げられる」。
     * ここを取り違えると、どちらかの画面が嘘をつく。
     */
    focus: FocusControls? = null,
) = DopaBlockTheme {
    var remainingSec by remember(untilEpochSec) {
        mutableIntStateOf((untilEpochSec - System.currentTimeMillis() / 1000).coerceAtLeast(0).toInt())
    }

    LaunchedEffect(untilEpochSec) {
        while (remainingSec > 0) {
            delay(1000)
            remainingSec = (untilEpochSec - System.currentTimeMillis() / 1000)
                .coerceAtLeast(0)
                .toInt()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF120B0B)),
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
            Spacer(Modifier.height(24.dp))
            Text(
                text = if (focus != null) "集中中" else "お預け中",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Medium,
                color = if (focus != null) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = when {
                    focus != null -> reason.ifBlank { "自分で始めた集中" }
                    reason.isBlank() -> "ルールを破った罰"
                    else -> "「$reason」を破った罰"
                },
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(48.dp))

            val minutes = remainingSec / 60
            val seconds = remainingSec % 60
            Text(
                text = if (minutes > 0) "$minutes" else "$seconds",
                fontSize = 64.sp,
                fontWeight = FontWeight.Light,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = if (minutes > 0) "分ほど残っています" else "秒",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(48.dp))

            Button(
                onClick = onGoHome,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text("ホームに戻る", modifier = Modifier.padding(vertical = 6.dp))
            }

            if (focus == null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "押し切る手段はありません。時間が過ぎれば自動で開きます。",
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Spacer(Modifier.height(20.dp))
                FocusPanel(focus)
            }
        }
    }
}

/** 集中中の封鎖画面から使える操作。 */
data class FocusControls(
    val extendChoices: List<Int>,
    val onExtend: (Int) -> Unit,
    /** いま切り上げるのに要るポイント。0 なら無料(押し間違いの猶予のうち)。 */
    val abortCost: Int,
    val balance: Int,
    val abortEffort: String,
    val onEndEarly: () -> Unit,
) {
    val canAfford: Boolean get() = abortCost <= 0 || balance >= abortCost
}

/**
 * 集中中の「足す」と「切り上げる」。
 *
 * 足すほうを**先に、押しやすく**置いてある。短く始めて足す前提なので、
 * ここがいちばんよく使う操作になる。切り上げるほうは文字だけにして、
 * 押してからさらに手間を通す。
 */
@Composable
private fun FocusPanel(focus: FocusControls) {
    var ending by remember { mutableStateOf(false) }

    if (ending) {
        Text(
            if (focus.abortCost > 0) {
                "切り上げると ${focus.abortCost} ポイント引かれます(残り ${focus.balance})"
            } else {
                "いまなら無料で取り消せます"
            },
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        if (focus.canAfford) {
            ReleaseEffortGate(
                effort = focus.abortEffort,
                onCancel = { ending = false },
                onPass = { focus.onEndEarly() },
            )
        } else {
            Text(
                "ポイントが足りないので切り上げられません。時間が過ぎれば開きます。",
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { ending = false }) { Text("戻る") }
        }
        return
    }

    Text(
        "まだ足りなければ足せます",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        focus.extendChoices.forEach { minutes ->
            OutlinedButton(onClick = { focus.onExtend(minutes) }) { Text("+${minutes}分") }
        }
    }
    Spacer(Modifier.height(12.dp))
    TextButton(onClick = { ending = true }) {
        Text(
            if (focus.abortCost > 0) "切り上げる(${focus.abortCost}ポイント)" else "取り消す",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
