package com.dopachiru

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.dopachiru.runtime.DopaRuntime
import com.dopachiru.service.MonitorService
import com.dopachiru.ui.DopaApp
import com.dopachiru.ui.theme.DopaTheme

class MainActivity : ComponentActivity() {

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // 断られても監視自体は動く。常駐通知が出せなくなるだけ。
            if (granted) MonitorService.start(this)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        DopaRuntime.init(this)

        ensureNotificationPermission()
        // ユーザーが見ている状態からの起動なので、ここが一番確実に常駐を立てられる
        MonitorService.start(this)

        setContent {
            DopaTheme {
                DopaApp()
            }
        }
    }

    /**
     * Android 13 以降、常駐サービスの通知を出すにはランタイム許可が要る。
     * 通知が出せないと、そもそも Foreground Service を維持できない。
     */
    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
