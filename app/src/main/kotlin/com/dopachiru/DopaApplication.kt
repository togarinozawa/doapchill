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
        val channel = NotificationChannel(
            CHANNEL_MONITOR,
            getString(R.string.notification_channel_monitor),
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            setShowBadge(false)
            description = "監視が動いていることを示すだけの通知"
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_MONITOR = "monitor"
    }
}
