package com.dopachiru.service

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * 学習予定を「中断した」と連携アプリに伝える。
 *
 * 学習中は押し切れないようにしてある。その代わりの出口がこれ。
 * 1タップで数分素通りする押し切りと違い、**中断した予定は連携アプリ側で
 * 後の空き時間に再配置され、記録にも残る**。逃げても勉強の総量は減らない。
 *
 * 同時に、許可リストを間違えたときの**閉じ込め事故の出口**でもある。
 * だから連携アプリに届かなかったときでも、こちらの解除は必ず通す
 * ── ここで諦めると「相手が落ちていると閉じ込められる」ことになる。
 */
object StudyAbort {

    private const val ACTION = "dev.togar.dynasched.action.ABORT_SESSION"
    private const val TARGET_PACKAGE = "dev.togar.dynasched"
    private const val EXTRA_ID = "id"

    /** 連携アプリが受け取れる状態か。ボタンの文言を変えるために使う。 */
    fun isAvailable(context: Context): Boolean = runCatching {
        val intent = Intent(ACTION).setPackage(TARGET_PACKAGE)
        context.packageManager
            .queryBroadcastReceivers(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .isNotEmpty()
    }.getOrDefault(false)

    /**
     * 中断を伝える。届いたかどうかは返り値では分からない
     * (ブロードキャストに配達確認は無い)。受け手が居たかどうかだけ返す。
     */
    fun abort(context: Context, windowId: String): Boolean {
        val available = isAvailable(context)
        runCatching {
            context.sendBroadcast(
                Intent(ACTION).apply {
                    setPackage(TARGET_PACKAGE)
                    putExtra(EXTRA_ID, windowId)
                }
            )
        }
        return available
    }
}
