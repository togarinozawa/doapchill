package com.dopachiru.data

import android.content.Context
import com.dopachiru.core.sync.AppInfo

/**
 * 他の端末から届いたアプリの名札。
 *
 * ## なぜ要るか
 *
 * 実績には識別子しか入っていません。PC の実績を受け取っても、こちらには
 * `chrome.exe` が何なのか知りようがない ── **入っていないアプリの名前は、
 * その端末からしか分かりません。**
 *
 * 自分の端末のアプリ名は `InstalledApps` が出せるので、ここに入るのは
 * **よその端末のぶんだけ**です。
 *
 * 見た目にしか使わないので、素朴に SharedPreferences に置いてあります。
 * 消えても困りません(次の同期でまた届きます)。
 */
object AppLabels {

    private const val FILE = "app_labels"

    fun remember(context: Context, info: AppInfo) {
        prefs(context).edit().putString(info.uid, info.label).apply()
    }

    /** 見つからなければ識別子をそのまま返します。**空にするより読めるので。** */
    fun labelOf(context: Context, uid: String): String =
        prefs(context).getString(uid, null)
            ?: AppInfo.idOf(uid)?.second
            ?: uid

    fun all(context: Context): Map<String, String> =
        prefs(context).all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
