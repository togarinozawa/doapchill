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
        DopaRuntime.init(this)

        // 型が指しているなら、その範囲(グループだけ / グループ以外 / 全部)で止める。
        // 型が無ければ、これまでどおり「逃がすもの以外ぜんぶ」を決め打ちの長さで。
        val template = intent?.getStringExtra(EXTRA_TEMPLATE_ID)?.let { DopaRuntime.focusTemplate(it) }
        val minutes = intent?.getIntExtra(EXTRA_MINUTES, 0)?.takeIf { it > 0 }
            ?: template?.minutes?.takeIf { it > 0 }
            ?: DopaRuntime.focusSettings.shortcutMinutes

        val message = when {
            DopaRuntime.activeFocus() != null -> "すでに集中中です"
            template != null && !template.isUsable -> "この型はタグが空です。設定から選び直してください"
            template != null && DopaRuntime.startFocus(template, minutes) ->
                "${template.displayLabel()}・${Focus.clampMinutes(minutes)}分を始めました"
            template == null && DopaRuntime.startFocus(minutes) ->
                "${Focus.clampMinutes(minutes)}分の集中を始めました"
            else -> "始められませんでした"
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

        finish()
        overridePendingTransition(0, 0)
    }

    companion object {
        const val EXTRA_MINUTES = "minutes"
        const val EXTRA_TEMPLATE_ID = "template"

        fun intent(context: Context, minutes: Int): Intent =
            Intent(context, FocusShortcutActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                putExtra(EXTRA_MINUTES, minutes)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }

        private fun templateIntent(context: Context, templateId: String): Intent =
            Intent(context, FocusShortcutActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                putExtra(EXTRA_TEMPLATE_ID, templateId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }

        /**
         * ホーム画面に置く。
         *
         * ランチャーが対応していなければ false。その場合はアプリのアイコンを
         * 長押しして出る候補から自分でドラッグしてもらう(そちらは常に出る)。
         */
        fun requestPin(context: Context, minutes: Int): Boolean =
            pin(
                context = context,
                id = "focus_$minutes",
                shortLabel = "${minutes}分集中",
                longLabel = "${minutes}分だけ集中する",
                intent = intent(context, minutes),
            )

        /**
         * 「長さを選ぶ」ほうをホーム画面に置く。
         *
         * 決め打ちのボタンを何個も並べるより、これ1つのほうが場所を食わない。
         * 決め打ちが要るのは、選ぶ手間すら惜しい長さが1つだけあるとき。
         */
        fun requestPinPicker(context: Context): Boolean =
            pin(
                context = context,
                id = "focus_pick",
                shortLabel = "長さを選ぶ",
                longLabel = "何分止めるかを選んで始める",
                intent = FocusPickerActivity.intent(context),
            )

        /**
         * 型([FocusTemplate])をホーム画面に置く。
         *
         * 1タップの型([FocusTemplate.isOneTap])は画面を出さずに始まる入口へ、
         * 長さを選ぶ型は格子の入口へ繋ぐ。ラベルは型の名前をそのまま出す ──
         * ホームに並んだとき、どれが何を止めるボタンかが分かる。
         */
        fun requestPinTemplate(context: Context, template: com.dopachiru.core.model.FocusTemplate): Boolean {
            val label = template.displayLabel()
            return if (template.isOneTap) {
                pin(
                    context = context,
                    id = "focus_tmpl_${template.id}",
                    shortLabel = label.take(10),
                    longLabel = "$label(${template.minutes}分)",
                    intent = templateIntent(context, template.id),
                )
            } else {
                pin(
                    context = context,
                    id = "focus_tmpl_${template.id}",
                    shortLabel = label.take(10),
                    longLabel = "$label(長さを選ぶ)",
                    intent = FocusPickerActivity.intent(context, template.id),
                )
            }
        }

        private fun pin(
            context: Context,
            id: String,
            shortLabel: String,
            longLabel: String,
            intent: Intent,
        ): Boolean {
            if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) return false
            val shortcut = ShortcutInfoCompat.Builder(context, id)
                .setShortLabel(shortLabel)
                .setLongLabel(longLabel)
                .setIcon(IconCompat.createWithResource(context, R.drawable.ic_notification))
                .setIntent(intent)
                .build()
            return ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
        }
    }
}
