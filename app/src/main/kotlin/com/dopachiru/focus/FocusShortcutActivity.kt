package com.dopachiru.focus

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.dopachiru.R
import com.dopachiru.core.model.Focus
import com.dopachiru.runtime.DopaRuntime

/**
 * ホーム画面から1タップで集中を始めるための入口。
 *
 * 画面を出さずに始めて、すぐ閉じる。
 * **ドパチルを開いてから始めるのでは遅い** ── そのときにはもう
 * スマホを握っていて、通知もタイムラインも目に入っている。
 * 置き場所が近いことがそのまま使われる回数になる。
 *
 * すでに走っているときは何も足さない。押し間違いで延びると、
 * 「触ったら伸びる」ことを覚えてしまって、ますます触りづらくなる。
 */
class FocusShortcutActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val minutes = intent?.getIntExtra(EXTRA_MINUTES, 0)?.takeIf { it > 0 }
            ?: DopaRuntime.focusSettings.shortcutMinutes

        val message = when {
            DopaRuntime.activeFocus() != null -> "すでに集中中です"
            DopaRuntime.startFocus(minutes) -> "${Focus.clampMinutes(minutes)}分の集中を始めました"
            else -> "始められませんでした"
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

        finish()
        overridePendingTransition(0, 0)
    }

    companion object {
        const val EXTRA_MINUTES = "minutes"

        fun intent(context: Context, minutes: Int): Intent =
            Intent(context, FocusShortcutActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                putExtra(EXTRA_MINUTES, minutes)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }

        /**
         * ホーム画面に置く。
         *
         * ランチャーが対応していなければ false。その場合はアプリのアイコンを
         * 長押しして出る候補から自分でドラッグしてもらう(そちらは常に出る)。
         */
        fun requestPin(context: Context, minutes: Int): Boolean {
            if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) return false
            val shortcut = ShortcutInfoCompat.Builder(context, "focus_$minutes")
                .setShortLabel("${minutes}分集中")
                .setLongLabel("${minutes}分だけ集中する")
                .setIcon(IconCompat.createWithResource(context, R.drawable.ic_notification))
                .setIntent(intent(context, minutes))
                .build()
            return ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
        }
    }
}
