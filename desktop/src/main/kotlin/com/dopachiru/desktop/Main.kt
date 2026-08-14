package com.dopachiru.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.dopachiru.desktop.ui.BlockScreen
import com.dopachiru.desktop.ui.DeclareScreen
import com.dopachiru.desktop.ui.DesktopApp
import com.dopachiru.desktop.ui.ESCAPE_HOLD_SECONDS
import com.dopachiru.desktop.ui.LockedScreen
import com.dopachiru.desktop.ui.WarnScreen
import kotlinx.coroutines.delay

/**
 * Windows 版のエントリポイント。
 *
 * 常駐はトレイ。ウィンドウを閉じても監視は続く。
 * ブロック画面は、必要になったときだけ現れる別のウィンドウとして出す
 * ── Android の TYPE_ACCESSIBILITY_OVERLAY にあたるものが Windows には無いので、
 * 「最前面・枠なし・全画面」の普通のウィンドウで代用する。
 */
fun main() = application {
    LaunchedEffect(Unit) { DesktopRuntime.start() }

    val settings by DesktopRuntime.settings.collectAsState()
    val presentation by DesktopRuntime.presentation.collectAsState()
    var windowOpen by remember { mutableStateOf(true) }

    Tray(
        icon = remember(settings.paused) { TrayIcon(settings.paused) },
        tooltip = if (settings.paused) "ドパチル(一時停止中)" else "ドパチル",
        onAction = { windowOpen = true },
        menu = {
            Item("開く", onClick = { windowOpen = true })
            CheckboxItem(
                "一時停止",
                checked = settings.paused,
                onCheckedChange = { paused ->
                    DesktopRuntime.updateSettings { it.copy(paused = paused) }
                },
            )
            Separator()
            Item(
                "終了",
                onClick = {
                    DesktopRuntime.flush()
                    exitApplication()
                },
            )
        },
    )

    if (windowOpen) {
        Window(
            onCloseRequest = { windowOpen = false },
            title = "ドパチル",
            state = rememberWindowState(size = DpSize(760.dp, 680.dp)),
        ) {
            DesktopApp()
        }
    }

    when (val current = presentation) {
        is Presentation.Block -> {
            var escHeld by remember(current.key) { mutableStateOf(false) }
            var escSeconds by remember(current.key) { mutableIntStateOf(0) }

            LaunchedEffect(escHeld, current.key) {
                if (!escHeld) {
                    escSeconds = 0
                    return@LaunchedEffect
                }
                while (escSeconds < ESCAPE_HOLD_SECONDS) {
                    delay(1000)
                    escSeconds += 1
                }
                DesktopRuntime.emergencyPause()
            }

            OverlayWindow(
                onKeyEvent = { event ->
                    if (event.key == Key.Escape) {
                        escHeld = event.type == KeyEventType.KeyDown
                        true
                    } else {
                        false
                    }
                }
            ) {
                BlockScreen(
                    block = current,
                    escapeHoldSeconds = escSeconds,
                    onDismiss = { DesktopRuntime.dismissBlock() },
                    onOverride = { DesktopRuntime.overrideBlock() },
                )
            }
        }

        is Presentation.Locked -> {
            var escHeld by remember(current.key) { mutableStateOf(false) }
            var escSeconds by remember(current.key) { mutableIntStateOf(0) }

            // 罰の最中でも逃げ道は残す。罰は自分で選んだものだが、
            // こちらの不具合で閉じられなくなる可能性はブロック画面と変わらない
            LaunchedEffect(escHeld, current.key) {
                if (!escHeld) {
                    escSeconds = 0
                    return@LaunchedEffect
                }
                while (escSeconds < ESCAPE_HOLD_SECONDS) {
                    delay(1000)
                    escSeconds += 1
                }
                DesktopRuntime.emergencyPause()
            }

            OverlayWindow(
                onKeyEvent = { event ->
                    if (event.key == Key.Escape) {
                        escHeld = event.type == KeyEventType.KeyDown
                        true
                    } else {
                        false
                    }
                }
            ) {
                LockedScreen(
                    locked = current,
                    escapeHoldSeconds = escSeconds,
                    onMinimize = { DesktopRuntime.dismissBlock() },
                )
            }
        }

        is Presentation.Declare -> OverlayWindow {
            DeclareScreen(
                declare = current,
                onDeclare = { minutes, reason ->
                    DesktopRuntime.declare(current.processName, minutes, reason)
                },
                onCancel = { DesktopRuntime.cancelDeclare() },
            )
        }

        is Presentation.Warn -> Window(
            onCloseRequest = {},
            title = "ドパチル",
            undecorated = true,
            transparent = true,
            alwaysOnTop = true,
            focusable = false,
            resizable = false,
            state = rememberWindowState(
                size = DpSize(460.dp, 110.dp),
                position = WindowPosition(Alignment.TopCenter),
            ),
        ) {
            WarnScreen(current.message)
        }

        null -> Unit
    }
}

/**
 * トレイの絵。止めているかどうかで色を変える。
 * アイコン用の依存をわざわざ足さずに済むので、その場で描く。
 */
private class TrayIcon(private val paused: Boolean) : Painter() {
    override val intrinsicSize = Size(32f, 32f)

    override fun DrawScope.onDraw() {
        drawCircle(color = if (paused) Color(0xFF5A5A6B) else Color(0xFF9BB8FF))
        drawCircle(color = Color(0xFF0B0B12), radius = size.minDimension / 4.5f)
    }
}

/** 閉じられない・最前面・全画面のウィンドウ。 */
@Composable
private fun androidx.compose.ui.window.ApplicationScope.OverlayWindow(
    onKeyEvent: (KeyEvent) -> Boolean = { false },
    content: @Composable () -> Unit,
) {
    Window(
        onCloseRequest = {},
        title = "ドパチル",
        undecorated = true,
        alwaysOnTop = true,
        resizable = false,
        state = rememberWindowState(placement = WindowPlacement.Fullscreen),
        onKeyEvent = onKeyEvent,
    ) {
        content()
    }
}
