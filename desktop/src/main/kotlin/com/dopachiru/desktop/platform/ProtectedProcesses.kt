package com.dopachiru.desktop.platform

/**
 * 何があってもブロックしないプロセス。
 *
 * Android 版の ProtectedApps と同じ役割で、ルールより強く、設定からも外せない。
 * 「必要なアプリ以外ぜんぶ」が書ける以上、選ばなくても巻き込めてしまうので、
 * 巻き込まれると詰むものはここで弾く。
 *
 * タスクマネージャを入れてあるのは意図的。ドパチル自身を必ず止められるようにしておく。
 * 自分で入れたものを自分で止められなくなるのは、抑止ではなく事故なので。
 */
object ProtectedProcesses {

    private val names = setOf(
        // シェルと画面まわり。塞ぐとデスクトップが操作できなくなる
        "explorer.exe",
        "dwm.exe",
        "shellexperiencehost.exe",
        "startmenuexperiencehost.exe",
        "searchhost.exe",
        "searchapp.exe",
        "textinputhost.exe",
        "ctfmon.exe",
        "applicationframehost.exe",

        // ログオン・認証
        "winlogon.exe",
        "logonui.exe",
        "csrss.exe",
        "lsass.exe",
        "consent.exe",
        "credentialuibroker.exe",

        // 逃げ道。ドパチルを止める手段は必ず残す
        "taskmgr.exe",
        "systemsettings.exe",
        "control.exe",
        "mmc.exe",

        // ドパチル自身(開発中は java/javaw から起動する)
        "dopachiru.exe",
        "javaw.exe",
        "java.exe",
    )

    operator fun contains(processName: String): Boolean = processName.lowercase() in names

    /** 一覧表示用。 */
    fun all(): Set<String> = names
}
