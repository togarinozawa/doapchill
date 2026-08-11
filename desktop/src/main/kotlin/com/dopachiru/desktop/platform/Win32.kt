package com.dopachiru.desktop.platform

import com.sun.jna.Native
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions

/**
 * JNA の標準バインディングに無い Win32 API。
 *
 * プロセスの一時停止は文書化されていない ntdll の関数だが、
 * Windows XP 以降ずっと同じ形で存在していて、タスクマネージャの
 * 「プロセスの停止」も実質これを使っている。
 */
internal interface NtDll : StdCallLibrary {
    fun NtSuspendProcess(processHandle: WinNT.HANDLE): Int
    fun NtResumeProcess(processHandle: WinNT.HANDLE): Int

    companion object {
        val INSTANCE: NtDll =
            Native.load("ntdll", NtDll::class.java, W32APIOptions.DEFAULT_OPTIONS)
    }
}

/**
 * 応答があるかを確かめるための SendMessageTimeout。JNA の User32 には無い。
 *
 * 止まっているプロセスはメッセージを processing しないので、この呼び出しが
 * 時間切れになる。「本当に止まったか」を外から確かめられる数少ない手段。
 */
internal interface User32Ext : StdCallLibrary {
    fun SendMessageTimeout(
        hWnd: com.sun.jna.platform.win32.WinDef.HWND,
        msg: Int,
        wParam: com.sun.jna.platform.win32.WinDef.WPARAM,
        lParam: com.sun.jna.platform.win32.WinDef.LPARAM,
        flags: Int,
        timeoutMs: Int,
        result: com.sun.jna.ptr.PointerByReference,
    ): com.sun.jna.platform.win32.WinDef.LRESULT

    companion object {
        val INSTANCE: User32Ext =
            Native.load("user32", User32Ext::class.java, W32APIOptions.DEFAULT_OPTIONS)
    }
}

internal object Win32Const {
    /** 実行ファイルのパスを引くだけなら、これで足りる(管理者権限が要らない)。 */
    const val PROCESS_QUERY_LIMITED_INFORMATION = 0x1000

    /** 一時停止・再開に要る。 */
    const val PROCESS_SUSPEND_RESUME = 0x0800

    const val SW_MINIMIZE = 6
    const val SW_RESTORE = 9

    /** タスクバーに出ない補助的な窓。JNA の WinUser には無いので自分で持つ。 */
    const val WS_EX_TOOLWINDOW = 0x00000080

    const val WM_NULL = 0x0000
    const val SMTO_ABORTIFHUNG = 0x0002
}
