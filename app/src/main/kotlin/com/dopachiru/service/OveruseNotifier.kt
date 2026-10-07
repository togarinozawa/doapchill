package com.dopachiru.service

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.dopachiru.DopaApplication
import com.dopachiru.R

/** 使い過ぎの知らせを通知で出す。使っている画面には割り込まない。 */
object OveruseNotifier {

    fun notify(context: Context, pkg: String, appLabel: String, message: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val notification = NotificationCompat.Builder(context, DopaApplication.CHANNEL_OVERUSE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("${appLabel}を長く使っています")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            // 同じアプリの知らせは1枚に重ねる。繰り返すたびに通知欄が埋まらないように
            .setOnlyAlertOnce(false)
            .build()
        // 通知を出す許可が無い端末では鳴らせないので、黙って戻る
        runCatching { manager.notify(NOTIFICATION_BASE + (pkg.hashCode() and 0xFFFF), notification) }
    }

    private const val NOTIFICATION_BASE = 0x40000
}
