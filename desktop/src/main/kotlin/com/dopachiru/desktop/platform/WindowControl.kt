package com.dopachiru.desktop.platform

import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

/** ブロックのやり方。強いほど逃げにくいが、荒い。 */
@Serializable
enum class BlockStrength(val displayName: String, val description: String) {
    OVERLAY(
        "オーバーレイのみ",
        "最前面に全画面の窓を出す。Alt+Tab や仮想デスクトップで裏に回れる余地が残る。",
    ),
    MINIMIZE(
        "最小化し続ける",
        "オーバーレイに加えて、対象のウィンドウを最小化し続ける。戻してもまた最小化される。",
    ),
    SUSPEND(
        "プロセスを一時停止",
        "対象のプロセスを止める。最も強いが、保存していない編集内容やゲームの状態を壊すことがある。",
    ),
}

/**
 * ウィンドウとプロセスの操作。
 *
 * ## 一時停止の後始末について
 * 止めたまま終了すると、そのプロセスは誰にも再開されず固まったままになる。
 * 止めた pid は必ず控えて、[resumeAll] を終了フックから呼ぶ。
 * ドパチルが落ちてもプロセスが巻き添えにならないための最低限の約束。
 */
object WindowControl {

    private val suspendedPids = ConcurrentHashMap.newKeySet<Int>()

    init {
        Runtime.getRuntime().addShutdownHook(
            Thread({ resumeAll() }, "dopachiru-resume-on-exit")
        )
    }

    fun minimize(hwnd: WinDef.HWND) {
        runCatching { User32.INSTANCE.ShowWindow(hwnd, Win32Const.SW_MINIMIZE) }
    }

    fun suspend(pid: Int): Boolean {
        if (pid in suspendedPids) return true
        val handle = Kernel32.INSTANCE.OpenProcess(Win32Const.PROCESS_SUSPEND_RESUME, false, pid)
            ?: return false
        return try {
            val ok = NtDll.INSTANCE.NtSuspendProcess(handle) == 0
            if (ok) suspendedPids.add(pid)
            ok
        } catch (_: Throwable) {
            false
        } finally {
            Kernel32.INSTANCE.CloseHandle(handle)
        }
    }

    fun resume(pid: Int): Boolean {
        val handle = Kernel32.INSTANCE.OpenProcess(Win32Const.PROCESS_SUSPEND_RESUME, false, pid)
        if (handle == null) {
            // もう居ないプロセスは控えから外す
            suspendedPids.remove(pid)
            return false
        }
        return try {
            val ok = NtDll.INSTANCE.NtResumeProcess(handle) == 0
            suspendedPids.remove(pid)
            ok
        } catch (_: Throwable) {
            false
        } finally {
            Kernel32.INSTANCE.CloseHandle(handle)
        }
    }

    /** 止めているプロセスを全部戻す。終了時と、ブロックを解くときに呼ぶ。 */
    fun resumeAll() {
        suspendedPids.toList().forEach { resume(it) }
    }

    fun isSuspended(pid: Int): Boolean = pid in suspendedPids

    val suspendedCount: Int get() = suspendedPids.size

    /**
     * そのウィンドウがメッセージを処理しているか。
     * 止まっているプロセスは応えないので、一時停止が本当に効いたかを外から確かめられる。
     */
    fun isResponding(hwnd: WinDef.HWND, timeoutMs: Int = 800): Boolean = runCatching {
        val result = com.sun.jna.ptr.PointerByReference()
        val ret = User32Ext.INSTANCE.SendMessageTimeout(
            hwnd,
            Win32Const.WM_NULL,
            WinDef.WPARAM(0),
            WinDef.LPARAM(0),
            Win32Const.SMTO_ABORTIFHUNG,
            timeoutMs,
            result,
        )
        ret.toLong() != 0L
    }.getOrDefault(false)
}
