package com.dopachiru.desktop.tools

import com.dopachiru.desktop.platform.ForegroundWatcher
import com.dopachiru.desktop.platform.ProtectedProcesses
import com.dopachiru.desktop.platform.RunningApps
import com.dopachiru.desktop.platform.WindowControl

/**
 * Win32 まわりが実機で本当に動くかを確かめる使い捨ての入口。
 *
 *     gradlew :desktop:win32Smoke
 *
 * ここが通らないと、ブロックの土台が全部動かない。
 */
fun main() {
    println("=== 前面のアプリ ===")
    val foreground = ForegroundWatcher.current()
    if (foreground == null) {
        println("  取得できず")
    } else {
        println("  process : ${foreground.processName}")
        println("  label   : ${foreground.label}")
        println("  pid     : ${foreground.pid}")
        println("  title   : ${foreground.windowTitle}")
        println("  path    : ${foreground.exePath}")
        println("  保護対象 : ${foreground.processName in ProtectedProcesses}")
    }

    println()
    println("=== 窓を持って動いているアプリ ===")
    val running = RunningApps.visible()
    println("  ${running.size} 件")
    running.take(15).forEach { println("  - ${it.processName.padEnd(28)} ${it.windowTitle.take(50)}") }

    println()
    println("=== 一時停止と再開 ===")
    ProcessBuilder("notepad.exe").start()
    Thread.sleep(1500)

    // Windows 11 の notepad はストアアプリで、起動した側の pid は別物になることがある。
    // 実際に窓を持っているほうを探す。
    val pid = pidOfWindowOwner("notepad.exe")
    if (pid == null) {
        println("  NG: pid が取れない")
        return
    }
    val hwnd = RunningApps.windowOf(pid)
    if (hwnd == null) {
        println("  NG: ウィンドウが取れない")
        return
    }
    println("  対象 pid=$pid")

    val before = WindowControl.isResponding(hwnd)
    println("  止める前の応答 : $before")

    val suspended = WindowControl.suspend(pid)
    Thread.sleep(300)
    val during = WindowControl.isResponding(hwnd)
    println("  suspend        : $suspended → 応答 $during ${if (!during) "(= 本当に止まっている)" else "(!! 止まっていない)"}")

    val resumed = WindowControl.resume(pid)
    Thread.sleep(300)
    val after = WindowControl.isResponding(hwnd)
    println("  resume         : $resumed → 応答 $after")

    ProcessHandle.of(pid.toLong()).ifPresent { it.destroyForcibly() }
    Thread.sleep(300)
    println("  片付け完了(控え=${WindowControl.suspendedCount})")

    println()
    val ok = before && suspended && !during && resumed && after
    println(if (ok) "OK: 一時停止は本当に効いている" else "NG: どこかが期待どおりでない")
}

/** その名前のプロセスのうち、見えている窓を持っているものの pid。 */
private fun pidOfWindowOwner(processName: String): Int? =
    ProcessHandle.allProcesses()
        .filter { handle ->
            handle.info().command().orElse("").lowercase().endsWith("\\$processName")
        }
        .map { it.pid().toInt() }
        .filter { RunningApps.windowOf(it) != null }
        .findFirst()
        .orElse(null)
