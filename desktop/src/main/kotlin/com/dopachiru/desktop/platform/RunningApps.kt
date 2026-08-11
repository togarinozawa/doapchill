package com.dopachiru.desktop.platform

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference
import java.io.File

/**
 * ルールの対象を選ぶための一覧。
 *
 * Android の「インストール済みアプリ」にあたるものは Windows には無い
 * (インストーラを経由しないものがいくらでもある)。代わりに
 * **いまウィンドウを持って動いているもの**を出す。
 *
 * 一度見えたものは覚えておく。閉じたアプリもルールから外れないように。
 */
object RunningApps {

    data class RunningApp(
        val processName: String,
        val label: String,
        /** 見えているウィンドウのタイトル。どのアプリか思い出す手がかりに出す。 */
        val windowTitle: String,
    )

    /** その pid が持っている、見えているウィンドウ。 */
    fun windowOf(pid: Int): WinDef.HWND? {
        val user32 = User32.INSTANCE
        var found: WinDef.HWND? = null
        user32.EnumWindows({ hwnd: WinDef.HWND, _: Pointer? ->
            if (found == null && user32.IsWindowVisible(hwnd)) {
                val pidRef = IntByReference()
                user32.GetWindowThreadProcessId(hwnd, pidRef)
                if (pidRef.value == pid) found = hwnd
            }
            true
        }, null)
        return found
    }

    /** いま見えているウィンドウを持つプロセス。 */
    fun visible(): List<RunningApp> {
        val user32 = User32.INSTANCE
        val found = LinkedHashMap<String, String>()

        user32.EnumWindows({ hwnd: WinDef.HWND, _: Pointer? ->
            collect(user32, hwnd, found)
            true
        }, null)

        return found.map { (process, title) ->
            RunningApp(
                processName = process,
                label = ForegroundApp.labelFor(process),
                windowTitle = title,
            )
        }.sortedBy { it.label.lowercase() }
    }

    private fun collect(
        user32: User32,
        hwnd: WinDef.HWND,
        into: MutableMap<String, String>,
    ) {
        if (!user32.IsWindowVisible(hwnd)) return

        // タイトルの無い窓は、たいてい実体のない管理用の窓
        val titleBuf = CharArray(512)
        user32.GetWindowText(hwnd, titleBuf, titleBuf.size)
        val title = Native.toString(titleBuf)
        if (title.isBlank()) return

        // ツールウィンドウはタスクバーにも出ない。一覧に出す意味がない
        val exStyle = user32.GetWindowLong(hwnd, WinUser.GWL_EXSTYLE)
        if (exStyle and Win32Const.WS_EX_TOOLWINDOW != 0) return

        val pidRef = IntByReference()
        user32.GetWindowThreadProcessId(hwnd, pidRef)
        val pid = pidRef.value
        if (pid == 0) return

        val exePath = ForegroundWatcher.exePathOf(pid) ?: return
        val processName = File(exePath).name.lowercase()
        if (processName in ProtectedProcesses) return

        into.putIfAbsent(processName, title)
    }
}
