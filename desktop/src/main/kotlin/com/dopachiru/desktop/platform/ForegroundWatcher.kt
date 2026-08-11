package com.dopachiru.desktop.platform

import com.sun.jna.Native
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.ptr.IntByReference
import java.io.File

/**
 * いま前面にあるアプリ。
 *
 * Android の `packageName` にあたるものは、Windows では実行ファイル名
 * (`chrome.exe` のような)。ルールの対象指定はこれで書く。
 */
data class ForegroundApp(
    /** 小文字の実行ファイル名。例: `chrome.exe` */
    val processName: String,
    val pid: Int,
    val hwnd: WinDef.HWND,
    val windowTitle: String,
    val exePath: String,
) {
    /** 画面に出す名前。`chrome.exe` → `Chrome` */
    val label: String get() = labelFor(processName)

    companion object {
        fun labelFor(processName: String): String =
            processName.removeSuffix(".exe").replaceFirstChar { it.uppercase() }
    }
}

/**
 * 前面ウィンドウを見に行く。
 *
 * Android のユーザー補助のようなイベント通知が Windows には無いので、
 * 1秒ごとに聞きに行く。API 呼び出し2〜3回で終わるので、負荷は誤差の範囲。
 */
object ForegroundWatcher {

    fun current(): ForegroundApp? {
        val user32 = User32.INSTANCE
        val hwnd = user32.GetForegroundWindow() ?: return null

        val pidRef = IntByReference()
        user32.GetWindowThreadProcessId(hwnd, pidRef)
        val pid = pidRef.value
        if (pid == 0) return null

        val exePath = exePathOf(pid) ?: return null
        val processName = File(exePath).name.lowercase()

        val titleBuf = CharArray(512)
        user32.GetWindowText(hwnd, titleBuf, titleBuf.size)

        return ForegroundApp(
            processName = processName,
            pid = pid,
            hwnd = hwnd,
            windowTitle = Native.toString(titleBuf),
            exePath = exePath,
        )
    }

    /** pid から実行ファイルのパス。取れなければ null(権限が足りない保護プロセスなど)。 */
    fun exePathOf(pid: Int): String? {
        val kernel32 = Kernel32.INSTANCE
        val handle = kernel32.OpenProcess(
            Win32Const.PROCESS_QUERY_LIMITED_INFORMATION,
            false,
            pid,
        ) ?: return null

        return try {
            val buf = CharArray(1024)
            val size = IntByReference(buf.size)
            if (!kernel32.QueryFullProcessImageName(handle, 0, buf, size)) return null
            String(buf, 0, size.value)
        } catch (_: Throwable) {
            null
        } finally {
            kernel32.CloseHandle(handle)
        }
    }
}
