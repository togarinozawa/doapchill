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
            help = "ブロック画面に出る文章。自分に効く言葉を書く",
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
    )

    override fun summarize(p: Params): String =
        "完全封印(${p.int(KEY_MIN_SECONDS, 15)}秒)"
}
