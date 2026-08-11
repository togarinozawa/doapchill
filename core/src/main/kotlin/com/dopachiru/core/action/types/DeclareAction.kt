package com.dopachiru.core.action.types

import com.dopachiru.core.action.ActionType
import com.dopachiru.core.param.ParamSpec
import com.dopachiru.core.param.Params

/**
 * 開く前に持ち時間を宣言させる。
 *
 * dopa.md の「アプリをひらいたときに自分でどんな制限を立てれるか決めれる」に対応する。
 * 宣言が済んでいて残り時間があるあいだは通し、使い切った時点で封印に切り替わる。
 */
object DeclareAction : ActionType {
    const val KEY_MAX_MINUTES = "maxMinutes"
    const val KEY_DEFAULT_MINUTES = "defaultMinutes"
    const val KEY_REQUIRE_REASON = "requireReason"
    const val KEY_REFLECTION = "reflection"

    override val id = "declare"
    override val displayName = "開く前に宣言させる"
    override val description = "アプリを開いた瞬間に「今回は何分使うか」を申告させる。使い切ったら封印になる。"
    override val severity = 50

    override val params = listOf(
        ParamSpec.IntParam(KEY_MAX_MINUTES, "宣言できる上限", default = 30, min = 1, max = 240, unit = "分"),
        ParamSpec.IntParam(KEY_DEFAULT_MINUTES, "初期値", default = 10, min = 1, max = 240, unit = "分"),
        ParamSpec.BoolParam(
            KEY_REQUIRE_REASON,
            "使う理由も書かせる",
            default = false,
            help = "書いた理由はダッシュボードの振り返りに残る",
        ),
        ParamSpec.TextParam(
            KEY_REFLECTION,
            "使い切ったときの文",
            default = "宣言した時間は終わり。",
            multiline = true,
        ),
    )

    override fun summarize(p: Params): String =
        "宣言制(最大${p.int(KEY_MAX_MINUTES, 30)}分)"
}
