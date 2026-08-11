package com.dopachiru.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.dopachiru.DopaApplication
import com.dopachiru.MainActivity
import com.dopachiru.R
import com.dopachiru.runtime.DopaRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 常駐サービス。
 *
 * 検知そのものは AccessibilityService 側で完結しているので、これは検知の前提ではない。
 * 役割は2つだけ:
 *   - プロセス優先度を上げて、メーカー独自のタスク管理に落とされにくくする
 *   - 使用時間・宣言の消費を一定間隔で刻む
 */
class MonitorService : Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + job)

    override fun onCreate() {
        super.onCreate()
        DopaRuntime.init(this)
        startForegroundCompat()
        scope.launch {
            while (isActive) {
                // 次にどれだけ眠ってよいかは runtime 側が決める。
                // 画面が消えているあいだは数えるものが無いので長く眠る。
                delay(DopaRuntime.tick())
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        job.cancel()
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, DopaApplication.CHANNEL_MONITOR)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_monitor_title))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .build()

        startForeground(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
    }

    companion object {
        private const val NOTIFICATION_ID = 1001

        /**
         * バックグラウンドからの起動が制限される場合があるので、失敗しても落とさない。
         * Android 15 以降は「バッテリー最適化の除外」が有効な免除条件になる。
         */
        fun start(context: Context) {
            runCatching {
                context.startForegroundService(Intent(context, MonitorService::class.java))
            }
        }
    }
}
