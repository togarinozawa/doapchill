package com.dopachiru.desktop.platform

/**
 * ブラウザとみなす実行ファイル。
 *
 * URL の規則は**これらが前面にあるときにしか効かせない**。
 * 拡張が最後に報せてきた URL は、ブラウザから離れても手元に残り続けるので、
 * ここで絞らないと、メモ帳を開いている最中に
 * 「YouTube のショートを見ている」と判定されうる。
 */
val Browsers: Set<String> = setOf(
    "chrome.exe",
    "msedge.exe",
    "firefox.exe",
    "brave.exe",
    "vivaldi.exe",
    "opera.exe",
    "opera_gx.exe",
    "librewolf.exe",
    "arc.exe",
    "floorp.exe",
)
