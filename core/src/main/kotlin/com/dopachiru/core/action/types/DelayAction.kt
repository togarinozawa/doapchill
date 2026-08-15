package com.dopachiru.core.action.types

import com.dopachiru.core.action.ActionType
import com.dopachiru.core.param.ParamSpec
import com.dopachiru.core.param.Params

/**
 * 少し待たせてから、必ず通す。
 *
 * 止めるのではなく、**報酬までの時間を伸ばす**だけの措置。
 * Lyngs ら(CHI 2019)が367個のツールを二重過程モデルで分類したとき、
 * 自己制御の成否を決める3要素(報酬の大きさ・成功見込み・**報酬までの遅延**)のうち、
 * **遅延を直接扱うツールはわずか4%** しかなかった。ブロックは74%が持っている。
 *
 * 遅延が効くのは、System 1 の反射と System 2 の意識のあいだに隙間を作るから。
 * 反射で掴んだ手は、数秒立ち止まれるだけで目的を思い出すことがある。
 *
 * ## 押し切りボタンが無い
 * わざと置いていない。**必ず通る**ので押し切る必要が無く、
 * 「拒まれた」という感覚が生まれない。InteractOut(2024)が
 * 従来のロックアウトに対して**受容度 +25.3%、削減 追加15.6%** を出したのも、
 * 遮断ではなく入力に微細な妨害を入れる作りだったため。
 *
 * 摩擦は毎回かけると嫌われる([com.dopachiru.core.condition.types.ChanceCondition]
 * と組み合わせて、たまにだけ出すのがよい)。
 */
object DelayAction : ActionType {
    const val KEY_SECONDS = "seconds"
    const val KEY_MESSAGE = "message"

    override val id = "delay"
    override val displayName = "少し待たせる"
    override val description = "数秒待たせてから必ず通す。止めないので押し切る必要が無い。"

    /** 警告(10)より上、宣言(50)より下。止めはしないが、警告よりは手を止めさせる。 */
    override val severity = 30

    override val params = listOf(
        ParamSpec.IntParam(
            KEY_SECONDS,
            "待たせる時間",
            default = 5,
            min = 1,
            max = 60,
            unit = "秒",
            help = "長すぎると回避されるので、まずは5秒あたりから",
        ),
        ParamSpec.TextParam(
            KEY_MESSAGE,
            "待っているあいだに出す文",
            default = "何をしに開いた?",
            multiline = true,
            help = "改行で分けると、開くたびに1つずつ選ばれる(同じ文が続くと慣れる)",
        ),
    )

    override fun summarize(p: Params): String = "${p.int(KEY_SECONDS, 5)}秒待たせる"
}
