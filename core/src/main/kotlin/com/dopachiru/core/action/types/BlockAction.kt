package com.dopachiru.core.action.types

import com.dopachiru.core.action.ActionType
import com.dopachiru.core.param.ParamSpec
import com.dopachiru.core.param.Params

/** 完全封印。全画面のブロック画面を出し、しばらく閉じられなくする。 */
object BlockAction : ActionType {
    const val KEY_REFLECTION = "reflection"
    const val KEY_MIN_SECONDS = "minSeconds"
    const val KEY_COVER_SYSTEM_BARS = "coverSystemBars"
    const val KEY_ALLOW_OVERRIDE = "allowOverride"
    const val KEY_RELEASE_EFFORT = "releaseEffort"

    /** 押し切るのに要る手間。 */
    object Effort {
        /** ボタンを1回押すだけ。 */
        const val TAP = "tap"

        /** 3秒押し続ける。 */
        const val HOLD = "hold"

        /** 決められた言葉を打ち込む。 */
        const val TYPE = "type"
    }

    override val id = "block"
    override val displayName = "完全封印"
    override val description = "全画面でブロック画面を出す。指定秒数が経つまで閉じられない。"
    override val severity = 100

    override val params = listOf(
        ParamSpec.TextParam(
            KEY_REFLECTION,
            "反省文",
            default = "これを開こうとした理由を、いま一度考える。",
            multiline = true,
            help = "改行で分けると、開くたびに1つずつ選ばれる。同じ文が続くと慣れて効かなくなる",
        ),
        ParamSpec.IntParam(
            KEY_MIN_SECONDS,
            "閉じられるまで",
            default = 15,
            min = 0,
            max = 300,
            unit = "秒",
        ),
        ParamSpec.BoolParam(
            KEY_COVER_SYSTEM_BARS,
            "ナビゲーションバーごと覆う",
            default = true,
            help = "オフにすると戻る・ホームがすぐ押せるぶん抑止力が下がる",
        ),
        ParamSpec.BoolParam(
            KEY_ALLOW_OVERRIDE,
            "押し切って使えるようにする",
            default = true,
            help = "オフにすると逃げ道が無くなる。学習予定中は、この設定に関わらず押し切れない",
        ),
        // 警告ダイアログの92%は「使い続ける」で無視された(GoalKeeper, IMWUT 2019)。
        // 1タップで通れるものは、事実上そこに無いのと変わらない。
        ParamSpec.EnumParam(
            KEY_RELEASE_EFFORT,
            "押し切るのに要る手間",
            options = listOf(
                ParamSpec.EnumParam.Option(Effort.TAP, "1回押す"),
                ParamSpec.EnumParam.Option(Effort.HOLD, "3秒押し続ける"),
                ParamSpec.EnumParam.Option(Effort.TYPE, "言葉を打ち込む"),
            ),
            default = Effort.HOLD,
            help = "1タップで通れる警告は92%が無視される。手を動かさせるほど効く",
        ),
    )

    override fun summarize(p: Params): String =
        "完全封印(${p.int(KEY_MIN_SECONDS, 15)}秒)"
}
