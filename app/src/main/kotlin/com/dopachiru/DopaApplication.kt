package com.dopachiru

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.dopachiru.runtime.DopaRuntime

class DopaApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        DopaRuntime.init(this)
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val monitor = NotificationChannel(
            CHANNEL_MONITOR,
            getString(R.string.notification_channel_monitor),
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            setShowBadge(false)
            description = "監視が動いていることを示すだけの通知"
        }
        // 見張りが外れたときだけ鳴らす。ここを黙らせると気づけないので、
        // 常駐通知(MIN)とは別に、目に入る強さの口を分けて持つ。
        val guard = NotificationChannel(
            CHANNEL_GUARD,
            getString(R.string.notification_channel_guard),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            setShowBadge(true)
            description = "ユーザー補助や重ね表示の許可が外れて、制限が効かなくなったときの警告"
        }
        // 使い過ぎの知らせは見張りの警告と口を分ける。こちらは本人が音を消したくなる
        // かもしれないが、見張りの警告まで一緒に黙ると、外れたことに気づけない
        val overuse = NotificationChannel(
            CHANNEL_OVERUSE,
            getString(R.string.notification_channel_overuse),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "いつもより長く使っているときの知らせ"
        }
        manager.createNotificationChannel(monitor)
        manager.createNotificationChannel(guard)
        manager.createNotificationChannel(overuse)
    }

    companion object {
        const val CHANNEL_MONITOR = "monitor"
        const val CHANNEL_GUARD = "guard"
        const val CHANNEL_OVERUSE = "overuse"
    }
}
