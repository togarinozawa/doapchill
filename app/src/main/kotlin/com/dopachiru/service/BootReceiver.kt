package com.dopachiru.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dopachiru.runtime.DopaRuntime

/**
 * 再起動・アプリ更新のあとに常駐サービスを立て直す。
 *
 * AccessibilityService 自体は有効なままシステムが復帰させるので、
 * ここで面倒を見るのは MonitorService だけでよい。
 * BOOT_COMPLETED はフォアグラウンドサービス起動制限の免除対象。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                DopaRuntime.init(context)
                MonitorService.start(context)
            }
        }
    }
}
