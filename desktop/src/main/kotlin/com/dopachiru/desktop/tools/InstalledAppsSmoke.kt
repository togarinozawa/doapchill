package com.dopachiru.desktop.tools

import com.dopachiru.desktop.platform.InstalledApps

/**
 * 入っているアプリの一覧が、この機械で本当に取れるかを見る。
 * `gradlew :desktop:installedAppsSmoke`
 *
 * スタートメニューの中身は環境ごとに違うので、テストでは確かめようがない。
 * 実機で目視するための道具として置いてある(単体テストは [com.dopachiru.desktop.platform.ShellLink] の
 * バイト列の読み方だけを見ている)。
 */
fun main() {
    val started = System.currentTimeMillis()
    val apps = InstalledApps.refresh()
    val elapsed = System.currentTimeMillis() - started

    println("入っているアプリ: ${apps.size} 件 (${elapsed}ms)")
    println()
    apps.take(40).forEach { println("  ${it.processName.padEnd(28)} ${it.label}") }
    if (apps.size > 40) println("  … ほか ${apps.size - 40} 件")
    println()
    println(if (apps.size >= 5) "OK: 一覧として使える数が取れている" else "NG: 少なすぎる。走査先かパースを疑う")
}
