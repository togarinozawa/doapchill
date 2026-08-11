package com.dopachiru.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.telecom.TelecomManager
import android.view.inputmethod.InputMethodManager

/**
 * 何があってもブロックしないアプリ。
 *
 * ルールの対象指定より強く、設定画面からも外せない。ユーザーが自分の意思で
 * 選べる場所に置いてはいけない類のもの ── 電話がかけられなくなる設定を
 * うっかり作れてしまうこと自体が事故なので。
 *
 * 正指定しかできなかったうちは、電話を封印するには電話アプリをわざわざ選ぶ
 * 必要があったので事故にならなかった。「必要なアプリ以外ぜんぶ」(許可リスト型)を
 * 入れた時点で、選ばなくても巻き込めるようになる。この床はその対になっている。
 */
class ProtectedApps(context: Context) {

    val packages: Set<String> = buildSet {
        add(context.packageName)
        add("android")
        add("com.android.systemui")

        val pm = context.packageManager

        // 電話。既定のダイヤラーと、発信を扱えるアプリすべて
        runCatching {
            context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage
        }.getOrNull()?.let { add(it) }
        addAll(resolve(pm, Intent(Intent.ACTION_DIAL)))
        addAll(resolve(pm, Intent("com.android.phone.EmergencyDialer.DIAL")))

        // ホーム。ここを塞ぐとブロック画面から逃げる先が無くなる
        addAll(resolve(pm, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)))

        // 設定。ドパチル自身を無効化する道は必ず残す(引き止めはするが塞がない)
        addAll(resolve(pm, Intent(Settings.ACTION_SETTINGS)))
        add("com.android.settings")

        // 入力メソッド。塞ぐと文字が打てなくなる
        runCatching {
            context.getSystemService(InputMethodManager::class.java)
                ?.enabledInputMethodList
                ?.mapNotNull { it.packageName }
                ?: emptyList()
        }.getOrDefault(emptyList()).let(::addAll)
    }

    operator fun contains(packageName: String): Boolean = packageName in packages

    private fun resolve(pm: PackageManager, intent: Intent): Set<String> = runCatching {
        pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .mapNotNull { it.activityInfo?.packageName }
            .toSet()
    }.getOrDefault(emptySet())
}
