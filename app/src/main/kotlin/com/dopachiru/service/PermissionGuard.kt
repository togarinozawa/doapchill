package com.dopachiru.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.dopachiru.DopaApplication
import com.dopachiru.R

/**
 * 見張り(ユーザー補助・重ね表示)が外れていないか、常駐サービスの折々に確かめる。
 *
 * ## なぜ要るか
 *
 * ドパチルの取り締まりは、そのほとんどがユーザー補助の上に乗っている。これを切られると、
 * アプリは起動していても**何も止めない**。いちばん危ないのは、切ったこと自体を忘れて
 * 「守られているつもり」で過ごすこと ── 誘惑に負けて切ったのなら、なおさら気づかない。
 * だから外れたら、目に入る強さの通知で知らせる。
 *
 * ## 一度でも許可したものだけ見張る
 *
 * 入れたてでまだ許可していない段階で鳴らすと、設定を促す案内と区別がつかず、ただ煩い。
 * **一度許可したものが消えたとき**だけ鳴らす。許可した事実は端末に残すので、
 * プロセスが作り直されても判断がぶれない。
 *
 * ## 重ね表示について
 *
 * ブロック画面は本来ユーザー補助のオーバーレイで出すので、重ね表示は切られても
 * すぐ困るわけではない(フォールバック用)。それでもユーザーが気にしていたので、
 * 一度許可したものが外れたら同じように知らせる ── 気づける材料は多いほうがよい。
 */
object PermissionGuard {

    /**
     * いまの許可の状態を確かめ、外れていれば通知する。外れていなければ通知を消す。
     * 常駐サービスのループから、そのつど呼ぶ。
     */
    fun check(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val accessibilityOn = isAccessibilityEnabled(context)
        val overlayOn = runCatching { Settings.canDrawOverlays(context) }.getOrDefault(true)

        // 許可を観測したら「一度は許可された」と覚える
        prefs.edit().apply {
            if (accessibilityOn) putBoolean(KEY_ACC_GRANTED, true)
            if (overlayOn) putBoolean(KEY_OVERLAY_GRANTED, true)
            apply()
        }

        val accessibilityLost = !accessibilityOn && prefs.getBoolean(KEY_ACC_GRANTED, false)
        val overlayLost = !overlayOn && prefs.getBoolean(KEY_OVERLAY_GRANTED, false)

        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (!accessibilityLost && !overlayLost) {
            manager.cancel(NOTIFICATION_ID)
            return
        }
        // 通知を出す許可自体が無い端末では、鳴らせないので黙って戻る
        runCatching { manager.notify(NOTIFICATION_ID, build(context, accessibilityLost, overlayLost)) }
    }

    private fun build(context: Context, accessibilityLost: Boolean, overlayLost: Boolean) =
        NotificationCompat.Builder(context, DopaApplication.CHANNEL_GUARD)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("ドパチルの見張りが外れています")
            .setContentText(message(accessibilityLost, overlayLost))
            .setStyle(NotificationCompat.BigTextStyle().bigText(message(accessibilityLost, overlayLost)))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(fixIntent(context, accessibilityLost))
            .build()

    private fun message(accessibilityLost: Boolean, overlayLost: Boolean): String = when {
        accessibilityLost && overlayLost ->
            "ユーザー補助と重ね表示の許可が外れています。いまは制限が効きません。タップして戻してください。"
        accessibilityLost ->
            "ユーザー補助が外れています。いまはアプリを何も止められません。タップして戻してください。"
        else ->
            "重ね表示の許可が外れています。ブロック画面が出せないことがあります。タップして戻してください。"
    }

    /** 外れているほうの設定へ直行させる。両方なら、まず要のユーザー補助へ。 */
    private fun fixIntent(context: Context, accessibilityLost: Boolean): PendingIntent {
        val intent = if (accessibilityLost) {
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        } else {
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            context,
            if (accessibilityLost) 0 else 1,
            intent,
            PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** ドパチルのユーザー補助が有効か。設定画面と同じ見方。 */
    private fun isAccessibilityEnabled(context: Context): Boolean {
        val expected = "${context.packageName}/${DopaAccessibilityService::class.java.name}"
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private const val PREFS = "permission_guard"
    private const val KEY_ACC_GRANTED = "accessibility_once_granted"
    private const val KEY_OVERLAY_GRANTED = "overlay_once_granted"
    private const val NOTIFICATION_ID = 1002
}
