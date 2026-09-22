package com.dopachiru.core.model

import com.dopachiru.core.action.ActionExtras
import com.dopachiru.core.action.ActionRegistry
import com.dopachiru.core.action.types.BlockAction
import com.dopachiru.core.action.types.DelayAction
import com.dopachiru.core.action.types.RadioAction
import com.dopachiru.core.action.types.WarnAction
import com.dopachiru.core.param.Params

/**
 * 「条件を満たしたら、こうする」を、人が組む形で表したもの。
 *
 * ## なぜ要るか
 *
 * 保存の形は今まで通り actionId + params のまま。だが編集画面では「閉じる」
 * 「少し待たせる」「警告だけ」を、迷わず選べる形として見せたい。その UI 上の状態と、
 * 保存の形との**行き来を1か所に置く** ── Android と Windows で別々に書くと、
 * かたや block、かたや warn に化ける、のような食い違いが必ず出る。
 *
 * ## 対応
 *
 * | UI | 保存 |
 * |---|---|
 * | 使えなくする(やんわり/しっかり) | [BlockAction](押し切れる/切れない) |
 * | 少し待たせて通す | [DelayAction] |
 * | 警告だけ | [WarnAction] |
 * | くわしい | それ以外の措置そのまま |
 *
 * ## 「しばらく閉め出す」はここに無い
 *
 * 以前は CLOSE に「このあとN分」という時限の選択肢を重ねて、内部で
 * [com.dopachiru.core.action.types.LockoutAction] に化けさせていた。
 * けれど「条件が続くあいだ閉める(封印)」と「時間で区切って閉める(閉め出し)」は
 * 仕組みが別物(前者は押し切れる・後者は押し切れない)で、1つのトグルに
 * 畳むと「いま何を選んでいるのか」が読めなくなる。いまは他の「くわしい動作」
 * (音だけ・目的を書く・経過表示など)と同じ並びに置いてある。
 */
enum class MainAction { CLOSE, DELAY, WARN, ADVANCED }

data class ActionPlan(
    val main: MainAction = MainAction.CLOSE,

    /** CLOSE のとき、押し切れる(やんわり)か。 */
    val soft: Boolean = false,

    /** 閉じる前にそっと知らせる秒数。0 = 出さない。CLOSE のときだけ効く。 */
    val prewarnSeconds: Int = 0,

    /** ADVANCED のときに使う措置の id(しばらく閉め出す・音だけ・目的を書く…)。 */
    val advancedActionId: String = RadioAction.id,
) {
    /** この計画を実行する措置の id。 */
    fun actionId(): String = when (main) {
        MainAction.DELAY -> DelayAction.id
        MainAction.WARN -> WarnAction.id
        MainAction.ADVANCED -> advancedActionId
        MainAction.CLOSE -> BlockAction.id
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
            else -> base
        }
        return id to params
    }

    companion object {
        /** 保存の形から UI の形を起こす。 */
        fun from(actionId: String, params: Params): ActionPlan = when (actionId) {
            BlockAction.id -> ActionPlan(
                main = MainAction.CLOSE,
                soft = params.bool(BlockAction.KEY_ALLOW_OVERRIDE, true),
                prewarnSeconds = ActionExtras.prewarnSeconds(params),
            )

            DelayAction.id -> ActionPlan(main = MainAction.DELAY)
            WarnAction.id -> ActionPlan(main = MainAction.WARN)
            // 以前 CLOSE+タイマーで作られていた lockout もここに落ちる。
            // 「ほかの動作にする」に並んでいるので、そのまま編集を続けられる
            else -> ActionPlan(main = MainAction.ADVANCED, advancedActionId = actionId)
        }
    }
}
