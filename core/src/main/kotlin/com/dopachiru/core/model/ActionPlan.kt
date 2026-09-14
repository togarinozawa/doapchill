package com.dopachiru.core.model

import com.dopachiru.core.action.ActionExtras
import com.dopachiru.core.action.ActionRegistry
import com.dopachiru.core.action.types.BlockAction
import com.dopachiru.core.action.types.DelayAction
import com.dopachiru.core.action.types.LockoutAction
import com.dopachiru.core.action.types.RadioAction
import com.dopachiru.core.action.types.WarnAction
import com.dopachiru.core.param.Params

/**
 * 「条件を満たしたら、こうする」を、人が組む形で表したもの。
 *
 * ## なぜ要るか
 *
 * 保存の形は今まで通り actionId + params のまま。だが編集画面では「完全封印か閉め出しか」
 * を選ばせず、**「閉じる」+ 重ねる少しの選択**として見せたい。その UI 上の状態と、
 * 保存の形との**行き来を1か所に置く** ── Android と Windows で別々に書くと、
 * かたや block、かたや lockout に化ける、のような食い違いが必ず出る。
 *
 * ## 対応
 *
 * | UI | 保存 |
 * |---|---|
 * | 閉じる・条件が続くあいだ(やんわり/しっかり) | [BlockAction](押し切れる/切れない) |
 * | 閉じる・N分開けない | [LockoutAction](N分。押し切れない) |
 * | 少し待たせて通す | [DelayAction] |
 * | 警告だけ | [WarnAction] |
 * | くわしい | それ以外の措置そのまま |
 *
 * 完全封印と閉め出しの違い(条件バウンドか、タイマーバウンドか)は、
 * **「閉じたあと開けない」を入れるかどうか**という1つの選択に畳んである。
 */
enum class MainAction { CLOSE, DELAY, WARN, ADVANCED }

data class ActionPlan(
    val main: MainAction = MainAction.CLOSE,

    /** CLOSE のとき、押し切れる(やんわり)か。タイマーを入れたら無視される(閉め出しは押し切れない)。 */
    val soft: Boolean = false,

    /** 0 = 条件が続くあいだ閉じる(完全封印)。1以上 = 閉じて、その分だけ開けない(閉め出し)。 */
    val lockMinutes: Int = 0,

    /** 閉じる前にそっと知らせる秒数。0 = 出さない。CLOSE のときだけ効く。 */
    val prewarnSeconds: Int = 0,

    /** ADVANCED のときに使う措置の id(音だけ・目的を書く・宣言・経過表示)。 */
    val advancedActionId: String = RadioAction.id,
) {
    /** 「閉じたあと開けない」を使っているか。ここが完全封印と閉め出しの分かれ目。 */
    val usesTimer: Boolean get() = main == MainAction.CLOSE && lockMinutes > 0

    /** この計画を実行する措置の id。 */
    fun actionId(): String = when (main) {
        MainAction.DELAY -> DelayAction.id
        MainAction.WARN -> WarnAction.id
        MainAction.ADVANCED -> advancedActionId
        MainAction.CLOSE -> if (usesTimer) LockoutAction.id else BlockAction.id
    }

    /**
     * 保存する (actionId, params) を作る。
     *
     * いま編集中の措置と同じ id に落ちるなら、書いてある値(反省文など)を残す。
     * 別の id に移るときだけ既定に戻す ── 「閉じる」を触っただけで反省文が消えると、
     * 書き直すのが嫌になる。
     */
    fun resolve(currentActionId: String, currentParams: Params): Pair<String, Params> {
        val id = actionId()
        val base =
            if (id == currentActionId) currentParams
            else Params.defaultsOf(ActionRegistry[id]?.params ?: emptyList())

        val params = when (id) {
            BlockAction.id -> base.with(
                BlockAction.KEY_ALLOW_OVERRIDE to soft,
                ActionExtras.KEY_PREWARN_SECONDS to prewarnSeconds,
            )
            LockoutAction.id -> base.with(
                LockoutAction.KEY_MINUTES to lockMinutes.coerceAtLeast(1),
                ActionExtras.KEY_PREWARN_SECONDS to prewarnSeconds,
            )
            else -> base
        }
        return id to params
    }

    companion object {
        /** 保存の形から UI の形を起こす。 */
        fun from(actionId: String, params: Params): ActionPlan = when (actionId) {
            LockoutAction.id -> ActionPlan(
                main = MainAction.CLOSE,
                lockMinutes = params.int(LockoutAction.KEY_MINUTES, 10).coerceAtLeast(1),
                prewarnSeconds = ActionExtras.prewarnSeconds(params),
            )

            BlockAction.id -> ActionPlan(
                main = MainAction.CLOSE,
                soft = params.bool(BlockAction.KEY_ALLOW_OVERRIDE, true),
                lockMinutes = 0,
                prewarnSeconds = ActionExtras.prewarnSeconds(params),
            )

            DelayAction.id -> ActionPlan(main = MainAction.DELAY)
            WarnAction.id -> ActionPlan(main = MainAction.WARN)
            else -> ActionPlan(main = MainAction.ADVANCED, advancedActionId = actionId)
        }
    }
}
