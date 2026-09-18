package com.dopachiru.focus

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import com.dopachiru.MainActivity
import com.dopachiru.R
import com.dopachiru.core.model.Focus
import com.dopachiru.core.model.Lockout
import com.dopachiru.runtime.DopaRuntime
import kotlinx.coroutines.launch

/**
 * ホーム画面に置く大きい集中ボタン(3×4)。
 *
 * ## 1タップのショートカットと何が違うか
 *
 * [FocusShortcutActivity] は長さが決め打ちで、しかも**走っている最中は何も
 * 見えません**。集中中はアプリが全部塞がっていてホーム画面しか見られないのに、
 * そこに残り時間も「最初の一歩」も出ていませんでした。
 * いちばん見たいときに見えないのでは、置いてある意味が薄い。
 *
 * このウィジェットは状態で姿を変えます。
 *
 *  - 走っていないとき: 長さを選ぶ格子(5つ)と「選ぶ」
 *  - 走っているとき: 残り時間・最初の一歩・足すボタン
 *
 * ## 切り上げるボタンは置いていません
 *
 * 足すのは1タップ、やめるのは本体を開いてから([MainActivity])。
 * 自分で決めた時間を1タップで消せるなら、それは決めたことになっていません。
 * 足す側だけ軽いのは、**縛りを増やす方向に摩擦をかける理由が無い**ためです。
 *
 * ## 残り時間はこちらから書き換えません
 *
 * `Chronometer` に任せてあります。RemoteViews を毎分書き換える仕掛けを持つと、
 * 電池を使ったうえに、書き換えを取りこぼしたときに時間が止まって見えます。
 * こちらから書き換えるのは**状態が変わった瞬間だけ**です([refresh])。
 */
class FocusWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        manager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        render(context, manager, appWidgetIds)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_START -> act(context) {
                val minutes = intent.getIntExtra(EXTRA_MINUTES, Focus.DEFAULT_MINUTES)
                // すでに走っているなら足さない。押し間違いで延びると
                // 「触ったら伸びる」を覚えて、ますます触りづらくなる
                if (DopaRuntime.activeFocus() == null) DopaRuntime.startFocus(minutes)
            }

            ACTION_EXTEND -> act(context) {
                DopaRuntime.extendFocus(intent.getIntExtra(EXTRA_MINUTES, 5))
            }
        }
    }

    /**
     * 押されたときの処理。
     *
     * ウィジェットは**アプリの息が止まっている状態から叩かれます**。封鎖は
     * 起動直後には温まっていないので、温め終わってから見ないと
     * 「走っていないこと」にして二重に始めてしまいます。
     */
    private fun act(context: Context, body: () -> Unit) {
        DopaRuntime.init(context)
        val pending = goAsync()
        DopaRuntime.scope.launch {
            try {
                DopaRuntime.lockouts.warmUp()
                body()
                refresh(context)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val ACTION_START = "com.dopachiru.widget.START"
        private const val ACTION_EXTEND = "com.dopachiru.widget.EXTEND"
        private const val EXTRA_MINUTES = "minutes"

        /** 走っていないときに並べる長さ。最後の1枠は「選ぶ」に使う。 */
        private val CHOICES = listOf(15, 25, 45, 60, 90)

        /** 走っているときに足せる長さ。 */
        private val EXTENSIONS = listOf(5, 10, 30)

        private val BUTTONS = listOf(
            R.id.widget_button_1,
            R.id.widget_button_2,
            R.id.widget_button_3,
            R.id.widget_button_4,
            R.id.widget_button_5,
            R.id.widget_button_6,
        )

        /**
         * 置いてあるウィジェットを全部描き直す。
         *
         * 集中が始まった・明けた・切り上げられた瞬間に呼びます。呼ばないと、
         * 明けたあとも残り時間が残って見えます。**置かれていなければ何もしません。**
         */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = runCatching {
                manager.getAppWidgetIds(ComponentName(context, FocusWidget::class.java))
            }.getOrNull() ?: return
            if (ids.isEmpty()) return
            render(context, manager, ids)
        }

        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            DopaRuntime.init(context)
            val focus = DopaRuntime.activeFocus()
            val views = RemoteViews(context.packageName, R.layout.widget_focus)
            if (focus != null) running(context, views, focus) else idle(context, views)
            ids.forEach { manager.updateAppWidget(it, views) }
        }

        // ---- 走っていないとき ------------------------------------------

        private fun idle(context: Context, views: RemoteViews) {
            views.setTextViewText(R.id.widget_title, context.getString(R.string.widget_focus_idle_title))
            views.setViewVisibility(R.id.widget_countdown, View.GONE)
            views.setTextViewText(
                R.id.widget_note,
                context.getString(R.string.widget_focus_idle_note),
            )

            CHOICES.forEachIndexed { index, minutes ->
                val id = BUTTONS[index]
                views.setViewVisibility(id, View.VISIBLE)
                views.setTextViewText(id, "${minutes}分")
                views.setOnClickPendingIntent(id, startIntent(context, minutes))
            }

            // 最後の1枠は「選ぶ」。決まった長さに無いものを始めたいときの逃げ道で、
            // ここが無いと5つの中から選ぶしかなくなる
            val pick = BUTTONS[CHOICES.size]
            views.setViewVisibility(pick, View.VISIBLE)
            views.setTextViewText(pick, context.getString(R.string.widget_focus_pick))
            views.setOnClickPendingIntent(pick, activityIntent(context, FocusPickerActivity::class.java))

            views.setTextViewText(R.id.widget_open, context.getString(R.string.widget_focus_open))
            views.setOnClickPendingIntent(
                R.id.widget_open,
                activityIntent(context, MainActivity::class.java),
            )
        }

        // ---- 走っているとき --------------------------------------------

        private fun running(context: Context, views: RemoteViews, focus: Lockout) {
            views.setTextViewText(R.id.widget_title, context.getString(R.string.widget_focus_running_title))

            val remainingMs = (focus.untilEpochSec * 1000L) - System.currentTimeMillis()
            views.setViewVisibility(R.id.widget_countdown, View.VISIBLE)
            views.setChronometer(
                R.id.widget_countdown,
                SystemClock.elapsedRealtime() + remainingMs.coerceAtLeast(0L),
                null,
                true,
            )
            // 進むのではなく減る。API 24 以降。minSdk 29 なので必ず効く
            views.setChronometerCountDown(R.id.widget_countdown, true)

            // 「最初の一歩」が入っていればそれを出す。塞いだだけでは行き先が無い
            views.setTextViewText(R.id.widget_note, focus.reason)

            EXTENSIONS.forEachIndexed { index, minutes ->
                val id = BUTTONS[index]
                views.setViewVisibility(id, View.VISIBLE)
                views.setTextViewText(id, "+${minutes}分")
                views.setOnClickPendingIntent(id, extendIntent(context, minutes))
            }
            // 余った枠は消す。空の箱が並ぶより、無いほうが読める
            for (index in EXTENSIONS.size until BUTTONS.size) {
                views.setViewVisibility(BUTTONS[index], View.GONE)
            }

            views.setTextViewText(R.id.widget_open, context.getString(R.string.widget_focus_stop))
            views.setOnClickPendingIntent(
                R.id.widget_open,
                activityIntent(context, MainActivity::class.java),
            )
        }

        // ---- 押されたときの宛先 ----------------------------------------

        private fun startIntent(context: Context, minutes: Int): PendingIntent =
            broadcast(context, ACTION_START, minutes)

        private fun extendIntent(context: Context, minutes: Int): PendingIntent =
            broadcast(context, ACTION_EXTEND, minutes)

        /**
         * 分ごとに別の [PendingIntent] にする必要があるので、要求コードに分数を混ぜる。
         * 同じ要求コードで作ると、あとから作ったほうの extras が
         * **前のものに差し替わって**、どのボタンを押しても同じ長さになる。
         */
        private fun broadcast(context: Context, action: String, minutes: Int): PendingIntent {
            val intent = Intent(context, FocusWidget::class.java).apply {
                this.action = action
                putExtra(EXTRA_MINUTES, minutes)
            }
            return PendingIntent.getBroadcast(
                context,
                action.hashCode() + minutes,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /**
         * `CLEAR_TASK` は付けない。付けると、ルールを編集している途中で
         * ウィジェットを押しただけで積み上がった画面が消える。
         */
        private fun activityIntent(context: Context, target: Class<*>): PendingIntent =
            PendingIntent.getActivity(
                context,
                target.name.hashCode(),
                Intent(context, target).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
    }
}
